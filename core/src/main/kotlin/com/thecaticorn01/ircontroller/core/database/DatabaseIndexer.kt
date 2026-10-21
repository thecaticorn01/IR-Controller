package com.thecaticorn01.ircontroller.core.database

import com.thecaticorn01.ircontroller.core.model.DeviceCategory
import java.util.Locale

/** Turns repository file listings into searchable [DatabaseEntry] values. */
object DatabaseIndexer {

    /** Builds entries from repository-relative file paths. */
    fun entries(paths: List<String>, source: DatabaseSource): List<DatabaseEntry> {
        val fileExtension = source.format.fileExtension
        val prefix = if (source.subpath.isEmpty()) "" else source.subpath + "/"
        val result = ArrayList<DatabaseEntry>(paths.size)
        for (path in paths) {
            if (!path.lowercase(Locale.ROOT).endsWith(fileExtension) || !path.startsWith(prefix)) continue
            val relative = path.substring(prefix.length)
            val entry = when (source.format) {
                DatabaseFormat.FLIPPER -> flipperEntry(path, relative, source)
                DatabaseFormat.IRDB_CSV -> irdbEntry(path, relative, source)
            }
            if (entry != null) result.add(entry)
        }
        return result.sortedWith(compareBy<DatabaseEntry> { it.brandKey }.thenBy { it.model })
    }

    fun display(component: String): String {
        var text = component
        for (ext in listOf(".ir", ".csv", ".IR", ".CSV")) {
            if (text.endsWith(ext)) text = text.dropLast(ext.length)
        }
        return text.replace('_', ' ').trim()
    }

    private fun flipperEntry(path: String, relative: String, source: DatabaseSource): DatabaseEntry? {
        val parts = relative.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        // Hidden folders and repository plumbing.
        if (parts.any { it.startsWith(".") }) return null

        if (parts[0] == "_Converted_") {
            // _Converted_/<Collection>/<Letter>/<Brand>/<Model…>.ir
            if (parts.size < 5) return null
            val collection = display(parts[1])
            val brand = display(parts[3])
            val rest = parts.drop(4).map(::display)
            if (parts[1].uppercase(Locale.ROOT) == "CSV" && rest.size >= 2) {
                // Converted IRDB files: <DeviceType>/<device,subdevice>.ir
                val deviceType = rest[0].replace("Unknown ", "")
                return DatabaseEntry(
                    source.id, path, brand,
                    model = "$deviceType (${rest.drop(1).joinToString(" ")})",
                    categoryName = rest[0], collection = collection,
                )
            }
            return DatabaseEntry(source.id, path, brand, model = rest.joinToString(" / "), categoryName = "", collection = collection)
        }

        return when (parts.size) {
            1 -> DatabaseEntry(source.id, path, brand = "", model = display(parts[0]), categoryName = "")
            2 -> {
                // Either <Brand>/<Model> or <Category>/<Model>; treat known categories specially.
                val first = display(parts[0])
                if (DeviceCategory.fromDatabaseName(first) != DeviceCategory.OTHER) {
                    DatabaseEntry(source.id, path, brand = "", model = display(parts[1]), categoryName = first)
                } else {
                    DatabaseEntry(source.id, path, brand = first, model = display(parts[1]), categoryName = "")
                }
            }
            else -> {
                val brand = display(parts[1])
                var model = parts.drop(2).map(::display).joinToString(" / ")
                // Curated files are usually named "<Brand>_<Model>.ir"; drop the redundant brand.
                if (model.lowercase(Locale.ROOT).startsWith(brand.lowercase(Locale.ROOT) + " ") && model.length > brand.length + 1) {
                    model = model.substring(brand.length + 1)
                }
                DatabaseEntry(source.id, path, brand, model, categoryName = display(parts[0]))
            }
        }
    }

    private fun irdbEntry(path: String, relative: String, source: DatabaseSource): DatabaseEntry? {
        // <Brand>/<DeviceType>/<device>,<subdevice>.csv
        val parts = relative.split('/').filter { it.isNotEmpty() }
        if (parts.size < 3) return null
        val brand = display(parts[0])
        val deviceType = display(parts[1])
        val codes = display(parts.drop(2).joinToString("/")).replace(",", ", ")
        val model = deviceType.replace("Unknown ", "")
        return DatabaseEntry(source.id, path, brand, model = "$model ($codes)", categoryName = deviceType)
    }
}
