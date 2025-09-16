package edu.gatech.cc.cellwatch.msak_tester

import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.util.Log
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import edu.gatech.cc.cellwatch.msak.shared.ServerFactory
import edu.gatech.cc.cellwatch.msak.shared.latency.LatencyConfig
import edu.gatech.cc.cellwatch.msak.shared.latency.runLatency
import edu.gatech.cc.cellwatch.msak.shared.throughput.ThroughputConfig
import edu.gatech.cc.cellwatch.msak.shared.throughput.ThroughputDirection
import edu.gatech.cc.cellwatch.msak.shared.throughput.runThroughput
import edu.gatech.cc.cellwatch.msak_tester.databinding.ActivityMainBinding
import kotlinx.coroutines.launch


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
        binding.latDuration.setText("3000")
        binding.tputStreams.setText("3")
        binding.tputDuration.setText("5000")
        binding.tputDelay.setText("0")

        // setup buttons
        binding.btnLatency.setOnClickListener { wrapTestRun { testLatency() } }
        binding.btnDownload.setOnClickListener { wrapTestRun { testThroughput(ThroughputDirection.DOWNLOAD) } }
        binding.btnUpload.setOnClickListener { wrapTestRun { testThroughput(ThroughputDirection.UPLOAD) } }

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

    /** Demonstrates building a LatencyConfig and calling the shared API. */
    private suspend fun testLatency() {
        printHeader("Latency test")
        val cfg = buildLatencyConfig()
        printMsg("Config → host=${cfg.server.machine} udpPort=${cfg.udpPort} durationMs=${cfg.duration} tls=${binding.localSecure.isChecked}")
        try {
            val summary = runLatency(cfg)
            printMsg("Result → ${summary.asText()}")
        } catch (t: Throwable) {
            Log.e(TAG, "Latency failed", t)
            printError("Latency failed: ${t.message ?: t::class.simpleName ?: "unknown"}")
        }
    }

    /** Demonstrates building a ThroughputConfig and calling the shared API. */
    private suspend fun testThroughput(dir: ThroughputDirection) {
        printHeader("Throughput ${dir.name.lowercase()} test")
        val cfg = buildThroughputConfig(dir)
        printMsg("Config → host=${cfg.server.machine} streams=${cfg.streams} durationMs=${cfg.durationMs} delayMs=${cfg.delayMs} tls=${binding.localSecure.isChecked}")
        try {
            val summary = runThroughput(cfg)
            printMsg("Result → ${summary.asText()}")
        } catch (t: Throwable) {
            Log.e(TAG, "Throughput failed", t)
            printError("Throughput ${dir.name} failed: ${t.message ?: t::class.simpleName ?: "unknown"}")
        }
    }


    /** Resolve emulator loopback and parse host/port from the UI value. */
    private fun normalizeHostPort(hostPort: String): Pair<String, Int> {
        val parts = hostPort.split(":", limit = 2)
        val rawHost = parts[0].ifBlank { "127.0.0.1" }
        val port = parts.getOrNull(1)?.toIntOrNull() ?: 8080
        val host = if (rawHost.equals("localhost", true) || rawHost == "127.0.0.1") "10.0.2.2" else rawHost
        return host to port
    }

    /** Build a LatencyConfig from current UI fields to demonstrate the API. */
    private fun buildLatencyConfig(): LatencyConfig {
        val (hostPort, secure) = readServerFromUi()
        val (host, httpPort) = normalizeHostPort(hostPort)
        val server = ServerFactory.forLatency(host = host, httpPort = httpPort, useTls = secure)
        val durationMs = binding.latDuration.text.toString().toLongOrNull() ?: 3000L
        return LatencyConfig(
            server = server,
            measurementId = "android-demo",
            udpPort = 1053,
            duration = durationMs,
            userAgent = USER_AGENT
        )
    }

    /** Build a ThroughputConfig from current UI fields to demonstrate the API. */
    private fun buildThroughputConfig(dir: ThroughputDirection): ThroughputConfig {
        val (hostPort, secure) = readServerFromUi()
        val (host, wsPort) = normalizeHostPort(hostPort)
        val server = ServerFactory.forThroughput(host = host, wsPort = wsPort, useTls = secure)
        val streams = binding.tputStreams.text.toString().toIntOrNull() ?: 2
        val durationMs = binding.tputDuration.text.toString().toLongOrNull() ?: 5000L
        val delayMs = binding.tputDelay.text.toString().toLongOrNull() ?: 0L
        return ThroughputConfig(
            server = server,
            direction = dir,
            streams = streams,
            durationMs = durationMs,
            delayMs = delayMs,
            measurementId = "android-demo",
            userAgent = USER_AGENT
        )
    }

    /** Read host:port and secure flag from UI. */
    private fun readServerFromUi(): Pair<String, Boolean> {
        val hostPort = binding.localHost.text.toString().ifBlank { "127.0.0.1:8080" }
        val secure = binding.localSecure.isChecked
        return hostPort to secure
    }

    companion object {
        const val USER_AGENT = "msak-android/test"
    }
}