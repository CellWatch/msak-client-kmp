package edu.gatech.cc.cellwatch.msak.shared.locate

import edu.gatech.cc.cellwatch.msak.shared.LATENCY_AUTHORIZE_PATH
import edu.gatech.cc.cellwatch.msak.shared.LATENCY_RESULT_PATH
import edu.gatech.cc.cellwatch.msak.shared.LOCATE_LATENCY_PATH
import edu.gatech.cc.cellwatch.msak.shared.LOCATE_THROUGHPUT_PATH
import edu.gatech.cc.cellwatch.msak.shared.Log
import edu.gatech.cc.cellwatch.msak.shared.Server
import edu.gatech.cc.cellwatch.msak.shared.THROUGHPUT_DOWNLOAD_PATH
import edu.gatech.cc.cellwatch.msak.shared.THROUGHPUT_UPLOAD_PATH
import edu.gatech.cc.cellwatch.msak.shared.net.NetHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.URLBuilder
import io.ktor.http.isSuccess
import io.ktor.http.path
import kotlinx.serialization.json.Json
import kotlin.random.Random

/**
 * Cross‑platform Locate manager for discovering MSAK servers.
 *
 * This version is KMP‑friendly: no OkHttp or Gson; uses shared KMP HTTP and kotlinx.serialization.
 */
class LocateManager(
    private val serverEnv: ServerEnv = ServerEnv.PROD,
    locateBaseUrl: String? = null,
    private val userAgent: String? = null,
    private val msakLocalServerHost: String? = null,
    private val msakLocalServerSecure: Boolean = false,
) {
    private val TAG = this::class.simpleName ?: "LocateManager"

    private val locateUrls = mapOf(
        ServerEnv.PROD to "https://locate.measurementlab.net/v2/nearest/",
        ServerEnv.STAGING to "https://locate-dot-mlab-staging.appspot.com/v2/nearest/",
    )

    /** The base URL used for locate requests. */
    val locateUrl: String = locateBaseUrl ?: locateUrls[serverEnv] ?: ""

    init {
        if (serverEnv == ServerEnv.LOCAL && msakLocalServerHost == null) {
            throw IllegalArgumentException("must provide msak local server host if server env is local")
        }
    }

    /** Request available MSAK throughput servers from the Locate API. */
    suspend fun locateThroughputServers(limitToSiteOf: Server? = null): List<Server> =
        locateServers("throughput", limitToSiteOf)

    /** Request available MSAK latency servers from the Locate API. */
    suspend fun locateLatencyServers(limitToSiteOf: Server? = null): List<Server> =
        locateServers("latency", limitToSiteOf)

    private suspend fun locateServers(
        test: String,
        limitToSiteOf: Server? = null,
    ): List<Server> {
        val locatePath = when (test) {
            "throughput" -> LOCATE_THROUGHPUT_PATH
            "latency" -> LOCATE_LATENCY_PATH
            else -> throw IllegalArgumentException("unknown test type $test")
        }

        // Local developer server shortcut; build URLs directly without calling Locate.
        if (serverEnv == ServerEnv.LOCAL) {
            val host = msakLocalServerHost ?: error("missing msak local server host for local env")
            val mid = newMeasurementId()
            val urls = when (test) {
                "throughput" -> {
                    val proto = if (msakLocalServerSecure) "wss" else "ws"
                    mapOf(
                        "$proto:///$THROUGHPUT_DOWNLOAD_PATH" to "$proto://$host/$THROUGHPUT_DOWNLOAD_PATH?mid=$mid",
                        "$proto:///$THROUGHPUT_UPLOAD_PATH"   to "$proto://$host/$THROUGHPUT_UPLOAD_PATH?mid=$mid",
                    )
                }
                "latency" -> {
                    val proto = if (msakLocalServerSecure) "https" else "http"
                    mapOf(
                        "$proto:///$LATENCY_AUTHORIZE_PATH" to "$proto://$host/$LATENCY_AUTHORIZE_PATH?mid=$mid",
                        "$proto:///$LATENCY_RESULT_PATH"    to "$proto://$host/$LATENCY_RESULT_PATH?mid=$mid",
                    )
                }
                else -> emptyMap()
            }
            return listOf(Server(machine = host, location = null, urls = urls))
        }

        // If a Server is provided, limit results to its site (extracted from hostname).
        val site: String? = limitToSiteOf?.let { srv ->
            // Expected format: mlab<number>-<site>.<rest>; fall back to null if no match
            Regex("mlab\\d+-([^.\\-]+)\\.").find(srv.machine)?.groupValues?.getOrNull(1)
        }

        val fullLocateUrl = URLBuilder("$locateUrl$locatePath").apply {
            if (!site.isNullOrBlank()) parameters.append("site", site)
        }.buildString()

        val client = NetHttp.client
        val resp = try {
            client.get(fullLocateUrl) {
                if (!userAgent.isNullOrBlank()) header("User-Agent", userAgent)
            }
        } catch (t: Throwable) {
            Log.i(TAG, "locate request failure: $fullLocateUrl", t)
            throw LocateNetworkException(fullLocateUrl, t)
        }
        if (!resp.status.isSuccess()) {
            Log.i(TAG, "locate request failed: status=${resp.status} url=$fullLocateUrl")
            throw LocateHttpException(resp.status.value, fullLocateUrl)
        }

        val body = resp.bodyAsText()
        return try {
            // Tolerant JSON: server may add fields
            val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }
            json.decodeFromString<LocateResponse>(body).results
        } catch (t: Throwable) {
            Log.e(TAG, "locate response deserialization failed; body=${body.take(200)}", t)
            throw LocateDecodeException(fullLocateUrl, t)
        }
    }

    /** The M-Lab server environment. */
    enum class ServerEnv { PROD, STAGING, LOCAL }
}

/** Base class for locate‑related failures surfaced to callers. */
open class LocateException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Network I/O failure when calling the locate endpoint. */
class LocateNetworkException(val url: String, cause: Throwable) : LocateException("locate network failure: $url", cause)

/** Non‑2xx HTTP response from locate. */
class LocateHttpException(val status: Int, val url: String) : LocateException("locate failed: HTTP $status for $url")

/** Unable to parse locate response JSON. */
class LocateDecodeException(val url: String, cause: Throwable) : LocateException("locate response parsing failed for $url", cause)

private fun newMeasurementId(): String {
    val bytes = ByteArray(16)
    Random.nextBytes(bytes)
    // Set version (4) and variant (RFC 4122)
    bytes[6] = (bytes[6].toInt() and 0x0F or 0x40).toByte() // version 4
    bytes[8] = (bytes[8].toInt() and 0x3F or 0x80).toByte() // variant 2
    fun Byte.toHex() = (this.toInt() and 0xFF).toString(16).padStart(2, '0')
    val hex = bytes.joinToString("") { it.toHex() }
    return buildString(36) {
        append(hex, 0, 8); append('-')
        append(hex, 8, 12); append('-')
        append(hex, 12, 16); append('-')
        append(hex, 16, 20); append('-')
        append(hex, 20, 32)
    }
}
