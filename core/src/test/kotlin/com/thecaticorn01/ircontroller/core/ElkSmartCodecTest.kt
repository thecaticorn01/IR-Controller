package com.thecaticorn01.ircontroller.core

import com.thecaticorn01.ircontroller.core.elksmart.BulkTransport
import com.thecaticorn01.ircontroller.core.elksmart.ElkSmartCodec
import com.thecaticorn01.ircontroller.core.elksmart.ElkSmartDriver
import com.thecaticorn01.ircontroller.core.elksmart.ElkSmartException
import com.thecaticorn01.ircontroller.core.elksmart.ElkSmartPayloadEncoding
import com.thecaticorn01.ircontroller.core.model.ParsedSignal
import com.thecaticorn01.ircontroller.core.model.Pulse
import com.thecaticorn01.ircontroller.core.protocols.IRProtocolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Vectors produced by the reference C implementation (elksmart-comm). */
class ElkSmartCodecTest {

    private fun hex(bytes: List<Int>) = bytes.joinToString("") { "%02x".format(it) }

    /** Mirrors elksmart-comm's `encode_nec`, used to generate the reference vectors. */
    private fun referenceNECPulses(bytes: List<Int>): List<Pulse> {
        val pulses = mutableListOf<Pulse>()
        fun byte(b: Int) {
            for (i in 7 downTo 0) pulses.add(Pulse(550, if ((b shr i) and 1 == 1) 1650 else 550))
        }
        for (i in bytes.indices step 2) {
            pulses.add(Pulse(8500, 4250))
            byte(bytes[i]); byte(bytes[i].inv() and 0xFF); byte(bytes[i + 1]); byte(bytes[i + 1].inv() and 0xFF)
            pulses.add(Pulse(550, 25000))
        }
        return pulses
    }

    @Test fun huffmanMatchesReferenceVectors() {
        assertEquals("000000", hex(ElkSmartCodec.huffmanEncode(emptyList())))
        assertEquals("000100000100", hex(ElkSmartCodec.huffmanEncode(listOf(0))))
        assertEquals("00040000010100010200010300010039", hex(ElkSmartCodec.huffmanEncode(listOf(0, 1, 2, 3))))
        assertEquals(
            "00054100054200024300014400015200020159cf58",
            hex(ElkSmartCodec.huffmanEncode("ABRACADABRA".toByteArray().map { it.toInt() })),
        )
    }

    @Test fun plainTransmitMessage() {
        val message = ElkSmartCodec.transmitMessage(38_000, referenceNECPulses(listOf(0x04, 0x08)), huffman = false)
        assertEquals(
            "ffffffffd6ef09ff0b22672222ffffffb4429a21000000000001000001010101010001010000000001000000010101010001010122a8c301",
            hex(message),
        )
    }

    @Test fun huffmanTransmitMessage() {
        val message = ElkSmartCodec.transmitMessage(38_000, referenceNECPulses(listOf(0x04, 0x08)), huffman = true)
        assertEquals(
            "ffffffffd6ef09ff33000b0000100100112100012200044200016700019a0001a80001b40001c30001ff000302a8eaa666fb6167fef067fbf0c55d10",
            hex(message),
        )
    }

    @Test fun packetsCarryChecksums() {
        val pulses = referenceNECPulses(listOf(0x04, 0x08, 0x07, 0x10, 0xAA, 0xBB))
        val plain = ElkSmartCodec.packets(ElkSmartCodec.transmitMessage(36_000, pulses, huffman = false))
        assertEquals(
            listOf(
                "ffffffffceef06ff0122672222ffffffb4429a21000000000001000001010101010001010000000001000000010101010001010122a8c301b4429a2100000e",
                "00000001010101010101010000000000000100000000010101000101010122a8c301b4429a2101000100010001000001000100010001010001010100010135",
                "000100000001000022a8c301",
            ),
            plain.map(::hex),
        )
        val huffman = ElkSmartCodec.packets(ElkSmartCodec.transmitMessage(36_000, pulses, huffman = true))
        assertEquals(
            listOf(
                "ffffffffceef06ff6d000b0000300100332100032200064200036700019a0003a80003b40003c30003ff0003059b266db6da42fbbff7833fdf8626355217d0",
                "ddff807ffbfc6131aa90bee6dbdb66337ef98d40",
            ),
            huffman.map(::hex),
        )
    }

    @Test fun mangleIsBitReverseAndInvert() {
        assertEquals(0xFF, ElkSmartCodec.mangle(0x00))
        assertEquals(0x7F, ElkSmartCodec.mangle(0x01))
        assertEquals(0xFE, ElkSmartCodec.mangle(0x80))
        assertEquals(0xF0, ElkSmartCodec.mangle(0xF0))
    }

    @Test fun compressedValuesNeverEmitTerminatorByte() {
        val out = mutableListOf<Int>()
        // 0x7F low group with continuation would be 0xFF.
        ElkSmartCodec.compressValue(0x7F or (20 shl 7), out)
        assertFalse(0xFF in out)
        out.clear()
        ElkSmartCodec.compressValue(0, out)
        assertEquals(listOf(2), out)
    }

    @Test fun decodesLearnedCodes() {
        // 9104 µs = 0xFF (4080) + 0xFF (4080) + 0x3B (944)
        val pulses = ElkSmartCodec.decodeLearned(listOf(0xFF, 0xFF, 0x3B, 0xFF, 0x1B, 0x23, 0x23, 0x23))
        assertNotNull(pulses)
        pulses!!
        assertEquals(3, pulses.size)
        assertEquals(Pulse(9104, 4512), pulses[0])
        assertEquals(Pulse(560, 560), pulses[1])
        assertEquals(Pulse(560, 0), pulses[2])
    }
}

/** In-memory stand-in for a dongle. */
class FakeTransport(private val identity: List<Int>) : BulkTransport {
    val written = mutableListOf<List<Int>>()
    private val pendingReads = ArrayDeque<List<Int>>()
    var learnResponse: List<List<Int>> = emptyList()

    override fun write(bytes: List<Int>, timeoutMillis: Int) {
        written.add(bytes)
        if (bytes == ElkSmartCodec.commandIdentify) pendingReads.addLast(identity)
        if (bytes == ElkSmartCodec.commandLearn) pendingReads.addAll(learnResponse)
    }

    override fun read(maxLength: Int, timeoutMillis: Int): List<Int>? = pendingReads.removeFirstOrNull()
}

class ElkSmartDriverTest {
    private val plainIdentity = listOf(0xFC, 0xFC, 0xFC, 0xFC, 0x70, 0x01)

    private fun driver(transport: BulkTransport) = ElkSmartDriver(transport).apply {
        interPacketDelayMillis = 0
        sleeper = {}
    }

    @Test fun identifiesProtocolVariants() {
        assertEquals(ElkSmartPayloadEncoding.HUFFMAN, driver(FakeTransport(listOf(0xFC, 0xFC, 0xFC, 0xFC, 0x02, 0xAA))).identify())
        assertEquals(ElkSmartPayloadEncoding.PLAIN, driver(FakeTransport(plainIdentity)).identify())
        assertEquals(ElkSmartPayloadEncoding.PLAIN, driver(FakeTransport(listOf(0xFA, 0xFA, 0xFA, 0xFA, 0x00, 0x00))).identify())
        assertThrows(ElkSmartException.UnsupportedDevice::class.java) {
            driver(FakeTransport(listOf(1, 2, 3, 4, 5, 6))).identify()
        }
    }

    @Test fun transmitWritesFramedPackets() {
        val transport = FakeTransport(plainIdentity)
        val raw = IRProtocolRegistry.encode(ParsedSignal("NEC", 0x04, 0x08))
        driver(transport).transmit(raw)
        val packets = transport.written.dropWhile { it == ElkSmartCodec.commandIdentify }
        val message = packets.flatMap { if (it.size == 63) it.take(62) else it }
        assertEquals(ElkSmartCodec.commandTransmit, message.take(4))
        assertTrue(packets.dropLast(1).all { it.size == 63 })
    }

    @Test fun learnsCode() {
        val transport = FakeTransport(plainIdentity)
        // Header announces 8 data bytes split across two packets.
        transport.learnResponse = listOf(
            listOf(0xFE, 0xFE, 0xFE, 0xFE, 0x00, 0x08, 0xFF, 0xFF, 0x3B, 0xFF),
            listOf(0x1B, 0x23, 0x23, 0x23),
        )
        val signal = driver(transport).learn(1_000) { false }
        assertEquals(listOf(9104, 4512, 560, 560), signal.timings.take(4))
        assertEquals(ElkSmartCodec.commandStop, transport.written.last())
    }

    @Test fun learnCanBeCancelled() {
        val d = driver(FakeTransport(plainIdentity))
        assertThrows(ElkSmartException.LearnCancelled::class.java) {
            d.learn(5_000) { true }
        }
    }
}
