package edu.gatech.cc.cellwatch.msak.shared

/**
 * The throughput test's service name with the Locate API.
 */
const val LOCATE_THROUGHPUT_PATH = "msak/throughput1"

/**
 * The latency test's service name with the Locate API.
 */
const val LOCATE_LATENCY_PATH = "msak/latency1"

/**
 * The path used to select the download subtest in a throughput test.
 */
const val THROUGHPUT_DOWNLOAD_PATH = "throughput/v1/download"

/**
 * The path used to select the upload subtest in a throughput test.
 */
const val THROUGHPUT_UPLOAD_PATH = "throughput/v1/upload"

/**
 * The path used to make the authorize request in a latency test.
 */
const val LATENCY_AUTHORIZE_PATH = "latency/v1/authorize"

/**
 * The path used to get the results of a latency test.
 */
const val LATENCY_RESULT_PATH = "latency/v1/result"

/**
 * The value of the Sec-WebSocket-Protocol header for the throughput test.
 */
const val THROUGHPUT_WS_PROTO = "net.measurementlab.throughput.v1"

/**
 * The average time between measurement sampling in throughput tests.
 */
const val THROUGHPUT_AVG_MEASUREMENT_INTERVAL_MILLIS = 250L

/**
 * The maximum time between measurement sampling in throughput tests.
 */
const val THROUGHPUT_MAX_MEASUREMENT_INTERVAL_MILLIS = 400L

/**
 * The minimum time between measurement sampling in throughput tests.
 */
const val THROUGHPUT_MIN_MEASUREMENT_INTERVAL_MILLIS = 100L

/**
 * The initial size of the data messages sent during the upload test.
 */
const val THROUGHPUT_MIN_MESSAGE_SIZE = 1 shl 10

/**
 * The maximum size of the data messages sent during the upload test.
 */
const val THROUGHPUT_MAX_SCALED_MESSAGE_SIZE = 1 shl 20

/**
 * The threshold for increasing the size of the data messages sent during the upload test.
 */
const val THROUGHPUT_MESSAGE_SCALING_FRACTION = 16

/**
 * The charset used for latency test messages.
 */
//JBW
val LATENCY_CHARSET = "UTF-8"
//val LATENCY_CHARSET = Charsets.UTF_8

/**
 * The duration of a latency test.
 */
const val LATENCY_DURATION = 5000L

/**
 * How long the client waits without receiving a packet before concluding that
 * the server's send loop has ended.
 *
 * This is the measurement's real termination condition, and it is observed
 * rather than assumed. The server's send interval is bounded - msak's
 * `memoryless.Config{ Expected: 25ms, Min: 10ms, Max: 40ms }` - so a live server
 * never goes quiet for anything close to this long. Concluding from silence
 * keeps working if the server's own send duration ever changes, which a margin
 * measured against [LATENCY_DURATION] would not.
 */
const val LATENCY_QUIET_THRESHOLD = 500L

/**
 * Worst case time the initial-packet handshake can consume before giving up:
 * three attempts with a linear backoff of 1000, 1500 and 2000 ms.
 */
const val LATENCY_HANDSHAKE_BUDGET = 4500L

/**
 * Upper bound on the echo loop, used only if silence is never observed - for
 * instance against a server that keeps sending indefinitely.
 *
 * A fallback, not a schedule: normal termination is
 * [LATENCY_QUIET_THRESHOLD] of quiet.
 */
fun latencyEchoCeilingMs(callerDurationMs: Long): Long =
    maxOf(callerDurationMs, LATENCY_DURATION) + 2 * LATENCY_QUIET_THRESHOLD

fun latencyRunTimeoutMs(callerDurationMs: Long): Long =
    LATENCY_HANDSHAKE_BUDGET + latencyEchoCeilingMs(callerDurationMs) + 3_000L
