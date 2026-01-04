package net.typeblob.socks

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLogger {

    private const val FILE_NAME = "slipstream.log"
    private const val MAX_SIZE_BYTES = 512 * 1024 // 512 KB
    private lateinit var logFile: File

    fun init(context: Context) {
        logFile = File(context.filesDir, FILE_NAME)
    }

    @Synchronized
    fun log(msg: String) {
        try {
            if (!::logFile.isInitialized) return

            if (logFile.exists() && logFile.length() > MAX_SIZE_BYTES) {
                logFile.writeText("")
            }

            val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            logFile.appendText("[$ts] $msg\n")
        } catch (_: Exception) {
        }
    }
}
