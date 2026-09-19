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
version = "0.6.0"

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

/*
 * Debug and Release XCFrameworks ship as SEPARATE zips on purpose.
 *
 * A single .xcframework cannot carry both: slices are keyed by platform + arch +
 * variant (device/simulator/catalyst), not by build configuration, so two
 * ios-arm64 slices collide --
 *   "A library with the identifier 'ios-arm64' already exists."
 *
 * Unlike the published .klib artifacts -- where the consumer links the framework
 * itself and chooses its own configuration -- these zips are pre-linked, so the
 * configuration is baked in. Anything shipping to TestFlight wants the release
 * zip: a debug Kotlin/Native binary is larger, slower, and does not behave
 * identically on unhandled exceptions.
 */
val xcframeworkConfigurations = listOf("debug" to "Debug", "release" to "Release")

val localXcframeworkTasks = xcframeworkConfigurations.flatMap { (lower, capitalized) ->
    val zipTask = tasks.register<Zip>("zipLocal${capitalized}Xcframework") {
        group = "publishing"
        description = "Zips the $lower MsakShared.xcframework for local distribution."
        dependsOn("assembleMsakShared${capitalized}XCFramework")
        from(layout.buildDirectory.dir("XCFrameworks/$lower/MsakShared.xcframework")) {
            into("MsakShared.xcframework")
        }
        archiveFileName.set("MsakShared-$lower.xcframework.zip")
        destinationDirectory.set(localAppleDistDir)
    }

    val shaTask = tasks.register("writeLocal${capitalized}XcframeworkSha256") {
        group = "publishing"
        description = "Writes the SHA-256 checksum for the $lower XCFramework zip."
        val zipFile = zipTask.flatMap { it.archiveFile }
        val shaFile = localAppleDistDir.map { it.file("MsakShared-$lower.xcframework.sha256") }
        inputs.file(zipFile)
        outputs.file(shaFile)
        doLast {
            val zip = zipFile.get().asFile
            val digest = MessageDigest.getInstance("SHA-256")
            zip.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val out = shaFile.get().asFile
            out.parentFile.mkdirs()
            out.writeText("$hash  ${zip.name}\n")
        }
    }

    listOf(zipTask, shaTask)
}

tasks.register("publishLocalMavenAndXcframework") {
    group = "publishing"
    description =
        "Publishes to mavenLocal and creates local XCFramework zip+sha artifacts " +
        "for both Debug and Release."
    dependsOn("publishToMavenLocal")
    dependsOn(localXcframeworkTasks)
}
