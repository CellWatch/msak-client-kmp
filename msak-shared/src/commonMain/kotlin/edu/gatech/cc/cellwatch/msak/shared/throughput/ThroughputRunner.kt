package edu.gatech.cc.cellwatch.msak.shared.throughput

import edu.gatech.cc.cellwatch.msak.shared.Server
import edu.gatech.cc.cellwatch.msak.shared.MsakException
import edu.gatech.cc.cellwatch.msak.shared.MsakErrorCode
import kotlinx.datetime.Clock
import kotlin.math.max
import kotlin.math.roundToLong

import edu.gatech.cc.cellwatch.msak.shared.mapToMsakException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds


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
    val warmupDurationMs: Long = 0,
    val warmupBytesTransferred: Long = 0,
) {
    fun asText(): String {
        val d = when (direction) {
            ThroughputDirection.DOWNLOAD -> "download"
            ThroughputDirection.UPLOAD -> "upload"
        }
        return "Throughput $d OK | bytes=$appBytesTotal app Mbits=${fmt2(mbits)} Mbps=${fmt2(mbps)} " +
                "updates client=$clientUpdates server=$serverUpdates " +
                "warmup=${warmupDurationMs}ms/${warmupBytesTransferred}B " +
                "[client=${clientUpdates}/${fmt2(clientBytes / 1_000_000.0)}M server=${serverUpdates}/${fmt2(serverBytes / 1_000_000.0)}M]"
    }
}

internal data class ThroughputAggregation(
    val appBytesTotal: Long,
    val totalAppBytesTransferred: Long,
    val clientUpdates: Int,
    val serverUpdates: Int,
    val clientBytes: Long,
    val serverBytes: Long,
    val elapsedMs: Double,
    val warmupDurationMs: Long,
    val warmupBytesTransferred: Long,
)

internal fun aggregateThroughputUpdates(
    direction: ThroughputDirection,
    streams: Int,
    updates: Iterable<ThroughputUpdate>,
    testStartTimeMs: Long? = null,
): ThroughputAggregation {
    var appBytesTotal = 0L
    var totalAppBytesTransferred = 0L
    var clientUpdates = 0
    var serverUpdates = 0
    var clientBytes = 0L
    var serverBytes = 0L
    val lastClient = LongArray(streams)
    val lastServer = LongArray(streams)

    var firstTsMs: Long? = null
    var lastTsMs: Long? = null
    var warmupBytesTransferred = 0L

    for (u in updates) {
        val app = u.measurement.Application
        val s = u.stream
        if (s !in 0 until streams) {
            // Ignore out-of-range stream indices from server/client; they shouldn't happen,
            // but guarding avoids attributing bytes to the wrong stream.
            continue
        }

        val delta = if (u.fromServer) {
            val cum = when (direction) {
                ThroughputDirection.DOWNLOAD -> app.BytesSent
                ThroughputDirection.UPLOAD -> app.BytesReceived
            }
            val d = (cum - lastServer[s]).coerceAtLeast(0)
            lastServer[s] = cum
            serverUpdates++
            d
        } else {
            val cum = when (direction) {
                ThroughputDirection.DOWNLOAD -> app.BytesReceived
                ThroughputDirection.UPLOAD -> app.BytesSent
            }
            val d = (cum - lastClient[s]).coerceAtLeast(0)
            lastClient[s] = cum
            clientUpdates++
            d
        }

        val t = u.time.toEpochMilliseconds()
        if (firstTsMs == null) {
            firstTsMs = t
            // Bytes in the first accepted update are treated as warmup bytes because they
            // accumulated before the summary measurement window starts.
            warmupBytesTransferred += delta
        } else {
            appBytesTotal += delta
            if (u.fromServer) {
                serverBytes += delta
            } else {
                clientBytes += delta
            }
        }
        totalAppBytesTransferred += delta
        lastTsMs = t
    }

    val measuredStartMs = firstTsMs
    val measuredEndMs = lastTsMs ?: measuredStartMs
    val elapsedMs = when {
        measuredStartMs == null || measuredEndMs == null -> 1.0
        else -> max(1, (measuredEndMs - measuredStartMs).toInt()).toDouble()
    }

    val warmupDurationMs = when {
        testStartTimeMs == null || firstTsMs == null -> 0L
        else -> (firstTsMs - testStartTimeMs).coerceAtLeast(0L)
    }

    return ThroughputAggregation(
        appBytesTotal = appBytesTotal,
        totalAppBytesTransferred = totalAppBytesTransferred,
        clientUpdates = clientUpdates,
        serverUpdates = serverUpdates,
        clientBytes = clientBytes,
        serverBytes = serverBytes,
        elapsedMs = elapsedMs,
        warmupDurationMs = warmupDurationMs,
        warmupBytesTransferred = warmupBytesTransferred,
    )
}

internal fun summarizeThroughputAggregation(
    direction: ThroughputDirection,
    aggregation: ThroughputAggregation,
): ThroughputSummary {
    val mbits = (aggregation.appBytesTotal * 8.0) / 1_000_000.0
    val mbps = mbits / (aggregation.elapsedMs / 1000.0)

    return ThroughputSummary(
        direction = direction,
        appBytesTotal = aggregation.appBytesTotal,
        mbits = mbits,
        mbps = mbps,
        clientUpdates = aggregation.clientUpdates,
        serverUpdates = aggregation.serverUpdates,
        clientBytes = aggregation.clientBytes,
        serverBytes = aggregation.serverBytes,
        warmupDurationMs = aggregation.warmupDurationMs,
        warmupBytesTransferred = aggregation.warmupBytesTransferred,
    )
}


@Suppress("RedundantThrows")
@Throws(MsakException::class, CancellationException::class)
suspend fun runThroughput(config: ThroughputConfig): ThroughputSummary {
    var test: ThroughputTest? = null
    val updates = ArrayList<ThroughputUpdate>(256)

    try {
        // Construction must happen INSIDE the boundary. ThroughputTest resolves the
        // server's WebSocket URL in its initialiser and throws IllegalStateException
        // when the Server carries no throughput endpoint - which is exactly what a
        // latency-only Locate result looks like. Anything that escapes this function
        // that is not an MsakException violates the @Throws contract below, and
        // Kotlin/Native then terminates the host app instead of bridging it to Swift
        // as an NSError.
        val activeTest = try {
            ThroughputTest(
                server = config.server,
                direction = config.direction,
                numStreams = config.streams,
                duration = config.durationMs,
                delay = config.delayMs,
                measurementId = config.measurementId,
                userAgent = config.userAgent
            )
        } catch (e: IllegalStateException) {
            throw MsakException(
                MsakErrorCode.INVALID_URL,
                e.message ?: "server has no usable throughput URL",
                e
            )
        }
        test = activeTest

        // Register this test as the active one so UI cancel can stop it.
        ThroughputControl.register(activeTest)

        // NOTE: no SupervisorJob() here. Passing a Job to withContext reparents the
        // block and silently detaches it from the caller's cancellation. The
        // detached machinery inside ThroughputTest/ThroughputStream records its own
        // failures instead of rethrowing, so no supervision is needed at this level.
        return withContext(Dispatchers.Default) {
            activeTest.start()
            val testStartTimeMs = activeTest.startTime?.toEpochMilliseconds() ?: Clock.System.now().toEpochMilliseconds()
            // Drain updates until completion or timeout (duration + small grace)
            try {
                withTimeout(config.durationMs.milliseconds + 3.seconds) {
                    for (u in activeTest.updatesChan) {
                        updates.add(u)
                    }
                }
            } catch (t: TimeoutCancellationException) {
                // Soft end: we timed out waiting for more updates. Do not fail the run;
                // compute summary from what we have. ThroughputTest will be finished below.
            }
            // Surface any error the test recorded
            activeTest.lastError?.let { throw it }

            // If nothing moved at all, treat as handshake/authorization failure
            val agg = aggregateThroughputUpdates(
                direction = config.direction,
                streams = config.streams,
                updates = updates,
                testStartTimeMs = testStartTimeMs,
            )

            if (agg.totalAppBytesTransferred == 0L && agg.clientUpdates == 0 && agg.serverUpdates == 0) {
                // Nothing moved at all. If a stream recorded why (connect refused,
                // TLS failure, send/receive error), report that rather than a
                // generic guess.
                val streamError = activeTest.firstStreamError()
                throw if (streamError != null) {
                    mapToMsakException(
                        streamError,
                        "No data or updates received; websocket session failed"
                    )
                } else {
                    MsakException(
                        MsakErrorCode.HANDSHAKE_FAILED,
                        "No data or updates received; websocket handshake likely failed"
                    )
                }
            }

            summarizeThroughputAggregation(config.direction, agg)
        }
    } catch (t: Throwable) {
        // Let coroutine cancellation bubble up unchanged; map all other failures (including timeouts) to MsakException
        if (t is CancellationException) throw t
        if (t is MsakException) throw t
        throw mapToMsakException(t, "throughput failed")
    } finally {
        // `test` stays null when construction itself failed, so there is nothing
        // registered or running to clean up in that case.
        test?.let {
            // Clear active test registration regardless of outcome
            ThroughputControl.clear(it)
            runCatching {
                // Not all platforms expose an explicit stop; call if present.
                it.stop()
            }
        }
    }
}
