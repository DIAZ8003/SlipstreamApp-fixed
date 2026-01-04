package net.typeblob.socks
import net.typeblob.socks.AppLogger
import net.typeblob.socks.AppLogger
import net.typeblob.socks.AppLogger

import net.typeblob.socks.AppLogger
import android.app.Notification
import net.typeblob.socks.AppLogger
import android.app.NotificationChannel
import net.typeblob.socks.AppLogger
import android.app.NotificationManager
import net.typeblob.socks.AppLogger
import android.app.PendingIntent
import net.typeblob.socks.AppLogger
import android.content.Context
import net.typeblob.socks.AppLogger
import android.content.Intent
import net.typeblob.socks.AppLogger
import android.os.Build
import net.typeblob.socks.AppLogger
import android.os.Handler
import net.typeblob.socks.AppLogger
import android.os.IBinder
import net.typeblob.socks.AppLogger
import android.os.Looper
import net.typeblob.socks.AppLogger
import android.util.Log
import net.typeblob.socks.AppLogger
import androidx.core.app.NotificationCompat
import net.typeblob.socks.AppLogger
import androidx.lifecycle.LifecycleService
import net.typeblob.socks.AppLogger
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import net.typeblob.socks.AppLogger
import java.io.BufferedReader
import net.typeblob.socks.AppLogger
import java.io.File
import net.typeblob.socks.AppLogger
import java.io.InputStreamReader
import net.typeblob.socks.AppLogger
import kotlin.coroutines.CoroutineContext
import net.typeblob.socks.AppLogger
import kotlinx.coroutines.*
import net.typeblob.socks.AppLogger
import kotlinx.coroutines.sync.Mutex
import net.typeblob.socks.AppLogger
import kotlinx.coroutines.sync.withLock
import net.typeblob.socks.AppLogger

import net.typeblob.socks.AppLogger
data class CommandResult(val exitCode: Int, val output: String)
import net.typeblob.socks.AppLogger

import net.typeblob.socks.AppLogger
class CommandService : LifecycleService(), CoroutineScope {
import net.typeblob.socks.AppLogger

import net.typeblob.socks.AppLogger
    private val job = SupervisorJob()
import net.typeblob.socks.AppLogger
    override val coroutineContext: CoroutineContext = job + Dispatchers.IO
import net.typeblob.socks.AppLogger

import net.typeblob.socks.AppLogger
    private val TAG = "CommandService"
import net.typeblob.socks.AppLogger
    private val NOTIFICATION_CHANNEL_ID = "CommandServiceChannel"
import net.typeblob.socks.AppLogger
    private val NOTIFICATION_ID = 101
import net.typeblob.socks.AppLogger

import net.typeblob.socks.AppLogger
    private val mainHandler = Handler(Looper.getMainLooper())
import net.typeblob.socks.AppLogger

import net.typeblob.socks.AppLogger
    private var slipstreamProcess: Process? = null
import net.typeblob.socks.AppLogger
    private var proxyProcess: Process? = null
import net.typeblob.socks.AppLogger
    private var slipstreamReaderJob: Job? = null
import net.typeblob.socks.AppLogger
    private var proxyReaderJob: Job? = null
import net.typeblob.socks.AppLogger
    private var tunnelMonitorJob: Job? = null
import net.typeblob.socks.AppLogger
    private var mainExecutionJob: Job? = null
import net.typeblob.socks.AppLogger

import net.typeblob.socks.AppLogger
    private val tunnelMutex = Mutex()
import net.typeblob.socks.AppLogger

import net.typeblob.socks.AppLogger
    private var resolversConfig: ArrayList<String> = arrayListOf()
import net.typeblob.socks.AppLogger
    private var domainNameConfig: String = ""
import net.typeblob.socks.AppLogger
    private var privateKeyPath: String = ""
import net.typeblob.socks.AppLogger
    private var isRestarting = false
import net.typeblob.socks.AppLogger

import net.typeblob.socks.AppLogger
    companion object {
import net.typeblob.socks.AppLogger
        const val EXTRA_RESOLVERS = "extra_ip_addresses_list"
import net.typeblob.socks.AppLogger
        const val EXTRA_DOMAIN = "domain_name"
import net.typeblob.socks.AppLogger
        const val EXTRA_KEY_PATH = "private_key_path"
import net.typeblob.socks.AppLogger

import net.typeblob.socks.AppLogger
        // ⚠️ IMPORTANTE: binarios nativos empaquetados en jniLibs
import net.typeblob.socks.AppLogger
        const val SLIPSTREAM_BINARY_NAME = "libslipstream.so"
import net.typeblob.socks.AppLogger
        const val PROXY_CLIENT_BINARY_NAME = "libproxy.so"
import net.typeblob.socks.AppLogger

import net.typeblob.socks.AppLogger
        const val ACTION_STATUS_UPDATE = "net.typeblob.socks.STATUS_UPDATE"
import net.typeblob.socks.AppLogger
        const val ACTION_ERROR = "net.typeblob.socks.ERROR"
import net.typeblob.socks.AppLogger
        const val ACTION_REQUEST_STATUS = "net.typeblob.socks.REQUEST_STATUS"
import net.typeblob.socks.AppLogger

import net.typeblob.socks.AppLogger
        const val EXTRA_STATUS_SLIPSTREAM = "status_slipstream"
import net.typeblob.socks.AppLogger
        const val EXTRA_STATUS_SSH = "status_ssh"
import net.typeblob.socks.AppLogger
        const val EXTRA_ERROR_MESSAGE = "error_message"
import net.typeblob.socks.AppLogger
        const val EXTRA_ERROR_OUTPUT = "error_output"
import net.typeblob.socks.AppLogger

import net.typeblob.socks.AppLogger
        private const val MONITOR_INTERVAL_MS = 2000L
import net.typeblob.socks.AppLogger
    }
import net.typeblob.socks.AppLogger

import net.typeblob.socks.AppLogger
    override fun onCreate() {
import net.typeblob.socks.AppLogger
        super.onCreate()
import net.typeblob.socks.AppLogger
        createNotificationChannel()
import net.typeblob.socks.AppLogger
    }
import net.typeblob.socks.AppLogger

import net.typeblob.socks.AppLogger
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLogger.log("CommandService started")
import net.typeblob.socks.AppLogger
        AppLogger.log("onStartCommand called")
import net.typeblob.socks.AppLogger
        super.onStartCommand(intent, flags, startId)
        AppLogger.log("CommandService started")
import net.typeblob.socks.AppLogger

import net.typeblob.socks.AppLogger
        if (intent?.action == ACTION_REQUEST_STATUS) {
import net.typeblob.socks.AppLogger
            sendCurrentStatus()
import net.typeblob.socks.AppLogger
            return START_STICKY
import net.typeblob.socks.AppLogger
        }
import net.typeblob.socks.AppLogger

import net.typeblob.socks.AppLogger
        resolversConfig = intent?.getStringArrayListExtra(EXTRA_RESOLVERS) ?: arrayListOf()
import net.typeblob.socks.AppLogger
        domainNameConfig = intent?.getStringExtra(EXTRA_DOMAIN) ?: ""
import net.typeblob.socks.AppLogger
        privateKeyPath = intent?.getStringExtra(EXTRA_KEY_PATH) ?: ""
import net.typeblob.socks.AppLogger

import net.typeblob.socks.AppLogger
        startForeground(NOTIFICATION_ID, buildForegroundNotification())
import net.typeblob.socks.AppLogger

import net.typeblob.socks.AppLogger
        mainExecutionJob?.cancel()
import net.typeblob.socks.AppLogger
        mainExecutionJob = launch {
import net.typeblob.socks.AppLogger
            startTunnelSequence()
import net.typeblob.socks.AppLogger
        }
import net.typeblob.socks.AppLogger

import net.typeblob.socks.AppLogger
        return START_STICKY
import net.typeblob.socks.AppLogger
    }
import net.typeblob.socks.AppLogger

import net.typeblob.socks.AppLogger
    private fun sendCurrentStatus() {
import net.typeblob.socks.AppLogger
        sendStatusUpdate(
import net.typeblob.socks.AppLogger
            if (slipstreamProcess?.isAlive == true) "Running" else "Stopped",
import net.typeblob.socks.AppLogger
            if (proxyProcess?.isAlive == true) "Running" else "Stopped"
import net.typeblob.socks.AppLogger
        )
import net.typeblob.socks.AppLogger
    }
import net.typeblob.socks.AppLogger

import net.typeblob.socks.AppLogger
    private suspend fun startTunnelSequence() {
import net.typeblob.socks.AppLogger
        tunnelMutex.withLock {
import net.typeblob.socks.AppLogger
            isRestarting = true
import net.typeblob.socks.AppLogger
            try {
import net.typeblob.socks.AppLogger
                stopBackgroundProcesses()
import net.typeblob.socks.AppLogger
                cleanUpLingeringProcesses()
import net.typeblob.socks.AppLogger

import net.typeblob.socks.AppLogger
                val slipstreamPath = getNativeBinaryPath(SLIPSTREAM_BINARY_NAME)
import net.typeblob.socks.AppLogger
                val proxyPath = getNativeBinaryPath(PROXY_CLIENT_BINARY_NAME)
import net.typeblob.socks.AppLogger

import net.typeblob.socks.AppLogger
                if (slipstreamPath == null || proxyPath == null) {
import net.typeblob.socks.AppLogger
                    sendErrorMessage("Native binaries not found")
import net.typeblob.socks.AppLogger
                    stopSelf()
import net.typeblob.socks.AppLogger
                    return
import net.typeblob.socks.AppLogger
                }
import net.typeblob.socks.AppLogger

import net.typeblob.socks.AppLogger
                if (privateKeyPath.isNotEmpty()) {
import net.typeblob.socks.AppLogger
                    try {
import net.typeblob.socks.AppLogger
                        Runtime.getRuntime()
import net.typeblob.socks.AppLogger
                            .exec(arrayOf("chmod", "600", privateKeyPath))
import net.typeblob.socks.AppLogger
                            .waitFor()
import net.typeblob.socks.AppLogger
                    } catch (e: Exception) {
import net.typeblob.socks.AppLogger
                        Log.e(TAG, "chmod failed: ${e.message}")
import net.typeblob.socks.AppLogger
                    }
import net.typeblob.socks.AppLogger
                }
import net.typeblob.socks.AppLogger

import net.typeblob.socks.AppLogger
                val success = executeCommands(slipstreamPath, proxyPath)
import net.typeblob.socks.AppLogger
                if (success) {
import net.typeblob.socks.AppLogger
                    tunnelMonitorJob = launch { startTunnelMonitor() }
import net.typeblob.socks.AppLogger
                } else {
import net.typeblob.socks.AppLogger
                    stopSelf()
import net.typeblob.socks.AppLogger
                }
import net.typeblob.socks.AppLogger
            } finally {
import net.typeblob.socks.AppLogger
                isRestarting = false
import net.typeblob.socks.AppLogger
            }
import net.typeblob.socks.AppLogger
        }
import net.typeblob.socks.AppLogger
    }
import net.typeblob.socks.AppLogger

import net.typeblob.socks.AppLogger
    private fun getNativeBinaryPath(name: String): String? {
import net.typeblob.socks.AppLogger
        val libDir = applicationInfo.nativeLibraryDir ?: return null
import net.typeblob.socks.AppLogger
        val file = File(libDir, name)
import net.typeblob.socks.AppLogger
        return if (file.exists()) file.absolutePath else null
import net.typeblob.socks.AppLogger
    }
import net.typeblob.socks.AppLogger

import net.typeblob.socks.AppLogger
    private suspend fun startTunnelMonitor() {
import net.typeblob.socks.AppLogger
        while (isActive) {
import net.typeblob.socks.AppLogger
            delay(MONITOR_INTERVAL_MS)
import net.typeblob.socks.AppLogger
            if (slipstreamProcess?.isAlive != true || proxyProcess?.isAlive != true) {
import net.typeblob.socks.AppLogger
                if (!isRestarting) {
import net.typeblob.socks.AppLogger
                    startTunnelSequence()
import net.typeblob.socks.AppLogger
                    break
import net.typeblob.socks.AppLogger
                }
import net.typeblob.socks.AppLogger
            } else {
import net.typeblob.socks.AppLogger
                sendStatusUpdate("Running", "Running")
import net.typeblob.socks.AppLogger
            }
import net.typeblob.socks.AppLogger
        }
import net.typeblob.socks.AppLogger
    }
import net.typeblob.socks.AppLogger

import net.typeblob.socks.AppLogger
    private suspend fun executeCommands(
import net.typeblob.socks.AppLogger
        slipstreamPath: String,
import net.typeblob.socks.AppLogger
        proxyPath: String
import net.typeblob.socks.AppLogger
    ): Boolean {
import net.typeblob.socks.AppLogger

import net.typeblob.socks.AppLogger
        val slipCommand = mutableListOf(
import net.typeblob.socks.AppLogger
            slipstreamPath,
import net.typeblob.socks.AppLogger
            "--domain=$domainNameConfig"
import net.typeblob.socks.AppLogger
        )
import net.typeblob.socks.AppLogger

import net.typeblob.socks.AppLogger
        resolversConfig.forEach {
import net.typeblob.socks.AppLogger
            slipCommand.add("--resolver=${if (it.contains(":")) it else "$it:53"}")
import net.typeblob.socks.AppLogger
        }
import net.typeblob.socks.AppLogger

import net.typeblob.socks.AppLogger
        val slipProcess = ProcessBuilder(slipCommand)
import net.typeblob.socks.AppLogger
            .redirectErrorStream(true)
import net.typeblob.socks.AppLogger
            .start()
import net.typeblob.socks.AppLogger

import net.typeblob.socks.AppLogger
        slipstreamProcess = slipProcess
import net.typeblob.socks.AppLogger
        slipstreamReaderJob = launch { readProcessOutput(slipProcess, "slipstream") }
import net.typeblob.socks.AppLogger

import net.typeblob.socks.AppLogger
        delay(1500)
import net.typeblob.socks.AppLogger

import net.typeblob.socks.AppLogger
        val proxyCommand = listOf(
import net.typeblob.socks.AppLogger
            proxyPath,
import net.typeblob.socks.AppLogger
            privateKeyPath,
import net.typeblob.socks.AppLogger
            "127.0.0.1:5201",
import net.typeblob.socks.AppLogger
            "127.0.0.1:3080"
import net.typeblob.socks.AppLogger
        )
import net.typeblob.socks.AppLogger

import net.typeblob.socks.AppLogger
        val proxyProc = ProcessBuilder(proxyCommand)
import net.typeblob.socks.AppLogger
            .redirectErrorStream(true)
import net.typeblob.socks.AppLogger
            .start()
import net.typeblob.socks.AppLogger

import net.typeblob.socks.AppLogger
        proxyProcess = proxyProc
import net.typeblob.socks.AppLogger
        proxyReaderJob = launch { readProcessOutput(proxyProc, "proxy") }
import net.typeblob.socks.AppLogger

import net.typeblob.socks.AppLogger
        return true
import net.typeblob.socks.AppLogger
    }
import net.typeblob.socks.AppLogger

import net.typeblob.socks.AppLogger
    private suspend fun readProcessOutput(process: Process, tag: String) {
import net.typeblob.socks.AppLogger
        withContext(Dispatchers.IO) {
import net.typeblob.socks.AppLogger
            val reader = BufferedReader(InputStreamReader(process.inputStream))
import net.typeblob.socks.AppLogger
            while (isActive && process.isAlive) {
import net.typeblob.socks.AppLogger
                val line = reader.readLine() ?: break
import net.typeblob.socks.AppLogger
                Log.d(TAG, "$tag: $line")
import net.typeblob.socks.AppLogger
            }
import net.typeblob.socks.AppLogger
        }
import net.typeblob.socks.AppLogger
    }
import net.typeblob.socks.AppLogger

import net.typeblob.socks.AppLogger
    private fun sendErrorMessage(msg: String) {
import net.typeblob.socks.AppLogger
        AppLogger.log("ERROR: " + msg)
import net.typeblob.socks.AppLogger
        val intent = Intent(ACTION_ERROR)
import net.typeblob.socks.AppLogger
        intent.putExtra(EXTRA_ERROR_MESSAGE, msg)
import net.typeblob.socks.AppLogger
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
import net.typeblob.socks.AppLogger
    }
import net.typeblob.socks.AppLogger

import net.typeblob.socks.AppLogger
    private fun sendStatusUpdate(slip: String, ssh: String) {
import net.typeblob.socks.AppLogger
        val intent = Intent(ACTION_STATUS_UPDATE)
import net.typeblob.socks.AppLogger
        intent.putExtra(EXTRA_STATUS_SLIPSTREAM, slip)
import net.typeblob.socks.AppLogger
        intent.putExtra(EXTRA_STATUS_SSH, ssh)
import net.typeblob.socks.AppLogger
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
import net.typeblob.socks.AppLogger
    }
import net.typeblob.socks.AppLogger

import net.typeblob.socks.AppLogger
    private fun createNotificationChannel() {
import net.typeblob.socks.AppLogger
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
import net.typeblob.socks.AppLogger
            val channel = NotificationChannel(
import net.typeblob.socks.AppLogger
                NOTIFICATION_CHANNEL_ID,
import net.typeblob.socks.AppLogger
                "Tunnel Service",
import net.typeblob.socks.AppLogger
                NotificationManager.IMPORTANCE_LOW
import net.typeblob.socks.AppLogger
            )
import net.typeblob.socks.AppLogger
            getSystemService(NotificationManager::class.java)
import net.typeblob.socks.AppLogger
                .createNotificationChannel(channel)
import net.typeblob.socks.AppLogger
        }
import net.typeblob.socks.AppLogger
    }
import net.typeblob.socks.AppLogger

import net.typeblob.socks.AppLogger
    private fun buildForegroundNotification(): Notification {
import net.typeblob.socks.AppLogger
        val intent = Intent(this, MainActivity::class.java)
import net.typeblob.socks.AppLogger
        val pendingIntent = PendingIntent.getActivity(
import net.typeblob.socks.AppLogger
            this, 0, intent,
import net.typeblob.socks.AppLogger
            PendingIntent.FLAG_IMMUTABLE
import net.typeblob.socks.AppLogger
        )
import net.typeblob.socks.AppLogger

import net.typeblob.socks.AppLogger
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
import net.typeblob.socks.AppLogger
            .setContentTitle("Slipstream Tunnel")
import net.typeblob.socks.AppLogger
            .setContentText("Running")
import net.typeblob.socks.AppLogger
            .setSmallIcon(android.R.drawable.ic_dialog_info)
import net.typeblob.socks.AppLogger
            .setContentIntent(pendingIntent)
import net.typeblob.socks.AppLogger
            .build()
import net.typeblob.socks.AppLogger
    }
import net.typeblob.socks.AppLogger

import net.typeblob.socks.AppLogger
    private fun cleanUpLingeringProcesses() {
import net.typeblob.socks.AppLogger
        try {
import net.typeblob.socks.AppLogger
            Runtime.getRuntime().exec(arrayOf("killall", "-9", "libslipstream.so"))
import net.typeblob.socks.AppLogger
            Runtime.getRuntime().exec(arrayOf("killall", "-9", "libproxy.so"))
import net.typeblob.socks.AppLogger
        } catch (_: Exception) {}
import net.typeblob.socks.AppLogger
    }
import net.typeblob.socks.AppLogger

import net.typeblob.socks.AppLogger
    private fun stopBackgroundProcesses() {
import net.typeblob.socks.AppLogger
        slipstreamProcess?.destroy()
import net.typeblob.socks.AppLogger
        proxyProcess?.destroy()
import net.typeblob.socks.AppLogger
        slipstreamProcess = null
import net.typeblob.socks.AppLogger
        proxyProcess = null
import net.typeblob.socks.AppLogger
    }
import net.typeblob.socks.AppLogger

import net.typeblob.socks.AppLogger
    override fun onBind(intent: Intent): IBinder? = null
import net.typeblob.socks.AppLogger

import net.typeblob.socks.AppLogger
    override fun onDestroy() {
import net.typeblob.socks.AppLogger
        job.cancel()
import net.typeblob.socks.AppLogger
        stopBackgroundProcesses()
import net.typeblob.socks.AppLogger
        super.onDestroy()
import net.typeblob.socks.AppLogger
    }
import net.typeblob.socks.AppLogger
}
import net.typeblob.socks.AppLogger

import net.typeblob.socks.AppLogger
