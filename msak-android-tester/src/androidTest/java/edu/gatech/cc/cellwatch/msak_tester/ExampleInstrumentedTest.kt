package edu.gatech.cc.cellwatch.msak_tester

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import edu.gatech.cc.cellwatch.msak.shared.ServerFactory
import edu.gatech.cc.cellwatch.msak.shared.throughput.ThroughputConfig
import edu.gatech.cc.cellwatch.msak.shared.throughput.ThroughputDirection
import edu.gatech.cc.cellwatch.msak.shared.throughput.runThroughput
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun useAppContext() {
        // Context of the app under test.
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("edu.gatech.cc.cellwatch.msak_tester", appContext.packageName)
    }

    @Test
    fun throughputSummaryReportsWarmupAgainstLocalDockerFixture() = runBlocking {
        val server = ServerFactory.buildServer(
            host = "10.0.2.2",
            port = 8080,
            useTls = false,
            measurementId = "android-docker-throughput-test",
        )

        val download = runThroughput(
            ThroughputConfig(
                server = server,
                direction = ThroughputDirection.DOWNLOAD,
                streams = 1,
                durationMs = 1_500,
                measurementId = "android-docker-download",
            )
        )
        val upload = runThroughput(
            ThroughputConfig(
                server = server,
                direction = ThroughputDirection.UPLOAD,
                streams = 1,
                durationMs = 1_500,
                measurementId = "android-docker-upload",
            )
        )

        assertTrue("download should transfer measured bytes", download.appBytesTotal > 0)
        assertTrue("download should report positive throughput", download.mbps > 0.0)
        assertTrue(
            "download should have multiple updates for a measured interval",
            download.clientUpdates + download.serverUpdates > 1
        )
        assertTrue("download warmup duration should be non-negative", download.warmupDurationMs >= 0)
        assertTrue("download warmup bytes should be non-negative", download.warmupBytesTransferred >= 0)
        assertTrue("download summary should include warmup reporting", download.asText().contains("warmup="))

        assertTrue("upload should transfer measured bytes", upload.appBytesTotal > 0)
        assertTrue("upload should report positive throughput", upload.mbps > 0.0)
        assertTrue(
            "upload should have multiple updates for a measured interval",
            upload.clientUpdates + upload.serverUpdates > 1
        )
        assertTrue("upload warmup duration should be non-negative", upload.warmupDurationMs >= 0)
        assertTrue("upload warmup bytes should be non-negative", upload.warmupBytesTransferred >= 0)
        assertTrue("upload summary should include warmup reporting", upload.asText().contains("warmup="))
    }
}
