package edu.gatech.cc.cellwatch.msak_tester

import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.util.Log
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import edu.gatech.cc.cellwatch.msak.shared.LATENCY_DURATION
import edu.gatech.cc.cellwatch.msak.shared.Server
import edu.gatech.cc.cellwatch.msak.shared.latency.LatencyTest
import edu.gatech.cc.cellwatch.msak.shared.throughput.ThroughputDirection
import edu.gatech.cc.cellwatch.msak.shared.throughput.ThroughputTest
import edu.gatech.cc.cellwatch.msak.shared.LATENCY_AUTHORIZE_PATH
import edu.gatech.cc.cellwatch.msak.shared.LATENCY_RESULT_PATH
import edu.gatech.cc.cellwatch.msak.shared.THROUGHPUT_UPLOAD_PATH
import edu.gatech.cc.cellwatch.msak.shared.THROUGHPUT_DOWNLOAD_PATH
import edu.gatech.cc.cellwatch.msak_tester.databinding.ActivityMainBinding
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.pow
import kotlin.math.roundToInt

//import edu.gatech.cc.cellwatch.msak.shared.platform   // <-- from :msak-shared


class MainActivity : AppCompatActivity() {
    private val TAG = this::class.simpleName
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // setup defaults
        binding.localHost.setText("10.0.2.2:8080")
        binding.localSecure.isChecked = false
        binding.latDuration.setText("$LATENCY_DURATION")
        binding.tputStreams.setText("3")
        binding.tputDuration.setText("5000")
        binding.tputDelay.setText("0")

        // setup buttons
        binding.btnLatency.setOnClickListener { wrapTestRun { testLatency() } }
        binding.btnDownload.setOnClickListener { wrapTestRun { testThroughputSmoke(ThroughputDirection.DOWNLOAD) } }
        binding.btnUpload.setOnClickListener { wrapTestRun { testThroughputSmoke(ThroughputDirection.UPLOAD) } }

        // setup output view
        binding.output.movementMethod = ScrollingMovementMethod()
    }

    fun wrapTestRun(testRun: suspend () -> Unit) {
        lifecycleScope.launch {
            setBtnsEnabled(false)
            testRun()
            setBtnsEnabled(true)
        }
    }

    fun setBtnsEnabled(enabled: Boolean) {
        binding.btnLatency.isEnabled = enabled
        binding.btnDownload.isEnabled = enabled
        binding.btnUpload.isEnabled = enabled
    }

    fun printHeader(text: String) {
        printMsg("===== ${text.uppercase()} =====")
    }

    fun printError(text: String) {
        printMsg("ERROR: $text")
    }

    fun printMsg(text: String) {
        Log.v(TAG, "MSG: $text")
        binding.output.append("$text\n")
    }

    private suspend fun testLatency() {
        // Read host:port and TLS from UI
        val (hostPort, secure) = readServerFromUi()

        // Split host:port
        val parts = hostPort.split(":", limit = 2)
        val hostOnly = parts[0]
        val httpPort = parts.getOrNull(1)?.toIntOrNull() ?: 8080

        // Android emulator: 127.0.0.1/localhost → 10.0.2.2
        val host = if (hostOnly.equals("localhost", true) || hostOnly == "127.0.0.1") "10.0.2.2" else hostOnly

        // Delegate to shared KMP helper for parity with iOS
        val summary = edu.gatech.cc.cellwatch.msak.shared.net.QuickTests.latencyFull(
            host = host,
            httpPort = httpPort,
            udpPort = 1053,
            durationMs = 3000,
            mid = "localtest",
            userAgent = USER_AGENT
        )

        printMsg(summary)
    }

    private suspend fun testThroughputSmoke(dir: ThroughputDirection) {
        // Read desired parameters from UI
        val streams = binding.tputStreams.text.toString().toIntOrNull() ?: 1
        val durationMs = binding.tputDuration.text.toString().toLongOrNull() ?: 3000L
        val delayMs = binding.tputDelay.text.toString().toLongOrNull() ?: 0L
        val (hostPort, secure) = readServerFromUi()

        // Determine host and WS port from the UI field
        val parts = hostPort.split(":", limit = 2)
        val rawHost = parts[0]
        val wsPort = parts.getOrNull(1)?.toIntOrNull() ?: 8080

        // Android emulator: 127.0.0.1/localhost → 10.0.2.2
        val host = if (rawHost.equals("localhost", true) || rawHost == "127.0.0.1") "10.0.2.2" else rawHost

        // Direction string expected by QuickTests
        val directionStr = if (dir == ThroughputDirection.DOWNLOAD) "download" else "upload"

        printHeader("Throughput ${dir.name.lowercase()} (shared KMP smoke)")
        printMsg("Using throughput server $host:$wsPort (from=$hostPort, secure=$secure)")

        // Bridge the callback-style API to suspend so wrapTestRun can await completion
        suspendCancellableCoroutine<Unit> { cont ->
            edu.gatech.cc.cellwatch.msak.shared.net.QuickTests.throughputSmokeTest(
                host = host,
                wsPort = wsPort,
                directionStr = directionStr,
                streams = streams,
                durationMs = durationMs,
                delayMs = delayMs,
                userAgent = USER_AGENT
            ) { summary, throwable ->
                if (throwable != null) {
                    Log.e(TAG, "throughput smoke test failed", throwable)
                    printError("Throughput smoke test failed: ${throwable.message ?: throwable::class.simpleName ?: "unknown"}")
                } else {
                    printMsg(summary ?: "")
                }
                if (cont.isActive) cont.resume(Unit)
            }
            cont.invokeOnCancellation { /* no-op */ }
        }
    }

    suspend fun testThroughput(dir: ThroughputDirection) {
        val streams = binding.tputStreams.text.toString().toInt()
        val duration = binding.tputDuration.text.toString().toLong()
        val delay = binding.tputDelay.text.toString().toLong()
        val (hostPort, secure) = readServerFromUi()

        try {
            printHeader("Throughput ${dir.name.lowercase()} (shared KMP)")
            val server = buildLocalServer(hostPort, secure)
            printMsg("Using throughput server ${server.machine} (host=${hostPort}, secure=${secure})")

            val test = ThroughputTest(server, dir, streams, duration, delay, userAgent = USER_AGENT)
            test.start()

            val fromServerWanted = (dir == ThroughputDirection.UPLOAD)
            val lastRecvByStream = mutableMapOf<Int, edu.gatech.cc.cellwatch.msak.shared.throughput.ThroughputUpdate>()

            test.updatesChan.consumeEach { update ->
                Log.v(TAG, "throughput update: $update")
                if (update.fromServer == fromServerWanted) {
                    lastRecvByStream[update.stream] = update
                }
            }

            var sumAppMbps = 0.0
            var sumNetMbps = 0.0

            test.streams.forEachIndexed { i, stream ->
                if (stream.error != null) {
                    Log.w(TAG, "throughput stream ${i + 1} failed", stream.error)
                    printError("Throughput stream ${i + 1} failed: ${stream.error}")
                } else {
                    val update = lastRecvByStream[stream.id]
                    if (update == null) {
                        printError("No receiver updates for stream ${i + 1}")
                    } else {
                        val appBytes = update.measurement.Application.BytesReceived
                        val netBytes = update.measurement.Network?.BytesReceived
                        val elapsed = update.measurement.ElapsedTime // server uses microseconds
                        val secs = elapsed.toDouble() / 1_000_000.0
                        val appMbps = calcMbps(appBytes, elapsed)
                        val netMbps = calcMbps(netBytes, elapsed)

                        sumAppMbps += appMbps
                        sumNetMbps += netMbps

                        printMsg("Stream ${i + 1} transferred $appBytes bytes (app) over ${"%.2f".format(secs)} s")
                        printMsg("Stream ${i + 1} transferred $netBytes bytes (net) over ${"%.2f".format(secs)} s")
                        printMsg("Stream ${i + 1} speed (app): ${"%.2f".format(appMbps)} Mbps")
                        printMsg("Stream ${i + 1} speed (net): ${"%.2f".format(netMbps)} Mbps")
                    }
                }
            }

            printMsg("Aggregate speed (app): ${"%.2f".format(sumAppMbps)} Mbps")
            printMsg("Aggregate speed (net): ${"%.2f".format(sumNetMbps)} Mbps")
            printMsg("Throughput test complete")
        } catch (e: Exception) {
            Log.e(TAG, "throughput test failed", e)
            printError("Throughput test failed: $e")
        }
    }

    fun calcMbps(bytes: Long?, usecs: Long): Double {
        return ((bytes ?: 0).toDouble() * 8.0 / 1000000.0) / (usecs.toDouble() / 1000000.0)
    }

    /** Read host:port and secure flag from UI. */
    private fun readServerFromUi(): Pair<String, Boolean> {
        val hostPort = binding.localHost.text.toString().ifBlank { "127.0.0.1:8080" }
        val secure = binding.localSecure.isChecked
        return hostPort to secure
    }

    /**
     * Construct a minimal `Server` object using the shared KMP model.
     * We provide concrete URLs for both WS/WSS and HTTP/HTTPS lookups keyed by the
     * shared `Server` convention of "scheme:///path" so that the `Server` helpers
     * can compose full URLs correctly.
     */
    private fun buildLocalServer(hostPort: String, secure: Boolean): Server {
        val wsScheme = if (secure) "wss" else "ws"
        val httpScheme = if (secure) "https" else "http"
        val urls = mapOf(
            // Throughput
            "wss:///" + THROUGHPUT_UPLOAD_PATH to "${wsScheme}://${hostPort}/${THROUGHPUT_UPLOAD_PATH}",
            "ws:///" + THROUGHPUT_UPLOAD_PATH to "${wsScheme}://${hostPort}/${THROUGHPUT_UPLOAD_PATH}",
            "wss:///" + THROUGHPUT_DOWNLOAD_PATH to "${wsScheme}://${hostPort}/${THROUGHPUT_DOWNLOAD_PATH}",
            "ws:///" + THROUGHPUT_DOWNLOAD_PATH to "${wsScheme}://${hostPort}/${THROUGHPUT_DOWNLOAD_PATH}",
            // Latency
            "https:///" + LATENCY_AUTHORIZE_PATH to "${httpScheme}://${hostPort}/${LATENCY_AUTHORIZE_PATH}",
            "http:///" + LATENCY_AUTHORIZE_PATH to "${httpScheme}://${hostPort}/${LATENCY_AUTHORIZE_PATH}",
            "https:///" + LATENCY_RESULT_PATH to "${httpScheme}://${hostPort}/${LATENCY_RESULT_PATH}",
            "http:///" + LATENCY_RESULT_PATH to "${httpScheme}://${hostPort}/${LATENCY_RESULT_PATH}",
        )
        return Server(
            machine = hostPort,
            location = null,
            urls = urls,
        )
    }

    companion object {
        const val USER_AGENT = "msak-android/test"
    }
}
    // Helper to format double to 2 decimal places
    private fun fmt2(v: Double): String = String.format("%.2f", v)