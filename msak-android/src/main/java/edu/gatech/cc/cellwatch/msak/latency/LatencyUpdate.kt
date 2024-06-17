package edu.gatech.cc.cellwatch.msak.latency

import kotlinx.datetime.Instant

/**
 * An update received from the latency measurement server.
 *
 * @param time The time when the update was received.
 * @param message The message received from the server.
 */
data class LatencyUpdate(
    val time: Instant,
    val message: LatencyMessage,
)
