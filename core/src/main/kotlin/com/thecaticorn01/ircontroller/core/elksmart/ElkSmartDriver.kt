package com.thecaticorn01.ircontroller.core.elksmart

import com.thecaticorn01.ircontroller.core.model.RawSignal
import com.thecaticorn01.ircontroller.core.protocols.IRProtocolException
import kotlin.math.max

/**
 * A bidirectional USB bulk pipe pair. Implemented by the Android app on top of
 * `UsbDeviceConnection`; tests use an in-memory fake.
 */
interface BulkTransport {
    /** Writes one bulk packet. Throws on failure. */
    fun write(bytes: List<Int>, timeoutMillis: Int)

    /** Reads one bulk packet. Returns `null` if nothing arrived before the timeout. */
    fun read(maxLength: Int, timeoutMillis: Int): List<Int>?
}

/** Known ELK Smart dongle models (USB vendor 0x045C). */
data class ElkSmartModel(val productId: Int, val name: String) {
    companion object {
        const val VENDOR_ID = 0x045C

        val known = listOf(
            ElkSmartModel(0x0184, "ELK Smart EKX5S-T (USB-C)"),
            ElkSmartModel(0x0195, "ELK Smart EKX4S"),
            ElkSmartModel(0x0134, "ELK Smart EKX5S"),
            ElkSmartModel(0x0132, "ELK Smart IR Blaster"),
            ElkSmartModel(0x0131, "ELK Smart IR Blaster"),
            ElkSmartModel(0x014A, "ELK Smart IR Blaster"),
            ElkSmartModel(0x02AA, "ELK Smart IR Blaster"),
        )

        fun forProductId(productId: Int): ElkSmartModel =
            known.firstOrNull { it.productId == productId }
                ?: ElkSmartModel(productId, "ELK Smart device (0x%04X)".format(productId))
    }
}

sealed class ElkSmartException(message: String) : Exception(message) {
    class NoResponse : ElkSmartException("The IR blaster did not respond. Unplug it, plug it back in and try again.")
    class UnsupportedDevice(val response: List<Int>) :
        ElkSmartException("The IR blaster returned an unrecognised identity (${response.hexString}). It may be busy or unsupported.")
    class SignalTooLong : ElkSmartException("The signal is too long to send with this IR blaster.")
    class LearnTimedOut : ElkSmartException(
        "No infrared signal was received. Point the remote at the blaster from about 1 cm away and press the button again.",
    )
    class LearnCancelled : ElkSmartException("Learning was cancelled.")
    class UnexpectedResponse(val response: List<Int>) :
        ElkSmartException("The IR blaster sent an unexpected response (${response.take(8).hexString}).")
}

/** How the dongle expects transmit payloads to be encoded. */
enum class ElkSmartPayloadEncoding {
    /** EKX4S and older: plain compressed pulses (identity "70 01" or "FA FA FA FA"). */
    PLAIN,
    /** EKX5S-T and newer: Huffman-coded payload (identity "02 AA"). */
    HUFFMAN,
}

/**
 * Implements the ELK Smart USB protocol over a [BulkTransport].
 * Not thread-safe: drive it from a single thread.
 */
class ElkSmartDriver(val transport: BulkTransport) {
    var encoding: ElkSmartPayloadEncoding? = null
        private set
    var identity: List<Int> = emptyList()
        private set

    /** Delay between consecutive bulk packets of one message. */
    var interPacketDelayMillis: Long = 2

    /** Pause used while waiting; overridable so tests run instantly. */
    var sleeper: (Long) -> Unit = { Thread.sleep(it) }

    /** Flushes stale input and asks the dongle to identify itself. */
    fun identify(): ElkSmartPayloadEncoding {
        drain(50)
        var response: List<Int>? = null
        for (attempt in 0 until 4) {
            transport.write(ElkSmartCodec.commandIdentify, 200)
            val bytes = transport.read(64, 500)
            if (!bytes.isNullOrEmpty()) {
                response = bytes
                break
            }
        }
        val bytes = response ?: throw ElkSmartException.NoResponse()
        identity = bytes
        val encoding = classify(bytes) ?: throw ElkSmartException.UnsupportedDevice(bytes)
        this.encoding = encoding
        return encoding
    }

    /** Transmits a waveform and waits until the dongle has finished emitting it. */
    fun transmit(signal: RawSignal) {
        if (encoding == null) identify()
        val pulses = signal.normalized().pulses()
        if (pulses.isEmpty()) throw IRProtocolException.EmptySignal()
        val message = ElkSmartCodec.transmitMessage(signal.frequency, pulses, encoding == ElkSmartPayloadEncoding.HUFFMAN)
        if (message.size - 9 > ElkSmartCodec.MAXIMUM_PAYLOAD_LENGTH) throw ElkSmartException.SignalTooLong()
        for (packet in ElkSmartCodec.packets(message)) {
            transport.write(packet, 100)
            if (interPacketDelayMillis > 0) sleeper(interPacketDelayMillis)
        }
        // The dongle buffers the code and emits it afterwards; give it time to
        // finish before the next command so codes are not cut short.
        val emitMicros = pulses.sumOf { it.on.toLong() + it.off.toLong() }
        sleeper(max(80L, emitMicros / 1000 + 25))
        drain(30)
    }

    /**
     * Puts the dongle into learning mode and waits for a code.
     * @param timeoutMillis how long to wait for the user to press a button.
     * @param frequency carrier frequency to record (the dongle does not measure it).
     * @param isCancelled polled roughly every 100 ms.
     */
    fun learn(timeoutMillis: Long, frequency: Int = 38_000, isCancelled: () -> Boolean): RawSignal {
        if (encoding == null) identify()
        transport.write(ElkSmartCodec.commandLearn, 200)
        try {
            val deadline = System.currentTimeMillis() + timeoutMillis
            var first: List<Int>? = null
            while (first == null) {
                if (isCancelled()) throw ElkSmartException.LearnCancelled()
                if (System.currentTimeMillis() > deadline) throw ElkSmartException.LearnTimedOut()
                val packet = transport.read(64, 100)
                if (!packet.isNullOrEmpty()) first = packet
            }
            if (first.size < 6 || first.take(4) != ElkSmartCodec.commandLearn) {
                throw ElkSmartException.UnexpectedResponse(first)
            }
            val expected = (first[4] shl 8) or first[5]
            var data = first.drop(6)
            // The dongle streams the code in pieces with pauses between them.
            var idleDeadline = System.currentTimeMillis() + 1_000
            while (data.size < expected && System.currentTimeMillis() < idleDeadline) {
                if (isCancelled()) throw ElkSmartException.LearnCancelled()
                val packet = transport.read(64, 100)
                if (!packet.isNullOrEmpty()) {
                    data = data + packet
                    idleDeadline = System.currentTimeMillis() + 1_000
                }
            }
            if (expected > 0 && data.size > expected) data = data.take(expected)
            val pulses = ElkSmartCodec.decodeLearned(data)
            if (pulses == null || pulses.size < 2) throw ElkSmartException.LearnTimedOut()
            val timings = ArrayList<Int>(pulses.size * 2)
            for (pulse in pulses) {
                timings.add(pulse.on)
                timings.add(pulse.off)
            }
            // Replace the meaningless final silence with a conventional gap.
            val last = timings.last()
            if (last == 0 || last > 100_000) timings[timings.size - 1] = 50_000
            return RawSignal(frequency, 0.33, timings).normalized()
        } finally {
            runCatching { transport.write(ElkSmartCodec.commandStop, 100) }
        }
    }

    private fun drain(durationMillis: Long) {
        val deadline = System.currentTimeMillis() + durationMillis
        while (System.currentTimeMillis() < deadline) {
            val packet = runCatching { transport.read(64, 10) }.getOrNull()
            if (packet.isNullOrEmpty()) break
        }
    }

    companion object {
        fun classify(b: List<Int>): ElkSmartPayloadEncoding? {
            if (b.size < 6) return null
            val prefix = b.take(4)
            if (prefix == ElkSmartCodec.commandIdentify) {
                if (b[4] == 0x02 && b[5] == 0xAA) return ElkSmartPayloadEncoding.HUFFMAN
                if (b[4] == 0x70 && b[5] == 0x01) return ElkSmartPayloadEncoding.PLAIN
                return null
            }
            if (prefix == listOf(0xFA, 0xFA, 0xFA, 0xFA)) return ElkSmartPayloadEncoding.PLAIN
            return null
        }
    }
}
