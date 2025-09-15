package edu.gatech.cc.cellwatch.msak.shared.latency

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

/**
 * One latency round-trip measurement.
 *
 * Server payloads vary across deployments. We keep this model tolerant:
 *  - Fields are nullable with defaults.
 *  - Multiple key spellings are accepted with @JsonNames.
 *
 * Examples observed:
 *   {"RTT": 345}
 *   {"rtt_us": 345}
 *   {"RTT_US": 345}
 *   {"RTT": 345, "Lost": false}
 */
@Serializable
data class LatencyRoundTrip(
    // Round-trip time in microseconds
    @JsonNames("RTT", "rtt_us", "RTT_US", "rtt")
    val rttUs: Long? = null,

    // Whether the packet was considered lost (optional)
    @JsonNames("Lost", "lost", "is_lost")
    val lost: Boolean? = null,
)
