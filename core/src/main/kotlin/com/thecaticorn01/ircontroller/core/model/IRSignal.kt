package com.thecaticorn01.ircontroller.core.model

import com.thecaticorn01.ircontroller.core.protocols.IRProtocolException
import com.thecaticorn01.ircontroller.core.protocols.IRProtocolRegistry
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.util.Locale
import kotlin.math.max

/**
 * A raw infrared waveform: alternating mark (carrier on) and space (carrier off)
 * durations in microseconds, always starting with a mark.
 */
@Serializable
data class RawSignal(
    /** Carrier frequency in hertz. */
    val frequency: Int = 38_000,
    /**
     * Carrier duty cycle in the range 0..1. ElkSmart dongles ignore it, but it is
     * preserved so codes round-trip through Flipper `.ir` files unchanged.
     */
    val dutyCycle: Double = 0.33,
    /** Mark/space durations in microseconds, starting with a mark. */
    val timings: List<Int>,
) {
    /** Total duration of the waveform in microseconds. */
    val duration: Int get() = timings.sumOf { max(0, it) }

    /**
     * Pairs of (mark, space). An odd-length signal gets a trailing space of
     * [trailingGap] so the final mark is not truncated by the transmitter.
     */
    fun pulses(trailingGap: Int = 10_000): List<Pulse> {
        val result = ArrayList<Pulse>((timings.size + 1) / 2)
        var index = 0
        while (index < timings.size) {
            val on = max(0, timings[index])
            val off = if (index + 1 < timings.size) max(0, timings[index + 1]) else trailingGap
            result.add(Pulse(on, off))
            index += 2
        }
        return result
    }

    /**
     * Returns a copy with consecutive same-level durations merged and
     * non-positive values removed, starting with a mark.
     */
    fun normalized(): RawSignal = copy(timings = normalize(timings))

    companion object {
        fun normalize(timings: List<Int>): List<Int> {
            val out = ArrayList<Int>(timings.size)
            timings.forEachIndexed { index, value ->
                if (value <= 0) return@forEachIndexed
                val isMark = index % 2 == 0
                val outIsMark = out.size % 2 == 0
                if (isMark == outIsMark) {
                    out.add(value)
                } else if (out.isEmpty()) {
                    // Leading space: drop it, a waveform must start with a mark.
                } else {
                    out[out.size - 1] += value
                }
            }
            return out
        }
    }
}

/** One (mark, space) pair in microseconds. */
data class Pulse(val on: Int, val off: Int)

/** A protocol-level code such as `NEC address 0x04 command 0x08`. */
@Serializable
data class ParsedSignal(
    /** Name of the protocol as understood by [IRProtocolRegistry]. */
    val protocolName: String,
    val address: Long,
    val command: Long,
    /** Optional sub-device (IRP `S` parameter); `null` means protocol default. */
    val subdevice: Long? = null,
)

/** Any infrared code the app knows how to transmit. */
@Serializable(with = IRSignalSerializer::class)
sealed interface IRSignal {
    data class Parsed(val signal: ParsedSignal) : IRSignal
    data class Raw(val signal: RawSignal) : IRSignal

    /** Renders the signal to a transmittable waveform. */
    fun rendered(repeats: Int = 0): RawSignal = when (this) {
        is Raw -> {
            if (signal.timings.isEmpty()) throw IRProtocolException.EmptySignal()
            if (repeats <= 0) {
                signal.normalized()
            } else {
                val timings = signal.normalized().timings.toMutableList()
                if (timings.size % 2 == 1) timings.add(40_000)
                val frame = timings.toList()
                repeat(repeats) { timings.addAll(frame) }
                RawSignal(signal.frequency, signal.dutyCycle, timings)
            }
        }
        is Parsed -> IRProtocolRegistry.encode(signal, extraRepeats = repeats)
    }

    /** Renders with an explicit toggle bit (used for exports, which should be deterministic). */
    fun rendered(toggle: Boolean): RawSignal = when (this) {
        is Raw -> rendered()
        is Parsed -> IRProtocolRegistry.encode(signal, toggle = toggle)
    }

    /** Short human readable description, e.g. "NEC · A 0x4 · C 0x8" or "Raw · 38.0 kHz · 67 timings". */
    val summary: String
        get() = when (this) {
            is Parsed -> buildString {
                append("${signal.protocolName} · A 0x${signal.address.toString(16).uppercase()}")
                signal.subdevice?.let { append(".${it.toString(16).uppercase()}") }
                append(" · C 0x${signal.command.toString(16).uppercase()}")
            }
            is Raw -> String.format(Locale.ROOT, "Raw · %.1f kHz · %d timings", signal.frequency / 1000.0, signal.timings.size)
        }

    val isRaw: Boolean get() = this is Raw
}

/**
 * Stores [IRSignal] in the library format as a single-key object whose value is
 * wrapped in `_0`: `{"parsed":{"_0":{…}}}` or `{"raw":{"_0":{…}}}`.
 */
object IRSignalSerializer : KSerializer<IRSignal> {
    @Serializable
    private data class Box<T>(@SerialName("_0") val value: T)

    @Serializable
    private data class Surrogate(
        val parsed: Box<ParsedSignal>? = null,
        val raw: Box<RawSignal>? = null,
    )

    override val descriptor: SerialDescriptor = Surrogate.serializer().descriptor

    override fun serialize(encoder: Encoder, value: IRSignal) {
        val surrogate = when (value) {
            is IRSignal.Parsed -> Surrogate(parsed = Box(value.signal))
            is IRSignal.Raw -> Surrogate(raw = Box(value.signal))
        }
        encoder.encodeSerializableValue(Surrogate.serializer(), surrogate)
    }

    override fun deserialize(decoder: Decoder): IRSignal {
        val surrogate = decoder.decodeSerializableValue(Surrogate.serializer())
        surrogate.parsed?.let { return IRSignal.Parsed(it.value) }
        surrogate.raw?.let { return IRSignal.Raw(it.value) }
        throw IllegalArgumentException("Signal has neither parsed nor raw data")
    }
}
