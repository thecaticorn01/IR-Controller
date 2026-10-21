package com.thecaticorn01.ircontroller.core.elksmart

import com.thecaticorn01.ircontroller.core.model.Pulse
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Wire format of ELK Smart (Ocrustar / ZaZa) USB infrared dongles.
 *
 * The protocol was reverse engineered by Přemysl Eric Janouch in
 * `elksmart-comm` (0BSD licence, https://git.janouch.name/p/usb-drivers);
 * this is an independent Kotlin implementation of the same format.
 *
 * Transmission message layout:
 * ```
 * FF FF FF FF  mangle(f>>8) mangle(f>>16) mangle(f)  mangle(len>>8) mangle(len)  payload…
 * ```
 * where `f = frequency + 0x7FFFF` and the payload is a compressed pulse list,
 * optionally Huffman-coded for newer dongles. The message is split into
 * 62-byte chunks; full chunks get a one-byte checksum appended.
 *
 * Bytes are represented as `Int` values in `0..255`.
 */
object ElkSmartCodec {

    val commandTransmit = listOf(0xFF, 0xFF, 0xFF, 0xFF)
    val commandLearn = listOf(0xFE, 0xFE, 0xFE, 0xFE)
    val commandStop = listOf(0xFD, 0xFD, 0xFD, 0xFD)
    val commandIdentify = listOf(0xFC, 0xFC, 0xFC, 0xFC)

    /** Bit-reverses and inverts a byte. */
    fun mangle(value: Int): Int {
        var input = value and 0xFF
        var reversed = 0
        repeat(8) {
            reversed = (reversed shl 1) or (input and 1)
            input = input shr 1
        }
        return reversed.inv() and 0xFF
    }

    fun checksum(bytes: List<Int>): Int {
        val sum = bytes.sum()
        return mangle((sum and 0xF0) or ((sum shr 8) and 0x0F))
    }

    /**
     * Compresses one duration (µs). Short values are stored in 16 µs units;
     * long values use 7-bit little-endian groups with a continuation bit.
     */
    fun compressValue(value: Int, out: MutableList<Int>) {
        val v = max(0, value)
        if (v <= 2032) {
            // Values 0 and 1 are reserved as references to the two most common pulses.
            val units = floor(v / 16.0 + 0.5).toInt()
            out.add(max(2, units))
        } else {
            var remaining = v
            do {
                var byte = remaining and 0x7F
                remaining = remaining shr 7
                if (remaining != 0) byte = byte or 0x80
                // 0xFF would be confused with the header terminator.
                out.add(if (byte == 0xFF) 0xFE else byte)
            } while (remaining != 0)
        }
    }

    /**
     * Dictionary-compresses a pulse list: the two most frequent pulses are
     * declared up front and then referenced with single bytes 0 and 1.
     */
    fun compress(pulses: List<Pulse>): List<Int> {
        if (pulses.isEmpty()) return emptyList()
        val counts = HashMap<Pulse, Int>()
        for (p in pulses) counts[p] = (counts[p] ?: 0) + 1
        fun count(p: Pulse) = counts[p] ?: 0

        // Same tie-breaking as the reference implementation: first occurrence wins.
        var top1 = 0
        for (i in pulses.indices) if (count(pulses[i]) > count(pulses[top1])) top1 = i
        var top2 = top1
        for (i in pulses.indices) {
            if (pulses[i] == pulses[top1]) continue
            if (top2 == top1 || count(pulses[i]) > count(pulses[top2])) top2 = i
        }
        val p1 = pulses[top1]
        val p2 = pulses[top2]

        val out = ArrayList<Int>(pulses.size + 16)
        compressValue(p2.on, out)
        compressValue(p2.off, out)
        compressValue(p1.on, out)
        compressValue(p1.off, out)
        out += listOf(0xFF, 0xFF, 0xFF)
        for (p in pulses) {
            when (p) {
                p1 -> out.add(0)
                p2 -> out.add(1)
                else -> {
                    compressValue(p.on, out)
                    compressValue(p.off, out)
                }
            }
        }
        return out
    }

    // Huffman

    private class Node(val frequency: Int) {
        var parent: Node? = null
        var bit = false
    }

    /**
     * Binary heap mirroring java.util.PriorityQueue, so that ties between equal
     * frequencies resolve exactly like the vendor's Android application.
     */
    private class JavaHeap {
        val items = ArrayList<Node>()

        fun push(node: Node) {
            items.add(node)
            var child = items.size - 1
            while (child > 0) {
                val parent = (child - 1) / 2
                if (node.frequency >= items[parent].frequency) break
                items[child] = items[parent]
                child = parent
            }
            items[child] = node
        }

        fun pop(): Node {
            val result = items[0]
            val last = items.removeAt(items.size - 1)
            if (items.isEmpty()) return result
            val count = items.size
            var parent = 0
            while (parent < count / 2) {
                var child = parent * 2 + 1
                if (child + 1 < count && items[child + 1].frequency < items[child].frequency) child++
                if (items[child].frequency >= last.frequency) break
                items[parent] = items[child]
                parent = child
            }
            items[parent] = last
            return result
        }
    }

    /**
     * Huffman-codes a payload for dongles that report the "02 AA" identity.
     * Output: u16 symbol count, (u8 symbol, u16 frequency)…, u8 padding bits, bitstream.
     */
    fun huffmanEncode(input: List<Int>): List<Int> {
        val frequencies = IntArray(256)
        for (byte in input) frequencies[byte and 0xFF]++

        val leaves = arrayOfNulls<Node>(256)
        val heap = JavaHeap()
        for (symbol in 0 until 256) {
            if (frequencies[symbol] > 0) {
                val node = Node(frequencies[symbol])
                leaves[symbol] = node
                heap.push(node)
            }
        }

        val out = ArrayList<Int>()
        val symbolCount = heap.items.size
        out.add((symbolCount shr 8) and 0xFF)
        out.add(symbolCount and 0xFF)
        for (symbol in 0 until 256) {
            if (frequencies[symbol] > 0) {
                out.add(symbol)
                out.add((frequencies[symbol] shr 8) and 0xFF)
                out.add(frequencies[symbol] and 0xFF)
            }
        }

        while (heap.items.size > 1) {
            val left = heap.pop()
            val right = heap.pop()
            val node = Node(left.frequency + right.frequency)
            left.parent = node
            right.parent = node
            left.bit = false
            right.bit = true
            heap.push(node)
        }

        val paddingIndex = out.size
        out.add(0)
        var byte = 0
        var bitCount = 0
        val path = ArrayList<Boolean>()
        for (symbol in input) {
            path.clear()
            var node = leaves[symbol and 0xFF]
            while (node != null) {
                val parent = node.parent ?: break
                path.add(node.bit)
                node = parent
            }
            for (i in path.indices.reversed()) {
                byte = ((byte shl 1) or (if (path[i]) 1 else 0)) and 0xFF
                bitCount++
                if (bitCount == 8) {
                    out.add(byte)
                    byte = 0
                    bitCount = 0
                }
            }
        }
        if (bitCount > 0) {
            out[paddingIndex] = 8 - bitCount
            out.add((byte shl (8 - bitCount)) and 0xFF)
        }
        return out
    }

    // Messages

    /** Builds the complete transmit message (before chunking). */
    fun transmitMessage(frequency: Int, pulses: List<Pulse>, huffman: Boolean): List<Int> {
        var payload = compress(pulses)
        if (huffman) payload = huffmanEncode(payload)
        val f = max(1, frequency) + 0x7FFFF
        val message = commandTransmit.toMutableList()
        message.add(mangle((f shr 8) and 0xFF))
        message.add(mangle((f shr 16) and 0xFF))
        message.add(mangle(f and 0xFF))
        message.add(mangle((payload.size shr 8) and 0xFF))
        message.add(mangle(payload.size and 0xFF))
        message += payload
        return message
    }

    /**
     * Splits a message into USB bulk packets: 62 data bytes plus checksum,
     * with the final short chunk sent as-is.
     */
    fun packets(message: List<Int>): List<List<Int>> {
        val packets = mutableListOf<List<Int>>()
        var index = 0
        while (index < message.size) {
            val end = min(index + 62, message.size)
            val chunk = message.subList(index, end).toMutableList()
            if (chunk.size == 62) chunk.add(checksum(chunk))
            packets.add(chunk)
            index = end
        }
        return packets
    }

    /** Maximum payload length expressible in the 16-bit length field. */
    const val MAXIMUM_PAYLOAD_LENGTH = 0xFFFF

    /**
     * Decodes a learned code: each byte is a duration in 16 µs units and 0xFF
     * bytes carry 4080 µs into the following byte.
     */
    fun decodeLearned(bytes: List<Int>): List<Pulse>? {
        val pulses = mutableListOf<Pulse>()
        var i = 0
        fun readDuration(): Int? {
            var total = 0
            while (i < bytes.size && bytes[i] == 0xFF) {
                total += 4080
                i++
            }
            if (i >= bytes.size) return null
            total += bytes[i] * 16
            i++
            return total
        }
        while (i < bytes.size) {
            val on = readDuration() ?: return pulses.ifEmpty { null }
            if (i >= bytes.size) {
                pulses.add(Pulse(on, 0))
                break
            }
            val off = readDuration() ?: return pulses.ifEmpty { null }
            pulses.add(Pulse(on, off))
        }
        return pulses.ifEmpty { null }
    }
}

/** "FC FC 02 AA" style hex dump. */
val List<Int>.hexString: String get() = joinToString(" ") { "%02X".format(it and 0xFF) }
