import Foundation

/// Best-effort identification of common protocols in captured raw waveforms.
/// Used to label learned buttons (e.g. "Detected NEC 0x04/0x08").
public enum IRProtocolDecoder {

    public static func decode(_ raw: RawSignal) -> ParsedSignal? {
        let t = raw.normalized().timings
        guard t.count >= 10 else { return nil }
        return decodeNECFamily(t) ?? decodeSIRC(t) ?? decodeRC5(t)
    }

    private static func near(_ value: Int, _ target: Int, tolerance: Double = 0.3) -> Bool {
        let delta = Double(target) * tolerance
        return Double(value) >= Double(target) - delta && Double(value) <= Double(target) + delta
    }

    /// Reads pulse-distance bits starting at `index` (which must point to a mark).
    private static func readPulseDistance(_ t: [Int], from index: Int, count: Int, threshold: Int) -> UInt64? {
        var value: UInt64 = 0
        var i = index
        for bit in 0..<count {
            guard i + 1 < t.count else { return nil }
            let mark = t[i], space = t[i + 1]
            guard mark < threshold, space < threshold * 3 else { return nil }
            if space > threshold { value |= 1 << UInt64(bit) }
            i += 2
        }
        return value
    }

    private static func decodeNECFamily(_ t: [Int]) -> ParsedSignal? {
        let isNEC = near(t[0], 9_000, tolerance: 0.2) && near(t[1], 4_500, tolerance: 0.25)
        let isSamsung = near(t[0], 4_500, tolerance: 0.2) && near(t[1], 4_500, tolerance: 0.25)
        guard isNEC || isSamsung else { return nil }

        // NEC42 frames have 42 data bits; detect by counting short marks before the gap.
        var bitCount = 0
        var i = 2
        while i + 1 < t.count, t[i] < 1_200, t[i + 1] < 2_500 { bitCount += 1; i += 2 }

        if isNEC, bitCount >= 42, let data = readPulseDistance(t, from: 2, count: 42, threshold: 1_100) {
            let address = data & 0x1FFF
            let addressInverse = (data >> 13) & 0x1FFF
            let command = (data >> 26) & 0xFF
            let commandInverse = (data >> 34) & 0xFF
            if address ^ addressInverse == 0x1FFF, command ^ commandInverse == 0xFF {
                return ParsedSignal(protocolName: "NEC42", address: UInt32(address), command: UInt32(command))
            }
            return ParsedSignal(protocolName: "NEC42ext", address: UInt32(data & 0x3FF_FFFF), command: UInt32((data >> 26) & 0xFFFF))
        }

        guard bitCount >= 32, let data = readPulseDistance(t, from: 2, count: 32, threshold: 1_100) else { return nil }
        let b0 = data & 0xFF, b1 = (data >> 8) & 0xFF, b2 = (data >> 16) & 0xFF, b3 = (data >> 24) & 0xFF
        let commandOK = b2 ^ b3 == 0xFF
        if isSamsung {
            if b0 == b1, commandOK {
                return ParsedSignal(protocolName: "Samsung32", address: UInt32(b0), command: UInt32(b2))
            }
            return ParsedSignal(protocolName: "NECx", address: UInt32(b0), command: UInt32(b2), subdevice: UInt32(b1))
        }
        if b0 ^ b1 == 0xFF, commandOK {
            return ParsedSignal(protocolName: "NEC", address: UInt32(b0), command: UInt32(b2))
        }
        return ParsedSignal(protocolName: "NECext", address: UInt32(b0 | b1 << 8), command: UInt32(b2 | b3 << 8))
    }

    private static func decodeSIRC(_ t: [Int]) -> ParsedSignal? {
        guard near(t[0], 2_400, tolerance: 0.2), near(t[1], 600, tolerance: 0.4) else { return nil }
        var value: UInt64 = 0
        var bits = 0
        var i = 2
        while i < t.count, bits < 20 {
            let mark = t[i]
            if near(mark, 1_200, tolerance: 0.3) { value |= 1 << UInt64(bits) }
            else if !near(mark, 600, tolerance: 0.4) { break }
            bits += 1
            // A long space terminates the frame.
            if i + 1 >= t.count || t[i + 1] > 1_500 { break }
            i += 2
        }
        let command = UInt32(value & 0x7F)
        switch bits {
        case 12: return ParsedSignal(protocolName: "SIRC", address: UInt32((value >> 7) & 0x1F), command: command)
        case 15: return ParsedSignal(protocolName: "SIRC15", address: UInt32((value >> 7) & 0xFF), command: command)
        case 20: return ParsedSignal(protocolName: "SIRC20", address: UInt32((value >> 7) & 0x1FFF), command: command)
        default: return nil
        }
    }

    /// Decodes Philips RC5 by sampling the bi-phase waveform at half-bit resolution.
    private static func decodeRC5(_ t: [Int]) -> ParsedSignal? {
        let half = 889
        // Expand into half-bit levels, starting with the implicit leading space of the start bit.
        var levels: [Bool] = [false]
        for (index, duration) in t.enumerated() {
            let units = Int((Double(duration) / Double(half)).rounded())
            if index > 0, duration > 4 * half { break } // end of frame
            guard units == 1 || units == 2 else { return nil }
            levels.append(contentsOf: Array(repeating: index % 2 == 0, count: units))
            if levels.count >= 28 { break }
        }
        if levels.count == 27 { levels.append(false) } // trailing space of a final 0 bit
        guard levels.count >= 28 else { return nil }
        var value: UInt64 = 0
        for bit in 0..<14 {
            let first = levels[bit * 2], second = levels[bit * 2 + 1]
            guard first != second else { return nil }
            value = value << 1 | (second ? 1 : 0)
        }
        guard value >> 13 == 1 else { return nil }
        let field = (value >> 12) & 1
        let address = UInt32((value >> 6) & 0x1F)
        let command = UInt32(value & 0x3F) | (field == 0 ? 0x40 : 0)
        return ParsedSignal(protocolName: "RC5", address: address, command: command)
    }
}
