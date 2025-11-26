package com.example.commandexecutor

import android.app.Notification
import android.app.NotificationChannel
import android.app.PendingIntent
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

// Data class to hold the execution result (for clarity in complex logic)
data class CommandResult(val exitCode: Int, val output: String)

// CommandService now implements CoroutineScope to correctly handle 'launch' and 'isActive'
class CommandService : LifecycleService(), CoroutineScope {

    // Define the CoroutineScope for the service lifecycle (SupervisorJob to prevent child failures from killing others)
    private val job = SupervisorJob()
    override val coroutineContext: CoroutineContext = job + Dispatchers.IO

    private val TAG = "CommandService"
    private val NOTIFICATION_CHANNEL_ID = "CommandServiceChannel"
    private val NOTIFICATION_ID = 101

    // Global variables for tunnel management
    private var slipstreamProcess: Process? = null
        private var sshProcess: Process? = null
            private var processOutputReaderJob: Job? = null
                // New: Job for periodic health monitoring
                private var tunnelMonitorJob: Job? = null

                    // Mutex to prevent simultaneous starting/stopping/restarting
                    private val tunnelMutex = Mutex()

                    private var ipAddressConfig: String = ""
                        private var domainNameConfig: String = ""

                            companion object {
                                // Inputs & Config
                                const val EXTRA_IP_ADDRESS = "ip_address"
                                const val EXTRA_DOMAIN = "domain_name"

                                // Corrected binary name
                                const val BINARY_NAME = "slipstream-client"

                                // Broadcast actions
                                const val ACTION_STATUS_UPDATE = "com.example.commandexecutor.STATUS_UPDATE"
                                const val ACTION_ERROR = "com.example.commandexecutor.ERROR"

                                // Broadcast extras
                                const val EXTRA_STATUS_SLIPSTREAM = "status_slipstream"
                                const val EXTRA_STATUS_SSH = "status_ssh"
                                const val EXTRA_ERROR_MESSAGE = "error_message"
                                const val EXTRA_ERROR_OUTPUT = "error_output"

                                // Monitoring interval (1 minute)
                                private const val MONITOR_INTERVAL_MS = 60000L
                            }

                            override fun onCreate() {
                                super.onCreate()
                                createNotificationChannel()
                            }

                            override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
                                super.onStartCommand(intent, flags, startId)

                                // Retrieve and SAVE configuration into member variables
                                ipAddressConfig = intent?.getStringExtra(EXTRA_IP_ADDRESS) ?: ""
                                domainNameConfig = intent?.getStringExtra(EXTRA_DOMAIN) ?: ""
                                Log.d(TAG, "Service started. IP: $ipAddressConfig, Domain: $domainNameConfig")

                                // Start foreground service with clickable notification
                                startForeground(NOTIFICATION_ID, buildForegroundNotification())

                                // Use the service's CoroutineScope (Dispatchers.IO) via 'launch'
                                // Use an immediate launch to start the sequence
                                launch {
                                    // Start the tunnel sequence
                                    startTunnelSequence(ipAddressConfig, domainNameConfig)
                                }

                                return START_STICKY
                            }

                            /**
                             * The main execution sequence: cleans up, copies binary, and starts the tunnel.
                             */
                            private suspend fun startTunnelSequence(ipAddress: String, domainName: String) {
                                // We use a Mutex to ensure only one sequence can run at a time (e.g., if onStartCommand is called twice)
                                tunnelMutex.withLock {

                                    sendStatusUpdate(slipstreamStatus = "Cleaning up...", sshStatus = "Waiting...")

                                    // Cancel existing monitoring if restarting
                                    tunnelMonitorJob?.cancel()
                                    cleanUpLingeringProcesses() // Use killall for general cleanup

                                    val binaryPath = copyBinaryToFilesDir(BINARY_NAME)
                                    if (binaryPath != null) {
                                        // Execute the commands.
                                        val success = executeCommands(binaryPath, ipAddress, domainName)

                                        if (success) {
                                            // Start periodic health monitoring only if successful
                                            tunnelMonitorJob = launch { startTunnelMonitor() }
                                        } else {
                                            stopSelf()
                                        }

                                    } else {
                                        Log.e(TAG, "Failed to prepare '$BINARY_NAME' binary for execution.")
                                        sendError("Binary Error", "Failed to prepare '$BINARY_NAME' binary for execution.")
                                        stopSelf()
                                    }
                                }
                            }

                            /**
                             * Checks if both processes are running. If not, it attempts a full restart.
                             */
                            private suspend fun startTunnelMonitor() {
                                Log.d(TAG, "Starting tunnel monitor job.")
                                while (isActive) {
                                    delay(MONITOR_INTERVAL_MS) // Wait 1 minute

                                    val slipstreamAlive = slipstreamProcess?.isAlive == true
                                    val sshAlive = sshProcess?.isAlive == true

                                    Log.d(TAG, "Monitor Check: Slipstream is Alive: $slipstreamAlive, SSH is Alive: $sshAlive")

                                    if (slipstreamAlive && sshAlive) {
                                        // All good, update status to reassure UI
                                        sendStatusUpdate(slipstreamStatus = "Running", sshStatus = "Running")
                                    } else if (!slipstreamAlive || !sshAlive) {
                                        // Critical failure: one or both processes died unexpectedly.
                                        Log.w(TAG, "Tunnel failure detected. Attempting full restart.")
                                        sendError(
                                            "Connection Dropped",
                                            "Slipstream status: ${if (slipstreamAlive) "Running" else "Dead"}. SSH status: ${if (sshAlive) "Running" else "Dead"}."
                                        )

                                        // Immediately attempt restart with current configuration
                                        // This call uses the service's main scope but respects the Mutex
                                        // Note: The monitor thread will pause here waiting for the Mutex.
                                        startTunnelSequence(ipAddressConfig, domainNameConfig)
                                    }
                                }
                            }

                            /**
                             * Executes the two shell commands: slipstream-client and ssh.
                             * Returns true if successful, false otherwise.
                             */
                            private suspend fun executeCommands(
                                slipstreamClientPath: String,
                                ipAddress: String,
                                domainName: String
                            ): Boolean {
                                // Stop any running jobs before starting new processes
                                processOutputReaderJob?.cancel()
                                processOutputReaderJob = null

                                Log.i(TAG, "Starting command execution with IP: '$ipAddress' and Domain: '$domainName'")

                                sendStatusUpdate(slipstreamStatus = "Starting...", sshStatus = "Waiting...")

                                // Command 1: slipstream-client
                                val command1 = listOf(
                                    slipstreamClientPath,
                                    "--congestion-control=bbr",
                                    "--resolver=$ipAddress:53",
                                    "--domain=$domainName"
                                )

                                // 1. Start slipstream-client and wait for connection confirmation (2000ms timeout)
                                val confirmationMessage = "Connection confirmed."
                                val slipstreamStartResult = startProcessWithOutputCheck(command1, BINARY_NAME, 2000L, confirmationMessage)
                                slipstreamProcess = slipstreamStartResult.second

                                if (slipstreamStartResult.first.contains(confirmationMessage, ignoreCase = false)) {
                                    Log.i(TAG, "$BINARY_NAME output confirmed successful connection. Proceeding with ssh.")

                                    // Start job to monitor slipstream's ongoing output (only for live logging)
                                    processOutputReaderJob = launch { readProcessOutput(slipstreamProcess!!, BINARY_NAME) }

                                    sendStatusUpdate(slipstreamStatus = "Running", sshStatus = "Starting...")

                                    delay(500L)

                                    // Command 2: ssh tunnel
                                    val sshArgs = "-p 5201 -ND 3080 root@localhost"
                                    val shellCommand = "ssh $sshArgs"
                                    val command2 = listOf("su", "-c", shellCommand)

                                    // 2. Start ssh (no read timeout, we just start and let it run)
                                    val sshStartResult = startProcessWithOutputCheck(command2, "ssh", null, null)
                                    sshProcess = sshStartResult.second

                                    if (sshProcess?.isAlive == true) {
                                        sendStatusUpdate(sshStatus = "Running")
                                        return true
                                    } else {
                                        sendError("SSH Start Error", "SSH tunnel failed to start. Output:\n${sshStartResult.first}")
                                        killProcess(slipstreamProcess, BINARY_NAME) // Kill slipstream if ssh failed
                                        // Do NOT stopSelf yet, let the outer scope handle the stopSelf or restart
                                        return false
                                    }
                                } else {
                                    val errorMessage = "$BINARY_NAME failed to confirm connection or timed out."
                                    Log.w(TAG, errorMessage)
                                    sendError(errorMessage, slipstreamStartResult.first)
                                    killProcess(slipstreamProcess, BINARY_NAME)
                                    // Do NOT stopSelf yet, let the outer scope handle the stopSelf or restart
                                    return false
                                }
                            }

                            /**
                             * Starts a shell command, reads initial output with an optional timeout, and returns the Process object.
                             */
                            private suspend fun startProcessWithOutputCheck(
                                command: List<String>,
                                logTag: String,
                                readTimeoutMillis: Long?,
                                successMessage: String?
                            ): Pair<String, Process> {
                                try {
                                    Log.i(TAG, "Starting $logTag execution: ${command.joinToString(" ")}")

                                    val process = ProcessBuilder(command)
                                    .redirectErrorStream(true)
                                    .start()

                                    val output = StringBuilder()
                                    val reader = BufferedReader(InputStreamReader(process.inputStream))

                                    if (readTimeoutMillis != null) {
                                        withTimeoutOrNull(readTimeoutMillis) {
                                            var line: String?
                                            while (reader.readLine().also { line = it } != null) {
                                                output.append(line).append('\n')
                                                if (line?.isNotBlank() == true) Log.d(TAG, "$logTag Startup Output: $line")

                                                    if (successMessage != null && line?.contains(successMessage, ignoreCase = false) == true) {
                                                        Log.d(TAG, "$logTag Success message found, confirming startup.")
                                                        break
                                                    }
                                            }
                                            true
                                        }
                                    } else {
                                        if (process.isAlive) {
                                            Log.d(TAG, "$logTag is running in the background.")
                                        } else {
                                            var line: String?
                                            while (reader.readLine().also { line = it } != null) {
                                                output.append(line).append('\n')
                                                if (line?.isNotBlank() == true) Log.d(TAG, "$logTag Initial Output: $line")
                                            }
                                        }
                                    }

                                    return Pair(output.toString().trim(), process)

                                } catch (e: Exception) {
                                    Log.e(TAG, "Error executing $logTag command: ${e.message}", e)
                                    // Return a dummy process to avoid crashing, but include error in output
                                    return Pair("Execution Error: ${e.message}", ProcessBuilder("echo", "error").start())
                                }
                            }

                            /**
                             * Coroutine job to read the slipstream process output and log it (the "Live Output" feature).
                             */
                            private suspend fun readProcessOutput(process: Process, logTag: String) {
                                val reader = BufferedReader(InputStreamReader(process.inputStream))
                                try {
                                    var line: String?
                                    // Continue while coroutine is active AND process is alive
                                    while (isActive && process.isAlive) {
                                        if (reader.ready()) {
                                            line = reader.readLine()
                                            if (line != null) {
                                                Log.d(TAG, "$logTag Live Output: $line") // Log all output
                                            }
                                        } else {
                                            // Short delay to prevent busy-waiting if nothing is ready
                                            delay(10)
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error reading $logTag output: ${e.message}", e)
                                } finally {
                                    // Check if the process died while monitoring, if so, trigger a failure check via monitor job
                                    if (process.isAlive) {
                                        Log.i(TAG, "$logTag output reader job finished gracefully.")
                                    } else {
                                        Log.w(TAG, "$logTag died unexpectedly. Monitor job should detect this shortly.")
                                    }
                                    reader.close()
                                }
                            }

                            // --- Cleanup & Helpers ---

                            private fun createNotificationChannel() {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    val channel = NotificationChannel(
                                        NOTIFICATION_CHANNEL_ID,
                                        "Command Executor Status",
                                        NotificationManager.IMPORTANCE_DEFAULT
                                    )
                                    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                                    manager.createNotificationChannel(channel)
                                }
                            }

                            /**
                             * Builds the foreground notification, including a PendingIntent to open MainActivity.
                             */
                            private fun buildForegroundNotification(): Notification {
                                // Intent to launch MainActivity when the notification is tapped
                                val notificationIntent = Intent(this, Class.forName("com.example.commandexecutor.MainActivity")).apply {
                                    // FIX: Use FLAG_ACTIVITY_SINGLE_TOP so clicking the notification brings the existing Activity instance to the foreground
                                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                }

                                // Define PendingIntent flags based on Android version
                                val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                                } else {
                                    PendingIntent.FLAG_UPDATE_CURRENT
                                }

                                val pendingIntent = PendingIntent.getActivity(
                                    this,
                                    0,
                                    notificationIntent,
                                    pendingIntentFlags
                                )

                                // TODO: Replace with a real icon
                                val smallIconRes = android.R.drawable.ic_dialog_info

                                return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                                .setContentTitle("Slipstream Tunnel Running")
                                .setContentText("Tap to view configuration or stop the service.")
                                .setSmallIcon(smallIconRes)
                                .setContentIntent(pendingIntent) // Set the intent to be triggered on click
                                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                                .setOnlyAlertOnce(true)
                                .build()
                            }

                            private fun sendStatusUpdate(slipstreamStatus: String? = null, sshStatus: String? = null) {
                                val intent = Intent(ACTION_STATUS_UPDATE)
                                slipstreamStatus?.let { intent.putExtra(EXTRA_STATUS_SLIPSTREAM, it) }
                                sshStatus?.let { intent.putExtra(EXTRA_STATUS_SSH, it) }
                                LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
                            }

                            private fun sendError(message: String, output: String) {
                                val intent = Intent(ACTION_ERROR).apply {
                                    putExtra(EXTRA_ERROR_MESSAGE, message)
                                    putExtra(EXTRA_ERROR_OUTPUT, output)
                                }
                                LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
                                // Note: We don't call sendStatusUpdate here, as executeCommands/monitor will handle the final status
                            }

                            private fun copyBinaryToFilesDir(binaryName: String): String? {
                                val destFile = File(filesDir, binaryName)
                                if (destFile.exists()) {
                                    return destFile.absolutePath
                                }

                                try {
                                    assets.open(binaryName).use { input ->
                                        destFile.outputStream().use { output ->
                                            input.copyTo(output)
                                        }
                                    }
                                    destFile.setExecutable(true, false)
                                    Log.d(TAG, "Copied and set executable permission for: ${destFile.absolutePath}")
                                    return destFile.absolutePath
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error copying binary '$binaryName': ${e.message}", e)
                                    return null
                                }
                            }

                            private fun cleanUpLingeringProcesses() {
                                Log.w(TAG, "Attempting to clean up lingering processes using killall...")
                                // Kill slipstream binary
                                val killallSlipstream = listOf("su", "-c", "killall -9 ${BINARY_NAME}")
                                executeCleanupCommand(killallSlipstream, "killall ${BINARY_NAME}")

                                // Kill specific ssh instance running on the expected port (optional, but good practice)
                                // Finding the specific ssh process started by the app is hard, but we rely on killing the slipstream pipe
                                // and expecting the ssh over the pipe to die shortly. The next call to start ssh will fail if it's still running.
                            }

                            private fun executeCleanupCommand(command: List<String>, logTag: String) {
                                try {
                                    val process = ProcessBuilder(command)
                                    .redirectErrorStream(true)
                                    .start()

                                    val exited = process.waitFor(1, TimeUnit.SECONDS)
                                    if (exited) {
                                        // killall returns 1 if no process was found, which is OK.
                                        if (process.exitValue() != 0) {
                                            Log.d(TAG, "Cleanup command $logTag returned non-zero exit code, likely meaning no processes were running.")
                                        }
                                    } else {
                                        process.destroyForcibly()
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error executing cleanup command $logTag: ${e.message}", e)
                                }
                            }

                            /**
                             * Terminates a process, waiting up to 1 second for graceful exit, then forcing.
                             * @return true if the process is no longer alive, false otherwise.
                             */
                            private fun killProcess(process: Process?, tag: String): Boolean {
                                if (process != null && process.isAlive) {
                                    process.destroy()
                                    try {
                                        // Wait for graceful termination (synchronous and blocking)
                                        if (!process.waitFor(1000, TimeUnit.MILLISECONDS)) {
                                            process.destroyForcibly()
                                            // Wait again for forceful termination
                                            if (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                                                Log.e(TAG, "$tag process **FAILED** to terminate forcibly. It may be orphaned.")
                                                return false
                                            }
                                        }
                                        Log.i(TAG, "$tag process successfully terminated.")
                                        return true
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Error waiting/forcing termination for $tag: ${e.message}")
                                        if (process.isAlive) {
                                            process.destroyForcibly()
                                        }
                                        return false
                                    }
                                }
                                return true // Process was null or not alive, so it's "stopped"
                            }

                            override fun onBind(intent: Intent): IBinder? {
                                super.onBind(intent)
                                return null
                            }

                            override fun onDestroy() {
                                // Cancel the job to stop all coroutines launched in this scope
                                job.cancel()
                                stopBackgroundProcesses()
                                super.onDestroy()
                                Log.d(TAG, "Command Service destroyed.")
                            }

                            /**
                             * Stops all running processes and coroutines.
                             * This function should run synchronously (on the main thread if called from onDestroy).
                             */
                            private fun stopBackgroundProcesses() {
                                Log.i(TAG, "Stopping foreground processes and coroutines...")

                                // 1. Cancel jobs: monitor and output reader
                                tunnelMonitorJob?.cancel()
                                processOutputReaderJob?.cancel()

                                // 2. Kill processes (blocking calls)
                                val sshKilled = killProcess(sshProcess, "ssh")
                                val slipstreamKilled = killProcess(slipstreamProcess, BINARY_NAME)

                                // 3. Update UI based on actual termination result (ensuring UI is updated AFTER kill attempt)
                                val finalSlipstreamStatus = if (slipstreamKilled) "Stopped" else "Failed to Stop"
                                val finalSshStatus = if (sshKilled) "Stopped" else "Failed to Stop"

                                sendStatusUpdate(slipstreamStatus = finalSlipstreamStatus, sshStatus = finalSshStatus)

                                if (!slipstreamKilled || !sshKilled) {
                                    Log.e(TAG, "CRITICAL: One or more processes failed to stop! Slipstream: $finalSlipstreamStatus, SSH: $finalSshStatus")
                                }

                                // Clear references
                                slipstreamProcess = null
                                sshProcess = null
                                processOutputReaderJob = null
                                tunnelMonitorJob = null
                            }
}
