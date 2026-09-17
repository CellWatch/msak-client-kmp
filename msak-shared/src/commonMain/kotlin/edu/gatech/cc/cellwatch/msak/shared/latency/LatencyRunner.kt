package edu.gatech.cc.cellwatch.msak.shared.latency

import edu.gatech.cc.cellwatch.msak.shared.Server
import edu.gatech.cc.cellwatch.msak.shared.latency.LatencyTest
import edu.gatech.cc.cellwatch.msak.shared.latency.LatencyResult
import edu.gatech.cc.cellwatch.msak.shared.latency.LatencyUpdate

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.channels.awaitClose
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.math.roundToLong

import edu.gatech.cc.cellwatch.msak.shared.MsakException
import edu.gatech.cc.cellwatch.msak.shared.MsakErrorCode
import edu.gatech.cc.cellwatch.msak.shared.LATENCY_DURATION
import edu.gatech.cc.cellwatch.msak.shared.mapToMsakException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Configuration for a latency measurement run.
 */
data class LatencyConfig(
    val server: Server,
    val measurementId: String = "localtest",
    val udpPort: Int = 1053,
    val duration: Long = 3_000,
    val userAgent: String? = null,
) {
    // Swift-friendly overloads: let Swift omit udpPort by delegating with udpPort=0 (auto-infer on Kotlin side)
    constructor(
        server: Server,
        measurementId: String = "localtest",
        duration: Long = 3_000,
        userAgent: String? = null
    ) : this(
        server = server,
        measurementId = measurementId,
        udpPort = 0, // 0 = "let LatencyTest infer (1053 local / 6001 remote)"
        duration = duration,
        userAgent = userAgent
    )

    constructor(
        server: Server,
        duration: Long
    ) : this(
        server = server,
        measurementId = "localtest",
        udpPort = 0,
        duration = duration,
        userAgent = null
    )

    constructor(server: Server) : this(
        server = server,
        measurementId = "localtest",
        udpPort = 0,
        duration = 3_000,
        userAgent = null
    )
}

/**
 * Structured result of a latency run. Use [asText] for a compact one-line summary.
 */
data class LatencySummary(
    val sent: Int,
    val received: Int,
    val meanMs: Double?,
    val stdevMs: Double?,
) {
    fun asText(): String =
        if (meanMs == null || stdevMs == null) "OK $received/$sent (no samples)"
        else "OK $received/$sent mean=${fmt2(meanMs)}ms stdev=${fmt2(stdevMs)}ms"
}

private fun fmt2(v: Double): String {
    val rounded = (v * 100.0).roundToLong() / 100.0
    val s = rounded.toString()
    val i = s.indexOf('.')
    return when {
        i < 0 -> s + ".00"
        s.length - i - 1 == 1 -> s + "0"
        else -> s
    }
}

/**
 * Start a latency measurement and return a [LatencySummary].
 * drains updates up to (duration + 3s) and then summarizes.
 * The caller owns any UI and logging.
 */
/**
 * Builds the caller-visible summary from the server's result.
 *
 * The loss counts are the SERVER's, fetched over HTTP from `latency/v1/result`;
 * nothing the client records locally affects them. They are reported as-is,
 * because [LatencyTest] now echoes for the server's full send window - see the
 * note there. Trimming or otherwise adjusting them here would risk masking a
 * genuine loss of connectivity late in a test.
 *
 * RTT statistics do need care: the server represents a lost packet as
 * `RTT = 0, Lost = true`, which is a real zero rather than a missing value, so
 * including it drags the mean toward zero in proportion to the loss rate.
 */
internal fun summarizeLatency(res: LatencyResult): LatencySummary {
    // Filter on `lost`, not on nullability: a lost packet reports rttUs = 0.
    val rtts = res.RoundTrips.filter { it.lost != true }.mapNotNull { it.rttUs }
    val mean = rtts.takeIf { it.isNotEmpty() }?.average()?.div(1000.0)
    val stdev = rtts.takeIf { it.isNotEmpty() }?.let { xs ->
        val mu = xs.average()
        kotlin.math.sqrt(xs.fold(0.0) { acc, v -> val d = v - mu; acc + d * d } / xs.size) / 1000.0
    }

    return LatencySummary(
        sent = res.PacketsSent ?: 0,
        received = res.PacketsReceived ?: 0,
        meanMs = mean,
        stdevMs = stdev,
    )
}

@Suppress("RedundantThrows")
@Throws(MsakException::class, CancellationException::class)
suspend fun runLatency(config: LatencyConfig): LatencySummary {
    var test: LatencyTest? = null
    try {
        // Construction must happen INSIDE the boundary. LatencyTest resolves its
        // control-plane URLs in property initialisers and throws
        // IllegalStateException when the Server carries no latency endpoint - what
        // a throughput-only Locate result looks like. Anything that escapes this
        // function that is not an MsakException violates the @Throws contract
        // above, and Kotlin/Native then terminates the host app instead of bridging
        // it to Swift as an NSError.
        val activeTest = try {
            LatencyTest(
                server = config.server,
                measurementId = config.measurementId,
                latencyPort = config.udpPort,
                duration = config.duration,
                userAgent = config.userAgent,
            )
        } catch (e: IllegalStateException) {
            throw MsakException(
                MsakErrorCode.INVALID_URL,
                e.message ?: "server has no usable latency URL",
                e
            )
        }
        test = activeTest

        // NOTE: no SupervisorJob() here. Passing a Job to withContext reparents the
        // block, which silently detaches it from the caller's cancellation. The
        // detached machinery inside LatencyTest records its own failures rather
        // than rethrowing, so no supervision is needed at this level.
        val drained = withContext(Dispatchers.Default) {
            LatencyControl.register(activeTest)
            activeTest.start()
            withTimeoutOrNull(maxOf(config.duration, LATENCY_DURATION).milliseconds + 3.seconds) {
                for (u in activeTest.updatesChan) {
                    // optional: forward to logs or a callback
                }
                true
            }
        }

        // Surface any recorded error, mapping to MsakException if needed.
        activeTest.lastError?.let { err ->
            if (err is CancellationException) throw err
            throw mapToMsakException(err, "latency failed")
        }
        if (drained == null) {
            // The test never closed its updates channel inside the run window.
            throw MsakException(
                MsakErrorCode.TIMEOUT,
                "latency test did not complete within ${maxOf(config.duration, LATENCY_DURATION) + 3_000}ms"
            )
        }
        val res = activeTest.result
            ?: throw MsakException(MsakErrorCode.UNKNOWN, "no latency result")

        return summarizeLatency(res)
    } catch (ce: CancellationException) {
        // If caller cancels, ensure the underlying test stops promptly, then rethrow.
        test?.let { runCatching { it.stop() } }
        throw ce
    } catch (t: Throwable) {
        // Everything else leaves as an MsakException. Without this, a stray
        // IllegalStateException (double start, bad server URL, ...) would breach
        // the @Throws contract and kill the host app on Kotlin/Native.
        if (t is MsakException) throw t
        throw mapToMsakException(t, "latency failed")
    } finally {
        // Ensure sockets/UDP are closed and background jobs are cancelled even on
        // success. `test` stays null when construction itself failed.
        test?.let {
            runCatching { it.stop() }
            LatencyControl.clear(it)
        }
    }
}

/**
 * Streaming variant that emits [LatencyUpdate]s for UI or observers. No summarization here.
 */
fun latencyFlow(config: LatencyConfig): Flow<LatencyUpdate> = channelFlow {
    val test = LatencyTest(
        server = config.server,
        measurementId = config.measurementId,
        latencyPort = config.udpPort,
        duration = config.duration,
        userAgent = config.userAgent,
    )
    val job = launch(Dispatchers.Default) {
        LatencyControl.register(test)
        test.start()
        for (u in test.updatesChan) send(u)
        // LatencyTest never rethrows from its detached scope, so a failure would
        // otherwise look like a normal end-of-stream. Surface it structurally:
        // this throw happens inside channelFlow, so the collector sees it.
        test.lastError?.let { throw mapToMsakException(it, "latency failed") }
    }
    awaitClose {
        // Ensure the sender coroutine is cancelled when the collector stops.
        job.cancel()
        // Ensure UDP/socket resources are released even if the job was already done.
        runCatching { test.stop() }
        LatencyControl.clear(test)
    }
}