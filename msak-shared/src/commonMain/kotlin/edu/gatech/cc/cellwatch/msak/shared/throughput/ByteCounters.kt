package edu.gatech.cc.cellwatch.msak.shared.throughput

import kotlinx.serialization.Serializable

/**
 * The count of bytes sent and received.
 *
 * @param BytesSent The number of bytes sent.
 * @param BytesReceived The number of bytes received.
 */
@Serializable
data class ByteCounters(
    val BytesSent: Long = 0,
    val BytesReceived: Long = 0,
)
