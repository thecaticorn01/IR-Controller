import Foundation
import Testing
@testable import IRCore

/// Vectors produced by the reference C implementation (elksmart-comm).
@Suite struct ElkSmartCodecTests {

    private func hex(_ bytes: [UInt8]) -> String { bytes.map { String(format: "%02x", $0) }.joined() }

    /// Mirrors elksmart-comm's `encode_nec`, used to generate the reference vectors.
    private func referenceNECPulses(_ bytes: [UInt8]) -> [ElkSmartCodec.Pulse] {
        var pulses: [ElkSmartCodec.Pulse] = []
        func byte(_ b: UInt8) {
            for i in stride(from: 7, through: 0, by: -1) {
                pulses.append((550, (b >> UInt8(i)) & 1 == 1 ? 1650 : 550))
            }
        }
        for i in stride(from: 0, to: bytes.count, by: 2) {
            pulses.append((8500, 4250))
            byte(bytes[i]); byte(~bytes[i]); byte(bytes[i + 1]); byte(~bytes[i + 1])
            pulses.append((550, 25000))
        }
        return pulses
    }

    @Test func huffmanMatchesReferenceVectors() {
        #expect(hex(ElkSmartCodec.huffmanEncode([])) == "000000")
        #expect(hex(ElkSmartCodec.huffmanEncode([0])) == "000100000100")
        #expect(hex(ElkSmartCodec.huffmanEncode([0, 1, 2, 3])) == "00040000010100010200010300010039")
        #expect(hex(ElkSmartCodec.huffmanEncode(Array("ABRACADABRA".utf8))) == "00054100054200024300014400015200020159cf58")
    }

    @Test func plainTransmitMessage() {
        let message = ElkSmartCodec.transmitMessage(frequency: 38_000, pulses: referenceNECPulses([0x04, 0x08]), huffman: false)
        #expect(hex(message) == "ffffffffd6ef09ff0b22672222ffffffb4429a21000000000001000001010101010001010000000001000000010101010001010122a8c301")
    }

    @Test func huffmanTransmitMessage() {
        let message = ElkSmartCodec.transmitMessage(frequency: 38_000, pulses: referenceNECPulses([0x04, 0x08]), huffman: true)
        #expect(hex(message) == "ffffffffd6ef09ff33000b0000100100112100012200044200016700019a0001a80001b40001c30001ff000302a8eaa666fb6167fef067fbf0c55d10")
    }

    @Test func packetsCarryChecksums() {
        let pulses = referenceNECPulses([0x04, 0x08, 0x07, 0x10, 0xAA, 0xBB])
        let plain = ElkSmartCodec.packets(for: ElkSmartCodec.transmitMessage(frequency: 36_000, pulses: pulses, huffman: false))
        #expect(plain.map(hex) == [
            "ffffffffceef06ff0122672222ffffffb4429a21000000000001000001010101010001010000000001000000010101010001010122a8c301b4429a2100000e",
            "00000001010101010101010000000000000100000000010101000101010122a8c301b4429a2101000100010001000001000100010001010001010100010135",
            "000100000001000022a8c301",
        ])
        let huffman = ElkSmartCodec.packets(for: ElkSmartCodec.transmitMessage(frequency: 36_000, pulses: pulses, huffman: true))
        #expect(huffman.map(hex) == [
            "ffffffffceef06ff6d000b0000300100332100032200064200036700019a0003a80003b40003c30003ff0003059b266db6da42fbbff7833fdf8626355217d0",
            "ddff807ffbfc6131aa90bee6dbdb66337ef98d40",
        ])
    }

    @Test func mangleIsBitReverseAndInvert() {
        #expect(ElkSmartCodec.mangle(0x00) == 0xFF)
        #expect(ElkSmartCodec.mangle(0x01) == 0x7F)
        #expect(ElkSmartCodec.mangle(0x80) == 0xFE)
        #expect(ElkSmartCodec.mangle(0xF0) == 0xF0)
    }

    @Test func compressedValuesNeverEmitTerminatorByte() {
        var out: [UInt8] = []
        // 0x7F low group with continuation would be 0xFF.
        ElkSmartCodec.compressValue(0x7F | 20 << 7, into: &out)
        #expect(!out.contains(0xFF))
        out = []
        ElkSmartCodec.compressValue(0, into: &out)
        #expect(out == [2])
    }

    @Test func decodesLearnedCodes() throws {
        // 9008 µs = 0xFF (4080) + 0xFF (4080) + 0x3B (944)
        let pulses = try #require(ElkSmartCodec.decodeLearned([0xFF, 0xFF, 0x3B, 0xFF, 0x1B, 0x23, 0x23, 0x23]))
        #expect(pulses.count == 3)
        #expect(pulses[0].on == 9104 && pulses[0].off == 4512)
        #expect(pulses[1].on == 560 && pulses[1].off == 560)
        #expect(pulses[2].on == 560 && pulses[2].off == 0)
    }
}

/// In-memory stand-in for a dongle.
final class FakeTransport: BulkTransport {
    var identity: [UInt8]
    var written: [[UInt8]] = []
    var pendingReads: [[UInt8]] = []
    var learnResponse: [[UInt8]] = []

    init(identity: [UInt8]) { self.identity = identity }

    func write(_ bytes: [UInt8], timeout: TimeInterval) throws {
        written.append(bytes)
        if bytes == ElkSmartCodec.commandIdentify { pendingReads.append(identity) }
        if bytes == ElkSmartCodec.commandLearn { pendingReads += learnResponse }
    }

    func read(maxLength: Int, timeout: TimeInterval) throws -> [UInt8]? {
        pendingReads.isEmpty ? nil : pendingReads.removeFirst()
    }
}

@Suite struct ElkSmartDriverTests {

    @Test func identifiesProtocolVariants() throws {
        #expect(try ElkSmartDriver(transport: FakeTransport(identity: [0xFC, 0xFC, 0xFC, 0xFC, 0x02, 0xAA])).identify() == .huffman)
        #expect(try ElkSmartDriver(transport: FakeTransport(identity: [0xFC, 0xFC, 0xFC, 0xFC, 0x70, 0x01])).identify() == .plain)
        #expect(try ElkSmartDriver(transport: FakeTransport(identity: [0xFA, 0xFA, 0xFA, 0xFA, 0x00, 0x00])).identify() == .plain)
        #expect(throws: ElkSmartError.self) {
            try ElkSmartDriver(transport: FakeTransport(identity: [1, 2, 3, 4, 5, 6])).identify()
        }
    }

    @Test func transmitWritesFramedPackets() throws {
        let transport = FakeTransport(identity: [0xFC, 0xFC, 0xFC, 0xFC, 0x70, 0x01])
        let driver = ElkSmartDriver(transport: transport)
        driver.interPacketDelay = 0
        let raw = try IRProtocolRegistry.encode(ParsedSignal(protocolName: "NEC", address: 0x04, command: 0x08))
        try driver.transmit(raw)
        let packets = transport.written.drop { $0 == ElkSmartCodec.commandIdentify }
        let message = packets.flatMap { $0.count == 63 ? Array($0.prefix(62)) : $0 }
        #expect(Array(message.prefix(4)) == ElkSmartCodec.commandTransmit)
        #expect(packets.dropLast().allSatisfy { $0.count == 63 })
    }

    @Test func learnsCode() throws {
        let transport = FakeTransport(identity: [0xFC, 0xFC, 0xFC, 0xFC, 0x70, 0x01])
        // Header announces 8 data bytes split across two packets.
        transport.learnResponse = [[0xFE, 0xFE, 0xFE, 0xFE, 0x00, 0x08, 0xFF, 0xFF, 0x3B, 0xFF], [0x1B, 0x23, 0x23, 0x23]]
        let driver = ElkSmartDriver(transport: transport)
        let signal = try driver.learn(timeout: 1, isCancelled: { false })
        #expect(signal.timings.prefix(4) == [9104, 4512, 560, 560])
        #expect(transport.written.last == ElkSmartCodec.commandStop)
    }

    @Test func learnCanBeCancelled() {
        let driver = ElkSmartDriver(transport: FakeTransport(identity: [0xFC, 0xFC, 0xFC, 0xFC, 0x70, 0x01]))
        #expect(throws: ElkSmartError.learnCancelled) {
            try driver.learn(timeout: 5, isCancelled: { true })
        }
    }
}
