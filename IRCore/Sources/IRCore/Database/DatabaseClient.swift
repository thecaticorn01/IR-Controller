import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

public enum DatabaseError: LocalizedError, Sendable {
    case httpStatus(Int, URL)
    case rateLimited
    case invalidListing
    case unreadableFile

    public var errorDescription: String? {
        switch self {
        case .httpStatus(let code, let url): "The server returned HTTP \(code) for \(url.lastPathComponent)."
        case .rateLimited: "GitHub’s rate limit was reached. Try again later or add a GitHub token in Settings."
        case .invalidListing: "The repository listing could not be read."
        case .unreadableFile: "The remote file could not be decoded."
        }
    }
}

/// Downloads database listings and remote files from GitHub.
public struct DatabaseClient: Sendable {
    public var session: URLSession
    /// Optional GitHub personal access token, raising the API rate limit.
    public var gitHubToken: String?

    public init(session: URLSession = .shared, gitHubToken: String? = nil) {
        self.session = session
        self.gitHubToken = gitHubToken?.isEmpty == true ? nil : gitHubToken
    }

    // MARK: Listings

    /// Fetches and indexes every remote file in a source.
    public func fetchIndex(for source: DatabaseSource) async throws -> [DatabaseEntry] {
        let paths: [String]
        if let indexFile = source.indexFile {
            paths = try await fetchIndexFile(indexFile, source: source)
        } else {
            do {
                paths = try await fetchGitTree(source)
            } catch {
                // The jsDelivr mirror has no rate limit and serves the same listing.
                paths = try await fetchJSDelivrListing(source)
            }
        }
        return DatabaseIndexer.entries(from: paths, source: source)
    }

    private func fetchIndexFile(_ indexFile: String, source: DatabaseSource) async throws -> [String] {
        guard let url = source.rawURL(for: indexFile) else { throw DatabaseError.invalidListing }
        let text = try await fetchText(url)
        // Index entries are relative to the folder containing the index file.
        let base = (indexFile as NSString).deletingLastPathComponent
        return text.split(whereSeparator: \.isNewline).map {
            let line = $0.trimmingCharacters(in: .whitespaces)
            return base.isEmpty ? line : base + "/" + line
        }
    }

    private struct GitTree: Decodable {
        struct Item: Decodable { let path: String; let type: String }
        let tree: [Item]
        let truncated: Bool?
    }

    private func fetchGitTree(_ source: DatabaseSource) async throws -> [String] {
        let branch = source.branch.addingPercentEncoding(withAllowedCharacters: .urlPathComponentAllowed) ?? source.branch
        guard let url = URL(string: "https://api.github.com/repos/\(source.owner)/\(source.repository)/git/trees/\(branch)?recursive=1") else {
            throw DatabaseError.invalidListing
        }
        var request = URLRequest(url: url)
        request.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        request.setValue("2022-11-28", forHTTPHeaderField: "X-GitHub-Api-Version")
        if let gitHubToken { request.setValue("Bearer \(gitHubToken)", forHTTPHeaderField: "Authorization") }
        let data = try await fetch(request)
        let tree = try JSONDecoder().decode(GitTree.self, from: data)
        return tree.tree.filter { $0.type == "blob" }.map(\.path)
    }

    private struct JSDelivrListing: Decodable {
        struct File: Decodable { let name: String }
        let files: [File]
    }

    private func fetchJSDelivrListing(_ source: DatabaseSource) async throws -> [String] {
        guard let url = URL(string: "https://data.jsdelivr.com/v1/packages/gh/\(source.owner)/\(source.repository)@\(source.branch)?structure=flat") else {
            throw DatabaseError.invalidListing
        }
        let data = try await fetch(URLRequest(url: url))
        let listing = try JSONDecoder().decode(JSDelivrListing.self, from: data)
        return listing.files.map { $0.name.hasPrefix("/") ? String($0.name.dropFirst()) : $0.name }
    }

    // MARK: Files

    /// Downloads a remote file and converts it into a library remote.
    public func fetchRemote(_ entry: DatabaseEntry, source: DatabaseSource) async throws -> RemoteImport {
        guard let url = source.rawURL(for: entry.path) else { throw DatabaseError.unreadableFile }
        let text = try await fetchText(url)
        return try RemoteImport.make(from: text, format: source.format, entry: entry, source: source)
    }

    public func fetchText(_ url: URL) async throws -> String {
        let data = try await fetch(URLRequest(url: url))
        if let text = String(data: data, encoding: .utf8) ?? String(data: data, encoding: .isoLatin1) {
            return text
        }
        throw DatabaseError.unreadableFile
    }

    private func fetch(_ request: URLRequest) async throws -> Data {
        var request = request
        request.timeoutInterval = 30
        request.setValue("IR-Controller", forHTTPHeaderField: "User-Agent")
        let (data, response) = try await session.data(for: request)
        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            if http.statusCode == 403 || http.statusCode == 429 { throw DatabaseError.rateLimited }
            throw DatabaseError.httpStatus(http.statusCode, request.url ?? URL(fileURLWithPath: "/"))
        }
        return data
    }
}

/// The result of downloading a database file: the remote plus import diagnostics.
public struct RemoteImport: Sendable {
    public var remote: IRRemote
    /// Human-readable notes about signals that could not be imported.
    public var warnings: [String]

    public static func make(from text: String, format: DatabaseFormat, entry: DatabaseEntry, source: DatabaseSource) throws -> RemoteImport {
        var warnings: [String] = []
        let buttons: [IRButton]
        switch format {
        case .flipper:
            let parsed = try FlipperIRFile.parse(text)
            buttons = parsed.buttons
            if !parsed.skipped.isEmpty {
                warnings.append("Skipped \(parsed.skipped.count) malformed signal(s): " + parsed.skipped.prefix(5).map(\.name).joined(separator: ", "))
            }
            let unsupported = Set(buttons.compactMap { button -> String? in
                if case .parsed(let p) = button.signal, !IRProtocolRegistry.isSupported(p.protocolName) { return p.protocolName }
                return nil
            })
            if !unsupported.isEmpty {
                warnings.append("Unsupported protocol(s): " + unsupported.sorted().joined(separator: ", "))
            }
        case .irdbCSV:
            let parsed = IRDBCSV.parse(text)
            buttons = parsed.buttons
            if !parsed.unsupportedProtocols.isEmpty {
                warnings.append("Skipped codes using unsupported protocol(s): " + parsed.unsupportedProtocols.sorted().joined(separator: ", "))
            }
        }
        guard !buttons.isEmpty else { throw IRFormatError.noSignals }
        let remote = IRRemote(
            name: entry.title,
            brand: entry.brand,
            model: entry.model,
            category: entry.category,
            buttons: buttons,
            origin: RemoteOrigin(sourceID: source.id, sourceName: source.name, path: entry.path, url: source.webURL(for: entry.path))
        )
        return RemoteImport(remote: remote, warnings: warnings)
    }
}
