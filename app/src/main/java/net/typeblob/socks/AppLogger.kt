package net.typeblob.socks

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLogger {

    private const val TAG = "SlipstreamApp"
    private const val LOG_FILE_NAME = "slipstream.log"

    @Volatile
    private var logFile: File? = null

    fun init(context: Context) {
        try {
            logFile = File(context.filesDir, LOG_FILE_NAME)
            log("=== AppLogger initialized ===")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init logger", e)
        }
    }

    fun log(message: String) {
        val timestamp = SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss.SSS",
            Locale.US
        ).format(Date())

        val line = "$timestamp | $message"

        Log.d(TAG, line)

        try {
            logFile?.appendText(line + "\n")
        } catch (_: Exception) {
            // nunca romper la app por logging
        }
    }

    fun getLogFile(): File? = logFile
}
