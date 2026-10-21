import Foundation

/// Turns repository file listings into searchable `DatabaseEntry` values.
public enum DatabaseIndexer {

    /// Builds entries from repository-relative file paths.
    public static func entries(from paths: [String], source: DatabaseSource) -> [DatabaseEntry] {
        let fileExtension = source.format == .flipper ? ".ir" : ".csv"
        let prefix = source.subpath.isEmpty ? "" : source.subpath + "/"
        var result: [DatabaseEntry] = []
        result.reserveCapacity(paths.count)
        for path in paths {
            guard path.lowercased().hasSuffix(fileExtension), path.hasPrefix(prefix) else { continue }
            let relative = String(path.dropFirst(prefix.count))
            let entry: DatabaseEntry?
            switch source.format {
            case .flipper: entry = flipperEntry(path: path, relative: relative, source: source)
            case .irdbCSV: entry = irdbEntry(path: path, relative: relative, source: source)
            }
            if let entry { result.append(entry) }
        }
        return result.sorted { ($0.brandKey, $0.model) < ($1.brandKey, $1.model) }
    }

    static func display(_ component: some StringProtocol) -> String {
        var text = String(component)
        for ext in [".ir", ".csv", ".IR", ".CSV"] where text.hasSuffix(ext) { text.removeLast(ext.count) }
        return text.replacingOccurrences(of: "_", with: " ").trimmingCharacters(in: .whitespaces)
    }

    private static func flipperEntry(path: String, relative: String, source: DatabaseSource) -> DatabaseEntry? {
        let parts = relative.split(separator: "/").map(String.init)
        guard !parts.isEmpty else { return nil }
        // Hidden folders and repository plumbing.
        if parts.contains(where: { $0.hasPrefix(".") }) { return nil }

        if parts[0] == "_Converted_" {
            // _Converted_/<Collection>/<Letter>/<Brand>/<Model…>.ir
            guard parts.count >= 5 else { return nil }
            let collection = display(parts[1])
            let brand = display(parts[3])
            let rest = parts[4...].map(display)
            if parts[1].uppercased() == "CSV", rest.count >= 2 {
                // Converted IRDB files: <DeviceType>/<device,subdevice>.ir
                let deviceType = rest[0].replacingOccurrences(of: "Unknown ", with: "")
                return DatabaseEntry(sourceID: source.id, path: path, brand: brand,
                                     model: "\(deviceType) (\(rest.dropFirst().joined(separator: " ")))",
                                     categoryName: rest[0], collection: collection)
            }
            return DatabaseEntry(sourceID: source.id, path: path, brand: brand,
                                 model: rest.joined(separator: " / "), categoryName: "", collection: collection)
        }

        switch parts.count {
        case 1:
            return DatabaseEntry(sourceID: source.id, path: path, brand: "", model: display(parts[0]), categoryName: "")
        case 2:
            // Either <Brand>/<Model> or <Category>/<Model>; treat known categories specially.
            let first = display(parts[0])
            if DeviceCategory(databaseName: first) != .other {
                return DatabaseEntry(sourceID: source.id, path: path, brand: "", model: display(parts[1]), categoryName: first)
            }
            return DatabaseEntry(sourceID: source.id, path: path, brand: first, model: display(parts[1]), categoryName: "")
        default:
            let brand = display(parts[1])
            var model = parts[2...].map(display).joined(separator: " / ")
            // Curated files are usually named "<Brand>_<Model>.ir"; drop the redundant brand.
            if model.lowercased().hasPrefix(brand.lowercased() + " "), model.count > brand.count + 1 {
                model = String(model.dropFirst(brand.count + 1))
            }
            return DatabaseEntry(sourceID: source.id, path: path, brand: brand, model: model, categoryName: display(parts[0]))
        }
    }

    private static func irdbEntry(path: String, relative: String, source: DatabaseSource) -> DatabaseEntry? {
        // <Brand>/<DeviceType>/<device>,<subdevice>.csv
        let parts = relative.split(separator: "/").map(String.init)
        guard parts.count >= 3 else { return nil }
        let brand = display(parts[0])
        let deviceType = display(parts[1])
        let codes = display(parts[2...].joined(separator: "/")).replacingOccurrences(of: ",", with: ", ")
        let model = deviceType.replacingOccurrences(of: "Unknown ", with: "")
        return DatabaseEntry(sourceID: source.id, path: path, brand: brand, model: "\(model) (\(codes))",
                             categoryName: deviceType)
    }
}
