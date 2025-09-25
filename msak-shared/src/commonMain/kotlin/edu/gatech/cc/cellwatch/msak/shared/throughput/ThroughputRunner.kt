package edu.gatech.cc.cellwatch.msak.shared.throughput

import edu.gatech.cc.cellwatch.msak.shared.Server
import edu.gatech.cc.cellwatch.msak.shared.MsakException
import edu.gatech.cc.cellwatch.msak.shared.MsakErrorCode
import kotlinx.datetime.Clock
import kotlin.math.max
import kotlin.math.roundToLong

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import io.ktor.util.network.UnresolvedAddressException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds


private fun mapThrowable(t: Throwable): MsakException = when (t) {
    is io.ktor.http.URLParserException ->
        MsakException(MsakErrorCode.INVALID_URL, "Invalid URL", t)
    is kotlinx.serialization.SerializationException ->
        MsakException(MsakErrorCode.BAD_JSON, "Bad JSON", t)
    is kotlinx.coroutines.TimeoutCancellationException ->
        MsakException(MsakErrorCode.TIMEOUT, "Timed out", t)
    is UnresolvedAddressException ->
        MsakException(MsakErrorCode.DNS, "DNS resolution failed", t)
    is CancellationException ->
        MsakException(MsakErrorCode.CANCELED, "Canceled", t)
    else -> MsakException(MsakErrorCode.UNKNOWN, t.message ?: "Unknown error", t)
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


data class ThroughputConfig(
    val server: Server,
    val direction: ThroughputDirection,
    val streams: Int = 2,
    val durationMs: Long = 5_000,
    val delayMs: Long = 0,
    val userAgent: String? = null,
    val measurementId: String = "localtest",
)


data class ThroughputSummary(
    val direction: ThroughputDirection,
    val appBytesTotal: Long,
    val mbits: Double,
    val mbps: Double,
    val clientUpdates: Int,
    val serverUpdates: Int,
    val clientBytes: Long,
    val serverBytes: Long,
) {
    fun asText(): String {
        val d = when (direction) {
            ThroughputDirection.DOWNLOAD -> "download"
            ThroughputDirection.UPLOAD -> "upload"
        }
        return "Throughput $d OK | bytes=$appBytesTotal app Mbits=${fmt2(mbits)} Mbps=${fmt2(mbps)} " +
                "updates client=$clientUpdates server=$serverUpdates " +
                "[client=${clientUpdates}/${fmt2(clientBytes / 1_000_000.0)}M server=${serverUpdates}/${fmt2(serverBytes / 1_000_000.0)}M]"
    }
}


@Suppress("RedundantThrows")
@Throws(MsakException::class, CancellationException::class)
suspend fun runThroughput(config: ThroughputConfig): ThroughputSummary {
    val test = ThroughputTest(
        server = config.server,
        direction = config.direction,
        numStreams = config.streams,
        duration = config.durationMs,
        delay = config.delayMs,
        measurementId = config.measurementId,
        userAgent = config.userAgent
    )

    // Register this test as the active one so UI cancel can stop it.
    ThroughputControl.register(test)

    var appBytesTotal = 0L
    var firstTs = Clock.System.now()
    var lastTs = firstTs
    var clientUpdates = 0
    var serverUpdates = 0
    var clientBytes = 0L
    var serverBytes = 0L
    val lastClient = LongArray(config.streams)
    val lastServer = LongArray(config.streams)

    try {
        return withContext(Dispatchers.Default + SupervisorJob()) {
            test.start()
            // Drain updates until completion or timeout (duration + small grace)
            try {
                withTimeout(config.durationMs.milliseconds + 3.seconds) {
                    for (u in test.updatesChan) {
                        val app = u.measurement.Application
                        val s = u.stream
                        if (s !in 0 until config.streams) {
                            // Ignore out-of-range stream indices from server/client; they shouldn't happen,
                            // but guarding avoids attributing bytes to the wrong stream.
                            continue
                        }
                        if (u.fromServer) {
                            val cum = when (config.direction) {
                                ThroughputDirection.DOWNLOAD -> app.BytesSent
                                ThroughputDirection.UPLOAD -> app.BytesReceived
                            }
                            val delta = (cum - lastServer[s]).coerceAtLeast(0)
                            lastServer[s] = cum
                            serverBytes += delta
                            appBytesTotal += delta
                            serverUpdates++
                        } else {
                            val cum = when (config.direction) {
                                ThroughputDirection.DOWNLOAD -> app.BytesReceived
                                ThroughputDirection.UPLOAD -> app.BytesSent
                            }
                            val delta = (cum - lastClient[s]).coerceAtLeast(0)
                            lastClient[s] = cum
                            clientBytes += delta
                            appBytesTotal += delta
                            clientUpdates++
                        }
                        if (clientUpdates + serverUpdates == 1) firstTs = u.time
                        lastTs = u.time
                    }
                }
            } catch (t: TimeoutCancellationException) {
                // Soft end: we timed out waiting for more updates. Do not fail the run;
                // compute summary from what we have. ThroughputTest will be finished below.
            }
            // Surface any error the test recorded
            test.lastError?.let { throw it }

            // If nothing moved at all, treat as handshake/authorization failure
            if (appBytesTotal == 0L && clientUpdates == 0 && serverUpdates == 0) {
                throw MsakException(
                    MsakErrorCode.HANDSHAKE_FAILED,
                    "No data or updates received; websocket handshake likely failed"
                )
            }

            val elapsedMs = max(1, (lastTs.toEpochMilliseconds() - firstTs.toEpochMilliseconds()).toInt()).toDouble()
            val mbits = (appBytesTotal * 8.0) / 1_000_000.0
            val mbps = mbits / (elapsedMs / 1000.0)

            ThroughputSummary(
                direction = config.direction,
                appBytesTotal = appBytesTotal,
                mbits = mbits,
                mbps = mbps,
                clientUpdates = clientUpdates,
                serverUpdates = serverUpdates,
                clientBytes = clientBytes,
                serverBytes = serverBytes,
            )
        }
    } catch (t: Throwable) {
        // Let coroutine cancellation bubble up unchanged; map all other failures (including timeouts) to MsakException
        if (t is CancellationException) throw t
        if (t is MsakException) throw t
        throw mapThrowable(t)
    } finally {
        // Clear active test registration regardless of outcome
        ThroughputControl.clear(test)
        runCatching {
            // Not all platforms expose an explicit stop; call if present.
            test.stop()
        }
    }
}