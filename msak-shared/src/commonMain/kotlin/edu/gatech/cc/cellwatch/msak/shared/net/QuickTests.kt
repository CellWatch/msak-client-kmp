package edu.gatech.cc.cellwatch.msak.shared.net

import kotlinx.coroutines.delay
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.client.request.header
import io.ktor.http.isSuccess
import kotlin.time.Duration.Companion.milliseconds
import edu.gatech.cc.cellwatch.msak.shared.latency.LatencyTest
import edu.gatech.cc.cellwatch.msak.shared.latency.LatencyResult
import edu.gatech.cc.cellwatch.msak.shared.Log

import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.channels.onSuccess
import edu.gatech.cc.cellwatch.msak.shared.THROUGHPUT_DOWNLOAD_PATH
import edu.gatech.cc.cellwatch.msak.shared.THROUGHPUT_UPLOAD_PATH
import edu.gatech.cc.cellwatch.msak.shared.Server
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds



import edu.gatech.cc.cellwatch.msak.shared.throughput.*
import io.ktor.http.Url
import kotlinx.coroutines.*
import kotlinx.datetime.Clock
import kotlin.math.max
import kotlin.math.roundToLong

object QuickTests {
    // KMP-safe 2-decimal formatter (no java.util/DecimalFormat in commonMain)
    private fun fmt2(x: Double): String {
        if (x.isNaN()) return "NaN"
        if (x.isInfinite()) return if (x > 0) "+∞" else "-∞"
        val scaled = (x * 100.0).roundToLong()
        var intPart: Long = scaled / 100
        var frac: Long = kotlin.math.abs(scaled % 100)
        // Avoid "-0" for tiny negative values rounded to 0
        if (intPart == 0L && x < 0 && frac == 0L) intPart = 0
        val fracStr = if (frac < 10L) "0$frac" else "$frac"
        return "$intPart.$fracStr"
    }
    /** Open a UDP socket, call connect(host, port), briefly hold, then close.
     *  Returns true if no exception was thrown. */
    suspend fun udpOpenClose(host: String, port: Int, holdMs: Long = 500L): Boolean {
        val s = SocketFactory.udp()
        return try {
            s.connect(host, port)
            try {
                delay(holdMs)
            } catch (t: Throwable) {
                println("udpOpenClose delay failed: ${t.message} | host=$host port=$port holdMs=$holdMs")
                throw t
            }
            true
        } catch (t: Throwable) {
            println("udpOpenClose failed: ${t.message} | host=$host port=$port holdMs=$holdMs")
            false
        } finally {
            runCatching { s.close() }
        }
    }

    /** HTTP probe to the MSAK server instead of raw TCP.
     *  Builds http://host:port/throughput/v1 (a known control prefix) and issues a GET.
     *  Any HTTP status (200–499) counts as "reachable"; network errors count as failure.
     *  [holdMs] is still respected as a short delay before returning (for UI parity). */
    suspend fun tcpOpenClose(host: String, port: Int, holdMs: Long = 500L): Boolean {
        // Ensure the shared HTTP client exists (initialize lazily with defaults if not).
        val client = try {
            NetHttp.client
        } catch (_: Throwable) {
            NetHttp.initialize()
            NetHttp.client
        }
        val url = "http://$host:$port/throughput/v1"
        return try {
            val resp = client.get(url)
            val code = resp.status.value
            // Read body text length for visibility but avoid logging large payloads
            val bodyLen = runCatching { resp.bodyAsText().length }.getOrElse { -1 }
            println("QuickTests.http GET $url -> $code (bodyLen=$bodyLen)")
            try {
                delay(holdMs)
            } catch (t: Throwable) {
                println("http probe delay failed: ${t.message}")
            }
            // Treat any HTTP response as success; only transport-level failures cause false
            true
        } catch (t: Throwable) {
            println("QuickTests.http GET failed for $url: ${t.message}")
            false
        }
    }

    /** Open a WebSocket to [url] (ws:// or wss://), wait [holdMs], then close. */
    suspend fun wsOpenClose(url: String, holdMs: Long = 500L): Boolean {
        // If this looks like an MSAK throughput endpoint, include the required WS subprotocol header.
        val headers =
            if (url.contains("/throughput/")) mapOf("Sec-WebSocket-Protocol" to "net.measurementlab.throughput.v1")
            else emptyMap()

        val ws = try {
            WebSocketFactory.connect(url, headers)
        } catch (_: Throwable) {
            return false
        }
        return try {
            delay(holdMs)
            true
        } catch (_: Throwable) {
            false
        } finally {
            runCatching { ws.close(1000, "quick test") }
            runCatching { ws.close() }
        }
    }


    suspend fun latencyProbe(
        host: String,
        httpPort: Int = 8080,
        udpPort: Int = 1053,
        mid: String = "localtest",
        userAgent: String? = null,
        waitForReplyMs: Long = 1000L
    ): String {
        // 1) Authorize over HTTP
        val client = try {
            NetHttp.client
        } catch (_: Throwable) {
            NetHttp.initialize()
            NetHttp.client
        }
        val authUrl = "http://$host:$httpPort/latency/v1/authorize?mid=$mid"
        val authResp = try {
            client.get(authUrl) {
                if (userAgent != null) header("User-Agent", userAgent)
            }
        } catch (t: Throwable) {
            return "authorize failed: ${t.message}"
        }
        if (!authResp.status.isSuccess()) {
            return "authorize HTTP ${authResp.status.value}"
        }

        // 2) Send one UDP “initial” packet and wait for a reply
        val s = SocketFactory.udp()
        try {
            s.connect(host, udpPort)
        } catch (t: Throwable) {
            return "UDP connect failed: ${t.message ?: "unknown"}"
        }

        // Minimal wire-shape JSON (kept manual for exactness)
        val initialJson = buildString {
            append('{')
            append("\"Type\":\"c2s\"")
            append(",\"ID\":\"").append(mid).append('"')
            append(",\"Seq\":").append(0)
            append('}')
        }
        val initialBytes = initialJson.encodeToByteArray()

        // Send and await first echo
        return try {
            val sent = s.send(initialBytes)
            if (sent <= 0) {
                "UDP send failed"
            } else {
                val deadline = kotlinx.datetime.Clock.System.now() + waitForReplyMs.toInt().milliseconds
                var gotReply = false
                while (kotlinx.datetime.Clock.System.now() < deadline) {
                    val pkt = s.receive(2048) ?: continue
                    if (pkt.data.isNotEmpty()) { gotReply = true; break }
                }
                if (gotReply) "OK" else "timeout waiting for UDP echo"
            }
        } catch (t: Throwable) {
            "UDP error: ${t.message ?: "unknown"}"
        } finally {
            runCatching { s.close() }
        }
    }

    /**
     * Run the full LatencyTest harness and return a compact status string.
     * This exercises the actual measurement code rather than the lightweight probe.
     *
     * Returns:
     *  - "OK <received>/<sent> mean=<ms> stdev=<ms>" on success
     *  - "error: <message>" on failure
     */
    suspend fun latencyFull(
        host: String,
        httpPort: Int = 8080,
        udpPort: Int = 1053,
        durationMs: Long = 3000,
        mid: String = "localtest",
        userAgent: String? = null
    ): String {
        return try {
            // Construct and start the real latency runner, using a Server as LatencyTest expects.
            // We only need latency endpoints for this local test; throughput URLs are optional here.
            val urls = mapOf(
                "http:///latency/v1/authorize" to "http://$host:$httpPort/latency/v1/authorize",
                "http:///latency/v1/result"    to "http://$host:$httpPort/latency/v1/result"
            )
            val server = Server(machine = host, location = null, urls = urls)
            val test = LatencyTest(
                server = server,
                measurementId = mid,
                latencyPort = udpPort,
                duration = durationMs,
                userAgent = userAgent
            )

            // Kotlin/Native note:
            // Run inside a supervised scope with an exception handler so any uncaught coroutine exceptions
            // are captured as a string instead of crashing the process when bridged to Swift.
            var uncaught: Throwable? = null
            val handler = CoroutineExceptionHandler { _, e ->
                println("LatencyFull uncaught: ${e.message}")
                uncaught = e
            }

            withContext(Dispatchers.Default + SupervisorJob() + handler) {
                test.start()
                // Drain updates until completion or timeout (duration + small grace)
                val overall = durationMs.toInt().milliseconds + 3.seconds
                withTimeoutOrNull(overall) {
                    while (true) {
                        val rc = test.updatesChan.receiveCatching()
                        val upd = rc.getOrNull() ?: break
                        println("LatencyUpdate: $upd")
                    }
                }
            }

            // Prefer any captured uncaught exception
            uncaught?.let { return "error: ${it.message ?: it::class.simpleName ?: "unknown"}" }

            // Summarize outcome
            test.error?.let { return "error: ${it.message ?: it::class.simpleName ?: "unknown"}" }
            val res: LatencyResult = test.result ?: return "error: no result"
            val received = res.PacketsReceived
            val sent = res.PacketsSent
            val rttsUs = res.RoundTrips.mapNotNull { it.rttUs }
            if (rttsUs.isEmpty()) return "OK $received/$sent (no samples)"
            val n = rttsUs.size
            val meanUs = rttsUs.sum().toDouble() / n
            var variance = 0.0
            for (v in rttsUs) {
                val d = v - meanUs
                variance += d * d
            }
            variance /= n
            val stdevUs = kotlin.math.sqrt(variance)
            val meanMs = meanUs / 1000.0
            val stdevMs = stdevUs / 1000.0
            "OK $received/$sent mean=${fmt2(meanMs)}ms stdev=${fmt2(stdevMs)}ms"
        } catch (t: Throwable) {
            // FINAL guard so nothing escapes across the Swift bridge.
            "error: ${t.message ?: t::class.simpleName ?: "unknown"}"
        }
    }

    suspend fun loggerHello(): String {
        return try {
            Log.i("QuickTests", "Hello from loggerHello")
            "OK"
        } catch (t: Throwable) {
            "error: ${t.message ?: t::class.simpleName ?: "unknown"}"
        }
    }


    /**
     * Run a short multi-stream throughput test over WebSocket against a single host:port.
     *
     * This is a smoke test for the KMP pipeline, not a full UX flow. It:
     *  1) builds a minimal Server URL map for ws:///throughput/v1/{download|upload}
     *  2) runs ThroughputTest
     *  3) aggregates Application-layer bytes from all streams
     *  4) returns a compact summary string
     *
     * The function is Swift-friendly: it manages its own scope and calls [completion] instead of using suspend.
     */
    fun throughputSmokeTest(
        host: String,
        wsPort: Int,
        directionStr: String = "download",   // "download" or "upload"
        streams: Int = 2,
        durationMs: Long = 5_000,
        delayMs: Long = 0,
        userAgent: String? = null,
        completion: (String?, Throwable?) -> Unit
    ) {
        val dir = when (directionStr.lowercase()) {
            "download", "down", "dl" -> ThroughputDirection.DOWNLOAD
            "upload", "up", "ul" -> ThroughputDirection.UPLOAD
            else -> ThroughputDirection.DOWNLOAD
        }

        // Build the minimal URL map that Server expects.
        // Server.getThroughputUrl looks for "ws:///throughput/v1/download" and ".../upload".
        val wsBase = "ws://$host:$wsPort"
        val urls = mapOf(
            "ws:///$THROUGHPUT_DOWNLOAD_PATH" to "$wsBase/$THROUGHPUT_DOWNLOAD_PATH",
            "ws:///$THROUGHPUT_UPLOAD_PATH"   to "$wsBase/$THROUGHPUT_UPLOAD_PATH",
        )
        val server = Server(machine = host, location = null, urls = urls)

        // Internal scope; independent of callers.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        scope.launch {
            Log.d("ThroughputTest", "starting smoke test: dir=${dir} streams=${streams} durationMs=${durationMs} wsBase=$wsBase")
            try {
                val test = ThroughputTest(
                    server = server,
                    direction = dir,
                    numStreams = streams,
                    duration = durationMs,
                    delay = delayMs,
                    measurementId = "localtest", // harmless for local testing; omit for production locate flow
                    userAgent = userAgent
                )

                var appBytesTotal: Long = 0
                var firstTs = Clock.System.now()
                var lastTs = firstTs
                var updates = 0

                // Start and collect until the test closes its channel.
                test.start()
                // Aggregate application-layer bytes using per-stream deltas, and keep
                // separate tallies for client- vs server-originated measurements so
                // we can diagnose “all zeros” situations.
                val lastClient = LongArray(streams) { 0L }
                val lastServer = LongArray(streams) { 0L }
                var clientUpdates = 0
                var serverUpdates = 0
                var clientBytes = 0L
                var serverBytes = 0L

                for (u in test.updatesChan) {
                    val app = u.measurement.Application
                    val s = u.stream.coerceIn(0, streams - 1)

                    if (u.fromServer) {
                        // Server-originated cumulative counter to use
                        val cum = when (dir) {
                            ThroughputDirection.DOWNLOAD -> app.BytesSent      // server sent to us
                            ThroughputDirection.UPLOAD   -> app.BytesReceived   // server received from us
                        }
                        val delta = (cum - lastServer[s]).coerceAtLeast(0)
                        lastServer[s] = cum
                        serverBytes += delta
                        appBytesTotal += delta
                        serverUpdates++
                    } else {
                        // Client-originated cumulative counter to use
                        val cum = when (dir) {
                            ThroughputDirection.DOWNLOAD -> app.BytesReceived   // we received
                            ThroughputDirection.UPLOAD   -> app.BytesSent       // we sent
                        }
                        val delta = (cum - lastClient[s]).coerceAtLeast(0)
                        lastClient[s] = cum
                        clientBytes += delta
                        appBytesTotal += delta
                        clientUpdates++
                    }

                    // Track time bounds for a rough bitrate
                    if (updates == 0) firstTs = u.time
                    lastTs = u.time
                    updates++
                }

                // Small diagnostics string appended to summary for visibility.
                val diagSummary = "client=${clientUpdates}/${fmt2(clientBytes / 1_000_000.0)}M " +
                                  "server=${serverUpdates}/${fmt2(serverBytes / 1_000_000.0)}M"

                // Compute Mbps based on observed elapsed. Guard against very small intervals.
                val elapsedMs = max(1, lastTs.toEpochMilliseconds() - firstTs.toEpochMilliseconds())
                val mbits = (appBytesTotal * 8.0) / 1_000_000.0
                val mbps = mbits / (elapsedMs / 1000.0)

                val summary =
                    "Throughput $directionStr OK | streams=$streams dur=${durationMs}ms " +
                    "bytes=$appBytesTotal app Mbits=${fmt2(mbits)} " +
                    "Mbps=${fmt2(mbps)} updates=$updates [$diagSummary]"

                completion(summary, null)
            } catch (t: Throwable) {
                completion(null, t)
            } finally {
                // Ensure we do not leak this helper scope.
                scope.cancel()
            }
        }
    }
}