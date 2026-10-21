package com.thecaticorn01.ircontroller.core.database

import com.thecaticorn01.ircontroller.core.formats.FlipperIRFile
import com.thecaticorn01.ircontroller.core.formats.IRDBCSV
import com.thecaticorn01.ircontroller.core.formats.IRFormatException
import com.thecaticorn01.ircontroller.core.library.IRJson
import com.thecaticorn01.ircontroller.core.model.IRRemote
import com.thecaticorn01.ircontroller.core.model.IRSignal
import com.thecaticorn01.ircontroller.core.model.RemoteOrigin
import com.thecaticorn01.ircontroller.core.protocols.IRProtocolRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.Serializable
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

sealed class DatabaseException(message: String) : IOException(message) {
    class HttpStatus(val code: Int, url: String) :
        DatabaseException("The server returned HTTP $code for ${url.substringAfterLast('/')}.")
    class RateLimited : DatabaseException("GitHub’s rate limit was reached. Try again later or add a GitHub token in Settings.")
    class InvalidListing : DatabaseException("The repository listing could not be read.")
    class UnreadableFile : DatabaseException("The remote file could not be decoded.")
}

/** Downloads database listings and remote files from GitHub. */
class DatabaseClient(
    gitHubToken: String? = null,
    private val userAgent: String = "IRController-Android",
) {
    /** Optional GitHub personal access token, raising the API rate limit. */
    private val gitHubToken: String? = gitHubToken?.takeIf { it.isNotBlank() }

    // Listings

    /** Fetches and indexes every remote file in a source. */
    suspend fun fetchIndex(source: DatabaseSource): List<DatabaseEntry> {
        val paths = if (source.indexFile != null) {
            fetchIndexFile(source.indexFile, source)
        } else {
            try {
                fetchGitTree(source)
            } catch (e: IOException) {
                // The jsDelivr mirror has no rate limit and serves the same listing.
                try {
                    fetchJSDelivrListing(source)
                } catch (fallback: IOException) {
                    throw e
                }
            }
        }
        return DatabaseIndexer.entries(paths, source)
    }

    private suspend fun fetchIndexFile(indexFile: String, source: DatabaseSource): List<String> {
        val text = fetchText(source.rawUrl(indexFile))
        // Index entries are relative to the folder containing the index file.
        val base = indexFile.substringBeforeLast('/', "")
        return text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
            .map { if (base.isEmpty()) it else "$base/$it" }.toList()
    }

    @Serializable
    private data class GitTree(val tree: List<Item>, val truncated: Boolean? = null) {
        @Serializable
        data class Item(val path: String, val type: String)
    }

    private suspend fun fetchGitTree(source: DatabaseSource): List<String> {
        val branch = DatabaseSource.encodePathComponent(source.branch)
        val url = "https://api.github.com/repos/${source.owner}/${source.repository}/git/trees/$branch?recursive=1"
        val headers = buildMap {
            put("Accept", "application/vnd.github+json")
            put("X-GitHub-Api-Version", "2022-11-28")
            gitHubToken?.let { put("Authorization", "Bearer $it") }
        }
        val data = fetch(url, headers)
        val tree = try {
            IRJson.decodeFromString(GitTree.serializer(), data.decodeToString())
        } catch (e: IllegalArgumentException) {
            throw DatabaseException.InvalidListing()
        }
        return tree.tree.filter { it.type == "blob" }.map { it.path }
    }

    @Serializable
    private data class JSDelivrListing(val files: List<File>) {
        @Serializable
        data class File(val name: String)
    }

    private suspend fun fetchJSDelivrListing(source: DatabaseSource): List<String> {
        val url = "https://data.jsdelivr.com/v1/packages/gh/${source.owner}/${source.repository}@${source.branch}?structure=flat"
        val data = fetch(url)
        val listing = try {
            IRJson.decodeFromString(JSDelivrListing.serializer(), data.decodeToString())
        } catch (e: IllegalArgumentException) {
            throw DatabaseException.InvalidListing()
        }
        return listing.files.map { it.name.removePrefix("/") }
    }

    // Files

    /** Downloads a remote file and converts it into a library remote. */
    suspend fun fetchRemote(entry: DatabaseEntry, source: DatabaseSource): RemoteImport {
        val text = fetchText(source.rawUrl(entry.path))
        return RemoteImport.make(text, source.format, entry, source)
    }

    suspend fun fetchText(url: String): String = decodeText(fetch(url))

    private suspend fun fetch(url: String, headers: Map<String, String> = emptyMap()): ByteArray =
        runInterruptible(Dispatchers.IO) {
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 30_000
                connection.readTimeout = 30_000
                connection.setRequestProperty("User-Agent", userAgent)
                headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
                val status = connection.responseCode
                if (status !in 200..299) {
                    if (status == 403 || status == 429) throw DatabaseException.RateLimited()
                    throw DatabaseException.HttpStatus(status, url)
                }
                connection.inputStream.use { it.readBytes() }
            } finally {
                connection.disconnect()
            }
        }

    companion object {
        /** UTF-8 when valid, otherwise ISO Latin 1 (some older IRDB files). */
        fun decodeText(data: ByteArray): String = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(data))
                .toString()
        } catch (e: CharacterCodingException) {
            String(data, Charsets.ISO_8859_1)
        }
    }
}

/** The result of downloading a database file: the remote plus import diagnostics. */
data class RemoteImport(
    val remote: IRRemote,
    /** Human-readable notes about signals that could not be imported. */
    val warnings: List<String>,
) {
    companion object {
        fun make(text: String, format: DatabaseFormat, entry: DatabaseEntry, source: DatabaseSource): RemoteImport {
            val warnings = mutableListOf<String>()
            val buttons = when (format) {
                DatabaseFormat.FLIPPER -> {
                    val parsed = FlipperIRFile.parse(text)
                    if (parsed.skipped.isNotEmpty()) {
                        warnings.add(
                            "Skipped ${parsed.skipped.size} malformed signal(s): " +
                                parsed.skipped.take(5).joinToString(", ") { it.name },
                        )
                    }
                    val unsupported = parsed.buttons.mapNotNull { button ->
                        (button.signal as? IRSignal.Parsed)?.signal?.protocolName
                            ?.takeIf { !IRProtocolRegistry.isSupported(it) }
                    }.toSortedSet()
                    if (unsupported.isNotEmpty()) warnings.add("Unsupported protocol(s): " + unsupported.joinToString(", "))
                    parsed.buttons
                }
                DatabaseFormat.IRDB_CSV -> {
                    val parsed = IRDBCSV.parse(text)
                    if (parsed.unsupportedProtocols.isNotEmpty()) {
                        warnings.add("Skipped codes using unsupported protocol(s): " + parsed.unsupportedProtocols.joinToString(", "))
                    }
                    parsed.buttons
                }
            }
            if (buttons.isEmpty()) throw IRFormatException.NoSignals()
            val remote = IRRemote(
                name = entry.title,
                brand = entry.brand,
                model = entry.model,
                category = entry.category,
                buttons = buttons,
                origin = RemoteOrigin(source.id, source.name, entry.path, source.webUrl(entry.path)),
            )
            return RemoteImport(remote, warnings)
        }
    }
}
