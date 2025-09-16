package edu.gatech.cc.cellwatch.msak.shared.throughput

import kotlinx.serialization.Serializable

/**
 * A throughput measurement sent as a textual message during a throughput test.
 *
 * @param Network The transport-layer byte counts.
 * @param Application The application-layer byte counts (i.e., bytes in WebSocket payloads).
 * @param ElapsedTime The total milliseconds elapsed since the beginning of the test.
 * @param CC The congestion control algorithm in use.
 * @param UUID The unique ID of this TCP stream.
 * @param LocalAddr The TCP endpoint of the sender of this measurement as observed by that sender,
 *                  formatted as <host>:<port>, surrounded by square brackets for IPv6.
 * @param RemoveAddr The TCP endpoint of the receiver of this measurement as observed by the sender,
 *                   formatted as <host>:<port>, surrounded by square brackets for IPv6.
 */
@Serializable
data class ThroughputMeasurement(
    val Network: ByteCounters?,
    val Application: ByteCounters,
    val ElapsedTime: Long,

    // TODO: add BBRInfo and TCPInfo

    // WireMeasurement fields, only sent once by server
    val CC: String? = null,
    val UUID: String? = null,
    val LocalAddr: String? = null,
    val RemoteAddr: String? = null,
)
