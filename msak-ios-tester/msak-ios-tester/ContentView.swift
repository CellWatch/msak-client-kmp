import SwiftUI
import MsakShared
import Foundation

struct ContentView: View {
    // MARK: - Inputs
    @State private var host: String = "127.0.0.1"
    @State private var useTLS: Bool = false // HTTPS/WSS toggle (ServerFactory currently uses http/ws)
    @State private var latencyDurationMs: String = "3000"
    @State private var tpStreams: String = "2"
    @State private var tpDurationMs: String = "5000"
    @State private var tpDelayMs: String = "0"
    @State private var netHttpReady: Bool = false
    private let USER_AGENT = "msak-ios-tester/0.2"
    @State private var currentServer: MsakShared.Server? = nil
    @State private var suppressLocateUpdateSideEffects: Bool = false
    private enum ServerSource { case local, located }
    @State private var serverSource: ServerSource = .local

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
        print(line)
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
                    HStack(spacing: 8) {
                        Text("Host")
                        TextField("127.0.0.1", text: $host)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .textFieldStyle(.roundedBorder)
                            .multilineTextAlignment(.trailing)
                            .focused($focusedField, equals: .host)
                            .submitLabel(.done)
                            .onChange(of: host) { _ in
                                // Ignore changes when a located server is active or when we're programmatically updating fields
                                if suppressLocateUpdateSideEffects || serverSource == .located { return }
                                // User is editing manually; switch to a local server config.
                                currentServer = nil
                                serverSource = .local
                                rebuildLocalServerFromFields()
                            }
                        // Two locate buttons: latency (left), throughput (right)
                        Button("📍L") { runLocateLatency() }
                            .buttonStyle(.bordered)
                            .accessibilityLabel("Locate latency")
                            .font(.system(size: 14))
                            .frame(minWidth: 28, minHeight: 28)
                            .help("Locate nearest public MSAK latency server and populate host/TLS")
                        Button("📍T") { runLocateThroughput() }
                            .buttonStyle(.bordered)
                            .accessibilityLabel("Locate throughput")
                            .font(.system(size: 14))
                            .frame(minWidth: 28, minHeight: 28)
                            .help("Locate nearest public MSAK throughput server and populate host/TLS")
                    }
                            Toggle("Use HTTPS/WSS", isOn: $useTLS)
                                .help("If enabled, endpoints will use https/wss for locally built servers.")
                                .onChange(of: useTLS) { _ in
                                    // Ignore programmatic updates during Locate and while a located server is active.
                                    if suppressLocateUpdateSideEffects || serverSource == .located { return }
                                    currentServer = nil
                                    serverSource = .local
                                    rebuildLocalServerFromFields()
                                }
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
        .onAppear {
            ensureNetHttpInitialized()
            if currentServer == nil && serverSource == .local { rebuildLocalServerFromFields() }
        }
    }

    // Parse "host[:port]" into (host, port?)
    private func splitHostPort(_ value: String) -> (String, Int?) {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return ("127.0.0.1", nil) }
        if let idx = trimmed.lastIndex(of: ":"),
           // ensure we only treat it as host:port if port looks numeric
           idx < trimmed.endIndex,
           let port = Int(trimmed[trimmed.index(after: idx)...]) {
            let h = String(trimmed[..<idx])
            return (h, port)
        }
        return (trimmed, nil)
    }

    // MARK: - Server builders (TLS-aware unified ServerFactory)
    private func rebuildLocalServerFromFields() {
        let (h, pOpt) = splitHostPort(host)
        let p = Int32(pOpt ?? (useTLS ? 443 : 8080))
        // Build a complete local Server (both latency & throughput endpoints) using the unified factory.
        // This is ONLY for local/dev. Located servers are kept verbatim in `currentServer`.
        let srv = ServerFactory.shared.buildServer(
            host: h,
            port: p,
            useTls: useTLS,
            includeLatency: true,
            includeThroughput: true,
            measurementId: "ios-demo",
            latencyPathPrefix: "latency/v1",
            throughputPathPrefix: "throughput/v1",
            latencyUdpPort: Int32(1053)
        )
        currentServer = srv
        serverSource = .local
        appendLog("Server built (local): host=\(h) port=\(p) tls=\(useTLS)")
    }

    // Single source of truth: use located server if present, otherwise build a local one
    private func activeServer() -> Server {
        if let s = currentServer {
            appendLog("Using existing server (\(serverSource == .located ? "located" : "local")) → \(s.machine)")
            return s
        }
        let (h, pOpt) = splitHostPort(host)
        let p = Int32(pOpt ?? (useTLS ? 443 : 8080))
        let s = ServerFactory.shared.buildServer(
            host: h,
            port: p,
            useTls: useTLS,
            includeLatency: true,
            includeThroughput: true,
            measurementId: "ios-demo",
            latencyPathPrefix: "latency/v1",
            throughputPathPrefix: "throughput/v1",
            latencyUdpPort: Int32(1053)
        )
        serverSource = .local
        currentServer = s
        appendLog("Server built (implicit local): host=\(h) port=\(p) tls=\(useTLS)")
        return s
    }

    // MARK: - Locator integration (KMP LocateManager)
    private func ensureNetHttpInitialized() {
        if netHttpReady { return }
        // Kotlin `object` NetHttp is bridged as a singleton with `shared`
        MsakShared.NetHttp.shared.initialize(config: MsakShared.NetHttpConfig(
            userAgent: USER_AGENT,
            requestTimeoutMs: 15_000,
            connectTimeoutMs: 10_000,
            verboseLogging: false
        ))
        netHttpReady = true
    }

    // Locate latency server and populate host/TLS fields
    private func runLocateLatency() {
        ensureNetHttpInitialized()
        appendLog("Locate (latency): starting")
        Task {
            do {
                let lm = MsakShared.LocateManager(
                    serverEnv: MsakShared.LocateManager.ServerEnv.prod,
                    locateBaseUrl: nil,
                    userAgent: USER_AGENT,
                    msakLocalServerHost: nil,
                    msakLocalServerSecure: false
                )
                let lServers = try await lm.locateLatencyServers(limitToSiteOf: nil)
                guard let chosen = lServers.first else {
                    appendLog("Locate (latency): no latency servers returned")
                    return
                }
                // Prefer https latency authorize, then http
                let urls = chosen.urls
                var latencyUrl: String? = nil
                if let u = urls["https:///latency/v1/authorize"] as? String { latencyUrl = u }
                if latencyUrl == nil, let u = urls["http:///latency/v1/authorize"] as? String { latencyUrl = u }
                guard let lurl = latencyUrl, let comps = URLComponents(string: lurl) else {
                    appendLog("Locate (latency): missing or invalid latency authorize URL in server response")
                    return
                }
                let h = comps.host ?? ""
                let scheme = (comps.scheme ?? "").lowercased()
                let defaultPort = (scheme == "https") ? 443 : 80
                let p = comps.port ?? defaultPort
                let tls = (scheme == "https")

                // Update UI fields
                await MainActor.run {
                    self.suppressLocateUpdateSideEffects = true
                    self.host = "\(h):\(p)"
                    self.useTLS = tls
                    self.currentServer = chosen
                    self.serverSource = .located
                    self.suppressLocateUpdateSideEffects = false
                    self.appendLog("Server set from Locate (latency) → host=\(h) port=\(p) tls=\(tls)")
                }
                appendLog("Locate (latency): selected \(chosen.machine) → host=\(h) port=\(p) tls=\(tls)")
                appendLog("You can now run Latency/Download/Upload against this server")
            } catch {
                appendLog("Locate (latency) error: \(error.localizedDescription)")
            }
        }
    }

    // Locate throughput server and populate host/TLS fields
    private func runLocateThroughput() {
        ensureNetHttpInitialized()
        appendLog("Locate (throughput): starting")
        Task {
            do {
                // Default env = PROD; pass userAgent for observability
                let lm = MsakShared.LocateManager(
                    serverEnv: MsakShared.LocateManager.ServerEnv.prod,
                    locateBaseUrl: nil,
                    userAgent: USER_AGENT,
                    msakLocalServerHost: nil,
                    msakLocalServerSecure: false
                )
                let tServers = try await lm.locateThroughputServers(limitToSiteOf: nil)
                guard let chosen = tServers.first else {
                    appendLog("Locate (throughput): no throughput servers returned")
                    return
                }
                // Prefer download URL; fall back to upload
                let urls = chosen.urls
                var wsUrl: String? = nil
                if let u = urls["ws:///throughput/v1/download"] as? String { wsUrl = u }
                if wsUrl == nil, let u = urls["ws:///throughput/v1/upload"] as? String { wsUrl = u }
                guard let ws = wsUrl, let comps = URLComponents(string: ws) else {
                    appendLog("Locate (throughput): missing or invalid throughput URL in server response")
                    return
                }
                let h = comps.host ?? ""
                let scheme = (comps.scheme ?? "").lowercased()
                let defaultPort = (scheme == "wss" || scheme == "https") ? 443 : 80
                let p = comps.port ?? defaultPort
                let tls = (scheme == "wss" || scheme == "https")

                // Update UI fields
                await MainActor.run {
                    // Prevent onChange handlers from discarding the located Server while we populate fields.
                    self.suppressLocateUpdateSideEffects = true
                    self.host = "\(h):\(p)"
                    self.useTLS = tls
                    self.currentServer = chosen
                    self.serverSource = .located
                    self.suppressLocateUpdateSideEffects = false
                    self.appendLog("Server set from Locate (throughput) → host=\(h) port=\(p) tls=\(tls)")
                }
                appendLog("Locate (throughput): selected \(chosen.machine) → host=\(h) port=\(p) tls=\(tls)")
                appendLog("You can now run Download/Upload/Latency against this server")
            } catch {
                appendLog("Locate (throughput) error: \(error.localizedDescription)")
            }
        }
    }

    // MARK: - Actions

    private func runLatency() {
        latencyStatus = "Running…"
        appendLog("Latency: starting")

        // Parse inputs with defaults
        let dur = Int64(latencyDurationMs.trimmingCharacters(in: .whitespacesAndNewlines)) ?? 3000

        // Build Server using ServerFactory (http). If you require HTTPS, update ServerFactory in shared code.
        // Kotlin 'object' maps to a Swift type with a `shared` singleton.
        let server = activeServer()
        if serverSource == .located {
            appendLog("Latency: using located server URLs (with access_token)")
        }
        appendLog("Latency: server=\(server.machine)")

        // Build config — do NOT force the UDP port here; shared code will choose based on environment/host.
        let cfg = LatencyConfig(
            server: server,
            measurementId: "ios-demo",
            duration: dur,
            userAgent: USER_AGENT
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
                let msg = (error as NSError).localizedDescription
                appendLog("Latency error: \(msg)")
                latencyStatus = "Error: \(msg)"
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
        let server = activeServer()
        if serverSource == .located {
            appendLog("Throughput \(direction.name): using located server URLs (with access_token)")
        }
        appendLog("Throughput \(direction.name): server=\(server.machine)")

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
                let msg = (error as NSError).localizedDescription
                appendLog("Throughput \(direction.name) error: \(msg)")
                if direction == .download {
                    throughputDownloadStatus = "Error: \(msg)"
                } else {
                    throughputUploadStatus = "Error: \(msg)"
                }
            }
        }
    }

}

#Preview {
    ContentView()
}
