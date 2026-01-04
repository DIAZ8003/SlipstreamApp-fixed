package net.typeblob.socks
import net.typeblob.socks.AppLogger

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class CommandResult(val exitCode: Int, val output: String)

class CommandService : LifecycleService(), CoroutineScope {

    private val job = SupervisorJob()
    override val coroutineContext: CoroutineContext = job + Dispatchers.IO

    private val TAG = "CommandService"
    private val NOTIFICATION_CHANNEL_ID = "CommandServiceChannel"
    private val NOTIFICATION_ID = 101

    private val mainHandler = Handler(Looper.getMainLooper())

    private var slipstreamProcess: Process? = null
    private var proxyProcess: Process? = null
    private var slipstreamReaderJob: Job? = null
    private var proxyReaderJob: Job? = null
    private var tunnelMonitorJob: Job? = null
    private var mainExecutionJob: Job? = null

    private val tunnelMutex = Mutex()

    private var resolversConfig: ArrayList<String> = arrayListOf()
    private var domainNameConfig: String = ""
    private var privateKeyPath: String = ""
    private var isRestarting = false

    companion object {
        const val EXTRA_RESOLVERS = "extra_ip_addresses_list"
        const val EXTRA_DOMAIN = "domain_name"
        const val EXTRA_KEY_PATH = "private_key_path"

        // ⚠️ IMPORTANTE: binarios nativos empaquetados en jniLibs
        const val SLIPSTREAM_BINARY_NAME = "libslipstream.so"
        const val PROXY_CLIENT_BINARY_NAME = "libproxy.so"

        const val ACTION_STATUS_UPDATE = "net.typeblob.socks.STATUS_UPDATE"
        const val ACTION_ERROR = "net.typeblob.socks.ERROR"
        const val ACTION_REQUEST_STATUS = "net.typeblob.socks.REQUEST_STATUS"

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
        AppLogger.log("onStartCommand called")
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_REQUEST_STATUS) {
            sendCurrentStatus()
            return START_STICKY
        }

        resolversConfig = intent?.getStringArrayListExtra(EXTRA_RESOLVERS) ?: arrayListOf()
        domainNameConfig = intent?.getStringExtra(EXTRA_DOMAIN) ?: ""
        privateKeyPath = intent?.getStringExtra(EXTRA_KEY_PATH) ?: ""

        startForeground(NOTIFICATION_ID, buildForegroundNotification())

        mainExecutionJob?.cancel()
        mainExecutionJob = launch {
            startTunnelSequence()
        }

        return START_STICKY
    }

    private fun sendCurrentStatus() {
        sendStatusUpdate(
            if (slipstreamProcess?.isAlive == true) "Running" else "Stopped",
            if (proxyProcess?.isAlive == true) "Running" else "Stopped"
        )
    }

    private suspend fun startTunnelSequence() {
        tunnelMutex.withLock {
            isRestarting = true
            try {
                stopBackgroundProcesses()
                cleanUpLingeringProcesses()

                val slipstreamPath = getNativeBinaryPath(SLIPSTREAM_BINARY_NAME)
                val proxyPath = getNativeBinaryPath(PROXY_CLIENT_BINARY_NAME)

                if (slipstreamPath == null || proxyPath == null) {
                    sendErrorMessage("Native binaries not found")
                    stopSelf()
                    return
                }

                if (privateKeyPath.isNotEmpty()) {
                    try {
                        Runtime.getRuntime()
                            .exec(arrayOf("chmod", "600", privateKeyPath))
                            .waitFor()
                    } catch (e: Exception) {
                        Log.e(TAG, "chmod failed: ${e.message}")
                    }
                }

                val success = executeCommands(slipstreamPath, proxyPath)
                if (success) {
                    tunnelMonitorJob = launch { startTunnelMonitor() }
                } else {
                    stopSelf()
                }
            } finally {
                isRestarting = false
            }
        }
    }

    private fun getNativeBinaryPath(name: String): String? {
        val libDir = applicationInfo.nativeLibraryDir ?: return null
        val file = File(libDir, name)
        return if (file.exists()) file.absolutePath else null
    }

    private suspend fun startTunnelMonitor() {
        while (isActive) {
            delay(MONITOR_INTERVAL_MS)
            if (slipstreamProcess?.isAlive != true || proxyProcess?.isAlive != true) {
                if (!isRestarting) {
                    startTunnelSequence()
                    break
                }
            } else {
                sendStatusUpdate("Running", "Running")
            }
        }
    }

    private suspend fun executeCommands(
        slipstreamPath: String,
        proxyPath: String
    ): Boolean {

        val slipCommand = mutableListOf(
            slipstreamPath,
            "--domain=$domainNameConfig"
        )

        resolversConfig.forEach {
            slipCommand.add("--resolver=${if (it.contains(":")) it else "$it:53"}")
        }

        val slipProcess = ProcessBuilder(slipCommand)
            .redirectErrorStream(true)
            .start()

        slipstreamProcess = slipProcess
        slipstreamReaderJob = launch { readProcessOutput(slipProcess, "slipstream") }

        delay(1500)

        val proxyCommand = listOf(
            proxyPath,
            privateKeyPath,
            "127.0.0.1:5201",
            "127.0.0.1:3080"
        )

        val proxyProc = ProcessBuilder(proxyCommand)
            .redirectErrorStream(true)
            .start()

        proxyProcess = proxyProc
        proxyReaderJob = launch { readProcessOutput(proxyProc, "proxy") }

        return true
    }

    private suspend fun readProcessOutput(process: Process, tag: String) {
        withContext(Dispatchers.IO) {
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            while (isActive && process.isAlive) {
                val line = reader.readLine() ?: break
                Log.d(TAG, "$tag: $line")
            }
        }
    }

    private fun sendErrorMessage(msg: String) {
        AppLogger.log("ERROR: " + msg)
        val intent = Intent(ACTION_ERROR)
        intent.putExtra(EXTRA_ERROR_MESSAGE, msg)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun sendStatusUpdate(slip: String, ssh: String) {
        val intent = Intent(ACTION_STATUS_UPDATE)
        intent.putExtra(EXTRA_STATUS_SLIPSTREAM, slip)
        intent.putExtra(EXTRA_STATUS_SSH, ssh)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Tunnel Service",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Slipstream Tunnel")
            .setContentText("Running")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun cleanUpLingeringProcesses() {
        try {
            Runtime.getRuntime().exec(arrayOf("killall", "-9", "libslipstream.so"))
            Runtime.getRuntime().exec(arrayOf("killall", "-9", "libproxy.so"))
        } catch (_: Exception) {}
    }

    private fun stopBackgroundProcesses() {
        slipstreamProcess?.destroy()
        proxyProcess?.destroy()
        slipstreamProcess = null
        proxyProcess = null
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onDestroy() {
        job.cancel()
        stopBackgroundProcesses()
        super.onDestroy()
    }
}

