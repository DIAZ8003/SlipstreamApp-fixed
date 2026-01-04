package net.typeblob.socks

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
        const val SLIPSTREAM_BINARY_NAME = "slipstream-client"
        const val PROXY_CLIENT_BINARY_NAME = "proxy-client"
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
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_REQUEST_STATUS) {
            sendCurrentStatus("Request")
            return START_STICKY
        }

        val newResolvers = intent?.getStringArrayListExtra(EXTRA_RESOLVERS) ?: arrayListOf()
        val newDomain = intent?.getStringExtra(EXTRA_DOMAIN) ?: ""
        val newPrivateKeyPath = intent?.getStringExtra(EXTRA_KEY_PATH) ?: ""

        if (newResolvers == resolversConfig &&
            newDomain == domainNameConfig &&
            slipstreamProcess?.isAlive == true
        ) {
            Log.d(TAG, "Profile unchanged and alive. Skipping.")
            return START_STICKY
        }

        resolversConfig = newResolvers
        domainNameConfig = newDomain
        privateKeyPath = newPrivateKeyPath

        Log.d(TAG, "Service starting/updating profile. Domain: $domainNameConfig")
        startForeground(NOTIFICATION_ID, buildForegroundNotification())

        mainExecutionJob?.cancel()
        mainExecutionJob = launch {
            try {
                startTunnelSequence(resolversConfig, domainNameConfig)
            } catch (e: CancellationException) {
                Log.d(TAG, "Startup job cancelled")
            }
        }

        return START_STICKY
    }

    private fun sendCurrentStatus(logTag: String) {
        val sAlive = slipstreamProcess?.isAlive == true
        val proxyAlive = proxyProcess?.isAlive == true
        sendStatusUpdate(
            if (sAlive) "Running" else "Stopped",
            if (proxyAlive) "Running" else "Stopped"
        )
    }

    private suspend fun startTunnelSequence(resolvers: ArrayList<String>, domainName: String) {
        tunnelMutex.withLock {
            isRestarting = true
            try {
                stopBackgroundProcesses()
                cleanUpLingeringProcesses()

                val slipstreamPath = getNativeBinaryPath(SLIPSTREAM_BINARY_NAME)
                val proxyPath = getNativeBinaryPath(PROXY_CLIENT_BINARY_NAME)

                if (!File(slipstreamPath).exists() || !File(proxyPath).exists()) {
                    sendErrorMessage("Native binaries not found")
                    return
                }

                val success = executeCommands(slipstreamPath, proxyPath, resolvers, domainName)
                if (success && isActive) {
                    tunnelMonitorJob = launch { startTunnelMonitor() }
                }
            } finally {
                isRestarting = false
            }
        }
    }

    private fun getNativeBinaryPath(name: String): String {
        return "${applicationInfo.nativeLibraryDir}/$name"
    }

    private suspend fun startTunnelMonitor() {
        while (isActive) {
            delay(MONITOR_INTERVAL_MS)
            if (slipstreamProcess?.isAlive != true || proxyProcess?.isAlive != true) {
                launch { startTunnelSequence(resolversConfig, domainNameConfig) }
                break
            }
        }
    }

    private suspend fun executeCommands(
        slipstreamPath: String,
        proxyPath: String,
        resolvers: ArrayList<String>,
        domainName: String
    ): Boolean {

        val slipCommand = mutableListOf(
            slipstreamPath,
            "--congestion-control=bbr",
            "--domain=$domainName"
        )

        resolvers.forEach {
            slipCommand.add("--resolver=${if (it.contains(":")) it else "$it:53"}")
        }

        val slipResult = startProcessWithOutputCheck(
            slipCommand,
            SLIPSTREAM_BINARY_NAME,
            5000L,
            "Connection confirmed."
        )

        slipstreamProcess = slipResult.second

        if (!slipResult.first.contains("Connection confirmed.")) {
            sendErrorMessage("Slipstream failed")
            return false
        }

        val proxyCommand = listOf(
            proxyPath,
            privateKeyPath,
            "127.0.0.1:5201",
            "127.0.0.1:3080"
        )

        val proxyResult = startProcessWithOutputCheck(
            proxyCommand,
            PROXY_CLIENT_BINARY_NAME,
            1500L,
            null
        )

        proxyProcess = proxyResult.second
        return proxyProcess?.isAlive == true
    }

    private suspend fun startProcessWithOutputCheck(
        command: List<String>,
        logTag: String,
        timeout: Long,
        successMsg: String?
    ): Pair<String, Process> {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        val output = StringBuilder()

        withTimeoutOrNull(timeout) {
            while (true) {
                val line = reader.readLine() ?: break
                output.append(line)
                if (successMsg != null && line.contains(successMsg)) return@withTimeoutOrNull
            }
        }

        return Pair(output.toString(), process)
    }

    private fun sendErrorMessage(msg: String) {
        val intent = Intent(ACTION_ERROR).apply {
            putExtra(EXTRA_ERROR_MESSAGE, msg)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Tunnel Service",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(Context.NOTIFICATION_SERVICE)
                .let { it as NotificationManager }
                .createNotificationChannel(chan)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Tunnel Service")
            .setContentText("Active")
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

    private fun cleanUpLingeringProcesses() {
        try {
            Runtime.getRuntime().exec(arrayOf("killall", "-9", SLIPSTREAM_BINARY_NAME))
            Runtime.getRuntime().exec(arrayOf("killall", "-9", PROXY_CLIENT_BINARY_NAME))
        } catch (_: Exception) {}
    }

    private fun stopBackgroundProcesses() {
        slipstreamProcess?.destroy()
        proxyProcess?.destroy()
        slipstreamProcess = null
        proxyProcess = null
    }

    override fun onBind(intent: Intent): IBinder? = null
}
