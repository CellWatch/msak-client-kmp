package edu.gatech.cc.cellwatch.msak.shared.latency

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

/**
 * Represents the result of a latency measurement.
 *
 * This data class is tolerant of absent or differently-capitalized JSON fields.
 * Fields that may be missing from the input JSON (such as `StartTime`, `PacketsSent`, `PacketsReceived`)
 * are nullable and have default values. The `RoundTrips` list defaults to empty if absent.
 *
 * Field capitalizations handled:
 * - `ID` or `id`
 * - `StartTime` or `start_time`
 * - `RoundTrips`, `roundtrips`, or `round_trips`
 * - `PacketsSent` or `packets_sent`
 * - `PacketsReceived` or `packets_received`
 *
 * Example tolerant JSON:
 * ```
 * {
 *   "id": "abc123",
 *   "start_time": "2024-06-05T12:00:00Z",
 *   "roundtrips": [],
 *   "packets_sent": 10,
 *   "packets_received": 9
 * }
 * ```
 */
@Serializable
data class LatencyResult(
    @JsonNames("ID", "id")
    val ID: String,
    @JsonNames("StartTime", "start_time")
    val StartTime: String? = null,
    @JsonNames("RoundTrips", "roundtrips", "round_trips")
    val RoundTrips: List<LatencyRoundTrip> = emptyList(),
    @JsonNames("PacketsSent", "packets_sent")
    val PacketsSent: Int? = null,
    @JsonNames("PacketsReceived", "packets_received")
    val PacketsReceived: Int? = null,
)
