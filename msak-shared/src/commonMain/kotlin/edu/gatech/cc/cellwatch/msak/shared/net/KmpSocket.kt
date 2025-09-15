package edu.gatech.cc.cellwatch.msak.shared.net

/**
 * Minimal UDP and TCP socket interfaces for KMP.
 *
 * UDP usage patterns:
 *  - Server‑style: bind(localHost?, localPort), then receive() in a loop; reply with send(data, host, port).
 *  - Client‑style: connect(remoteHost, remotePort) once, then send(data) / receive(). No handshake occurs;
 *    the kernel records a default peer and filters other sources.
 *  - One‑shot send: send(data, host, port) without a prior connect (uses sendto semantics).
 *
 * TCP usage patterns:
 *  - Call connect(host, port), then sendAll()/receive() as needed.
 *  - Close to terminate the stream. receive() returns an empty array on EOF.
 *
 * Thread safety: a single socket instance is not intended for concurrent use from multiple coroutines.
 */
interface KmpUdpSocket : AutoCloseable {
    /** Bind to a local address and port. Pass null for host to bind all interfaces. */
    suspend fun bind(localHost: String? = null, localPort: Int)

    /** Select a default peer for client‑style UDP. No network handshake occurs; the kernel records a default peer.
     *  After this call, [send] without host/port uses the selected peer. Throws [UdpException] on failure. */
    suspend fun connect(remoteHost: String, remotePort: Int)

    /**
     * Send a datagram.
     *  - If [host]/[port] are provided, this performs a one‑shot send (no prior [connect] required).
     *  - If both are null, this sends to the previously selected peer and therefore requires a prior [connect].
     * Returns the number of bytes sent. Throws [UdpException] on failure.
     */
    suspend fun send(data: ByteArray, host: String? = null, port: Int? = null): Int

    /**
     * Receive a datagram, up to [maxBytes]. Returns null on graceful close.
     * Requires the socket to be open via [bind] or [connect]. Throws [UdpException] on failure.
     */
    suspend fun receive(maxBytes: Int = 65535): UdpPacket?

    /** Close the socket. Safe to call multiple times. */
    override fun close()
}

/**
 * Minimal TCP client interface.
 */
interface KmpTcpSocket : AutoCloseable {
    /** Connect to the host:port. Throws TcpException (or platform error) on failure. */
    suspend fun connect(host: String, port: Int)

    /** Send the entire contents of [data]. Returns number of bytes sent (== data.size on success). */
    suspend fun sendAll(data: ByteArray): Int

    /** Receive up to [maxBytes] bytes. Returns empty array on EOF. */
    suspend fun receive(maxBytes: Int = 4096): ByteArray

    /** Close the socket. Safe to call multiple times. */
    override fun close()
}

/** A received UDP datagram. */
data class UdpPacket(val data: ByteArray, val host: String, val port: Int)

/** UDP exception wrapper. */
class UdpException(message: String, val errno: Int? = null) : Exception(message)

/** TCP exception wrapper. Mirrors UdpException and may carry a platform errno. */
class TcpException(message: String, val errno: Int? = null) : Exception(message)

/** Platform factories. */
expect object SocketFactory {
    /** Create a platform UDP socket implementation. */
    fun udp(): KmpUdpSocket
    /** Create a platform TCP socket implementation. */
    fun tcp(): KmpTcpSocket
}