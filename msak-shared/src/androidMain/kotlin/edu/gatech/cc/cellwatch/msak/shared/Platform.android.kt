package edu.gatech.cc.cellwatch.msak.shared

class AndroidPlatform : Platform {
    override val name: String =
        "Android ${android.os.Build.VERSION.RELEASE}"
}

actual fun getPlatform(): Platform = AndroidPlatform()