import Foundation
import Testing
@testable import IRCore

@Suite struct ProtocolEncodingTests {

    @Test func necWaveform() throws {
        let raw = try IRProtocolRegistry.encode(ParsedSignal(protocolName: "NEC", address: 0x04, command: 0x08))
        #expect(raw.frequency == 38_000)
        #expect(Array(raw.timings.prefix(4)) == [9_000, 4_500, 560, 560])
        // Header + 32 bits + stop mark + trailing gap.
        #expect(raw.timings.count == 2 + 64 + 2)
        // Address 0x04 LSB first: bits 0,0,1 → third bit is a one (long space).
        #expect(raw.timings[2 + 2 * 2 + 1] == 1_690)
        // Frame is padded to 108 ms.
        #expect(raw.duration == 108_000)
    }

    @Test(arguments: [
        ParsedSignal(protocolName: "NEC", address: 0x04, command: 0x08),
        ParsedSignal(protocolName: "NEC", address: 0xEF, command: 0x01),
        ParsedSignal(protocolName: "NECext", address: 0x1234, command: 0x5678),
        ParsedSignal(protocolName: "NEC42", address: 0x1ABC, command: 0x5A),
        ParsedSignal(protocolName: "Samsung32", address: 0x07, command: 0x02),
        ParsedSignal(protocolName: "SIRC", address: 0x01, command: 0x15),
        ParsedSignal(protocolName: "SIRC15", address: 0x97, command: 0x2C),
        ParsedSignal(protocolName: "SIRC20", address: 0x1A1A, command: 0x39),
        ParsedSignal(protocolName: "RC5", address: 0x00, command: 0x0C),
        ParsedSignal(protocolName: "RC5", address: 0x1F, command: 0x7F),
        ParsedSignal(protocolName: "RC5", address: 0x05, command: 0x35),
    ])
    func encodeDecodeRoundTrip(_ signal: ParsedSignal) throws {
        let raw = try IRProtocolRegistry.encode(signal, toggle: false)
        let decoded = try #require(IRProtocolDecoder.decode(raw))
        #expect(decoded == signal)
    }

    @Test func rc5ToggleAlternatesBetweenPresses() throws {
        let signal = ParsedSignal(protocolName: "RC5", address: 0, command: 12)
        let first = try IRProtocolRegistry.encode(signal)
        let second = try IRProtocolRegistry.encode(signal)
        #expect(first.timings != second.timings)
    }

    @Test func rc5MatchesReferenceWaveform() throws {
        // Address 0, command 12, toggle 0: bits 1 1 0 00000 001100.
        let raw = try IRProtocolRegistry.encode(ParsedSignal(protocolName: "RC5", address: 0, command: 12), toggle: false)
        let expected = [889, 889, 1778, 889] + Array(repeating: 889, count: 12) + [889, 1778, 889, 889, 1778, 889, 889]
        #expect(Array(raw.timings.prefix(expected.count)) == expected)
        #expect(raw.frequency == 36_000)
    }

    @Test func rc6Leader() throws {
        let raw = try IRProtocolRegistry.encode(ParsedSignal(protocolName: "RC6", address: 0, command: 0x0C), toggle: false)
        #expect(Array(raw.timings.prefix(4)) == [2_664, 888, 444, 888])
    }

    @Test func sircSendsThreeFrames() throws {
        let raw = try IRProtocolRegistry.encode(ParsedSignal(protocolName: "SIRC", address: 1, command: 0x15))
        #expect(raw.timings.filter { $0 == 2_400 }.count == 3)
        #expect(raw.frequency == 40_000)
    }

    @Test func extraRepeatsAddNECRepeatCodes() throws {
        let raw = try IRProtocolRegistry.encode(ParsedSignal(protocolName: "NEC", address: 1, command: 2), extraRepeats: 2)
        #expect(raw.timings.filter { $0 == 2_250 }.count == 2)
    }

    @Test(arguments: IRProtocolRegistry.protocols.map(\.name))
    func everyProtocolEncodes(_ name: String) throws {
        let raw = try IRProtocolRegistry.encode(ParsedSignal(protocolName: name, address: 3, command: 0x21, subdevice: name == "Panasonic" ? 1 : nil))
        #expect(raw.timings.count > 10)
        #expect(raw.timings.allSatisfy { $0 > 0 })
        // Normalised: starts with a mark and never has two equal-level entries.
        #expect(raw.normalized().timings == raw.timings)
    }

    @Test func aliasesResolve() {
        #expect(IRProtocolRegistry.canonicalName(for: "nec1") == "NEC")
        #expect(IRProtocolRegistry.canonicalName(for: "Sony12") == "SIRC")
        #expect(IRProtocolRegistry.canonicalName(for: "JVC{2}") == "JVC")
        #expect(IRProtocolRegistry.canonicalName(for: "NECx2") == "NECx")
        #expect(IRProtocolRegistry.canonicalName(for: "Tivo unit=0") == nil)
        #expect(throws: IRProtocolError.unsupportedProtocol("XMP-1")) {
            try IRProtocolRegistry.encode(ParsedSignal(protocolName: "XMP-1", address: 0, command: 0))
        }
    }

    @Test func pulseBuilderMergesLevels() {
        var builder = PulseBuilder()
        builder.space(100) // leading space is dropped
        builder.mark(10); builder.mark(5); builder.space(3); builder.space(2); builder.mark(1)
        #expect(builder.timings == [15, 5, 1])
    }

    @Test func rawPulsesPadOddLength() {
        let raw = RawSignal(timings: [100, 200, 300])
        let pulses = raw.pulses(trailingGap: 999)
        #expect(pulses.count == 2)
        #expect(pulses[1].on == 300 && pulses[1].off == 999)
    }
}

@Suite struct ButtonRoleTests {
    @Test func recognisesCommonNames() {
        #expect(RemoteButtonRole(buttonName: "Power") == .power)
        #expect(RemoteButtonRole(buttonName: "Vol_up") == .volumeUp)
        #expect(RemoteButtonRole(buttonName: "Vol_dn") == .volumeDown)
        #expect(RemoteButtonRole(buttonName: "VOL+") == .volumeUp)
        #expect(RemoteButtonRole(buttonName: "Vol-") == .volumeDown)
        #expect(RemoteButtonRole(buttonName: "Ch_next") == .channelUp)
        #expect(RemoteButtonRole(buttonName: "KEY_VOLUMEUP") == .volumeUp)
        #expect(RemoteButtonRole(buttonName: "Volume Up") == .volumeUp)
        #expect(RemoteButtonRole(buttonName: "7") == .digit(7))
        #expect(RemoteButtonRole(buttonName: "Ok") == .ok)
        #expect(RemoteButtonRole(buttonName: "Power On") == .powerOn)
        #expect(RemoteButtonRole(buttonName: "Fast_fo") == .fastForward)
        #expect(RemoteButtonRole(buttonName: "Something odd") == .other)
    }
}

@Suite struct RemoteLayoutTests {
    private func buttons(_ names: [String]) -> [IRButton] {
        names.map { IRButton(name: $0, signal: .raw(RawSignal(timings: [1, 1]))) }
    }

    @Test func placesButtonsIntoZones() {
        let layout = RemoteLayout(buttons: buttons(["Power", "Vol_up", "Vol_dn", "Mute", "Up", "Down", "Ok", "1", "2", "3", "Play", "Rew", "Netflix", "Power"]))
        #expect(layout.power?.name == "Power")
        #expect(layout.hasVolume && layout.hasDirectionalPad && layout.hasNumberPad)
        #expect(layout.transport.map(\.name) == ["Rew", "Play"])
        // The duplicate power button and unknown buttons fall through to `others`.
        #expect(layout.others.map(\.name) == ["Netflix", "Power"])
    }

    @Test func templatesUseRecognisableNames() {
        for category in DeviceCategory.allCases {
            let names = LearningTemplate.buttonNames(for: category)
            #expect(!names.isEmpty)
        }
        let tv = RemoteLayout(buttons: buttons(LearningTemplate.buttonNames(for: .tv)))
        #expect(tv.others.isEmpty)
    }
}
