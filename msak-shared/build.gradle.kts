import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework
import org.gradle.api.publish.maven.MavenPublication
import java.security.MessageDigest


plugins {
    alias(libs.plugins.jetbrains.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.jetbrains.kotlin.serialization)
    alias(libs.plugins.jetbrains.kotlin.atomicfu)
    `maven-publish`
}

group = "edu.gatech.cc.cellwatch"
version = "0.2.0"

kotlin {

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }


// Target declarations - add or remove as needed below. These define
// which platforms this KMP module supports.
// See: https://kotlinlang.org/docs/multiplatform-discover-project.html#targets
    androidLibrary {
        namespace = "edu.gatech.cc.cellwatch.msak.shared"
        compileSdk = 35
        minSdk = 24

        withHostTestBuilder {
        }

        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

// For iOS targets, this is also where you should
// configure native binary output. For more information, see:
// https://kotlinlang.org/docs/multiplatform-build-native-binaries.html#build-xcframeworks

// A step-by-step guide on how to include this library in an XCode
// project can be found here:
// https://developer.android.com/kotlin/multiplatform/migrate

    val xcf = XCFramework("MsakShared")
    val iosTargets = listOf(iosX64(), iosArm64(), iosSimulatorArm64())

    iosTargets.forEach {
        it.binaries.framework {
            baseName = "MsakShared"
            xcf.add(this)
        }
    }

// Source set declarations.
// Declaring a target automatically creates a source set with the same name. By default, the
// Kotlin Gradle Plugin creates additional source sets that depend on each other, since it is
// common to share sources between related targets.
// See: https://kotlinlang.org/docs/multiplatform-hierarchy.html
    sourceSets {
        // Ensure all source sets opt-in consistently to avoid hierarchy mismatches
        all {
            languageSettings.optIn("kotlinx.serialization.ExperimentalSerializationApi")
            languageSettings.optIn("kotlin.ExperimentalStdlibApi")
        }
        commonMain {

            dependencies {
                implementation(libs.kotlin.stdlib)
                implementation(libs.kotlinx.coroutines.core)
                // Add KMP dependencies here

                // Ktor client and WebSockets
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.websockets)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.datetime)
                implementation(libs.ktor.client.logging)
                implementation(libs.kotlinx.atomicfu)
            }
        }

        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }

        androidMain {
            dependencies {
                implementation(libs.kotlinx.coroutines.android)
                // Add Android-specific dependencies here. Note that this source set depends on
                // commonMain by default and will correctly pull the Android artifacts of any KMP
                // dependencies declared in commonMain.
                implementation(libs.okhttp)
                implementation(libs.ktor.client.okhttp)
            }
        }

        getByName("androidDeviceTest") {
            dependencies {
                implementation(libs.androidx.runner)
                implementation(libs.androidx.core)
                implementation(libs.androidx.junit)
            }
        }

        iosMain {
            dependencies {
                // Add iOS-specific dependencies here. This a source set created by Kotlin Gradle
                // Plugin (KGP) that each specific iOS target (e.g., iosX64) depends on as
                // part of KMP’s default source set hierarchy. Note that this source set depends
                // on common by default and will correctly pull the iOS artifacts of any
                // KMP dependencies declared in commonMain.
                implementation(libs.ktor.client.darwin)
            }
            languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
        }
        val iosX64Main by getting {
            languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
        }
        val iosArm64Main by getting {
            languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
        }
        val iosSimulatorArm64Main by getting {
            languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
        }
    }

}

publishing {
    publications.withType<MavenPublication>().configureEach {
        groupId = project.group.toString()
        version = project.version.toString()

        artifactId = when (name) {
            "kotlinMultiplatform" -> "msak-client-kmp"
            else -> "msak-client-kmp-$name"
        }
    }

    repositories {
        maven {
            name = "local"
            url = uri(layout.buildDirectory.dir("repo"))
        }
    }
}

val localAppleDistDir = layout.buildDirectory.dir("local-dist/apple/msak-client-kmp/${project.version}")

tasks.register<Zip>("zipLocalDebugXcframework") {
    dependsOn("assembleMsakSharedDebugXCFramework")
    from(layout.buildDirectory.dir("XCFrameworks/debug/MsakShared.xcframework")) {
        into("MsakShared.xcframework")
    }
    archiveFileName.set("MsakShared.xcframework.zip")
    destinationDirectory.set(localAppleDistDir)
}

tasks.register("writeLocalDebugXcframeworkSha256") {
    dependsOn("zipLocalDebugXcframework")
    doLast {
        val zipFile = localAppleDistDir.get().file("MsakShared.xcframework.zip").asFile
        val digest = MessageDigest.getInstance("SHA-256")
        zipFile.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        val shaFile = localAppleDistDir.get().file("MsakShared.xcframework.sha256").asFile
        shaFile.parentFile.mkdirs()
        shaFile.writeText("$hash  MsakShared.xcframework.zip\n")
    }
}

tasks.register("publishLocalMavenAndXcframework") {
    group = "publishing"
    description = "Publishes to mavenLocal and creates local XCFramework zip+sha artifacts."
    dependsOn(
        "publishToMavenLocal",
        "zipLocalDebugXcframework",
        "writeLocalDebugXcframeworkSha256",
    )
}
