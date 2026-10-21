import Foundation

/// Bit order used when serialising a data field.
public enum BitOrder: Sendable {
    case lsbFirst
    case msbFirst
}

/// How a single logical bit is rendered. Durations are signed microseconds:
/// positive values are marks, negative values are spaces.
public struct BitEncoding: Sendable {
    public var zero: [Int]
    public var one: [Int]

    public init(zero: [Int], one: [Int]) {
        self.zero = zero
        self.one = one
    }

    /// Pulse-distance encoding (fixed mark, variable space).
    public static func pulseDistance(mark: Int, zeroSpace: Int, oneSpace: Int) -> BitEncoding {
        BitEncoding(zero: [mark, -zeroSpace], one: [mark, -oneSpace])
    }

    /// Pulse-width encoding (variable mark, fixed space).
    public static func pulseWidth(zeroMark: Int, oneMark: Int, space: Int) -> BitEncoding {
        BitEncoding(zero: [zeroMark, -space], one: [oneMark, -space])
    }
}

/// Incrementally builds a normalised mark/space waveform.
///
/// Consecutive durations of the same level are merged, which makes bi-phase
/// (Manchester) encodings such as RC5 and RC6 fall out naturally.
public struct PulseBuilder: Sendable {
    /// Positive alternating durations, starting with a mark.
    public private(set) var timings: [Int] = []
    private var frameStart = 0

    public init() {}

    /// Total length of everything emitted so far.
    public var totalDuration: Int { timings.reduce(0, +) }

    public mutating func mark(_ microseconds: Int) { append(microseconds) }
    public mutating func space(_ microseconds: Int) { append(-microseconds) }

    /// Appends a signed duration (positive = mark, negative = space).
    public mutating func append(_ signed: Int) {
        guard signed != 0 else { return }
        let isMark = signed > 0
        let value = abs(signed)
        if timings.isEmpty {
            // A waveform always begins with a mark; leading silence is meaningless.
            if isMark { timings.append(value) }
            return
        }
        let lastIsMark = timings.count % 2 == 1
        if lastIsMark == isMark {
            timings[timings.count - 1] += value
        } else {
            timings.append(value)
        }
    }

    public mutating func append(contentsOf signed: [Int]) {
        for value in signed { append(value) }
    }

    /// Emits `count` bits of `value`.
    public mutating func bits(_ value: UInt64, count: Int, order: BitOrder, encoding: BitEncoding) {
        guard count > 0 else { return }
        for i in 0..<count {
            let bitIndex = order == .lsbFirst ? i : count - 1 - i
            let bit = (value >> UInt64(bitIndex)) & 1
            append(contentsOf: bit == 1 ? encoding.one : encoding.zero)
        }
    }

    /// Marks the start of a new frame for `padFrame(to:)`.
    public mutating func beginFrame() {
        frameStart = totalDuration
    }

    /// Pads with silence so the current frame lasts at least `length` microseconds
    /// (IRP `^length`). Always appends at least `minimumGap` of silence.
    public mutating func padFrame(to length: Int, minimumGap: Int = 0) {
        let elapsed = totalDuration - frameStart
        space(max(length - elapsed, minimumGap))
    }

    /// Appends a copy of the waveform emitted between `start` (an index into
    /// `timings`, which must point at a mark) and the end.
    public mutating func repeatSegment(from start: Int) {
        let segment = Array(timings[start...])
        for (offset, value) in segment.enumerated() {
            append(offset % 2 == 0 ? value : -value)
        }
    }
}
