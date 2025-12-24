package com.example.commandexecutor

import android.app.Notification
import android.app.NotificationChannel
import android.app.PendingIntent
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

data class CommandResult(val exitCode: Int, val output: String)

class CommandService : LifecycleService(), CoroutineScope {

    private val job = SupervisorJob()
    override val coroutineContext: CoroutineContext = job + Dispatchers.IO

    private val TAG = "CommandService"
    private val NOTIFICATION_CHANNEL_ID = "CommandServiceChannel"
    private val NOTIFICATION_ID = 101

    private val mainHandler = Handler(Looper.getMainLooper())

    private var slipstreamProcess: Process? = null
        private var sshProcess: Process? = null
            private var processOutputReaderJob: Job? = null
                private var tunnelMonitorJob: Job? = null
                    private var mainExecutionJob: Job? = null

                        private val tunnelMutex = Mutex()

                        private var resolversConfig: ArrayList<String> = arrayListOf()
                            private var domainNameConfig: String = ""
                                private var isRestarting = false

                                companion object {
                                    const val EXTRA_RESOLVERS = "extra_ip_addresses_list"
                                    const val EXTRA_DOMAIN = "domain_name"
                                    const val BINARY_NAME = "slipstream-client"
                                    const val ACTION_STATUS_UPDATE = "com.example.commandexecutor.STATUS_UPDATE"
                                    const val ACTION_ERROR = "com.example.commandexecutor.ERROR"
                                    const val ACTION_REQUEST_STATUS = "com.example.commandexecutor.REQUEST_STATUS"
                                    const val EXTRA_STATUS_SLIPSTREAM = "status_slipstream"
                                    const val EXTRA_STATUS_SSH = "status_ssh"
                                    const val EXTRA_ERROR_MESSAGE = "error_message"
                                    const val EXTRA_ERROR_OUTPUT = "error_output"
                                    private const val MONITOR_INTERVAL_MS = 2000L
                                }

                                override fun onCreate() {
                                    super.onCreate()
                                    createNotificationChannel()
                                }

                                override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
                                    super.onStartCommand(intent, flags, startId)

                                    if (intent?.action == ACTION_REQUEST_STATUS) {
                                        sendCurrentStatus("Request")
                                        return START_STICKY
                                    }

                                    val newResolvers = intent?.getStringArrayListExtra(EXTRA_RESOLVERS) ?: arrayListOf()
                                    val newDomain = intent?.getStringExtra(EXTRA_DOMAIN) ?: ""

                                    // Only start if config changed or not running
                                    if (newResolvers == resolversConfig && newDomain == domainNameConfig && slipstreamProcess?.isAlive == true) {
                                        Log.d(TAG, "Profile unchanged and alive. Skipping.")
                                        return START_STICKY
                                    }

                                    resolversConfig = newResolvers
                                    domainNameConfig = newDomain

                                    Log.d(TAG, "Service starting/updating profile. Domain: $domainNameConfig")
                                    startForeground(NOTIFICATION_ID, buildForegroundNotification())

                                    // Cancel previous job to start fresh with new profile
                                    mainExecutionJob?.cancel()
                                    mainExecutionJob = launch {
                                        try {
                                            startTunnelSequence(resolversConfig, domainNameConfig)
                                        } catch (e: CancellationException) {
                                            Log.d(TAG, "Startup job cancelled (normal for profile switch)")
                                        }
                                    }

                                    return START_STICKY
                                }

                                private fun sendCurrentStatus(logTag: String) {
                                    val sAlive = slipstreamProcess?.isAlive == true
                                    val sshAlive = sshProcess?.isAlive == true
                                    sendStatusUpdate(if (sAlive) "Running" else "Stopped", if (sshAlive) "Running" else "Stopped")
                                }

                                private suspend fun startTunnelSequence(resolvers: ArrayList<String>, domainName: String) {
                                    tunnelMutex.withLock {
                                        isRestarting = true
                                        try {
                                            sendStatusUpdate("Cleaning up...", "Waiting...")

                                            // Stop monitoring during the change
                                            tunnelMonitorJob?.cancel()
                                            processOutputReaderJob?.cancel()

                                            stopBackgroundProcesses()
                                            cleanUpLingeringProcesses()

                                            val binaryPath = copyBinaryToFilesDir(BINARY_NAME)
                                            if (binaryPath != null) {
                                                val success = executeCommands(binaryPath, resolvers, domainName)
                                                if (success && isActive) {
                                                    tunnelMonitorJob = launch { startTunnelMonitor() }
                                                } else if (isActive) {
                                                    Log.e(TAG, "Failed to start tunnel. Stopping service.")
                                                    stopSelf()
                                                }
                                            }
                                        } finally {
                                            isRestarting = false
                                        }
                                    }
                                }

                                private suspend fun startTunnelMonitor() {
                                    while (isActive) {
                                        delay(MONITOR_INTERVAL_MS)

                                        val slipstreamAlive = slipstreamProcess?.isAlive == true
                                        val sshAlive = sshProcess?.isAlive == true

                                        if (!slipstreamAlive || !sshAlive) {
                                            if (isActive && !isRestarting) {
                                                Log.w(TAG, "Tunnel failure detected. Restarting...")
                                                // Launch restart in a NEW coroutine so it doesn't cancel this monitor loop immediately
                                                launch { startTunnelSequence(resolversConfig, domainNameConfig) }
                                                break // Exit this monitor loop, a new one will be created by the new sequence
                                            }
                                        } else {
                                            sendStatusUpdate("Running", "Running")
                                        }
                                    }
                                }

                                private suspend fun executeCommands(slipstreamPath: String, resolvers: ArrayList<String>, domainName: String): Boolean {
                                    Log.i(TAG, "Starting commands for domain: '$domainName'")

                                    val command1 = mutableListOf(slipstreamPath, "--congestion-control=bbr", "--domain=$domainName")
                                    resolvers.forEach { command1.add("--resolver=${if (it.contains(":")) it else "$it:53"}") }

                                    val slipResult = startProcessWithOutputCheck(command1, BINARY_NAME, 5000L, "Connection confirmed.")
                                    slipstreamProcess = slipResult.second

                                    if (slipResult.first.contains("Connection confirmed.")) {
                                        processOutputReaderJob = launch { readProcessOutput(slipstreamProcess!!, BINARY_NAME) }
                                        sendStatusUpdate("Running", "Starting SSH...")
                                        delay(1500L)

                                        // Better shell command execution for SSH
                                        val sshCmd = "ssh -o ServerAliveInterval=60 -o ServerAliveCountMax=1000 -p 5201 -ND 3080 root@localhost"
                                        val command2 = listOf("su", "-c", sshCmd)

                                        val sshResult = startProcessWithOutputCheck(command2, "ssh", null, null)
                                        sshProcess = sshResult.second

                                        if (sshProcess?.isAlive == true) {
                                            sendStatusUpdate("Running", "Running")
                                            return true
                                        }
                                    }

                                    Log.e(TAG, "Command execution failed.")
                                    return false
                                }

                                private suspend fun startProcessWithOutputCheck(
                                    command: List<String>, logTag: String, timeout: Long?, successMsg: String?
                                ): Pair<String, Process> {
                                    return try {
                                        val process = ProcessBuilder(command).redirectErrorStream(true).start()
                                        val output = StringBuilder()
                                        val reader = BufferedReader(InputStreamReader(process.inputStream))

                                        if (timeout != null) {
                                            withTimeoutOrNull(timeout) {
                                                while (isActive) {
                                                    val line = reader.readLine() ?: break
                                                    output.append(line).append("\n")
                                                    Log.d(TAG, "$logTag: $line")
                                                    if (successMsg != null && line.contains(successMsg)) break
                                                }
                                            }
                                        }
                                        Pair(output.toString().trim(), process)
                                    } catch (e: Exception) {
                                        if (e is CancellationException) throw e
                                            Log.e(TAG, "Error starting $logTag: ${e.message}")
                                            Pair("Error", ProcessBuilder("echo").start())
                                    }
                                }

                                private suspend fun readProcessOutput(process: Process, logTag: String) {
                                    val reader = BufferedReader(InputStreamReader(process.inputStream))
                                    try {
                                        while (isActive && process.isAlive) {
                                            if (reader.ready()) {
                                                val line = reader.readLine() ?: break
                                                Log.d(TAG, "$logTag Live: $line")
                                            } else {
                                                delay(200)
                                            }
                                        }
                                    } catch (e: CancellationException) {
                                        // Normal behavior on stop
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Output Reader Error ($logTag): ${e.message}")
                                    } finally {
                                        try { reader.close() } catch (e: Exception) {}
                                    }
                                }

                                private fun createNotificationChannel() {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        val chan = NotificationChannel(NOTIFICATION_CHANNEL_ID, "Tunnel Service", NotificationManager.IMPORTANCE_LOW)
                                        val service = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                                        service.createNotificationChannel(chan)
                                    }
                                }

                                private fun buildForegroundNotification(): Notification {
                                    val intent = Intent(this, MainActivity::class.java)
                                    val pendingIntent = PendingIntent.getActivity(this, 0, intent,
                                                                                  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)

                                    return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                                    .setContentTitle("Slipstream Active")
                                    .setContentText("Connected to $domainNameConfig")
                                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                                    .setContentIntent(pendingIntent)
                                    .build()
                                }

                                private fun sendStatusUpdate(slipstreamStatus: String, sshStatus: String) {
                                    val intent = Intent(ACTION_STATUS_UPDATE).apply {
                                        putExtra(EXTRA_STATUS_SLIPSTREAM, slipstreamStatus)
                                        putExtra(EXTRA_STATUS_SSH, sshStatus)
                                    }
                                    LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
                                }

                                private fun copyBinaryToFilesDir(name: String): String? {
                                    val file = File(filesDir, name)
                                    return try {
                                        if (!file.exists()) {
                                            assets.open(name).use { input ->
                                                file.outputStream().use { output -> input.copyTo(output) }
                                            }
                                        }
                                        file.setExecutable(true, false)
                                        file.absolutePath
                                    } catch (e: Exception) { null }
                                }

                                private fun cleanUpLingeringProcesses() {
                                    try {
                                        Runtime.getRuntime().exec(arrayOf("su", "-c", "killall -9 $BINARY_NAME")).waitFor()
                                        Runtime.getRuntime().exec(arrayOf("su", "-c", "killall -9 ssh")).waitFor()
                                    } catch (e: Exception) {}
                                }

                                private fun killProcess(p: Process?) {
                                    try {
                                        if (p?.isAlive == true) {
                                            p.destroy()
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                                p.destroyForcibly()
                                            }
                                        }
                                    } catch (e: Exception) {}
                                }

                                private fun stopBackgroundProcesses() {
                                    killProcess(sshProcess)
                                    killProcess(slipstreamProcess)
                                    sshProcess = null
                                    slipstreamProcess = null
                                }

                                override fun onBind(intent: Intent): IBinder? {
                                    super.onBind(intent)
                                    return null
                                }

                                override fun onDestroy() {
                                    Log.d(TAG, "Service destroyed.")
                                    mainExecutionJob?.cancel()
                                    job.cancel()
                                    mainHandler.removeCallbacksAndMessages(null)
                                    stopBackgroundProcesses()
                                    super.onDestroy()
                                }
}
