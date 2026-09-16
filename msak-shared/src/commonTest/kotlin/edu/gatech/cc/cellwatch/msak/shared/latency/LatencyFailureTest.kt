package edu.gatech.cc.cellwatch.msak.shared.latency

import edu.gatech.cc.cellwatch.msak.shared.MsakErrorCode
import edu.gatech.cc.cellwatch.msak.shared.installTestLogger
import edu.gatech.cc.cellwatch.msak.shared.MsakException
import edu.gatech.cc.cellwatch.msak.shared.blackholeLatencyServer
import edu.gatech.cc.cellwatch.msak.shared.throughputOnlyServer
import edu.gatech.cc.cellwatch.msak.shared.unreachableLatencyServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression coverage for the crash reported from CellWatch TestFlight:
 * LatencyTest.start() ran run() inside a detached CoroutineScope and rethrew
 * from the catch block. On Kotlin/Native that rethrow reaches the uncaught
 * exception handler and terminates the host app instead of returning an error
 * through runLatency().
 *
 * These tests force the real authorize failure (LatencyTest.AuthorizeFailureExecption,
 * the exception named in the reported stack trace) by pointing the control plane
 * at a closed loopback port, and assert the failure comes back as a structured
 * MsakException from the suspend boundary.
 */
class LatencyFailureTest {

    @BeforeTest
    fun setUp() = installTestLogger()

    @Test
    fun runLatency_authorizeFailure_throwsStructuredMsakException(): Unit = runBlocking {
        val config = LatencyConfig(
            server = unreachableLatencyServer(),
            measurementId = "unit-test",
            udpPort = 1,
            duration = 500,
        )

        val e = assertFailsWith<MsakException> { runLatency(config) }

        // Authorize could not reach the server: that is an authorization-phase
        // failure, reported structurally rather than as a process-killing throw.
        assertEquals(MsakErrorCode.UNAUTHORIZED, e.code)
        assertNotNull(e.cause, "the underlying transport failure must be preserved")
    }

    @Test
    fun latencyTest_authorizeFailure_doesNotRethrowFromItsDetachedScope(): Unit = runBlocking {
        // Exercise LatencyTest directly: the failure must land in error state and
        // close the updates channel, with nothing escaping the internal scope.
        val test = LatencyTest(
            server = unreachableLatencyServer(),
            measurementId = "unit-test",
            latencyPort = 1,
            duration = 500,
        )
        test.start()
        for (update in test.updatesChan) {
            // no updates are expected; drain until close
        }
        test.awaitEnd()

        val err = test.error
        assertNotNull(err, "the authorize failure must be recorded on the test")
        assertTrue(
            err is LatencyTest.AuthorizeFailureExecption,
            "expected AuthorizeFailureExecption, got ${err::class.simpleName}"
        )
        assertTrue(test.ended, "the test must reach a terminal state")
        assertNull(test.result)
    }

    @Test
    fun latencyTest_finishIsIdempotentAcrossRepeatedStop(): Unit = runBlocking {
        val test = LatencyTest(
            server = unreachableLatencyServer(),
            measurementId = "unit-test",
            latencyPort = 1,
            duration = 500,
        )
        // stop() before start() must not throw and must still reach a terminal state.
        test.stop()
        assertTrue(test.ended)
        val firstEnd = test.endTime
        test.stop()
        test.stop()
        assertEquals(firstEnd, test.endTime, "finish() must run exactly once")
    }

    /**
     * Mirror of the throughput case: LatencyTest resolves its control-plane URLs
     * in property initialisers, so a server with no latency endpoint throws during
     * construction. That has to surface as MsakException, not escape the @Throws
     * contract.
     */
    @Test
    fun runLatency_serverWithoutLatencyUrls_throwsStructuredMsakException(): Unit = runBlocking {
        val e = assertFailsWith<MsakException> {
            runLatency(
                LatencyConfig(
                    server = throughputOnlyServer(),
                    measurementId = "unit-test",
                    duration = 300,
                )
            )
        }
        assertEquals(MsakErrorCode.INVALID_URL, e.code)
        assertTrue(
            e.message?.contains("latency") == true,
            "the message should say which endpoint was missing, got: ${e.message}"
        )
    }

    @Test
    fun runLatency_cancellation_propagatesAsCancellationNotAsUnknownError(): Unit = runBlocking {
        // A blackholed server keeps the run in flight long enough to cancel it;
        // a refused connection would finish before the cancel could land.
        val config = LatencyConfig(
            server = blackholeLatencyServer(),
            measurementId = "unit-test",
            udpPort = 1,
            duration = 30_000,
        )
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val deferred = scope.async { runLatency(config) }
            delay(200)
            deferred.cancel()
            assertFailsWith<CancellationException> { deferred.await() }
        } finally {
            scope.cancel()
        }
    }
}
