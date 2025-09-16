package edu.gatech.cc.cellwatch.msak.shared.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.security.KeyStore
import java.security.Security
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Android actual for KmpWebSocket using OkHttp WebSocket.
 *
 * Transport-layer byte counts:
 * OkHttp does not expose TLS byte counters for WebSockets. If you require
 * transport-layer counts over TLS (wss://), install Conscrypt in your app
 * initialization:
 *
 *   Security.insertProviderAt(org.conscrypt.Conscrypt.newProvider(), 1)
 *
 * This implementation will prefer Conscrypt automatically if it is present.
 * Application-layer byte counts can be computed by summing message sizes.
 */
private class AndroidWebSocket(
    private val ws: WebSocket
) : KmpWebSocket {

    private val incomingChannel = Channel<WsMessage>(Channel.BUFFERED)
    override val incoming: Flow<WsMessage> = incomingChannel.receiveAsFlow()

    override suspend fun sendText(text: String) {
        withContext(Dispatchers.IO) {
            ws.send(text)
        }
    }

    override suspend fun sendBinary(bytes: ByteArray) {
        withContext(Dispatchers.IO) {
            ws.send(ByteString.of(*bytes))
        }
    }

    /**
     * OkHttp does not provide an explicit ping API on WebSocket.
     * It manages pings internally. This is a no-op.
     */
    override suspend fun ping(payload: ByteArray?) {
        // no-op
    }

    override suspend fun close(code: Int, reason: String?) {
        withContext(Dispatchers.IO) {
            try {
                ws.close(code, reason)
            } finally {
                incomingChannel.close()
            }
        }
    }

    override fun close() {
        try {
            ws.cancel()
        } catch (_: Throwable) {
            // ignore
        } finally {
            incomingChannel.close()
        }
    }

    fun onText(t: String) {
        incomingChannel.trySend(WsMessage.Text(t))
    }
    fun onBinary(b: ByteString) {
        incomingChannel.trySend(WsMessage.Binary(b.toByteArray()))
    }
    fun onClosed() {
        incomingChannel.close()
    }
    fun onFailure(@Suppress("UNUSED_PARAMETER") t: Throwable) {
        incomingChannel.close()
    }
}

actual object WebSocketFactory {

    // Single OkHttpClient instance shared across sockets
    private val client: OkHttpClient by lazy {
        val builder = OkHttpClient.Builder()
        // Prefer Conscrypt if the app installed it. This helps with TLS byte counters.
        try {
            val hasConscrypt = Security.getProviders().any { it.name.equals("Conscrypt", ignoreCase = true) }
            if (hasConscrypt) {
                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(null, null, null)
                builder.sslSocketFactory(sslContext.socketFactory, defaultTrustManager())
            }
        } catch (_: Throwable) {
            // fall back to platform TLS
        }
        builder.build()
    }

    actual suspend fun connect(url: String, headers: Map<String, String>): KmpWebSocket =
        withContext(Dispatchers.IO) {
            val reqBuilder = Request.Builder().url(url)
            headers.forEach { (k, v) -> reqBuilder.addHeader(k, v) }
            val request = reqBuilder.build()

            lateinit var socketWrapper: AndroidWebSocket

            val listener = object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    socketWrapper.onText(text)
                }
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    socketWrapper.onBinary(bytes)
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    socketWrapper.onClosed()
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    socketWrapper.onFailure(t)
                }
            }

            val ws = client.newWebSocket(request, listener)
            socketWrapper = AndroidWebSocket(ws)

            // Note: OkHttp begins the connection asynchronously. The returned wrapper is usable immediately.
            socketWrapper
        }

    private fun defaultTrustManager(): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        val tm = factory.trustManagers.firstOrNull { it is X509TrustManager }
            ?: error("No X509TrustManager found")
        return tm as X509TrustManager
    }
}