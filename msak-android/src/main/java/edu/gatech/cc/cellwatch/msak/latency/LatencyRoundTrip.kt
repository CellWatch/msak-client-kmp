package edu.gatech.cc.cellwatch.msak.latency

/**
 * A latency measurement packet's round trip information.
 *
 * @param RTT The measured RTT of the packet. Only valid if Lost is false.
 * @param Lost Whether the packet was lost.
 */
data class LatencyRoundTrip(
    val RTT: Int,
    val Lost: Boolean,
)
