package com.example.commandexecutor

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class MainActivity : AppCompatActivity() {

    private val TAG = "CommandExecutor"
    private lateinit var ipInput: EditText       // Field for IP
        private lateinit var domainInput: EditText   // Field for Domain
            private lateinit var startButton: Button
                private lateinit var stopButton: Button

                    // New UI status elements
                    private lateinit var slipstreamStatusIndicator: TextView
                        private lateinit var slipstreamStatusText: TextView
                            private lateinit var sshStatusIndicator: TextView
                                private lateinit var sshStatusText: TextView

                                    private lateinit var sharedPreferences: SharedPreferences

                                        // Keys for SharedPreferences
                                        private val PREF_IP_ADDRESS = "pref_ip_address"
                                        private val PREF_DOMAIN_NAME = "pref_domain_name"
                                        private val DEFAULT_IP = "1.1.1.1"
                                        private val DEFAULT_DOMAIN = "example.com"


                                        // Broadcast Receiver to handle status updates from the service
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
                                                        val output = intent.getStringExtra(CommandService.EXTRA_ERROR_OUTPUT) ?: "No output provided."

                                                        // Show error as Toast, including the output from slipstream
                                                        Toast.makeText(this@MainActivity, "ERROR: $message\n\nOutput:\n$output", Toast.LENGTH_LONG).show()

                                                        // Update UI to reflect failure
                                                        updateStatusUI(slipstreamStatus = "Failed: $message", sshStatus = "Stopped")
                                                    }
                                                }
                                            }
                                        }


                                        // Request permission launcher for Android 13+ notifications
                                        private val requestPermissionLauncher = registerForActivityResult(
                                            ActivityResultContracts.RequestPermission()
                                        ) { isGranted: Boolean ->
                                            if (isGranted) {
                                                Log.d(TAG, "Notification permission granted.")
                                                startCommandService()
                                            } else {
                                                Toast.makeText(this, "Notification permission is required to run the background service.", Toast.LENGTH_LONG).show()
                                            }
                                        }

                                        override fun onCreate(savedInstanceState: Bundle?) {
                                            super.onCreate(savedInstanceState)
                                            setContentView(R.layout.activity_main)

                                            // Initialize SharedPreferences
                                            sharedPreferences = getSharedPreferences("SlipstreamPrefs", Context.MODE_PRIVATE)

                                            // Initialize UI components from the layout
                                            ipInput = findViewById(R.id.ip_input)
                                            domainInput = findViewById(R.id.domain_input)
                                            startButton = findViewById(R.id.start_button)
                                            stopButton = findViewById(R.id.stop_button)

                                            // Initialize NEW UI status components (missing in your current version)
                                            slipstreamStatusIndicator = findViewById(R.id.slipstream_status_indicator)
                                            slipstreamStatusText = findViewById(R.id.slipstream_status_text)
                                            sshStatusIndicator = findViewById(R.id.ssh_status_indicator)
                                            sshStatusText = findViewById(R.id.ssh_status_text)

                                            // --- Load stored values or use defaults ---
                                            val storedIp = sharedPreferences.getString(PREF_IP_ADDRESS, DEFAULT_IP)
                                            val storedDomain = sharedPreferences.getString(PREF_DOMAIN_NAME, DEFAULT_DOMAIN)

                                            ipInput.setText(storedIp)
                                            domainInput.setText(storedDomain)

                                            // --- Set initial status ---
                                            updateStatusUI("Stopped", "Stopped")

                                            // --- Start Service Logic ---
                                            startButton.setOnClickListener {
                                                // Save inputs before starting the service
                                                saveInputs()
                                                checkPermissionsAndStartService()
                                            }

                                            // --- Stop Service Logic ---
                                            stopButton.setOnClickListener {
                                                stopService(Intent(this, CommandService::class.java))
                                                Toast.makeText(this, "Command Service Stop Signal Sent", Toast.LENGTH_SHORT).show()
                                                // The service will send a "Stopped" status broadcast when it finishes cleaning up.
                                            }

                                            // --- Register Broadcast Receiver ---
                                            LocalBroadcastManager.getInstance(this).registerReceiver(
                                                statusReceiver,
                                                IntentFilter(CommandService.ACTION_STATUS_UPDATE)
                                            )
                                            LocalBroadcastManager.getInstance(this).registerReceiver(
                                                statusReceiver,
                                                IntentFilter(CommandService.ACTION_ERROR)
                                            )
                                        }

                                        override fun onDestroy() {
                                            // --- Unregister Broadcast Receiver ---
                                            LocalBroadcastManager.getInstance(this).unregisterReceiver(statusReceiver)
                                            super.onDestroy()
                                        }

                                        private fun saveInputs() {
                                            val editor = sharedPreferences.edit()
                                            editor.putString(PREF_IP_ADDRESS, ipInput.text.toString().trim())
                                            editor.putString(PREF_DOMAIN_NAME, domainInput.text.toString().trim())
                                            editor.apply()
                                            Log.d(TAG, "Saved IP and Domain to SharedPreferences.")
                                        }

                                        private fun checkPermissionsAndStartService() {
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                                if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                                                    startCommandService()
                                                } else {
                                                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                                }
                                            } else {
                                                startCommandService()
                                            }
                                        }

                                        private fun startCommandService() {
                                            // Retrieve and validate inputs
                                            val ipAddress = ipInput.text.toString().trim()
                                            val domainName = domainInput.text.toString().trim()

                                            if (ipAddress.isBlank() || domainName.isBlank()) {
                                                Toast.makeText(this, "IP Address and Domain are required for slipstream-client.", Toast.LENGTH_LONG).show()
                                                return
                                            }

                                            // Log the actual inputs being sent
                                            Log.i(TAG, "Sending inputs to service: IP=$ipAddress, Domain=$domainName")

                                            val serviceIntent = Intent(this, CommandService::class.java).apply {
                                                // Pass IP and Domain using the constants defined in CommandService
                                                putExtra(CommandService.EXTRA_IP_ADDRESS, ipAddress)
                                                putExtra(CommandService.EXTRA_DOMAIN, domainName)
                                            }

                                            // Start the service as a foreground service
                                            ContextCompat.startForegroundService(this, serviceIntent)
                                            Toast.makeText(this, "Service Start Signal Sent", Toast.LENGTH_SHORT).show()

                                            // Set initial UI status immediately to reflect the start signal
                                            updateStatusUI("Starting...", "Waiting...")
                                        }

                                        /**
                                         * Updates the status indicators and text based on broadcasts from the service.
                                         * Uses: ✔ (green check), ❌ (red X), 🟡 (yellow circle/starting)
                                         */
                                        private fun updateStatusUI(slipstreamStatus: String? = null, sshStatus: String? = null) {
                                            slipstreamStatus?.let { status ->
                                                slipstreamStatusText.text = status
                                                when {
                                                    status.contains("Running", ignoreCase = true) -> {
                                                        slipstreamStatusIndicator.text = "✔" // Green Checkmark
                                                        slipstreamStatusIndicator.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
                                                    }
                                                    status.contains("Stopped", ignoreCase = true) || status.contains("Failed", ignoreCase = true) -> {
                                                        slipstreamStatusIndicator.text = "❌" // Red X
                                                        slipstreamStatusIndicator.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
                                                    }
                                                    else -> { // Starting, Waiting, Cleaning up...
                                                        slipstreamStatusIndicator.text = "🟡" // Yellow Circle
                                                        slipstreamStatusIndicator.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_dark))
                                                    }
                                                }
                                            }

                                            sshStatus?.let { status ->
                                                sshStatusText.text = status
                                                when {
                                                    status.contains("Running", ignoreCase = true) -> {
                                                        sshStatusIndicator.text = "✔" // Green Checkmark
                                                        sshStatusIndicator.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
                                                    }
                                                    status.contains("Stopped", ignoreCase = true) || status.contains("Failed", ignoreCase = true) -> {
                                                        sshStatusIndicator.text = "❌" // Red X
                                                        sshStatusIndicator.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
                                                    }
                                                    else -> { // Starting, Waiting, Cleaning up...
                                                        sshStatusIndicator.text = "🟡" // Yellow Circle
                                                        sshStatusIndicator.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_dark))
                                                    }
                                                }
                                            }
                                        }
}
