package com.thecaticorn01.ircontroller.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.text.Normalizer
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID

/** New identifier: an upper-case UUID string. */
fun newID(): String = UUID.randomUUID().toString().uppercase(Locale.ROOT)

/** A single button of a remote control. */
@Serializable
data class IRButton(
    val id: String = newID(),
    val name: String,
    val signal: IRSignal,
) {
    /** The semantic role inferred from the button name. */
    val role: RemoteButtonRole get() = RemoteButtonRole.of(name)

    /** Button name with Flipper-style underscores turned into spaces. */
    val displayName: String get() = name.replace('_', ' ')
}

/** Where an imported remote came from. */
@Serializable
data class RemoteOrigin(
    @SerialName("sourceID") val sourceId: String,
    val sourceName: String,
    val path: String,
    val url: String? = null,
)

/** A remote control in the user's library. */
@Serializable
data class IRRemote(
    val id: String = newID(),
    val name: String,
    val brand: String = "",
    val model: String = "",
    val category: DeviceCategory = DeviceCategory.OTHER,
    val buttons: List<IRButton> = emptyList(),
    val isFavorite: Boolean = false,
    val notes: String = "",
    val origin: RemoteOrigin? = null,
    @Serializable(with = InstantSerializer::class) val createdAt: Instant = now(),
    @Serializable(with = InstantSerializer::class) val modifiedAt: Instant = now(),
) {
    fun button(named: String): IRButton? {
        val key = named.searchKey
        return buttons.firstOrNull { it.name.searchKey == key }
    }

    /** The best candidate for a power button, used by power scanning. */
    val powerButton: IRButton?
        get() = buttons.firstOrNull { it.role.kind == RemoteButtonRole.Kind.POWER }
            ?: buttons.firstOrNull { it.role.kind == RemoteButtonRole.Kind.POWER_ON }

    /** "Brand Model", omitting empty parts. */
    val brandAndModel: String get() = listOf(brand, model).filter { it.isNotBlank() }.joinToString(" ")

    companion object {
        /** Current time truncated to whole seconds, matching ISO 8601 round trips. */
        fun now(): Instant = Instant.now().truncatedTo(ChronoUnit.SECONDS)
    }
}

/** ISO 8601 dates without fractional seconds. */
object InstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Instant) =
        encoder.encodeString(DateTimeFormatter.ISO_INSTANT.format(value.truncatedTo(ChronoUnit.SECONDS)))
    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}

/** Broad device categories used for icons, filtering and learning templates. */
@Serializable
enum class DeviceCategory(val displayName: String) {
    @SerialName("tv") TV("TV"),
    @SerialName("projector") PROJECTOR("Projector"),
    @SerialName("audio") AUDIO("Audio Receiver"),
    @SerialName("soundbar") SOUNDBAR("Soundbar"),
    @SerialName("speaker") SPEAKER("Speaker"),
    @SerialName("streaming") STREAMING("Streaming Device"),
    @SerialName("cableBox") CABLE_BOX("Cable / Satellite Box"),
    @SerialName("mediaPlayer") MEDIA_PLAYER("Disc / Media Player"),
    @SerialName("airConditioner") AIR_CONDITIONER("Air Conditioner"),
    @SerialName("fan") FAN("Fan"),
    @SerialName("heater") HEATER("Heater"),
    @SerialName("lighting") LIGHTING("Lighting"),
    @SerialName("camera") CAMERA("Camera"),
    @SerialName("toy") TOY("Toy"),
    @SerialName("other") OTHER("Other");

    companion object {
        /**
         * Maps a database folder or device-type name (e.g. "TVs",
         * "Audio_and_Video_Receivers", "Video Projector") to a category.
         */
        fun fromDatabaseName(databaseName: String): DeviceCategory {
            val key = databaseName.searchKey
            fun has(vararg words: String) = words.any { key.contains(it) }
            return when {
                has("soundbar", "sound bar") -> SOUNDBAR
                has("projector", "beamer") -> PROJECTOR
                has("receiver", "amplifier", "avr") -> AUDIO
                has("streaming", "apple tv", "appletv", "roku", "chromecast", "fire tv", "firetv", "media center", "htpc", "kodi") -> STREAMING
                has("cable", "satellite", "set top", "settop", "dvb", "stb", "decoder", "freeview", "iptv", "tivo", "tv tuner", "tuner") -> CABLE_BOX
                has("air conditioner", "aircon", "hvac", "climate", "acs") || key == "ac" || key.startsWith("ac ") -> AIR_CONDITIONER
                has("fan", "purifier", "humidifier") -> FAN
                has("heater", "fireplace") -> HEATER
                has("camera", "cctv") -> CAMERA
                has("tv", "television", "monitor", "display", "plasma", "screen") -> TV
                has("blu ray", "bluray", "dvd", "vcr", "laserdisc", "disc player", "video", "multimedia", "media player") -> MEDIA_PLAYER
                has("speaker", "subwoofer") -> SPEAKER
                has("audio", "hifi", "hi fi", "stereo", "cd player", "minidisc", "tape", "radio", "music") -> AUDIO
                has("led", "light", "lamp", "bulb") -> LIGHTING
                has("toy", "robot", "helicopter") -> TOY
                else -> OTHER
            }
        }
    }
}

private val combiningMarks = Regex("\\p{Mn}+")

/** Lowercased, diacritic-folded, punctuation-normalised form used for matching. */
val String.searchKey: String
    get() {
        val folded = combiningMarks.replace(Normalizer.normalize(this, Normalizer.Form.NFD), "").lowercase(Locale.ROOT)
        val out = StringBuilder(folded.length)
        var lastWasSpace = true
        var index = 0
        while (index < folded.length) {
            val codePoint = folded.codePointAt(index)
            if (Character.isLetterOrDigit(codePoint)) {
                out.appendCodePoint(codePoint)
                lastWasSpace = false
            } else if (!lastWasSpace) {
                out.append(' ')
                lastWasSpace = true
            }
            index += Character.charCount(codePoint)
        }
        if (out.endsWith(" ")) out.setLength(out.length - 1)
        return out.toString()
    }
