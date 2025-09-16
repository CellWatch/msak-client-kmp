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
        binding.latDuration.setText("3000")
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

        // Parse host and port
        val parts = hostPort.split(":", limit = 2)
        val rawHost = parts[0]
        val httpPort = parts.getOrNull(1)?.toIntOrNull() ?: 8080

        // Android emulator: 127.0.0.1/localhost → 10.0.2.2
        val host = if (rawHost.equals("localhost", true) || rawHost == "127.0.0.1") "10.0.2.2" else rawHost

        printHeader("Latency (runner)")
        printMsg("Using latency server $host:$httpPort (secure=$secure)")

        val server = ServerFactory.forLatency(host = host, httpPort = httpPort, useTls = secure)

        val durationMs = binding.latDuration.text.toString().toLongOrNull() ?: 3000L
        val cfg = LatencyConfig(
            server = server,
            measurementId = "android-demo",
            udpPort = 1053,
            duration = durationMs,
            userAgent = USER_AGENT
        )

        try {
            val summary = runLatency(cfg)
            printMsg("Latency OK: ${summary.asText()}")
        } catch (t: Throwable) {
            Log.e(TAG, "Latency failed", t)
            printError("Latency failed: ${t.message ?: t::class.simpleName ?: "unknown"}")
        }
    }

    private suspend fun testThroughputSmoke(dir: ThroughputDirection) {
        // Read desired parameters from UI
        val streams = binding.tputStreams.text.toString().toIntOrNull() ?: 2
        val durationMs = binding.tputDuration.text.toString().toLongOrNull() ?: 5000L
        val delayMs = binding.tputDelay.text.toString().toLongOrNull() ?: 0L
        val (hostPort, secure) = readServerFromUi()

        // Parse host and port
        val parts = hostPort.split(":", limit = 2)
        val rawHost = parts[0]
        val wsPort = parts.getOrNull(1)?.toIntOrNull() ?: 8080

        // Android emulator: 127.0.0.1/localhost → 10.0.2.2
        val host = if (rawHost.equals("localhost", true) || rawHost == "127.0.0.1") "10.0.2.2" else rawHost

        printHeader("Throughput ${dir.name.lowercase()} (runner)")
        printMsg("Using throughput server $host:$wsPort (secure=$secure)")

        val server = ServerFactory.forThroughput(host = host, wsPort = wsPort, useTls = secure)
        val cfg = ThroughputConfig(
            server = server,
            direction = dir,
            streams = streams,
            durationMs = durationMs,
            delayMs = delayMs,
            measurementId = "android-demo",
            userAgent = USER_AGENT
        )

        try {
            val summary = runThroughput(cfg)
            printMsg("Throughput ${dir.name} OK: ${summary.asText()}")
        } catch (t: Throwable) {
            Log.e(TAG, "Throughput failed", t)
            printError("Throughput ${dir.name} failed: ${t.message ?: t::class.simpleName ?: "unknown"}")
        }
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