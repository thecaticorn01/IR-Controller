import SwiftUI
import IRCore

struct SettingsView: View {
    var body: some View {
        TabView {
            Tab("General", systemImage: "gearshape") {
                GeneralSettings()
            }
            Tab("Databases", systemImage: "externaldrive.connected.to.line.below") {
                DatabaseSettings()
            }
            Tab("IR Blaster", systemImage: "dot.radiowaves.right") {
                BlasterSettings()
            }
        }
        .frame(width: 580)
        .scenePadding()
    }
}

private struct GeneralSettings: View {
    @AppStorage(SettingsKey.showMenuBarExtra) private var showMenuBarExtra = true
    @AppStorage(SettingsKey.keyboardShortcuts) private var keyboardShortcuts = true
    @AppStorage(SettingsKey.extraRepeats) private var extraRepeats = 0

    var body: some View {
        Form {
            Section {
                Toggle("Show remote in the menu bar", isOn: $showMenuBarExtra)
                Toggle("Control remotes with the keyboard", isOn: $keyboardShortcuts)
            } footer: {
                Text("Arrow keys move, Return selects, Space plays/pauses, + and − change volume, M mutes, P toggles power and digits enter numbers.")
            }
            Section {
                Stepper(value: $extraRepeats, in: 0...10) {
                    LabeledContent("Extra repeats per press", value: "\(extraRepeats)")
                }
            } footer: {
                Text("Some devices need a code repeated before they respond. Increase this if buttons work only intermittently.")
            }
        }
        .formStyle(.grouped)
    }
}

private struct BlasterSettings: View {
    @Environment(BlasterManager.self) private var blaster
    @AppStorage(SettingsKey.learnTimeout) private var learnTimeout = 20.0
    @AppStorage(SettingsKey.learnFrequency) private var learnFrequency = 38_000
    @AppStorage(SettingsKey.preferDecodedProtocol) private var preferDecoded = true

    var body: some View {
        Form {
            Section("Connection") {
                DeviceDetailsView()
            }
            Section {
                Stepper(value: $learnTimeout, in: 5...60, step: 5) {
                    LabeledContent("Listen for up to", value: "\(Int(learnTimeout)) seconds")
                }
                Picker("Carrier frequency for learned codes", selection: $learnFrequency) {
                    Text("36 kHz (Philips RC5/RC6)").tag(36_000)
                    Text("38 kHz (most remotes)").tag(38_000)
                    Text("40 kHz (Sony)").tag(40_000)
                    Text("56 kHz (RCA)").tag(56_000)
                }
                Toggle("Convert recognised codes to clean protocol codes", isOn: $preferDecoded)
            } header: {
                Text("Learning")
            } footer: {
                Text("The blaster’s receiver records timing but not the carrier frequency. 38 kHz works for the vast majority of devices.")
            }
        }
        .formStyle(.grouped)
    }
}

private struct DatabaseSettings: View {
    @Environment(DatabaseStore.self) private var database
    @State private var token = KeychainHelper.read(account: KeychainHelper.gitHubTokenAccount) ?? ""
    @State private var showingAddSource = false

    var body: some View {
        Form {
            Section {
                ForEach(database.sources) { source in
                    HStack(alignment: .top) {
                        Toggle(isOn: Binding {
                            source.isEnabled
                        } set: {
                            database.setEnabled($0, sourceID: source.id)
                        }) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(source.name)
                                Text("\(source.owner)/\(source.repository)" + (source.subpath.isEmpty ? "" : "/\(source.subpath)"))
                                    .font(.caption.monospaced())
                                    .foregroundStyle(.secondary)
                                Text(status(for: source))
                                    .font(.caption)
                                    .foregroundStyle(.tertiary)
                            }
                        }
                        Spacer()
                        if !source.isBuiltIn {
                            Button("Remove", systemImage: "trash", role: .destructive) {
                                database.removeSource(id: source.id)
                            }
                            .labelStyle(.iconOnly)
                            .buttonStyle(.borderless)
                        }
                    }
                }
            } header: {
                Text("Sources")
            } footer: {
                HStack {
                    Button("Add GitHub Repository…", systemImage: "plus") { showingAddSource = true }
                    Spacer()
                    Button("Refresh All", systemImage: "arrow.clockwise") { Task { await database.refreshAll() } }
                        .disabled(database.isLoadingAny)
                    Button("Clear Cache") { database.clearCache() }
                }
                .padding(.top, 6)
            }

            Section {
                SecureField("Personal access token", text: $token)
                    .onSubmit(saveToken)
                HStack {
                    Spacer()
                    Button("Save Token", action: saveToken)
                }
            } header: {
                Text("GitHub")
            } footer: {
                Text("Optional. Indexes are fetched with GitHub’s public API, which allows 60 requests an hour without a token. A token with no scopes raises the limit. It is stored in your keychain.")
            }
        }
        .formStyle(.grouped)
        .sheet(isPresented: $showingAddSource) {
            AddSourceSheet()
        }
    }

    private func saveToken() {
        KeychainHelper.write(token.trimmingCharacters(in: .whitespacesAndNewlines), account: KeychainHelper.gitHubTokenAccount)
    }

    private func status(for source: DatabaseSource) -> String {
        switch database.loadStates[source.id] ?? .idle {
        case .idle: return source.isEnabled ? "Not downloaded yet" : "Disabled"
        case .loading: return "Downloading index…"
        case .loaded(let date): return "Updated \(date.formatted(.relative(presentation: .named)))"
        case .failed(let message): return "Failed: \(message)"
        }
    }
}

/// Adds any GitHub repository of Flipper `.ir` or IRDB-style `.csv` files.
private struct AddSourceSheet: View {
    @Environment(DatabaseStore.self) private var database
    @Environment(\.dismiss) private var dismiss
    @State private var urlText = ""
    @State private var name = ""
    @State private var branch = "main"
    @State private var subpath = ""
    @State private var format: DatabaseFormat = .flipper

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Add Database Source")
                .font(.title2.weight(.semibold))
            Text("Any public GitHub repository containing Flipper Zero .ir files (for example a Flipper-IRDB fork) or IRDB-style CSV files.")
                .font(.callout)
                .foregroundStyle(.secondary)
            Form {
                TextField("Repository", text: $urlText, prompt: Text("owner/repo or https://github.com/owner/repo"))
                TextField("Display Name", text: $name, prompt: Text(parsed?.repo ?? "My IR Files"))
                TextField("Branch", text: $branch)
                TextField("Folder", text: $subpath, prompt: Text("Optional, e.g. infrared"))
                Picker("File Format", selection: $format) {
                    Text("Flipper Zero (.ir)").tag(DatabaseFormat.flipper)
                    Text("IRDB CSV (.csv)").tag(DatabaseFormat.irdbCSV)
                }
            }
            .formStyle(.columns)
            HStack {
                Spacer()
                Button("Cancel", role: .cancel) { dismiss() }
                    .keyboardShortcut(.cancelAction)
                Button("Add Source") { add() }
                    .buttonStyle(.glassProminent)
                    .keyboardShortcut(.defaultAction)
                    .disabled(parsed == nil)
            }
        }
        .padding(24)
        .frame(width: 500)
    }

    private var parsed: (owner: String, repo: String)? {
        var text = urlText.trimmingCharacters(in: .whitespacesAndNewlines)
        for prefix in ["https://", "http://", "github.com/", "www.github.com/"] where text.lowercased().hasPrefix(prefix) {
            text = String(text.dropFirst(prefix.count))
        }
        if text.lowercased().hasPrefix("github.com/") { text = String(text.dropFirst("github.com/".count)) }
        let parts = text.split(separator: "/").map(String.init)
        guard parts.count >= 2, !parts[0].isEmpty, !parts[1].isEmpty else { return nil }
        return (parts[0], parts[1].replacingOccurrences(of: ".git", with: ""))
    }

    private func add() {
        guard let parsed else { return }
        let source = DatabaseSource(
            name: name.isEmpty ? parsed.repo : name,
            owner: parsed.owner,
            repository: parsed.repo,
            branch: branch.isEmpty ? "main" : branch,
            subpath: subpath,
            format: format
        )
        database.addSource(source)
        dismiss()
    }
}
