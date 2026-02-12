package edu.gatech.cc.cellwatch.msak.shared.throughput

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class ThroughputMeasurementDeserializationTest {
    private val tolerantJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    @Test
    fun decode_whenApplicationBytesSentMissing_defaultsToZero() {
        val payload = """{"Application":{"BytesReceived":1127},"ElapsedTime":307}"""

        val decoded = tolerantJson.decodeFromString<ThroughputMeasurement>(payload)

        assertEquals(0, decoded.Application.BytesSent)
        assertEquals(1127, decoded.Application.BytesReceived)
    }

    @Test
    fun decode_whenApplicationBytesReceivedMissing_defaultsToZero() {
        val payload = """{"Application":{"BytesSent":33},"ElapsedTime":307}"""

        val decoded = tolerantJson.decodeFromString<ThroughputMeasurement>(payload)

        assertEquals(33, decoded.Application.BytesSent)
        assertEquals(0, decoded.Application.BytesReceived)
    }

    @Test
    fun decode_whenBothApplicationCountersMissing_defaultsBothToZero() {
        val payload = """{"Application":{},"ElapsedTime":307}"""

        val decoded = tolerantJson.decodeFromString<ThroughputMeasurement>(payload)

        assertEquals(0, decoded.Application.BytesSent)
        assertEquals(0, decoded.Application.BytesReceived)
    }
}
