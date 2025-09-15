package edu.gatech.cc.cellwatch.msak.shared

/**
 * Cross-platform logging API.
 */
interface Logger {
    fun v(tag: String?, message: String?, throwable: Throwable? = null)
    fun d(tag: String?, message: String?, throwable: Throwable? = null)
    fun i(tag: String?, message: String?, throwable: Throwable? = null)
    fun w(tag: String?, message: String?, throwable: Throwable? = null)
    fun e(tag: String?, message: String?, throwable: Throwable? = null)
}

/**
 * Provide a platform-appropriate default logger.
 * Implemented per platform via `actual fun defaultLogger()`.
 */
expect fun defaultLogger(): Logger

/**
 * Globally used logger. Override via [setLogger] if needed by host apps.
 */
@PublishedApi
internal var Log: Logger = defaultLogger()

/**
 * Host apps may inject their own logger.
 */
fun setLogger(logger: Logger) {
    Log = logger
}

/**
 * Simple stdout logger for tests or CLI.
 */
object ConsoleLogger : Logger {
    private fun line(lvl: String, tag: String?, msg: String?, t: Throwable?) =
        buildString {
            append('[').append(lvl).append(']')
            if (!tag.isNullOrBlank()) append(' ').append(tag)
            if (!msg.isNullOrBlank()) append(": ").append(msg)
            if (t != null) append(" (").append(t::class.simpleName).append(": ").append(t.message).append(')')
        }

    override fun v(tag: String?, message: String?, throwable: Throwable?) = println(line("V", tag, message, throwable))
    override fun d(tag: String?, message: String?, throwable: Throwable?) = println(line("D", tag, message, throwable))
    override fun i(tag: String?, message: String?, throwable: Throwable?) = println(line("I", tag, message, throwable))
    override fun w(tag: String?, message: String?, throwable: Throwable?) = println(line("W", tag, message, throwable))
    override fun e(tag: String?, message: String?, throwable: Throwable?) = println(line("E", tag, message, throwable))
}