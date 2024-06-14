package edu.gatech.cc.cellwatch.msak.latency

data class LatencyRoundTrip(
    val RTT: Int,
    val Lost: Boolean,
)
