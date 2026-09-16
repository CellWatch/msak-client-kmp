package edu.gatech.cc.cellwatch.msak.shared

import edu.gatech.cc.cellwatch.msak.shared.throughput.ThroughputDirection

/**
 * Route MSAK logging to stdout for tests.
 *
 * The Android host-test source set runs on a plain JVM where `android.util.Log`
 * is an unmocked stub that throws, so any code path that logs would fail for a
 * reason unrelated to what is under test.
 */
fun installTestLogger() = setLogger(ConsoleLogger)

/**
 * A TCP/UDP port on loopback that nothing is listening on.
 *
 * Port 1 is reserved and unbindable by an unprivileged process, so a connect
 * attempt fails immediately with ECONNREFUSED on both the JVM and Kotlin/Native.
 * That gives a deterministic, offline way to force the real network failure
 * paths without a mock transport or a live MSAK server.
 */
const val CLOSED_PORT = 1

/** A Server whose latency control plane points at a closed loopback port. */
fun unreachableLatencyServer(): Server = Server(
    machine = "127.0.0.1",
    location = null,
    urls = mapOf(
        "http:///$LATENCY_AUTHORIZE_PATH" to "http://127.0.0.1:$CLOSED_PORT/$LATENCY_AUTHORIZE_PATH",
        "http:///$LATENCY_RESULT_PATH" to "http://127.0.0.1:$CLOSED_PORT/$LATENCY_RESULT_PATH",
    ),
    latencyUdpPort = CLOSED_PORT,
)

/**
 * An address that swallows connection attempts instead of refusing them.
 *
 * 192.0.2.0/24 is TEST-NET-1 (RFC 5737): it is never routed, so a SYN leaves via
 * the default route and is dropped. Connects hang rather than failing fast,
 * which is what a cancellation test needs -- an unreachable *loopback* port
 * fails so quickly that the run is over before a cancel can land.
 */
const val BLACKHOLE_HOST = "192.0.2.1"
const val BLACKHOLE_PORT = 8080

/** A Server whose latency control plane hangs instead of refusing. */
fun blackholeLatencyServer(): Server = Server(
    machine = BLACKHOLE_HOST,
    location = null,
    urls = mapOf(
        "http:///$LATENCY_AUTHORIZE_PATH" to
            "http://$BLACKHOLE_HOST:$BLACKHOLE_PORT/$LATENCY_AUTHORIZE_PATH",
        "http:///$LATENCY_RESULT_PATH" to
            "http://$BLACKHOLE_HOST:$BLACKHOLE_PORT/$LATENCY_RESULT_PATH",
    ),
    latencyUdpPort = BLACKHOLE_PORT,
)

/** A Server whose throughput WebSocket endpoints hang instead of refusing. */
fun blackholeThroughputServer(): Server = Server(
    machine = BLACKHOLE_HOST,
    location = null,
    urls = mapOf(
        "ws:///$THROUGHPUT_DOWNLOAD_PATH" to
            "ws://$BLACKHOLE_HOST:$BLACKHOLE_PORT/$THROUGHPUT_DOWNLOAD_PATH",
        "ws:///$THROUGHPUT_UPLOAD_PATH" to
            "ws://$BLACKHOLE_HOST:$BLACKHOLE_PORT/$THROUGHPUT_UPLOAD_PATH",
    ),
)

/** A Server whose throughput WebSocket endpoints point at a closed loopback port. */
fun unreachableThroughputServer(): Server = Server(
    machine = "127.0.0.1",
    location = null,
    urls = mapOf(
        "ws:///$THROUGHPUT_DOWNLOAD_PATH" to "ws://127.0.0.1:$CLOSED_PORT/$THROUGHPUT_DOWNLOAD_PATH",
        "ws:///$THROUGHPUT_UPLOAD_PATH" to "ws://127.0.0.1:$CLOSED_PORT/$THROUGHPUT_UPLOAD_PATH",
    ),
)

/** A Server whose throughput URL is not a parseable WebSocket URL. */
fun malformedThroughputServer(): Server = Server(
    machine = "bad",
    location = null,
    urls = mapOf(
        "ws:///$THROUGHPUT_DOWNLOAD_PATH" to "ws:///$THROUGHPUT_DOWNLOAD_PATH",
        "ws:///$THROUGHPUT_UPLOAD_PATH" to "ws:///$THROUGHPUT_UPLOAD_PATH",
    ),
)

internal fun ThroughputDirection.label() = when (this) {
    ThroughputDirection.DOWNLOAD -> "download"
    ThroughputDirection.UPLOAD -> "upload"
}
