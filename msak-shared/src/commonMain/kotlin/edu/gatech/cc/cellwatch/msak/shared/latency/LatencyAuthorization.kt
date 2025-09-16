package edu.gatech.cc.cellwatch.msak.shared.latency

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

/**
 * DTO for latency authorization response from server.
 *
 * Your server currently returns:
 * {
 *   "Type": "c2s",
 *   "ID": "localtest",
 *   "Seq": 0
 * }
 *
 * We mirror those fields and keep them tolerant to common variants.
 * If the schema evolves, extend @JsonNames accordingly.
 */
@Serializable
data class LatencyAuthorization(
    @JsonNames("Type", "type")
    val Type: String? = null,

    @JsonNames("ID", "Id", "id", "mid", "measurementId")
    val ID: String? = null,

    @JsonNames("Seq", "seq", "Sequence", "sequence")
    val Seq: Int? = null
)