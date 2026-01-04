package net.typeblob.socks

import android.content.Context
import java.io.File

object AppLogger {

    private lateinit var logFile: File

    fun init(context: Context) {
        logFile = File(context.filesDir, "slipstream.log")
        log("=== App started ===")
    }

    @Synchronized
    fun log(msg: String) {
        try {
            if (!::logFile.isInitialized) return
            logFile.appendText(msg + "\n")
        } catch (_: Exception) {}
    }
}
