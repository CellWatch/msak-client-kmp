@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package edu.gatech.cc.cellwatch.msak.shared.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CancellationException
import kotlinx.cinterop.*
import platform.posix.*
import kotlinx.cinterop.ExperimentalForeignApi

// Diagnostics helper for readable errno names on Darwin
private fun errnoNameDarwin(code: Int): String = when (code) {
    22 -> "EINVAL"; 47 -> "EAFNOSUPPORT"; 49 -> "EADDRNOTAVAIL"; 50 -> "ENETDOWN";
    51 -> "ENETUNREACH"; 57 -> "ENOTCONN"; 60 -> "ETIMEDOUT"; 61 -> "ECONNREFUSED";
    else -> "errno=$code"
}

@OptIn(ExperimentalForeignApi::class)
internal class IosUdpSocket : KmpUdpSocket {
    private var fd: Int = -1
    private var closed = false
    private var connected = false

    override suspend fun bind(localHost: String?, localPort: Int) = withContext(Dispatchers.Default) {
        ensureClosedOrThrow()
        fd = socket(AF_INET6, SOCK_DGRAM, IPPROTO_UDP)
        if (fd < 0) {
            // Fallback to IPv4 if IPv6 socket creation fails
            fd = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
            if (fd < 0) throw UdpException("socket() failed", errno)
        } else {
            // Allow dual-stack if available (0 = allow v4-mapped)
            memScoped {
                val zero = alloc<IntVar>()
                zero.value = 0
                setsockopt(fd, IPPROTO_IPV6, IPV6_V6ONLY, zero.ptr, sizeOf<IntVar>().convert())
            }
        }

        // REUSEADDR for quick rebinds during development
        memScoped {
            val one = alloc<IntVar>()
            one.value = 1
            setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, one.ptr, sizeOf<IntVar>().convert())
        }

        memScoped {
            val hints = alloc<addrinfo>().apply {
                ai_family = AF_UNSPEC
                ai_socktype = SOCK_DGRAM
                ai_protocol = IPPROTO_UDP
                ai_flags = (AI_PASSIVE).convert()
            }
            val host = localHost
            val serv = localPort.toString()
            val resPtr = allocPointerTo<addrinfo>()
            val rc = getaddrinfo(host, serv, hints.ptr, resPtr.ptr)
            if (rc != 0) {
                val msg = gai_strerror(rc)?.toKString() ?: "getaddrinfo failed"
                throw UdpException("bind DNS error: $msg")
            }
            try {
                var ai = resPtr.value
                var lastErr: Int? = null
                var bound = false
                while (ai != null && !bound) {
                    if (platform.posix.bind(fd, ai.pointed.ai_addr, ai.pointed.ai_addrlen) == 0) {
                        bound = true
                    } else {
                        lastErr = errno
                        ai = ai.pointed.ai_next
                    }
                }
                if (!bound) throw UdpException("bind() failed", lastErr)
            } finally {
                freeaddrinfo(resPtr.value)
            }
        }
    }

    override suspend fun connect(remoteHost: String, remotePort: Int) = withContext(Dispatchers.Default) {
        openIfNeeded()
        // If the target is an IPv4 literal (e.g., 127.0.0.1), prefer an AF_INET socket to avoid v4-on-v6 issues.
        val isIPv4Literal = Regex("""^\d{1,3}(?:\.\d{1,3}){3}$""").matches(remoteHost)
        // Also detect IPv6 literals (e.g., ::1, 2001:db8::1, [::1]).
        val isIPv6Literal = remoteHost.contains(':')
        if (isIPv6Literal && !isIPv4Literal) {
            if (fd >= 0) closeFdQuiet(fd)
            fd = socket(AF_INET6, SOCK_DGRAM, IPPROTO_UDP)
            if (fd < 0) throw UdpException("socket() failed", errno)
            closed = false
            connected = false
        } else if (isIPv4Literal) {
            if (fd >= 0) closeFdQuiet(fd)
            fd = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
            if (fd < 0) throw UdpException("socket() failed", errno)
            closed = false
            connected = false
        }
        memScoped {
            // Normalize bracketed IPv6 literals like "[::1]" to "::1" for getaddrinfo.
            val lookupHost = if (remoteHost.startsWith("[") && remoteHost.endsWith("]")) {
                remoteHost.substring(1, remoteHost.length - 1)
            } else {
                remoteHost
            }
            val hints = alloc<addrinfo>().apply {
                ai_family = when {
                    isIPv4Literal -> AF_INET
                    isIPv6Literal -> AF_INET6
                    else -> AF_UNSPEC
                }
                ai_socktype = SOCK_DGRAM
                ai_protocol = IPPROTO_UDP
            }
            val resPtr = allocPointerTo<addrinfo>()
            val rc = getaddrinfo(lookupHost, remotePort.toString(), hints.ptr, resPtr.ptr)
            if (rc != 0) {
                val msg = gai_strerror(rc)?.toKString() ?: "getaddrinfo failed"
                throw UdpException("connect DNS error: $msg")
            }
            try {
                var ai = resPtr.value
                var ok = false
                var lastErr: Int? = null
                while (ai != null && !ok) {
                    if (platform.posix.connect(fd, ai.pointed.ai_addr, ai.pointed.ai_addrlen) == 0) {
                        ok = true
                    } else {
                        lastErr = errno
                        println("UDP connect attempt failed ${errnoNameDarwin(errno)} | fd=$fd family=${ai.pointed.ai_family} host=$remoteHost port=$remotePort")
                        ai = ai.pointed.ai_next
                    }
                }
                if (!ok) throw UdpException(
                    "UDP connect() failed ${lastErr?.let { "(${errnoNameDarwin(it)})" } ?: ""} | fd=$fd host=$remoteHost port=$remotePort",
                    lastErr
                )
                connected = true
            } finally {
                freeaddrinfo(resPtr.value)
            }
        }
    }

    override suspend fun send(data: ByteArray, host: String?, port: Int?): Int = withContext(Dispatchers.Default) {
        // For connected-send, require an already open socket.
        ensureOpen()
        data.usePinned { pinned ->
            if (host == null && port == null) {
                // send() to the connected peer
                if (!connected) throw UdpException("No peer: call connect() or provide host/port")
                val n = platform.posix.send(fd, pinned.addressOf(0), data.size.convert(), 0)
                if (n < 0) throw UdpException("send() failed", errno)
                n.toInt()
            } else {
                // sendto() to an explicit peer; allow opening on demand
                openIfNeeded()
                memScoped {
                    val hints = alloc<addrinfo>().apply {
                        ai_family = AF_UNSPEC
                        ai_socktype = SOCK_DGRAM
                        ai_protocol = IPPROTO_UDP
                    }
                    val resPtr = allocPointerTo<addrinfo>()
                    val rc = getaddrinfo(host ?: error("host required if port is provided"),
                        (port ?: error("port required if host is provided")).toString(),
                        hints.ptr, resPtr.ptr)
                    if (rc != 0) {
                        val msg = gai_strerror(rc)?.toKString() ?: "getaddrinfo failed"
                        throw UdpException("sendto DNS error: $msg")
                    }
                    try {
                        var ai = resPtr.value
                        var lastErr: Int? = null
                        while (ai != null) {
                            val n = sendto(fd, pinned.addressOf(0), data.size.convert(), 0, ai.pointed.ai_addr, ai.pointed.ai_addrlen)
                            if (n >= 0) return@memScoped n.toInt()
                            lastErr = errno
                            ai = ai.pointed.ai_next
                        }
                        throw UdpException("sendto() failed", lastErr)
                    } finally {
                        freeaddrinfo(resPtr.value)
                    }
                }
            }
        }
    }

    override suspend fun receive(maxBytes: Int): UdpPacket? = withContext(Dispatchers.Default) {
        ensureOpen()
        if (maxBytes <= 0) return@withContext UdpPacket(ByteArray(0), "", 0)
        memScoped {
            val storage = alloc<sockaddr_storage>()
            val addrLen = alloc<socklen_tVar>().apply { value = sizeOf<sockaddr_storage>().convert() }
            val buf = ByteArray(maxBytes)
            var nBytes = 0
            buf.usePinned { pinned ->
                nBytes = recvfrom(
                    fd,
                    pinned.addressOf(0),
                    maxBytes.convert(),
                    0,
                    storage.ptr.reinterpret(),
                    addrLen.ptr
                ).toInt()
                if (nBytes < 0) {
                    val e = errno
                    // If we were cancelled or the socket was closed, surface a cooperative cancellation instead of crashing.
                    val ctx = currentCoroutineContext()
                    if (closed || !ctx.isActive || e == EBADF) {
                        throw CancellationException("UDP receive cancelled")
                    }
                    // Timeout from SO_RCVTIMEO: return null so callers can poll and re-check state.
                    if (e == EAGAIN || e == EWOULDBLOCK) {
                        return@withContext null
                    }
                    throw UdpException("recvfrom() failed", e)
                }
            }
            // Decode peer address
            val (host, port) = sockaddrToHostPort(storage)
            UdpPacket(if (nBytes <= 0) ByteArray(0) else buf.copyOf(nBytes), host, port)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        val localFd = fd
        if (localFd >= 0) {
            // Interrupt any blocking recvfrom first
            var rc = shutdown(localFd, SHUT_RDWR)
            if (rc != 0 && errno == EINTR) {
                rc = shutdown(localFd, SHUT_RDWR) // one retry on EINTR
            }
            closeFdQuiet(localFd)
            fd = -1
        }
    }

    private fun ensureOpen() {
        if (closed || fd < 0) throw UdpException("socket is closed | fd=$fd closed=$closed")
    }

    private fun ensureClosedOrThrow() {
        if (fd >= 0 && !closed) throw UdpException("socket already open | fd=$fd closed=$closed")
    }

    private fun openIfNeeded() {
        if (fd < 0 || closed) {
            closed = false
            fd = socket(AF_INET6, SOCK_DGRAM, IPPROTO_UDP)
            if (fd < 0) {
                // Fallback to IPv4 if IPv6 socket creation fails
                fd = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
                if (fd < 0) throw UdpException("socket() failed", errno)
            } else {
                // Allow dual-stack if available (0 = allow v4-mapped)
                memScoped {
                    val zero = alloc<IntVar>()
                    zero.value = 0
                    setsockopt(fd, IPPROTO_IPV6, IPV6_V6ONLY, zero.ptr, sizeOf<IntVar>().convert())
                }
            }

            // REUSEADDR for quick rebinds during development
            memScoped {
                val one = alloc<IntVar>()
                one.value = 1
                setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, one.ptr, sizeOf<IntVar>().convert())
            }
            // Small receive timeout so blocking recvfrom wakes periodically; aids prompt shutdown.
            memScoped {
                val tv = alloc<timeval>()
                tv.tv_sec = 0
                tv.tv_usec = 250_000 // 250 ms
                val rc = setsockopt(
                    fd, SOL_SOCKET, SO_RCVTIMEO,
                    tv.ptr,                                   // ← no reinterpret
                    sizeOf<timeval>().convert()
                )
                if (rc != 0) {
                    val e = errno
                    // Non-fatal; timeout just improves shutdown responsiveness.
                    println("IosUdpSocket: SO_RCVTIMEO set failed errno=$e")
                }
            }
            println("UDP openIfNeeded: created fd=$fd (ipv6=${(fcntl(fd, F_GETFL) >= 0)}) closed=$closed")
        }
    }

    private fun closeFdQuiet(descriptor: Int) {
        var rc: Int
        do {
            rc = platform.posix.close(descriptor)
        } while (rc == -1 && errno == EINTR)
    }

    private fun sockaddrToHostPort(storage: sockaddr_storage): Pair<String, Int> = memScoped {
        val hostBuf = ByteArray(NI_MAXHOST)
        val servBuf = ByteArray(NI_MAXSERV)

        val rc = hostBuf.usePinned { hp ->
            servBuf.usePinned { sp ->
                getnameinfo(
                    storage.ptr.reinterpret(),
                    sizeOf<sockaddr_storage>().convert(),
                    hp.addressOf(0),
                    NI_MAXHOST.convert(),
                    sp.addressOf(0),
                    NI_MAXSERV.convert(),
                    (NI_NUMERICHOST or NI_NUMERICSERV)
                )
            }
        }
        if (rc != 0) {
            val msg = gai_strerror(rc)?.toKString() ?: "getnameinfo failed"
            throw UdpException("getnameinfo error: $msg")
        }

        val host = hostBuf.decodeToString().trimEnd(Char(0))
        val serv = servBuf.decodeToString().trimEnd(Char(0))
        host to serv.toIntOrNull().orEmptyPort()
    }
}

actual object SocketFactory {
    actual fun udp(): KmpUdpSocket = IosUdpSocket()
    actual fun tcp(): KmpTcpSocket = IosTcpSocket()
}

// --- TCP implementation (iOS POSIX) ---
@OptIn(ExperimentalForeignApi::class)
internal class IosTcpSocket : KmpTcpSocket {
    private var fd: Int = -1
    private var closed = false

    override suspend fun connect(host: String, port: Int) = withContext(Dispatchers.Default) {
        ensureClosedOrThrow()
        memScoped {
            val hints = alloc<addrinfo>().apply {
                ai_family = AF_UNSPEC
                ai_socktype = SOCK_STREAM
                ai_protocol = IPPROTO_TCP
            }
            val resPtr = allocPointerTo<addrinfo>()
            val rc = getaddrinfo(host, port.toString(), hints.ptr, resPtr.ptr)
            if (rc != 0) {
                val msg = gai_strerror(rc)?.toKString() ?: "getaddrinfo failed"
                throw TcpException("TCP DNS error: $msg")
            }
            try {
                var ai = resPtr.value
                var lastErrno: Int? = null
                var connected = false
                while (ai != null && !connected) {
                    val s = socket(ai.pointed.ai_family, ai.pointed.ai_socktype, ai.pointed.ai_protocol)
                    if (s >= 0) {
                        // Optional: disable Nagle
                        memScoped {
                            val one = alloc<IntVar>().apply { value = 1 }
                            setsockopt(s, IPPROTO_TCP, TCP_NODELAY, one.ptr, sizeOf<IntVar>().convert())
                        }
                        if (platform.posix.connect(s, ai.pointed.ai_addr, ai.pointed.ai_addrlen) == 0) {
                            fd = s
                            connected = true
                        } else {
                            lastErrno = errno
                            println("TCP connect attempt failed ${errnoNameDarwin(errno)} | family=${ai.pointed.ai_family} host=$host port=$port")
                            closeFdQuiet(s)
                        }
                    } else {
                        lastErrno = errno
                    }
                    ai = ai.pointed.ai_next
                }
                if (!connected) throw TcpException("TCP connect failed", lastErrno)
            } finally {
                freeaddrinfo(resPtr.value)
            }
        }
    }

    override suspend fun sendAll(data: ByteArray): Int = withContext(Dispatchers.Default) {
        ensureOpen()
        var total = 0
        data.usePinned { pinned ->
            while (total < data.size) {
                val sent = platform.posix.send(fd, pinned.addressOf(total), (data.size - total).convert(), 0).toInt()
                if (sent < 0) throw TcpException("TCP send failed", errno)
                if (sent == 0) break
                total += sent
            }
        }
        total
    }

    override suspend fun receive(maxBytes: Int): ByteArray = withContext(Dispatchers.Default) {
        ensureOpen()
        if (maxBytes <= 0) return@withContext ByteArray(0)
        val buf = ByteArray(maxBytes)
        var n = 0
        buf.usePinned { pinned ->
            n = recv(fd, pinned.addressOf(0), maxBytes.convert(), 0).toInt()
            if (n < 0) throw TcpException("TCP recv failed", errno)
        }
        if (n <= 0) ByteArray(0) else buf.copyOf(n)
    }

    override fun close() {
        if (closed) return
        closed = true
        val localFd = fd
        if (localFd >= 0) {
            // First, shutdown both directions to interrupt any blocking recvfrom.
            // Ignore errors (e.g., ENOTCONN) since UDP may not be "connected".
            var rc = shutdown(localFd, SHUT_RDWR)
            if (rc != 0 && errno == EINTR) {
                // Retry once on EINTR
                rc = shutdown(localFd, SHUT_RDWR)
            }
            closeFdQuiet(localFd)
            fd = -1
        }
    }

    private fun ensureOpen() {
        if (closed || fd < 0) throw TcpException("socket is closed | fd=$fd closed=$closed")
    }

    private fun ensureClosedOrThrow() {
        if (fd >= 0 && !closed) throw TcpException("socket already open | fd=$fd closed=$closed")
    }

    private fun closeFdQuiet(descriptor: Int) {
        var rc: Int
        do {
            rc = platform.posix.close(descriptor)
        } while (rc == -1 && errno == EINTR)
    }
}

private fun Int?.orEmptyPort(): Int = this ?: 0