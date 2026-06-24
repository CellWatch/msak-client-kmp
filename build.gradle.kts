// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.jetbrains.kotlin.android) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.jetbrains.kotlin.multiplatform) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
}

val ensureMsakDocker by tasks.registering(Exec::class) {
    group = "verification"
    description = "Builds and starts the local MSAK Docker fixture from ../msak if needed."
    workingDir = rootDir
    commandLine("sh", "${rootDir.absolutePath}/scripts/ensure-msak-docker.sh")
}

tasks.register("androidDockerThroughputTest") {
    group = "verification"
    description = "Ensures the local MSAK Docker fixture is running, then runs Android instrumented tests."
    dependsOn(ensureMsakDocker)
    dependsOn(":msak-android-tester:connectedDebugAndroidTest")
}
