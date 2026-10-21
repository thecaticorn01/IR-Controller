package com.thecaticorn01.ircontroller.core.protocols

import com.thecaticorn01.ircontroller.core.model.ParsedSignal
import com.thecaticorn01.ircontroller.core.model.RawSignal
import java.util.Locale
import kotlin.math.max

sealed class IRProtocolException(message: String) : Exception(message) {
    class UnsupportedProtocol(val protocolName: String) :
        IRProtocolException("The “$protocolName” infrared protocol is not supported yet.") {
        override fun equals(other: Any?) = other is UnsupportedProtocol && other.protocolName == protocolName
        override fun hashCode() = protocolName.hashCode()
    }

    class EmptySignal : IRProtocolException("The signal contains no timing data.")
}

/** Describes a protocol the app can generate. */
data class IRProtocolInfo(
    val name: String,
    val frequency: Int,
    val addressBits: Int,
    val commandBits: Int,
    /** Number of frames sent for a single button press. */
    val minimumFrames: Int,
    val summary: String,
)

/**
 * Encodes protocol-level codes (Flipper Zero protocols and the common IRP
 * protocols used by the IRDB database) into raw mark/space timings.
 */
object IRProtocolRegistry {

    /** All protocols supported for encoding, in display order. */
    val protocols: List<IRProtocolInfo> = listOf(
        IRProtocolInfo("NEC", 38_000, 8, 8, 1, "NEC (8-bit address, 8-bit command)"),
        IRProtocolInfo("NECext", 38_000, 16, 16, 1, "Extended NEC (16-bit address and command)"),
        IRProtocolInfo("NEC42", 38_000, 13, 8, 1, "NEC 42-bit"),
        IRProtocolInfo("NEC42ext", 38_000, 26, 16, 1, "Extended NEC 42-bit"),
        IRProtocolInfo("NEC2", 38_000, 8, 8, 1, "NEC with full-frame repeats"),
        IRProtocolInfo("NECx", 38_000, 8, 8, 1, "NECx (Samsung-style 4.5 ms header)"),
        IRProtocolInfo("NEC-f16", 38_000, 8, 16, 1, "NEC with 16-bit function"),
        IRProtocolInfo("Samsung32", 38_000, 8, 8, 1, "Samsung 32-bit"),
        IRProtocolInfo("RC5", 36_000, 5, 7, 1, "Philips RC5"),
        IRProtocolInfo("RC5X", 36_000, 5, 6, 1, "Philips RC5 extended"),
        IRProtocolInfo("RC6", 36_000, 8, 8, 1, "Philips RC6 mode 0"),
        IRProtocolInfo("MCE", 36_000, 7, 8, 1, "Windows Media Center (RC6-6-32)"),
        IRProtocolInfo("SIRC", 40_000, 5, 7, 3, "Sony SIRC 12-bit"),
        IRProtocolInfo("SIRC15", 40_000, 8, 7, 3, "Sony SIRC 15-bit"),
        IRProtocolInfo("SIRC20", 40_000, 13, 7, 3, "Sony SIRC 20-bit"),
        IRProtocolInfo("Kaseikyo", 37_000, 26, 10, 1, "Kaseikyo (Flipper layout)"),
        IRProtocolInfo("Panasonic", 37_000, 8, 8, 1, "Panasonic (Kaseikyo vendor 0x2002)"),
        IRProtocolInfo("RCA", 38_000, 4, 8, 1, "RCA (Flipper layout)"),
        IRProtocolInfo("RCA-IRP", 58_000, 4, 8, 1, "RCA 58 kHz (IRP layout)"),
        IRProtocolInfo("RCA-38", 38_700, 4, 8, 1, "RCA 38 kHz (IRP layout)"),
        IRProtocolInfo("Pioneer", 40_000, 8, 8, 2, "Pioneer"),
        IRProtocolInfo("JVC", 38_000, 8, 8, 1, "JVC"),
        IRProtocolInfo("Mitsubishi", 32_600, 8, 8, 1, "Mitsubishi"),
        IRProtocolInfo("Aiwa", 38_000, 8, 8, 1, "Aiwa"),
        IRProtocolInfo("Denon", 38_000, 5, 8, 1, "Denon"),
        IRProtocolInfo("Sharp", 38_000, 5, 8, 1, "Sharp"),
    )

    private val infoByKey: Map<String, IRProtocolInfo> = protocols.associateBy { it.name.lowercase(Locale.ROOT) }

    /** Maps alternative protocol names (IRDB / IrScrutinizer spellings) to canonical names. */
    private val aliases = mapOf(
        "nec1" to "NEC",
        "nec1-rnc" to "NEC",
        "necx1" to "NECx",
        "necx2" to "NECx",
        "nec1-f16" to "NEC-f16",
        "sony12" to "SIRC",
        "sony15" to "SIRC15",
        "sony20" to "SIRC20",
        "samsung" to "Samsung32",
        "rc5x" to "RC5X",
        "rc6-0-16" to "RC6",
        "rc6-6-32" to "MCE",
    )

    /** Returns the canonical protocol name, or `null` if the protocol is unknown. */
    fun canonicalName(name: String): String? {
        var key = name.trim().lowercase(Locale.ROOT)
        // IRDB writes variants like "JVC{2}" or "Sharp{1}".
        val brace = key.indexOf('{')
        if (brace >= 0) key = key.substring(0, brace)
        aliases[key]?.let { return it }
        return infoByKey[key]?.name
    }

    fun info(name: String): IRProtocolInfo? = canonicalName(name)?.let { infoByKey[it.lowercase(Locale.ROOT)] }

    fun isSupported(name: String): Boolean = canonicalName(name) != null

    /**
     * Toggle bit state shared by RC5/RC6/MCE so consecutive presses of the same
     * button are recognised as separate key presses by the receiver.
     */
    private var toggleState = false

    @Synchronized
    private fun nextToggle(): Long {
        toggleState = !toggleState
        return if (toggleState) 1 else 0
    }

    private val toggledProtocols = setOf("RC5", "RC5X", "RC6", "MCE")

    /**
     * Encodes a parsed signal into raw timings.
     * @param extraRepeats additional frames appended after the protocol's minimum
     *   (as if the button were held a little longer).
     */
    fun encode(signal: ParsedSignal, extraRepeats: Int = 0, toggle: Boolean? = null): RawSignal {
        val name = canonicalName(signal.protocolName)
            ?: throw IRProtocolException.UnsupportedProtocol(signal.protocolName)
        val info = infoByKey[name.lowercase(Locale.ROOT)]
            ?: throw IRProtocolException.UnsupportedProtocol(signal.protocolName)
        val a = signal.address and 0xFFFF_FFFFL
        val c = signal.command and 0xFFFF_FFFFL
        val s = signal.subdevice?.let { it and 0xFFFF_FFFFL }
        val repeats = max(0, extraRepeats)
        val t: Long = when {
            toggle != null -> if (toggle) 1 else 0
            name in toggledProtocols -> nextToggle()
            else -> 0
        }
        val b = PulseBuilder()

        when (name) {
            "NEC", "NEC2", "NECx", "NEC-f16" -> {
                val header = if (name == "NECx") 4_500 to 4_500 else 9_000 to 4_500
                val sub = s ?: if (name == "NECx") (a and 0xFF) else (a.inv() and 0xFF)
                var data = (a and 0xFF) or ((sub and 0xFF) shl 8)
                data = if (name == "NEC-f16") {
                    data or ((c and 0xFFFF) shl 16)
                } else {
                    data or ((c and 0xFF) shl 16) or ((c.inv() and 0xFF) shl 24)
                }
                val frames = if (name == "NEC") 1 else 1 + repeats
                repeat(frames) { necFrame(b, header, data, 32) }
                if (name == "NEC") repeat(repeats) { necRepeat(b) }
            }
            "NECext" -> {
                val data = (a and 0xFFFF) or ((c and 0xFFFF) shl 16)
                necFrame(b, 9_000 to 4_500, data, 32)
                repeat(repeats) { necRepeat(b) }
            }
            "NEC42" -> {
                val data = (a and 0x1FFF) or ((a.inv() and 0x1FFF) shl 13) or
                    ((c and 0xFF) shl 26) or ((c.inv() and 0xFF) shl 34)
                necFrame(b, 9_000 to 4_500, data, 42)
                repeat(repeats) { necRepeat(b) }
            }
            "NEC42ext" -> {
                val data = (a and 0x3FF_FFFF) or ((c and 0xFFFF) shl 26)
                necFrame(b, 9_000 to 4_500, data, 42)
                repeat(repeats) { necRepeat(b) }
            }

            "Samsung32" -> {
                val data = (a and 0xFF) or (((s ?: a) and 0xFF) shl 8) or
                    ((c and 0xFF) shl 16) or ((c.inv() and 0xFF) shl 24)
                repeat(repeats + 1) {
                    b.beginFrame()
                    b.mark(4_500); b.space(4_500)
                    b.bits(data, 32, BitOrder.LSB_FIRST, BitEncoding.pulseDistance(550, 550, 1_650))
                    b.mark(550)
                    b.padFrame(108_000, minimumGap = 46_000)
                }
            }

            "RC5", "RC5X" -> {
                val command = if (name == "RC5X") (c and 0x3F) or 0x40 else c and 0x7F
                val field: Long = if ((command and 0x40) == 0L) 1 else 0
                val data = (1L shl 13) or (field shl 12) or (t shl 11) or ((a and 0x1F) shl 6) or (command and 0x3F)
                val manchester = BitEncoding(intArrayOf(889, -889), intArrayOf(-889, 889))
                repeat(repeats + 1) {
                    b.beginFrame()
                    b.bits(data, 14, BitOrder.MSB_FIRST, manchester)
                    b.padFrame(113_778, minimumGap = 2_700)
                }
            }

            "RC6", "MCE" -> {
                val unit = 444
                val bit = BitEncoding(intArrayOf(-unit, unit), intArrayOf(unit, -unit))
                val trailer = BitEncoding(intArrayOf(-2 * unit, 2 * unit), intArrayOf(2 * unit, -2 * unit))
                repeat(repeats + 1) {
                    b.beginFrame()
                    b.mark(6 * unit); b.space(2 * unit)
                    b.bits(1, 1, BitOrder.MSB_FIRST, bit)
                    if (name == "RC6") {
                        b.bits(0, 3, BitOrder.MSB_FIRST, bit)
                        b.bits(t, 1, BitOrder.MSB_FIRST, trailer)
                        b.bits(a and 0xFF, 8, BitOrder.MSB_FIRST, bit)
                        b.bits(c and 0xFF, 8, BitOrder.MSB_FIRST, bit)
                    } else {
                        // MCE: mode 6, trailer 0, OEM1 = 0x80, OEM2 = subdevice (default 0x0F).
                        b.bits(6, 3, BitOrder.MSB_FIRST, bit)
                        b.bits(0, 1, BitOrder.MSB_FIRST, trailer)
                        b.bits(0x80, 8, BitOrder.MSB_FIRST, bit)
                        b.bits(s ?: 0x0F, 8, BitOrder.MSB_FIRST, bit)
                        b.bits(t, 1, BitOrder.MSB_FIRST, bit)
                        b.bits(a and 0x7F, 7, BitOrder.MSB_FIRST, bit)
                        b.bits(c and 0xFF, 8, BitOrder.MSB_FIRST, bit)
                    }
                    b.padFrame(107_000, minimumGap = 2_700)
                }
            }

            "SIRC", "SIRC15", "SIRC20" -> {
                val addressBits = when (name) { "SIRC" -> 5; "SIRC15" -> 8; else -> 13 }
                var address = a
                if (name == "SIRC20" && s != null) address = (a and 0x1F) or ((s and 0xFF) shl 5)
                val data = (c and 0x7F) or ((address and ((1L shl addressBits) - 1)) shl 7)
                repeat(info.minimumFrames + repeats) {
                    b.beginFrame()
                    b.mark(2_400); b.space(600)
                    b.bits(data, 7 + addressBits, BitOrder.LSB_FIRST, BitEncoding.pulseWidth(600, 1_200, 600))
                    b.padFrame(45_000)
                }
            }

            "Kaseikyo", "Panasonic" -> {
                val bytes: List<Long> = if (name == "Kaseikyo") {
                    val id = (a ushr 24) and 3
                    val vendor = (a ushr 8) and 0xFFFF
                    val genre1 = (a ushr 4) and 0xF
                    val genre2 = a and 0xF
                    val v0 = vendor and 0xFF
                    val v1 = vendor ushr 8
                    var parity = v0 xor v1
                    parity = (parity and 0xF) xor (parity ushr 4)
                    val d2 = (parity and 0xF) or (genre1 shl 4)
                    val d3 = (genre2 and 0xF) or ((c and 0xF) shl 4)
                    val d4 = (id shl 6) or ((c ushr 4) and 0x3F)
                    listOf(v0, v1, d2, d3, d4, d2 xor d3 xor d4)
                } else {
                    val d = a and 0xFF
                    val sub = (s ?: 0) and 0xFF
                    val f = c and 0xFF
                    listOf(0x02, 0x20, d, sub, f, d xor sub xor f)
                }
                val unit = 432
                repeat(repeats + 1) {
                    b.mark(8 * unit); b.space(4 * unit)
                    for (byte in bytes) {
                        b.bits(byte, 8, BitOrder.LSB_FIRST, BitEncoding.pulseDistance(unit, unit, 3 * unit))
                    }
                    b.mark(unit)
                    b.space(173 * unit)
                }
            }

            "RCA" -> {
                val data = (a and 0xF) or ((c and 0xFF) shl 4) or ((a.inv() and 0xF) shl 12) or ((c.inv() and 0xFF) shl 16)
                repeat(repeats + 1) {
                    b.mark(4_000); b.space(4_000)
                    b.bits(data, 24, BitOrder.LSB_FIRST, BitEncoding.pulseDistance(500, 1_000, 2_000))
                    b.mark(500)
                    b.space(8_000)
                }
            }
            "RCA-IRP", "RCA-38" -> {
                val unit = 460
                val data = ((a and 0xF) shl 20) or ((c and 0xFF) shl 12) or ((a.inv() and 0xF) shl 8) or (c.inv() and 0xFF)
                repeat(repeats + 1) {
                    b.mark(8 * unit); b.space(8 * unit)
                    b.bits(data, 24, BitOrder.MSB_FIRST, BitEncoding.pulseDistance(unit, 2 * unit, 4 * unit))
                    b.mark(unit)
                    b.space(16 * unit)
                }
            }

            "Pioneer" -> {
                val sub = s ?: (a.inv() and 0xFF)
                val data = (a and 0xFF) or ((sub and 0xFF) shl 8) or ((c and 0xFF) shl 16) or ((c.inv() and 0xFF) shl 24)
                repeat(info.minimumFrames + repeats) {
                    b.mark(8_500); b.space(4_225)
                    b.bits(data, 32, BitOrder.LSB_FIRST, BitEncoding.pulseDistance(500, 500, 1_500))
                    b.mark(500)
                    b.space(26_000)
                }
            }

            "JVC" -> {
                val data = (a and 0xFF) or ((c and 0xFF) shl 8)
                for (frame in 0..repeats) {
                    b.beginFrame()
                    if (frame == 0) { b.mark(8_400); b.space(4_200) }
                    b.bits(data, 16, BitOrder.LSB_FIRST, BitEncoding.pulseDistance(525, 525, 1_575))
                    b.mark(525)
                    b.padFrame(59_080, minimumGap = 10_000)
                }
            }

            "Mitsubishi" -> {
                val data = (a and 0xFF) or ((c and 0xFF) shl 8)
                repeat(repeats + 1) {
                    b.bits(data, 16, BitOrder.LSB_FIRST, BitEncoding.pulseDistance(300, 900, 2_100))
                    b.mark(300)
                    b.space(24_000)
                }
            }

            "Aiwa" -> {
                val sub = (s ?: 0) and 0x1F
                val d = a and 0xFF
                val f = c and 0xFF
                var data = d or (sub shl 8) or ((d.inv() and 0xFF) shl 13) or ((sub.inv() and 0x1F) shl 21)
                data = data or (f shl 26) or ((f.inv() and 0xFF) shl 34)
                b.mark(8_800); b.space(4_400)
                b.bits(data, 42, BitOrder.LSB_FIRST, BitEncoding.pulseDistance(550, 550, 1_650))
                b.mark(550); b.space(23_100)
                repeat(repeats) {
                    b.mark(8_800); b.space(4_400); b.mark(550); b.space(90_750)
                }
            }

            "Denon", "Sharp" -> {
                val unit = 264
                val encoding = BitEncoding.pulseDistance(unit, 3 * unit, 7 * unit)
                val (first, second) = if (name == "Denon") 0L to 3L else 1L to 2L
                repeat(repeats + 1) {
                    b.bits((a and 0x1F) or ((c and 0xFF) shl 5) or (first shl 13), 15, BitOrder.LSB_FIRST, encoding)
                    b.mark(unit); b.space(165 * unit)
                    b.bits((a and 0x1F) or ((c.inv() and 0xFF) shl 5) or (second shl 13), 15, BitOrder.LSB_FIRST, encoding)
                    b.mark(unit); b.space(165 * unit)
                }
            }

            else -> throw IRProtocolException.UnsupportedProtocol(signal.protocolName)
        }

        if (b.timings.isEmpty()) throw IRProtocolException.EmptySignal()
        return RawSignal(frequency = info.frequency, dutyCycle = 0.33, timings = b.timings.toList())
    }

    private fun necFrame(b: PulseBuilder, header: Pair<Int, Int>, data: Long, bits: Int) {
        b.beginFrame()
        b.mark(header.first); b.space(header.second)
        b.bits(data, bits, BitOrder.LSB_FIRST, BitEncoding.pulseDistance(560, 560, 1_690))
        b.mark(560)
        b.padFrame(108_000, minimumGap = 10_000)
    }

    private fun necRepeat(b: PulseBuilder) {
        b.beginFrame()
        b.mark(9_000); b.space(2_250); b.mark(560)
        b.padFrame(108_000)
    }
}
