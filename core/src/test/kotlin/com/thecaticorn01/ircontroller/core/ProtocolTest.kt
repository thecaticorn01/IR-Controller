package com.thecaticorn01.ircontroller.core

import com.thecaticorn01.ircontroller.core.model.DeviceCategory
import com.thecaticorn01.ircontroller.core.model.IRButton
import com.thecaticorn01.ircontroller.core.model.IRSignal
import com.thecaticorn01.ircontroller.core.model.LearningTemplate
import com.thecaticorn01.ircontroller.core.model.ParsedSignal
import com.thecaticorn01.ircontroller.core.model.Pulse
import com.thecaticorn01.ircontroller.core.model.RawSignal
import com.thecaticorn01.ircontroller.core.model.RemoteButtonRole
import com.thecaticorn01.ircontroller.core.model.RemoteButtonRole.Kind
import com.thecaticorn01.ircontroller.core.model.RemoteLayout
import com.thecaticorn01.ircontroller.core.protocols.IRProtocolDecoder
import com.thecaticorn01.ircontroller.core.protocols.IRProtocolException
import com.thecaticorn01.ircontroller.core.protocols.IRProtocolRegistry
import com.thecaticorn01.ircontroller.core.protocols.PulseBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolEncodingTest {

    @Test fun necWaveform() {
        val raw = IRProtocolRegistry.encode(ParsedSignal("NEC", 0x04, 0x08))
        assertEquals(38_000, raw.frequency)
        assertEquals(listOf(9_000, 4_500, 560, 560), raw.timings.take(4))
        // Header + 32 bits + stop mark + trailing gap.
        assertEquals(2 + 64 + 2, raw.timings.size)
        // Address 0x04 LSB first: bits 0,0,1 → third bit is a one (long space).
        assertEquals(1_690, raw.timings[2 + 2 * 2 + 1])
        // Frame is padded to 108 ms.
        assertEquals(108_000, raw.duration)
    }

    @Test fun encodeDecodeRoundTrip() {
        val signals = listOf(
            ParsedSignal("NEC", 0x04, 0x08),
            ParsedSignal("NEC", 0xEF, 0x01),
            ParsedSignal("NECext", 0x1234, 0x5678),
            ParsedSignal("NEC42", 0x1ABC, 0x5A),
            ParsedSignal("Samsung32", 0x07, 0x02),
            ParsedSignal("SIRC", 0x01, 0x15),
            ParsedSignal("SIRC15", 0x97, 0x2C),
            ParsedSignal("SIRC20", 0x1A1A, 0x39),
            ParsedSignal("RC5", 0x00, 0x0C),
            ParsedSignal("RC5", 0x1F, 0x7F),
            ParsedSignal("RC5", 0x05, 0x35),
        )
        for (signal in signals) {
            val raw = IRProtocolRegistry.encode(signal, toggle = false)
            assertEquals(signal.toString(), signal, IRProtocolDecoder.decode(raw))
        }
    }

    @Test fun rc5ToggleAlternatesBetweenPresses() {
        val signal = ParsedSignal("RC5", 0, 12)
        assertNotEquals(IRProtocolRegistry.encode(signal).timings, IRProtocolRegistry.encode(signal).timings)
    }

    @Test fun rc5MatchesReferenceWaveform() {
        // Address 0, command 12, toggle 0: bits 1 1 0 00000 001100.
        val raw = IRProtocolRegistry.encode(ParsedSignal("RC5", 0, 12), toggle = false)
        val expected = listOf(889, 889, 1778, 889) + List(12) { 889 } + listOf(889, 1778, 889, 889, 1778, 889, 889)
        assertEquals(expected, raw.timings.take(expected.size))
        assertEquals(36_000, raw.frequency)
    }

    @Test fun rc6Leader() {
        val raw = IRProtocolRegistry.encode(ParsedSignal("RC6", 0, 0x0C), toggle = false)
        assertEquals(listOf(2_664, 888, 444, 888), raw.timings.take(4))
    }

    @Test fun sircSendsThreeFrames() {
        val raw = IRProtocolRegistry.encode(ParsedSignal("SIRC", 1, 0x15))
        assertEquals(3, raw.timings.count { it == 2_400 })
        assertEquals(40_000, raw.frequency)
    }

    @Test fun extraRepeatsAddNECRepeatCodes() {
        val raw = IRProtocolRegistry.encode(ParsedSignal("NEC", 1, 2), extraRepeats = 2)
        assertEquals(2, raw.timings.count { it == 2_250 })
    }

    @Test fun everyProtocolEncodes() {
        for (info in IRProtocolRegistry.protocols) {
            val name = info.name
            val raw = IRProtocolRegistry.encode(ParsedSignal(name, 3, 0x21, if (name == "Panasonic") 1 else null))
            assertTrue(name, raw.timings.size > 10)
            assertTrue(name, raw.timings.all { it > 0 })
            // Normalised: starts with a mark and never has two equal-level entries.
            assertEquals(name, raw.timings, raw.normalized().timings)
        }
    }

    @Test fun aliasesResolve() {
        assertEquals("NEC", IRProtocolRegistry.canonicalName("nec1"))
        assertEquals("SIRC", IRProtocolRegistry.canonicalName("Sony12"))
        assertEquals("JVC", IRProtocolRegistry.canonicalName("JVC{2}"))
        assertEquals("NECx", IRProtocolRegistry.canonicalName("NECx2"))
        assertNull(IRProtocolRegistry.canonicalName("Tivo unit=0"))
        val error = assertThrows(IRProtocolException.UnsupportedProtocol::class.java) {
            IRProtocolRegistry.encode(ParsedSignal("XMP-1", 0, 0))
        }
        assertEquals(IRProtocolException.UnsupportedProtocol("XMP-1"), error)
    }

    @Test fun pulseBuilderMergesLevels() {
        val builder = PulseBuilder()
        builder.space(100) // leading space is dropped
        builder.mark(10); builder.mark(5); builder.space(3); builder.space(2); builder.mark(1)
        assertEquals(listOf(15, 5, 1), builder.timings)
    }

    @Test fun rawPulsesPadOddLength() {
        val pulses = RawSignal(timings = listOf(100, 200, 300)).pulses(trailingGap = 999)
        assertEquals(2, pulses.size)
        assertEquals(Pulse(300, 999), pulses[1])
    }

    @Test fun rawRepeatsDuplicateFrame() {
        val raw = IRSignal.Raw(RawSignal(timings = listOf(100, 200, 300))).rendered(repeats = 1)
        assertEquals(listOf(100, 200, 300, 40_000, 100, 200, 300, 40_000), raw.timings)
    }
}

class ButtonRoleTest {
    @Test fun recognisesCommonNames() {
        assertEquals(Kind.POWER, RemoteButtonRole.of("Power").kind)
        assertEquals(Kind.VOLUME_UP, RemoteButtonRole.of("Vol_up").kind)
        assertEquals(Kind.VOLUME_DOWN, RemoteButtonRole.of("Vol_dn").kind)
        assertEquals(Kind.VOLUME_UP, RemoteButtonRole.of("VOL+").kind)
        assertEquals(Kind.VOLUME_DOWN, RemoteButtonRole.of("Vol-").kind)
        assertEquals(Kind.CHANNEL_UP, RemoteButtonRole.of("Ch_next").kind)
        assertEquals(Kind.VOLUME_UP, RemoteButtonRole.of("KEY_VOLUMEUP").kind)
        assertEquals(Kind.VOLUME_UP, RemoteButtonRole.of("Volume Up").kind)
        assertEquals(RemoteButtonRole.digit(7), RemoteButtonRole.of("7"))
        assertEquals(Kind.OK, RemoteButtonRole.of("Ok").kind)
        assertEquals(Kind.POWER_ON, RemoteButtonRole.of("Power On").kind)
        assertEquals(Kind.FAST_FORWARD, RemoteButtonRole.of("Fast_fo").kind)
        assertEquals(Kind.OTHER, RemoteButtonRole.of("Something odd").kind)
    }
}

class RemoteLayoutTest {
    private fun buttons(names: List<String>) = names.map { IRButton(name = it, signal = IRSignal.Raw(RawSignal(timings = listOf(1, 1)))) }

    @Test fun placesButtonsIntoZones() {
        val layout = RemoteLayout(buttons(listOf("Power", "Vol_up", "Vol_dn", "Mute", "Up", "Down", "Ok", "1", "2", "3", "Play", "Rew", "Netflix", "Power")))
        assertEquals("Power", layout.power?.name)
        assertTrue(layout.hasVolume && layout.hasDirectionalPad && layout.hasNumberPad)
        assertEquals(listOf("Rew", "Play"), layout.transport.map { it.name })
        // The duplicate power button and unknown buttons fall through to `others`.
        assertEquals(listOf("Netflix", "Power"), layout.others.map { it.name })
    }

    @Test fun templatesUseRecognisableNames() {
        for (category in DeviceCategory.entries) {
            assertTrue(LearningTemplate.buttonNames(category).isNotEmpty())
        }
        val tv = RemoteLayout(buttons(LearningTemplate.buttonNames(DeviceCategory.TV)))
        assertTrue(tv.others.isEmpty())
    }
}
