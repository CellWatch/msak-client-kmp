package edu.gatech.cc.cellwatch.msak.shared

import android.util.Log as ALog

private object AndroidLogger : Logger {
    override fun v(tag: String?, message: String?, throwable: Throwable?) { ALog.v(tag, message, throwable) }
    override fun d(tag: String?, message: String?, throwable: Throwable?) { ALog.d(tag, message, throwable) }
    override fun i(tag: String?, message: String?, throwable: Throwable?) { ALog.i(tag, message, throwable) }
    override fun w(tag: String?, message: String?, throwable: Throwable?) { ALog.w(tag, message, throwable) }
    override fun e(tag: String?, message: String?, throwable: Throwable?) { ALog.e(tag, message, throwable) }
}

actual fun defaultLogger(): Logger = AndroidLogger