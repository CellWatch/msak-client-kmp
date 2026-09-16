# msak-client-kmp

An Android client for the [Measurement Lab](https://www.measurementlab.net/)'s [MSAK](https://github.com/m-lab/msak) measurement server. Uses the [Locate API](https://github.com/m-lab/locate/blob/main/USAGE.md) to select a measurement server and runs latency and throughput (download and upload speed) tests.

Used for CellWatch App

## Important caveat for throughput tests: Conscrypt required with TLS

Due to the lack of a suitable WebSocket client for Android that provides access to detailed socket information, the transport-layer byte counts are gathered via a bit of a hack. In our testing, the app must use the Conscrypt security provider to get correct transport-layer byte counts when accessing MSAK servers over TLS. Adding a dependency on `org.conscrypt:conscrypt-android` and then adding the following early in your app's initialization should do the trick:

```kotlin
Security.insertProviderAt(Conscrypt.newProvider(), 1)
```

(The MSAK throughput tests also collect application-layer byte counts, which work regardless with or without Conscrypt.)

## Setup

JitPack is **not** currently used in the active CellWatch integration workflow. Current development and integration use local Maven/XCFramework publication (see Development setup below).

If/when JitPack is used again, use the following setup:

You can get msak-android via [jitpack.io](https://jitpack.io)'s Maven repository. First add the repository to your `settings.gradle` file:

```
dependencyResolutionManagement {
    repositories {
        // ...
        maven { url 'https://jitpack.io' }
    }
}
```

Then add msak-android to your dependencies in the app-level `build.gradle`, replacing `<tag>` with the desired version tag:

```
dependencies {
    implementation 'com.github.CellWatch:msak-android:<tag>'
}
```

For more instructions, see <https://jitpack.io/#CellWatch/msak-android>.

## Usage

The Javadoc comments aim to document all public classes and methods. A few useful examples are included below.

### Basic usage

```kotlin
// pick a latency server
val latencyServer = LocateManager().locateLatencyServers().first()

// start the latency test and monitor its updates while it runs
val latencyTest = LatencyTest(latencyServer)
latencyTest.start()
latencyTest.updatesChan.consumeEach { update -> 
    Log.d(TAG, "got latency update $update")
}

// handle the latency result
Log.d(TAG, "latency result: ${latencyTest.result}")

// pick a throughput server
val throughputServer = LocateManager().locateThroughputServers().first()

// start the download test and monitor its updates while it runs
val downloadTest = ThroughputTest(throughputServer, ThroughputDirection.DOWNLOAD)
downloadTest.start()
downloadTest.updatesChan.consumeEach { update ->
    Log.d(TAG, "got download update: $update")
}

// handle the download result
Log.d(TAG, "download result: ${downloadTest.result}")

// start the upload test and monitor its updates while it runs
val uploadTest = ThroughputTest(throughputServer, ThroughputDirection.UPLOAD)
uploadTest.start()
uploadTest.updatesChan.consumeEach { update ->
    Log.d(TAG, "got upload update: $update")
}

// handle the upload result
Log.d(TAG, "upload result: ${uploadTest.result}")
```

### Using a local MSAK server for testing

You can use a local MSAK server by providing a few additional arguments when constructing the `LocateManager`:

```kotlin
val locate = LocateManager(
    serverEnv = ServerEnv.LOCAL,
    msakLocalServerHost = "localhost",
    msakLocalServerSecure = false,
)
```

In this case, the `LocateManager` will not actually call M-Lab's Locate API but will instead construct URLs to access the local MSAK server.

You may need to adjust the `msakLocalServerHost` depending on where your local MSAK server is running.

### Custom logger

By default, msak-android uses `android.util.Log` to log. You can call `setLogger` to use a custom logger object instead.

## Development setup

msak-android is an Android Studio project.

### Local distribution (current)

Local distribution supports:

1. Maven local cache publication for Android/KMP consumers.
2. Local XCFramework zip + checksum output for iOS/Xcode consumers, in **both
   Debug and Release**.

Run:

```bash
./gradlew :msak-shared:publishLocalMavenAndXcframework
```

Outputs:

1. Maven local cache (`~/.m2/repository`) with coordinate:
    - `edu.gatech.cc.cellwatch:msak-client-kmp:<new-version>`
2. Local XCFramework artifacts, under
   `msak-shared/build/local-dist/apple/msak-client-kmp/<new-version>/`:
    - `MsakShared-debug.xcframework.zip` + `MsakShared-debug.xcframework.sha256`
    - `MsakShared-release.xcframework.zip` + `MsakShared-release.xcframework.sha256`

To build just one configuration:

```bash
./gradlew :msak-shared:zipLocalReleaseXcframework :msak-shared:writeLocalReleaseXcframeworkSha256
```

Android/KMP consumer example:

```kotlin
// settings.gradle(.kts)
dependencyResolutionManagement {
    repositories {
        mavenLocal()
        google()
        mavenCentral()
    }
}
```

```kotlin
// build.gradle(.kts)
dependencies {
    implementation("edu.gatech.cc.cellwatch:msak-client-kmp:<new-version>")
}
```

#### Debug vs Release XCFrameworks

**Pick the zip that matches what you are shipping.** Unlike the published `.klib`
artifacts -- where the consumer links the framework itself and picks its own
configuration -- these zips are pre-linked, so the configuration is baked in:

| Zip | Use for |
|---|---|
| `MsakShared-release.xcframework.zip` | TestFlight, App Store, any release build |
| `MsakShared-debug.xcframework.zip` | Local development and debugging |

A debug Kotlin/Native binary is roughly twice the size (the `ios-arm64` slice is
15.4 MB debug vs 7.3 MB release), slower, and **does not behave identically on unhandled
exceptions** -- Debug tends to log and continue where Release aborts. Shipping the
debug zip to TestFlight means testing a configuration you are not going to ship.

The two cannot be merged into one `.xcframework`. Slices are keyed by platform +
arch + variant (device/simulator/catalyst), not by build configuration, so two
`ios-arm64` slices collide:

```
A library with the identifier 'ios-arm64' already exists.
```

If you want Xcode to select automatically, unzip both to separate directories and
set `FRAMEWORK_SEARCH_PATHS[config=Debug]` and `FRAMEWORK_SEARCH_PATHS[config=Release]`
in the consumer target. (msak-ios-tester does not need this: it builds from source
and its scheme pre-action regenerates `XCFrameworks/Current` for the active
configuration -- see the bootstrap section below.)

iOS/Xcode local consumption:

1. Unzip the zip you need to a stable local path in your consumer project.
2. Add `MsakShared.xcframework` to Xcode target dependencies/frameworks.
3. **Embed it.** `MsakShared` is a *dynamic* framework, so it must be in the app
   bundle's `Frameworks/` directory -- add it to an Embed Frameworks build phase
   with "Embed & Sign". Linking without embedding builds and runs fine in the
   simulator (dyld finds the framework next to the `.app` in the build products
   directory) and then fails at launch on a real device with
   `Library not loaded: @rpath/MsakShared.framework/MsakShared`.

### Running a local MSAK server for testing

The repo ships `scripts/ensure-msak-docker.sh`, which builds and runs a local
MSAK server fixture. It expects the MSAK server source (which contains the
`Dockerfile`) next to this repo:

```
MSAK_DIR=../msak      # i.e. ~/Projects/msak
HOST_HTTP_PORT=8080   # cleartext HTTP/WS
HOST_UDP_PORT=1053    # UDP latency
```

```bash
./scripts/ensure-msak-docker.sh
```

`scripts/run-android-docker-throughput-test.sh` wraps that and then runs the
Android instrumented throughput test against it.

**Without Docker.** The server is a Go binary, so a local run needs no Docker
daemon at all:

```bash
cd ~/Projects/msak && go build -o /tmp/msak-server ./cmd/msak-server
mkdir -p /tmp/msakdata
/tmp/msak-server -datadir /tmp/msakdata -ws_addr :8080 -latency_addr :1053
```

Both forms listen on all interfaces, so the same server serves the simulator and
a physical device.

#### Pointing a client at it

| Client | Host to enter |
|---|---|
| iOS Simulator | `127.0.0.1:8080` |
| Android emulator | `10.0.2.2:8080` |
| Physical iPhone / Android device | your Mac's LAN address, e.g. `192.168.1.119:8080` |

**A physical device cannot use `127.0.0.1`** — on the phone that is the phone
itself, so the measurement fails against a server that is not there. Use the
Mac's LAN IP (`ipconfig getifaddr en0`), keep the phone on the same network, and
allow incoming connections for the server binary in the macOS firewall.

The iOS tester's `Host` field accepts `host[:port]` and defaults to port 8080
(443 with TLS on). The UDP latency port is fixed at 1053.

#### Public (M-Lab) servers

The iOS tester does not have a local/public toggle. It has two **Locate**
buttons next to the `Host` field — one for latency, one for throughput — which
query the M-Lab Locate API, pick a nearby server, and switch the tester to that
server's URLs verbatim (including the `access_token`). Editing `Host` by hand
switches back to local/dev mode.

### iOS tester: XCFramework bootstrap

`msak-ios-tester` links a generated framework:

```
msak-shared/build/XCFrameworks/Current/MsakShared.xcframework
```

That path is Gradle output and is intentionally **not** tracked in git
(`msak-shared/.gitignore` ignores `/build`). Xcode resolves framework inputs
before it runs any target, so a missing `Current/MsakShared.xcframework` used to
fail the build outright:

```
error: There is no XCFramework found at '.../XCFrameworks/Current/MsakShared.xcframework'
```

even though the `msak-shared-xcframework` aggregate target exists to produce it.

**How it bootstraps now.** The tracked shared scheme
`msak-ios-tester.xcodeproj/xcshareddata/xcschemes/msak-ios-tester.xcscheme`
carries a build **pre-action** that runs
`msak-ios-tester/scripts/compile-kotlin-framework.sh` before Xcode resolves
those inputs. The pre-action declares the app target as its
`EnvironmentBuildable`, so it inherits that target's build settings — notably
`SRCROOT` and the active `CONFIGURATION`, which is what selects the Debug or
Release Gradle task.

Consequences worth knowing:

- A normal Debug or Release build of the `msak-ios-tester` scheme regenerates
  the framework automatically, including after `rm -rf msak-shared/build`. No
  preliminary aggregate-target build is needed.
- The app target still depends on `msak-shared-xcframework`, so the script is
  invoked twice per build. The script keeps a stamp at
  `XCFrameworks/Current/.bootstrap-stamp` and skips Gradle when the published
  framework is newer than every Kotlin source and Gradle build file, so the
  second call is a no-op.
- Xcode does not stream pre-action output into the build log. The script's
  output is written to `msak-shared/build/xcframework-bootstrap.log`; read that
  file first when a build fails with a missing or stale framework.
- Build outputs stay untracked. Only the scheme and the scripts are committed.

**Recovery path.** If the framework is missing, stale, or built for the wrong
configuration:

1. Build the `msak-ios-tester` scheme normally — this is expected to fix it.
2. If it does not, read `msak-shared/build/xcframework-bootstrap.log`.
3. Force a clean regeneration by hand:

   ```bash
   rm -rf msak-shared/build/XCFrameworks/Current
   CONFIGURATION=Debug ./msak-ios-tester/scripts/compile-kotlin-framework.sh
   ```

4. The aggregate scheme is still available as a manual escape hatch:

   ```bash
   xcodebuild -project msak-ios-tester/msak-ios-tester.xcodeproj \
     -scheme msak-shared-xcframework -configuration Debug build
   ```

**Automated verification.** `msak-ios-tester/scripts/verify-xcframework-bootstrap.sh`
deletes `msak-shared/build` outright, builds the normal tester scheme, and fails
unless the build succeeds *and* the framework was regenerated:

```bash
./msak-ios-tester/scripts/verify-xcframework-bootstrap.sh Debug
./msak-ios-tester/scripts/verify-xcframework-bootstrap.sh Release
```

### iOS error handling: failures are structured, not fatal

`runLatency()` and `runThroughput()` are the only structured error boundary.
Everything inside — `LatencyTest`, `ThroughputTest`, `ThroughputStream` and the
platform WebSocket adapters — runs in detached `SupervisorJob` scopes that
**record** failures instead of rethrowing them. On Kotlin/Native a rethrow from
a detached scope reaches the runtime's unhandled-exception hook, which is how a
failed measurement could make the host app exit with no visible error.

What callers can rely on:

- Network, authorization, timeout, WebSocket, parsing, socket and internal
  lifecycle failures all surface as a thrown `MsakException` from those two
  suspend functions, with the underlying error kept as `cause`.
- Cancellation stays cancellation: `CancellationException` propagates unchanged
  and is never re-reported as `MsakErrorCode.UNKNOWN`.
- `MsakErrorCode` is deliberately not extended, so an exhaustive Swift `switch`
  in a consumer keeps compiling.

On iOS specifically, `NSURLSessionWebSocketTask.sendMessage` completion errors
are no longer discarded. Data sends stay pipelined (up to 8 outstanding per
socket) because awaiting each one halved upload throughput in local testing
(16.8 -> 8.6 Gbit/s over loopback); a completion error is recorded, terminates
the socket, and is rethrown on the next send. Session callbacks also run on a
dedicated queue rather than `NSOperationQueue.mainQueue`, where a blocked main
thread could hide receive errors entirely.

### Updating version and deploying locally for CellWatch (current workflow)

Use this workflow when changing msak-client-kmp and testing it from CellWatch without remote publishing.

1. Update the MSAK library version in this repo:

    - Edit `msak-shared/build.gradle.kts`
    - Set `version = "<new-version>"` (for example, `0.2.3`)

2. Publish local artifacts from this repo:

```bash
./gradlew :msak-shared:publishLocalMavenAndXcframework
```

3. Verify local outputs match the new version:

    - Maven local coordinate exists at:
      - `~/.m2/repository/edu/gatech/cc/cellwatch/msak-client-kmp/<new-version>/`
    - XCFramework zips/checksums exist at
      `msak-shared/build/local-dist/apple/msak-client-kmp/<new-version>/`, one pair
      per configuration:
      - `MsakShared-debug.xcframework.zip` / `.sha256`
      - `MsakShared-release.xcframework.zip` / `.sha256`

4. Update CellWatch to consume the same local version:

    - Ensure CellWatch dependency resolution includes `mavenLocal()` before remote repos.
    - Update CellWatch MSAK dependency version to `<new-version>` (for example in `gradle/libs.versions.toml`).
    - On iOS, replace the embedded `MsakShared.xcframework` with the unzipped
      contents of the zip matching the configuration you are building -- use the
      **release** zip for anything going to TestFlight.
    - Re-sync and rebuild CellWatch.

5. If CellWatch still resolves an old artifact, refresh local caches:

```bash
./gradlew --stop
rm -rf ~/.gradle/caches
```

Then rebuild and verify the resolved version in dependency insight/build logs.

### Remote distribution status

Remote distribution for either Maven artifacts or Apple binary artifacts is not set up yet.
Current support is local-only distribution for development and integration testing.

### Java toolchain alignment (Android Studio + terminal + Xcode)

This project is sensitive to JDK mismatches across shell, Android Studio, and Xcode-driven Gradle runs.

Use JDK 17 and keep all three configuration points aligned to the same absolute JDK path:

1. Shell (SDKMAN, jEnv, manual `JAVA_HOME`, or similar)
2. Android Studio (`GRADLE_LOCAL_JAVA_HOME` via `.gradle/config.properties`)
3. Gradle property pin used by non-interactive Gradle/Xcode runs (`org.gradle.java.home`)

Example JDK path used below (use your own local JDK 17 path if different):

```text
/opt/homebrew/opt/sdkman-cli/libexec/candidates/java/17.0.8-tem
```

#### 1) Shell (tooling is up to you)

`.sdkmanrc` is tracked as one option for terminal sessions:

```bash
sdk env
```

If you do not use SDKMAN, set `JAVA_HOME` via your preferred tool/process.
If your shell does not auto-apply `.sdkmanrc`, configure SDKMAN auto-env or use a shell hook.

#### 2) Android Studio (`GRADLE_LOCAL_JAVA_HOME`)

Set Android Studio Gradle JDK to `GRADLE_LOCAL_JAVA_HOME`.

That value comes from:

```text
.gradle/config.properties
```

Create/update it (example):

```properties
java.home=/opt/homebrew/opt/sdkman-cli/libexec/candidates/java/17.0.8-tem
```

#### 3) Gradle property pin (Xcode/non-interactive Gradle)

This repo currently pins Gradle JVM in:

```text
gradle.properties
```

with (example):

```properties
org.gradle.java.home=/opt/homebrew/opt/sdkman-cli/libexec/candidates/java/17.0.8-tem
```

If your local path differs, update this value accordingly.

#### Verification

From terminal:

```bash
java -version
./gradlew -version
```

In Android Studio:

1. Check `Build, Execution, Deployment > Build Tools > Gradle > Gradle JDK`.
2. Ensure it is `GRADLE_LOCAL_JAVA_HOME`.
3. Re-sync project.

If behavior looks inconsistent, stop daemons and clear caches before retrying:

```bash
./gradlew --stop
rm -rf .gradle .gradle-local
rm -rf ~/.gradle/caches ~/.gradle/daemon ~/.gradle/native
```

## Credits

msak-android was developed using [Roberto D'Auria's early MSAK implementation](https://github.com/robertodauria/msak/) as a reference. It also provides a basic re-implementation of [M-Lab's memoryless package](https://github.com/m-lab/go/tree/main/memoryless) in Kotlin.

## License

```
Copyright 2024 Georgia Tech Research Corporation

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

See the `LICENSE` file for the full license text.
