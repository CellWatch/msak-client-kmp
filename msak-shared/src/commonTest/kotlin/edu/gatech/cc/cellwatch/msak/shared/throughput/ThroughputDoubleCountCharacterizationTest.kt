package edu.gatech.cc.cellwatch.msak.shared.throughput

import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression cover for counting the same bytes twice.
 *
 * Both peers report cumulative Application counters for the SAME transfer - the
 * client what it saw, the server what it saw. Summing both reported roughly
 * double the real throughput, which reached the FCC submission through
 * UploadDownloadData.bytes/bytesPerSec.
 */
class ThroughputDoubleCountCharacterizationTest {

    @Test
    fun downloadCountsTheClientOnly() {
        // 1,000,000 bytes cross the wire once. Both sides say so.
        val agg = aggregateThroughputUpdates(
            direction = ThroughputDirection.DOWNLOAD,
            streams = 1,
            updates = listOf(
                u(false, 0, 1_000, sent = 0, recv = 0),
                u(true, 0, 1_010, sent = 0, recv = 0),
                u(false, 0, 2_000, sent = 0, recv = 1_000_000),
                u(true, 0, 2_010, sent = 1_000_000, recv = 0),
            ),
            testStartTimeMs = 1_000,
        )

        assertEquals(1_000_000, agg.appBytesTotal, "download must count the receiving client once")
        // Both sides stay visible for diagnostics.
        assertEquals(1_000_000, agg.clientBytes)
        assertEquals(1_000_000, agg.serverBytes)
        // The window spans the counted (client) updates only.
        assertEquals(1_000.0, agg.elapsedMs)
        assertEquals(8.0, summarizeThroughputAggregation(ThroughputDirection.DOWNLOAD, agg).mbps, 0.0001)
    }

    @Test
    fun uploadCountsTheServerOnly() {
        // Bytes handed to a socket are not necessarily delivered, so upload is
        // measured by the receiving server.
        val agg = aggregateThroughputUpdates(
            direction = ThroughputDirection.UPLOAD,
            streams = 1,
            updates = listOf(
                u(true, 0, 1_000, sent = 0, recv = 0),
                u(false, 0, 1_010, sent = 0, recv = 0),
                u(true, 0, 2_000, sent = 0, recv = 900_000),
                u(false, 0, 2_010, sent = 1_000_000, recv = 0),
            ),
            testStartTimeMs = 1_000,
        )

        assertEquals(900_000, agg.appBytesTotal, "upload must count the receiving server once")
        assertEquals(1_000_000, agg.clientBytes, "the client's own count stays available")
        assertEquals(900_000, agg.serverBytes)
    }

    @Test
    fun measuredDurationReflectsTheObservedWindowNotTheRequestedOne() {
        // A run cut short after 2s of a requested 5s: the bytes are short, so
        // the window must be short too or the reported rate is understated.
        val agg = aggregateThroughputUpdates(
            direction = ThroughputDirection.DOWNLOAD,
            streams = 1,
            updates = listOf(
                u(false, 0, 1_000, sent = 0, recv = 0),
                u(false, 0, 3_000, sent = 0, recv = 2_000_000),
            ),
            testStartTimeMs = 1_000,
        )
        val summary = summarizeThroughputAggregation(ThroughputDirection.DOWNLOAD, agg)

        assertEquals(2_000, summary.measuredDurationMs)
        assertEquals(8.0, summary.mbps, 0.0001)
    }

    private fun u(fromServer: Boolean, stream: Int, epochMs: Long, sent: Long, recv: Long) =
        ThroughputUpdate(
            fromServer = fromServer,
            stream = stream,
            time = Instant.fromEpochMilliseconds(epochMs),
            measurement = ThroughputMeasurement(
                Network = null,
                Application = ByteCounters(BytesSent = sent, BytesReceived = recv),
                ElapsedTime = 0,
            ),
        )
}
