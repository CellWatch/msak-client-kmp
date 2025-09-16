package edu.gatech.cc.cellwatch.msak.shared

/**
 * The location of a server returned by the Locate API.
 *
 * @param city The city in which the server is located.
 * @param country The country in which the server is located.
 */
data class ServerLocation(
    val city: String?,
    val country: String?,
)
