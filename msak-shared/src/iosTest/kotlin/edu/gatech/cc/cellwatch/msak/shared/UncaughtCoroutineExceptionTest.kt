@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)

package edu.gatech.cc.cellwatch.msak.shared

import edu.gatech.cc.cellwatch.msak.shared.latency.LatencyConfig
import edu.gatech.cc.cellwatch.msak.shared.latency.runLatency
import edu.gatech.cc.cellwatch.msak.shared.throughput.ThroughputConfig
import edu.gatech.cc.cellwatch.msak.shared.throughput.ThroughputDirection
import edu.gatech.cc.cellwatch.msak.shared.throughput.runThroughput
import kotlin.native.setUnhandledExceptionHook
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The actual Kotlin/Native regression guard.
 *
 * kotlinx.coroutines' last-resort handler on Native routes an unhandled
 * coroutine exception to `processUnhandledException`, which invokes the
 * unhandled-exception hook -- and, depending on build configuration, terminates
 * the process. That is the mechanism behind the reported CellWatch symptom: a
 * measurement that ran for a while and then made the app exit with no
 * user-visible failure.
 *
 * Asserting only "runLatency threw an MsakException" is not enough: the old code
 * did that *and* leaked an uncaught exception. These tests install the hook and
 * assert it is never reached.
 */
class UncaughtCoroutineExceptionTest {

    private val captured = atomic<Throwable?>(null)
    private var previousHook: ReportUnhandledExceptionHook? = null

    @BeforeTest
    fun setUp() {
        installTestLogger()
        captured.value = null
        previousHook = setUnhandledExceptionHook { t -> captured.value = t }
    }

    @AfterTest
    fun tearDown() {
        // Restore whatever was installed before (null resets to the default).
        setUnhandledExceptionHook { t -> previousHook?.invoke(t) }
    }

    @Test
    fun runLatency_authorizeFailure_leaksNoUncaughtCoroutineException() {
        runBlocking {
            assertFailsWith<MsakException> {
                runLatency(
                    LatencyConfig(
                        server = unreachableLatencyServer(),
                        measurementId = "unit-test",
                        udpPort = CLOSED_PORT,
                        duration = 500,
                    )
                )
            }
        }
        assertNoUncaught()
    }

    @Test
    fun runThroughput_connectFailure_leaksNoUncaughtCoroutineException() {
        runBlocking {
            assertFailsWith<MsakException> {
                runThroughput(
                    ThroughputConfig(
                        server = unreachableThroughputServer(),
                        direction = ThroughputDirection.DOWNLOAD,
                        streams = 2,
                        durationMs = 1_000,
                        measurementId = "unit-test",
                    )
                )
            }
        }
        assertNoUncaught()
    }

    private fun assertNoUncaught() {
        // The failure may be reported from a background worker a moment after the
        // suspend boundary returns, so give it a chance to land before asserting.
        runBlocking { kotlinx.coroutines.delay(500) }
        val leaked = captured.value
        assertNull(
            leaked,
            "a MSAK-owned detached coroutine reached Kotlin/Native's unhandled " +
                "exception hook: ${leaked?.let { "${it::class.simpleName}: ${it.message}" }}"
        )
    }
}
