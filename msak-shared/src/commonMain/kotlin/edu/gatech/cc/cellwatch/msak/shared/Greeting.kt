package edu.gatech.cc.cellwatch.msak.shared

import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

class Greeting() {
    private val platform = getPlatform()

    fun greet(): String {
        val current = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())

        return buildString {
            appendLine("Hello, ${platform.name}! Current time: $current")
        }
    }
}