package edu.gatech.cc.cellwatch.msak.shared.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.*

/** UDP on Android via java.net.DatagramSocket */
internal class AndroidUdpSocket : KmpUdpSocket {
    private var socket: DatagramSocket? = null
    private var connected: Boolean = false
    private var closed = false

    override suspend fun bind(localHost: String?, localPort: Int) = withContext(Dispatchers.IO) {
        check(socket == null) { "socket already open" }
        val s = DatagramSocket(null)
        // Allow quick rebinds during development
        s.reuseAddress = true
        val addr = if (localHost.isNullOrBlank()) InetSocketAddress(localPort)
        else InetSocketAddress(InetAddress.getByName(localHost), localPort)
        s.bind(addr)
        // Short receive timeout so blocking receive wakes periodically; aids prompt shutdown symmetry with iOS.
        s.soTimeout = 250 // ms
        socket = s
        closed = false
        connected = false
    }

    override suspend fun connect(remoteHost: String, remotePort: Int) = withContext(Dispatchers.IO) {
        val s = socket ?: DatagramSocket().also { socket = it }
        s.connect(InetAddress.getByName(remoteHost), remotePort)
        // Match iOS behavior: periodic wakeups from blocking receive
        s.soTimeout = 250 // ms
        connected = true
    }

    override suspend fun send(data: ByteArray, host: String?, port: Int?): Int = withContext(Dispatchers.IO) {
        val s = socket ?: throw UdpException("socket not open")
        val pkt = when {
            host != null && port != null -> DatagramPacket(data, data.size, InetAddress.getByName(host), port)
            connected -> DatagramPacket(data, data.size)
            else -> throw UdpException("No peer: call connect() or provide host/port")
        }
        s.send(pkt)
        data.size
    }

    override suspend fun receive(maxBytes: Int): UdpPacket? = withContext(Dispatchers.IO) {
        val s = socket ?: throw UdpException("socket not open")
        if (maxBytes <= 0) return@withContext UdpPacket(ByteArray(0), "", 0)
        val buf = ByteArray(maxBytes)
        val pkt = DatagramPacket(buf, buf.size)
        try {
            s.receive(pkt) // blocks until a datagram arrives or soTimeout elapses
        } catch (e: SocketTimeoutException) {
            return@withContext null
        }
        val data = pkt.data.copyOf(pkt.length)
        val host = (pkt.address?.hostAddress) ?: ""
        val port = pkt.port
        UdpPacket(data, host, port)
    }

    override fun close() {
        if (!closed) {
            socket?.close()
            socket = null
            connected = false
            closed = true
        }
    }
}

/** TCP on Android via java.net.Socket */
internal class AndroidTcpSocket : KmpTcpSocket {
    private var socket: Socket? = null
    private var inStream: InputStream? = null
    private var outStream: OutputStream? = null
    private var closed = false

    override suspend fun connect(host: String, port: Int) = withContext(Dispatchers.IO) {
        check(socket == null) { "socket already open" }
        val s = Socket()
        // Optional low latency settings
        s.tcpNoDelay = true
        // Configure timeouts as needed (example 5s connect, 5s read)
        s.connect(InetSocketAddress(host, port), /* timeout ms */ 5000)
        s.soTimeout = 5000
        socket = s
        inStream = s.getInputStream()
        outStream = s.getOutputStream()
        closed = false
    }

    override suspend fun sendAll(data: ByteArray): Int = withContext(Dispatchers.IO) {
        val out = outStream ?: throw TcpException("socket not open")
        out.write(data)
        out.flush()
        data.size
    }

    override suspend fun receive(maxBytes: Int): ByteArray = withContext(Dispatchers.IO) {
        val `in` = inStream ?: throw TcpException("socket not open")
        if (maxBytes <= 0) return@withContext ByteArray(0)
        val buf = ByteArray(maxBytes)
        val n = `in`.read(buf) // -1 on EOF
        if (n <= 0) ByteArray(0) else buf.copyOf(n)
    }

    override fun close() {
        if (!closed) {
            try { inStream?.close() } catch (_: Throwable) {}
            try { outStream?.close() } catch (_: Throwable) {}
            try { socket?.close() } catch (_: Throwable) {}
            inStream = null; outStream = null; socket = null
            closed = true
        }
    }
}

/** Unified factory for Android */
actual object SocketFactory {
    actual fun udp(): KmpUdpSocket = AndroidUdpSocket()
    actual fun tcp(): KmpTcpSocket = AndroidTcpSocket()
}