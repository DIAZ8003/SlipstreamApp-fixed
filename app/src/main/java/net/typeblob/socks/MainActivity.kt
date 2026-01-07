package net.typeblob.socks

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import net.typeblob.socks.socks.CommandService
import net.typeblob.socks.socks.util.Utility
import java.io.File

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        initUI()
// restoreProfiles()  // SAFE MODE
    }

    private fun startCommandService() {
        val ipList = getIpListFromUI()
        val domainName = domainInput.text.toString().trim()
        val keyPath = keyPathInput.text.toString().trim()

        if (ipList.isEmpty() || domainName.isBlank() || !File(keyPath).exists()) {
            Toast.makeText(this, "Complete domain, resolver and key", Toast.LENGTH_LONG).show()
            syncSwitchState(false)
            return
        }

        if (!File(keyPath).exists()) {
            Toast.makeText(this, "Key file not found", Toast.LENGTH_LONG).show()
            syncSwitchState(false)
            return
        }

        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            startActivityForResult(prepareIntent, 0)
        } else {
            proceedStart(ipList, domainName, keyPath, ipList[0])
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
            proceedStart(
                ipList,
                domainInput.text.toString().trim(),
                keyPathInput.text.toString().trim(),
                ipList[0]
            )
        } else {
            Toast.makeText(this, "VPN permission denied", Toast.LENGTH_SHORT).show()
            syncSwitchState(false)
        }
    }

    private fun proceedStart(
        ipList: ArrayList<String>,
        domainName: String,
        keyPath: String,
        dns: String
    ) {
        val serviceIntent = Intent(this, CommandService::class.java).apply {
            putStringArrayListExtra(CommandService.EXTRA_RESOLVERS, ipList)
            putExtra(CommandService.EXTRA_DOMAIN, domainName)
            putExtra(CommandService.EXTRA_KEY_PATH, keyPath)
        }

        Utility.startVpn(this, dns)

        ContextCompat.startForegroundService(this, serviceIntent)
    }

    private fun syncSwitchState(checked: Boolean) {
        if (tunnelSwitch.isChecked != checked) {
            isUpdatingSwitch = true
            tunnelSwitch.isChecked = checked
            isUpdatingSwitch = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
