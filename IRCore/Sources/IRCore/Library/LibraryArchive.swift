import Foundation

/// On-disk representation of the user's remote library.
public struct LibraryArchive: Codable, Sendable {
    public static let currentVersion = 1

    public var version: Int
    public var remotes: [IRRemote]

    public init(remotes: [IRRemote]) {
        self.version = Self.currentVersion
        self.remotes = remotes
    }

    public static func load(from url: URL) throws -> LibraryArchive {
        let data = try Data(contentsOf: url)
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return try decoder.decode(LibraryArchive.self, from: data)
    }

    public func save(to url: URL) throws {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        let data = try encoder.encode(self)
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try data.write(to: url, options: .atomic)
    }
}

/// Cached database index for one source.
public struct DatabaseIndexCache: Codable, Sendable {
    public var sourceID: String
    public var fetchedAt: Date
    public var entries: [DatabaseEntry]

    public init(sourceID: String, fetchedAt: Date = Date(), entries: [DatabaseEntry]) {
        self.sourceID = sourceID
        self.fetchedAt = fetchedAt
        self.entries = entries
    }

    public static func load(from url: URL) throws -> DatabaseIndexCache {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return try decoder.decode(DatabaseIndexCache.self, from: Data(contentsOf: url))
    }

    public func save(to url: URL) throws {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try encoder.encode(self).write(to: url, options: .atomic)
    }
}
