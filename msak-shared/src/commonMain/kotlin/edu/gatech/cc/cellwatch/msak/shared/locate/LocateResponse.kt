package edu.gatech.cc.cellwatch.msak.shared.locate

import edu.gatech.cc.cellwatch.msak.shared.Server

/**
 * A successful response from the Locate API.
 *
 * @param results The available servers.
 */
data class LocateResponse(val results: List<Server>)