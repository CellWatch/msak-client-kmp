package edu.gatech.cc.cellwatch.msak.shared.throughput

import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThroughputRunnerAggregationTest {

    @Test
    fun aggregateThroughputUpdates_tracksWarmupDurationAndBytes() {
        // DOWNLOAD is measured at the receiving client. The server's own report
        // of the same transfer stays visible for diagnostics but must not be
        // added to the total.
        val updates = listOf(
            update(
                fromServer = false,
                stream = 0,
                epochMs = 1_500,
                appBytesSent = 0,
                appBytesReceived = 400,
            ),
            update(
                fromServer = true,
                stream = 0,
                epochMs = 1_700,
                appBytesSent = 800,
                appBytesReceived = 0,
            ),
            update(
                fromServer = false,
                stream = 0,
                epochMs = 2_500,
                appBytesSent = 0,
                appBytesReceived = 1_400,
            ),
        )

        val agg = aggregateThroughputUpdates(
            direction = ThroughputDirection.DOWNLOAD,
            streams = 1,
            updates = updates,
            testStartTimeMs = 1_000,
        )

        // 400 warm-up + 1000 measured on the client side; the server's 800 is excluded.
        assertEquals(1_000, agg.appBytesTotal)
        assertEquals(2_200, agg.totalAppBytesTransferred)
        assertEquals(2, agg.clientUpdates)
        assertEquals(1, agg.serverUpdates)
        // Per-side totals are diagnostics and include warm-up.
        assertEquals(1_400, agg.clientBytes)
        assertEquals(800, agg.serverBytes)
        assertEquals(500, agg.warmupDurationMs)
        assertEquals(400, agg.warmupBytesTransferred)
        // Window spans counted updates only: 2500 - 1500.
        assertEquals(1_000.0, agg.elapsedMs)
    }

    @Test
    fun aggregateThroughputUpdates_ignoresOutOfRangeStreamIndices() {
        val updates = listOf(
            update(
                fromServer = false,
                stream = 9,
                epochMs = 1_100,
                appBytesSent = 123,
                appBytesReceived = 456,
            ),
        )

        val agg = aggregateThroughputUpdates(
            direction = ThroughputDirection.UPLOAD,
            streams = 2,
            updates = updates,
            testStartTimeMs = 1_000,
        )

        assertEquals(0, agg.appBytesTotal)
        assertEquals(0, agg.totalAppBytesTransferred)
        assertEquals(0, agg.clientUpdates)
        assertEquals(0, agg.serverUpdates)
        assertEquals(0, agg.warmupDurationMs)
        assertEquals(0, agg.warmupBytesTransferred)
        assertEquals(1.0, agg.elapsedMs)
    }

    @Test
    fun throughputSummary_asText_includesWarmupFields() {
        val summary = ThroughputSummary(
            direction = ThroughputDirection.UPLOAD,
            appBytesTotal = 10_000,
            mbits = 0.08,
            mbps = 1.2,
            clientUpdates = 5,
            serverUpdates = 4,
            clientBytes = 8_000,
            serverBytes = 2_000,
            warmupDurationMs = 350,
            warmupBytesTransferred = 1_500,
        )

        val text = summary.asText()

        assertTrue(text.contains("warmup=350ms/1500B"))
    }

    @Test
    fun summarizeThroughputAggregation_excludesWarmupFromRateMath() {
        val agg = ThroughputAggregation(
            appBytesTotal = 2_200,
            totalAppBytesTransferred = 3_000,
            clientUpdates = 2,
            serverUpdates = 1,
            clientBytes = 1_000,
            serverBytes = 1_200,
            elapsedMs = 2_000.0,
            warmupDurationMs = 500,
            warmupBytesTransferred = 800,
        )

        val summary = summarizeThroughputAggregation(ThroughputDirection.DOWNLOAD, agg)

        assertEquals(2_200, summary.appBytesTotal)
        assertEquals(0.0176, summary.mbits, 0.0000001)
        assertEquals(0.0088, summary.mbps, 0.0000001)
        assertEquals(500, summary.warmupDurationMs)
        assertEquals(800, summary.warmupBytesTransferred)
    }

    @Test
    fun aggregateThroughputUpdates_multiStreamWithDelayedFirstUpdate() {
        // UPLOAD is measured at the receiving server: bytes handed to a socket
        // are not necessarily delivered.
        val updates = listOf(
            // Stream 0, first update at 2000ms (1000ms warmup)
            update(fromServer = true, stream = 0, epochMs = 2_000, appBytesSent = 0, appBytesReceived = 500),
            // Stream 1, much later
            update(fromServer = true, stream = 1, epochMs = 3_500, appBytesSent = 0, appBytesReceived = 1_200),
            // Stream 0 second update
            update(fromServer = true, stream = 0, epochMs = 4_000, appBytesSent = 0, appBytesReceived = 1_500),
            // The client's own optimistic count must not reach the total.
            update(fromServer = false, stream = 0, epochMs = 4_100, appBytesSent = 9_999, appBytesReceived = 0),
        )

        val agg = aggregateThroughputUpdates(
            direction = ThroughputDirection.UPLOAD,
            streams = 2,
            updates = updates,
            testStartTimeMs = 1_000,
        )

        // warmup = first counted update at 2000ms - test start at 1000ms
        assertEquals(1_000, agg.warmupDurationMs)
        assertEquals(500, agg.warmupBytesTransferred)
        // 1200 (stream 1) + 1000 (stream 0 delta); warm-up and the client excluded
        assertEquals(2_200, agg.appBytesTotal)
        assertEquals(12_699, agg.totalAppBytesTransferred)
        // Window ends at the last COUNTED update, not the trailing client one.
        assertEquals(2_000.0, agg.elapsedMs)
        assertEquals(1, agg.clientUpdates)
        assertEquals(3, agg.serverUpdates)
        assertEquals(9_999, agg.clientBytes)
        assertEquals(2_700, agg.serverBytes)
    }

    @Test
    fun aggregateThroughputUpdates_noUpdates_returnsZeroWarmupAndMinimalElapsed() {
        val updates = emptyList<ThroughputUpdate>()

        val agg = aggregateThroughputUpdates(
            direction = ThroughputDirection.DOWNLOAD,
            streams = 1,
            updates = updates,
            testStartTimeMs = 5_000,
        )

        assertEquals(0, agg.appBytesTotal)
        assertEquals(0, agg.totalAppBytesTransferred)
        assertEquals(0, agg.clientUpdates)
        assertEquals(0, agg.serverUpdates)
        assertEquals(0, agg.warmupDurationMs)
        assertEquals(0, agg.warmupBytesTransferred)
        assertEquals(1.0, agg.elapsedMs)
    }

    private fun update(
        fromServer: Boolean,
        stream: Int,
        epochMs: Long,
        appBytesSent: Long,
        appBytesReceived: Long,
    ): ThroughputUpdate = ThroughputUpdate(
        fromServer = fromServer,
        stream = stream,
        time = Instant.fromEpochMilliseconds(epochMs),
        measurement = ThroughputMeasurement(
            Network = null,
            Application = ByteCounters(
                BytesSent = appBytesSent,
                BytesReceived = appBytesReceived,
            ),
            ElapsedTime = 0,
        ),
    )
}
