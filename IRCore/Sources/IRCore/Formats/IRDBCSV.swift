import Foundation

/// Parser for the CSV files of the IRDB database (github.com/probonopd/irdb):
/// `functionname,protocol,device,subdevice,function`.
public enum IRDBCSV {

    public struct ParseResult: Sendable {
        public var buttons: [IRButton]
        /// Protocols present in the file that cannot be transmitted yet.
        public var unsupportedProtocols: Set<String>
    }

    public static func parse(_ text: String) -> ParseResult {
        var buttons: [IRButton] = []
        var unsupported: Set<String> = []
        for (index, rawLine) in text.split(whereSeparator: \.isNewline).enumerated() {
            let line = rawLine.trimmingCharacters(in: .whitespaces)
            if line.isEmpty { continue }
            if index == 0, line.lowercased().hasPrefix("functionname") { continue }
            // The function name may itself contain commas, so split from the right.
            let rawFields = line.split(separator: ",", omittingEmptySubsequences: false).map(String.init)
            guard rawFields.count >= 5 else { continue }
            let tail = rawFields.suffix(4).map { $0.trimmingCharacters(in: .whitespaces) }
            let name = rawFields.dropLast(4).joined(separator: ",")
                .trimmingCharacters(in: CharacterSet(charactersIn: " \""))
            let protocolName = tail[0]
            guard let device = Int(tail[1]), let subdevice = Int(tail[2]), let function = Int(tail[3]),
                  device >= 0, function >= 0 else { continue }
            guard let canonical = IRProtocolRegistry.canonicalName(for: protocolName) else {
                unsupported.insert(protocolName)
                continue
            }
            let signal = ParsedSignal(
                protocolName: canonical,
                address: UInt32(device),
                command: UInt32(function),
                subdevice: subdevice >= 0 ? UInt32(subdevice) : nil
            )
            buttons.append(IRButton(name: prettify(name), signal: .parsed(signal)))
        }
        return ParseResult(buttons: buttons, unsupportedProtocols: unsupported)
    }

    /// "KEY_VOLUMEUP" → "Volume Up", "POWER" → "Power".
    static func prettify(_ name: String) -> String {
        var text = name
        if text.uppercased().hasPrefix("KEY_") { text = String(text.dropFirst(4)) }
        let known: [String: String] = [
            "VOLUMEUP": "Volume Up", "VOLUMEDOWN": "Volume Down", "CHANNELUP": "Channel Up",
            "CHANNELDOWN": "Channel Down", "FASTFORWARD": "Fast Forward", "PLAYPAUSE": "Play/Pause",
        ]
        if let mapped = known[text.uppercased()] { return mapped }
        let words = text.replacingOccurrences(of: "_", with: " ").split(separator: " ")
        guard text == text.uppercased() else { return words.joined(separator: " ") }
        return words.map { $0.prefix(1).uppercased() + $0.dropFirst().lowercased() }.joined(separator: " ")
    }
}

/// Imports a single code pasted by the user in one of several common text formats.
public enum IRCodeImporter {

    public enum Format: String, Sendable {
        case pronto = "Pronto Hex"
        case sendir = "Global Caché sendir"
        case timings = "Raw timings"
    }

    public static func parse(_ text: String, defaultFrequency: Int = 38_000) throws -> (RawSignal, Format) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { throw IRFormatError.unrecognizedCode }
        if trimmed.lowercased().hasPrefix("sendir") {
            return (try parseSendIR(trimmed), .sendir)
        }
        let words = trimmed.split(whereSeparator: { $0.isWhitespace || $0 == "," })
        if words.count >= 6, words.allSatisfy({ $0.count == 4 && UInt16($0, radix: 16) != nil }), words[0] == "0000" {
            return (try parsePronto(words.map(String.init)), .pronto)
        }
        let numbers = words.compactMap { Int($0.replacingOccurrences(of: "+", with: "")) }
        guard numbers.count == words.count, numbers.count >= 2 else { throw IRFormatError.unrecognizedCode }
        return (RawSignal(frequency: defaultFrequency, timings: numbers.map(abs)).normalized(), .timings)
    }

    /// Pronto "learned" format (0000 FFFF NNNN RRRR pairs...).
    public static func parsePronto(_ words: [String]) throws -> RawSignal {
        let values = words.compactMap { Int($0, radix: 16) }
        guard values.count >= 4, values[0] == 0, values[1] > 0 else { throw IRFormatError.unrecognizedCode }
        let frequency = 1_000_000.0 / (Double(values[1]) * 0.241246)
        let period = 1_000_000.0 / frequency
        let once = values[2] * 2, repeating = values[3] * 2
        guard values.count >= 4 + once + repeating, once + repeating > 0 else { throw IRFormatError.unrecognizedCode }
        let burst = values[4...].prefix(once + repeating).map { Int((Double($0) * period).rounded()) }
        var timings = Array(burst.prefix(once))
        timings += burst.suffix(repeating)
        return RawSignal(frequency: Int(frequency.rounded()), timings: timings).normalized()
    }

    /// Global Caché: sendir,<module:port>,<id>,<freq>,<repeat>,<offset>,<on>,<off>,...
    public static func parseSendIR(_ text: String) throws -> RawSignal {
        let fields = text.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }
        guard fields.count > 8, let frequency = Int(fields[3]), frequency > 0 else { throw IRFormatError.unrecognizedCode }
        let period = 1_000_000.0 / Double(frequency)
        let cycles = fields[6...].compactMap { Int($0) }
        guard cycles.count >= 2 else { throw IRFormatError.unrecognizedCode }
        return RawSignal(frequency: frequency, timings: cycles.map { Int((Double($0) * period).rounded()) }).normalized()
    }

    /// Renders a signal as Pronto hex, useful for sharing codes.
    public static func prontoHex(for raw: RawSignal) -> String {
        let frequency = max(raw.frequency, 1)
        let code = Int((1_000_000.0 / (Double(frequency) * 0.241246)).rounded())
        let period = 1_000_000.0 / Double(frequency)
        var timings = raw.timings
        if timings.count % 2 == 1 { timings.append(40_000) }
        var words = [0, code, timings.count / 2, 0]
        words += timings.map { max(1, Int((Double($0) / period).rounded())) }
        return words.map { String(format: "%04X", min($0, 0xFFFF)) }.joined(separator: " ")
    }
}
