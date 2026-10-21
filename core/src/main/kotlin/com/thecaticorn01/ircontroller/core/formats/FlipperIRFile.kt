package com.thecaticorn01.ircontroller.core.formats

import com.thecaticorn01.ircontroller.core.model.IRButton
import com.thecaticorn01.ircontroller.core.model.IRSignal
import com.thecaticorn01.ircontroller.core.model.ParsedSignal
import com.thecaticorn01.ircontroller.core.model.RawSignal
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

sealed class IRFormatException(message: String) : Exception(message) {
    class NotAnIRFile : IRFormatException("The file is not a Flipper Zero infrared (.ir) file.")
    class MalformedSignal(val name: String, val reason: String) :
        IRFormatException("The signal “$name” is malformed: $reason.")
    class NoSignals : IRFormatException("The file does not contain any infrared signals.")
    class UnrecognizedCode : IRFormatException(
        "The code could not be recognised. Paste Pronto hex, Global Caché sendir, or a list of mark/space timings in microseconds.",
    )
}

/**
 * Reader and writer for Flipper Zero `.ir` signal files, the format used by
 * the Flipper-IRDB database.
 */
object FlipperIRFile {

    data class Skipped(val name: String, val reason: String)

    data class ParseResult(
        val buttons: List<IRButton>,
        /** Signals that could not be parsed, with the reason. */
        val skipped: List<Skipped>,
    )

    /** Parses the textual contents of a `.ir` file. */
    fun parse(text: String): ParseResult {
        val buttons = mutableListOf<IRButton>()
        val skipped = mutableListOf<Skipped>()
        var sawHeader = false
        val block = mutableMapOf<String, String>()

        fun flush() {
            val name = block["name"]
            if (name != null) {
                try {
                    buttons.add(IRButton(name = name, signal = signal(block, name)))
                } catch (e: IRFormatException.MalformedSignal) {
                    skipped.add(Skipped(name, e.reason))
                } catch (e: Exception) {
                    skipped.add(Skipped(name, e.message ?: e.toString()))
                }
            }
            block.clear()
        }

        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#")) { flush(); continue }
            val colon = line.indexOf(':')
            if (colon < 0) continue
            val key = line.substring(0, colon).trim().lowercase(Locale.ROOT)
            val value = line.substring(colon + 1).trim()
            when (key) {
                "filetype", "version" -> sawHeader = true
                "name" -> {
                    flush()
                    block["name"] = value
                }
                else -> block[key] = block[key]?.let { "$it $value" } ?: value
            }
        }
        flush()

        if (!sawHeader && buttons.isEmpty()) throw IRFormatException.NotAnIRFile()
        if (buttons.isEmpty() && skipped.isEmpty()) throw IRFormatException.NoSignals()
        return ParseResult(buttons, skipped)
    }

    private fun signal(block: Map<String, String>, name: String): IRSignal {
        return when (block["type"]?.lowercase(Locale.ROOT)) {
            "parsed" -> {
                val proto = block["protocol"]
                if (proto.isNullOrEmpty()) throw IRFormatException.MalformedSignal(name, "missing protocol")
                val address = block["address"]?.let(::littleEndianHex)
                val command = block["command"]?.let(::littleEndianHex)
                if (address == null || command == null) {
                    throw IRFormatException.MalformedSignal(name, "invalid address or command")
                }
                IRSignal.Parsed(ParsedSignal(proto, address, command))
            }
            "raw" -> {
                val frequency = block["frequency"]?.toDoubleOrNull()?.roundToInt() ?: 38_000
                val duty = block["duty_cycle"]?.toDoubleOrNull() ?: 0.33
                val data = block["data"] ?: throw IRFormatException.MalformedSignal(name, "missing data")
                val timings = data.split(' ', ',', '\t').mapNotNull { it.toIntOrNull() }
                if (timings.isEmpty()) throw IRFormatException.MalformedSignal(name, "empty data")
                IRSignal.Raw(RawSignal(frequency, duty, timings.map { abs(it) }))
            }
            else -> throw IRFormatException.MalformedSignal(name, "unknown signal type")
        }
    }

    /** Flipper stores 32-bit values as four little-endian hex bytes: "07 00 00 00". */
    fun littleEndianHex(text: String): Long? {
        val parts = text.split(' ').filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.size > 4) {
            // Some hand-written files use "0x07" style values.
            val trimmed = text.lowercase(Locale.ROOT).replace("0x", "")
            return trimmed.toLongOrNull(16)?.takeIf { it in 0..0xFFFF_FFFFL }
        }
        var value = 0L
        for ((index, part) in parts.withIndex()) {
            if (part.length > 2) return null
            val byte = part.toIntOrNull(16) ?: return null
            if (byte < 0) return null
            value = value or (byte.toLong() shl (8 * index))
        }
        return value
    }

    fun littleEndianHexString(value: Long): String =
        (0 until 4).joinToString(" ") { String.format(Locale.ROOT, "%02X", (value ushr (8 * it)) and 0xFF) }

    /** Protocol names Flipper firmware understands natively in `parsed` signals. */
    val nativeProtocols = setOf(
        "NEC", "NECext", "NEC42", "NEC42ext", "Samsung32", "RC5", "RC5X", "RC6",
        "SIRC", "SIRC15", "SIRC20", "Kaseikyo", "RCA", "Pioneer",
    )

    /**
     * Serialises buttons to `.ir` text. Signals in protocols Flipper does not
     * understand are rendered to raw timings so the file stays usable.
     */
    fun serialize(buttons: List<IRButton>): String = buildString {
        append("Filetype: IR signals file\nVersion: 1\n")
        for (button in buttons) {
            val signal = button.signal
            if (signal is IRSignal.Parsed && signal.signal.subdevice == null && signal.signal.protocolName in nativeProtocols) {
                append("# \nname: ${button.name.replace("\n", " ")}\n")
                append("type: parsed\nprotocol: ${signal.signal.protocolName}\n")
                append("address: ${littleEndianHexString(signal.signal.address)}\n")
                append("command: ${littleEndianHexString(signal.signal.command)}\n")
            } else {
                val raw = runCatching { signal.rendered(toggle = false) }.getOrNull() ?: continue
                append("# \nname: ${button.name.replace("\n", " ")}\n")
                append("type: raw\nfrequency: ${raw.frequency}\n")
                append("duty_cycle: ${String.format(Locale.ROOT, "%.6f", raw.dutyCycle)}\n")
                append("data: ${raw.timings.joinToString(" ")}\n")
            }
        }
    }
}
