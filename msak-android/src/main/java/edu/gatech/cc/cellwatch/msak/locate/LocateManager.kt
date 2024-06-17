package edu.gatech.cc.cellwatch.msak.locate

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import edu.gatech.cc.cellwatch.msak.LATENCY_AUTHORIZE_PATH
import edu.gatech.cc.cellwatch.msak.LATENCY_RESULT_PATH
import edu.gatech.cc.cellwatch.msak.LOCATE_LATENCY_PATH
import edu.gatech.cc.cellwatch.msak.LOCATE_THROUGHPUT_PATH
import edu.gatech.cc.cellwatch.msak.Log
import edu.gatech.cc.cellwatch.msak.Server
import edu.gatech.cc.cellwatch.msak.ServerLocation
import edu.gatech.cc.cellwatch.msak.THROUGHPUT_DOWNLOAD_PATH
import edu.gatech.cc.cellwatch.msak.THROUGHPUT_UPLOAD_PATH
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.security.InvalidParameterException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Class to interface with M-Lab's Locate API.
 *
 * @param client An OkHttpClient to use for HTTP requests. If not provided, a new one will be
 *               created.
 * @param serverEnv The M-Lab environment to use.
 * @param locateUrl A custom locate base URL to use rather than the default based on serverEnv.
 * @param userAgent The value of the User-Agent header to use for HTTP requests.
 * @param msakLocalServerHost The host portion of the URL to use when running MSAK locally. Only
 *                            used if serverEnv is LOCAL.
 * @param msakLocalServerSecure Whether the local MSAK server is using HTTPS or not. Only used if
 *                              serverEnv is LOCAL.
 */
class LocateManager(
    client: OkHttpClient? = null,
    private val serverEnv: ServerEnv = ServerEnv.PROD,
    locateUrl: String? = null,
    private val userAgent: String? = null,
    private val msakLocalServerHost: String? = null,
    private val msakLocalServerSecure: Boolean = false,
) {
    private val TAG = this::class.simpleName
    private val locateUrls = mapOf(
        ServerEnv.PROD to "https://locate.measurementlab.net/v2/nearest/",
        ServerEnv.STAGING to "https://locate-dot-mlab-staging.appspot.com/v2/nearest/"
    )
    private val client = client ?: OkHttpClient.Builder().build()

    /**
     * The base URL used for locate requests.
     */
    val locateUrl: String = locateUrl ?: locateUrls[serverEnv] ?: ""

    init {
        if (serverEnv == ServerEnv.LOCAL && msakLocalServerHost == null) {
            throw InvalidParameterException("must provide msak local server host if server env is local")
        }
    }

    /**
     * Request available MSAK throughput servers from the Locate API.
     *
     * @param server An optional server that, if provided, will be used to limit the results to only
     *               servers at the same site.
     */
    suspend fun locateThroughputServers(server: Server? = null): List<Server> {
        return locateServers("throughput", server)
    }


    /**
     * Request available MSAK latency servers from the Locate API.
     *
     * @param server An optional server that, if provided, will be used to limit the results to only
     *               servers at the same site.
     */
    suspend fun locateLatencyServers(server: Server? = null): List<Server> {
        return locateServers("latency", server)
    }

    private suspend fun locateServers(
        test: String,
        server: Server? = null,
    ): List<Server> {
        val locatePath = when (test) {
            "throughput" -> LOCATE_THROUGHPUT_PATH
            "latency" -> LOCATE_LATENCY_PATH
            else -> throw InvalidParameterException("unknown test type $test")
        }

        // No locate URL for the given environment means we should use the local MSAK server.
        if (serverEnv == ServerEnv.LOCAL) {
            if (msakLocalServerHost == null) {
                throw RuntimeException("missing msak local server host for local env")
            }

            val urls = when (test) {
                "throughput" -> {
                    val proto = "ws${if (msakLocalServerSecure) { "s" } else { "" }}"
                    mapOf(
                        "$proto:///$THROUGHPUT_DOWNLOAD_PATH" to "$proto://$msakLocalServerHost/$THROUGHPUT_DOWNLOAD_PATH",
                        "$proto:///$THROUGHPUT_UPLOAD_PATH" to "$proto://$msakLocalServerHost/$THROUGHPUT_UPLOAD_PATH",
                    )
                }
                "latency" -> {
                    val proto = "http${if (msakLocalServerSecure) { "s" } else { "" }}"
                    mapOf(
                        "$proto:///$LATENCY_AUTHORIZE_PATH" to "$proto://$msakLocalServerHost/$LATENCY_AUTHORIZE_PATH",
                        "$proto:///$LATENCY_RESULT_PATH" to "$proto://$msakLocalServerHost/$LATENCY_RESULT_PATH",
                    )
                }
                else -> throw InvalidParameterException("unknown test type $test")
            }

            return listOf(Server(msakLocalServerHost, null, urls))
        }

        // The site name is embedded in the server's machine (hostname), formatted as
        // "mlab<number>-<site>.<rest of hostname>".
        val site = if (server != null) {
            Regex("([^-.]+)\\.").find(server.machine)?.groupValues?.get(1) ?: throw Exception("not site found in machine ${server.machine}")
        } else {
            null
        }

        return requestServers("$locateUrl$locatePath", site)
    }

    private suspend fun requestServers(
        fullLocateUrl: String,
        site: String? = null,
    ): List<Server> = suspendCoroutine { continuation ->
        val params = if (site != null) "site=$site" else ""
        val builder = Request.Builder().url("$fullLocateUrl?$params")
        if (userAgent != null) {
            builder.header("User-Agent", userAgent)
        }
        val request = builder.build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.i(TAG, "locate request failure: $call", e)
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body
                if (response.code != 200 || body == null) {
                    Log.i(TAG, "locate request $request failed: $response")
                    continuation.resumeWithException(Exception("locate request $request failed: $response"))
                    return
                }

                val results = try {
                    Gson().fromJson(body.charStream(), LocateResponse::class.java).results
                } catch (e: JsonSyntaxException) {
                    Log.e(TAG, "locate response deserialization failed: $body", e)
                    continuation.resumeWithException(e)
                    return
                }

                continuation.resume(results)
            }
        })
    }

    /**
     * The M-Lab server environment.
     */
    enum class ServerEnv {PROD, STAGING, LOCAL}
}
