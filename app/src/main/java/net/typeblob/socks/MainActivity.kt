package net.typeblob.socks

import android.content.*
import android.graphics.Color
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import net.typeblob.socks.socks.CommandService
import net.typeblob.socks.socks.util.AppLogger
import net.typeblob.socks.socks.util.Utility
import java.io.File

class MainActivity : AppCompatActivity() {

    /* =========================
     * Termux Slipstream Control
     * ========================= */

    private fun writeSlipstreamControl(
        action: String,
        resolver: String,
        domain: String
    ) {
        try {
            val baseDir =
                File("/data/data/com.termux/files/home/.termux/slipstream")
            if (!baseDir.exists()) baseDir.mkdirs()

            val controlFile = File(baseDir, "control.json")

            val json = """
                {
                  "action": "$action",
                  "resolver": "$resolver",
                  "domain": "$domain"
                }
            """.trimIndent()

            controlFile.writeText(json)
            AppLogger.log("Slipstream control.json written: $json")
        } catch (e: Exception) {
            AppLogger.log("Slipstream control write failed: ${e.message}")
        }
    }

    private fun startTermuxSlipstream() {
        try {
            val intent = Intent("com.termux.RUN_COMMAND").apply {
                setClassName(
                    "com.termux",
                    "com.termux.app.RunCommandService"
                )
                putExtra(
                    "com.termux.RUN_COMMAND_PATH",
                    "/data/data/com.termux/files/home/.termux/tasker/slipstream.sh"
                )
                putExtra(
                    "com.termux.RUN_COMMAND_WORKDIR",
                    "/data/data/com.termux/files/home"
                )
                putExtra(
                    "com.termux.RUN_COMMAND_BACKGROUND",
                    true
                )
            }
            startService(intent)
            AppLogger.log("Termux RunCommandService started")
        } catch (e: Exception) {
            AppLogger.log("Failed to start Termux: ${e.message}")
        }
    }

    /* =========================
     * Original Activity Logic
     * ========================= */

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        initUI()
        restoreProfiles()
    }

    private fun startCommandService() {
        val ipList = getIpListFromUI()
        val domainName = domainInput.text.toString().trim()
        val keyPath = keyPathInput.text.toString().trim()

        if (ipList.isEmpty() || domainName.isBlank()) {
            Toast.makeText(this, "Configuration missing", Toast.LENGTH_SHORT).show()
            syncSwitchState(false)
            return
        }

        if (!File(keyPath).exists()) {
            Toast.makeText(this, "Key file not found.", Toast.LENGTH_LONG).show()
            syncSwitchState(false)
            return
        }

        val vpnPrepareIntent = VpnService.prepare(this)
        if (vpnPrepareIntent != null) {
            startActivityForResult(vpnPrepareIntent, 0)
        } else {
            proceedWithStart(ipList, domainName, keyPath, ipList[0])
        }
    }

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK) {
            val ipList = getIpListFromUI()
            val domainName = domainInput.text.toString().trim()
            val keyPath = keyPathInput.text.toString().trim()
            proceedWithStart(ipList, domainName, keyPath, ipList[0])
        } else {
            Toast.makeText(this, "VPN permission denied", Toast.LENGTH_SHORT).show()
            syncSwitchState(false)
        }
    }

    private fun proceedWithStart(
        ipList: ArrayList<String>,
        domainName: String,
        keyPath: String,
        dns: String
    ) {
        val serviceIntent =
            Intent(this, CommandService::class.java).apply {
                putStringArrayListExtra(
                    CommandService.EXTRA_RESOLVERS,
                    ipList
                )
                putExtra(
                    CommandService.EXTRA_DOMAIN,
                    domainName
                )
                putExtra(
                    CommandService.EXTRA_KEY_PATH,
                    keyPath
                )
            }

        ContextCompat.startForegroundService(this, serviceIntent)

        // ===============================
        // Termux Slipstream Integration
        // ===============================
        writeSlipstreamControl("START", dns, domainName)
        startTermuxSlipstream()

        Utility.startVpn(this, dns)
        updateStatusUI("Starting...", "Waiting...")
    }

    /* =========================
     * UI / Status (original)
     * ========================= */

    private fun updateStatusUI(
        slipstreamStatus: String? = null,
        sshStatus: String? = null
    ) {
        slipstreamStatus?.let { status ->
            slipstreamStatusText.text = status
            val color =
                when {
                    status.contains("Running", true) -> Color.GREEN
                    status.contains("Stopped", true) ||
                            status.contains("Failed", true) -> Color.RED
                    else -> Color.YELLOW
                }
            slipstreamStatusIndicator.setTextColor(color)
            slipstreamStatusIndicator.text =
                if (color == Color.GREEN) "�"
                else if (color == Color.RED) "✔❌"
                else ""

            if (status.contains("Running", true)) syncSwitchState(true)
            else if (
                status.contains("Stopped", true) ||
                status.contains("Failed", true)
            ) syncSwitchState(false)
        }

        sshStatus?.let { status ->
            sshStatusText.text = status
            val color =
                if (status.contains("Running", true)) Color.GREEN
                else if (status.contains("Stopped", true)) Color.RED
                else Color.YELLOW
            sshStatusIndicator.setTextColor(color)
            sshStatusIndicator.text =
                if (color == Color.GREEN) "�"
                else if (color == Color.RED) "✔❌"
                else ""
        }
    }

    private fun syncSwitchState(checked: Boolean) {
        if (tunnelSwitch.isChecked != checked) {
            isUpdatingSwitch = true
            tunnelSwitch.isChecked = checked
            isUpdatingSwitch = false
        }
    }

    override fun onDestroy() {
        saveCurrentProfileData()
        LocalBroadcastManager
            .getInstance(this)
            .unregisterReceiver(statusReceiver)
        super.onDestroy()
    }
