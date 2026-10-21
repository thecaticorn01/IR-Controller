import Foundation

public enum IRFormatError: LocalizedError, Equatable, Sendable {
    case notAnIRFile
    case malformedSignal(name: String, reason: String)
    case noSignals
    case unrecognizedCode

    public var errorDescription: String? {
        switch self {
        case .notAnIRFile: "The file is not a Flipper Zero infrared (.ir) file."
        case .malformedSignal(let name, let reason): "The signal “\(name)” is malformed: \(reason)."
        case .noSignals: "The file does not contain any infrared signals."
        case .unrecognizedCode: "The code could not be recognised. Paste Pronto hex, Global Caché sendir, or a list of mark/space timings in microseconds."
        }
    }
}

/// Reader and writer for Flipper Zero `.ir` signal files, the format used by
/// the Flipper-IRDB database.
public enum FlipperIRFile {

    public struct ParseResult: Sendable {
        public var buttons: [IRButton]
        /// Signals that could not be parsed, with the reason.
        public var skipped: [(name: String, reason: String)]
    }

    /// Parses the textual contents of a `.ir` file.
    public static func parse(_ text: String) throws -> ParseResult {
        var buttons: [IRButton] = []
        var skipped: [(String, String)] = []
        var sawHeader = false
        var block: [String: String] = [:]

        func flush() {
            defer { block = [:] }
            guard let name = block["name"] else { return }
            do {
                buttons.append(IRButton(name: name, signal: try signal(from: block, name: name)))
            } catch IRFormatError.malformedSignal(_, let reason) {
                skipped.append((name, reason))
            } catch {
                skipped.append((name, error.localizedDescription))
            }
        }

        for rawLine in text.split(omittingEmptySubsequences: true, whereSeparator: \.isNewline) {
            let line = rawLine.trimmingCharacters(in: .whitespaces)
            if line.isEmpty { continue }
            if line.hasPrefix("#") { flush(); continue }
            guard let colon = line.firstIndex(of: ":") else { continue }
            let key = line[..<colon].trimmingCharacters(in: .whitespaces).lowercased()
            let value = line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
            switch key {
            case "filetype", "version":
                sawHeader = true
            case "name":
                flush()
                block["name"] = value
            default:
                block[key] = block[key].map { $0 + " " + value } ?? value
            }
        }
        flush()

        guard sawHeader || !buttons.isEmpty else { throw IRFormatError.notAnIRFile }
        guard !buttons.isEmpty || !skipped.isEmpty else { throw IRFormatError.noSignals }
        return ParseResult(buttons: buttons, skipped: skipped)
    }

    private static func signal(from block: [String: String], name: String) throws -> IRSignal {
        switch block["type"]?.lowercased() {
        case "parsed":
            guard let proto = block["protocol"], !proto.isEmpty else {
                throw IRFormatError.malformedSignal(name: name, reason: "missing protocol")
            }
            guard let address = block["address"].flatMap(littleEndianHex),
                  let command = block["command"].flatMap(littleEndianHex) else {
                throw IRFormatError.malformedSignal(name: name, reason: "invalid address or command")
            }
            return .parsed(ParsedSignal(protocolName: proto, address: address, command: command))
        case "raw":
            let frequency = block["frequency"].flatMap { Double($0) }.map { Int($0.rounded()) } ?? 38_000
            let duty = block["duty_cycle"].flatMap(Double.init) ?? 0.33
            guard let data = block["data"] else {
                throw IRFormatError.malformedSignal(name: name, reason: "missing data")
            }
            let timings = data.split(whereSeparator: { $0 == " " || $0 == "," || $0 == "\t" }).compactMap { Int($0) }
            guard !timings.isEmpty else {
                throw IRFormatError.malformedSignal(name: name, reason: "empty data")
            }
            return .raw(RawSignal(frequency: frequency, dutyCycle: duty, timings: timings.map(abs)))
        default:
            throw IRFormatError.malformedSignal(name: name, reason: "unknown signal type")
        }
    }

    /// Flipper stores 32-bit values as four little-endian hex bytes: "07 00 00 00".
    static func littleEndianHex(_ text: String) -> UInt32? {
        let parts = text.split(separator: " ")
        guard !parts.isEmpty, parts.count <= 4 else {
            // Some hand-written files use "0x07" style values.
            let trimmed = text.lowercased().replacingOccurrences(of: "0x", with: "")
            return UInt32(trimmed, radix: 16)
        }
        var value: UInt32 = 0
        for (index, part) in parts.enumerated() {
            guard let byte = UInt8(part, radix: 16) else { return nil }
            value |= UInt32(byte) << (8 * UInt32(index))
        }
        return value
    }

    static func littleEndianHexString(_ value: UInt32) -> String {
        (0..<4).map { String(format: "%02X", (value >> (8 * UInt32($0))) & 0xFF) }.joined(separator: " ")
    }

    /// Protocol names Flipper firmware understands natively in `parsed` signals.
    public static let nativeProtocols: Set<String> = [
        "NEC", "NECext", "NEC42", "NEC42ext", "Samsung32", "RC5", "RC5X", "RC6",
        "SIRC", "SIRC15", "SIRC20", "Kaseikyo", "RCA", "Pioneer",
    ]

    /// Serialises buttons to `.ir` text. Signals in protocols Flipper does not
    /// understand are rendered to raw timings so the file stays usable.
    public static func serialize(_ buttons: [IRButton]) -> String {
        var out = "Filetype: IR signals file\nVersion: 1\n"
        for button in buttons {
            out += "# \nname: \(button.name.replacingOccurrences(of: "\n", with: " "))\n"
            switch button.signal {
            case .parsed(let p) where p.subdevice == nil && nativeProtocols.contains(p.protocolName):
                out += "type: parsed\nprotocol: \(p.protocolName)\n"
                out += "address: \(littleEndianHexString(p.address))\n"
                out += "command: \(littleEndianHexString(p.command))\n"
            default:
                guard let raw = try? button.signal.rendered(toggle: false) else { continue }
                out += "type: raw\nfrequency: \(raw.frequency)\n"
                out += "duty_cycle: \(String(format: "%.6f", raw.dutyCycle))\n"
                out += "data: \(raw.timings.map(String.init).joined(separator: " "))\n"
            }
        }
        return out
    }
}

extension IRSignal {
    /// Renders with an explicit toggle bit (used for exports, which should be deterministic).
    public func rendered(toggle: Bool) throws -> RawSignal {
        switch self {
        case .raw: return try rendered()
        case .parsed(let p): return try IRProtocolRegistry.encode(p, toggle: toggle)
        }
    }
}
