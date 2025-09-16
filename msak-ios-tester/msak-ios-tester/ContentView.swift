//
//  ContentView.swift
//  msak-ios-tester
//
//  Created by Jeff on 9/2/25.
//

import SwiftUI
import MsakShared

struct ContentView: View {
    @State private var statusUDP: String = "UDP: idle"
    @State private var statusTCP: String = "TCP: idle"
    @State private var statusWS:  String = "WS: idle"
    @State private var statusLatency: String = "Latency: idle"
    @State private var statusLatencyFull: String = "LatencyFull: idle"
    @State private var statusLogger: String = "Logger: idle"
    @State private var statusTP: String = "Throughput: idle"
    
    // IMPORTANT: Avoid heavy or complex work inside `body`. Doing so can confuse the SwiftUI
    // ViewBuilder type-checker and trigger the vague error "Generic parameter 'V' could not be inferred".
    // Compute values *outside* the `body` builder and persist them in @State or small computed properties.
    // We compute `greeting` once (onAppear) and then read it in `body`.
    @State private var greeting: String = ""

    // Adjust these to point at your local MSAK server
    @State private var host: String = "127.0.0.1"
    @State private var udpPort: String = "1053"
    @State private var tcpPort: String = "8080"
    @State private var wsURL: String  = "ws://127.0.0.1:8080/throughput/v1/download?streams=1&duration=60&mid=localtest"

    // NOTE: Replacing large `Group {}` blocks with small subviews avoids
    // SwiftUI's "Generic parameter 'V' could not be inferred" errors.
    // Keep builders shallow and extract complexity into helpers.
    @ViewBuilder
    private var targetsPanel: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Targets").font(.headline)
            HStack {
                TextField("Host", text: $host)
                    .textFieldStyle(.roundedBorder)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .frame(minWidth: 180)
                TextField("UDP Port", text: $udpPort)
                    .textFieldStyle(.roundedBorder)
                    .keyboardType(.numbersAndPunctuation)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .frame(width: 90)
                TextField("TCP Port", text: $tcpPort)
                    .textFieldStyle(.roundedBorder)
                    .keyboardType(.numbersAndPunctuation)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .frame(width: 90)
            }
            HStack {
                TextField("WebSocket URL", text: $wsURL)
                    .textFieldStyle(.roundedBorder)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
            }
        }
    }
  
    @ViewBuilder
    private var quickTestsPanel: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Quick tests").font(.headline)
            HStack {
                Button("Logger hello") {
                    statusLogger = "Logger: running…"
                    MsakShared.QuickTests.shared.loggerHello { summary, err in
                        DispatchQueue.main.async {
                            if let e = err {
                                statusLogger = "Logger: error (\(e.localizedDescription))"
                            } else {
                                statusLogger = "Logger: \(summary ?? "unknown")"
                            }
                        }
                    }
                }
                Text(statusLogger).font(.footnote).foregroundStyle(.secondary)
            }
            HStack {
                Button("Latency probe (authorize+UDP)") {
                    statusLatency = "Latency: testing…"
                    let udpTrim = udpPort.trimmingCharacters(in: .whitespacesAndNewlines)
                    let httpTrim = tcpPort.trimmingCharacters(in: .whitespacesAndNewlines)
                    guard let udpP = Int32(udpTrim), udpP > 0, let httpP = Int32(httpTrim), httpP > 0 else {
                        statusLatency = "Latency: invalid port"
                        return
                    }
                    MsakShared.QuickTests.shared.latencyProbe(
                        host: host,
                        httpPort: httpP,
                        udpPort: udpP,
                        mid: "localtest",
                        userAgent: nil,
                        waitForReplyMs: 1000
                    ) { result, error in
                        DispatchQueue.main.async {
                            if let e = error {
                                statusLatency = "Latency: error (\(e.localizedDescription))"
                            } else {
                                let text = result ?? "unknown"
                                statusLatency = (text == "OK") ? "Latency: OK" : "Latency: \(text)"
                            }
                        }
                    }
                }
                Text(statusLatency).font(.footnote).foregroundStyle(.secondary)
            }
            HStack {
                Button("Latency full (LatencyTest)") {
                    statusLatencyFull = "LatencyFull: running…"
                    let udpTrim = udpPort.trimmingCharacters(in: .whitespacesAndNewlines)
                    let httpTrim = tcpPort.trimmingCharacters(in: .whitespacesAndNewlines)
                    guard let udpP = Int32(udpTrim), udpP > 0, let httpP = Int32(httpTrim), httpP > 0 else {
                        statusLatencyFull = "LatencyFull: invalid port"
                        return
                    }
                    MsakShared.QuickTests.shared.latencyFull(
                        host: host,
                        httpPort: httpP,
                        udpPort: udpP,
                        durationMs: 3000,
                        mid: "localtest",
                        userAgent: nil
                    ) { summary, err in
                        DispatchQueue.main.async {
                            if let e = err {
                                statusLatencyFull = "LatencyFull: error (\(e.localizedDescription))"
                            } else {
                                statusLatencyFull = "LatencyFull: \(summary ?? "unknown")"
                            }
                        }
                    }
                }
                Text(statusLatencyFull).font(.footnote).foregroundStyle(.secondary)
            }
            
            HStack {
                Button("Throughput smoke") {
                    statusTP = "Throughput: testing…"
                    MsakShared.QuickTests.shared.throughputSmokeTest(
                        host: host,
                        wsPort: Int32(tcpPort) ?? 8080,
                        directionStr: "download",
                        streams: 2,
                        durationMs: 5000,
                        delayMs: 0,
                        userAgent: "msak-ios-tester/0.1"
                    ) { summary, err in
                        DispatchQueue.main.async {
                            if let e = err {
                                if let kt = e as? KotlinThrowable {
                                    statusTP = "Throughput: error (\(kt.message ?? String(describing: kt)))"
                                } else {
                                    statusTP = "Throughput: error (\(String(describing: e)))"
                                }
                            } else {
                                statusTP = summary ?? "Throughput: unknown"
                            }
                        }
                    }
                }
                Text(statusTP).font(.footnote).foregroundStyle(.secondary)
            }
            
            HStack {
                Button("UDP open/close") {
                    statusUDP = "UDP: testing…"
                    let trimmed = udpPort.trimmingCharacters(in: .whitespacesAndNewlines)
                    guard let udpP = Int32(trimmed), udpP > 0 else {
                        statusUDP = "UDP: invalid port"
                        return
                    }
                    MsakShared.QuickTests.shared.udpOpenClose(host: host, port: udpP, holdMs: 1000) { ok, err in
                        DispatchQueue.main.async {
                            if let err = err {
                                statusUDP = "UDP: failed (\(err.localizedDescription))"
                            } else {
                                statusUDP = (ok == true) ? "UDP: OK" : "UDP: failed"
                            }
                        }
                    }
                }
                Text(statusUDP).font(.footnote).foregroundStyle(.secondary)
            }
            HStack {
                Button("TCP open/close") {
                    statusTCP = "TCP: testing…"
                    let trimmed = tcpPort.trimmingCharacters(in: .whitespacesAndNewlines)
                    guard let tcpP = Int32(trimmed), tcpP > 0 else {
                        statusTCP = "TCP: invalid port"
                        return
                    }
                    MsakShared.QuickTests.shared.tcpOpenClose(host: host, port: tcpP, holdMs: 1000) { ok, err in
                        DispatchQueue.main.async {
                            if let err = err {
                                statusTCP = "TCP: failed (\(err.localizedDescription))"
                            } else {
                                statusTCP = (ok == true) ? "TCP: OK" : "TCP: failed"
                            }
                        }
                    }
                }
                Text(statusTCP).font(.footnote).foregroundStyle(.secondary)
            }
            HStack {
                Button("WS open/close") {
                    statusWS = "WS: testing…"
                    MsakShared.QuickTests.shared.wsOpenClose(url: wsURL, holdMs: 500) { ok, err in
                        DispatchQueue.main.async {
                            if let err = err {
                                statusWS = "WS: failed (\(err.localizedDescription))"
                            } else {
                                statusWS = (ok == true) ? "WS: OK" : "WS: failed"
                            }
                        }
                    }
                }
                Text(statusWS).font(.footnote).foregroundStyle(.secondary)
            }
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack {
                Image(systemName: "globe")
                    .imageScale(.large)
                    .foregroundStyle(.tint)
                Text("Hello, world! This is dud \(greeting)")
            }

            targetsPanel

            quickTestsPanel

            Spacer()
        }
        .onAppear {
            // Compute once outside of ViewBuilder evaluation to avoid generic-parameter inference errors.
            if greeting.isEmpty {
                greeting = Greeting().greet()
            }
        }
        .padding()
    }
}

#Preview {
    ContentView()
}
