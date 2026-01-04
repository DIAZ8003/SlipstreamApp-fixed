package net.typeblob.socks

object AppLogger {
    private const val MAX_LINES = 500
    private val buffer = mutableListOf<String>()

    @Synchronized
    fun log(msg: String) {
        if (buffer.size >= MAX_LINES) {
            buffer.removeAt(0)
        }
        buffer.add(msg)
    }

    @Synchronized
    fun getLogs(): String {
        return buffer.joinToString("\n")
    }

    @Synchronized
    fun clear() {
        buffer.clear()
    }
}
