package com.thecaticorn01.ircontroller.core.protocols

import kotlin.math.abs
import kotlin.math.max

/** Bit order used when serialising a data field. */
enum class BitOrder { LSB_FIRST, MSB_FIRST }

/**
 * How a single logical bit is rendered. Durations are signed microseconds:
 * positive values are marks, negative values are spaces.
 */
class BitEncoding(val zero: IntArray, val one: IntArray) {
    companion object {
        /** Pulse-distance encoding (fixed mark, variable space). */
        fun pulseDistance(mark: Int, zeroSpace: Int, oneSpace: Int) =
            BitEncoding(intArrayOf(mark, -zeroSpace), intArrayOf(mark, -oneSpace))

        /** Pulse-width encoding (variable mark, fixed space). */
        fun pulseWidth(zeroMark: Int, oneMark: Int, space: Int) =
            BitEncoding(intArrayOf(zeroMark, -space), intArrayOf(oneMark, -space))
    }
}

/**
 * Incrementally builds a normalised mark/space waveform.
 *
 * Consecutive durations of the same level are merged, which makes bi-phase
 * (Manchester) encodings such as RC5 and RC6 fall out naturally.
 */
class PulseBuilder {
    private val _timings = ArrayList<Int>()
    /** Positive alternating durations, starting with a mark. */
    val timings: List<Int> get() = _timings
    private var frameStart = 0

    /** Total length of everything emitted so far. */
    val totalDuration: Int get() = _timings.sum()

    fun mark(microseconds: Int) = append(microseconds)
    fun space(microseconds: Int) = append(-microseconds)

    /** Appends a signed duration (positive = mark, negative = space). */
    fun append(signed: Int) {
        if (signed == 0) return
        val isMark = signed > 0
        val value = abs(signed)
        if (_timings.isEmpty()) {
            // A waveform always begins with a mark; leading silence is meaningless.
            if (isMark) _timings.add(value)
            return
        }
        val lastIsMark = _timings.size % 2 == 1
        if (lastIsMark == isMark) {
            _timings[_timings.size - 1] += value
        } else {
            _timings.add(value)
        }
    }

    fun append(signed: IntArray) = signed.forEach { append(it) }

    /** Emits [count] bits of [value]. */
    fun bits(value: Long, count: Int, order: BitOrder, encoding: BitEncoding) {
        for (i in 0 until count) {
            val bitIndex = if (order == BitOrder.LSB_FIRST) i else count - 1 - i
            val bit = (value ushr bitIndex) and 1L
            append(if (bit == 1L) encoding.one else encoding.zero)
        }
    }

    /** Marks the start of a new frame for [padFrame]. */
    fun beginFrame() {
        frameStart = totalDuration
    }

    /**
     * Pads with silence so the current frame lasts at least [length] microseconds
     * (IRP `^length`). Always appends at least [minimumGap] of silence.
     */
    fun padFrame(length: Int, minimumGap: Int = 0) {
        val elapsed = totalDuration - frameStart
        space(max(length - elapsed, minimumGap))
    }
}
