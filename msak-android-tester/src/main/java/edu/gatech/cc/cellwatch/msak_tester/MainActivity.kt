package edu.gatech.cc.cellwatch.msak_tester

import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.util.Log
import edu.gatech.cc.cellwatch.msak.LATENCY_DURATION
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import edu.gatech.cc.cellwatch.msak.latency.LatencyTest
import edu.gatech.cc.cellwatch.msak.locate.LocateManager
import edu.gatech.cc.cellwatch.msak.throughput.ThroughputDirection
import edu.gatech.cc.cellwatch.msak.throughput.ThroughputTest
import edu.gatech.cc.cellwatch.msak_tester.databinding.ActivityMainBinding
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.math.pow
import kotlin.math.roundToInt

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
        binding.localHost.setText("localhost:8080")
        binding.localSecure.isChecked = false
        binding.latDuration.setText("$LATENCY_DURATION")
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

    fun getLocateManager(): LocateManager {
        val localServerHost = binding.localHost.text.toString()
        val localServerSecure = binding.localSecure.isChecked

        return LocateManager(
            serverEnv = if (localServerHost.isEmpty()) LocateManager.ServerEnv.PROD else LocateManager.ServerEnv.LOCAL,
            userAgent = USER_AGENT,
            msakLocalServerHost = localServerHost,
            msakLocalServerSecure = localServerSecure,
        )
    }

    suspend fun testLatency() {
        val duration = binding.latDuration.text.toString().toLong()
        val locateManager = getLocateManager()

        try {
            printHeader("Locating latency server")
            val server = locateManager.locateLatencyServers().firstOrNull()
            if (server == null) {
                printError("No latency server found")
                return
            }
            printMsg("Using latency server ${server.machine} in ${server.location}")

            printHeader("Running latency test")
            val test = LatencyTest(server, duration = duration, userAgent = USER_AGENT)
            test.start()
            test.updatesChan.consumeEach { update ->
                Log.v(TAG, "latency update: $update")
            }

            if (test.error != null) {
                Log.w(TAG, "latency test failed", test.error)
                printError("Latency test failed: ${test.error}")
                return
            }

            val result = test.result
            if (result == null) {
                printError("Missing latency result")
                return
            }

            val rtts = result.RoundTrips.filter { !it.Lost }.map { it.RTT / 1000.0 }
            val meanRTT = rtts.average()
            val stddev = (rtts.sumOf { (it - meanRTT).pow(2) } / rtts.size).pow(0.5)

            printMsg("Lost ${result.PacketsReceived - result.PacketsSent} of ${result.PacketsSent + result.PacketsReceived} packets")
            printMsg("Mean RTT: ${meanRTT.roundToInt()} ms")
            printMsg("RTT stddev: ${stddev.roundToInt()} ms")
            printMsg("Latency test complete")
        } catch (e: Exception) {
            Log.e(TAG, "latency test failed", e)
            printError("Latency test failed: $e")
        }
    }

    suspend fun testThroughput(dir: ThroughputDirection) {
        val streams = binding.tputStreams.text.toString().toInt()
        val duration = binding.tputDuration.text.toString().toLong()
        val delay = binding.tputDelay.text.toString().toLong()
        val locateManager = getLocateManager()

        try {
            printHeader("Locating throughput server")
            val server = locateManager.locateThroughputServers().firstOrNull()
            if (server == null) {
                printError("No throughput server found")
                return
            }
            printMsg("Using throughput server ${server.machine} at ${server.location}")

            printHeader("Running ${dir.name.lowercase()} test")
            val test = ThroughputTest(server, dir, streams, duration, delay, userAgent = USER_AGENT)
            test.start()

            test.updatesChan.consumeEach { update ->
                Log.v(TAG, "throughput update: $update")
            }

            var sumAppMbps = 0.0
            var sumNetMbps = 0.0

            test.streams.forEachIndexed { i, stream ->
                if (stream.error != null) {
                    Log.w(TAG, "throughput stream ${i + 1} failed", stream.error)
                    printError("Throughput stream ${i + 1} failed: ${stream.error}")
                } else {
                    val update = stream.updates.lastOrNull { it.fromServer == (dir == ThroughputDirection.UPLOAD) }
                    if (update == null) {
                        printError("No receiver updates for stream ${i + 1}")
                    } else {
                        val appBytes = update.measurement.Application.BytesReceived
                        val netBytes = update.measurement.Network?.BytesReceived
                        val usecs = update.measurement.ElapsedTime
                        val secs = usecs.toDouble() / 1000000.0
                        val appMbps = calcMbps(appBytes, usecs)
                        val netMbps = calcMbps(netBytes, usecs)

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

    companion object {
        const val USER_AGENT = "msak-android/test"
    }
}