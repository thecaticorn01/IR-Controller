package com.thecaticorn01.ircontroller.core.database

import com.thecaticorn01.ircontroller.core.model.DeviceCategory
import com.thecaticorn01.ircontroller.core.model.searchKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.util.Locale

/** File format used by an online remote database. */
@Serializable
enum class DatabaseFormat(val displayName: String, val fileExtension: String) {
    /** Flipper Zero `.ir` files (Flipper-IRDB and its many forks). */
    @SerialName("flipper") FLIPPER("Flipper Zero (.ir)", ".ir"),
    /** IRDB CSV files (`functionname,protocol,device,subdevice,function`). */
    @SerialName("irdbCSV") IRDB_CSV("IRDB CSV (.csv)", ".csv"),
}

/** A GitHub-hosted database of infrared remotes. */
@Serializable
data class DatabaseSource(
    val id: String,
    val name: String,
    val owner: String,
    val repository: String,
    val branch: String = "main",
    /** Only files below this folder are indexed ("" for the whole repository). */
    val subpath: String = "",
    val format: DatabaseFormat = DatabaseFormat.FLIPPER,
    /**
     * Path of a plain-text file listing every remote file, if the repository has one.
     * When `null` the GitHub tree API is used.
     */
    val indexFile: String? = null,
    val isBuiltIn: Boolean = false,
    val isEnabled: Boolean = true,
    val summary: String = "",
) {
    val webUrl: String get() = "https://github.com/$owner/$repository/tree/$branch/$subpath".trimEnd('/')

    /** "owner/repo/subpath" for display. */
    val location: String get() = "$owner/$repository" + if (subpath.isEmpty()) "" else "/$subpath"

    /** URL of a file in the repository on raw.githubusercontent.com. */
    fun rawUrl(path: String): String =
        "https://raw.githubusercontent.com/$owner/$repository/${encodePathComponent(branch)}/${encodePath(path)}"

    fun webUrl(path: String): String =
        "https://github.com/$owner/$repository/blob/${encodePathComponent(branch)}/${encodePath(path)}"

    companion object {
        fun make(
            name: String, owner: String, repository: String, branch: String = "main", subpath: String = "",
            format: DatabaseFormat = DatabaseFormat.FLIPPER, indexFile: String? = null,
            isBuiltIn: Boolean = false, isEnabled: Boolean = true, summary: String = "", id: String? = null,
        ): DatabaseSource {
            val cleanSubpath = subpath.trim('/')
            return DatabaseSource(
                id = id ?: "$owner/$repository@$branch/$cleanSubpath".lowercase(Locale.ROOT),
                name = name, owner = owner, repository = repository, branch = branch, subpath = cleanSubpath,
                format = format, indexFile = indexFile, isBuiltIn = isBuiltIn, isEnabled = isEnabled, summary = summary,
            )
        }

        val flipperIRDB = make(
            id = "flipper-irdb", name = "Flipper-IRDB", owner = "Lucaslhm", repository = "Flipper-IRDB",
            branch = "main", format = DatabaseFormat.FLIPPER, isBuiltIn = true,
            summary = "Community collection of Flipper Zero infrared files: TVs, ACs, audio, projectors, LED lights and more.",
        )

        val irdb = make(
            id = "probonopd-irdb", name = "IRDB", owner = "probonopd", repository = "irdb",
            branch = "master", subpath = "codes", format = DatabaseFormat.IRDB_CSV, indexFile = "codes/index", isBuiltIn = true,
            summary = "Thousands of protocol-level codes by manufacturer and device type, compiled from many sources.",
        )

        val builtIn = listOf(flipperIRDB, irdb)

        /** Characters left unescaped inside a single URL path component. */
        private const val UNRESERVED = "-._~!$&'()*+,:=@"

        fun encodePathComponent(component: String): String {
            val out = StringBuilder()
            for (byte in component.toByteArray(Charsets.UTF_8)) {
                val c = byte.toInt() and 0xFF
                val ch = c.toChar()
                if (c < 0x80 && (ch.isLetterOrDigit() || ch in UNRESERVED)) out.append(ch)
                else out.append('%').append("%02X".format(c))
            }
            return out.toString()
        }

        fun encodePath(path: String): String =
            path.split('/').filter { it.isNotEmpty() }.joinToString("/") { encodePathComponent(it) }

        /** Parses "owner/repo", "github.com/owner/repo" or a full GitHub URL. */
        fun parseRepository(text: String): Pair<String, String>? {
            var t = text.trim()
            for (prefix in listOf("https://", "http://", "www.", "github.com/")) {
                if (t.lowercase(Locale.ROOT).startsWith(prefix)) t = t.substring(prefix.length)
            }
            val parts = t.split('/').filter { it.isNotEmpty() }
            if (parts.size < 2) return null
            val owner = parts[0]
            val repo = parts[1].removeSuffix(".git")
            if (owner.isEmpty() || repo.isEmpty()) return null
            if (!owner.all { it.isLetterOrDigit() || it in "-_." } || !repo.all { it.isLetterOrDigit() || it in "-_." }) return null
            return owner to repo
        }
    }
}

/** One remote file in an online database. */
@Serializable
class DatabaseEntry(
    val sourceId: String,
    /** Path of the file inside the repository. */
    val path: String,
    val brand: String,
    val model: String,
    /** Category or device type as named by the database. */
    val categoryName: String,
    /** Sub-collection, e.g. "Pronto" or "IR Plus" for Flipper-IRDB's converted files. */
    val collection: String = "",
) {
    val id: String get() = "$sourceId:$path"

    @Transient
    val category: DeviceCategory = DeviceCategory.fromDatabaseName(categoryName).let {
        if (it == DeviceCategory.OTHER) DeviceCategory.fromDatabaseName(model) else it
    }

    @Transient val brandKey: String = brand.searchKey
    @Transient val modelKey: String = model.searchKey

    /** Precomputed normalised text for searching. */
    @Transient
    val searchKey: String = " " + listOf(brand, model, categoryName, collection, category.displayName).joinToString(" ").searchKey

    /** [searchKey] without spaces, so "aa5900600a" finds "AA59-00600A". */
    @Transient val compactKey: String = searchKey.replace(" ", "")

    /** Human-readable title such as "Samsung AA59-00600A". */
    val title: String get() = if (brand.isEmpty()) model else "$brand $model"

    /** Whether the file comes from a hand-curated folder rather than a bulk conversion. */
    val isCurated: Boolean get() = collection.isEmpty()

    override fun equals(other: Any?) = other is DatabaseEntry && other.id == id
    override fun hashCode() = id.hashCode()
    override fun toString() = "DatabaseEntry($id)"
}

/** Cached database index for one source. */
@Serializable
data class DatabaseIndexCache(
    val sourceId: String,
    val fetchedAtMillis: Long,
    val entries: List<DatabaseEntry>,
)
