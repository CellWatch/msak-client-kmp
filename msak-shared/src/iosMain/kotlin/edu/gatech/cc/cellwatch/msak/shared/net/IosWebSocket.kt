@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package edu.gatech.cc.cellwatch.msak.shared.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext
import kotlinx.cinterop.*
import platform.Foundation.*
import platform.posix.memcpy

private class IosWebSocket(
    private val task: NSURLSessionWebSocketTask,
    scope: CoroutineScope
) : KmpWebSocket {
    private val incomingChannel = Channel<WsMessage>(Channel.BUFFERED)
    override val incoming: Flow<WsMessage> = incomingChannel.receiveAsFlow()

    init {
        // Start the receive loop immediately
        receiveLoop()
    }

    private fun receiveLoop() {
        task.receiveMessageWithCompletionHandler { message, error ->
            if (error != null) {
                incomingChannel.close()
                return@receiveMessageWithCompletionHandler
            }
            when {
                message == null -> incomingChannel.close()
                message.type == NSURLSessionWebSocketMessageTypeString -> {
                    val text = message.string() ?: ""
                    incomingChannel.trySend(WsMessage.Text(text))
                    receiveLoop()
                }
                message.type == NSURLSessionWebSocketMessageTypeData -> {
                    val data = message.data()?.toByteArray() ?: byteArrayOf()
                    incomingChannel.trySend(WsMessage.Binary(data))
                    receiveLoop()
                }
                else -> {
                    // Unknown type; close the stream
                    incomingChannel.close()
                }
            }
        }
    }

    override suspend fun sendText(text: String) {
        withContext(Dispatchers.Default) {
            task.sendMessage(NSURLSessionWebSocketMessage(string = text)) { _ -> /* ignore */ }
        }
    }

    override suspend fun sendBinary(bytes: ByteArray) {
        withContext(Dispatchers.Default) {
            val nsData = bytes.toNSData()
            task.sendMessage(NSURLSessionWebSocketMessage(data = nsData)) { _ -> /* ignore */ }
        }
    }

    override suspend fun ping(payload: ByteArray?) {
        // NSURLSessionWebSocketTask does not support custom ping payloads; send a standard ping.
        withContext(Dispatchers.Default) {
            task.sendPingWithPongReceiveHandler { _ -> /* ignore */ }
        }
    }

    override suspend fun close(code: Int, reason: String?) {
        withContext(Dispatchers.Default) {
            task.cancelWithCloseCode(code.convert(), reason?.toNSData())
            incomingChannel.close()
        }
    }

    override fun close() {
        task.cancel()
        incomingChannel.close()
    }
}

actual object WebSocketFactory {
    actual suspend fun connect(url: String, headers: Map<String, String>): KmpWebSocket =
        withContext(Dispatchers.Default) {
            val config = NSURLSessionConfiguration.defaultSessionConfiguration()
            val session = NSURLSession.sessionWithConfiguration(
                configuration = config,
                delegate = null,
                delegateQueue = NSOperationQueue.mainQueue
            )
            val request = NSMutableURLRequest.requestWithURL(NSURL(string = url)!!).apply {
                headers.forEach { (key, value) -> setValue(value, forHTTPHeaderField = key) }
            }
            val task = session.webSocketTaskWithRequest(request)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            task.resume()
            IosWebSocket(task, scope)
        }
}

/* ---------- NSData / ByteArray helpers ---------- */

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
private fun ByteArray.toNSData(): NSData = memScoped {
    val md = NSMutableData.dataWithLength(this@toNSData.size.toULong()) as NSMutableData
    this@toNSData.usePinned { pinned ->
        memcpy(md.mutableBytes, pinned.addressOf(0), this@toNSData.size.convert())
    }
    md as NSData
}

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val len = length.toInt()
    if (len == 0) return ByteArray(0)
    val out = ByteArray(len)
    out.usePinned { pinned ->
        // Use Foundation API to copy into our buffer
        this.getBytes(pinned.addressOf(0), length)
    }
    return out
}

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
private fun String.toNSData(): NSData =
    this.encodeToByteArray().toNSData()