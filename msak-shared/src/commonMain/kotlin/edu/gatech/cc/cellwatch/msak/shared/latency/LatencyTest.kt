package edu.gatech.cc.cellwatch.msak.shared.latency

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import kotlin.time.Duration.Companion.milliseconds

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.atomicfu.atomic

import edu.gatech.cc.cellwatch.msak.shared.Log
import edu.gatech.cc.cellwatch.msak.shared.LATENCY_CHARSET
import edu.gatech.cc.cellwatch.msak.shared.LATENCY_DURATION
import edu.gatech.cc.cellwatch.msak.shared.LATENCY_QUIET_THRESHOLD
import edu.gatech.cc.cellwatch.msak.shared.latencyEchoCeilingMs
import edu.gatech.cc.cellwatch.msak.shared.Server
import edu.gatech.cc.cellwatch.msak.shared.net.NetHttp
import edu.gatech.cc.cellwatch.msak.shared.net.SocketFactory
import edu.gatech.cc.cellwatch.msak.shared.net.*

import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A latency test implemented with KMP primitives (Ktor client + UDP socket).
 *
 * @param server The Server against which to run the test.
 * @param measurementId Optional measurement id for MSAK control plane.
 * @param latencyPort UDP port used by the server for latency.
 * @param duration Duration in milliseconds to keep echoing packets after first response.
 * @param retryDelay Initial delay before re-sending the initial packet if no reply arrives.
 * @param retryBackoff Linear backoff added per retry attempt for the initial packet.
 * @param userAgent Optional User-Agent for control-plane requests.
 */
class LatencyTest(
    private val server: Server,
    private val measurementId: String? = null,
    private val latencyPort: Int = 1053,
    private val duration: Long = LATENCY_DURATION,
    private val retryDelay: Long = 1000L,
    private val retryBackoff: Long = 500L,
    private val userAgent: String? = null,
) {
    private val TAG = this::class.simpleName

    // Lifecycle management for background work.
    //
    // This scope is detached from the caller on purpose (start() is not a suspend
    // function). That makes an uncaught exception here fatal on Kotlin/Native, so
    // the scope carries a handler and nothing inside ever rethrows: failures are
    // recorded and surfaced by runLatency(), the structured error boundary.
    private val scope = CoroutineScope(
        Dispatchers.Default + SupervisorJob() + CoroutineExceptionHandler { _, t ->
            recordFailure(t)
        }
    )
    private var job: Job? = null
    private var activeSocket: KmpUdpSocket? = null

    // Terminal state, written exactly once.
    private val failure = atomic<Throwable?>(null)
    private val wasCancelled = atomic(false)
    private val finished = atomic(false)
    private val endSignal = CompletableDeferred<Unit>()

    // Control-plane endpoints
    private val authorizeUrl = server.getLatencyAuthorizeUrl(measurementId)
    private val resultUrl = server.getLatencyResultUrl(measurementId)

    // Permissive JSON for interop with differing server payloads
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    // Updates channel and snapshot
    private val _updatesChan = Channel<LatencyUpdate>(capacity = 32)
    private val _updates = ArrayList<LatencyUpdate>()
    val updatesChan: ReceiveChannel<LatencyUpdate> = _updatesChan
    val updates: List<LatencyUpdate> = _updates

    // Timestamps
    var startTime: Instant? = null; private set
    var endTime: Instant? = null; private set
    var started: Boolean = false; private set
    val ended: Boolean get() = endTime != null

    // Result / error after completion
    var result: LatencyResult? = null
        private set

    /** First non-cancellation failure recorded by the test, if any. */
    val error: Throwable? get() = failure.value

    /** Mirror ThroughputTest: expose the most recent error via a stable name. */
    val lastError: Throwable? get() = error

    /** True if the test stopped because it was cancelled rather than because it failed. */
    val cancelled: Boolean get() = wasCancelled.value

    // Hostname resolved from the authorize URL
    val serverHost: String = Url(authorizeUrl).host

    /**
     * Begin the latency test asynchronously. Collect updates on [updatesChan] until it closes.
     */
    fun start() {
        if (started) throw IllegalStateException("already started")
        started = true
        job = scope.launch {
            try {
                run()
            } catch (t: Throwable) {
                if (t is CancellationException) {
                    // Treat cancellation as a normal shutdown; do not mark as error.
                    Log.i(TAG, "latency test cancelled")
                    wasCancelled.value = true
                } else {
                    Log.i(TAG, "latency test error", t)
                    recordFailure(t)
                }
                // Deliberately NOT rethrown. This coroutine has no caller to catch
                // it: on Kotlin/Native a rethrow here reaches the uncaught exception
                // handler and terminates the host app. runLatency() reads
                // [error]/[cancelled] after the updates channel closes instead.
            } finally {
                finish()
            }
        }
        // Ensure any pending socket read is unblocked on completion
        job?.invokeOnCompletion { runCatching { activeSocket?.close() } }
    }

    /** Abort early. Idempotent and safe to call before [start]. */
    fun stop() {
        // runLatency() calls stop() in its finally block even on success, so only
        // mark a cancellation when the test had not already reached a terminal state.
        if (!finished.value) wasCancelled.value = true
        // Closing the socket unblocks a pending receive on native targets.
        activeSocket?.let { runCatching { it.close() } }
        job?.cancel()
        if (!started) finish()
    }

    /** Suspend until the test has fully ended and released its resources. */
    suspend fun awaitEnd() = endSignal.await()

    // Core flow
    private suspend fun run() = withContext(Dispatchers.Default) {
        val auth = authorize()
        val initialMessage = LatencyMessage(
            Type = auth.Type,
            ID = auth.ID,
            Seq = auth.Seq,
            LastRTT = null
        )
        Log.d(TAG, "got authorize response; Type=${auth.Type} ID=${auth.ID} Seq=${auth.Seq}")

        // Open UDP socket to serverHost:resolvedLatencyPort
        val sock = SocketFactory.udp()
        activeSocket = sock
        coroutineContext.ensureActive()
        val resolvedPort = if (latencyPort > 0) latencyPort else (server.latencyUdpPort ?: 1053)
        try {
            Log.d(TAG, "connecting UDP → host=$serverHost port=$resolvedPort")
            sock.connect(serverHost, resolvedPort)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Log.i(TAG, "UDP connect failed", t)
            throw InitialPacketTimeoutException(t)
        }

        try {
            echoPackets(sock, initialMessage)
            result = getResult()
            Log.d(TAG, "got latency result: $result")
        } finally {
            runCatching { sock.close() }
            activeSocket = null
        }
    }

    /**
     * Terminal cleanup. Runs exactly once no matter which path ends the test
     * (success, failure, cancellation, or stop() before start()).
     */
    private fun finish() {
        if (!finished.compareAndSet(expect = false, update = true)) return
        endTime = Clock.System.now()
        activeSocket?.let { runCatching { it.close() } }
        activeSocket = null
        _updatesChan.close()
        endSignal.complete(Unit)
    }

    /** Record the first non-cancellation failure; later ones are logged only. */
    private fun recordFailure(t: Throwable) {
        if (t is CancellationException) {
            wasCancelled.value = true
            return
        }
        if (!failure.compareAndSet(null, t)) {
            Log.d(TAG, "additional latency failure ignored: ${t::class.simpleName}: ${t.message}")
        }
    }

    private fun recordUpdate(update: LatencyUpdate) {
        val r = _updatesChan.trySend(update)
        if (!r.isSuccess) {
            Log.d(TAG, "failed to send latency message on channel: $r")
        }
        if (!r.isClosed) {
            _updates.add(update)
        }
    }

    // ----- Control plane (HTTP via Ktor) -----

    private suspend fun authorize(): LatencyAuthorization {
        Log.d(TAG, "authorize → $authorizeUrl")
        val client = ensureHttp()

        val resp = try {
            client.get(authorizeUrl) {
                header("Accept", "application/json")
                userAgent?.let { header("User-Agent", it) }
                if (serverHost == "10.0.2.2" || serverHost == "127.0.0.1") {
                    header("Host", "localhost")
                }
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Log.e(TAG, "authorize: HTTP request failed", t)
            throw AuthorizeFailureExecption(t)
        }

        val status = resp.status.value
        val body = runCatching { resp.bodyAsText() }.getOrElse { "" }

        if (status !in 200..299) {
            val hdrs = try { resp.headers.entries().joinToString { (k,v) -> "$k=${v.joinToString()}" } } catch (_: Throwable) { "<no headers>" }
            Log.e(TAG, "authorize: non-2xx status $status; headers=[$hdrs]; body='${body.take(200)}'")
            throw UnauthorizedException("authorize call returned HTTP $status")
        }
        if (body.isBlank()) {
            Log.e(TAG, "authorize: empty response body from $authorizeUrl")
            throw UnauthorizedException("authorize call returned an empty body")
        }

        return try {
            json.decodeFromString<LatencyAuthorization>(body)
        } catch (e: SerializationException) {
            Log.e(TAG, "authorize: JSON decode failed; body='${body.take(200)}'", e)
            throw UnauthorizedException("authorize response was not valid JSON", e)
        }
    }

    private suspend fun getResult(): LatencyResult {
        val client = ensureHttp()
        val text = try {
            client.get(resultUrl).bodyAsText()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Log.e(TAG, "results: HTTP request failed", t)
            throw ResultFailureException(t)
        }
        return try {
            json.decodeFromString<LatencyResult>(text)
        } catch (e: SerializationException) {
            Log.e(TAG, "results: JSON decode failed; body='$text'", e)
            throw NoResultException(e)
        }
    }

    private fun ensureHttp() = try {
        NetHttp.client
    } catch (_: Throwable) {
        NetHttp.initialize()
        NetHttp.client
    }

    // ----- Data plane (UDP via KMP socket) -----

    /**
     * Send the initial JSON message, retry a few times until the first reply arrives.
     * After the first reply, echo any payloads back to the server for [duration] ms,
     * recording each update.
     */
    private suspend fun echoPackets(sock: KmpUdpSocket, initialMessage: LatencyMessage) {
        // No coroutine-level poll timeout here: the socket already has
        // SO_RCVTIMEO (250ms) and receive() returns null when it expires.
        // Wrapping it in withTimeoutOrNull(200ms) meant the coroutine timeout
        // always fired FIRST, abandoning a recvfrom still blocked in a
        // Dispatchers.Default worker and immediately starting another. That went
        // unnoticed while the echo window was shorter than the server's send
        // loop, because the loop then always exited on a received packet and
        // never polled silence.

        coroutineContext.ensureActive()

        /*
         * This JSON is manually constructed with hard-coded property names.
         * Reason: guarantees exact wire format the server expects, avoids potential drift from kotlinx.serialization.
         * Limitation: no compile-time coordination with the LatencyMessage data class.
         * Long-term requirement: if the server schema or the LatencyMessage fields change,
         * this builder must be updated in lockstep.
         */
        val initialJson = buildString {
            append('{')
            append("\"Type\":\"").append(initialMessage.Type).append('"')
            append(",\"ID\":\"").append(initialMessage.ID).append('"')
            append(",\"Seq\":").append(initialMessage.Seq)
            initialMessage.LastRTT?.let { append(",\"LastRTT\":").append(it) }
            append('}')
        }
        val initialBytes = initialJson.encodeToByteArray()

        startTime = Clock.System.now()
        var gotFirst = false
        var firstReplyTimedOut = false

        // Retry-sender for the initial packet until first response
        val retryJob: Job = scope.launch {
            val maxAttempts = 3
            var rem = maxAttempts
            while (!gotFirst && rem > 0 && isActive) {
                coroutineContext.ensureActive()
                Log.d(TAG, "sending initial packet; ${rem - 1} attempt(s) remaining")
                runCatching { sock.send(initialBytes) }.onFailure {
                    Log.e(TAG, "initial UDP send failed", it)
                }
                val backoff = retryDelay + retryBackoff * (maxAttempts - rem)
                try {
                    delay(backoff)
                } catch (_: CancellationException) { return@launch }
                rem--
            }
            if (!gotFirst) {
                Log.i(TAG, "no response to initial packet; timing out")
                firstReplyTimedOut = true
                // Closing the socket will unblock any pending receive on native targets.
                runCatching { sock.close() }
            }
        }

        // Receive / echo loop. Stop ~[duration] ms after first reply.
        val rxBufSize = 2048
        // Termination is observed, not timed: the loop ends once the server has
        // been quiet for LATENCY_QUIET_THRESHOLD. `ceiling` is only a backstop
        // for a server that never stops sending.
        var ceiling: Instant? = null
        var lastPacketAt: Instant? = null
        try {
            while (true) {
                coroutineContext.ensureActive()
                val pkt = sock.receive(rxBufSize)
                if (pkt == null) {
                    // SO_RCVTIMEO expired with nothing to read. If the server has
                    // been quiet well past its maximum send interval (40ms), it
                    // has finished and so have we.
                    val idleNow = Clock.System.now()
                    val quietFor = lastPacketAt?.let { idleNow - it }
                    if (quietFor != null && quietFor.inWholeMilliseconds >= LATENCY_QUIET_THRESHOLD) {
                        Log.d(TAG, "server quiet for ${quietFor.inWholeMilliseconds}ms; latency data plane complete")
                        break
                    }
                    if (ceiling != null && idleNow >= ceiling) {
                        Log.i(TAG, "latency echo ceiling reached without observing silence")
                        break
                    }
                    continue
                }

                val now = Clock.System.now()
                if (!gotFirst) {
                    gotFirst = true
                    retryJob.cancel()
                    // Echo for at least as long as the server sends.
                    //
                    // The server's send loop runs for a fixed
                    // `sendDuration = 5 * time.Second` on its own context
                    // (msak internal/latency1/latency1.go), independent of the
                    // client, and it marks every packet Lost until the echo
                    // arrives. Stopping earlier - which is what a caller asking
                    // for a shorter duration used to do - leaves the remainder
                    // permanently unechoed and the server reports them as lost.
                    // Measured against a real m-lab server, a 3s window reported
                    // 6.2% loss on a clean link; covering the full window reports
                    // 0.00% and yields 219 samples instead of 145.
                    ceiling = now + latencyEchoCeilingMs(duration).milliseconds
                    coroutineContext.ensureActive()
                }

                // Decode message and record
                val bytes = pkt.data
                val payload = bytes.decodeToString()
                val msg = try {
                    json.decodeFromString<LatencyMessage>(payload)
                } catch (e: SerializationException) {
                    Log.e(TAG, "latency message decode failed; payload='$payload'", e)
                    continue
                }
                recordUpdate(LatencyUpdate(now, msg))

                coroutineContext.ensureActive()
                // Echo back
                runCatching { sock.send(bytes) }.onFailure {
                    if (!ended) Log.e(TAG, "failed to echo UDP packet", it)
                }

                lastPacketAt = now

                // Only the backstop applies on the packet path; normal
                // termination happens above, once the server goes quiet.
                if (ceiling != null && now >= ceiling) {
                    Log.i(TAG, "latency echo ceiling reached while server still sending")
                    break
                }
            }
        } finally {
            retryJob.cancel()
        }
        if (!gotFirst && firstReplyTimedOut) {
            throw InitialPacketTimeoutException()
        }
    }

    // ----- Exceptions matching previous semantics -----
    class AuthorizeFailureExecption(cause: Throwable? = null):
        Exception("authorize call failed", cause)
    class UnauthorizedException(
        message: String = "authorize call returned bad response",
        cause: Throwable? = null,
    ): Exception(message, cause)
    class ResultFailureException(cause: Throwable? = null):
        Exception("result call failed", cause)
    class NoResultException(cause: Throwable? = null):
        Exception("result call returned bad response", cause)
    class InitialPacketTimeoutException(cause: Throwable? = null):
        Exception("initial packet timeout", cause)
    class NoAddrException(cause: Throwable? = null):
        Exception("could not resolve server addr", cause)
}
