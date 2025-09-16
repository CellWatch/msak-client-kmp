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
import kotlinx.coroutines.delay
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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

import edu.gatech.cc.cellwatch.msak.shared.Log
import edu.gatech.cc.cellwatch.msak.shared.LATENCY_CHARSET
import edu.gatech.cc.cellwatch.msak.shared.LATENCY_DURATION
import edu.gatech.cc.cellwatch.msak.shared.Server
import edu.gatech.cc.cellwatch.msak.shared.net.NetHttp
import edu.gatech.cc.cellwatch.msak.shared.net.SocketFactory
import edu.gatech.cc.cellwatch.msak.shared.net.*

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

    // Lifecycle management for background work
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var job: Job? = null
    private var activeSocket: KmpUdpSocket? = null // Was marked @vVolatile

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
    var error: Throwable? = null

    // Hostname resolved from the authorize URL
    val serverHost: String = Url(authorizeUrl).host

    /**
     * Begin the latency test asynchronously. Collect updates on [updatesChan] until it closes.
     */
    fun start() {
        if (started) error("already started")
        started = true
        job = scope.launch {
            try {
                run()
            } catch (t: Throwable) {
                Log.i(TAG, "latency test error", t)
                error = t
            } finally {
                finish()
                _updatesChan.close()
            }
        }
    }

    /** Abort early. */
    fun stop() {
        if (!started) error("can't stop before starting")
        // Closing the socket unblocks a blocking receive on native targets.
        activeSocket?.let { runCatching { it.close() } }
        job?.cancel()
        finish()
    }

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

        // Open UDP socket to serverHost:latencyPort
        val sock = SocketFactory.udp()
        activeSocket = sock
        try {
            sock.connect(serverHost, latencyPort)
        } catch (t: Throwable) {
            Log.i(TAG, "UDP connect failed", t)
            throw InitialPacketTimeoutException()
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

    private fun finish() {
        if (ended) return
        endTime = Clock.System.now()
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
            Log.e(TAG, "authorize: HTTP request failed", t)
            throw AuthorizeFailureExecption()
        }

        val status = resp.status.value
        val body = runCatching { resp.bodyAsText() }.getOrElse { "" }

        if (status !in 200..299) {
            val hdrs = try { resp.headers.entries().joinToString { (k,v) -> "$k=${v.joinToString()}" } } catch (_: Throwable) { "<no headers>" }
            Log.e(TAG, "authorize: non-2xx status $status; headers=[$hdrs]; body='${body.take(200)}'")
            throw UnauthorizedException()
        }
        if (body.isBlank()) {
            Log.e(TAG, "authorize: empty response body from $authorizeUrl")
            throw UnauthorizedException()
        }

        return try {
            json.decodeFromString<LatencyAuthorization>(body)
        } catch (e: SerializationException) {
            Log.e(TAG, "authorize: JSON decode failed; body='${body.take(200)}'", e)
            throw UnauthorizedException()
        }
    }

    private suspend fun getResult(): LatencyResult {
        val client = ensureHttp()
        val text = client.get(resultUrl).bodyAsText()
        return try {
            json.decodeFromString<LatencyResult>(text)
        } catch (e: SerializationException) {
            Log.e(TAG, "results: JSON decode failed; body='$text'", e)
            throw NoResultException()
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

        // Retry-sender for the initial packet until first response
        val retryJob: Job = scope.launch {
            val maxAttempts = 3
            var rem = maxAttempts
            while (!gotFirst && rem > 0 && isActive) {
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
                Log.i(TAG, "no response to initial packet")
            }
        }

        // Receive / echo loop. Stop ~[duration] ms after first reply.
        val rxBufSize = 2048
        var stopDeadline: Instant? = null
        try {
            while (true) {
                val pkt = try {
                    sock.receive(rxBufSize) // adjust if your API exposes a timeout overload
                } catch (t: Throwable) {
                    if (ended) break
                    // keep polling
                    continue
                }
                if (pkt == null) continue
                val now = Clock.System.now()
                if (!gotFirst) {
                    gotFirst = true
                    retryJob.cancel()
                    stopDeadline = now + duration.milliseconds
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

                // Echo back
                runCatching { sock.send(bytes) }.onFailure {
                    if (!ended) Log.e(TAG, "failed to echo UDP packet", it)
                }

                // Check deadline
                if (stopDeadline != null && now >= stopDeadline) {
                    break
                }
            }
        } finally {
            retryJob.cancel()
        }
    }

    // ----- Exceptions matching previous semantics -----
    class AuthorizeFailureExecption: Exception("authorize call failed")
    class UnauthorizedException: Exception("authorize call returned bad response")
    class ResultFailureException: Exception("result call failed")
    class NoResultException: Exception("result call returned bad response")
    class InitialPacketTimeoutException: Exception("initial packet timeout")
    class NoAddrException: Exception("could not resolve server addr")
}
