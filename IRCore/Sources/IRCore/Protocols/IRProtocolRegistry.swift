import Foundation
import Synchronization

public enum IRProtocolError: LocalizedError, Equatable, Sendable {
    case unsupportedProtocol(String)
    case emptySignal

    public var errorDescription: String? {
        switch self {
        case .unsupportedProtocol(let name):
            "The “\(name)” infrared protocol is not supported yet."
        case .emptySignal:
            "The signal contains no timing data."
        }
    }
}

/// Describes a protocol the app can generate.
public struct IRProtocolInfo: Sendable, Hashable, Identifiable {
    public var id: String { name }
    public let name: String
    public let frequency: Int
    public let addressBits: Int
    public let commandBits: Int
    /// Number of frames sent for a single button press.
    public let minimumFrames: Int
    public let summary: String
}

/// Encodes protocol-level codes (Flipper Zero protocols and the common IRP
/// protocols used by the IRDB database) into raw mark/space timings.
public enum IRProtocolRegistry {

    /// All protocols supported for encoding, in display order.
    public static let protocols: [IRProtocolInfo] = [
        .init(name: "NEC", frequency: 38_000, addressBits: 8, commandBits: 8, minimumFrames: 1, summary: "NEC (8-bit address, 8-bit command)"),
        .init(name: "NECext", frequency: 38_000, addressBits: 16, commandBits: 16, minimumFrames: 1, summary: "Extended NEC (16-bit address and command)"),
        .init(name: "NEC42", frequency: 38_000, addressBits: 13, commandBits: 8, minimumFrames: 1, summary: "NEC 42-bit"),
        .init(name: "NEC42ext", frequency: 38_000, addressBits: 26, commandBits: 16, minimumFrames: 1, summary: "Extended NEC 42-bit"),
        .init(name: "NEC2", frequency: 38_000, addressBits: 8, commandBits: 8, minimumFrames: 1, summary: "NEC with full-frame repeats"),
        .init(name: "NECx", frequency: 38_000, addressBits: 8, commandBits: 8, minimumFrames: 1, summary: "NECx (Samsung-style 4.5 ms header)"),
        .init(name: "NEC-f16", frequency: 38_000, addressBits: 8, commandBits: 16, minimumFrames: 1, summary: "NEC with 16-bit function"),
        .init(name: "Samsung32", frequency: 38_000, addressBits: 8, commandBits: 8, minimumFrames: 1, summary: "Samsung 32-bit"),
        .init(name: "RC5", frequency: 36_000, addressBits: 5, commandBits: 7, minimumFrames: 1, summary: "Philips RC5"),
        .init(name: "RC5X", frequency: 36_000, addressBits: 5, commandBits: 6, minimumFrames: 1, summary: "Philips RC5 extended"),
        .init(name: "RC6", frequency: 36_000, addressBits: 8, commandBits: 8, minimumFrames: 1, summary: "Philips RC6 mode 0"),
        .init(name: "MCE", frequency: 36_000, addressBits: 7, commandBits: 8, minimumFrames: 1, summary: "Windows Media Center (RC6-6-32)"),
        .init(name: "SIRC", frequency: 40_000, addressBits: 5, commandBits: 7, minimumFrames: 3, summary: "Sony SIRC 12-bit"),
        .init(name: "SIRC15", frequency: 40_000, addressBits: 8, commandBits: 7, minimumFrames: 3, summary: "Sony SIRC 15-bit"),
        .init(name: "SIRC20", frequency: 40_000, addressBits: 13, commandBits: 7, minimumFrames: 3, summary: "Sony SIRC 20-bit"),
        .init(name: "Kaseikyo", frequency: 37_000, addressBits: 26, commandBits: 10, minimumFrames: 1, summary: "Kaseikyo (Flipper layout)"),
        .init(name: "Panasonic", frequency: 37_000, addressBits: 8, commandBits: 8, minimumFrames: 1, summary: "Panasonic (Kaseikyo vendor 0x2002)"),
        .init(name: "RCA", frequency: 38_000, addressBits: 4, commandBits: 8, minimumFrames: 1, summary: "RCA (Flipper layout)"),
        .init(name: "RCA-IRP", frequency: 58_000, addressBits: 4, commandBits: 8, minimumFrames: 1, summary: "RCA 58 kHz (IRP layout)"),
        .init(name: "RCA-38", frequency: 38_700, addressBits: 4, commandBits: 8, minimumFrames: 1, summary: "RCA 38 kHz (IRP layout)"),
        .init(name: "Pioneer", frequency: 40_000, addressBits: 8, commandBits: 8, minimumFrames: 2, summary: "Pioneer"),
        .init(name: "JVC", frequency: 38_000, addressBits: 8, commandBits: 8, minimumFrames: 1, summary: "JVC"),
        .init(name: "Mitsubishi", frequency: 32_600, addressBits: 8, commandBits: 8, minimumFrames: 1, summary: "Mitsubishi"),
        .init(name: "Aiwa", frequency: 38_000, addressBits: 8, commandBits: 8, minimumFrames: 1, summary: "Aiwa"),
        .init(name: "Denon", frequency: 38_000, addressBits: 5, commandBits: 8, minimumFrames: 1, summary: "Denon"),
        .init(name: "Sharp", frequency: 38_000, addressBits: 5, commandBits: 8, minimumFrames: 1, summary: "Sharp"),
    ]

    private static let infoByKey: [String: IRProtocolInfo] = {
        Dictionary(uniqueKeysWithValues: protocols.map { ($0.name.lowercased(), $0) })
    }()

    /// Maps alternative protocol names (IRDB / IrScrutinizer spellings) to canonical names.
    private static let aliases: [String: String] = [
        "nec1": "NEC",
        "nec1-rnc": "NEC",
        "necx1": "NECx",
        "necx2": "NECx",
        "nec1-f16": "NEC-f16",
        "sony12": "SIRC",
        "sony15": "SIRC15",
        "sony20": "SIRC20",
        "samsung": "Samsung32",
        "rc5x": "RC5X",
        "rc6-0-16": "RC6",
        "rc6-6-32": "MCE",
    ]

    /// Returns the canonical protocol name, or `nil` if the protocol is unknown.
    public static func canonicalName(for name: String) -> String? {
        var key = name.trimmingCharacters(in: .whitespaces).lowercased()
        // IRDB writes variants like "JVC{2}" or "Sharp{1}".
        if let brace = key.firstIndex(of: "{") { key = String(key[..<brace]) }
        if let alias = aliases[key] { return alias }
        return infoByKey[key]?.name
    }

    public static func info(for name: String) -> IRProtocolInfo? {
        guard let canonical = canonicalName(for: name) else { return nil }
        return infoByKey[canonical.lowercased()]
    }

    public static func isSupported(_ name: String) -> Bool { canonicalName(for: name) != nil }

    /// Toggle bit state shared by RC5/RC6/MCE so consecutive presses of the same
    /// button are recognised as separate key presses by the receiver.
    private static let toggleState = Mutex<Bool>(false)

    private static func nextToggle() -> UInt64 {
        toggleState.withLock { value in
            value.toggle()
            return value ? 1 : 0
        }
    }

    /// Encodes a parsed signal into raw timings.
    /// - Parameter extraRepeats: additional frames appended after the protocol's
    ///   minimum (as if the button were held a little longer).
    public static func encode(_ signal: ParsedSignal, extraRepeats: Int = 0, toggle: Bool? = nil) throws -> RawSignal {
        guard let name = canonicalName(for: signal.protocolName),
              let info = infoByKey[name.lowercased()] else {
            throw IRProtocolError.unsupportedProtocol(signal.protocolName)
        }
        let a = UInt64(signal.address)
        let c = UInt64(signal.command)
        let s = signal.subdevice.map(UInt64.init)
        let repeats = max(0, extraRepeats)
        let t: UInt64 = toggle.map { $0 ? 1 : 0 } ?? (["RC5", "RC5X", "RC6", "MCE"].contains(name) ? nextToggle() : 0)
        var b = PulseBuilder()

        switch name {
        case "NEC", "NEC2", "NECx", "NEC-f16":
            let header = name == "NECx" ? (4_500, 4_500) : (9_000, 4_500)
            let sub = s ?? (name == "NECx" ? a & 0xFF : ~a & 0xFF)
            var data = (a & 0xFF) | (sub & 0xFF) << 8
            if name == "NEC-f16" {
                data |= (c & 0xFFFF) << 16
            } else {
                data |= (c & 0xFF) << 16 | (~c & 0xFF) << 24
            }
            let frames = name == "NEC" ? 1 : 1 + repeats
            for _ in 0..<frames { necFrame(&b, header: header, data: data, bits: 32) }
            if name == "NEC" { for _ in 0..<repeats { necRepeat(&b) } }
        case "NECext":
            let data = (a & 0xFFFF) | (c & 0xFFFF) << 16
            necFrame(&b, header: (9_000, 4_500), data: data, bits: 32)
            for _ in 0..<repeats { necRepeat(&b) }
        case "NEC42":
            let data = (a & 0x1FFF) | (~a & 0x1FFF) << 13 | (c & 0xFF) << 26 | (~c & 0xFF) << 34
            necFrame(&b, header: (9_000, 4_500), data: data, bits: 42)
            for _ in 0..<repeats { necRepeat(&b) }
        case "NEC42ext":
            let data = (a & 0x3FF_FFFF) | (c & 0xFFFF) << 26
            necFrame(&b, header: (9_000, 4_500), data: data, bits: 42)
            for _ in 0..<repeats { necRepeat(&b) }

        case "Samsung32":
            let data = (a & 0xFF) | ((s ?? a) & 0xFF) << 8 | (c & 0xFF) << 16 | (~c & 0xFF) << 24
            for _ in 0...repeats {
                b.beginFrame()
                b.mark(4_500); b.space(4_500)
                b.bits(data, count: 32, order: .lsbFirst, encoding: .pulseDistance(mark: 550, zeroSpace: 550, oneSpace: 1_650))
                b.mark(550)
                b.padFrame(to: 108_000, minimumGap: 46_000)
            }

        case "RC5", "RC5X":
            let command = name == "RC5X" ? (c & 0x3F) | 0x40 : c & 0x7F
            let field: UInt64 = command & 0x40 == 0 ? 1 : 0
            let data = 1 << 13 | field << 12 | t << 11 | (a & 0x1F) << 6 | (command & 0x3F)
            let manchester = BitEncoding(zero: [889, -889], one: [-889, 889])
            for _ in 0...repeats {
                b.beginFrame()
                b.bits(data, count: 14, order: .msbFirst, encoding: manchester)
                b.padFrame(to: 113_778, minimumGap: 2_700)
            }

        case "RC6", "MCE":
            let unit = 444
            let bit = BitEncoding(zero: [-unit, unit], one: [unit, -unit])
            let trailer = BitEncoding(zero: [-2 * unit, 2 * unit], one: [2 * unit, -2 * unit])
            for _ in 0...repeats {
                b.beginFrame()
                b.mark(6 * unit); b.space(2 * unit)
                b.bits(1, count: 1, order: .msbFirst, encoding: bit)
                if name == "RC6" {
                    b.bits(0, count: 3, order: .msbFirst, encoding: bit)
                    b.bits(t, count: 1, order: .msbFirst, encoding: trailer)
                    b.bits(a & 0xFF, count: 8, order: .msbFirst, encoding: bit)
                    b.bits(c & 0xFF, count: 8, order: .msbFirst, encoding: bit)
                } else {
                    // MCE: mode 6, trailer 0, OEM1 = 0x80, OEM2 = subdevice (default 0x0F).
                    b.bits(6, count: 3, order: .msbFirst, encoding: bit)
                    b.bits(0, count: 1, order: .msbFirst, encoding: trailer)
                    b.bits(0x80, count: 8, order: .msbFirst, encoding: bit)
                    b.bits(s ?? 0x0F, count: 8, order: .msbFirst, encoding: bit)
                    b.bits(t, count: 1, order: .msbFirst, encoding: bit)
                    b.bits(a & 0x7F, count: 7, order: .msbFirst, encoding: bit)
                    b.bits(c & 0xFF, count: 8, order: .msbFirst, encoding: bit)
                }
                b.padFrame(to: 107_000, minimumGap: 2_700)
            }

        case "SIRC", "SIRC15", "SIRC20":
            let addressBits = name == "SIRC" ? 5 : name == "SIRC15" ? 8 : 13
            var address = a
            if name == "SIRC20", let s { address = (a & 0x1F) | (s & 0xFF) << 5 }
            let data = (c & 0x7F) | (address & ((1 << UInt64(addressBits)) - 1)) << 7
            for _ in 0..<(info.minimumFrames + repeats) {
                b.beginFrame()
                b.mark(2_400); b.space(600)
                b.bits(data, count: 7 + addressBits, order: .lsbFirst, encoding: .pulseWidth(zeroMark: 600, oneMark: 1_200, space: 600))
                b.padFrame(to: 45_000)
            }

        case "Kaseikyo", "Panasonic":
            let bytes: [UInt64]
            if name == "Kaseikyo" {
                let id = (a >> 24) & 3
                let vendor = (a >> 8) & 0xFFFF
                let genre1 = (a >> 4) & 0xF
                let genre2 = a & 0xF
                let v0 = vendor & 0xFF, v1 = vendor >> 8
                var parity = v0 ^ v1
                parity = (parity & 0xF) ^ (parity >> 4)
                let d2 = (parity & 0xF) | genre1 << 4
                let d3 = (genre2 & 0xF) | (c & 0xF) << 4
                let d4 = (id << 6) | ((c >> 4) & 0x3F)
                bytes = [v0, v1, d2, d3, d4, d2 ^ d3 ^ d4]
            } else {
                let d = a & 0xFF, sub = (s ?? 0) & 0xFF, f = c & 0xFF
                bytes = [0x02, 0x20, d, sub, f, d ^ sub ^ f]
            }
            let unit = 432
            for _ in 0...repeats {
                b.mark(8 * unit); b.space(4 * unit)
                for byte in bytes {
                    b.bits(byte, count: 8, order: .lsbFirst, encoding: .pulseDistance(mark: unit, zeroSpace: unit, oneSpace: 3 * unit))
                }
                b.mark(unit)
                b.space(173 * unit)
            }

        case "RCA":
            let data = (a & 0xF) | (c & 0xFF) << 4 | (~a & 0xF) << 12 | (~c & 0xFF) << 16
            for _ in 0...repeats {
                b.mark(4_000); b.space(4_000)
                b.bits(data, count: 24, order: .lsbFirst, encoding: .pulseDistance(mark: 500, zeroSpace: 1_000, oneSpace: 2_000))
                b.mark(500)
                b.space(8_000)
            }
        case "RCA-IRP", "RCA-38":
            let unit = 460
            let data = (a & 0xF) << 20 | (c & 0xFF) << 12 | (~a & 0xF) << 8 | (~c & 0xFF)
            for _ in 0...repeats {
                b.mark(8 * unit); b.space(8 * unit)
                b.bits(data, count: 24, order: .msbFirst, encoding: .pulseDistance(mark: unit, zeroSpace: 2 * unit, oneSpace: 4 * unit))
                b.mark(unit)
                b.space(16 * unit)
            }

        case "Pioneer":
            let sub = s ?? (~a & 0xFF)
            let data = (a & 0xFF) | (sub & 0xFF) << 8 | (c & 0xFF) << 16 | (~c & 0xFF) << 24
            for _ in 0..<(info.minimumFrames + repeats) {
                b.mark(8_500); b.space(4_225)
                b.bits(data, count: 32, order: .lsbFirst, encoding: .pulseDistance(mark: 500, zeroSpace: 500, oneSpace: 1_500))
                b.mark(500)
                b.space(26_000)
            }

        case "JVC":
            let data = (a & 0xFF) | (c & 0xFF) << 8
            for frame in 0...repeats {
                b.beginFrame()
                if frame == 0 { b.mark(8_400); b.space(4_200) }
                b.bits(data, count: 16, order: .lsbFirst, encoding: .pulseDistance(mark: 525, zeroSpace: 525, oneSpace: 1_575))
                b.mark(525)
                b.padFrame(to: 59_080, minimumGap: 10_000)
            }

        case "Mitsubishi":
            let data = (a & 0xFF) | (c & 0xFF) << 8
            for _ in 0...repeats {
                b.bits(data, count: 16, order: .lsbFirst, encoding: .pulseDistance(mark: 300, zeroSpace: 900, oneSpace: 2_100))
                b.mark(300)
                b.space(24_000)
            }

        case "Aiwa":
            let sub = (s ?? 0) & 0x1F
            let d = a & 0xFF, f = c & 0xFF
            var data = d | sub << 8 | (~d & 0xFF) << 13 | (~sub & 0x1F) << 21
            data |= f << 26 | (~f & 0xFF) << 34
            b.mark(8_800); b.space(4_400)
            b.bits(data, count: 42, order: .lsbFirst, encoding: .pulseDistance(mark: 550, zeroSpace: 550, oneSpace: 1_650))
            b.mark(550); b.space(23_100)
            for _ in 0..<repeats {
                b.mark(8_800); b.space(4_400); b.mark(550); b.space(90_750)
            }

        case "Denon", "Sharp":
            let unit = 264
            let encoding = BitEncoding.pulseDistance(mark: unit, zeroSpace: 3 * unit, oneSpace: 7 * unit)
            let (first, second): (UInt64, UInt64) = name == "Denon" ? (0, 3) : (1, 2)
            for _ in 0...repeats {
                b.bits((a & 0x1F) | (c & 0xFF) << 5 | first << 13, count: 15, order: .lsbFirst, encoding: encoding)
                b.mark(unit); b.space(165 * unit)
                b.bits((a & 0x1F) | (~c & 0xFF) << 5 | second << 13, count: 15, order: .lsbFirst, encoding: encoding)
                b.mark(unit); b.space(165 * unit)
            }

        default:
            throw IRProtocolError.unsupportedProtocol(signal.protocolName)
        }

        guard !b.timings.isEmpty else { throw IRProtocolError.emptySignal }
        return RawSignal(frequency: info.frequency, dutyCycle: 0.33, timings: b.timings)
    }

    private static func necFrame(_ b: inout PulseBuilder, header: (Int, Int), data: UInt64, bits: Int) {
        b.beginFrame()
        b.mark(header.0); b.space(header.1)
        b.bits(data, count: bits, order: .lsbFirst, encoding: .pulseDistance(mark: 560, zeroSpace: 560, oneSpace: 1_690))
        b.mark(560)
        b.padFrame(to: 108_000, minimumGap: 10_000)
    }

    private static func necRepeat(_ b: inout PulseBuilder) {
        b.beginFrame()
        b.mark(9_000); b.space(2_250); b.mark(560)
        b.padFrame(to: 108_000)
    }
}
