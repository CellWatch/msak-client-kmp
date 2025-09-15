package edu.gatech.cc.cellwatch.msak.shared.net

import kotlinx.coroutines.flow.Flow

/**
 * Multiplatform WebSocket minimal interface.
 *
 * Implementations should:
 *  - Support ws:// and wss://.
 *  - Start a receive loop immediately after connect, emitting messages on [incoming].
 *  - Map platform connection/IO errors to stream completion on [incoming].
 */
sealed interface WsMessage {
    data class Text(val text: String) : WsMessage
    data class Binary(val bytes: ByteArray) : WsMessage
}

interface KmpWebSocket : AutoCloseable {
    /** Stream of incoming messages; completes when the socket closes. */
    val incoming: Flow<WsMessage>

    /** Send a text message. */
    suspend fun sendText(text: String)

    /** Send a binary message. */
    suspend fun sendBinary(bytes: ByteArray)

    /**
     * Optional ping. Implementations may ignore payloads if unsupported.
     * Safe to no-op on platforms without explicit ping control.
     */
    suspend fun ping(payload: ByteArray? = null)

    /**
     * Close the WebSocket gracefully.
     * @param code WebSocket close code (default 1000: normal closure).
     * @param reason Optional UTF-8 reason.
     */
    suspend fun close(code: Int = 1000, reason: String? = null)

    /** Close immediately without the WebSocket closing handshake. */
    override fun close()
}

/**
 * Factory for platform WebSocket clients.
 */
expect object WebSocketFactory {
    /**
     * Connect to the given WebSocket URL (ws:// or wss://) and return a live socket.
     * Implementations should honor [headers] during the HTTP(S) upgrade handshake.
     */
    suspend fun connect(url: String, headers: Map<String, String> = emptyMap()): KmpWebSocket
}