package com.thecaticorn01.ircontroller.core.formats

import com.thecaticorn01.ircontroller.core.model.IRButton
import com.thecaticorn01.ircontroller.core.model.IRSignal
import com.thecaticorn01.ircontroller.core.model.ParsedSignal
import com.thecaticorn01.ircontroller.core.model.RawSignal
import com.thecaticorn01.ircontroller.core.protocols.IRProtocolRegistry
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Parser for the CSV files of the IRDB database (github.com/probonopd/irdb):
 * `functionname,protocol,device,subdevice,function`.
 */
object IRDBCSV {

    data class ParseResult(
        val buttons: List<IRButton>,
        /** Protocols present in the file that cannot be transmitted yet. */
        val unsupportedProtocols: Set<String>,
    )

    fun parse(text: String): ParseResult {
        val buttons = mutableListOf<IRButton>()
        val unsupported = sortedSetOf<String>()
        val lines = text.lineSequence().filter { it.isNotEmpty() }
        for ((index, rawLine) in lines.withIndex()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (index == 0 && line.lowercase(Locale.ROOT).startsWith("functionname")) continue
            // The function name may itself contain commas, so split from the right.
            val rawFields = line.split(',')
            if (rawFields.size < 5) continue
            val tail = rawFields.takeLast(4).map { it.trim() }
            val name = rawFields.dropLast(4).joinToString(",").trim(' ', '"')
            val protocolName = tail[0]
            val device = tail[1].toIntOrNull() ?: continue
            val subdevice = tail[2].toIntOrNull() ?: continue
            val function = tail[3].toIntOrNull() ?: continue
            if (device < 0 || function < 0) continue
            val canonical = IRProtocolRegistry.canonicalName(protocolName)
            if (canonical == null) {
                unsupported.add(protocolName)
                continue
            }
            val signal = ParsedSignal(
                protocolName = canonical,
                address = device.toLong(),
                command = function.toLong(),
                subdevice = if (subdevice >= 0) subdevice.toLong() else null,
            )
            buttons.add(IRButton(name = prettify(name), signal = IRSignal.Parsed(signal)))
        }
        return ParseResult(buttons, unsupported)
    }

    /** "KEY_VOLUMEUP" → "Volume Up", "POWER" → "Power". */
    fun prettify(name: String): String {
        var text = name
        if (text.uppercase(Locale.ROOT).startsWith("KEY_")) text = text.drop(4)
        val known = mapOf(
            "VOLUMEUP" to "Volume Up", "VOLUMEDOWN" to "Volume Down", "CHANNELUP" to "Channel Up",
            "CHANNELDOWN" to "Channel Down", "FASTFORWARD" to "Fast Forward", "PLAYPAUSE" to "Play/Pause",
        )
        known[text.uppercase(Locale.ROOT)]?.let { return it }
        val words = text.replace('_', ' ').split(' ').filter { it.isNotEmpty() }
        if (text != text.uppercase(Locale.ROOT)) return words.joinToString(" ")
        return words.joinToString(" ") { word ->
            word.take(1).uppercase(Locale.ROOT) + word.drop(1).lowercase(Locale.ROOT)
        }
    }
}

/** Imports a single code pasted by the user in one of several common text formats. */
object IRCodeImporter {

    enum class Format(val displayName: String) {
        PRONTO("Pronto Hex"),
        SENDIR("Global Caché sendir"),
        TIMINGS("Raw timings"),
    }

    fun parse(text: String, defaultFrequency: Int = 38_000): Pair<RawSignal, Format> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) throw IRFormatException.UnrecognizedCode()
        if (trimmed.lowercase(Locale.ROOT).startsWith("sendir")) {
            return parseSendIR(trimmed) to Format.SENDIR
        }
        val words = trimmed.split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
        if (words.size >= 6 && words.all { it.length == 4 && it.toIntOrNull(16) != null && !it.startsWith("-") && !it.startsWith("+") } && words[0] == "0000") {
            return parsePronto(words) to Format.PRONTO
        }
        val numbers = words.mapNotNull { it.replace("+", "").toIntOrNull() }
        if (numbers.size != words.size || numbers.size < 2) throw IRFormatException.UnrecognizedCode()
        return RawSignal(frequency = defaultFrequency, timings = numbers.map { abs(it) }).normalized() to Format.TIMINGS
    }

    /** Pronto "learned" format (0000 FFFF NNNN RRRR pairs...). */
    fun parsePronto(words: List<String>): RawSignal {
        val values = words.mapNotNull { it.toIntOrNull(16) }
        if (values.size < 4 || values[0] != 0 || values[1] <= 0) throw IRFormatException.UnrecognizedCode()
        val frequency = 1_000_000.0 / (values[1] * 0.241246)
        val period = 1_000_000.0 / frequency
        val once = values[2] * 2
        val repeating = values[3] * 2
        if (values.size < 4 + once + repeating || once + repeating <= 0) throw IRFormatException.UnrecognizedCode()
        val burst = values.drop(4).take(once + repeating).map { (it * period).roundToInt() }
        val timings = burst.take(once) + burst.takeLast(repeating)
        return RawSignal(frequency = frequency.roundToInt(), timings = timings).normalized()
    }

    /** Global Caché: sendir,<module:port>,<id>,<freq>,<repeat>,<offset>,<on>,<off>,... */
    fun parseSendIR(text: String): RawSignal {
        val fields = text.split(',').map { it.trim() }
        val frequency = fields.getOrNull(3)?.toIntOrNull()
        if (fields.size <= 8 || frequency == null || frequency <= 0) throw IRFormatException.UnrecognizedCode()
        val period = 1_000_000.0 / frequency
        val cycles = fields.drop(6).mapNotNull { it.toIntOrNull() }
        if (cycles.size < 2) throw IRFormatException.UnrecognizedCode()
        return RawSignal(frequency = frequency, timings = cycles.map { (it * period).roundToInt() }).normalized()
    }

    /** Renders a signal as Pronto hex, useful for sharing codes. */
    fun prontoHex(raw: RawSignal): String {
        val frequency = max(raw.frequency, 1)
        val code = (1_000_000.0 / (frequency * 0.241246)).roundToInt()
        val period = 1_000_000.0 / frequency
        val timings = raw.timings.toMutableList()
        if (timings.size % 2 == 1) timings.add(40_000)
        val words = mutableListOf(0, code, timings.size / 2, 0)
        words += timings.map { max(1, (it / period).roundToInt()) }
        return words.joinToString(" ") { String.format(Locale.ROOT, "%04X", min(it, 0xFFFF)) }
    }
}
