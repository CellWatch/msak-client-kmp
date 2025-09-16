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

/**
 * Configuration for a latency measurement run.
 */
data class LatencyConfig(
    val server: Server,
    val measurementId: String = "localtest",
    val udpPort: Int = 1053,
    val duration: Long = 3_000,
    val userAgent: String? = null,
)

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
suspend fun runLatency(config: LatencyConfig): LatencySummary {
    val test = LatencyTest(
        server = config.server,
        measurementId = config.measurementId,
        latencyPort = config.udpPort,
        duration = config.duration,
        userAgent = config.userAgent,
    )
    try {
        withContext(Dispatchers.Default + SupervisorJob()) {
            test.start()
            withTimeoutOrNull(config.duration.milliseconds + 3.seconds) {
                for (u in test.updatesChan) {
                    // optional: forward to logs or a callback
                }
            }
        }

        test.error?.let { throw it }
        val res = test.result ?: error("no result")

        val rtts = res.RoundTrips.mapNotNull { it.rttUs }
        val mean = rtts.takeIf { it.isNotEmpty() }?.average()?.div(1000.0)
        val stdev = rtts.takeIf { it.isNotEmpty() }?.let { xs ->
            val mu = xs.average()
            kotlin.math.sqrt(xs.fold(0.0) { acc, v -> val d = v - mu; acc + d * d } / xs.size) / 1000.0
        }

        return LatencySummary(
            sent = res.PacketsSent ?: 0,
            received = res.PacketsReceived ?: 0,
            meanMs = mean,
            stdevMs = stdev
        )
    } finally {
        // If LatencyTest exposes an explicit stop/shutdown, invoke it here to ensure sockets close promptly.
        // e.g., test.stop()
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
        test.start()
        for (u in test.updatesChan) send(u)
    }
    awaitClose {
        // Ensure the sender coroutine is cancelled when the collector stops.
        job.cancel()
        // If LatencyTest exposes an explicit stop, invoke it here as well.
        // e.g., test.stop()
    }
}