// Copycat of github.com/m-lab/go/memoryless, which is licensed Apache 2.0 but does not provide
// a copyright notice.
//
// KMP port: coroutine-based ticker that fires callbacks at memoryless (exponential) intervals,
// clamped to [minMillis, maxMillis]. Non-overlapping by construction and cancellable via stop().
//
// Usage in ThroughputStream:
//   private val ticker = MemorylessTicker(avgMs, maxMs, minMs, scope)
//   ticker.start { sendMeasurementSuspend() }   // suspending callback, runs on scope's dispatcher
//   // or, if you have a normal (non-suspending) callback:
//   ticker.startSync { sendMeasurement() }
//   ...
//   ticker.stop()
//
// Notes:
// - The callback runs to completion before the next delay is scheduled (no overlap).
// - start() is idempotent; calling it while already running is a no-op.
// - stop() is idempotent; it cancels the active job so a pending delay/iteration unblocks.
// - If you need main-thread execution for UI, pass a scope bound to Main dispatcher and use
//   start { withContext(Dispatchers.Main) { ... } } in your caller.

package edu.gatech.cc.cellwatch.msak.shared.throughput

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Runs a callback at exponentially distributed intervals with hard min/max bounds.
 *
 * @param expectedMillis Average interval (mean) in milliseconds.
 * @param maxMillis Maximum interval in milliseconds (inclusive upper clamp).
 * @param minMillis Minimum interval in milliseconds (inclusive lower clamp).
 * @param coroutineScope Scope used to launch the ticker loop (caller owns its lifecycle).
 */
class MemorylessTicker(
    private val expectedMillis: Long,
    private val maxMillis: Long,
    private val minMillis: Long,
    private val coroutineScope: CoroutineScope
) {
    init {
        require(expectedMillis in minMillis..maxMillis) {
            "(minMillis <= expectedMillis <= maxMillis) must be true"
        }
        require(minMillis >= 0 && maxMillis >= 0 && expectedMillis >= 0) {
            "Intervals must be non-negative"
        }
    }

    // Single running loop job; null when stopped.
    private var job: Job? = null

    private fun launchLoop(invoke: suspend () -> Unit) {
        if (job?.isActive == true) return
        job = coroutineScope.launch {
            while (isActive) {
                val d = nextDelayMillis()
                if (d > 0) delay(d)
                // Run the callback to completion before scheduling the next delay.
                runCatching { invoke() }
                    .onFailure { /* swallow errors; callers should log within callback */ }
            }
        }
    }

    /**
     * Start the ticker with a suspend callback. If already running, this is a no-op.
     *
     * The callback runs on the [coroutineScope] dispatcher. It will not overlap with itself.
     */
    fun start(callback: suspend () -> Unit) = launchLoop(callback)

    /**
     * Start the ticker with a non‑suspending callback. If already running, this is a no‑op.
     *
     * This has a distinct name to avoid overload ambiguity with the suspending start(..).
     */
    fun startSync(callback: () -> Unit) = launchLoop { callback() }

    /**
     * Stop the ticker. Idempotent. Waits for the active iteration to finish.
     */
    suspend fun stopAndJoin() {
        val j = job ?: return
        job = null
        j.cancel()
        // Ensure any in-flight callback completes before returning.
        withContext(Dispatchers.Default) {
            runCatching { j.cancelAndJoin() }
        }
    }

    /**
     * Stop the ticker without joining (fire-and-forget). Safe for UI threads.
     */
    fun stop() {
        val j = job ?: return
        job = null
        j.cancel()
    }

    // Exponential(λ=1) generator using inverse CDF, shifted to (0,1].
    private fun randExpUnit(): Double {
        // Random.nextDouble() returns [0.0, 1.0); map 0 to the smallest positive double fraction
        val u = 1.0 - Random.nextDouble() // (0,1]
        return -ln(u) // mean = 1
    }

    private fun nextDelayMillis(): Long {
        val raw = (randExpUnit() * expectedMillis).toLong()
        return clamp(raw, minMillis, maxMillis)
    }

    private fun clamp(value: Long, lo: Long, hi: Long): Long =
        max(lo, min(value, hi))
}
