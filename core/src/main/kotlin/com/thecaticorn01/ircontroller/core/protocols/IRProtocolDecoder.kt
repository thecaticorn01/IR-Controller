package com.thecaticorn01.ircontroller.core.protocols

import com.thecaticorn01.ircontroller.core.model.ParsedSignal
import com.thecaticorn01.ircontroller.core.model.RawSignal
import kotlin.math.roundToInt

/**
 * Best-effort identification of common protocols in captured raw waveforms.
 * Used to label learned buttons (e.g. "Detected NEC 0x04/0x08").
 */
object IRProtocolDecoder {

    fun decode(raw: RawSignal): ParsedSignal? {
        val t = raw.normalized().timings
        if (t.size < 10) return null
        return decodeNECFamily(t) ?: decodeSIRC(t) ?: decodeRC5(t)
    }

    private fun near(value: Int, target: Int, tolerance: Double = 0.3): Boolean {
        val delta = target * tolerance
        return value >= target - delta && value <= target + delta
    }

    /** Reads pulse-distance bits starting at [index] (which must point to a mark). */
    private fun readPulseDistance(t: List<Int>, index: Int, count: Int, threshold: Int): Long? {
        var value = 0L
        var i = index
        for (bit in 0 until count) {
            if (i + 1 >= t.size) return null
            val mark = t[i]
            val space = t[i + 1]
            if (mark >= threshold || space >= threshold * 3) return null
            if (space > threshold) value = value or (1L shl bit)
            i += 2
        }
        return value
    }

    private fun decodeNECFamily(t: List<Int>): ParsedSignal? {
        val isNEC = near(t[0], 9_000, 0.2) && near(t[1], 4_500, 0.25)
        val isSamsung = near(t[0], 4_500, 0.2) && near(t[1], 4_500, 0.25)
        if (!isNEC && !isSamsung) return null

        // NEC42 frames have 42 data bits; detect by counting short marks before the gap.
        var bitCount = 0
        var i = 2
        while (i + 1 < t.size && t[i] < 1_200 && t[i + 1] < 2_500) { bitCount++; i += 2 }

        if (isNEC && bitCount >= 42) {
            val data = readPulseDistance(t, 2, 42, 1_100)
            if (data != null) {
                val address = data and 0x1FFF
                val addressInverse = (data ushr 13) and 0x1FFF
                val command = (data ushr 26) and 0xFF
                val commandInverse = (data ushr 34) and 0xFF
                if ((address xor addressInverse) == 0x1FFFL && (command xor commandInverse) == 0xFFL) {
                    return ParsedSignal("NEC42", address, command)
                }
                return ParsedSignal("NEC42ext", data and 0x3FF_FFFF, (data ushr 26) and 0xFFFF)
            }
        }

        if (bitCount < 32) return null
        val data = readPulseDistance(t, 2, 32, 1_100) ?: return null
        val b0 = data and 0xFF
        val b1 = (data ushr 8) and 0xFF
        val b2 = (data ushr 16) and 0xFF
        val b3 = (data ushr 24) and 0xFF
        val commandOK = (b2 xor b3) == 0xFFL
        if (isSamsung) {
            if (b0 == b1 && commandOK) return ParsedSignal("Samsung32", b0, b2)
            return ParsedSignal("NECx", b0, b2, subdevice = b1)
        }
        if ((b0 xor b1) == 0xFFL && commandOK) return ParsedSignal("NEC", b0, b2)
        return ParsedSignal("NECext", b0 or (b1 shl 8), b2 or (b3 shl 8))
    }

    private fun decodeSIRC(t: List<Int>): ParsedSignal? {
        if (!near(t[0], 2_400, 0.2) || !near(t[1], 600, 0.4)) return null
        var value = 0L
        var bits = 0
        var i = 2
        while (i < t.size && bits < 20) {
            val mark = t[i]
            if (near(mark, 1_200, 0.3)) value = value or (1L shl bits)
            else if (!near(mark, 600, 0.4)) break
            bits++
            // A long space terminates the frame.
            if (i + 1 >= t.size || t[i + 1] > 1_500) break
            i += 2
        }
        val command = value and 0x7F
        return when (bits) {
            12 -> ParsedSignal("SIRC", (value ushr 7) and 0x1F, command)
            15 -> ParsedSignal("SIRC15", (value ushr 7) and 0xFF, command)
            20 -> ParsedSignal("SIRC20", (value ushr 7) and 0x1FFF, command)
            else -> null
        }
    }

    /** Decodes Philips RC5 by sampling the bi-phase waveform at half-bit resolution. */
    private fun decodeRC5(t: List<Int>): ParsedSignal? {
        val half = 889
        // Expand into half-bit levels, starting with the implicit leading space of the start bit.
        val levels = mutableListOf(false)
        for ((index, duration) in t.withIndex()) {
            val units = (duration.toDouble() / half).roundToInt()
            if (index > 0 && duration > 4 * half) break // end of frame
            if (units != 1 && units != 2) return null
            repeat(units) { levels.add(index % 2 == 0) }
            if (levels.size >= 28) break
        }
        if (levels.size == 27) levels.add(false) // trailing space of a final 0 bit
        if (levels.size < 28) return null
        var value = 0L
        for (bit in 0 until 14) {
            val first = levels[bit * 2]
            val second = levels[bit * 2 + 1]
            if (first == second) return null
            value = (value shl 1) or (if (second) 1L else 0L)
        }
        if ((value ushr 13) != 1L) return null
        val field = (value ushr 12) and 1
        val address = (value ushr 6) and 0x1F
        val command = (value and 0x3F) or (if (field == 0L) 0x40L else 0L)
        return ParsedSignal("RC5", address, command)
    }
}
