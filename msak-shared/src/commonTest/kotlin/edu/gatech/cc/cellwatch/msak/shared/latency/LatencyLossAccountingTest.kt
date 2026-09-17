package edu.gatech.cc.cellwatch.msak.shared.latency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The server reports a lost packet as `RTT = 0, Lost = true`. That zero is a
 * real value, not a missing one, so it must be excluded from RTT statistics or
 * it drags the mean toward zero in proportion to the loss rate.
 *
 * Loss counts themselves are reported exactly as the server gives them; the
 * client covers the server's full send window so they are trustworthy.
 */
class LatencyLossAccountingTest {

    private fun rt(rttUs: Long) = LatencyRoundTrip(rttUs = rttUs, lost = null)
    private fun lost() = LatencyRoundTrip(rttUs = 0, lost = true)

    @Test
    fun lostPacketsDoNotDragDownTheRttMean() {
        val res = LatencyResult(
            ID = "test",
            RoundTrips = listOf(rt(10_000), rt(20_000), rt(30_000)) + List(7) { lost() },
            PacketsSent = 10,
            PacketsReceived = 3,
        )

        val summary = summarizeLatency(res)

        // Naively averaging rttUs over all ten entries would give 6.0 ms.
        assertEquals(20.0, summary.meanMs)
    }

    @Test
    fun lostPacketsDoNotDistortStdev() {
        val res = LatencyResult(
            ID = "test",
            RoundTrips = List(5) { rt(20_000) } + List(5) { lost() },
            PacketsSent = 10,
            PacketsReceived = 5,
        )

        val summary = summarizeLatency(res)

        assertEquals(20.0, summary.meanMs)
        assertEquals(0.0, summary.stdevMs)
    }

    @Test
    fun serverLossCountsAreReportedUnchanged() {
        // Real loss must reach the caller; the client does not second-guess it.
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
    fun allPacketsLost_yieldsNoRttStatistics() {
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
        assertNull(summary.stdevMs)
    }

    @Test
    fun missingRoundTripsArray_stillReportsServerScalars() {
        val res = LatencyResult(ID = "test", PacketsSent = 100, PacketsReceived = 97)

        val summary = summarizeLatency(res)

        assertEquals(100, summary.sent)
        assertEquals(97, summary.received)
        assertNull(summary.meanMs)
    }
}
