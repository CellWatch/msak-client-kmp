import SwiftUI
import MsakShared

struct ContentView: View {
    // MARK: - Inputs
    @State private var host: String = "127.0.0.1"
    @State private var useTLS: Bool = false // HTTPS/WSS toggle (ServerFactory currently uses http/ws)
    @State private var latencyDurationMs: String = "3000"
    @State private var tpStreams: String = "2"
    @State private var tpDurationMs: String = "5000"
    @State private var tpDelayMs: String = "0"

    // MARK: - Focus management
    @FocusState private var focusedField: Field?
    private enum Field: Hashable {
        case host, latencyDuration, tpStreams, tpDuration, tpDelay
    }

    // MARK: - Outputs
    @State private var latencyStatus: String = "Idle"
    @State private var throughputDownloadStatus: String = "Idle"
    @State private var throughputUploadStatus: String = "Idle"
    @State private var logLines: [String] = []

    private func appendLog(_ line: String) {
        DispatchQueue.main.async {
            logLines.append(line)
            if logLines.count > 200 {
                logLines.removeFirst(logLines.count - 200)
            }
        }
    }

    // MARK: - UI
    var body: some View {
        NavigationStack {
            VStack(spacing: 12) {
                // ---- Top controls: non-scrolling ----
                VStack(spacing: 8) {
                    // Server
                    GroupBox("Server") {
                        VStack(alignment: .leading, spacing: 8) {
                            HStack {
                                Text("Host")
                                TextField("127.0.0.1", text: $host)
                                    .textInputAutocapitalization(.never)
                                    .autocorrectionDisabled()
                                    .textFieldStyle(.roundedBorder)
                                    .multilineTextAlignment(.trailing)
                                    .focused($focusedField, equals: .host)
                                    .submitLabel(.done)
                            }
                            Toggle("Use HTTPS/WSS", isOn: $useTLS)
                                .help("If enabled, endpoints should be https/wss. Current ServerFactory() helpers construct http/ws; update ServerFactory if you need TLS here.")
                        }
                    }

                    // Latency settings
                    GroupBox("Latency settings") {
                        HStack {
                            Text("Duration (ms)")
                            TextField("3000", text: $latencyDurationMs)
                                .keyboardType(.numbersAndPunctuation)
                                .textFieldStyle(.roundedBorder)
                                .multilineTextAlignment(.trailing)
                                .focused($focusedField, equals: .latencyDuration)
                                .submitLabel(.done)
                        }
                    }

                    // Throughput settings
                    GroupBox("Throughput settings") {
                        VStack(spacing: 8) {
                            HStack {
                                Text("Streams")
                                TextField("2", text: $tpStreams)
                                    .keyboardType(.numbersAndPunctuation)
                                    .textFieldStyle(.roundedBorder)
                                    .multilineTextAlignment(.trailing)
                                    .focused($focusedField, equals: .tpStreams)
                                    .submitLabel(.done)
                            }
                            HStack {
                                Text("Duration (ms)")
                                TextField("5000", text: $tpDurationMs)
                                    .keyboardType(.numbersAndPunctuation)
                                    .textFieldStyle(.roundedBorder)
                                    .multilineTextAlignment(.trailing)
                                    .focused($focusedField, equals: .tpDuration)
                                    .submitLabel(.done)
                            }
                            HStack {
                                Text("Start delay (ms)")
                                TextField("0", text: $tpDelayMs)
                                    .keyboardType(.numbersAndPunctuation)
                                    .textFieldStyle(.roundedBorder)
                                    .multilineTextAlignment(.trailing)
                                    .focused($focusedField, equals: .tpDelay)
                                    .submitLabel(.done)
                            }
                        }
                    }

                    // Actions (no output labels here; results go to the log)
                    HStack {
                        Button("Test Latency") { runLatency() }
                        Spacer()
                        Button("Test Download") { runThroughput(direction: .download) }
                        Spacer()
                        Button("Test Upload") { runThroughput(direction: .upload) }
                    }
                }
                .padding(.horizontal)

                // ---- Bottom log: fixed height, independent scroll ----
                GroupBox("Log") {
                    ScrollView {
                        LazyVStack(alignment: .leading, spacing: 4) {
                            ForEach(Array(logLines.enumerated()), id: \.offset) { _, line in
                                Text(line)
                                    .font(.system(size: 12, weight: .regular, design: .monospaced))
                                    .frame(maxWidth: .infinity, alignment: .leading)
                            }
                        }
                        .padding(.vertical, 4)
                    }
                    .allowsHitTesting(focusedField == nil) // do not steal touches while editing
                }
                .frame(maxWidth: .infinity)
                .frame(height: 240) // fixed height to avoid layout thrash with keyboard
                .padding([.horizontal, .bottom])
            }
            .navigationTitle("MSAK Demo")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItemGroup(placement: .keyboard) {
                    Spacer()
                    Button("Done") { focusedField = nil }
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        }
        .ignoresSafeArea(.keyboard) // let keyboard appear over content instead of resizing it
    }

    // MARK: - Server builders (prep for TLS-aware ServerFactory)
    private func buildLatencyServer() -> Server {
        // TODO: when ServerFactory.forLatency(host:httpPort:useTls:) is available, pass useTLS here.
        return ServerFactory.shared.forLatency(host: host, httpPort: 8080)
    }
    private func buildThroughputServer() -> Server {
        // TODO: when ServerFactory.forThroughput(host:wsPort:useTls:) is available, pass useTLS here.
        return ServerFactory.shared.forThroughput(host: host, wsPort: 8080)
    }

    // MARK: - Actions

    private func runLatency() {
        latencyStatus = "Running…"
        appendLog("Latency: starting")

        // Parse inputs with defaults
        let dur = Int64(latencyDurationMs.trimmingCharacters(in: .whitespacesAndNewlines)) ?? 3000

        // Build Server using ServerFactory (http). If you require HTTPS, update ServerFactory in shared code.
        // Kotlin 'object' maps to a Swift type with a `shared` singleton.
        let server = buildLatencyServer()

        // Build config
        //MsakShared.QuickTests.shared.latencyProbe
          
        let cfg = LatencyConfig(
            server: server,
            measurementId: "ios-demo",
            udpPort: 1053,
            duration: dur,
            userAgent: "msak-ios-tester/0.2"
        )

        Task {
            do {
                // NOTE: Top-level Kotlin functions from LatencyRunner.kt are usually surfaced under `LatencyRunnerKt`.
                // If your generated symbol differs, Xcode will suggest the correct name on build.
                let summary = try await LatencyRunnerKt.runLatency(config: cfg)
                let text = summary.asText()
                appendLog("Latency OK: \(text)")
                latencyStatus = text
            } catch {
                appendLog("Latency error: \(error.localizedDescription)")
                latencyStatus = "Error"
            }
        }
    }

    private func runThroughput(direction: ThroughputDirection) {
        if direction == .download {
            throughputDownloadStatus = "Running download…"
        } else {
            throughputUploadStatus = "Running upload…"
        }
        appendLog("Throughput \(direction.name): starting")

        // Parse inputs with defaults
        let streams = Int32(tpStreams.trimmingCharacters(in: .whitespacesAndNewlines)) ?? 2
        let dur = Int64(tpDurationMs.trimmingCharacters(in: .whitespacesAndNewlines)) ?? 5000
        let delay = Int64(tpDelayMs.trimmingCharacters(in: .whitespacesAndNewlines)) ?? 0

        // Build Server using ServerFactory (ws). If you require WSS, update ServerFactory in shared code.
        let server = buildThroughputServer()

        // Build config
        let cfg = ThroughputConfig(
            server: server,
            direction: direction,
            streams: streams,
            durationMs: dur,
            delayMs: delay,
            userAgent: "msak-ios-tester/0.2",
            measurementId: "ios-demo"
        )

        Task {
            do {
                // Top-level Kotlin function from ThroughputRunner.kt usually appears under `ThroughputRunnerKt`.
                let summary = try await ThroughputRunnerKt.runThroughput(config: cfg)
                let text = summary.asText()
                appendLog("Throughput \(direction.name) OK: \(text)")
                if direction == .download {
                    throughputDownloadStatus = text
                } else {
                    throughputUploadStatus = text
                }
            } catch {
                appendLog("Throughput \(direction.name) error: \(error.localizedDescription)")
                if direction == .download {
                    throughputDownloadStatus = "Error"
                } else {
                    throughputUploadStatus = "Error"
                }
            }
        }
    }
}

#Preview {
    ContentView()
}
