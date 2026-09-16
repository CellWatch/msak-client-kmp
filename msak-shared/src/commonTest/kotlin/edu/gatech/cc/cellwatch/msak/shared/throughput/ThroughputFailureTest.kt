package edu.gatech.cc.cellwatch.msak.shared.throughput

import edu.gatech.cc.cellwatch.msak.shared.MsakErrorCode
import edu.gatech.cc.cellwatch.msak.shared.installTestLogger
import edu.gatech.cc.cellwatch.msak.shared.MsakException
import edu.gatech.cc.cellwatch.msak.shared.blackholeThroughputServer
import edu.gatech.cc.cellwatch.msak.shared.malformedThroughputServer
import edu.gatech.cc.cellwatch.msak.shared.unreachableThroughputServer
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
import kotlin.test.assertTrue

/**
 * Regression coverage for throughput's detached-scope architecture
 * (ThroughputTest -> ThroughputStream -> platform WebSocket, each with its own
 * SupervisorJob scope). A connect or receive failure must come back as a
 * structured MsakException from runThroughput() and must never reach
 * Kotlin/Native's uncaught exception handler.
 */
class ThroughputFailureTest {

    private fun config(direction: ThroughputDirection) = ThroughputConfig(
        server = unreachableThroughputServer(),
        direction = direction,
        streams = 2,
        durationMs = 1_000,
        delayMs = 0,
        measurementId = "unit-test",
    )

    @BeforeTest
    fun setUp() = installTestLogger()

    @Test
    fun runThroughput_downloadConnectFailure_throwsStructuredMsakException(): Unit = runBlocking {
        val e = assertFailsWith<MsakException> { runThroughput(config(ThroughputDirection.DOWNLOAD)) }
        assertEquals(MsakErrorCode.HANDSHAKE_FAILED, e.code)
    }

    @Test
    fun runThroughput_uploadConnectFailure_throwsStructuredMsakException(): Unit = runBlocking {
        val e = assertFailsWith<MsakException> { runThroughput(config(ThroughputDirection.UPLOAD)) }
        assertEquals(MsakErrorCode.HANDSHAKE_FAILED, e.code)
    }

    @Test
    fun throughputTest_connectFailure_recordsStreamErrorAndClosesUpdates(): Unit = runBlocking {
        val test = ThroughputTest(
            server = unreachableThroughputServer(),
            direction = ThroughputDirection.DOWNLOAD,
            numStreams = 2,
            duration = 1_000,
            delay = 0,
            measurementId = "unit-test",
        )
        test.start()
        for (update in test.updatesChan) {
            // no updates are expected from a socket that never connects
        }
        test.awaitEnd()

        assertTrue(test.ended, "the test must reach a terminal state")
        assertTrue(
            test.streams.all { it.ended },
            "every stream must be finished once the test ends"
        )
        // The failure has to be observable rather than silently swallowed.
        assertTrue(
            test.firstStreamError() != null,
            "a websocket connect/receive failure must be recorded as stream error state"
        )
    }

    @Test
    fun runThroughput_malformedUrl_throwsStructuredMsakException(): Unit = runBlocking {
        val e = assertFailsWith<MsakException> {
            runThroughput(
                ThroughputConfig(
                    server = malformedThroughputServer(),
                    direction = ThroughputDirection.DOWNLOAD,
                    streams = 1,
                    durationMs = 500,
                    measurementId = "unit-test",
                )
            )
        }
        assertTrue(e.message?.isNotBlank() == true)
    }

    @Test
    fun runThroughput_cancellation_propagatesAsCancellation(): Unit = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            // A blackholed server keeps the run in flight long enough to cancel it;
            // a refused connection would finish before the cancel could land.
            val deferred = scope.async {
                runThroughput(
                    ThroughputConfig(
                        server = blackholeThroughputServer(),
                        direction = ThroughputDirection.DOWNLOAD,
                        streams = 1,
                        durationMs = 30_000,
                        measurementId = "unit-test",
                    )
                )
            }
            delay(200)
            deferred.cancel()
            assertFailsWith<CancellationException> { deferred.await() }
        } finally {
            scope.cancel()
        }
    }
}
