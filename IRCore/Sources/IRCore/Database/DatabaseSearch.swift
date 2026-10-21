import Foundation

/// Filters applied to a database search.
public struct DatabaseQuery: Hashable, Sendable {
    public var text: String
    /// Restrict to these sources; empty means all.
    public var sourceIDs: Set<String>
    public var category: DeviceCategory?
    /// Exact brand (matched case- and diacritic-insensitively).
    public var brand: String?
    /// Only hand-curated files (excludes bulk-converted collections).
    public var curatedOnly: Bool

    public init(text: String = "", sourceIDs: Set<String> = [], category: DeviceCategory? = nil, brand: String? = nil, curatedOnly: Bool = false) {
        self.text = text
        self.sourceIDs = sourceIDs
        self.category = category
        self.brand = brand
        self.curatedOnly = curatedOnly
    }

    public var isEmpty: Bool {
        text.trimmingCharacters(in: .whitespaces).isEmpty && category == nil && brand == nil
    }
}

/// A facet value with its number of matching entries.
public struct FacetCount<Value: Hashable & Sendable>: Hashable, Sendable {
    public var value: Value
    public var count: Int
}

/// Ranked full-text search over database entries.
public enum DatabaseSearch {

    public static func search(_ entries: [DatabaseEntry], query: DatabaseQuery, limit: Int = 500) -> [DatabaseEntry] {
        let tokens = query.text.searchKey.split(separator: " ").map(String.init)
        let brandKey = query.brand?.searchKey
        var scored: [(entry: DatabaseEntry, score: Int)] = []
        for entry in entries where matchesFilters(entry, query: query, brandKey: brandKey) {
            guard let score = score(entry, tokens: tokens) else { continue }
            scored.append((entry, score))
        }
        scored.sort { lhs, rhs in
            if lhs.score != rhs.score { return lhs.score > rhs.score }
            if lhs.entry.brandKey != rhs.entry.brandKey { return lhs.entry.brandKey < rhs.entry.brandKey }
            return lhs.entry.model.localizedStandardCompare(rhs.entry.model) == .orderedAscending
        }
        return scored.prefix(limit).map(\.entry)
    }

    /// Counts of brands among entries matching everything except the brand filter.
    public static func brandFacets(_ entries: [DatabaseEntry], query: DatabaseQuery) -> [FacetCount<String>] {
        var q = query
        q.brand = nil
        let tokens = q.text.searchKey.split(separator: " ").map(String.init)
        var counts: [String: (name: String, count: Int)] = [:]
        for entry in entries where !entry.brand.isEmpty && matchesFilters(entry, query: q, brandKey: nil) && score(entry, tokens: tokens) != nil {
            counts[entry.brandKey, default: (entry.brand, 0)].count += 1
        }
        return counts.values.map { FacetCount(value: $0.name, count: $0.count) }
            .sorted { $0.value.localizedCaseInsensitiveCompare($1.value) == .orderedAscending }
    }

    /// Counts of categories among entries matching everything except the category filter.
    public static func categoryFacets(_ entries: [DatabaseEntry], query: DatabaseQuery) -> [FacetCount<DeviceCategory>] {
        var q = query
        q.category = nil
        let tokens = q.text.searchKey.split(separator: " ").map(String.init)
        let brandKey = q.brand?.searchKey
        var counts: [DeviceCategory: Int] = [:]
        for entry in entries where matchesFilters(entry, query: q, brandKey: brandKey) && score(entry, tokens: tokens) != nil {
            counts[entry.category, default: 0] += 1
        }
        return DeviceCategory.allCases.compactMap { category in
            counts[category].map { FacetCount(value: category, count: $0) }
        }
    }

    private static func matchesFilters(_ entry: DatabaseEntry, query: DatabaseQuery, brandKey: String?) -> Bool {
        if !query.sourceIDs.isEmpty && !query.sourceIDs.contains(entry.sourceID) { return false }
        if let category = query.category, entry.category != category { return false }
        if let brandKey, entry.brandKey != brandKey { return false }
        if query.curatedOnly && !entry.isCurated { return false }
        return true
    }

    /// Returns nil when the entry does not contain every token.
    static func score(_ entry: DatabaseEntry, tokens: [String]) -> Int? {
        var score = entry.isCurated ? 15 : 0
        guard !tokens.isEmpty else { return score }
        let key = entry.searchKey
        let modelKey = entry.modelKey
        for token in tokens {
            guard key.contains(token) || entry.compactKey.contains(token) else { return nil }
            if entry.brandKey == token { score += 100 }
            else if entry.brandKey.hasPrefix(token) { score += 50 }
            if key.contains(" " + token) { score += 10 }
            if modelKey == token { score += 80 }
            else if modelKey.contains(token) {
                score += 20
                // Compact model numbers: "un55" should match "UN55NU7100".
                if modelKey.first == token.first, modelKey.replacingOccurrences(of: " ", with: "").hasPrefix(token) { score += 15 }
            }
        }
        return score
    }
}
