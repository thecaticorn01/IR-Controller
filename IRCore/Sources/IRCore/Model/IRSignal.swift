import Foundation

/// A raw infrared waveform: alternating mark (carrier on) and space (carrier off)
/// durations in microseconds, always starting with a mark.
public struct RawSignal: Codable, Hashable, Sendable {
    /// Carrier frequency in hertz.
    public var frequency: Int
    /// Carrier duty cycle in the range 0...1. ElkSmart dongles ignore it, but it
    /// is preserved so codes round-trip through Flipper `.ir` files unchanged.
    public var dutyCycle: Double
    /// Mark/space durations in microseconds, starting with a mark.
    public var timings: [Int]

    public init(frequency: Int = 38_000, dutyCycle: Double = 0.33, timings: [Int]) {
        self.frequency = frequency
        self.dutyCycle = dutyCycle
        self.timings = timings
    }

    /// Total duration of the waveform in microseconds.
    public var duration: Int { timings.reduce(0) { $0 + max(0, $1) } }

    /// Pairs of (mark, space). An odd-length signal gets a trailing space of
    /// `trailingGap` so the final mark is not truncated by the transmitter.
    public func pulses(trailingGap: Int = 10_000) -> [(on: Int, off: Int)] {
        var result: [(on: Int, off: Int)] = []
        result.reserveCapacity((timings.count + 1) / 2)
        var index = 0
        while index < timings.count {
            let on = max(0, timings[index])
            let off = index + 1 < timings.count ? max(0, timings[index + 1]) : trailingGap
            result.append((on, off))
            index += 2
        }
        return result
    }

    /// Returns a copy with consecutive same-level durations merged and
    /// non-positive values removed, starting with a mark.
    public func normalized() -> RawSignal {
        var copy = self
        copy.timings = RawSignal.normalize(timings)
        return copy
    }

    static func normalize(_ timings: [Int]) -> [Int] {
        var out: [Int] = []
        out.reserveCapacity(timings.count)
        for (index, value) in timings.enumerated() where value > 0 {
            let isMark = index % 2 == 0
            let outIsMark = out.count % 2 == 0
            if isMark == outIsMark {
                out.append(value)
            } else if out.isEmpty {
                // Leading space: drop it, a waveform must start with a mark.
                continue
            } else {
                out[out.count - 1] += value
            }
        }
        return out
    }
}

/// A protocol-level code such as `NEC address 0x04 command 0x08`.
public struct ParsedSignal: Codable, Hashable, Sendable {
    /// Name of the protocol as understood by `IRProtocolRegistry`.
    public var protocolName: String
    public var address: UInt32
    public var command: UInt32
    /// Optional sub-device (IRP `S` parameter); `nil` means protocol default.
    public var subdevice: UInt32?

    public init(protocolName: String, address: UInt32, command: UInt32, subdevice: UInt32? = nil) {
        self.protocolName = protocolName
        self.address = address
        self.command = command
        self.subdevice = subdevice
    }
}

/// Any infrared code the app knows how to transmit.
public enum IRSignal: Codable, Hashable, Sendable {
    case parsed(ParsedSignal)
    case raw(RawSignal)

    /// Renders the signal to a transmittable waveform.
    public func rendered(repeats: Int = 0) throws -> RawSignal {
        switch self {
        case .raw(let raw):
            guard !raw.timings.isEmpty else { throw IRProtocolError.emptySignal }
            if repeats <= 0 { return raw.normalized() }
            var timings = raw.normalized().timings
            if timings.count % 2 == 1 { timings.append(40_000) }
            let frame = timings
            for _ in 0..<repeats { timings += frame }
            return RawSignal(frequency: raw.frequency, dutyCycle: raw.dutyCycle, timings: timings)
        case .parsed(let parsed):
            return try IRProtocolRegistry.encode(parsed, extraRepeats: repeats)
        }
    }

    /// Short human readable description, e.g. "NEC · 0x04 / 0x08" or "Raw · 38 kHz · 67 pulses".
    public var summary: String {
        switch self {
        case .parsed(let p):
            var text = "\(p.protocolName) · A 0x\(String(p.address, radix: 16, uppercase: true))"
            if let s = p.subdevice { text += ".\(String(s, radix: 16, uppercase: true))" }
            text += " · C 0x\(String(p.command, radix: 16, uppercase: true))"
            return text
        case .raw(let r):
            let khz = Double(r.frequency) / 1000
            return "Raw · \(String(format: "%.1f", khz)) kHz · \(r.timings.count) timings"
        }
    }

    public var isRaw: Bool {
        if case .raw = self { return true }
        return false
    }
}
