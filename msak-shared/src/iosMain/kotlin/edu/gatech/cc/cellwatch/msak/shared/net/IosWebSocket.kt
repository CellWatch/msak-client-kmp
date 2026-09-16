@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package edu.gatech.cc.cellwatch.msak.shared.net

import edu.gatech.cc.cellwatch.msak.shared.Log
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.cinterop.*
import platform.Foundation.*
import platform.posix.memcpy
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "IosWebSocket"

/**
 * How many data sends may be outstanding on one socket at a time.
 *
 * Awaiting every send serialises the upload loop and measurably halves upload
 * throughput on a fast link (16.8 -> 8.6 Gbit/s over loopback in local testing),
 * while fire-and-forget sends give no back-pressure and, in the original code,
 * silently dropped completion errors. A small window keeps the pipe full and
 * still bounds queued memory.
 */
private const val MAX_IN_FLIGHT_SENDS = 8

/**
 * NSURLSessionWebSocketTask-backed [KmpWebSocket].
 *
 * Error handling contract (this is the part that used to be missing):
 *  - `sendMessage` completion errors are NOT discarded. Sends suspend until the
 *    completion handler fires and throw [WebSocketException] on failure, so the
 *    caller can turn a failed send into structured measurement failure state.
 *  - A receive failure closes [incoming] *with* its cause, so a collector sees a
 *    failure rather than an indistinguishable end-of-stream.
 *  - Terminations we asked for (close/cancel) and peer-initiated close frames
 *    still complete [incoming] normally: they are not measurement failures.
 */
private class IosWebSocket(
    private val task: NSURLSessionWebSocketTask,
    private val session: NSURLSession,
) : KmpWebSocket {
    private val incomingChannel = Channel<WsMessage>(Channel.BUFFERED)
    override val incoming: Flow<WsMessage> = incomingChannel.receiveAsFlow()

    /** Set when this side initiates the teardown, so it is not reported as failure. */
    private val closedLocally = atomic(false)

    /** Guards the single close of [incomingChannel] and the session teardown. */
    private val terminated = atomic(false)

    /** Bounds outstanding data sends; released by each send's completion handler. */
    private val inFlightSends = Semaphore(MAX_IN_FLIGHT_SENDS)

    /** First asynchronous send failure, surfaced on the next send. */
    private val sendFailure = atomic<Throwable?>(null)

    init {
        // Start the receive loop immediately
        receiveLoop()
    }

    private fun receiveLoop() {
        task.receiveMessageWithCompletionHandler { message, error ->
            if (error != null) {
                terminate(failureCauseOrNull(error))
                return@receiveMessageWithCompletionHandler
            }
            when {
                message == null -> terminate(null)
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
                    // Unknown message type: we cannot keep reading meaningfully.
                    terminate(WebSocketException("unsupported websocket message type"))
                }
            }
        }
    }

    /**
     * Close [incoming] exactly once, attaching [cause] when the termination was a
     * genuine failure and null when it was a normal end of stream.
     */
    private fun terminate(cause: Throwable?) {
        if (!terminated.compareAndSet(expect = false, update = true)) return
        if (cause != null) {
            Log.d(TAG, "websocket terminated with failure: ${cause.message}")
        }
        incomingChannel.close(cause)
        session.finishTasksAndInvalidate()
    }

    /**
     * Decide whether an NSError on the receive path is a measurement failure.
     *
     * Not a failure:
     *  - we cancelled/closed the task ourselves;
     *  - the peer sent a close frame (normal end of an MSAK throughput test --
     *    the server is what ends a download).
     */
    private fun failureCauseOrNull(error: NSError): WebSocketException? {
        if (closedLocally.value) return null
        if (error.domain == NSURLErrorDomain && error.code == NSURLErrorCancelled) return null
        if (task.closeCode != NSURLSessionWebSocketCloseCodeInvalid) return null
        return WebSocketException(
            "websocket receive failed: ${error.localizedDescription} " +
                "(${error.domain} ${error.code})"
        )
    }

    /**
     * Control-plane messages are low volume, so wait for the completion handler
     * and report a failure to the caller immediately.
     */
    override suspend fun sendText(text: String) {
        checkNoPriorSendFailure()
        withContext(Dispatchers.Default) {
            suspendCancellableCoroutine { cont: CancellableContinuation<Unit> ->
                task.sendMessage(NSURLSessionWebSocketMessage(string = text)) completion@{ error ->
                    if (!cont.isActive) return@completion
                    if (error != null) {
                        val e = sendException("text", error)
                        recordSendFailure(e)
                        cont.resumeWithException(e)
                    } else {
                        cont.resume(Unit)
                    }
                }
            }
        }
    }

    /**
     * Data-plane sends are pipelined up to [MAX_IN_FLIGHT_SENDS] and their
     * completion errors are recorded, not discarded: the failure terminates the
     * socket and is rethrown to the caller on the next send.
     *
     * The original implementation passed an empty completion block, so a broken
     * upload stream kept spinning while every send looked successful.
     */
    override suspend fun sendBinary(bytes: ByteArray) {
        checkNoPriorSendFailure()
        val message = NSURLSessionWebSocketMessage(data = bytes.toNSData())
        inFlightSends.acquire()
        var handedOff = false
        try {
            task.sendMessage(message) completion@{ error ->
                inFlightSends.release()
                if (error != null) {
                    val e = sendException("binary", error)
                    recordSendFailure(e)
                    // Terminate the socket so the stream's receive loop ends and
                    // the failure becomes structured test state.
                    terminate(e)
                }
            }
            handedOff = true
        } finally {
            if (!handedOff) inFlightSends.release()
        }
    }

    private fun checkNoPriorSendFailure() {
        sendFailure.value?.let { throw it }
    }

    private fun recordSendFailure(t: Throwable) {
        if (!sendFailure.compareAndSet(null, t)) return
        Log.d(TAG, "websocket send failed: ${t.message}")
    }

    private fun sendException(kind: String, error: NSError) = WebSocketException(
        "websocket $kind send failed: ${error.localizedDescription} " +
            "(${error.domain} ${error.code})"
    )

    override suspend fun ping(payload: ByteArray?) {
        // NSURLSessionWebSocketTask does not support custom ping payloads; send a standard ping.
        withContext(Dispatchers.Default) {
            suspendCancellableCoroutine { cont: CancellableContinuation<Unit> ->
                task.sendPingWithPongReceiveHandler { error ->
                    if (!cont.isActive) return@sendPingWithPongReceiveHandler
                    if (error != null) {
                        cont.resumeWithException(
                            WebSocketException(
                                "websocket ping failed: ${error.localizedDescription} " +
                                    "(${error.domain} ${error.code})"
                            )
                        )
                    } else {
                        cont.resume(Unit)
                    }
                }
            }
        }
    }

    override suspend fun close(code: Int, reason: String?) {
        withContext(Dispatchers.Default) {
            closedLocally.value = true
            task.cancelWithCloseCode(code.convert(), reason?.toNSData())
            terminate(null)
        }
    }

    override fun close() {
        closedLocally.value = true
        task.cancel()
        terminate(null)
    }
}

actual object WebSocketFactory {
    actual suspend fun connect(url: String, headers: Map<String, String>): KmpWebSocket =
        withContext(Dispatchers.Default) {
            val nsUrl = NSURL(string = url)
            val config = NSURLSessionConfiguration.defaultSessionConfiguration()
            // A dedicated serial queue, NOT NSOperationQueue.mainQueue.
            //
            // NSURLSession delivers task completion handlers on the session's
            // delegate queue. On the main queue that means every websocket
            // receive, send completion and error hop through the UI thread: a
            // busy or blocked main thread stalls the measurement and, worse,
            // hides receive errors entirely (the failure is then only noticed
            // by the client-side watchdog, long after the fact).
            val queue = NSOperationQueue().apply {
                maxConcurrentOperationCount = 1
                name = "edu.gatech.cc.cellwatch.msak.websocket"
            }
            val session = NSURLSession.sessionWithConfiguration(
                configuration = config,
                delegate = null,
                delegateQueue = queue
            )
            val request = NSMutableURLRequest.requestWithURL(nsUrl).apply {
                headers.forEach { (key, value) -> setValue(value, forHTTPHeaderField = key) }
            }
            val task = session.webSocketTaskWithRequest(request)
            task.resume()
            IosWebSocket(task, session)
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
