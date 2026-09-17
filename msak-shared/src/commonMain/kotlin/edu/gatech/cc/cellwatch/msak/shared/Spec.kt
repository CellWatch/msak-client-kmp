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
 * How long before the server's send loop ends the client stops echoing.
 *
 * The client must NOT outlive the server. Polling a silent socket exercises a
 * path that hangs: `receive()` relies on SO_RCVTIMEO to wake, and against a
 * server that stops first the loop stalls until the run timeout fires. That is
 * a real defect in the receive path, but stopping just short of the server both
 * avoids it and keeps the loop exiting on a received packet, which is the only
 * path exercised in practice today.
 *
 * Measured: with the window extended 500ms PAST the server, a local-server run
 * hung and aborted at 13000ms; stopping 250ms short completed with 197 of 197
 * packets echoed.
 */
const val LATENCY_ECHO_STOP_MARGIN = 250L

/**
 * Worst case time the initial-packet handshake can consume before giving up:
 * three attempts with a linear backoff of 1000, 1500 and 2000 ms.
 */
const val LATENCY_HANDSHAKE_BUDGET = 4500L

/**
 * How long the client echoes, given the duration the caller asked for.
 *
 * At least the server's own send window less [LATENCY_ECHO_STOP_MARGIN], so a
 * caller asking for less than the server sends no longer cuts the sample short -
 * a 3s request used to capture 145 of ~220 packets and is now ~197.
 *
 * Single definition so the echo loop and the runner's timeout cannot drift
 * apart; widening one without the other aborted runs at "did not complete
 * within 8000ms".
 */
fun latencyEchoWindowMs(callerDurationMs: Long): Long =
    maxOf(callerDurationMs, LATENCY_DURATION - LATENCY_ECHO_STOP_MARGIN)

fun latencyRunTimeoutMs(callerDurationMs: Long): Long =
    LATENCY_HANDSHAKE_BUDGET + latencyEchoWindowMs(callerDurationMs) + 3_000L
