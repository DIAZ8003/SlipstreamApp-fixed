package net.typeblob.socks

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.io.File
import kotlinx.coroutines.*

class CommandService : LifecycleService() {

    companion object {
        const val EXTRA_RESOLVERS = "extra_ip_addresses_list"
        const val EXTRA_DOMAIN = "domain_name"
        const val EXTRA_KEY_PATH = "private_key_path"

        const val SLIPSTREAM_BINARY_NAME = "slipstream-client"
        const val PROXY_CLIENT_BINARY_NAME = "proxy-client"

        const val ACTION_STATUS_UPDATE = "net.typeblob.socks.STATUS_UPDATE"
        const val EXTRA_STATUS_SLIPSTREAM = "status_slipstream"
        const val EXTRA_STATUS_SSH = "status_ssh"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var slipProcess: Process? = null
    private var proxyProcess: Process? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resolvers = intent?.getStringArrayListExtra(EXTRA_RESOLVERS) ?: arrayListOf()
        val domain = intent?.getStringExtra(EXTRA_DOMAIN) ?: ""
        val keyPath = intent?.getStringExtra(EXTRA_KEY_PATH) ?: ""

        startForeground(1, buildNotification("Starting"))

        scope.launch {
            startTunnel(resolvers, domain, keyPath)
        }

        return START_STICKY
    }

    private suspend fun startTunnel(
        resolvers: ArrayList<String>,
        domain: String,
        keyPath: String
    ) {
        stopProcesses()

        val slip = copyBinary(SLIPSTREAM_BINARY_NAME)
        val proxy = copyBinary(PROXY_CLIENT_BINARY_NAME)

        if (slip == null || proxy == null) {
            stopSelf()
            return
        }

        if (keyPath.isNotEmpty()) {
            Runtime.getRuntime().exec(arrayOf("chmod", "600", keyPath)).waitFor()
        }

        val cmd = mutableListOf(slip, "--domain=$domain")
        resolvers.forEach { cmd.add("--resolver=$it:53") }

        slipProcess = ProcessBuilder(cmd).redirectErrorStream(true).start()
        proxyProcess = ProcessBuilder(proxy).redirectErrorStream(true).start()

        sendStatus("Running", "Running")
    }

    private fun stopProcesses() {
        slipProcess?.destroy()
        proxyProcess?.destroy()
    }

    private fun sendStatus(slip: String, ssh: String) {
        val intent = Intent(ACTION_STATUS_UPDATE).apply {
            putExtra(EXTRA_STATUS_SLIPSTREAM, slip)
            putExtra(EXTRA_STATUS_SSH, ssh)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun copyBinary(name: String): String? {
        val file = File(codeCacheDir, name)
        return try {
            if (!file.exists()) {
                assets.open(name).use { input ->
                    file.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                file.setReadable(true, false)
                file.setWritable(true, false)
                file.setExecutable(true, false)
            }
            file.absolutePath
        } catch (e: Exception) {
            Log.e("CommandService", "Binary copy failed", e)
            null
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "slipstream",
                "Slipstream",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, "slipstream")
            .setContentTitle("Slipstream Tunnel")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .build()
    }
}
