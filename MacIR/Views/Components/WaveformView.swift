import SwiftUI
import IRCore

/// Draws an infrared signal as a square wave (marks high, spaces low).
struct WaveformView: View {
    var signal: RawSignal
    var tint: Color = .accentColor
    /// Spaces longer than this are drawn compressed so the interesting part stays visible.
    var maxGap: Int = 12_000

    var body: some View {
        Canvas { context, size in
            let timings = displayTimings
            let total = max(1, timings.reduce(0, +))
            let high = size.height * 0.15
            let low = size.height * 0.85
            var path = Path()
            var x: CGFloat = 0
            path.move(to: CGPoint(x: 0, y: low))
            for (index, duration) in timings.enumerated() {
                let width = CGFloat(duration) / CGFloat(total) * size.width
                let y = index % 2 == 0 ? high : low
                path.addLine(to: CGPoint(x: x, y: y))
                x += width
                path.addLine(to: CGPoint(x: x, y: y))
            }
            path.addLine(to: CGPoint(x: x, y: low))
            path.addLine(to: CGPoint(x: size.width, y: low))

            var fill = path
            fill.addLine(to: CGPoint(x: size.width, y: low))
            fill.addLine(to: CGPoint(x: 0, y: low))
            context.fill(fill, with: .linearGradient(
                Gradient(colors: [tint.opacity(0.35), tint.opacity(0.05)]),
                startPoint: CGPoint(x: 0, y: high), endPoint: CGPoint(x: 0, y: low)))
            context.stroke(path, with: .color(tint), style: StrokeStyle(lineWidth: 1.5, lineJoin: .round))
        }
        .accessibilityLabel("Waveform with \(signal.timings.count) timings")
    }

    private var displayTimings: [Int] {
        signal.timings.enumerated().map { index, value in
            index % 2 == 1 ? min(value, maxGap) : value
        }
    }
}

/// Compact description of a signal: protocol, carrier and length.
struct SignalSummaryView: View {
    var signal: IRSignal

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            if let raw = try? signal.rendered(toggle: false) {
                WaveformView(signal: raw)
                    .frame(height: 54)
                HStack(spacing: 12) {
                    Label(protocolText, systemImage: "waveform.path")
                    Label(String(format: "%.1f kHz", Double(raw.frequency) / 1000), systemImage: "antenna.radiowaves.left.and.right")
                    Label(String(format: "%.0f ms", Double(raw.duration) / 1000), systemImage: "timer")
                }
                .font(.caption)
                .foregroundStyle(.secondary)
                .labelStyle(.titleAndIcon)
            } else {
                Label("This protocol can’t be transmitted yet.", systemImage: "exclamationmark.triangle")
                    .foregroundStyle(.orange)
            }
        }
    }

    private var protocolText: String {
        switch signal {
        case .parsed(let parsed):
            return parsed.protocolName + " " + String(format: "0x%X / 0x%X", parsed.address, parsed.command)
        case .raw(let raw):
            if let decoded = IRProtocolDecoder.decode(raw) {
                return "Raw (looks like \(decoded.protocolName))"
            }
            return "Raw"
        }
    }
}
