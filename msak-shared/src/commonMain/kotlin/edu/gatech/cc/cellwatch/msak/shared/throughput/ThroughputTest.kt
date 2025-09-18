package edu.gatech.cc.cellwatch.msak.shared.throughput

import edu.gatech.cc.cellwatch.msak.shared.Log
import edu.gatech.cc.cellwatch.msak.shared.Server
import edu.gatech.cc.cellwatch.msak.shared.net.WebSocketFactory
import io.ktor.http.Url
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.atomicfu.atomic

/**
 * A multi‑stream throughput test (KMP version).
 *
 * This implementation removes Android‑specific constructs (Handler/Looper, java.util.concurrent)
 * and relies on coroutines so it runs on iOS and Android alike.
 *
 * @param server The Server against which to run the test, typically obtained via a LocateManager.
 * @param direction The direction of the test.
 * @param numStreams The number of TCP streams to use to measure throughput.
 * @param duration The total anticipated duration of the test, in milliseconds.
 * @param delay The anticipated delay, in milliseconds, between the start time of each stream.
 * @param measurementId A unique ID for the measurement. Typically not needed if the server was
 *                      obtained via a LocateManager.
 * @param serverEndTimeGraceMillis How long beyond [duration] to wait for the server to end the test
 *                                 before the client ends it.
 * @param userAgent Optional User‑Agent header for the WebSocket HTTP upgrade.
 * @param wsFactory The WebSocket factory to use for creating connections.
 * @param scope Optional external scope. If not provided, an internal one is created and cancelled
 *             by [stop] or [finish].
 */
class ThroughputTest(
    server: Server,
    private val direction: ThroughputDirection,
    private val numStreams: Int = 3,
    private val duration: Long = 10 * 1000,
    private val delay: Long = 0,
    measurementId: String? = null,
    private val serverEndTimeGraceMillis: Long = 5_000,
    userAgent: String? = null,
    private val wsFactory: WebSocketFactory = WebSocketFactory,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val TAG = this::class.simpleName
    private val url = server.getThroughputUrl(direction, numStreams, duration, delay, measurementId)

    private val _updatesChan = Channel<ThroughputUpdate>(capacity = 32)
    val updatesChan: ReceiveChannel<ThroughputUpdate> = _updatesChan

    // Streams used in the test.
    val streams = List(numStreams) { idx ->
        ThroughputStream(
            idx,
            url,
            direction,
            userAgent = userAgent,
            wsFactory = wsFactory,
            streamsHint = numStreams,
        )
    }

    // Timer job that enforces client‑side end if server does not close first.
    private var watchdogJob: Job? = null

    // Track unfinished streams in a lock‑free way.
    private val unfinished = atomic(streams.size)

    /** Start time of the test. */
    var startTime: Instant? = null; private set

    /** End time of the test. To wait for the test to end, observe [updatesChan] close. */
    var endTime: Instant? = null; private set

    /** Whether the test has started. */
    val started get() = startTime != null

    /** Whether the test has ended. */
    val ended get() = endTime != null

    /** The hostname of the server against which the test runs. */
    val serverHost = Url(url).host

    /** Begin the throughput test. Monitor updates on [updatesChan] and await close for completion. */
    fun start() {
        if (started) throw IllegalStateException("already started")

        Log.d(TAG, "${Clock.System.now()} Starting throughput test: direction=$direction, numStreams=$numStreams, duration=$duration ms, delay=$delay ms")

        startTime = Clock.System.now()

        // Launch each stream with the requested staggered delay.
        streams.forEachIndexed { i, stream ->
            val startDelay = i.toLong() * delay
            Log.d(TAG, "${Clock.System.now()} Scheduling stream #$i with start delay ${startDelay}ms")
            scope.launch {
                if (startDelay > 0) delay(startDelay)
                runStream(i, stream)
            }
        }

        // Watchdog to enforce end in case the server doesn't close us.
        watchdogJob = scope.launch {
            val total = duration + serverEndTimeGraceMillis
            Log.d(TAG, "${Clock.System.now()} Watchdog armed with total timeout ${total}ms")
            if (total > 0) delay(total)
            if (!ended) {
                Log.w(TAG, "${Clock.System.now()} test not ended by server, finishing on client")
                finish()
            }
        }
    }

    /** Abort the throughput test early. The test will end on its own when complete. */
    fun stop() {
        Log.d(TAG, "${Clock.System.now()} Stopping throughput test (stop called)")
        finish()
    }

    private fun finish() {
        if (ended) return

        Log.d(TAG, "${Clock.System.now()} Finishing throughput test (ended=$ended)")

        // Stop all streams (safe if some never started).
        streams.forEach { stream ->
            runCatching { stream.stop() }
                .onFailure { e ->
                    if (e !is ThroughputStream.NotStartedException) {
                        Log.d(TAG, "${Clock.System.now()} stream.stop() ignored: ${e}")
                    }
                }
        }

        watchdogJob?.cancel()
        watchdogJob = null

        _updatesChan.close()
        endTime = Clock.System.now()
    }

    private suspend fun runStream(index: Int, stream: ThroughputStream) {
        try {
            Log.d(TAG, "${Clock.System.now()} Stream #$index starting")
            stream.start()

            // Forward updates to the aggregate channel and suspend here until this stream completes.
            for (u in stream.updatesChan) {
                val result = _updatesChan.trySend(u)
                if (!result.isSuccess) {
                    Log.d(TAG, "${Clock.System.now()} failed to forward throughput update from stream #$index: $result")
                }
            }

            Log.d(TAG, "${Clock.System.now()} Stream #$index ended (updates channel closed)")

            // When the stream finishes, decrement and finish if it was the last one.
            if (unfinished.decrementAndGet() == 0) {
                Log.d(TAG, "${Clock.System.now()} all streams finished, finishing test")
                finish()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "${Clock.System.now()} unexpected error running stream #$index", t)
            finish()
        }
    }
}