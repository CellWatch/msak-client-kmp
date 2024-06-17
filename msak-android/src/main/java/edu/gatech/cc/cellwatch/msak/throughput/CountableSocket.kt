package edu.gatech.cc.cellwatch.msak.throughput

import org.apache.commons.io.input.CountingInputStream
import org.apache.commons.io.output.CountingOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/**
 * A socket that keeps track of the number of bytes sent and received at the transport layer. Does
 * not work reliably without Conscrypt.
 */
class CountableSocket: Socket() {
    private var inStream: CountingInputStream? = null
    private var outStream: CountingOutputStream? = null

    /**
     * The number of bytes received so far.
     */
    val inBytes
        get() = inStream?.byteCount ?: 0

    /**
     * The number of bytes sent so far;
     */
    val outBytes
        get() = outStream?.byteCount ?: 0

    override fun getInputStream(): InputStream {
        val i = inStream ?: CountingInputStream(super.getInputStream())
        inStream = i
        return i
    }

    override fun getOutputStream(): OutputStream {
        val o = outStream ?: CountingOutputStream(super.getOutputStream())
        outStream = o
        return o
    }
}
