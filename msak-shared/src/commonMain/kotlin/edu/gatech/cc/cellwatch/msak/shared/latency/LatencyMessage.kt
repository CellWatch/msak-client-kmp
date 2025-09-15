package edu.gatech.cc.cellwatch.msak.shared.latency

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

/**
 * NOTE: This model is intentionally tolerant:
 *  - All fields are nullable with defaults to avoid MissingFieldException.
 *  - Multiple common JSON key variants are accepted via @JsonNames to interoperate with
 *    differing server implementations (e.g., "rtt_us" vs "LastRTT").
 *  Downstream code must validate required semantics (e.g., ensure Type/ID/Seq are present).
 */
@Serializable
data class LatencyMessage(
    @JsonNames("Type", "type")
    val Type: String? = null, // "c2s" or "s2c"

    @JsonNames("ID", "Id", "id", "mid", "measurementId")
    val ID: String? = null,

    @JsonNames("Seq", "seq", "Sequence", "sequence")
    val Seq: Int? = null,

    // RTT in microseconds; accept multiple spellings/cases.
    @JsonNames("LastRTT", "last_rtt", "rtt_us", "RTT_US", "rtt")
    val LastRTT: Long? = null,
) {
    // Convenience for legacy call sites that expect an Int.
    val lastRttUsInt: Int? get() = LastRTT?.toInt()
}
