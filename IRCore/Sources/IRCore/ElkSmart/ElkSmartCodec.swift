import Foundation

/// Wire format of ELK Smart (Ocrustar / ZaZa) USB infrared dongles.
///
/// The protocol was reverse engineered by Přemysl Eric Janouch in
/// `elksmart-comm` (0BSD licence, https://git.janouch.name/p/usb-drivers);
/// this is an independent Swift implementation of the same format.
///
/// Transmission message layout:
/// ```
/// FF FF FF FF  mangle(f>>8) mangle(f>>16) mangle(f)  mangle(len>>8) mangle(len)  payload…
/// ```
/// where `f = frequency + 0x7FFFF` and the payload is a compressed pulse list,
/// optionally Huffman-coded for newer dongles. The message is split into
/// 62-byte chunks; full chunks get a one-byte checksum appended.
public enum ElkSmartCodec {

    public static let commandTransmit: [UInt8] = [0xFF, 0xFF, 0xFF, 0xFF]
    public static let commandLearn: [UInt8] = [0xFE, 0xFE, 0xFE, 0xFE]
    public static let commandStop: [UInt8] = [0xFD, 0xFD, 0xFD, 0xFD]
    public static let commandIdentify: [UInt8] = [0xFC, 0xFC, 0xFC, 0xFC]

    public typealias Pulse = (on: Int, off: Int)

    /// Bit-reverses and inverts a byte.
    public static func mangle(_ value: UInt8) -> UInt8 {
        var input = value
        var reversed: UInt8 = 0
        for _ in 0..<8 {
            reversed = (reversed << 1) | (input & 1)
            input >>= 1
        }
        return ~reversed
    }

    public static func checksum<C: Collection>(_ bytes: C) -> UInt8 where C.Element == UInt8 {
        let sum = bytes.reduce(UInt32(0)) { $0 + UInt32($1) }
        return mangle(UInt8((sum & 0xF0) | ((sum >> 8) & 0x0F)))
    }

    /// Compresses one duration (µs). Short values are stored in 16 µs units;
    /// long values use 7-bit little-endian groups with a continuation bit.
    static func compressValue(_ value: Int, into out: inout [UInt8]) {
        let value = max(0, value)
        if value <= 2032 {
            // Values 0 and 1 are reserved as references to the two most common pulses.
            let units = Int((Double(value) / 16 + 0.5).rounded(.down))
            out.append(UInt8(max(2, units)))
        } else {
            var remaining = value
            repeat {
                var byte = UInt8(remaining & 0x7F)
                remaining >>= 7
                if remaining != 0 { byte |= 0x80 }
                // 0xFF would be confused with the header terminator.
                out.append(byte == 0xFF ? 0xFE : byte)
            } while remaining != 0
        }
    }

    private static func equal(_ a: Pulse, _ b: Pulse) -> Bool { a.on == b.on && a.off == b.off }

    /// Dictionary-compresses a pulse list: the two most frequent pulses are
    /// declared up front and then referenced with single bytes 0 and 1.
    public static func compress(_ pulses: [Pulse]) -> [UInt8] {
        guard !pulses.isEmpty else { return [] }
        struct Key: Hashable { let on: Int, off: Int }
        var counts: [Key: Int] = [:]
        for p in pulses { counts[Key(on: p.on, off: p.off), default: 0] += 1 }
        func count(_ p: Pulse) -> Int { counts[Key(on: p.on, off: p.off)] ?? 0 }

        // Same tie-breaking as the reference implementation: first occurrence wins.
        var top1 = 0
        for i in pulses.indices where count(pulses[i]) > count(pulses[top1]) { top1 = i }
        var top2 = top1
        for i in pulses.indices where !equal(pulses[i], pulses[top1]) {
            if top2 == top1 || count(pulses[i]) > count(pulses[top2]) { top2 = i }
        }
        let p1 = pulses[top1], p2 = pulses[top2]

        var out: [UInt8] = []
        out.reserveCapacity(pulses.count + 16)
        compressValue(p2.on, into: &out)
        compressValue(p2.off, into: &out)
        compressValue(p1.on, into: &out)
        compressValue(p1.off, into: &out)
        out += [0xFF, 0xFF, 0xFF]
        for p in pulses {
            if equal(p, p1) {
                out.append(0)
            } else if equal(p, p2) {
                out.append(1)
            } else {
                compressValue(p.on, into: &out)
                compressValue(p.off, into: &out)
            }
        }
        return out
    }

    // MARK: Huffman

    private final class Node {
        var parent: Node?
        let frequency: Int
        var bit = false
        init(frequency: Int) { self.frequency = frequency }
    }

    /// Binary heap mirroring java.util.PriorityQueue, so that ties between equal
    /// frequencies resolve exactly like the vendor's Android application.
    private struct JavaHeap {
        var items: [Node] = []

        mutating func push(_ node: Node) {
            items.append(node)
            var child = items.count - 1
            while child > 0 {
                let parent = (child - 1) / 2
                if node.frequency >= items[parent].frequency { break }
                items[child] = items[parent]
                child = parent
            }
            items[child] = node
        }

        mutating func pop() -> Node {
            let result = items[0]
            let last = items.removeLast()
            if items.isEmpty { return result }
            let count = items.count
            var parent = 0
            while parent < count / 2 {
                var child = parent * 2 + 1
                if child + 1 < count, items[child + 1].frequency < items[child].frequency { child += 1 }
                if items[child].frequency >= last.frequency { break }
                items[parent] = items[child]
                parent = child
            }
            items[parent] = last
            return result
        }
    }

    /// Huffman-codes a payload for dongles that report the "02 AA" identity.
    /// Output: u16 symbol count, (u8 symbol, u16 frequency)…, u8 padding bits, bitstream.
    public static func huffmanEncode(_ input: [UInt8]) -> [UInt8] {
        var frequencies = [Int](repeating: 0, count: 256)
        for byte in input { frequencies[Int(byte)] += 1 }

        var leaves = [Node?](repeating: nil, count: 256)
        var heap = JavaHeap()
        for symbol in 0..<256 where frequencies[symbol] > 0 {
            let node = Node(frequency: frequencies[symbol])
            leaves[symbol] = node
            heap.push(node)
        }

        var out: [UInt8] = []
        let symbolCount = heap.items.count
        out.append(UInt8(symbolCount >> 8 & 0xFF))
        out.append(UInt8(symbolCount & 0xFF))
        for symbol in 0..<256 where frequencies[symbol] > 0 {
            out.append(UInt8(symbol))
            out.append(UInt8(frequencies[symbol] >> 8 & 0xFF))
            out.append(UInt8(frequencies[symbol] & 0xFF))
        }

        while heap.items.count > 1 {
            let left = heap.pop()
            let right = heap.pop()
            let node = Node(frequency: left.frequency + right.frequency)
            left.parent = node
            right.parent = node
            left.bit = false
            right.bit = true
            heap.push(node)
        }

        let paddingIndex = out.count
        out.append(0)
        var byte: UInt8 = 0
        var bitCount = 0
        var path: [Bool] = []
        for symbol in input {
            path.removeAll(keepingCapacity: true)
            var node = leaves[Int(symbol)]
            while let current = node, let parent = current.parent {
                path.append(current.bit)
                node = parent
            }
            for bit in path.reversed() {
                byte = byte << 1 | (bit ? 1 : 0)
                bitCount += 1
                if bitCount == 8 {
                    out.append(byte)
                    byte = 0
                    bitCount = 0
                }
            }
        }
        if bitCount > 0 {
            out[paddingIndex] = UInt8(8 - bitCount)
            out.append(byte << UInt8(8 - bitCount))
        }
        return out
    }

    // MARK: Messages

    /// Builds the complete transmit message (before chunking).
    public static func transmitMessage(frequency: Int, pulses: [Pulse], huffman: Bool) -> [UInt8] {
        var payload = compress(pulses)
        if huffman { payload = huffmanEncode(payload) }
        let f = UInt32(max(1, frequency)) &+ 0x7FFFF
        var message = commandTransmit
        message.append(mangle(UInt8(f >> 8 & 0xFF)))
        message.append(mangle(UInt8(f >> 16 & 0xFF)))
        message.append(mangle(UInt8(f & 0xFF)))
        message.append(mangle(UInt8(payload.count >> 8 & 0xFF)))
        message.append(mangle(UInt8(payload.count & 0xFF)))
        message += payload
        return message
    }

    /// Splits a message into USB bulk packets: 62 data bytes plus checksum,
    /// with the final short chunk sent as-is.
    public static func packets(for message: [UInt8]) -> [[UInt8]] {
        var packets: [[UInt8]] = []
        var index = 0
        while index < message.count {
            let end = min(index + 62, message.count)
            var chunk = Array(message[index..<end])
            if chunk.count == 62 { chunk.append(checksum(chunk)) }
            packets.append(chunk)
            index = end
        }
        return packets
    }

    /// Maximum payload length expressible in the 16-bit length field.
    public static let maximumPayloadLength = 0xFFFF

    /// Decodes a learned code: each byte is a duration in 16 µs units and 0xFF
    /// bytes carry 4080 µs into the following byte.
    public static func decodeLearned(_ bytes: [UInt8]) -> [Pulse]? {
        var pulses: [Pulse] = []
        var i = 0
        func readDuration() -> Int? {
            var total = 0
            while i < bytes.count, bytes[i] == 0xFF {
                total += 4080
                i += 1
            }
            guard i < bytes.count else { return nil }
            total += Int(bytes[i]) * 16
            i += 1
            return total
        }
        while i < bytes.count {
            guard let on = readDuration() else { return pulses.isEmpty ? nil : pulses }
            if i >= bytes.count {
                pulses.append((on, 0))
                break
            }
            guard let off = readDuration() else { return pulses.isEmpty ? nil : pulses }
            pulses.append((on, off))
        }
        return pulses.isEmpty ? nil : pulses
    }
}
