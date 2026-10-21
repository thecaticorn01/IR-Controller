import Foundation
import Observation
import IRCore

/// Online remote databases: source configuration, cached indexes, search and previews.
@MainActor @Observable
final class DatabaseStore {
    static let shared = DatabaseStore()

    enum LoadState: Equatable {
        case idle
        case loading
        case loaded(Date)
        case failed(String)
    }

    // MARK: Sources

    private(set) var sources: [DatabaseSource]
    private(set) var loadStates: [String: LoadState] = [:]
    private var entriesBySource: [String: [DatabaseEntry]] = [:]

    /// All indexed entries from enabled sources.
    private(set) var allEntries: [DatabaseEntry] = []

    // MARK: Search state

    var query = DatabaseQuery() {
        didSet { if query != oldValue { scheduleSearch() } }
    }
    private(set) var results: [DatabaseEntry] = []
    private(set) var brandFacets: [FacetCount<String>] = []
    private(set) var categoryFacets: [FacetCount<DeviceCategory>] = []
    private(set) var isSearching = false
    /// Result selected in the browser (kept here so it survives navigation).
    var selectedEntryID: DatabaseEntry.ID?
    private var searchTask: Task<Void, Never>?

    /// Maximum number of results shown.
    let resultLimit = 1_000

    // MARK: Previews

    enum PreviewState {
        case loading
        case loaded(RemoteImport)
        case failed(String)
    }
    private(set) var previews: [DatabaseEntry.ID: PreviewState] = [:]

    private let cacheDirectory = URL.cachesDirectory.appending(path: "IR Controller/Indexes", directoryHint: .isDirectory)
    private static let sourcesKey = "databaseSources"
    private static let maxIndexAge: TimeInterval = 3 * 24 * 60 * 60

    var client: DatabaseClient {
        DatabaseClient(gitHubToken: KeychainHelper.read(account: KeychainHelper.gitHubTokenAccount))
    }

    private init() {
        LegacyMigration.runIfNeeded()
        var sources = DatabaseSource.builtIn
        if let data = UserDefaults.standard.data(forKey: Self.sourcesKey),
           let saved = try? JSONDecoder().decode([DatabaseSource].self, from: data) {
            // Keep built-in definitions current but honour the user's enabled flags.
            for index in sources.indices {
                if let match = saved.first(where: { $0.id == sources[index].id }) {
                    sources[index].isEnabled = match.isEnabled
                }
            }
            sources += saved.filter { !$0.isBuiltIn }
        }
        self.sources = sources
        loadCachedIndexes()
    }

    func source(id: String) -> DatabaseSource? { sources.first { $0.id == id } }

    var enabledSources: [DatabaseSource] { sources.filter(\.isEnabled) }

    var totalEntryCount: Int { allEntries.count }

    var isLoadingAny: Bool { loadStates.values.contains(.loading) }

    // MARK: Source management

    func setEnabled(_ enabled: Bool, sourceID: String) {
        guard let index = sources.firstIndex(where: { $0.id == sourceID }) else { return }
        sources[index].isEnabled = enabled
        persistSources()
        rebuildEntries()
        if enabled, entriesBySource[sourceID] == nil {
            Task { await refresh(sourceID: sourceID) }
        }
    }

    func addSource(_ source: DatabaseSource) {
        guard !sources.contains(where: { $0.id == source.id }) else { return }
        sources.append(source)
        persistSources()
        Task { await refresh(sourceID: source.id) }
    }

    func removeSource(id: String) {
        guard let source = source(id: id), !source.isBuiltIn else { return }
        sources.removeAll { $0.id == id }
        entriesBySource[id] = nil
        loadStates[id] = nil
        try? FileManager.default.removeItem(at: cacheURL(for: id))
        persistSources()
        rebuildEntries()
    }

    private func persistSources() {
        if let data = try? JSONEncoder().encode(sources) {
            UserDefaults.standard.set(data, forKey: Self.sourcesKey)
        }
    }

    // MARK: Indexes

    private func cacheURL(for sourceID: String) -> URL {
        let safe = sourceID.map { $0.isLetter || $0.isNumber ? $0 : "_" }
        return cacheDirectory.appending(path: String(safe) + ".json")
    }

    private func loadCachedIndexes() {
        for source in sources {
            if let cache = try? DatabaseIndexCache.load(from: cacheURL(for: source.id)) {
                entriesBySource[source.id] = cache.entries
                loadStates[source.id] = .loaded(cache.fetchedAt)
            }
        }
        rebuildEntries()
    }

    /// Downloads indexes that are missing or stale.
    func refreshIfNeeded() async {
        await withTaskGroup(of: Void.self) { group in
            for source in enabledSources {
                let stale: Bool
                switch loadStates[source.id] {
                case .loaded(let date): stale = Date().timeIntervalSince(date) > Self.maxIndexAge
                case .loading: stale = false
                default: stale = true
                }
                if stale {
                    group.addTask { await self.refresh(sourceID: source.id) }
                }
            }
        }
    }

    func refreshAll() async {
        await withTaskGroup(of: Void.self) { group in
            for source in enabledSources {
                group.addTask { await self.refresh(sourceID: source.id) }
            }
        }
    }

    func refresh(sourceID: String) async {
        guard let source = source(id: sourceID), loadStates[sourceID] != .loading else { return }
        let previous = loadStates[sourceID]
        loadStates[sourceID] = .loading
        do {
            let entries = try await client.fetchIndex(for: source)
            entriesBySource[sourceID] = entries
            let cache = DatabaseIndexCache(sourceID: sourceID, entries: entries)
            let url = cacheURL(for: sourceID)
            Task.detached(priority: .utility) { try? cache.save(to: url) }
            loadStates[sourceID] = .loaded(cache.fetchedAt)
            rebuildEntries()
        } catch {
            // Keep serving a stale cache if we have one.
            if entriesBySource[sourceID] != nil, case .loaded(let date)? = previous {
                loadStates[sourceID] = .loaded(date)
            } else {
                loadStates[sourceID] = .failed(error.localizedDescription)
            }
        }
    }

    func clearCache() {
        try? FileManager.default.removeItem(at: cacheDirectory)
        entriesBySource = [:]
        loadStates = [:]
        previews = [:]
        rebuildEntries()
    }

    private func rebuildEntries() {
        let enabled = Set(enabledSources.map(\.id))
        allEntries = sources.filter { enabled.contains($0.id) }.flatMap { entriesBySource[$0.id] ?? [] }
        scheduleSearch(debounce: false)
    }

    // MARK: Search

    private func scheduleSearch(debounce: Bool = true) {
        searchTask?.cancel()
        let entries = allEntries
        let query = query
        let limit = resultLimit
        isSearching = true
        searchTask = Task {
            if debounce { try? await Task.sleep(for: .milliseconds(150)) }
            guard !Task.isCancelled else { return }
            let computed = await Task.detached(priority: .userInitiated) {
                (
                    DatabaseSearch.search(entries, query: query, limit: limit),
                    DatabaseSearch.brandFacets(entries, query: query),
                    DatabaseSearch.categoryFacets(entries, query: query)
                )
            }.value
            guard !Task.isCancelled else { return }
            results = computed.0
            brandFacets = computed.1
            categoryFacets = computed.2
            isSearching = false
        }
    }

    // MARK: Previews

    func preview(for entry: DatabaseEntry) -> PreviewState? { previews[entry.id] }

    /// Downloads and parses a remote file (cached for the session).
    @discardableResult
    func loadPreview(_ entry: DatabaseEntry, force: Bool = false) async -> RemoteImport? {
        if !force, case .loaded(let result)? = previews[entry.id] { return result }
        guard let source = source(id: entry.sourceID) else { return nil }
        previews[entry.id] = .loading
        do {
            let result = try await client.fetchRemote(entry, source: source)
            previews[entry.id] = .loaded(result)
            return result
        } catch {
            previews[entry.id] = .failed(error.localizedDescription)
            return nil
        }
    }
}
