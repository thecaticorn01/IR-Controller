import Foundation

/// Brings over data saved by pre-release builds of the app, which were called
/// "MacIR" (bundle ID com.thecaticorn01.MacIR). Runs once per launch, before any
/// store reads its data; every step is a no-op when there is nothing to migrate.
@MainActor
enum LegacyMigration {
    static let legacyBundleID = "com.thecaticorn01.MacIR"
    private static let migratedDefaultsKey = "migratedLegacyDefaults"
    private static var didRun = false

    /// Migrates settings and the GitHub token.
    static func runIfNeeded() {
        guard !didRun else { return }
        didRun = true
        migrateDefaults()
        migrateGitHubToken()
    }

    /// Copies an old library to `destination` if there is no library there yet.
    /// Returns a message describing a failure, or nil.
    static func migrateLibrary(to destination: URL) -> String? {
        let fileManager = FileManager.default
        guard !fileManager.fileExists(atPath: destination.path) else { return nil }
        let candidates = [
            // Unsandboxed MacIR builds.
            URL.applicationSupportDirectory.appending(path: "MacIR/library.json"),
            // Early sandboxed MacIR builds kept it in their container.
            fileManager.homeDirectoryForCurrentUser
                .appending(path: "Library/Containers/\(legacyBundleID)/Data/Library/Application Support/MacIR/library.json"),
        ]
        guard let source = candidates.first(where: {
            $0.standardizedFileURL != destination.standardizedFileURL && fileManager.fileExists(atPath: $0.path)
        }) else { return nil }
        do {
            try fileManager.createDirectory(at: destination.deletingLastPathComponent(), withIntermediateDirectories: true)
            try fileManager.copyItem(at: source, to: destination)
            return nil
        } catch {
            return "Couldn’t bring over your remotes from the previous version: \(error.localizedDescription)"
        }
    }

    private static func migrateDefaults() {
        let defaults = UserDefaults.standard
        guard !defaults.bool(forKey: migratedDefaultsKey) else { return }
        defaults.set(true, forKey: migratedDefaultsKey)
        guard let legacy = defaults.persistentDomain(forName: legacyBundleID) else { return }
        for (key, value) in legacy where !key.hasPrefix("NS") && defaults.object(forKey: key) == nil {
            defaults.set(value, forKey: key)
        }
    }

    private static func migrateGitHubToken() {
        let account = KeychainHelper.gitHubTokenAccount
        guard KeychainHelper.read(account: account) == nil,
              let token = KeychainHelper.read(account: account, service: legacyBundleID) else { return }
        KeychainHelper.write(token, account: account)
    }
}
