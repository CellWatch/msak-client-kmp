package edu.gatech.cc.cellwatch.msak.shared

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform