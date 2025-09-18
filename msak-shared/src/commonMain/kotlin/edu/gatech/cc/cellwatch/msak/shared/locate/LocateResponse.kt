package edu.gatech.cc.cellwatch.msak.shared.locate

import edu.gatech.cc.cellwatch.msak.shared.Server
import kotlinx.serialization.Serializable

/**
 * A successful response from the Locate API.
 *
 * @param results The available servers.
 */
@Serializable
data class LocateResponse(val results: List<Server>)