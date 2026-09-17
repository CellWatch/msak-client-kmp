package edu.gatech.cc.cellwatch.msak.shared.latency

import edu.gatech.cc.cellwatch.msak.shared.LATENCY_DURATION
import edu.gatech.cc.cellwatch.msak.shared.LATENCY_ECHO_STOP_MARGIN
import edu.gatech.cc.cellwatch.msak.shared.latencyEchoWindowMs
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
    fun trailingUnechoedPackets_areExcludedFromLoss() {
        // Shape measured against m-lab: the client stops marginally before the
        // server, so the last few packets are still in flight and unechoed when
        // the result is requested. 4.41% reported, 0% actual.
        val res = LatencyResult(
            ID = "test",
            RoundTrips = List(217) { rt(19_000) } + List(10) { lost() },
            PacketsSent = 227,
            PacketsReceived = 217,
        )

        val summary = summarizeLatency(res)

        assertEquals(217, summary.sent)
        assertEquals(217, summary.received)
    }

    @Test
    fun interiorLosses_areKept() {
        // A gap with echoed packets on both sides is real network loss.
        val res = LatencyResult(
            ID = "test",
            RoundTrips = List(10) { rt(20_000) } + lost() + lost() + List(10) { rt(20_000) },
            PacketsSent = 22,
            PacketsReceived = 20,
        )

        val summary = summarizeLatency(res)

        assertEquals(22, summary.sent)
        assertEquals(20, summary.received)
    }

    @Test
    fun interiorAndTrailingLosses_areDistinguished() {
        val res = LatencyResult(
            ID = "test",
            RoundTrips = List(5) { rt(20_000) } + lost() + List(5) { rt(20_000) } + List(3) { lost() },
            PacketsSent = 14,
            PacketsReceived = 10,
        )

        val summary = summarizeLatency(res)

        assertEquals(11, summary.sent)
        assertEquals(10, summary.received)
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

    // --- window sizing -------------------------------------------------------

    @Test
    fun echoWindowStopsBeforeTheServerSendLoopEnds() {
        // Outliving the server makes the loop poll a silent socket, which hangs.
        assertTrue(
            latencyEchoWindowMs(3_000) < LATENCY_DURATION,
            "echo window must end before the server stops sending",
        )
        assertEquals(LATENCY_DURATION - LATENCY_ECHO_STOP_MARGIN, latencyEchoWindowMs(3_000))
    }

    @Test
    fun echoWindowHonoursACallerAskingForLonger() {
        assertEquals(9_000, latencyEchoWindowMs(9_000))
    }

    @Test
    fun runTimeoutLeavesRoomForHandshakeAndResult() {
        // The timeout must exceed the echo window, or a healthy run aborts.
        assertTrue(latencyRunTimeoutMs(3_000) > latencyEchoWindowMs(3_000) + 3_000)
    }
}
