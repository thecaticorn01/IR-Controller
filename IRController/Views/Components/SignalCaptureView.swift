import SwiftUI
import IRCore

/// Drives the blaster's learning mode and shows the captured waveform.
/// The captured (optionally protocol-decoded) signal is written to `signal`.
struct SignalCaptureView: View {
    @Binding var signal: IRSignal?
    /// Name shown in the prompt ("Press Power on your remote").
    var buttonName: String
    /// Start listening as soon as the view appears.
    var autoStart = false
    /// Changing this restarts capture (used by the learning wizard).
    var sessionID: AnyHashable = 0

    @Environment(BlasterManager.self) private var blaster
    @AppStorage(SettingsKey.learnTimeout) private var learnTimeout = 20.0
    @AppStorage(SettingsKey.learnFrequency) private var learnFrequency = 38_000
    @AppStorage(SettingsKey.preferDecodedProtocol) private var preferDecoded = true

    private enum Phase: Equatable {
        case idle
        case listening(Date)
        case captured(RawSignal)
        case failed(String)
    }

    @State private var phase: Phase = .idle
    @State private var captureTask: Task<Void, Never>?

    var body: some View {
        VStack(spacing: 16) {
            switch phase {
            case .idle:
                idleView
            case .listening(let started):
                listeningView(started: started)
            case .captured(let raw):
                capturedView(raw)
            case .failed(let message):
                failedView(message)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(20)
        .glassEffect(.regular.tint(tint.opacity(0.12)), in: .rect(cornerRadius: 24))
        .animation(.smooth, value: phase)
        .task(id: sessionID) {
            // A new session (e.g. the next button in the wizard) starts fresh.
            captureTask?.cancel()
            phase = .idle
            if autoStart && signal == nil { start() }
        }
        .onDisappear { stop() }
    }

    private var tint: Color {
        switch phase {
        case .idle: .accentColor
        case .listening: .orange
        case .captured: .green
        case .failed: .red
        }
    }

    private var idleView: some View {
        VStack(spacing: 12) {
            Image(systemName: "dot.radiowaves.left.and.right")
                .font(.system(size: 40))
                .foregroundStyle(.tint)
            Text("Learn “\(buttonName)” from your remote")
                .font(.headline)
            Text("Hold the remote about 1 cm from the end of the IR blaster, then click Start Listening and press the button.")
                .font(.callout)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            Button("Start Listening", systemImage: "ear") { start() }
                .buttonStyle(.glassProminent)
                .controlSize(.large)
                .disabled(!blaster.state.isReady)
            if !blaster.state.isReady {
                Label("Connect your IR blaster to learn codes.", systemImage: "cable.connector.slash")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
    }

    private func listeningView(started: Date) -> some View {
        VStack(spacing: 12) {
            Image(systemName: "dot.radiowaves.left.and.right")
                .font(.system(size: 44))
                .foregroundStyle(.orange)
                .symbolEffect(.variableColor.iterative.reversing, options: .repeating)
            Text("Press “\(buttonName)” on your remote")
                .font(.title3.weight(.semibold))
            TimelineView(.periodic(from: started, by: 1)) { context in
                let remaining = max(0, Int(learnTimeout - context.date.timeIntervalSince(started)))
                Text("Listening… \(remaining)s")
                    .font(.callout.monospacedDigit())
                    .foregroundStyle(.secondary)
            }
            Button("Cancel", role: .cancel) { stop() }
                .buttonStyle(.glass)
        }
    }

    private func capturedView(_ raw: RawSignal) -> some View {
        let decoded = IRProtocolDecoder.decode(raw)
        return VStack(alignment: .leading, spacing: 12) {
            HStack {
                Label("Captured", systemImage: "checkmark.circle.fill")
                    .font(.headline)
                    .foregroundStyle(.green)
                Spacer()
                if let decoded {
                    Text("\(decoded.protocolName) · 0x\(String(decoded.address, radix: 16, uppercase: true)) / 0x\(String(decoded.command, radix: 16, uppercase: true))")
                        .font(.callout.monospaced())
                        .padding(.horizontal, 10)
                        .padding(.vertical, 4)
                        .glassEffect(.regular, in: .capsule)
                }
            }
            WaveformView(signal: raw, tint: .green)
                .frame(height: 64)
            if let decoded {
                Toggle("Store as a clean \(decoded.protocolName) code instead of the raw recording", isOn: $preferDecoded)
                    .font(.callout)
                    .onChange(of: preferDecoded) { publish(raw) }
            }
            HStack {
                Button("Test", systemImage: "dot.radiowaves.right") {
                    if let signal {
                        AppNavigation.shared.send(IRButton(name: buttonName, signal: signal))
                    }
                }
                .buttonStyle(.glass)
                Spacer()
                Button("Learn Again", systemImage: "arrow.counterclockwise") { start() }
                    .buttonStyle(.glass)
            }
        }
    }

    private func failedView(_ message: String) -> some View {
        VStack(spacing: 12) {
            Image(systemName: "exclamationmark.triangle")
                .font(.system(size: 36))
                .foregroundStyle(.red)
            Text(message)
                .multilineTextAlignment(.center)
                .font(.callout)
            Button("Try Again", systemImage: "arrow.counterclockwise") { start() }
                .buttonStyle(.glassProminent)
        }
    }

    private func publish(_ raw: RawSignal) {
        if preferDecoded, let decoded = IRProtocolDecoder.decode(raw) {
            signal = .parsed(decoded)
        } else {
            signal = .raw(raw)
        }
    }

    private func start() {
        captureTask?.cancel()
        signal = nil
        phase = .listening(Date())
        let timeout = learnTimeout
        let frequency = learnFrequency
        captureTask = Task {
            do {
                let raw = try await blaster.learn(timeout: timeout, frequency: frequency)
                guard !Task.isCancelled else { return }
                phase = .captured(raw)
                publish(raw)
            } catch ElkSmartError.learnCancelled {
                if !Task.isCancelled { phase = .idle }
            } catch {
                guard !Task.isCancelled else { return }
                phase = .failed(error.localizedDescription)
            }
        }
    }

    private func stop() {
        if case .listening = phase {
            blaster.cancelLearning()
            phase = .idle
        }
        captureTask?.cancel()
        captureTask = nil
    }
}
