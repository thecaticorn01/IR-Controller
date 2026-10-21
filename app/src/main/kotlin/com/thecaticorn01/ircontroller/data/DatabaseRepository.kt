package com.thecaticorn01.ircontroller.data

import android.content.Context
import com.thecaticorn01.ircontroller.core.database.DatabaseClient
import com.thecaticorn01.ircontroller.core.database.DatabaseEntry
import com.thecaticorn01.ircontroller.core.database.DatabaseIndexCache
import com.thecaticorn01.ircontroller.core.database.DatabaseQuery
import com.thecaticorn01.ircontroller.core.database.DatabaseSearch
import com.thecaticorn01.ircontroller.core.database.DatabaseSource
import com.thecaticorn01.ircontroller.core.database.FacetCount
import com.thecaticorn01.ircontroller.core.database.RemoteImport
import com.thecaticorn01.ircontroller.core.library.IRJson
import com.thecaticorn01.ircontroller.core.model.DeviceCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/** Online remote databases: source configuration, cached indexes, search and previews. */
@OptIn(ExperimentalCoroutinesApi::class)
class DatabaseRepository(
    context: Context,
    private val scope: CoroutineScope,
    private val secrets: SecretStore,
) {
    sealed interface LoadState {
        data object Loading : LoadState
        data class Loaded(val fetchedAtMillis: Long) : LoadState
        data class Failed(val message: String) : LoadState
    }

    sealed interface PreviewState {
        data object Loading : PreviewState
        data class Loaded(val result: RemoteImport) : PreviewState
        data class Failed(val message: String) : PreviewState
    }

    data class SearchState(
        val results: List<DatabaseEntry> = emptyList(),
        val brandFacets: List<FacetCount<String>> = emptyList(),
        val categoryFacets: List<FacetCount<DeviceCategory>> = emptyList(),
        val isSearching: Boolean = false,
    )

    private val sourcesFile = File(context.filesDir, "sources.json")
    private val cacheDirectory = File(context.cacheDir, "indexes")

    private val _sources = MutableStateFlow(loadSources())
    val sources: StateFlow<List<DatabaseSource>> = _sources.asStateFlow()

    private val _loadStates = MutableStateFlow<Map<String, LoadState>>(emptyMap())
    val loadStates: StateFlow<Map<String, LoadState>> = _loadStates.asStateFlow()

    private val entriesBySource = MutableStateFlow<Map<String, List<DatabaseEntry>>>(emptyMap())

    /** All indexed entries from enabled sources. */
    val allEntries: StateFlow<List<DatabaseEntry>> = combine(_sources, entriesBySource) { sources, bySource ->
        sources.filter { it.isEnabled }.flatMap { bySource[it.id].orEmpty() }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** The current search; the browse screen edits it. */
    val query = MutableStateFlow(DatabaseQuery())

    /** Result selected in the browser on large screens (kept here so it survives navigation). */
    val selectedEntryId = MutableStateFlow<String?>(null)

    private val searching = MutableStateFlow(false)
    private val computed = MutableStateFlow(SearchState())

    val search: StateFlow<SearchState> = combine(computed, searching) { state, busy -> state.copy(isSearching = busy) }
        .stateIn(scope, SharingStarted.Eagerly, SearchState())

    private val _previews = MutableStateFlow<Map<String, PreviewState>>(emptyMap())
    val previews: StateFlow<Map<String, PreviewState>> = _previews.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) { loadCachedIndexes() }
        scope.launch {
            var previousEntries: List<DatabaseEntry>? = null
            combine(allEntries, query) { entries, query -> entries to query }
                .onEach { searching.value = true }
                .mapLatest { (entries, query) ->
                    // Debounce typing, but react to new indexes immediately.
                    if (entries === previousEntries) delay(150)
                    previousEntries = entries
                    withContext(Dispatchers.Default) {
                        SearchState(
                            results = DatabaseSearch.search(entries, query, RESULT_LIMIT),
                            brandFacets = DatabaseSearch.brandFacets(entries, query),
                            categoryFacets = DatabaseSearch.categoryFacets(entries, query),
                        )
                    }
                }
                .collect {
                    computed.value = it
                    searching.value = false
                }
        }
    }

    private val client: DatabaseClient get() = DatabaseClient(secrets.read(SecretStore.GITHUB_TOKEN))

    fun source(id: String): DatabaseSource? = _sources.value.firstOrNull { it.id == id }

    val enabledSources: List<DatabaseSource> get() = _sources.value.filter { it.isEnabled }

    fun entry(id: String): DatabaseEntry? = allEntries.value.firstOrNull { it.id == id }
        ?: entriesBySource.value.values.asSequence().flatten().firstOrNull { it.id == id }

    val isLoadingAny: Boolean get() = _loadStates.value.values.any { it is LoadState.Loading }

    // Source management

    private fun loadSources(): List<DatabaseSource> {
        val builtIn = DatabaseSource.builtIn
        val saved = runCatching {
            IRJson.decodeFromString(ListSerializer(DatabaseSource.serializer()), sourcesFile.readText())
        }.getOrNull() ?: return builtIn
        // Keep built-in definitions current but honour the user's enabled flags.
        return builtIn.map { source -> saved.firstOrNull { it.id == source.id }?.let { source.copy(isEnabled = it.isEnabled) } ?: source } +
            saved.filter { !it.isBuiltIn }
    }

    private fun persistSources() {
        val list = _sources.value
        scope.launch(Dispatchers.IO) {
            runCatching { sourcesFile.writeText(IRJson.encodeToString(ListSerializer(DatabaseSource.serializer()), list)) }
        }
    }

    fun setEnabled(enabled: Boolean, sourceId: String) {
        _sources.update { list -> list.map { if (it.id == sourceId) it.copy(isEnabled = enabled) else it } }
        persistSources()
        if (enabled && entriesBySource.value[sourceId] == null) scope.launch { refresh(sourceId) }
    }

    /** Adds a source; returns false if it already exists. */
    fun addSource(source: DatabaseSource): Boolean {
        if (_sources.value.any { it.id == source.id }) return false
        _sources.update { it + source }
        persistSources()
        scope.launch { refresh(source.id) }
        return true
    }

    fun removeSource(id: String) {
        val source = source(id) ?: return
        if (source.isBuiltIn) return
        _sources.update { list -> list.filterNot { it.id == id } }
        entriesBySource.update { it - id }
        _loadStates.update { it - id }
        cacheFile(id).delete()
        persistSources()
        query.update { it.copy(sourceIds = it.sourceIds - id) }
    }

    // Indexes

    private fun cacheFile(sourceId: String): File =
        File(cacheDirectory, sourceId.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("") + ".json")

    private fun loadCachedIndexes() {
        for (source in _sources.value) {
            val cache = runCatching {
                IRJson.decodeFromString(DatabaseIndexCache.serializer(), cacheFile(source.id).readText())
            }.getOrNull() ?: continue
            entriesBySource.update { it + (source.id to cache.entries) }
            _loadStates.update { if (it[source.id] is LoadState.Loading) it else it + (source.id to LoadState.Loaded(cache.fetchedAtMillis)) }
        }
    }

    /** Downloads indexes that are missing or stale. */
    suspend fun refreshIfNeeded() = coroutineScope {
        enabledSources.filter { source ->
            when (val state = _loadStates.value[source.id]) {
                is LoadState.Loaded -> System.currentTimeMillis() - state.fetchedAtMillis > MAX_INDEX_AGE_MILLIS
                LoadState.Loading -> false
                else -> true
            }
        }.map { async { refresh(it.id) } }.awaitAll()
    }

    suspend fun refreshAll() = coroutineScope {
        enabledSources.map { async { refresh(it.id) } }.awaitAll()
    }

    suspend fun refresh(sourceId: String) {
        val source = source(sourceId) ?: return
        val previous = _loadStates.value[sourceId]
        if (previous is LoadState.Loading) return
        _loadStates.update { it + (sourceId to LoadState.Loading) }
        try {
            val entries = client.fetchIndex(source)
            val cache = DatabaseIndexCache(sourceId, System.currentTimeMillis(), entries)
            entriesBySource.update { it + (sourceId to entries) }
            _loadStates.update { it + (sourceId to LoadState.Loaded(cache.fetchedAtMillis)) }
            withContext(Dispatchers.IO) {
                runCatching {
                    cacheDirectory.mkdirs()
                    cacheFile(sourceId).writeText(IRJson.encodeToString(DatabaseIndexCache.serializer(), cache))
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) {
                _loadStates.update { if (previous == null) it - sourceId else it + (sourceId to previous) }
                throw e
            }
            // Keep serving a stale cache if we have one.
            val fallback = if (entriesBySource.value[sourceId] != null && previous is LoadState.Loaded) previous
            else LoadState.Failed(e.message ?: e.toString())
            _loadStates.update { it + (sourceId to fallback) }
        }
    }

    /** Installs an index and previews without downloading anything (screenshot tests and demos). */
    @androidx.annotation.VisibleForTesting
    fun installForTesting(sourceId: String, entries: List<DatabaseEntry>, previews: Map<String, RemoteImport> = emptyMap()) {
        entriesBySource.update { it + (sourceId to entries) }
        _loadStates.update { it + (sourceId to LoadState.Loaded(System.currentTimeMillis())) }
        _previews.update { it + previews.mapValues { (_, result) -> PreviewState.Loaded(result) } }
    }

    fun clearCache() {
        cacheDirectory.deleteRecursively()
        entriesBySource.value = emptyMap()
        _loadStates.value = emptyMap()
        _previews.value = emptyMap()
    }

    // Previews

    /** Downloads and parses a remote file (cached for the session). */
    suspend fun loadPreview(entry: DatabaseEntry, force: Boolean = false): RemoteImport? {
        if (!force) (_previews.value[entry.id] as? PreviewState.Loaded)?.let { return it.result }
        val source = source(entry.sourceId) ?: return null
        _previews.update { it + (entry.id to PreviewState.Loading) }
        return try {
            val result = client.fetchRemote(entry, source)
            _previews.update { it + (entry.id to PreviewState.Loaded(result)) }
            result
        } catch (e: kotlinx.coroutines.CancellationException) {
            _previews.update { it - entry.id }
            throw e
        } catch (e: Exception) {
            _previews.update { it + (entry.id to PreviewState.Failed(e.message ?: e.toString())) }
            null
        }
    }

    companion object {
        /** Maximum number of results shown. */
        const val RESULT_LIMIT = 1_000
        private const val MAX_INDEX_AGE_MILLIS = 3L * 24 * 60 * 60 * 1000

        /** Repositories offered as one-tap suggestions when adding a source. */
        val suggestedSources = listOf(
            DatabaseSource.make(
                name = "UberGuidoZ Flipper (Infrared)", owner = "UberGuidoZ", repository = "Flipper", branch = "main",
                subpath = "Infrared", summary = "A large community collection of Flipper Zero files, including thousands of IR remotes.",
            ),
        )
    }
}
