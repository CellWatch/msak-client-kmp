package edu.gatech.cc.cellwatch.msak

import edu.gatech.cc.cellwatch.msak.throughput.ThroughputDirection
import io.ktor.http.URLBuilder
import io.ktor.http.Url

/**
 * A measurement server returned by the Locate API.
 *
 * @param machine The server's name.
 * @param location The geographic location of the server.
 * @param urls A mapping of measurement service URL templates to the complete URL for the service on
 *             the specified machine.
 */
open class Server(
    val machine: String,
    val location: ServerLocation?,
    val urls: Map<String, String>,
) {

    /**
     * Get the full URL used to initiate a throughput test to this server.
     *
     * @param direction The desired direction of the throughput test.
     * @param streams The anticipated total number of concurrent TCP streams that will be used to
     *                measure throughput.
     * @param duration The anticipated duration of the throughput test in milliseconds.
     * @param delay The anticipated delay in milliseconds between the initiation of each TCP stream.
     * @param measurementId A unique identifier for the throughput measurement. This parameter is
     *                      typically not needed if the Server was returned from a LocateManager.
     *
     * @return The full URL used to initiate a throughput test to this server.
     */
    fun getThroughputUrl(
        direction: ThroughputDirection,
        streams: Int,
        duration: Long,
        delay: Long,
        measurementId: String?,
    ): String {
        val path = when (direction) {
            ThroughputDirection.UPLOAD -> THROUGHPUT_UPLOAD_PATH
            ThroughputDirection.DOWNLOAD -> THROUGHPUT_DOWNLOAD_PATH
        }

        val url = URLBuilder(Url(urls["wss:///$path"] ?: urls ["ws:///$path"] ?: throw Exception("no URL found")))

        url.parameters["streams"] = "$streams"
        url.parameters["duration"] = "$duration"
        url.parameters["delay"] = "$delay"
        if (measurementId != null) {
            url.parameters["mid"] = measurementId
        }

        return url.buildString()
    }

    /**
     * Get the full URL used to initiate a throughput test to this server.
     *
     * @param measurementId A unique identifier for the latency measurement. This parameter is
     *                      typically not needed if the Server was returned from a LocateManager.
     */
    fun getLatencyAuthorizeUrl(measurementId: String? = null): String {
        return getLatencyUrl(LATENCY_AUTHORIZE_PATH, measurementId)
    }

    /**
     * Get the full URL used to fetch the results of a latency measurement.
     *
     * @param measurementId The unique identifier of the latency measurement. This parameter should
     *                      match the measurementId provided to getLatencyAuthorizeUrl.
     */
    fun getLatencyResultUrl(measurementId: String? = null): String {
        return getLatencyUrl(LATENCY_RESULT_PATH, measurementId)
    }

    private fun getLatencyUrl(
        path: String,
        measurementId: String?,
    ): String {
        val url = URLBuilder(Url(urls["https:///$path"] ?: urls ["http:///$path"] ?: throw Exception("no URL found")))

        if (measurementId != null) {
            url.parameters["mid"] = measurementId
        }

        return url.buildString()
    }

    override fun equals(other: Any?): Boolean {
        if (other !is Server) return false
        return other.machine == machine && other.location == location && other.urls == urls
    }

    override fun toString(): String {
        return "${this::class.simpleName}(machine=$machine, location=$location, urls=$urls)"
    }

    override fun hashCode(): Int {
        var result = machine.hashCode()
        result = 31 * result + (location?.hashCode() ?: 0)
        result = 31 * result + urls.hashCode()
        return result
    }
}
