package edu.gatech.cc.cellwatch.msak

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
val LATENCY_CHARSET = Charsets.UTF_8

/**
 * The duration of a latency test.
 */
const val LATENCY_DURATION = 5000L
