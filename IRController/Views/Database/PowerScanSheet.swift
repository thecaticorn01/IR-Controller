import SwiftUI
import IRCore

/// Walks through the current search results sending each remote's power
/// button, so the user can find a working code without knowing the model.
struct PowerScanSheet: View {
    @Environment(DatabaseStore.self) private var database
    @Environment(LibraryStore.self) private var library
    @Environment(AppNavigation.self) private var navigation
    @Environment(BlasterManager.self) private var blaster
    @Environment(\.dismiss) private var dismiss

    @State private var candidates: [DatabaseEntry] = []
    @State private var index = 0
    @State private var status = ""
    @State private var autoAdvance = false
    @State private var interval = 3.0
    @State private var current: RemoteImport?
    @State private var runTask: Task<Void, Never>?

    private static let maxCandidates = 300

    var body: some View {
        VStack(spacing: 20) {
            HStack {
                Image(systemName: "power.circle.fill")
                    .font(.largeTitle)
                    .foregroundStyle(.red)
                VStack(alignment: .leading) {
                    Text("Power Scan")
                        .font(.title2.weight(.semibold))
                    Text("Point the blaster at your device. IR Controller sends the power code from each matching remote — stop when your device turns on or off.")
                        .font(.callout)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }

            if candidates.isEmpty {
                ContentUnavailableView("No Remotes to Scan", systemImage: "magnifyingglass",
                                       description: Text("Search or filter the database first, e.g. by brand and device type."))
            } else {
                VStack(spacing: 8) {
                    ProgressView(value: Double(index + 1), total: Double(candidates.count))
                    Text("Remote \(index + 1) of \(candidates.count)")
                        .font(.callout.monospacedDigit())
                        .foregroundStyle(.secondary)
                }

                VStack(spacing: 6) {
                    Text(candidates[index].title)
                        .font(.title3.weight(.semibold))
                        .multilineTextAlignment(.center)
                    Text(status)
                        .font(.callout)
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity)
                .padding(18)
                .glassEffect(.regular, in: .rect(cornerRadius: 20))

                GlassEffectContainer(spacing: 12) {
                    HStack(spacing: 12) {
                        Button("Previous", systemImage: "chevron.left") { step(-1) }
                            .disabled(index == 0)
                        Button("Send Again", systemImage: "power") { sendCurrent() }
                        Button("Next", systemImage: "chevron.right") { step(1) }
                            .disabled(index >= candidates.count - 1)
                    }
                    .buttonStyle(.glass)
                    .controlSize(.large)
                }

                HStack {
                    Toggle("Advance automatically every", isOn: $autoAdvance)
                    Stepper(value: $interval, in: 1...10, step: 0.5) {
                        Text("\(interval, specifier: "%.1f") s")
                            .monospacedDigit()
                    }
                    .disabled(!autoAdvance)
                }
                .font(.callout)
            }

            HStack {
                if !blaster.state.isReady {
                    Label("Connect your IR blaster to scan.", systemImage: "cable.connector.slash")
                        .font(.caption)
                        .foregroundStyle(.orange)
                }
                Spacer()
                Button("Done") { dismiss() }
                    .keyboardShortcut(.cancelAction)
                Button("It Worked — Add Remote", systemImage: "checkmark") { importCurrent() }
                    .buttonStyle(.glassProminent)
                    .tint(.green)
                    .disabled(current == nil)
                    .keyboardShortcut(.defaultAction)
            }
        }
        .padding(24)
        .frame(width: 560)
        .onAppear {
            candidates = Array(database.results.prefix(Self.maxCandidates))
            if !candidates.isEmpty { sendCurrent() }
        }
        .onDisappear { runTask?.cancel() }
        .onChange(of: autoAdvance) { scheduleAutoAdvance() }
    }

    private func step(_ delta: Int) {
        index = min(max(0, index + delta), max(0, candidates.count - 1))
        sendCurrent()
    }

    private func sendCurrent() {
        guard candidates.indices.contains(index) else { return }
        let entry = candidates[index]
        runTask?.cancel()
        current = nil
        status = "Downloading…"
        runTask = Task {
            guard let result = await database.loadPreview(entry) else {
                status = "Couldn’t download this remote."
                scheduleAutoAdvance()
                return
            }
            guard !Task.isCancelled else { return }
            current = result
            guard let power = result.remote.powerButton else {
                status = "This remote has no power button."
                scheduleAutoAdvance()
                return
            }
            status = "Sending “\(power.displayName)”…"
            do {
                try await blaster.send(power.signal, name: power.name)
                guard !Task.isCancelled else { return }
                status = "Sent “\(power.displayName)”. Did your device respond?"
            } catch {
                status = error.localizedDescription
            }
            scheduleAutoAdvance()
        }
    }

    private func scheduleAutoAdvance() {
        guard autoAdvance, index < candidates.count - 1 else { return }
        let delay = interval
        let expectedIndex = index
        Task {
            try? await Task.sleep(for: .seconds(delay))
            guard autoAdvance, index == expectedIndex else { return }
            step(1)
        }
    }

    private func importCurrent() {
        guard let current else { return }
        autoAdvance = false
        runTask?.cancel()
        let added = library.add(current.remote)
        navigation.open(added)
        dismiss()
    }
}
