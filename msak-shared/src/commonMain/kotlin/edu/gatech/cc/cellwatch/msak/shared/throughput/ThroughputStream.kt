// commonMain/edu/.../throughput/ThroughputStreamKmp.kt
package edu.gatech.cc.cellwatch.msak.shared.throughput

import edu.gatech.cc.cellwatch.msak.shared.net.KmpWebSocket
import edu.gatech.cc.cellwatch.msak.shared.net.WebSocketFactory
import edu.gatech.cc.cellwatch.msak.shared.net.WsMessage
import edu.gatech.cc.cellwatch.msak.shared.Log
import edu.gatech.cc.cellwatch.msak.shared.THROUGHPUT_WS_PROTO
import edu.gatech.cc.cellwatch.msak.shared.THROUGHPUT_MIN_MESSAGE_SIZE
import edu.gatech.cc.cellwatch.msak.shared.THROUGHPUT_MAX_SCALED_MESSAGE_SIZE
import edu.gatech.cc.cellwatch.msak.shared.THROUGHPUT_MESSAGE_SCALING_FRACTION
import edu.gatech.cc.cellwatch.msak.shared.THROUGHPUT_AVG_MEASUREMENT_INTERVAL_MILLIS
import edu.gatech.cc.cellwatch.msak.shared.THROUGHPUT_MAX_MEASUREMENT_INTERVAL_MILLIS
import edu.gatech.cc.cellwatch.msak.shared.THROUGHPUT_MIN_MEASUREMENT_INTERVAL_MILLIS
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.cancel
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlin.random.Random
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import io.ktor.http.URLBuilder
import io.ktor.http.Url

/**
 * A single TCP stream used to measure throughput.
 *
 * @param id The number of the stream within the throughput test.
 * @param url The URL used to initiate the stream.
 * @param direction The direction of the throughput test.
 * @param minMessageSize The initial size of data messages sent during an upload test.
 * @param maxMessageSize The maximum size of data messages sent during an upload test.
 * @param messageScalingFraction The denominator used to decide when to scale the upload message size (higher = slower growth).
 * @param queueFullDelayMillis How many milliseconds to wait before sending more data when the
 *                             WebSocket's queue grows large during an upload test.
 * @param avgMeasurementIntervalMillis The average time between measurement sampling.
 * @param maxMeasurementIntervalMillis The maximum time between measurement sampling.
 * @param minMeasurementIntervalMillis The minimum time between measurement sampling.
 * @param userAgent The value of the User-Agent header to send when initiating the stream.
 */


class ThroughputStream(
    val id: Int,
    private val url: String,
    private val direction: ThroughputDirection,
    private val minMessageSize: Int = THROUGHPUT_MIN_MESSAGE_SIZE,
    private val maxMessageSize: Int = THROUGHPUT_MAX_SCALED_MESSAGE_SIZE,
    private val messageScalingFraction: Int = THROUGHPUT_MESSAGE_SCALING_FRACTION,
    private val queueFullDelayMillis: Long = 1,
    avgMeasurementIntervalMillis: Long = THROUGHPUT_AVG_MEASUREMENT_INTERVAL_MILLIS,
    maxMeasurementIntervalMillis: Long = THROUGHPUT_MAX_MEASUREMENT_INTERVAL_MILLIS,
    minMeasurementIntervalMillis: Long = THROUGHPUT_MIN_MEASUREMENT_INTERVAL_MILLIS,
    private val userAgent: String? = null,
    private val wsFactory: WebSocketFactory,
    private val streamsHint: Int? = null,
) {
    private val logTAG = "${this::class.simpleName} #$id"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val started = atomic(false)

    private var ws: KmpWebSocket? = null
    private val _updatesChan = Channel<ThroughputUpdate>(capacity = 32)
    private val _updates = ArrayList<ThroughputUpdate>()
    val updatesChan: ReceiveChannel<ThroughputUpdate> = _updatesChan
    // val updates: List<ThroughputUpdate> = _updates

    var startTime: kotlinx.datetime.Instant? = null; private set
    var endTime: kotlinx.datetime.Instant? = null; private set
    val isStarted get() = started.value
    val ended get() = endTime != null
    var error: Throwable? = null; private set

    // Application-layer counters
    private val appBytesSent = atomic(0L)
    private val appBytesReceived = atomic(0L)

    // Optionally set by Android impl via a platform hook
    private var startNetBytesSent: Long? = null
    private var startNetBytesReceived: Long? = null
    private var endNetBytesSent: Long? = null
    private var endNetBytesReceived: Long? = null
    val netBytesSent get() = startNetBytesSent?.let { s -> endNetBytesSent?.minus(s) }
    val netBytesReceived get() = startNetBytesReceived?.let { s -> endNetBytesReceived?.minus(s) }

    // Use a tolerant JSON instance for server messages: the server may add fields (e.g., BBRInfo)
    // that we don't model client‑side. ignoreUnknownKeys keeps us forward‑compatible.
    private val THROUGHPUT_JSON = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    private val ticker = MemorylessTicker(
        expectedMillis = avgMeasurementIntervalMillis,
        maxMillis = maxMeasurementIntervalMillis,
        minMillis = minMeasurementIntervalMillis,
        coroutineScope = scope
    )

    fun start() {
        check(started.compareAndSet(false, true)) { "already started" }
        Log.d(logTAG, "Starting throughput stream: direction=${direction}, url=${url}")

        val headers = mutableMapOf("Sec-WebSocket-Protocol" to THROUGHPUT_WS_PROTO)
        userAgent?.let { headers["User-Agent"] = it }

        startTime = Clock.System.now()

        // Build per-stream URL: always set ?streams=<N>&index=<id>, warn if overriding existing.
        val finalUrl = try {
            val builder = URLBuilder(url)
            val params = builder.parameters
            if (params["streams"] != null) {
                Log.w(logTAG, "Overriding existing streams=${params["streams"]} with ${streamsHint ?: 1}")
            }
            if (params["index"] != null) {
                Log.w(logTAG, "Overriding existing index=${params["index"]} with $id")
            }
            params.set("streams", (streamsHint ?: 1).toString())
            params.set("index", id.toString())
            builder.buildString()
        } catch (t: Throwable) {
            Log.w(logTAG, "failed to build per-stream URL from '$url'", t)
            url // fall back to original
        }

        // Defensive sanity: ensure scheme and host exist (avoid malformed ws:/// or accidental :80:8080, etc.)
        runCatching {
            val u = Url(finalUrl)
            if (u.protocol.name !in listOf("ws", "wss") || u.host.isBlank()) {
                Log.w(logTAG, "invalid websocket URL built: '$finalUrl' (protocol='${u.protocol.name}', host='${u.host}') — falling back to original '$url'")
            }
        }.onFailure {
            Log.w(logTAG, "unable to parse finalUrl '$finalUrl'; falling back to original '$url'", it)
        }

        // Connect and manage the socket using the per-stream scope.
        scope.launch {
            try {
                Log.d(logTAG, "Connecting WebSocket to ${finalUrl}")
                val socket = wsFactory.connect(url = finalUrl, headers = headers)
                ws = socket
                Log.v(logTAG, "WebSocket open")

                if (ended) {
                    // If someone stopped the stream while we were connecting, close and exit.
                    runCatching { socket.close(1000, "stream stopped") }
                    return@launch
                }

                // Start periodic measurement emission.
                ticker.start { sendMeasurement() }

                // For upload, start the sender loop in this coroutine context.
                if (direction == ThroughputDirection.UPLOAD) {
                    launch { uploadLoop() }
                }

                // Collect incoming messages until the socket closes or an error occurs.
                try {
                    socket.incoming.collect { msg ->
                        if (ended) return@collect
                        when (msg) {
                            is WsMessage.Text -> {
                                val txt = msg.text
                                appBytesReceived.addAndGet(txt.encodeToByteArray().size.toLong())
                                runCatching {
                                    val m = THROUGHPUT_JSON.decodeFromString<ThroughputMeasurement>(txt)
                                    sendUpdate(ThroughputUpdate(true, id, Clock.System.now(), m))
                                }.onFailure { Log.w(logTAG, "text message deserialization failed", it) }
                            }
                            is WsMessage.Binary -> {
                                appBytesReceived.addAndGet(msg.bytes.size.toLong())
                            }
                        }
                    }
                    // Flow completed without explicit close code. Treat as normal end.
                    Log.d(logTAG, "websocket incoming completed")
                    finish(null)
                } catch (t: Throwable) {
                    // Receiving failed. Mark as failure.
                    Log.d(logTAG, "websocket receive loop failed for ${finalUrl}", t)
                    finish(FailureException())
                }
            } catch (t: Throwable) {
                // Connect failed or other setup error.
                Log.d(logTAG, "websocket connect failed for ${finalUrl}", t)
                finish(FailureException())
            }
        }
    }

    fun stop() {
        if (!isStarted && !ended) throw NotStartedException()

        val socket = ws
        // Graceful WebSocket close uses the suspending API. Run it in a scope
        // that is not cancelled by finish() (which cancels this.stream scope).
        CoroutineScope(Dispatchers.Default).launch {
            runCatching { socket?.close(1000, "stream stopped") }
                .onFailure { Log.d(logTAG, "graceful websocket close failed", it) }
        }

        // Proceed with local shutdown immediately.
        finish()
    }

    private suspend fun sendMeasurement() {
        val start = startTime ?: return
        val end = endTime ?: Clock.System.now()
        val app = ByteCounters(appBytesSent.value, appBytesReceived.value)
        val net = netBytesSent?.let { sent ->
            netBytesReceived?.let { recv -> ByteCounters(sent, recv) }
        }
        val m = ThroughputMeasurement(net, app, (end - start).inWholeMilliseconds)
        Log.v(logTAG, "sending measurement (stream=${id}): $m")
        val socket = ws ?: return
        val ok = runCatching {
            socket.sendText(THROUGHPUT_JSON.encodeToString(ThroughputMeasurement.serializer(), m))
            true
        }.getOrElse {
            Log.d(logTAG, "unable to send measurement", it)
            false
        }
        if (ok) sendUpdate(ThroughputUpdate(false, id, Clock.System.now(), m))
    }

    private suspend fun uploadLoop() {
        // Very close to Android logic, but using ByteArray and suspending sends
        var size = minMessageSize
        var payload = Random.nextBytes(size)
        while (!ended) {
            val socket = ws ?: break
            val sentOk = runCatching {
                // Suspends; throws on failure
                socket.sendBinary(payload)
                true
            }.getOrElse {
                Log.d(logTAG, "sendBinary failed", it)
                false
            }
            if (!sentOk) break

            appBytesSent.addAndGet(payload.size.toLong())

            // Crude back-pressure and scaling
            if (size < maxMessageSize &&
                size.toDouble() < (appBytesSent.value / messageScalingFraction.toDouble())
            ) {
                val newSize = (size shl 1).coerceAtMost(maxMessageSize)
                if (newSize != size) {
                    size = newSize
                    payload = Random.nextBytes(size)
                    Log.d(logTAG, "scaled message size to $size bytes")
                }
            } else {
                // simple pacing similar to queueFullDelayMillis path
                delay(queueFullDelayMillis)
            }
        }
    }

    private fun sendUpdate(update: ThroughputUpdate) {
        val r = _updatesChan.trySend(update)
        if (!r.isSuccess) Log.d(logTAG, "failed to send throughput update: $r")
        if (!r.isClosed) _updates.add(update)
    }

    private fun finish(err: Throwable? = null) {
        if (ended) return
        Log.d(logTAG, "finishing stream (err=${err?.let { it::class.simpleName } ?: "none"})")
        endTime = Clock.System.now()
        ticker.stop()
        error = err
        _updatesChan.close()
        runCatching { scope.cancel() }
    }

    class NotStartedException: Exception("not started")
    class UnexpectedCloseException(code: Int, reason: String?): Exception("websocket closed with unexpected code: $code $reason")
    class FailureException : Exception("websocket failure")
}