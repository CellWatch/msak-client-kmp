// Copycat of github.com/m-lab/go/memoryless, which is licensed Apache 2.0 but does not provide
// a copyright notice.

package edu.gatech.cc.cellwatch.msak.throughput

import java.security.InvalidParameterException
import kotlin.concurrent.thread
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
) {
    init {
        if (maxMillis < expectedMillis || minMillis > expectedMillis) {
            throw InvalidParameterException("(minMillis <= expectedMillis <= maxMillis) must be true")
        }
    }

    private var runningThread: Thread? = null
    private var stop = true

    /**
     * Begin running a callback periodically.
     *
     * @param callback The function to run.
     */
    fun start(callback: () -> Unit) {
        stop = false
        runningThread = thread {
            while (!stop) {
                Thread.sleep(nextDelayMillis())
                if (!stop) callback()
            }
        }
    }

    /**
     * Stop running the current callback.
     */
    fun stop() {
        stop = true
        runningThread = null
    }

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
