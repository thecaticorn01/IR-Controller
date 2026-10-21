package com.thecaticorn01.ircontroller.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.thecaticorn01.ircontroller.core.database.DatabaseClient
import com.thecaticorn01.ircontroller.core.formats.FlipperIRFile
import com.thecaticorn01.ircontroller.core.formats.IRDBCSV
import com.thecaticorn01.ircontroller.core.formats.IRFormatException
import com.thecaticorn01.ircontroller.core.library.LibraryArchive
import com.thecaticorn01.ircontroller.core.model.DeviceCategory
import com.thecaticorn01.ircontroller.core.model.IRButton
import com.thecaticorn01.ircontroller.core.model.IRRemote
import com.thecaticorn01.ircontroller.core.model.newID
import com.thecaticorn01.ircontroller.core.model.searchKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** The user's saved remotes, persisted as `library.json` in app storage. */
class LibraryRepository(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val file = File(context.filesDir, "library.json")
    private val _remotes = MutableStateFlow<List<IRRemote>>(emptyList())
    val remotes: StateFlow<List<IRRemote>> = _remotes.asStateFlow()

    private val _loadError = MutableStateFlow<String?>(null)
    /** Set when the library file exists but couldn't be read; a backup copy is kept. */
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    private val loaded = CompletableDeferred<Unit>()
    private var saveJob: Job? = null

    init {
        scope.launch(Dispatchers.IO) {
            load()
            loaded.complete(Unit)
        }
    }

    /** Waits until the library has been read from disk (for widgets and automation). */
    suspend fun awaitLoaded() = loaded.await()

    fun remote(id: String): IRRemote? = _remotes.value.firstOrNull { it.id == id }

    /** Finds a remote by name, ignoring case and punctuation (used by automation). */
    fun remoteNamed(name: String): IRRemote? {
        val key = name.searchKey
        return _remotes.value.firstOrNull { it.name.searchKey == key }
    }

    val favorites: List<IRRemote> get() = _remotes.value.filter { it.isFavorite }

    // Persistence

    private fun load() {
        if (!file.exists()) return
        try {
            _remotes.value = LibraryArchive.decode(file.readText()).remotes
        } catch (e: Exception) {
            _loadError.value = e.message ?: e.toString()
            // Keep a copy of the unreadable file instead of overwriting it.
            runCatching { file.copyTo(File(file.parentFile, "library.corrupt-${System.currentTimeMillis()}.json")) }
        }
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch(Dispatchers.IO) {
            delay(300)
            val text = LibraryArchive(remotes = _remotes.value).encode()
            val temp = File(file.parentFile, "library.json.tmp")
            temp.writeText(text)
            if (!temp.renameTo(file)) {
                file.writeText(text)
                temp.delete()
            }
        }
    }

    private fun mutate(change: (List<IRRemote>) -> List<IRRemote>) {
        _remotes.update(change)
        scheduleSave()
    }

    // Remotes

    fun add(remote: IRRemote): IRRemote {
        var added = remote
        mutate { list ->
            // The same downloaded preview can be added more than once; each copy needs its own ID.
            val id = if (list.any { it.id == remote.id }) newID() else remote.id
            added = remote.copy(id = id, name = uniqueName(remote.name, list))
            list + added
        }
        return added
    }

    fun update(remote: IRRemote) = mutate { list ->
        list.map { if (it.id == remote.id) remote.copy(modifiedAt = IRRemote.now()) else it }
    }

    /** Applies an in-place change to a remote. */
    fun modify(id: String, change: (IRRemote) -> IRRemote) = mutate { list ->
        list.map { if (it.id == id) change(it).copy(modifiedAt = IRRemote.now()) else it }
    }

    fun delete(id: String) = mutate { list -> list.filterNot { it.id == id } }

    fun duplicate(id: String): IRRemote? {
        val original = remote(id) ?: return null
        val now = IRRemote.now()
        return add(
            original.copy(
                id = newID(),
                name = original.name + " Copy",
                isFavorite = false,
                buttons = original.buttons.map { IRButton(name = it.name, signal = it.signal) },
                createdAt = now,
                modifiedAt = now,
            ),
        )
    }

    fun toggleFavorite(id: String) = modify(id) { it.copy(isFavorite = !it.isFavorite) }

    /** Moves a remote up (-1) or down (+1) in the list. */
    fun move(id: String, delta: Int) = mutate { list ->
        val from = list.indexOfFirst { it.id == id }
        val to = from + delta
        if (from < 0 || to !in list.indices) list else list.toMutableList().apply { add(to, removeAt(from)) }
    }

    // Buttons

    fun addButton(button: IRButton, remoteId: String) = modify(remoteId) { it.copy(buttons = it.buttons + button) }

    fun updateButton(button: IRButton, remoteId: String) = modify(remoteId) { remote ->
        remote.copy(buttons = remote.buttons.map { if (it.id == button.id) button else it })
    }

    fun deleteButton(buttonId: String, remoteId: String) = modify(remoteId) { remote ->
        remote.copy(buttons = remote.buttons.filterNot { it.id == buttonId })
    }

    /** Moves a button up (-1) or down (+1). */
    fun moveButton(buttonId: String, delta: Int, remoteId: String) = modify(remoteId) { remote ->
        val buttons = remote.buttons.toMutableList()
        val from = buttons.indexOfFirst { it.id == buttonId }
        val to = from + delta
        if (from >= 0 && to in buttons.indices) buttons.add(to, buttons.removeAt(from))
        remote.copy(buttons = buttons)
    }

    // Import / export

    data class ImportSummary(val imported: List<IRRemote>, val messages: List<String>)

    /** Imports Flipper `.ir`, IRDB `.csv` or library `.json` files. */
    suspend fun importUris(uris: List<Uri>): ImportSummary {
        val imported = mutableListOf<IRRemote>()
        val messages = mutableListOf<String>()
        for (uri in uris) {
            val fileName = withContext(Dispatchers.IO) { displayName(context.contentResolver, uri) } ?: "Remote"
            try {
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } ?: throw IllegalStateException("The file couldn’t be opened.")
                val text = DatabaseClient.decodeText(bytes)
                val summary = importText(text, fileName)
                imported += summary.imported
                messages += summary.messages
            } catch (e: Exception) {
                messages += "$fileName: ${e.message ?: e.toString()}"
            }
        }
        return ImportSummary(imported, messages)
    }

    /** Imports file contents, picking the format from the extension or the contents. */
    fun importText(text: String, fileName: String): ImportSummary {
        val imported = mutableListOf<IRRemote>()
        val messages = mutableListOf<String>()
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val name = fileName.substringBeforeLast('.').replace('_', ' ').ifBlank { "Remote" }
        val trimmed = text.trimStart()
        val format = when {
            extension == "json" || trimmed.startsWith("{") -> "json"
            extension == "csv" || trimmed.lowercase(Locale.ROOT).startsWith("functionname") -> "csv"
            else -> "ir"
        }
        when (format) {
            "json" -> {
                val archive = LibraryArchive.decode(text)
                for (remote in archive.remotes) {
                    imported += add(remote.copy(id = newID(), buttons = remote.buttons.map { it.copy(id = newID()) }))
                }
                if (archive.remotes.isEmpty()) throw IRFormatException.NoSignals()
            }
            "csv" -> {
                val parsed = IRDBCSV.parse(text)
                if (parsed.buttons.isEmpty()) throw IRFormatException.NoSignals()
                imported += add(IRRemote(name = name, buttons = parsed.buttons))
                if (parsed.unsupportedProtocols.isNotEmpty()) {
                    messages += "$fileName: skipped unsupported protocols ${parsed.unsupportedProtocols.joinToString(", ")}."
                }
            }
            else -> {
                val parsed = FlipperIRFile.parse(text)
                if (parsed.buttons.isEmpty()) throw IRFormatException.NoSignals()
                val words = name.split(' ').filter { it.isNotEmpty() }
                val brand = if (words.size > 1) words[0] else ""
                imported += add(
                    IRRemote(name = name, brand = brand, category = DeviceCategory.fromDatabaseName(name), buttons = parsed.buttons),
                )
                if (parsed.skipped.isNotEmpty()) {
                    messages += "$fileName: skipped ${parsed.skipped.size} malformed signal(s)."
                }
            }
        }
        return ImportSummary(imported, messages)
    }

    /** The whole library as a JSON backup. */
    fun exportArchive(remotes: List<IRRemote> = _remotes.value): String = LibraryArchive(remotes = remotes).encode()

    fun exportFlipper(remote: IRRemote): String = FlipperIRFile.serialize(remote.buttons)

    /** Writes text to a document the user picked with the system file picker. */
    suspend fun writeDocument(uri: Uri, text: String) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) }
            ?: throw IllegalStateException("The file couldn’t be written.")
    }

    private fun uniqueName(name: String, existing: List<IRRemote>): String {
        val base = name.trim().ifEmpty { "Remote" }
        val names = existing.map { it.name }.toSet()
        if (base !in names) return base
        var counter = 2
        while ("$base $counter" in names) counter++
        return "$base $counter"
    }

    companion object {
        fun displayName(resolver: ContentResolver, uri: Uri): String? {
            if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
                runCatching {
                    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) return cursor.getString(0)
                    }
                }
            }
            return uri.lastPathSegment?.substringAfterLast('/')
        }

        /** "Living Room TV" → "Living_Room_TV.ir" */
        fun fileName(remote: IRRemote, extension: String): String =
            remote.name.replace(Regex("[\\\\/:*?\"<>|]"), "").trim().replace(' ', '_').ifEmpty { "Remote" } + extension
    }
}
