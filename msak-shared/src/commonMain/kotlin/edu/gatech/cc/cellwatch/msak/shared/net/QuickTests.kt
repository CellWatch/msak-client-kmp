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
import edu.gatech.cc.cellwatch.msak.shared.Server
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds



object QuickTests {
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
            "OK $received/$sent"
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
}