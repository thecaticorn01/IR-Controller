import Foundation
import Observation
import UniformTypeIdentifiers
import IRCore

extension UTType {
    /// Flipper Zero infrared signal file.
    static let flipperIR = UTType(filenameExtension: "ir", conformingTo: .plainText) ?? .plainText
}

/// The user's saved remotes, persisted as JSON in Application Support.
@MainActor @Observable
final class LibraryStore {
    static let shared = LibraryStore()

    private(set) var remotes: [IRRemote] = []
    private(set) var loadError: String?

    private let fileURL: URL
    private var saveTask: Task<Void, Never>?

    init(fileURL: URL = URL.applicationSupportDirectory.appending(path: "MacIR/library.json")) {
        self.fileURL = fileURL
        migrateFromSandboxContainer()
        load()
    }

    /// MacIR 1.0.0–1.0.1 were sandboxed and kept the library inside their container.
    /// Bring it over the first time an unsandboxed build runs.
    private func migrateFromSandboxContainer() {
        let fileManager = FileManager.default
        guard !fileManager.fileExists(atPath: fileURL.path) else { return }
        let bundleID = Bundle.main.bundleIdentifier ?? "com.thecaticorn01.MacIR"
        let legacy = fileManager.homeDirectoryForCurrentUser
            .appending(path: "Library/Containers/\(bundleID)/Data/Library/Application Support/MacIR/library.json")
        // When the app itself runs sandboxed, its own Application Support is that path already.
        guard legacy.standardizedFileURL != fileURL.standardizedFileURL,
              fileManager.fileExists(atPath: legacy.path) else { return }
        do {
            try fileManager.createDirectory(at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
            try fileManager.copyItem(at: legacy, to: fileURL)
        } catch {
            loadError = "Couldn’t move your remotes from the previous version: \(error.localizedDescription)"
        }
    }

    var favorites: [IRRemote] { remotes.filter(\.isFavorite) }

    func remote(id: IRRemote.ID) -> IRRemote? {
        remotes.first { $0.id == id }
    }

    /// Finds a remote by name (case-insensitive), used by Shortcuts.
    func remote(named name: String) -> IRRemote? {
        let key = name.searchKey
        return remotes.first { $0.name.searchKey == key }
    }

    // MARK: Persistence

    private func load() {
        guard FileManager.default.fileExists(atPath: fileURL.path) else { return }
        do {
            remotes = try LibraryArchive.load(from: fileURL).remotes
        } catch {
            loadError = error.localizedDescription
            // Keep a copy of the unreadable file instead of overwriting it.
            let backup = fileURL.deletingPathExtension().appendingPathExtension("corrupt-\(Int(Date().timeIntervalSince1970)).json")
            try? FileManager.default.copyItem(at: fileURL, to: backup)
        }
    }

    private func scheduleSave() {
        saveTask?.cancel()
        let archive = LibraryArchive(remotes: remotes)
        let url = fileURL
        saveTask = Task.detached(priority: .utility) {
            try? await Task.sleep(for: .milliseconds(300))
            guard !Task.isCancelled else { return }
            try? archive.save(to: url)
        }
    }

    // MARK: Remotes

    @discardableResult
    func add(_ remote: IRRemote) -> IRRemote {
        var remote = remote
        remote.name = uniqueName(remote.name)
        remotes.append(remote)
        scheduleSave()
        return remote
    }

    func update(_ remote: IRRemote) {
        guard let index = remotes.firstIndex(where: { $0.id == remote.id }) else { return }
        var remote = remote
        remote.modifiedAt = Date()
        remotes[index] = remote
        scheduleSave()
    }

    /// Applies an in-place change to a remote.
    func modify(_ id: IRRemote.ID, _ change: (inout IRRemote) -> Void) {
        guard let index = remotes.firstIndex(where: { $0.id == id }) else { return }
        change(&remotes[index])
        remotes[index].modifiedAt = Date()
        scheduleSave()
    }

    func delete(_ id: IRRemote.ID) {
        remotes.removeAll { $0.id == id }
        scheduleSave()
    }

    @discardableResult
    func duplicate(_ id: IRRemote.ID) -> IRRemote? {
        guard let original = remote(id: id) else { return nil }
        var copy = original
        copy.id = UUID()
        copy.name = original.name + " Copy"
        copy.isFavorite = false
        copy.buttons = original.buttons.map { IRButton(name: $0.name, signal: $0.signal) }
        copy.createdAt = Date()
        return add(copy)
    }

    func toggleFavorite(_ id: IRRemote.ID) {
        modify(id) { $0.isFavorite.toggle() }
    }

    func move(fromOffsets source: IndexSet, toOffset destination: Int) {
        remotes.move(fromOffsets: source, toOffset: destination)
        scheduleSave()
    }

    // MARK: Buttons

    func addButton(_ button: IRButton, to remoteID: IRRemote.ID) {
        modify(remoteID) { $0.buttons.append(button) }
    }

    func updateButton(_ button: IRButton, in remoteID: IRRemote.ID) {
        modify(remoteID) { remote in
            if let index = remote.buttons.firstIndex(where: { $0.id == button.id }) {
                remote.buttons[index] = button
            }
        }
    }

    func deleteButton(_ buttonID: IRButton.ID, from remoteID: IRRemote.ID) {
        modify(remoteID) { $0.buttons.removeAll { $0.id == buttonID } }
    }

    func moveButton(_ buttonID: IRButton.ID, before targetID: IRButton.ID, in remoteID: IRRemote.ID) {
        modify(remoteID) { remote in
            guard let from = remote.buttons.firstIndex(where: { $0.id == buttonID }) else { return }
            let button = remote.buttons.remove(at: from)
            let to = remote.buttons.firstIndex(where: { $0.id == targetID }) ?? remote.buttons.endIndex
            remote.buttons.insert(button, at: to)
        }
    }

    // MARK: Import / export

    struct ImportSummary {
        var imported: [IRRemote] = []
        var messages: [String] = []
    }

    /// Imports Flipper `.ir`, IRDB `.csv` or MacIR `.json` files.
    func importFiles(_ urls: [URL]) -> ImportSummary {
        var summary = ImportSummary()
        for url in urls {
            let accessing = url.startAccessingSecurityScopedResource()
            defer { if accessing { url.stopAccessingSecurityScopedResource() } }
            do {
                let data = try Data(contentsOf: url)
                let name = url.deletingPathExtension().lastPathComponent.replacingOccurrences(of: "_", with: " ")
                switch url.pathExtension.lowercased() {
                case "json":
                    let archive = try JSONDecoder.library.decode(LibraryArchive.self, from: data)
                    for remote in archive.remotes {
                        var copy = remote
                        copy.id = UUID()
                        summary.imported.append(add(copy))
                    }
                case "csv":
                    let text = String(decoding: data, as: UTF8.self)
                    let parsed = IRDBCSV.parse(text)
                    guard !parsed.buttons.isEmpty else { throw IRFormatError.noSignals }
                    summary.imported.append(add(IRRemote(name: name, buttons: parsed.buttons)))
                    if !parsed.unsupportedProtocols.isEmpty {
                        summary.messages.append("\(url.lastPathComponent): skipped unsupported protocols \(parsed.unsupportedProtocols.sorted().joined(separator: ", ")).")
                    }
                default:
                    let text = String(data: data, encoding: .utf8) ?? String(decoding: data, as: UTF8.self)
                    let parsed = try FlipperIRFile.parse(text)
                    guard !parsed.buttons.isEmpty else { throw IRFormatError.noSignals }
                    let words = name.split(separator: " ")
                    let brand = words.count > 1 ? String(words[0]) : ""
                    let remote = IRRemote(name: name, brand: brand, category: DeviceCategory(databaseName: name), buttons: parsed.buttons)
                    summary.imported.append(add(remote))
                    if !parsed.skipped.isEmpty {
                        summary.messages.append("\(url.lastPathComponent): skipped \(parsed.skipped.count) malformed signal(s).")
                    }
                }
            } catch {
                summary.messages.append("\(url.lastPathComponent): \(error.localizedDescription)")
            }
        }
        return summary
    }

    private func uniqueName(_ name: String) -> String {
        let base = name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "Remote" : name
        let existing = Set(remotes.map(\.name))
        guard existing.contains(base) else { return base }
        var counter = 2
        while existing.contains("\(base) \(counter)") { counter += 1 }
        return "\(base) \(counter)"
    }
}

extension JSONDecoder {
    static var library: JSONDecoder {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return decoder
    }
}

extension JSONEncoder {
    static var library: JSONEncoder {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        return encoder
    }
}
