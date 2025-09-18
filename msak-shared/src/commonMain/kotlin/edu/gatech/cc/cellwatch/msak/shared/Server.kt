package edu.gatech.cc.cellwatch.msak.shared

import edu.gatech.cc.cellwatch.msak.shared.throughput.ThroughputDirection
import kotlinx.serialization.Serializable
import io.ktor.http.Url
import io.ktor.http.URLParserException

/**
 * A measurement server returned by the Locate API or synthesized for local/dev.
 *
 * For servers returned by Locate, the entries in [urls] are already fully-formed absolute URLs and
 * must be used verbatim. Do not amend scheme/host/port or add/remove query parameters.
 *
 * For local/dev use, construct this class via ServerFactory which produces absolute URLs in [urls]
 * using the same keys that Locate uses. This class intentionally does not stitch host/port itself.
 *
 * Keys in [urls] that this class understands:
 *  - "wss:///throughput/v1/download", "ws:///throughput/v1/download"
 *  - "wss:///throughput/v1/upload",   "ws:///throughput/v1/upload"
 *  - "https:///latency/v1/authorize", "http:///latency/v1/authorize"
 *  - "https:///latency/v1/result",    "http:///latency/v1/result"
 */
@Serializable
open class Server(
    val machine: String,
    val location: ServerLocation? = null,
    val urls: Map<String, String>,
) {

    private fun missingUrlError(kind: String, path: String): IllegalStateException {
        val tried = when (kind) {
            "throughput" -> "wss:///$path, ws:///$path"
            "latency"    -> "https:///$path, http:///$path"
            else         -> "wss/http variants for $path"
        }
        return IllegalStateException(
            "No $kind URL found in Server.urls for '$path'. " +
            "Tried keys: [$tried]. If using Locate, pass the server as returned. " +
            "For local/dev, construct URLs via ServerFactory with full endpoints."
        )
    }

    /**
     * Return the first present value for any of [candidates], validate it as a URL, and return it.
     * The value must already be an absolute URL. We never patch ports or query parameters here.
     */
    private fun selectAbsoluteUrl(kind: String, path: String, candidates: List<String>): String {
        val raw = candidates.firstNotNullOfOrNull { urls[it] }
            ?: throw missingUrlError(kind, path)
        // Validate and normalize; throws URLParserException if malformed.
        try {
            Url(raw)
        } catch (e: URLParserException) {
            throw IllegalStateException(
                "Malformed $kind URL for '$path': '$raw'. " +
                "Ensure the map value is an absolute URL from Locate or ServerFactory.",
                e
            )
        }
        return raw
    }

    /**
     * Get the full URL used to initiate a throughput test to this server.
     *
     * The URL is returned exactly as provided by [urls]; we do not append ports or query params.
     */
    fun getThroughputUrl(
        direction: ThroughputDirection,
        streams: Int,
        duration: Long,
        delay: Long,
        measurementId: String?,
    ): String {
        val path = when (direction) {
            ThroughputDirection.UPLOAD   -> THROUGHPUT_UPLOAD_PATH
            ThroughputDirection.DOWNLOAD -> THROUGHPUT_DOWNLOAD_PATH
        }
        return selectAbsoluteUrl(
            kind = "throughput",
            path = path,
            candidates = listOf("wss:///$path", "ws:///$path")
        )
    }

    /**
     * Get the full URL used to initiate a latency measurement (authorize).
     *
     * The URL is returned exactly as provided by [urls]; we do not append ports or query params.
     */
    fun getLatencyAuthorizeUrl(measurementId: String? = null): String {
        val path = LATENCY_AUTHORIZE_PATH
        return selectAbsoluteUrl(
            kind = "latency",
            path = path,
            candidates = listOf("https:///$path", "http:///$path")
        )
    }

    /**
     * Get the full URL used to fetch the results of a latency measurement.
     *
     * The URL is returned exactly as provided by [urls]; we do not append ports or query params.
     */
    fun getLatencyResultUrl(measurementId: String? = null): String {
        val path = LATENCY_RESULT_PATH
        return selectAbsoluteUrl(
            kind = "latency",
            path = path,
            candidates = listOf("https:///$path", "http:///$path")
        )
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
