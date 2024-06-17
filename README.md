# msak-android

An Android client for the [Measurement Lab](https://www.measurementlab.net/)'s [MSAK](https://github.com/m-lab/msak) measurement server. Uses the [Locate API](https://github.com/m-lab/locate/blob/main/USAGE.md) to select a measurement server and runs latency and throughput (download and upload speed) tests.

## Important caveat for throughput tests: Conscrypt required with TLS

Due to the lack of a suitable WebSocket client for Android that provides access to detailed socket information, the transport-layer byte counts are gathered via a bit of a hack. In our testing, the app must use the Conscrypt security provider to get correct transport-layer byte counts when accessing MSAK servers over TLS. Adding a dependency on `org.conscrypt:conscrypt-android` and then adding the following early in your app's initialization should do the trick:

```kotlin
Security.insertProviderAt(Conscrypt.newProvider(), 1)
```

(The MSAK throughput tests also collect application-layer byte counts, which work regardless with or without Conscrypt.)

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
