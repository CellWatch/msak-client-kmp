package edu.gatech.cc.cellwatch.msak.throughput

import kotlinx.datetime.Instant

/**
 * An update from an ongoing throughput test.
 *
 * @param fromServer Whether the enclosed ThroughputMeasurement originated from the measurement
 *                   server.
 * @param stream The number of the stream that sent/received the enclosed ThroughputMeasurement.
 * @param time The time at which the enclosed ThroughputMeasurement was sent/received.
 * @param measurement The details of the measured throughput.
 */
data class ThroughputUpdate(
    val fromServer: Boolean,
    val stream: Int,
    val time: Instant,
    val measurement: ThroughputMeasurement,
)
