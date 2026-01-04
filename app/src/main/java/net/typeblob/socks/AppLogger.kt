package net.typeblob.socks

object AppLogger {
    private const val MAX_LINES = 500
    private val buffer = mutableListOf<String>()

    @Synchronized
    fun log(msg: String) {
        if (buffer.size >= MAX_LINES) {
            buffer.removeAt(0)
        }
        buffer.add("[${System.currentTimeMillis()}] $msg")
    }

    @Synchronized
    fun getLogs(): String =
        buffer.joinToString("\n")

    @Synchronized
    fun clear() {
        buffer.clear()
    }
}
