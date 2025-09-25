// msak-shared/commonMain/.../latency/LatencyControl.kt
package edu.gatech.cc.cellwatch.msak.shared.latency

import edu.gatech.cc.cellwatch.msak.shared.Log
import kotlinx.atomicfu.atomic

/**
 * Coordinates a single active LatencyTest for cancel-from-UI semantics.
 *
 * Usage:
 *  - LatencyControl.register(test) after constructing the test
 *  - LatencyControl.clear(test) in finally{} after stop()
 *  - UI can call LatencyControl.cancelActive() to request cancellation
 */
object LatencyControl {

    private const val TAG = "LatencyControl"

    /** Atomic reference to the active test (if any). */
    private val active = atomic<LatencyTest?>(null)

    /** Register a test as the currently active one. Replaces any previous active test. */
    fun register(test: LatencyTest) {
        active.value = test
    }

    /**
     * Clear the active test if it matches [test]. If [test] is null, clears unconditionally.
     * Safe to call from any thread and from non-suspending contexts.
     */
    fun clear(test: LatencyTest?) {
        if (test == null) {
            active.value = null
        } else {
            active.compareAndSet(test, null)
        }
    }

    /** Reset (unconditionally clear) any active test. */
    fun reset() {
        active.value = null
    }

    /** Snapshot of current active test (may be stale immediately). */
    fun getActive(): LatencyTest? = active.value

    /**
     * Request cancellation of the current active test by calling stop().
     * Returns true if a test was present and stop() was invoked; false otherwise.
     *
     * We atomically swap out the reference first to avoid double-cancel races.
     */
    fun cancelActive(): Boolean {
        val toStop = active.getAndSet(null) ?: return false
        return runCatching {
            toStop.stop()
            Log.i(TAG, "cancelActive: stop() invoked on active latency test")
            true
        }.onFailure { err ->
            Log.i(TAG, "cancelActive: stop() failed", err)
        }.getOrElse { false }
    }
}