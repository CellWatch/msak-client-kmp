package edu.gatech.cc.cellwatch.msak.shared.net

import io.ktor.client.HttpClient

data class NetHttpConfig(
    val userAgent: String? = null,
    val requestTimeoutMs: Long = 15_000,
    val connectTimeoutMs: Long = 10_000,
    val verboseLogging: Boolean = false
)

object NetHttp {
    private var clientOrNull: HttpClient? = null

    fun initialize(config: NetHttpConfig = NetHttpConfig()) {
        if (clientOrNull == null) clientOrNull = provideHttpClient(config)
    }

    val client: HttpClient
        get() = clientOrNull ?: error("NetHttp not initialized. Call NetHttp.initialize() once at app start.")
}

// Platform engines provided by each target
expect fun provideHttpClient(config: NetHttpConfig): HttpClient