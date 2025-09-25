package edu.gatech.cc.cellwatch.msak.shared.throughput

import edu.gatech.cc.cellwatch.msak.shared.Log
import kotlinx.atomicfu.atomic

/**
 * Cross‑platform helper to coordinate a single active ThroughputTest.
 *
 * Usage:
 *  - ThroughputControl.register(test) right after constructing the test
 *  - ThroughputControl.clear(test) in the finally { } after stop()
 *  - UI can call ThroughputControl.cancelActive() to request cancellation
 *  - ThroughputControl.reset() to clear any stale active reference before a new run
 *
 * Notes:
 *  - We intentionally do not hold strong references to other resources here.
 *  - Calls are safe from any thread.
 */
object ThroughputControl {

    private const val TAG = "ThroughputControl"

    /**
     * Single atomic reference to the active test. Using atomicfu for lock‑free, cross‑platform semantics.
     */
    private val active = atomic<ThroughputTest?>(null)

    /**
     * Register a test as the currently active one.
     * If another test is already active, it will be replaced.
     */
    fun register(test: ThroughputTest) {
        active.value = test
    }

    /**
     * Clear the active test if it matches [test].
     * Safe to call with null; if null, clears unconditionally.
     */
    fun clear(test: ThroughputTest?) {
        if (test == null) {
            active.value = null
        } else {
            // Only clear if the same instance is still active
            active.compareAndSet(test, null)
        }
    }

    /** Convenience: clear any active reference unconditionally (alias for clear(null)). */
    fun reset() {
        active.value = null
    }

    /**
     * Returns the currently active test (if any). The result may become stale immediately
     * after return; treat as a snapshot only.
     */
    fun getActive(): ThroughputTest? = active.value

    /**
     * Requests cancellation of the currently active test by calling stop().
     * Returns true if a test was present and stop() was invoked; false otherwise.
     *
     * Atomic: swaps out the active reference before calling stop() to avoid racing with
     * concurrent register() or second cancel attempts.
     */
    fun cancelActive(): Boolean {
        val toStop = active.getAndSet(null) ?: return false
        return runCatching {
            toStop.stop()
            Log.i(TAG, "cancelActive: stop() invoked on active throughput test")
            true
        }.onFailure { err ->
            Log.i(TAG, "cancelActive: stop() failed", err)
        }.getOrElse { false }
    }
}
