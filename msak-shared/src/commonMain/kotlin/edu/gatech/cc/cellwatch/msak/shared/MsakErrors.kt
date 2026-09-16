package edu.gatech.cc.cellwatch.msak.shared

import edu.gatech.cc.cellwatch.msak.shared.latency.LatencyTest
import edu.gatech.cc.cellwatch.msak.shared.net.TcpException
import edu.gatech.cc.cellwatch.msak.shared.net.UdpException
import edu.gatech.cc.cellwatch.msak.shared.net.WebSocketException
import edu.gatech.cc.cellwatch.msak.shared.throughput.ThroughputStream
import io.ktor.http.URLParserException
import io.ktor.util.network.UnresolvedAddressException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.SerializationException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Single place where internal failures are translated into the public
 * [MsakException] surface.
 *
 * The public suspend APIs ([edu.gatech.cc.cellwatch.msak.shared.latency.runLatency] and
 * [edu.gatech.cc.cellwatch.msak.shared.throughput.runThroughput]) are the only
 * structured error boundary: everything that fails inside the measurement
 * machinery is recorded, not rethrown from a detached coroutine, and then
 * mapped here before it crosses back to the caller.
 *
 * [MsakErrorCode] is intentionally not extended: it is exported to Swift, and a
 * new case would break an exhaustive `switch` in a consumer such as CellWatch.
 */
internal fun mapToMsakException(t: Throwable, fallbackMessage: String): MsakException {
    if (t is MsakException) return t
    val code = classifyOrNull(t, 0) ?: MsakErrorCode.UNKNOWN
    return MsakException(code, t.message ?: fallbackMessage, t)
}

/** Maximum cause-chain depth to walk; guards against self-referencing causes. */
private const val MAX_CAUSE_DEPTH = 8

private fun classifyOrNull(t: Throwable, depth: Int): MsakErrorCode? {
    if (depth > MAX_CAUSE_DEPTH) return null
    val fromCause = { t.cause?.let { classifyOrNull(it, depth + 1) } }
    return when (t) {
        is MsakException -> t.code

        // TimeoutCancellationException is a CancellationException, so it must be
        // checked first or every timeout would be reported as a cancellation.
        is TimeoutCancellationException -> MsakErrorCode.TIMEOUT
        is CancellationException -> MsakErrorCode.CANCELED

        is URLParserException -> MsakErrorCode.INVALID_URL
        is UnresolvedAddressException -> MsakErrorCode.DNS
        is SerializationException -> MsakErrorCode.BAD_JSON

        is LatencyTest.UnauthorizedException -> MsakErrorCode.UNAUTHORIZED
        is LatencyTest.AuthorizeFailureExecption -> fromCause() ?: MsakErrorCode.UNAUTHORIZED
        is LatencyTest.InitialPacketTimeoutException -> fromCause() ?: MsakErrorCode.TIMEOUT
        is LatencyTest.NoResultException -> MsakErrorCode.BAD_JSON
        is LatencyTest.ResultFailureException -> fromCause()
        is LatencyTest.NoAddrException -> MsakErrorCode.DNS

        // A WebSocket/stream failure only reaches the public boundary when the
        // measurement made no progress at all, i.e. the session never came up.
        is WebSocketException -> fromCause() ?: MsakErrorCode.HANDSHAKE_FAILED
        is ThroughputStream.FailureException -> fromCause() ?: MsakErrorCode.HANDSHAKE_FAILED
        is ThroughputStream.UnexpectedCloseException -> MsakErrorCode.HANDSHAKE_FAILED

        // Socket-level errors have no dedicated code; fall through to UNKNOWN
        // unless the cause says something more specific.
        is UdpException -> fromCause()
        is TcpException -> fromCause()

        else -> fromCause()
    }
}
