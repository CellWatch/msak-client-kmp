// Copycat of github.com/m-lab/go/memoryless, which is licensed Apache 2.0 but does not provide
// a copyright notice.

package edu.gatech.cc.cellwatch.msak.shared.throughput

// JBW initial port done. Dropped java exception and kotlin.concurrent.thread
// import java.security.InvalidParameterException
// import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch


import kotlin.math.ln
import kotlin.random.Random

/**
 * A callback runner that runs callbacks at random intervals based on an exponential distrubition.
 *
 * @param expectedMillis The expected average milliseconds between subsequent calls to the callback.
 * @param maxMillis The maximum milliseconds between subsequent calls to the callback.
 * @param minMillis The minimum milliseconds between subsequent calls to the callback.
 */
class MemorylessTicker(
    private val expectedMillis: Long,
    private val maxMillis: Long,
    private val minMillis: Long,
    // JBW: New from port
    private val coroutineScope: CoroutineScope

) {
    init {
        if (maxMillis < expectedMillis || minMillis > expectedMillis) {
            //throw InvalidParameterException("(minMillis <= expectedMillis <= maxMillis) must be true")
            throw IllegalArgumentException("(minMillis <= expectedMillis <= maxMillis) must be true")
        }
    }

    // JBW
    // private var runningThread: Thread? = null
    // private var stop = true
    private var job: Job? = null


    /**
     * Begin running a callback periodically.
     *
     * @param callback The function to run.
     */

    // JBW

    fun start(callback: () -> Unit) {
        job = coroutineScope.launch {
            while (isActive) {
                delay(nextDelayMillis())
                callback()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
//    fun start(callback: () -> Unit) {
//        stop = false
//        runningThread = thread {
//            while (!stop) {
//                Thread.sleep(nextDelayMillis())
//                if (!stop) callback()
//            }
//        }
//    }
//
//    /**
//     * Stop running the current callback.
//     */
//    fun stop() {
//        stop = true
//        runningThread = null
//    }

    // Generate a random variable with an exponential distribution, based on
    // https://en.wikipedia.org/wiki/Exponential_distribution#Random_variate_generation.
    private fun randExp(): Double {
        val u = Random.nextDouble()
        return -ln(1 - u)
    }

    private fun nextDelayMillis(): Long {
        val delay = (randExp() * expectedMillis).toLong()
        if (delay > maxMillis) {
            return maxMillis
        }

        if (delay < minMillis) {
            return minMillis
        }

        return delay
    }
}
