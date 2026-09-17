package edu.gatech.cc.cellwatch.msak.shared.latency

import edu.gatech.cc.cellwatch.msak.shared.LATENCY_DURATION
import edu.gatech.cc.cellwatch.msak.shared.LATENCY_HANDSHAKE_BUDGET
import edu.gatech.cc.cellwatch.msak.shared.LATENCY_QUIET_THRESHOLD
import edu.gatech.cc.cellwatch.msak.shared.latencyEchoCeilingMs
import edu.gatech.cc.cellwatch.msak.shared.latencyRunTimeoutMs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LatencyLossAccountingTest {

    private fun rt(rttUs: Long) = LatencyRoundTrip(rttUs = rttUs, lost = null)
    private fun lost() = LatencyRoundTrip(rttUs = 0, lost = true)

    // --- loss accounting -----------------------------------------------------

    @Test
    fun serverLossCountsAreReportedVerbatim() {
        // Termination now waits for the server to fall silent, so an unechoed
        // packet means real loss and must not be adjusted away.
        val res = LatencyResult(
            ID = "test",
            RoundTrips = List(90) { rt(20_000) } + List(10) { lost() },
            PacketsSent = 100,
            PacketsReceived = 90,
        )

        val summary = summarizeLatency(res)

        assertEquals(100, summary.sent)
        assertEquals(90, summary.received)
    }

    @Test
    fun trailingLossIsNotTrimmed() {
        // A late run of losses is what a connection failing near the end of a
        // test looks like. Hiding it would be the worst possible reading.
        val res = LatencyResult(
            ID = "test",
            RoundTrips = List(200) { rt(20_000) } + List(25) { lost() },
            PacketsSent = 225,
            PacketsReceived = 200,
        )

        val summary = summarizeLatency(res)

        assertEquals(225, summary.sent)
        assertEquals(200, summary.received)
    }

    // --- RTT statistics ------------------------------------------------------

    @Test
    fun lostPacketsDoNotDragDownTheRttMean() {
        // An interior loss survives trimming, so its rttUs = 0 must still be
        // excluded from the mean explicitly.
        val res = LatencyResult(
            ID = "test",
            RoundTrips = listOf(rt(10_000), lost(), rt(20_000), lost(), rt(30_000)),
            PacketsSent = 5,
            PacketsReceived = 3,
        )

        val summary = summarizeLatency(res)

        // Averaging all five rttUs values would give 12.0 ms.
        assertEquals(20.0, summary.meanMs)
    }

    @Test
    fun lostPacketsDoNotDistortStdev() {
        val res = LatencyResult(
            ID = "test",
            RoundTrips = listOf(rt(20_000), lost(), rt(20_000), lost(), rt(20_000)),
            PacketsSent = 5,
            PacketsReceived = 3,
        )

        val summary = summarizeLatency(res)

        assertEquals(20.0, summary.meanMs)
        assertEquals(0.0, summary.stdevMs)
    }

    // --- fallbacks -----------------------------------------------------------

    @Test
    fun allPacketsLost_fallsBackToServerScalars() {
        val res = LatencyResult(
            ID = "test",
            RoundTrips = List(6) { lost() },
            PacketsSent = 6,
            PacketsReceived = 0,
        )

        val summary = summarizeLatency(res)

        assertEquals(6, summary.sent)
        assertEquals(0, summary.received)
        assertNull(summary.meanMs)
    }

    @Test
    fun missingRoundTripsArray_fallsBackToServerScalars() {
        val res = LatencyResult(ID = "test", PacketsSent = 100, PacketsReceived = 97)

        val summary = summarizeLatency(res)

        assertEquals(100, summary.sent)
        assertEquals(97, summary.received)
    }

    // --- termination bounds -------------------------------------------------

    @Test
    fun quietThresholdIsWellAboveTheServersMaxSendInterval() {
        // msak sends with memoryless Max: 40ms, so silence this long is
        // unambiguous proof the send loop has ended.
        assertTrue(
            LATENCY_QUIET_THRESHOLD >= 10 * 40L,
            "quiet threshold must not be mistakable for normal send jitter",
        )
    }

    @Test
    fun echoCeilingOutlastsTheServerSendLoop() {
        // The ceiling is a backstop; it must not cut a healthy run short before
        // silence can be observed.
        assertTrue(latencyEchoCeilingMs(3_000) > LATENCY_DURATION + LATENCY_QUIET_THRESHOLD)
    }

    @Test
    fun echoCeilingHonoursACallerAskingForLonger() {
        assertTrue(latencyEchoCeilingMs(9_000) > 9_000)
    }

    @Test
    fun runTimeoutLeavesRoomForHandshakeCeilingAndResult() {
        // Deriving these separately is what produced an abort at 8000ms on a
        // healthy run.
        assertTrue(
            latencyRunTimeoutMs(3_000) >= LATENCY_HANDSHAKE_BUDGET + latencyEchoCeilingMs(3_000),
        )
    }
}
