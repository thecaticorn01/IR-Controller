import Foundation

/// A bidirectional USB bulk pipe pair. Implemented by the macOS app on top of
/// IOUSBHost; tests use an in-memory fake.
public protocol BulkTransport: AnyObject {
    /// Writes one bulk packet.
    func write(_ bytes: [UInt8], timeout: TimeInterval) throws
    /// Reads one bulk packet. Returns `nil` if nothing arrived before the timeout.
    func read(maxLength: Int, timeout: TimeInterval) throws -> [UInt8]?
}

/// Known ELK Smart dongle models (USB vendor 0x045C).
public struct ElkSmartModel: Hashable, Sendable {
    public static let vendorID: Int = 0x045C

    public let productID: Int
    public let name: String

    public static let known: [ElkSmartModel] = [
        .init(productID: 0x0184, name: "ELK Smart EKX5S-T (USB-C)"),
        .init(productID: 0x0195, name: "ELK Smart EKX4S"),
        .init(productID: 0x0134, name: "ELK Smart EKX5S"),
        .init(productID: 0x0132, name: "ELK Smart IR Blaster"),
        .init(productID: 0x0131, name: "ELK Smart IR Blaster"),
        .init(productID: 0x014A, name: "ELK Smart IR Blaster"),
        .init(productID: 0x02AA, name: "ELK Smart IR Blaster"),
    ]

    public static func model(forProductID productID: Int) -> ElkSmartModel {
        known.first { $0.productID == productID }
            ?? ElkSmartModel(productID: productID, name: String(format: "ELK Smart device (0x%04X)", productID))
    }

    public static var knownProductIDs: [Int] { known.map(\.productID) }
}

public enum ElkSmartError: LocalizedError, Sendable, Equatable {
    case noResponse
    case unsupportedDevice(response: [UInt8])
    case signalTooLong
    case learnTimedOut
    case learnCancelled
    case unexpectedResponse([UInt8])

    public var errorDescription: String? {
        switch self {
        case .noResponse:
            "The IR blaster did not respond. Unplug it, plug it back in and try again."
        case .unsupportedDevice(let bytes):
            "The IR blaster returned an unrecognised identity (\(bytes.hexString)). It may be busy or unsupported."
        case .signalTooLong:
            "The signal is too long to send with this IR blaster."
        case .learnTimedOut:
            "No infrared signal was received. Point the remote at the blaster from about 1 cm away and press the button again."
        case .learnCancelled:
            "Learning was cancelled."
        case .unexpectedResponse(let bytes):
            "The IR blaster sent an unexpected response (\(bytes.prefix(8).hexString))."
        }
    }
}

/// How the dongle expects transmit payloads to be encoded.
public enum ElkSmartPayloadEncoding: String, Sendable {
    /// EKX4S and older: plain compressed pulses (identity "70 01" or "FA FA FA FA").
    case plain
    /// EKX5S-T and newer: Huffman-coded payload (identity "02 AA").
    case huffman
}

/// Implements the ELK Smart USB protocol over a `BulkTransport`.
/// Not thread-safe: drive it from a single serial queue.
public final class ElkSmartDriver {
    public let transport: BulkTransport
    public private(set) var encoding: ElkSmartPayloadEncoding?
    public private(set) var identity: [UInt8] = []

    /// Delay between consecutive bulk packets of one message.
    public var interPacketDelay: TimeInterval = 0.002

    public init(transport: BulkTransport) {
        self.transport = transport
    }

    /// Flushes stale input and asks the dongle to identify itself.
    @discardableResult
    public func identify() throws -> ElkSmartPayloadEncoding {
        drain(for: 0.05)
        var response: [UInt8]?
        for _ in 0..<4 {
            try transport.write(ElkSmartCodec.commandIdentify, timeout: 0.2)
            if let bytes = try transport.read(maxLength: 64, timeout: 0.5), !bytes.isEmpty {
                response = bytes
                break
            }
        }
        guard let bytes = response else { throw ElkSmartError.noResponse }
        identity = bytes
        guard let encoding = Self.classify(bytes) else { throw ElkSmartError.unsupportedDevice(response: bytes) }
        self.encoding = encoding
        return encoding
    }

    static func classify(_ b: [UInt8]) -> ElkSmartPayloadEncoding? {
        guard b.count >= 6 else { return nil }
        let prefix = Array(b.prefix(4))
        if prefix == ElkSmartCodec.commandIdentify {
            if b[4] == 0x02 && b[5] == 0xAA { return .huffman }
            if b[4] == 0x70 && b[5] == 0x01 { return .plain }
            return nil
        }
        if prefix == [0xFA, 0xFA, 0xFA, 0xFA] { return .plain }
        return nil
    }

    /// Transmits a waveform and waits until the dongle has finished emitting it.
    public func transmit(_ signal: RawSignal) throws {
        if encoding == nil { try identify() }
        let pulses = signal.normalized().pulses()
        guard !pulses.isEmpty else { throw IRProtocolError.emptySignal }
        let message = ElkSmartCodec.transmitMessage(
            frequency: signal.frequency, pulses: pulses, huffman: encoding == .huffman)
        guard message.count - 9 <= ElkSmartCodec.maximumPayloadLength else { throw ElkSmartError.signalTooLong }
        for packet in ElkSmartCodec.packets(for: message) {
            try transport.write(packet, timeout: 0.1)
            Thread.sleep(forTimeInterval: interPacketDelay)
        }
        // The dongle buffers the code and emits it afterwards; give it time to
        // finish before the next command so codes are not cut short.
        let emitTime = Double(pulses.reduce(0) { $0 + $1.on + $1.off }) / 1_000_000
        Thread.sleep(forTimeInterval: max(0.08, emitTime + 0.025))
        drain(for: 0.03)
    }

    /// Puts the dongle into learning mode and waits for a code.
    /// - Parameters:
    ///   - timeout: how long to wait for the user to press a button.
    ///   - isCancelled: polled roughly every 100 ms.
    ///   - frequency: carrier frequency to record (the dongle does not measure it).
    public func learn(timeout: TimeInterval, frequency: Int = 38_000, isCancelled: () -> Bool) throws -> RawSignal {
        if encoding == nil { try identify() }
        try transport.write(ElkSmartCodec.commandLearn, timeout: 0.2)
        defer { try? transport.write(ElkSmartCodec.commandStop, timeout: 0.1) }

        let deadline = Date().addingTimeInterval(timeout)
        var first: [UInt8]?
        while first == nil {
            if isCancelled() { throw ElkSmartError.learnCancelled }
            if Date() > deadline { throw ElkSmartError.learnTimedOut }
            if let packet = try transport.read(maxLength: 64, timeout: 0.1), !packet.isEmpty {
                first = packet
            }
        }
        guard let header = first, header.count >= 6, Array(header.prefix(4)) == ElkSmartCodec.commandLearn else {
            throw ElkSmartError.unexpectedResponse(first ?? [])
        }
        let expected = Int(header[4]) << 8 | Int(header[5])
        var data = Array(header.dropFirst(6))
        // The dongle streams the code in pieces with pauses between them.
        var idleDeadline = Date().addingTimeInterval(1.0)
        while data.count < expected, Date() < idleDeadline {
            if isCancelled() { throw ElkSmartError.learnCancelled }
            if let packet = try transport.read(maxLength: 64, timeout: 0.1), !packet.isEmpty {
                data += packet
                idleDeadline = Date().addingTimeInterval(1.0)
            }
        }
        if expected > 0, data.count > expected { data = Array(data.prefix(expected)) }
        guard let pulses = ElkSmartCodec.decodeLearned(data), pulses.count >= 2 else {
            throw ElkSmartError.learnTimedOut
        }
        var timings: [Int] = []
        for pulse in pulses {
            timings.append(pulse.on)
            timings.append(pulse.off)
        }
        // Replace the meaningless final silence with a conventional gap.
        if let last = timings.last, last == 0 || last > 100_000 { timings[timings.count - 1] = 50_000 }
        return RawSignal(frequency: frequency, dutyCycle: 0.33, timings: timings).normalized()
    }

    private func drain(for duration: TimeInterval) {
        let deadline = Date().addingTimeInterval(duration)
        while Date() < deadline {
            guard let packet = try? transport.read(maxLength: 64, timeout: 0.01), !packet.isEmpty else { break }
        }
    }
}

extension Collection where Element == UInt8 {
    public var hexString: String { map { String(format: "%02X", $0) }.joined(separator: " ") }
}
