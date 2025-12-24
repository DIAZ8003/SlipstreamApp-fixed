package com.example.commandexecutor

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.util.ArrayList

class MainActivity : AppCompatActivity() {

    private lateinit var resolversContainer: LinearLayout
        private lateinit var addResolverButton: Button
            private lateinit var domainInput: EditText
                private lateinit var tunnelSwitch: Switch
                    private lateinit var profileSpinner: Spinner
                        private lateinit var addProfileButton: ImageButton
                            private lateinit var deleteProfileButton: ImageButton

                                private lateinit var slipstreamStatusIndicator: TextView
                                    private lateinit var slipstreamStatusText: TextView
                                        private lateinit var sshStatusIndicator: TextView
                                            private lateinit var sshStatusText: TextView

                                                private lateinit var sharedPreferences: SharedPreferences
                                                    private var isUpdatingSwitch = false
                                                    private var isSwitchingProfile = false

                                                    // FIX: Track the last selected position to save data correctly
                                                    private var lastSelectedPosition: Int = 0

                                                        private val PREF_PROFILES_SET = "pref_profiles_set"
                                                        private val PREF_LAST_PROFILE = "pref_last_profile"
                                                        private val IP_SEPARATOR = "|"

                                                        private var profileList = mutableListOf<String>()
                                                        private lateinit var profileAdapter: ArrayAdapter<String>

                                                            private val statusReceiver = object : BroadcastReceiver() {
                                                                override fun onReceive(context: Context?, intent: Intent?) {
                                                                    when (intent?.action) {
                                                                        CommandService.ACTION_STATUS_UPDATE -> {
                                                                            val slipstreamStatus = intent.getStringExtra(CommandService.EXTRA_STATUS_SLIPSTREAM)
                                                                            val sshStatus = intent.getStringExtra(CommandService.EXTRA_STATUS_SSH)
                                                                            updateStatusUI(slipstreamStatus, sshStatus)
                                                                        }
                                                                        CommandService.ACTION_ERROR -> {
                                                                            val message = intent.getStringExtra(CommandService.EXTRA_ERROR_MESSAGE) ?: "Unknown Error"
                                                                            Toast.makeText(this@MainActivity, "ERROR: $message", Toast.LENGTH_LONG).show()
                                                                            updateStatusUI(slipstreamStatus = "Failed: $message", sshStatus = "Stopped")
                                                                        }
                                                                    }
                                                                }
                                                            }

                                                            private val requestPermissionLauncher = registerForActivityResult(
                                                                ActivityResultContracts.RequestPermission()
                                                            ) { isGranted ->
                                                                if (isGranted && tunnelSwitch.isChecked) startCommandService()
                                                                    else if (!isGranted) syncSwitchState(false)
                                                            }

                                                            override fun onCreate(savedInstanceState: Bundle?) {
                                                                super.onCreate(savedInstanceState)
                                                                setContentView(R.layout.activity_main)

                                                                sharedPreferences = getSharedPreferences("SlipstreamPrefs", Context.MODE_PRIVATE)

                                                                resolversContainer = findViewById(R.id.resolvers_container)
                                                                addResolverButton = findViewById(R.id.add_resolver_button)
                                                                domainInput = findViewById(R.id.domain_input)
                                                                tunnelSwitch = findViewById(R.id.tunnel_switch)
                                                                profileSpinner = findViewById(R.id.profile_spinner)
                                                                addProfileButton = findViewById(R.id.add_profile_button)
                                                                deleteProfileButton = findViewById(R.id.delete_profile_button)

                                                                slipstreamStatusIndicator = findViewById(R.id.slipstream_status_indicator)
                                                                slipstreamStatusText = findViewById(R.id.slipstream_status_text)
                                                                sshStatusIndicator = findViewById(R.id.ssh_status_indicator)
                                                                sshStatusText = findViewById(R.id.ssh_status_text)

                                                                setupProfiles()

                                                                addResolverButton.setOnClickListener { addResolverInput("", true) }
                                                                addProfileButton.setOnClickListener { showAddProfileDialog() }
                                                                deleteProfileButton.setOnClickListener { deleteCurrentProfile() }

                                                                tunnelSwitch.setOnCheckedChangeListener { _, isChecked ->
                                                                    if (isUpdatingSwitch) return@setOnCheckedChangeListener

                                                                        // Save current UI state before starting service
                                                                        val currentProfile = profileSpinner.selectedItem?.toString()
                                                                        if (currentProfile != null) {
                                                                            saveProfileData(currentProfile)
                                                                        }

                                                                        if (isChecked) checkPermissionsAndStartService()
                                                                            else {
                                                                                stopService(Intent(this, CommandService::class.java))
                                                                                updateStatusUI("Stopping...", "Stopping...")
                                                                            }
                                                                }

                                                                val filter = IntentFilter().apply {
                                                                    addAction(CommandService.ACTION_STATUS_UPDATE)
                                                                    addAction(CommandService.ACTION_ERROR)
                                                                }
                                                                LocalBroadcastManager.getInstance(this).registerReceiver(statusReceiver, filter)
                                                            }

                                                            private fun setupProfiles() {
                                                                val savedProfiles = sharedPreferences.getStringSet(PREF_PROFILES_SET, setOf("Default"))?.toMutableList() ?: mutableListOf("Default")
                                                                profileList.clear()
                                                                profileList.addAll(savedProfiles.sorted())

                                                                profileAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, profileList)
                                                                profileAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                                                                profileSpinner.adapter = profileAdapter

                                                                val lastProfile = sharedPreferences.getString(PREF_LAST_PROFILE, "Default")
                                                                val lastIndex = profileList.indexOf(lastProfile).let { if (it == -1) 0 else it }

                                                                lastSelectedPosition = lastIndex
                                                                profileSpinner.setSelection(lastIndex)
                                                                loadProfileData(profileList[lastIndex])

                                                                profileSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                                                                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                                                                        if (isSwitchingProfile) return

                                                                            // FIX: Save the OLD profile's data using the saved index before loading the new one
                                                                            val oldProfileName = profileList[lastSelectedPosition]
                                                                            saveProfileData(oldProfileName)

                                                                            // Now load the new one
                                                                            loadProfileData(profileList[position])
                                                                            lastSelectedPosition = position // Update tracker
                                                                            sharedPreferences.edit().putString(PREF_LAST_PROFILE, profileList[position]).apply()
                                                                    }
                                                                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                                                                }
                                                            }

                                                            private fun loadProfileData(profileName: String) {
                                                                isSwitchingProfile = true
                                                                resolversContainer.removeAllViews()

                                                                // Default values if keys don't exist
                                                                val ips = sharedPreferences.getString("profile_${profileName}_ips", "1.1.1.1")
                                                                val domain = sharedPreferences.getString("profile_${profileName}_domain", "")

                                                                domainInput.setText(domain)
                                                                val ipList = ips?.split(IP_SEPARATOR)?.filter { it.isNotBlank() } ?: listOf("1.1.1.1")

                                                                ipList.forEachIndexed { index, ip -> addResolverInput(ip, index > 0) }
                                                                isSwitchingProfile = false
                                                            }

                                                            // FIX: Pass the specific profile name to save to, rather than relying on current spinner selection
                                                            private fun saveProfileData(profileName: String) {
                                                                val ipList = getIpListFromUI()
                                                                sharedPreferences.edit().apply {
                                                                    putString("profile_${profileName}_ips", ipList.joinToString(IP_SEPARATOR))
                                                                    putString("profile_${profileName}_domain", domainInput.text.toString().trim())
                                                                    apply()
                                                                }
                                                            }

                                                            // This helper saves the current state into the current selection (used for general saving)
                                                            private fun saveCurrentProfileData() {
                                                                val currentProfile = profileSpinner.selectedItem?.toString() ?: return
                                                                saveProfileData(currentProfile)
                                                            }

                                                            private fun showAddProfileDialog() {
                                                                val input = EditText(this).apply {
                                                                    hint = "Profile Name"
                                                                    inputType = InputType.TYPE_CLASS_TEXT
                                                                }
                                                                AlertDialog.Builder(this)
                                                                .setTitle("New Profile")
                                                                .setView(input)
                                                                .setPositiveButton("Create") { _, _ ->
                                                                    val name = input.text.toString().trim()
                                                                    if (name.isNotEmpty() && !profileList.contains(name)) {
                                                                        // FIX: Save the current active profile before adding a new one
                                                                        saveCurrentProfileData()

                                                                        // Initialize the new profile in Prefs with BLANK data immediately
                                                                        // so it doesn't inherit the current UI's text
                                                                        sharedPreferences.edit().apply {
                                                                            putString("profile_${name}_ips", "1.1.1.1")
                                                                            putString("profile_${name}_domain", "")
                                                                            apply()
                                                                        }

                                                                        profileList.add(name)
                                                                        profileList.sort()
                                                                        profileAdapter.notifyDataSetChanged()
                                                                        saveProfilesList()

                                                                        // Switch to the new profile
                                                                        val newIndex = profileList.indexOf(name)
                                                                        profileSpinner.setSelection(newIndex)

                                                                        // Force update the tracker and load the blank data
                                                                        lastSelectedPosition = newIndex
                                                                        loadProfileData(name)
                                                                    }
                                                                }
                                                                .setNegativeButton("Cancel", null)
                                                                .show()
                                                            }

                                                            private fun deleteCurrentProfile() {
                                                                if (profileList.size <= 1) {
                                                                    Toast.makeText(this, "Cannot delete the last profile", Toast.LENGTH_SHORT).show()
                                                                    return
                                                                }
                                                                val currentProfile = profileSpinner.selectedItem.toString()
                                                                AlertDialog.Builder(this)
                                                                .setTitle("Delete Profile")
                                                                .setMessage("Are you sure you want to delete '$currentProfile'?")
                                                                .setPositiveButton("Delete") { _, _ ->
                                                                    profileList.remove(currentProfile)
                                                                    saveProfilesList()
                                                                    profileAdapter.notifyDataSetChanged()

                                                                    // Reset to first profile
                                                                    lastSelectedPosition = 0
                                                                    profileSpinner.setSelection(0)
                                                                    loadProfileData(profileList[0])
                                                                }
                                                                .setNegativeButton("Cancel", null)
                                                                .show()
                                                            }

                                                            private fun saveProfilesList() {
                                                                sharedPreferences.edit().putStringSet(PREF_PROFILES_SET, profileList.toSet()).apply()
                                                            }

                                                            private fun addResolverInput(ip: String, canDelete: Boolean) {
                                                                val rowLayout = LinearLayout(this).apply {
                                                                    orientation = LinearLayout.HORIZONTAL
                                                                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                                                                        setMargins(0, 0, 0, 16)
                                                                    }
                                                                }

                                                                val editText = EditText(this).apply {
                                                                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
                                                                    hint = "Resolver IP"
                                                                    setText(ip)
                                                                }
                                                                rowLayout.addView(editText)

                                                                if (canDelete) {
                                                                    val deleteBtn = Button(this).apply {
                                                                        text = "–"
                                                                        setTextColor(Color.WHITE)
                                                                        setBackgroundColor(Color.RED)
                                                                        setOnClickListener { resolversContainer.removeView(rowLayout) }
                                                                    }
                                                                    rowLayout.addView(deleteBtn)
                                                                }
                                                                resolversContainer.addView(rowLayout)
                                                            }

                                                            private fun getIpListFromUI(): ArrayList<String> {
                                                                val ipList = ArrayList<String>()
                                                                for (i in 0 until resolversContainer.childCount) {
                                                                    val row = resolversContainer.getChildAt(i) as? ViewGroup ?: continue
                                                                    for (j in 0 until row.childCount) {
                                                                        val child = row.getChildAt(j)
                                                                        if (child is EditText) {
                                                                            val text = child.text.toString().trim()
                                                                            if (text.isNotBlank()) ipList.add(text)
                                                                        }
                                                                    }
                                                                }
                                                                return ipList
                                                            }

                                                            private fun checkPermissionsAndStartService() {
                                                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                                                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) startCommandService()
                                                                        else requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                                                } else startCommandService()
                                                            }

                                                            private fun startCommandService() {
                                                                val ipList = getIpListFromUI()
                                                                val domainName = domainInput.text.toString().trim()
                                                                if (ipList.isEmpty() || domainName.isBlank()) {
                                                                    Toast.makeText(this, "Configuration missing", Toast.LENGTH_SHORT).show()
                                                                    syncSwitchState(false)
                                                                    return
                                                                }
                                                                val serviceIntent = Intent(this, CommandService::class.java).apply {
                                                                    putStringArrayListExtra(CommandService.EXTRA_RESOLVERS, ipList)
                                                                    putExtra(CommandService.EXTRA_DOMAIN, domainName)
                                                                }
                                                                ContextCompat.startForegroundService(this, serviceIntent)
                                                                updateStatusUI("Starting...", "Waiting...")
                                                            }

                                                            private fun updateStatusUI(slipstreamStatus: String? = null, sshStatus: String? = null) {
                                                                slipstreamStatus?.let { status ->
                                                                    slipstreamStatusText.text = status
                                                                    val color = when {
                                                                        status.contains("Running", true) -> Color.GREEN
                                                                        status.contains("Stopped", true) || status.contains("Failed", true) -> Color.RED
                                                                        else -> Color.YELLOW
                                                                    }
                                                                    slipstreamStatusIndicator.setTextColor(color)
                                                                    slipstreamStatusIndicator.text = if (color == Color.GREEN) "✔" else if (color == Color.RED) "❌" else "🟡"

                                                                    if (status.contains("Running", true)) syncSwitchState(true)
                                                                        else if (status.contains("Stopped", true) || status.contains("Failed", true)) syncSwitchState(false)
                                                                }

                                                                sshStatus?.let { status ->
                                                                    sshStatusText.text = status
                                                                    val color = if (status.contains("Running", true)) Color.GREEN else if (status.contains("Stopped", true)) Color.RED else Color.YELLOW
                                                                    sshStatusIndicator.setTextColor(color)
                                                                    sshStatusIndicator.text = if (color == Color.GREEN) "✔" else if (color == Color.RED) "❌" else "🟡"
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
                                                                // Save data one last time before app closes
                                                                saveCurrentProfileData()
                                                                LocalBroadcastManager.getInstance(this).unregisterReceiver(statusReceiver)
                                                                super.onDestroy()
                                                            }
}
