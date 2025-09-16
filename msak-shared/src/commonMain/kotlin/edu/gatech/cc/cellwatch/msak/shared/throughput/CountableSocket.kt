// src/commonMain/kotlin/edu/gatech/cc/cellwatch/msak/shared/throughput/CountableTcpSocket.kt
package edu.gatech.cc.cellwatch.msak.shared.throughput

import edu.gatech.cc.cellwatch.msak.shared.net.KmpTcpSocket
import edu.gatech.cc.cellwatch.msak.shared.net.SocketFactory
import kotlinx.atomicfu.atomic

/**
 * KMP-counting wrapper around a TCP socket.
 *
 * Thread-safety
 * - Counters are updated and read using atomic primitives via kotlinx-atomicfu, so multiple threads
 *   (uploader, WebSocket callbacks, tickers) may safely increment/read concurrently.
 *
 * Counts application-layer bytes written and read through this socket.
 * This mirrors the original Android CountableSocket that used Apache Commons
 * Counting*Stream, but is portable across Android and iOS.
 *
 * Important
 * - These counters are at the application layer. They match the number of bytes you pass to
 *   sendAll and the number returned by receive. They do not include TCP headers or TLS framing.
 * - To observe transport-layer byte counts for TLS/WebSocket on Android, you will still need
 *   a Conscrypt-backed stack as noted in the original comment. That is an Android-specific concern.
 */
class CountableTcpSocket(
    private val delegate: KmpTcpSocket = SocketFactory.tcp()
) : KmpTcpSocket {

    // Thread-safe counters across KMP targets.
    // Uses kotlinx-atomicfu for correct memory semantics on JVM and Native.
    private val sent = atomic(0L)
    private val recv = atomic(0L)

    /** Application-layer bytes successfully sent so far. */
    val sentBytes: Long get() = sent.value

    /** Application-layer bytes successfully received so far. */
    val recvBytes: Long get() = recv.value

    /** Reset both counters to zero. Safe to call anytime. */
    fun resetCounters() {
        sent.value = 0L
        recv.value = 0L
    }

    /** Atomically snapshot both counters. */
    fun snapshot(): ByteCounters = ByteCounters(
        BytesSent = sent.value,
        BytesReceived = recv.value
    )

    override suspend fun connect(host: String, port: Int) {
        delegate.connect(host, port)
    }

    override suspend fun sendAll(data: ByteArray): Int {
        val n = delegate.sendAll(data)
        // By contract sendAll returns data.size on success.
        sent.plusAssign(n.toLong())
        return n
    }

    override suspend fun receive(maxBytes: Int): ByteArray {
        val buf = delegate.receive(maxBytes)
        recv.plusAssign(buf.size.toLong())
        return buf
    }

    override fun close() {
        delegate.close()
    }
}

/** Convenience factory if you prefer call-site symmetry. */
fun SocketFactory.countingTcp(): CountableTcpSocket = CountableTcpSocket(SocketFactory.tcp())