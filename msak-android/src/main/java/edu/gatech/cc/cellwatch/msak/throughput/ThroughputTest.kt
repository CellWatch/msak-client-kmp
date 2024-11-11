package edu.gatech.cc.cellwatch.msak.throughput

import android.os.Handler
import android.os.Looper
import edu.gatech.cc.cellwatch.msak.Log
import edu.gatech.cc.cellwatch.msak.Server
import io.ktor.http.Url
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * A multi-stream throughput test.
 *
 * @param server The Server against which to run the test, typically obtained via a LocateManager.
 * @param direction The direction of the test.
 * @param streams The number of TCP streams to use to measure throughput.
 * @param duration The total anticipated duration of the test, in milliseconds.
 * @param delay The anticipated delay, in milliseconds, between the start time of each stream.
 * @param measurementId A unique ID for the measurement. Typically not needed if the server was
 *                      obtained via a LocateManager.
 * @param serverEndTimeGraceMillis The number of milliseconds beyond duration to wait for the test
 *                                 to be ended by the server before ending it at the client.
 */
class ThroughputTest(
    server: Server,
    direction: ThroughputDirection,
    streams: Int = 3,
    private val duration: Long = 10 * 1000,
    private val delay: Long = 0,
    measurementId: String? = null,
    private val serverEndTimeGraceMillis: Long = 5000,
    userAgent: String? = null,
) {
    private val TAG = this::class.simpleName
    private val url = server.getThroughputUrl(direction, streams, duration, delay, measurementId)
    private val _updatesChan = Channel<ThroughputUpdate>(32)
    private val handler = Handler(Looper.getMainLooper())
    private val startStopSem = Semaphore(1)
    private val unfinishedStreamCount = AtomicInteger(streams)

    /**
     * The streams used in the test.
     */
    val streams = List(streams) { ThroughputStream(it, url, direction, userAgent = userAgent) }

    /**
     * A channel on which to receive updates as the throughput test progresses. Will be closed when
     * the test is complete.
     */
    val updatesChan: ReceiveChannel<ThroughputUpdate> = _updatesChan

    /**
     * The start time of the test.
     */
    var startTime: Instant? = null; private set

    /**
     * The end time of the test. To wait for the test to end, use updatesChan.
     */
    var endTime: Instant? = null; private set

    /**
     * Whether the test has started.
     */
    val started; get() = startTime != null

    /**
     * Whether the test has ended. To wait for the test to end, use updatesChan.
     */
    val ended; get() = endTime != null

    /**
     * The hostname of the server against which the test runs. Note that this may differ from the
     * machine value provided by the Server.
     */
    val serverHost = Url(url).host

    /**
     * Begin the throughput test. Monitor its updates and wait for it to end with updatesChan.
     */
    fun start() {
        startStopSem.acquire()

        try {
            if (started) {
                throw Exception("already started")
            }

            startTime = Clock.System.now()
            streams.forEachIndexed { i, stream ->
                handler.postDelayed({ runStream(stream) }, i * delay)
            }

            handler.postDelayed({
                Log.w(TAG, "test not ended by server")
                finish()
            }, duration + serverEndTimeGraceMillis)
        } finally {
            startStopSem.release()
        }
    }

    /**
     * End the throughput test. The test will end on its own when complete, but this method can be
     * used to abort it early.
     */
    fun stop() {
        startStopSem.acquire()

        try {
            if (!started) {
                throw Exception("can't stop before starting")
            }

            finish()
        } finally {
            startStopSem.release()
        }
    }

    private fun finish() {
        if (ended) {
            return
        }

        for (stream in streams) {
            try {
                stream.stop()
            } catch (n: ThroughputStream.NotStartedException) {
                // ignore
            }
        }

        handler.removeCallbacksAndMessages(null)
        _updatesChan.close()
        endTime = Clock.System.now()
    }

    private fun runStream(stream: ThroughputStream) {
        thread {
            try {
                runBlocking {
                    stream.start()
                    stream.updatesChan.consumeEach {
                        val result = _updatesChan.trySend(it)
                        if (!result.isSuccess) {
                            Log.d(TAG, "failed to send throughput update on channel: $result")
                        }
                    }

                    if (unfinishedStreamCount.addAndGet(-1) == 0) {
                        Log.d(TAG, "all streams finished, finishing test")
                        finish()
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "unexpected error running stream", e)
                finish()
            }
        }
    }
}