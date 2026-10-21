import Foundation

/// File format used by an online remote database.
public enum DatabaseFormat: String, Codable, Sendable, CaseIterable {
    /// Flipper Zero `.ir` files (Flipper-IRDB and its many forks).
    case flipper
    /// IRDB CSV files (`functionname,protocol,device,subdevice,function`).
    case irdbCSV
}

/// A GitHub-hosted database of infrared remotes.
public struct DatabaseSource: Codable, Hashable, Identifiable, Sendable {
    public var id: String
    public var name: String
    public var owner: String
    public var repository: String
    public var branch: String
    /// Only files below this folder are indexed ("" for the whole repository).
    public var subpath: String
    public var format: DatabaseFormat
    /// Path of a plain-text file listing every remote file, if the repository has one.
    /// When `nil` the GitHub tree API is used.
    public var indexFile: String?
    public var isBuiltIn: Bool
    public var isEnabled: Bool
    public var summary: String

    public init(
        id: String? = nil, name: String, owner: String, repository: String, branch: String = "main",
        subpath: String = "", format: DatabaseFormat = .flipper, indexFile: String? = nil,
        isBuiltIn: Bool = false, isEnabled: Bool = true, summary: String = ""
    ) {
        self.id = id ?? "\(owner)/\(repository)@\(branch)/\(subpath)".lowercased()
        self.name = name
        self.owner = owner
        self.repository = repository
        self.branch = branch
        self.subpath = subpath.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        self.format = format
        self.indexFile = indexFile
        self.isBuiltIn = isBuiltIn
        self.isEnabled = isEnabled
        self.summary = summary
    }

    public static let flipperIRDB = DatabaseSource(
        id: "flipper-irdb", name: "Flipper-IRDB", owner: "Lucaslhm", repository: "Flipper-IRDB",
        branch: "main", format: .flipper, isBuiltIn: true,
        summary: "Community collection of Flipper Zero infrared files: TVs, ACs, audio, projectors, LED lights and more."
    )

    public static let irdb = DatabaseSource(
        id: "probonopd-irdb", name: "IRDB", owner: "probonopd", repository: "irdb",
        branch: "master", subpath: "codes", format: .irdbCSV, indexFile: "codes/index", isBuiltIn: true,
        summary: "Thousands of protocol-level codes by manufacturer and device type, compiled from many sources."
    )

    public static let builtIn: [DatabaseSource] = [.flipperIRDB, .irdb]

    public var webURL: URL {
        URL(string: "https://github.com/\(owner)/\(repository)/tree/\(branch)/\(subpath)")!
    }

    /// URL of a file in the repository on raw.githubusercontent.com.
    public func rawURL(for path: String) -> URL? {
        let encoded = path.split(separator: "/").map {
            String($0).addingPercentEncoding(withAllowedCharacters: .urlPathComponentAllowed) ?? String($0)
        }.joined(separator: "/")
        return URL(string: "https://raw.githubusercontent.com/\(owner)/\(repository)/\(branch)/\(encoded)")
    }

    public func webURL(for path: String) -> URL? {
        let encoded = path.split(separator: "/").map {
            String($0).addingPercentEncoding(withAllowedCharacters: .urlPathComponentAllowed) ?? String($0)
        }.joined(separator: "/")
        return URL(string: "https://github.com/\(owner)/\(repository)/blob/\(branch)/\(encoded)")
    }
}

extension CharacterSet {
    /// Characters allowed unescaped inside a single path component.
    static let urlPathComponentAllowed: CharacterSet = {
        var set = CharacterSet.urlPathAllowed
        set.remove(charactersIn: "/;?#")
        return set
    }()
}

/// One remote file in an online database.
public struct DatabaseEntry: Codable, Hashable, Identifiable, Sendable {
    public var id: String { "\(sourceID):\(path)" }
    public var sourceID: String
    /// Path of the file inside the repository.
    public var path: String
    public var brand: String
    public var model: String
    /// Category or device type as named by the database.
    public var categoryName: String
    /// Sub-collection, e.g. "Pronto" or "IR Plus" for Flipper-IRDB's converted files.
    public var collection: String
    public var category: DeviceCategory
    /// Precomputed normalised text for searching.
    public var searchKey: String
    public var brandKey: String
    public var modelKey: String
    /// `searchKey` without spaces, so "aa5900600a" finds "AA59-00600A".
    public var compactKey: String

    public init(sourceID: String, path: String, brand: String, model: String, categoryName: String, collection: String = "") {
        self.sourceID = sourceID
        self.path = path
        self.brand = brand
        self.model = model
        self.categoryName = categoryName
        self.collection = collection
        let fromCategory = DeviceCategory(databaseName: categoryName)
        self.category = fromCategory == .other ? DeviceCategory(databaseName: model) : fromCategory
        self.brandKey = brand.searchKey
        self.modelKey = model.searchKey
        self.searchKey = " " + [brand, model, categoryName, collection, category.displayName].joined(separator: " ").searchKey
        self.compactKey = searchKey.replacingOccurrences(of: " ", with: "")
    }

    /// Human-readable title such as "Samsung · AA59-00600A".
    public var title: String { brand.isEmpty ? model : "\(brand) \(model)" }

    /// Whether the file comes from a hand-curated folder rather than a bulk conversion.
    public var isCurated: Bool { collection.isEmpty }
}
