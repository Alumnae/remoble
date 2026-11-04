package ee.nekoko.remoble

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Small in-process diagnostic buffer. It mirrors messages to Android Logcat and retains only the
 * newest lines for the optional UI console, so enabling diagnostics cannot grow memory without a
 * bound or block BLE work on the main thread.
 */
object ConsoleLog {
    private const val MAX_LINES = 500
    private const val MAX_CHARACTERS = 96_000
    private val lock = Any()
    private val lines = ArrayDeque<String>()
    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var characterCount = 0

    @Volatile var version: Long = 0
        private set

    fun d(tag: String, message: String): Int {
        append("D", tag, message)
        return android.util.Log.d(tag, message)
    }

    fun i(tag: String, message: String): Int {
        append("I", tag, message)
        return android.util.Log.i(tag, message)
    }

    fun w(tag: String, message: String): Int {
        append("W", tag, message)
        return android.util.Log.w(tag, message)
    }

    fun w(tag: String, message: String, error: Throwable): Int {
        append("W", tag, "$message · ${error.summary()}")
        return android.util.Log.w(tag, message, error)
    }

    fun e(tag: String, message: String): Int {
        append("E", tag, message)
        return android.util.Log.e(tag, message)
    }

    fun e(tag: String, message: String, error: Throwable): Int {
        append("E", tag, "$message · ${error.summary()}")
        return android.util.Log.e(tag, message, error)
    }

    fun snapshot(): String = synchronized(lock) { lines.joinToString("\n") }

    fun clear() = synchronized(lock) {
        lines.clear()
        characterCount = 0
        version += 1
    }

    private fun append(level: String, tag: String, message: String) = synchronized(lock) {
        val flattened = message.replace('\n', ' ').replace('\r', ' ').trim()
        val line = "${clock.format(Date())} $level/$tag: $flattened"
        lines.addLast(line)
        characterCount += line.length + 1
        while (lines.size > MAX_LINES || characterCount > MAX_CHARACTERS) {
            characterCount -= (lines.removeFirst().length + 1)
        }
        version += 1
    }

    private fun Throwable.summary(): String =
            "${javaClass.simpleName}: ${message.orEmpty()}".trimEnd()
}
