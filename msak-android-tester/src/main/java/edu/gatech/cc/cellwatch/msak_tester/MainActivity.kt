package edu.gatech.cc.cellwatch.msak_tester

import android.net.Uri
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.util.Log
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import edu.gatech.cc.cellwatch.msak.shared.Server
import edu.gatech.cc.cellwatch.msak.shared.ServerFactory
import edu.gatech.cc.cellwatch.msak.shared.latency.LatencyConfig
import edu.gatech.cc.cellwatch.msak.shared.latency.runLatency
import edu.gatech.cc.cellwatch.msak.shared.locate.LocateManager
import edu.gatech.cc.cellwatch.msak.shared.net.NetHttp
import edu.gatech.cc.cellwatch.msak.shared.net.NetHttpConfig
import edu.gatech.cc.cellwatch.msak.shared.throughput.ThroughputConfig
import edu.gatech.cc.cellwatch.msak.shared.throughput.ThroughputDirection
import edu.gatech.cc.cellwatch.msak.shared.throughput.runThroughput
import edu.gatech.cc.cellwatch.msak_tester.databinding.ActivityMainBinding
import kotlinx.coroutines.launch


class MainActivity : AppCompatActivity() {
    private val TAG = this::class.simpleName
    private lateinit var binding: ActivityMainBinding

    private var currentServer: Server? = null
    private enum class ServerSource { LOCAL, LOCATED }
    private var serverSource: ServerSource = ServerSource.LOCAL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize shared KMP HTTP client once per process
        NetHttp.initialize(
            NetHttpConfig(
                userAgent = USER_AGENT,
                requestTimeoutMs = 15_000,
                connectTimeoutMs = 10_000,
                verboseLogging = false
            )
        )

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

        // locate buttons (parity with iOS: 📍L and 📍T)
        binding.btnLocateLatency.setOnClickListener { wrapTestRun { locateLatencyAndPopulate() } }
        binding.btnLocateThroughput.setOnClickListener { wrapTestRun { locateThroughputAndPopulate() } }

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
        binding.btnLocateLatency.isEnabled = enabled
        binding.btnLocateThroughput.isEnabled = enabled
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

    private fun printInfo(text: String) = printMsg(text)

    /** Demonstrates building a LatencyConfig and calling the shared API (unified server). */
    private suspend fun testLatency() {
        printHeader("Latency test")
        val server = activeServer()
        // NOTE: located servers carry access_token URLs; we don't rewrite them.
        if (serverSource == ServerSource.LOCATED) {
            printInfo("Latency: using located server URLs (with access_token)")
        }
        printInfo("Latency: server=${server.machine}")

        val durationMs = binding.latDuration.text.toString().toLongOrNull() ?: 3000L
        val cfg = LatencyConfig(
            server = server,
            measurementId = "android-demo",
            duration = durationMs,
            userAgent = USER_AGENT
        )
        try {
            val summary = runLatency(cfg)
            printMsg("Result → ${summary.asText()}")
        } catch (t: Throwable) {
            Log.e(TAG, "Latency failed", t)
            printError("Latency failed: ${t.message ?: t::class.simpleName ?: "unknown"}")
        }
    }

    /** Demonstrates building a ThroughputConfig and calling the shared API (unified server). */
    private suspend fun testThroughput(dir: ThroughputDirection) {
        printHeader("Throughput ${dir.name.lowercase()} test")
        val server = activeServer()
        if (serverSource == ServerSource.LOCATED) {
            printInfo("Throughput ${dir.name}: using located server URLs (with access_token)")
        }
        printInfo("Throughput ${dir.name}: server=${server.machine}")

        val streams = binding.tputStreams.text.toString().toIntOrNull() ?: 2
        val durationMs = binding.tputDuration.text.toString().toLongOrNull() ?: 5000L
        val delayMs = binding.tputDelay.text.toString().toLongOrNull() ?: 0L

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

    /** Split "host[:port]" where port is optional and numeric. */
    private fun splitHostPort(value: String): Pair<String, Int?> {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return "127.0.0.1" to null
        val idx = trimmed.lastIndexOf(':')
        if (idx > 0) {
            val maybePort = trimmed.substring(idx + 1)
            val p = maybePort.toIntOrNull()
            if (p != null) return trimmed.substring(0, idx) to p
        }
        return trimmed to null
    }

    /** Build/refresh a local Server using unified ServerFactory, honoring useTLS and UI host:port. */
    private fun rebuildLocalServerFromFields() {
        val (rawHostPort, secure) = readServerFromUi()
        val (hostOnly, portOpt) = splitHostPort(rawHostPort)
        val defaultPort = if (secure) 443 else 8080
        val port = portOpt ?: defaultPort
        val server = ServerFactory.buildServer(
            host = hostOnly,
            port = port,
            useTls = secure,
            includeLatency = true,
            includeThroughput = true,
            measurementId = "android-demo",
            latencyPathPrefix = "/latency/v1",
            throughputPathPrefix = "/throughput/v1",
            latencyUdpPort = 1053
        )
        currentServer = server
        serverSource = ServerSource.LOCAL
        printInfo("Server built (local): host=$hostOnly port=$port tls=$secure")
    }

    /** Choose the active server: prefer located; else build/return local. */
    private fun activeServer(): Server {
        currentServer?.let {
            printInfo("Using existing server (${if (serverSource == ServerSource.LOCATED) "located" else "local"}) → ${it.machine}")
            return it
        }
        rebuildLocalServerFromFields()
        return currentServer!!
    }

    /** Read host:port and secure flag from UI. */
    private fun readServerFromUi(): Pair<String, Boolean> {
        val hostPort = binding.localHost.text.toString().ifBlank { "127.0.0.1:8080" }
        val secure = binding.localSecure.isChecked
        return hostPort to secure
    }

    /** Locate latency server and populate host/TLS; keep located Server verbatim. */
    private suspend fun locateLatencyAndPopulate() {
        printHeader("Locate (latency)")
        try {
            val lm = LocateManager(LocateManager.ServerEnv.PROD, userAgent = USER_AGENT)
            val lServers = lm.locateLatencyServers()
            val chosen = lServers.firstOrNull()
            if (chosen == null) {
                printError("Locate (latency): no latency servers returned")
                return
            }
            val latencyUrl = chosen.urls["https:///latency/v1/authorize"]
                ?: chosen.urls["http:///latency/v1/authorize"]
            if (latencyUrl == null) {
                printError("Locate (latency): missing latency authorize URL")
                return
            }
            val (h, p, tls) = parseHostPortSecure(latencyUrl)
            // Update UI and state
            binding.localHost.setText("$h:$p")
            binding.localSecure.isChecked = tls
            currentServer = chosen
            serverSource = ServerSource.LOCATED
            printInfo("Server set from Locate (latency) → host=$h port=$p tls=$tls")
            printInfo("Locate (latency): selected ${chosen.machine} → host=$h port=$p tls=$tls")
            printInfo("You can now run Latency/Download/Upload against this server")
        } catch (t: Throwable) {
            printError("Locate (latency) error: ${t.message ?: t::class.simpleName ?: "unknown"}")
        }
    }

    /** Locate throughput server and populate host/TLS; keep located Server verbatim. */
    private suspend fun locateThroughputAndPopulate() {
        printHeader("Locate (throughput)")
        try {
            val lm = LocateManager(LocateManager.ServerEnv.PROD, userAgent = USER_AGENT)
            val tServers = lm.locateThroughputServers()
            val chosen = tServers.firstOrNull()
            if (chosen == null) {
                printError("Locate (throughput): no throughput servers returned")
                return
            }
            val wsUrl = chosen.urls["ws:///throughput/v1/download"]
                ?: chosen.urls["ws:///throughput/v1/upload"]
            if (wsUrl == null) {
                printError("Locate (throughput): missing throughput URL")
                return
            }
            val (h, p, tls) = parseHostPortSecure(wsUrl)
            // Update UI and state
            binding.localHost.setText("$h:$p")
            binding.localSecure.isChecked = tls
            currentServer = chosen
            serverSource = ServerSource.LOCATED
            printInfo("Server set from Locate (throughput) → host=$h port=$p tls=$tls")
            printInfo("Locate (throughput): selected ${chosen.machine} → host=$h port=$p tls=$tls")
            printInfo("You can now run Download/Upload/Latency against this server")
        } catch (t: Throwable) {
            printError("Locate (throughput) error: ${t.message ?: t::class.simpleName ?: "unknown"}")
        }
    }

    /** Parse ws/wss/http/https URL into host, port, and TLS flag. */
    private fun parseHostPortSecure(url: String): Triple<String, Int, Boolean> {
        val u = Uri.parse(url)
        val scheme = (u.scheme ?: "").lowercase()
        val host = u.host ?: ""
        val port = if (u.port != -1) u.port else defaultPortForScheme(scheme)
        val secure = scheme == "wss" || scheme == "https"
        return Triple(host, port, secure)
    }

    private fun defaultPortForScheme(s: String): Int = when (s) {
        "wss", "https" -> 443
        "ws", "http" -> 80
        else -> 0
    }

    companion object {
        const val USER_AGENT = "msak-android/test"
    }
}