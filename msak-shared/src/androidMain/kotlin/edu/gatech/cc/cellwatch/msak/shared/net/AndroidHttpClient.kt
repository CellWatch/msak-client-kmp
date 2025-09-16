package edu.gatech.cc.cellwatch.msak.shared.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.request.header
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

// Android actual for the common expect fun provideHttpClient(...)
actual fun provideHttpClient(config: NetHttpConfig): HttpClient {
    // You can preconfigure OkHttp if you need custom TLS, socket factories, or interceptors.
    val ok = OkHttpClient.Builder()
        .connectTimeout(config.connectTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(config.requestTimeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(config.requestTimeoutMs, TimeUnit.MILLISECONDS)
        .build()

    return HttpClient(OkHttp) {
        expectSuccess = false
        engine {
            // Use our preconfigured OkHttpClient
            preconfigured = ok
        }
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    explicitNulls = false
                }
            )
        }
        if (config.verboseLogging) {
            install(Logging) {
                level = LogLevel.INFO
            }
        }
        install(HttpTimeout) {
            requestTimeoutMillis = config.requestTimeoutMs
            connectTimeoutMillis = config.connectTimeoutMs
        }
        install(DefaultRequest) {
            config.userAgent?.let { header(HttpHeaders.UserAgent, it) }
        }
    }
}