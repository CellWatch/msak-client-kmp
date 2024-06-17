package edu.gatech.cc.cellwatch.msak

/**
 * The interface to be implemented by an object that the msak-android library can use for logging.
 */
interface Logger {
    /**
     * Log a verbose message.
     */
    fun v(tag: String?, message: String?, throwable: Throwable? = null)

    /**
     * Log a debug message.
     */
    fun d(tag: String?, message: String?, throwable: Throwable? = null)

    /**
     * Log an info message.
     */
    fun i(tag: String?, message: String?, throwable: Throwable? = null)

    /**
     * Log a warning message.
     */
    fun w(tag: String?, message: String?, throwable: Throwable? = null)

    /**
     * Log an error message.
     */
    fun e(tag: String?, message: String?, throwable: Throwable? = null)
}

/**
 * An implementation of the Logger interface that simply defers to android.util.Log.
 */
object AndroidLogger : Logger {
    override fun v(tag: String?, message: String?, throwable: Throwable?) {
        android.util.Log.v(tag, message, throwable)
    }
    override fun d(tag: String?, message: String?, throwable: Throwable?) {
        android.util.Log.d(tag, message, throwable)
    }
    override fun i(tag: String?, message: String?, throwable: Throwable?) {
        android.util.Log.i(tag, message, throwable)
    }
    override fun w(tag: String?, message: String?, throwable: Throwable?) {
        android.util.Log.w(tag, message, throwable)
    }
    override fun e(tag: String?, message: String?, throwable: Throwable?) {
        android.util.Log.e(tag, message, throwable)
    }
}

internal var Log: Logger = AndroidLogger

/**
 * Set up logging for the library.
 *
 * @param log The logging object to be used by the library for all of its logging.
 */
fun setLogger(log: Logger) {
    Log = log
}