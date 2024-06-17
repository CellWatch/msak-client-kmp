package edu.gatech.cc.cellwatch.msak.latency

/**
 * A message included in a UDP packet for the latency test.
 *
 * @param Type The type of the message. Either "c2s" if the message was sent by the client or "s2c"
 *             if it was sent by the server.
 * @param ID The measurement ID provided when authorizing the latency measurement.
 * @param Seq The sequence number of the message.
 * @param LastRTT The last RTT computed by the sender of this message, in microseconds.
 */
data class LatencyMessage(
    val Type: String, // "c2s" or "s2c"
    val ID: String,
    val Seq: Int,
    val LastRTT: Int?,
)
