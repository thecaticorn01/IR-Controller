package com.thecaticorn01.ircontroller.core.library

import com.thecaticorn01.ircontroller.core.model.IRRemote
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** JSON settings shared by the library file, index caches and network listings. */
val IRJson = Json {
    ignoreUnknownKeys = true
    // Library files always spell out every required field…
    encodeDefaults = true
    // …and leave out absent optionals instead of writing `null`.
    explicitNulls = false
    coerceInputValues = true
}

private val PrettyJson = Json(IRJson) {
    prettyPrint = true
    prettyPrintIndent = "  "
}

/**
 * On-disk representation of the user's remote library (`library.json`), also
 * used for backups so libraries can be moved between devices.
 */
@Serializable
data class LibraryArchive(
    val version: Int = CURRENT_VERSION,
    val remotes: List<IRRemote>,
) {
    fun encode(pretty: Boolean = true): String =
        (if (pretty) PrettyJson else IRJson).encodeToString(serializer(), this)

    companion object {
        const val CURRENT_VERSION = 1

        fun decode(text: String): LibraryArchive = IRJson.decodeFromString(serializer(), text)
    }
}
