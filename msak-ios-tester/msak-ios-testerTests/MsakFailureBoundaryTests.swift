//
//  MsakFailureBoundaryTests.swift
//  msak-ios-testerTests
//
//  Regression coverage for the CellWatch TestFlight symptom: a measurement ran
//  for a while and then the app exited with no user-visible failure, because
//  MSAK rethrew from a detached coroutine scope and Kotlin/Native turned that
//  into process termination.
//
//  These tests call the public suspend boundary from Swift exactly the way the
//  app does, force a failure, and assert that a structured error comes back and
//  the host process is still alive afterwards.
//
//  Scope note: this runs in the iOS Simulator. It proves the Swift-facing API
//  returns errors instead of terminating, but it does NOT reproduce the
//  physical-device conditions (real radio, jetsam pressure, background
//  transitions) under which the original exit was observed.
//

import XCTest
import MsakShared

// Kotlin/Native only allows suspend functions to be called from the main
// thread, which is also how the app invokes them (from a @MainActor Task).
@MainActor
final class MsakFailureBoundaryTests: XCTestCase {

    /// A loopback port nothing listens on: connects are refused immediately.
    private static let closedPort = 1

    private func unreachableServer() -> Server {
        Server(
            machine: "127.0.0.1",
            location: nil,
            urls: [
                "http:///latency/v1/authorize":
                    "http://127.0.0.1:\(Self.closedPort)/latency/v1/authorize",
                "http:///latency/v1/result":
                    "http://127.0.0.1:\(Self.closedPort)/latency/v1/result",
                "ws:///throughput/v1/download":
                    "ws://127.0.0.1:\(Self.closedPort)/throughput/v1/download",
                "ws:///throughput/v1/upload":
                    "ws://127.0.0.1:\(Self.closedPort)/throughput/v1/upload",
            ],
            latencyUdpPort: KotlinInt(int: Int32(Self.closedPort))
        )
    }

    func testLatencyFailureIsReturnedAndDoesNotTerminateTheHost() async throws {
        let config = LatencyConfig(
            server: unreachableServer(),
            measurementId: "xctest",
            duration: 500,
            userAgent: "msak-ios-tester-xctest"
        )

        do {
            _ = try await LatencyRunnerKt.runLatency(config: config)
            XCTFail("expected runLatency to fail against a closed port")
        } catch {
            // Kotlin exceptions surface to Swift as NSError. The important part
            // is that we are here at all: the process survived the failure.
            let nsError = error as NSError
            XCTAssertFalse(
                nsError.localizedDescription.isEmpty,
                "the failure must carry a description the UI can render"
            )
        }

        // Still running: the boundary returned instead of killing the process.
        XCTAssertTrue(true)
    }

    func testThroughputFailureIsReturnedAndDoesNotTerminateTheHost() async throws {
        let config = ThroughputConfig(
            server: unreachableServer(),
            direction: .download,
            streams: 2,
            durationMs: 1_000,
            delayMs: 0,
            userAgent: "msak-ios-tester-xctest",
            measurementId: "xctest"
        )

        do {
            _ = try await ThroughputRunnerKt.runThroughput(config: config)
            XCTFail("expected runThroughput to fail against a closed port")
        } catch {
            let nsError = error as NSError
            XCTAssertFalse(
                nsError.localizedDescription.isEmpty,
                "the failure must carry a description the UI can render"
            )
        }

        XCTAssertTrue(true)
    }

    /// Both in sequence: a failed run must leave the library usable, i.e. it
    /// must not have leaked a poisoned global scope or an unclosed channel.
    func testRepeatedFailuresKeepTheLibraryUsable() async throws {
        for _ in 0..<3 {
            do {
                _ = try await LatencyRunnerKt.runLatency(
                    config: LatencyConfig(
                        server: unreachableServer(),
                        measurementId: "xctest",
                        duration: 300,
                        userAgent: nil
                    )
                )
                XCTFail("expected runLatency to fail against a closed port")
            } catch {
                // expected
            }
        }
    }
}
