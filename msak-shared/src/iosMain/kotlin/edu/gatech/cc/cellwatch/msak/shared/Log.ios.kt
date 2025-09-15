package edu.gatech.cc.cellwatch.msak.shared

//import platform.Foundation.NSLog
//
//private object AppleLogger : Logger {
//    private fun line(level: String, tag: String?, message: String?, t: Throwable?): String =
//        buildString {
//            append('[').append(level).append(']')
//            if (!tag.isNullOrBlank()) append(' ').append(tag)
//            if (!message.isNullOrBlank()) append(": ").append(message)
//            if (t != null) append(" (").append(t::class.simpleName).append(": ").append(t.message).append(')')
//        }
//
//    private fun log(level: String, tag: String?, message: String?, t: Throwable?) {
//        // Always use a literal format and pass the composed line as an object to %@.
//        // This avoids any accidental printf-style formatting crashes.
//        val s = line(level, tag, message, t)
//        NSLog("%@", s)
//    }
//
//    override fun v(tag: String?, message: String?, throwable: Throwable?) = log("V", tag, message, throwable)
//    override fun d(tag: String?, message: String?, throwable: Throwable?) = log("D", tag, message, throwable)
//    override fun i(tag: String?, message: String?, throwable: Throwable?) = log("I", tag, message, throwable)
//    override fun w(tag: String?, message: String?, throwable: Throwable?) = log("W", tag, message, throwable)
//    override fun e(tag: String?, message: String?, throwable: Throwable?) = log("E", tag, message, throwable)
//}

// Use the safest option on iOS: stdout via ConsoleLogger.
// NSLog varargs/format can still crash under certain K/N bridging scenarios in Simulator.
actual fun defaultLogger(): Logger = ConsoleLogger