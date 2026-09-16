package edu.gatech.cc.cellwatch.msak.shared

import edu.gatech.cc.cellwatch.msak.shared.latency.LatencyTest
import edu.gatech.cc.cellwatch.msak.shared.net.UdpException
import edu.gatech.cc.cellwatch.msak.shared.net.WebSocketException
import edu.gatech.cc.cellwatch.msak.shared.throughput.ThroughputStream
import io.ktor.util.network.UnresolvedAddressException
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Unit coverage for the single mapping point that turns internal failures into
 * the public [MsakException] surface.
 */
class MsakErrorsTest {

    @Test
    fun authorizationFailuresMapToUnauthorized() {
        assertEquals(
            MsakErrorCode.UNAUTHORIZED,
            mapToMsakException(LatencyTest.UnauthorizedException(), "x").code
        )
        assertEquals(
            MsakErrorCode.UNAUTHORIZED,
            mapToMsakException(LatencyTest.AuthorizeFailureExecption(), "x").code
        )
    }

    @Test
    fun authorizeFailureDefersToItsCauseWhenItIsMoreSpecific() {
        val e = LatencyTest.AuthorizeFailureExecption(UnresolvedAddressException())
        assertEquals(MsakErrorCode.DNS, mapToMsakException(e, "x").code)
    }

    @Test
    fun timeoutsAndParsingFailuresKeepTheirOwnCodes() {
        assertEquals(
            MsakErrorCode.TIMEOUT,
            mapToMsakException(LatencyTest.InitialPacketTimeoutException(), "x").code
        )
        assertEquals(
            MsakErrorCode.BAD_JSON,
            mapToMsakException(LatencyTest.NoResultException(), "x").code
        )
        assertEquals(
            MsakErrorCode.BAD_JSON,
            mapToMsakException(SerializationException("bad"), "x").code
        )
    }

    @Test
    fun websocketAndStreamFailuresMapToHandshakeFailed() {
        assertEquals(
            MsakErrorCode.HANDSHAKE_FAILED,
            mapToMsakException(WebSocketException("boom"), "x").code
        )
        assertEquals(
            MsakErrorCode.HANDSHAKE_FAILED,
            mapToMsakException(
                ThroughputStream.FailureException(WebSocketException("boom")),
                "x"
            ).code
        )
    }

    @Test
    fun unclassifiedSocketErrorsFallBackToUnknownButKeepTheCause() {
        val cause = UdpException("sendto failed", errno = 9)
        val mapped = mapToMsakException(cause, "x")
        assertEquals(MsakErrorCode.UNKNOWN, mapped.code)
        assertSame(cause, mapped.cause)
    }

    @Test
    fun anExistingMsakExceptionIsPassedThroughUnchanged() {
        val original = MsakException(MsakErrorCode.DNS, "nope")
        assertSame(original, mapToMsakException(original, "x"))
    }

    @Test
    fun selfReferencingCauseChainsTerminate() {
        // Guards the cause-walking recursion against pathological inputs.
        class Loop : Exception("loop") {
            override val cause: Throwable get() = this
        }
        assertEquals(MsakErrorCode.UNKNOWN, mapToMsakException(Loop(), "x").code)
    }
}
