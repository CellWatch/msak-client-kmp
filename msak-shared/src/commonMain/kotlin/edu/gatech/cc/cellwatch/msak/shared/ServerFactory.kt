package edu.gatech.cc.cellwatch.msak.shared

/**
 * Helper for constructing [Server] instances with the minimal URL maps required
 * by the latency and throughput tests. Keeps URL wiring out of app code and
 * ensures keys match what Server.getLatencyUrl / Server.getThroughputUrl expect.
 *
 * The keys use triple-slash forms (e.g., "http:///latency/v1/authorize") to
 * identify logical endpoints. Values are the concrete scheme/host/port paths.
 *
 * TLS support:
 *  - Pass useTls=true to emit https:// and wss:// endpoints.
 *  - If a port <= 0 is provided, defaults to 443 for TLS and 80 for cleartext.
 */
object ServerFactory {

    // Logical keys expected by Server URL resolvers
    private const val LATENCY_AUTHORIZE_KEY = "http:///latency/v1/authorize"
    private const val LATENCY_RESULT_KEY    = "http:///latency/v1/result"
    private const val THROUGHPUT_DOWNLOAD_KEY = "ws:///throughput/v1/download"
    private const val THROUGHPUT_UPLOAD_KEY   = "ws:///throughput/v1/upload"

    /**
     * Build a [Server] with HTTP(S) latency endpoints.
     *
     * Example:
     *  key "http:///latency/v1/authorize" -> "http://127.0.0.1:8080/latency/v1/authorize"
     *  key "http:///latency/v1/result"    -> "http://127.0.0.1:8080/latency/v1/result"
     */
    fun forLatency(host: String, httpPort: Int = 8080): Server =
        forLatency(host = host, httpPort = httpPort, useTls = false)

    /**
     * TLS-aware variant. When [useTls] is true, emits https:// endpoints and defaults to port 443 when [httpPort] <= 0.
     */
    fun forLatency(host: String, httpPort: Int = 8080, useTls: Boolean): Server {
        val scheme = if (useTls) "https" else "http"
        val port = if (httpPort > 0) httpPort else if (useTls) 443 else 80
        val httpBase = "$scheme://$host:$port"
        val urls = mapOf(
            LATENCY_AUTHORIZE_KEY to "$httpBase/latency/v1/authorize",
            LATENCY_RESULT_KEY    to "$httpBase/latency/v1/result",
        )
        return Server(machine = host, location = null, urls = urls)
    }

    /**
     * Build a [Server] with WebSocket throughput endpoints.
     *
     * Example:
     *  key "ws:///throughput/v1/download" -> "ws://127.0.0.1:8080/throughput/v1/download"
     *  key "ws:///throughput/v1/upload"   -> "ws://127.0.0.1:8080/throughput/v1/upload"
     */
    fun forThroughput(host: String, wsPort: Int): Server =
        forThroughput(host = host, wsPort = wsPort, useTls = false)

    /**
     * TLS-aware variant. When [useTls] is true, emits wss:// endpoints and defaults to port 443 when [wsPort] <= 0.
     */
    fun forThroughput(host: String, wsPort: Int, useTls: Boolean): Server {
        val scheme = if (useTls) "wss" else "ws"
        val port = if (wsPort > 0) wsPort else if (useTls) 443 else 80
        val wsBase = "$scheme://$host:$port"
        val urls = mapOf(
            THROUGHPUT_DOWNLOAD_KEY to "$wsBase/throughput/v1/download",
            THROUGHPUT_UPLOAD_KEY   to "$wsBase/throughput/v1/upload",
        )
        return Server(machine = host, location = null, urls = urls)
    }
}