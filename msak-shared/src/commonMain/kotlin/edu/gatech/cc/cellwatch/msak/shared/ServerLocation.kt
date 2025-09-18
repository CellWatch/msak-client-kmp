package edu.gatech.cc.cellwatch.msak.shared
import kotlinx.serialization.Serializable

/**
 * The location of a server returned by the Locate API.
 *
 * @param city The city in which the server is located.
 * @param country The country in which the server is located.
 */
@Serializable
data class ServerLocation(
    val city: String? = null,
    val country: String? = null,
)
