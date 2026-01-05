package net.typeblob.socks

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLogger {

    private const val TAG = "SlipstreamApp"
    private const val LOG_FILE_NAME = "slipstream.log"
    private const val MAX_LOG_SIZE_BYTES = 1 * 1024 * 1024 // 1 MB

    @Volatile
    private var logFile: File? = null

    fun init(context: Context) {
        try {
            val downloads = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            )

            if (!downloads.exists()) {
                downloads.mkdirs()
            }

            logFile = File(downloads, LOG_FILE_NAME)
            rotateIfNeeded()
            log("=== AppLogger initialized ===")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to init logger", e)
        }
    }

    @Synchronized
    fun log(message: String) {
        val timestamp = SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss.SSS",
            Locale.US
        ).format(Date())

        val line = "$timestamp | $message"
        Log.d(TAG, line)

        try {
            rotateIfNeeded()
            logFile?.appendText(line + "\n")
        } catch (_: Exception) {
            // nunca romper la app por logging
        }
    }

    private fun rotateIfNeeded() {
        try {
            val file = logFile ?: return
            if (file.exists() && file.length() > MAX_LOG_SIZE_BYTES) {
                file.delete()
                file.createNewFile()
                Log.w(TAG, "Log rotated (size limit reached)")
            }
        } catch (_: Exception) {}
    }

    fun getLogFile(): File? = logFile
}
