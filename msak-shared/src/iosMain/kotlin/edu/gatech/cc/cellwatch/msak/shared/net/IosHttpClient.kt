// iosMain
package edu.gatech.cc.cellwatch.msak.shared.net

import io.ktor.client.*
import io.ktor.client.engine.darwin.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json


actual fun provideHttpClient(config: NetHttpConfig): HttpClient =
    HttpClient(Darwin) {
        expectSuccess = false
        engine {
            configureSession {
                timeoutIntervalForRequest = config.requestTimeoutMs.toDouble() / 1000.0
                timeoutIntervalForResource = config.requestTimeoutMs.toDouble() / 1000.0
                allowsCellularAccess = true
            }
        }
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; explicitNulls = false })
        }
        if (config.verboseLogging) install(Logging) { level = LogLevel.INFO }
        install(HttpTimeout) {
            requestTimeoutMillis = config.requestTimeoutMs
            connectTimeoutMillis = config.connectTimeoutMs
        }
        defaultRequest { config.userAgent?.let { headers.append("User-Agent", it) } }
    }