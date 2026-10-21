import SwiftUI
import IRCore

/// Lays out a remote like the physical thing: power row, rockers around a
/// navigation pad, transport controls, number pad and colour keys.
struct RemoteKeypadView: View {
    let layout: RemoteLayout
    var remoteID: IRRemote.ID?

    var body: some View {
        GlassEffectContainer(spacing: 10) {
            VStack(spacing: 22) {
                topRow
                if layout.hasDirectionalPad || layout.hasVolume || layout.hasChannel {
                    HStack(alignment: .center, spacing: 28) {
                        if layout.hasVolume {
                            Rocker(up: layout.volumeUp, down: layout.volumeDown, title: "VOL",
                                   upSymbol: "plus", downSymbol: "minus", remoteID: remoteID)
                        }
                        if layout.hasDirectionalPad {
                            DirectionalPad(layout: layout, remoteID: remoteID)
                        }
                        if layout.hasChannel {
                            Rocker(up: layout.channelUp, down: layout.channelDown, title: "CH",
                                   upSymbol: "chevron.up", downSymbol: "chevron.down", remoteID: remoteID)
                        }
                    }
                }
                if !layout.navigation.isEmpty {
                    buttonRow(layout.navigation, style: .icon)
                }
                if !layout.transport.isEmpty {
                    buttonRow(layout.transport, style: .icon)
                }
                if layout.hasNumberPad {
                    NumberPad(digits: layout.digits, remoteID: remoteID)
                }
                if !layout.colors.isEmpty {
                    HStack(spacing: 10) {
                        ForEach(layout.colors) { button in
                            RemoteButtonView(button: button, style: .icon, tint: color(for: button.role), prominent: true, remoteID: remoteID)
                        }
                    }
                }
                if !layout.climate.isEmpty {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 130), spacing: 10)], spacing: 10) {
                        ForEach(layout.climate) { button in
                            RemoteButtonView(button: button, remoteID: remoteID)
                        }
                    }
                }
            }
        }
    }

    @ViewBuilder
    private var topRow: some View {
        let others = [layout.powerOn, layout.powerOff, layout.input, layout.mute].compactMap { $0 }
        if layout.power != nil || !others.isEmpty {
            HStack(spacing: 14) {
                if let power = layout.power {
                    RemoteButtonView(button: power, style: .round, tint: .red, prominent: true, remoteID: remoteID)
                }
                ForEach(others) { button in
                    RemoteButtonView(button: button, style: .round, tint: button.role == .powerOn ? .green : nil,
                                     prominent: button.role == .powerOn || button.role == .powerOff, remoteID: remoteID)
                }
            }
        }
    }

    private func buttonRow(_ buttons: [IRButton], style: RemoteButtonView.Style) -> some View {
        HStack(spacing: 10) {
            ForEach(buttons) { button in
                RemoteButtonView(button: button, style: style, remoteID: remoteID)
            }
        }
    }

    private func color(for role: ButtonRole) -> Color {
        switch role {
        case .red: .red
        case .green: .green
        case .yellow: .yellow
        case .blue: .blue
        default: .accentColor
        }
    }
}

/// Up/down rocker such as volume or channel.
private struct Rocker: View {
    var up: IRButton?
    var down: IRButton?
    var title: String
    var upSymbol: String
    var downSymbol: String
    var remoteID: IRRemote.ID?

    var body: some View {
        VStack(spacing: 8) {
            if let up {
                RemoteButtonView(button: up, style: .round, remoteID: remoteID)
            } else {
                MissingButton(systemImage: upSymbol, round: true)
            }
            Text(title)
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)
            if let down {
                RemoteButtonView(button: down, style: .round, remoteID: remoteID)
            } else {
                MissingButton(systemImage: downSymbol, round: true)
            }
        }
        .padding(.vertical, 10)
        .padding(.horizontal, 6)
        .background(.quaternary.opacity(0.35), in: .capsule)
    }
}

/// Four-way navigation pad with a centre OK button.
private struct DirectionalPad: View {
    let layout: RemoteLayout
    var remoteID: IRRemote.ID?

    var body: some View {
        Grid(horizontalSpacing: 6, verticalSpacing: 6) {
            GridRow {
                Color.clear.gridCellUnsizedAxes([.horizontal, .vertical])
                pad(layout.up, symbol: "chevron.up")
                Color.clear.gridCellUnsizedAxes([.horizontal, .vertical])
            }
            GridRow {
                pad(layout.left, symbol: "chevron.left")
                if let ok = layout.ok {
                    RemoteButtonView(button: ok, style: .round, prominent: true, remoteID: remoteID)
                } else {
                    MissingButton(systemImage: "circle", round: true)
                }
                pad(layout.right, symbol: "chevron.right")
            }
            GridRow {
                Color.clear.gridCellUnsizedAxes([.horizontal, .vertical])
                pad(layout.down, symbol: "chevron.down")
                Color.clear.gridCellUnsizedAxes([.horizontal, .vertical])
            }
        }
        .padding(12)
        .background(.quaternary.opacity(0.35), in: .circle)
    }

    @ViewBuilder
    private func pad(_ button: IRButton?, symbol: String) -> some View {
        if let button {
            RemoteButtonView(button: button, style: .round, remoteID: remoteID)
        } else {
            MissingButton(systemImage: symbol, round: true)
        }
    }
}

/// Telephone-style 1–9, 0 keypad.
private struct NumberPad: View {
    let digits: [Int: IRButton]
    var remoteID: IRRemote.ID?

    var body: some View {
        Grid(horizontalSpacing: 12, verticalSpacing: 10) {
            ForEach(0..<3) { row in
                GridRow {
                    ForEach(1...3, id: \.self) { column in
                        cell(row * 3 + column)
                    }
                }
            }
            GridRow {
                Color.clear.gridCellUnsizedAxes([.horizontal, .vertical])
                cell(0)
                Color.clear.gridCellUnsizedAxes([.horizontal, .vertical])
            }
        }
    }

    @ViewBuilder
    private func cell(_ digit: Int) -> some View {
        if let button = digits[digit] {
            RemoteButtonView(button: button, style: .digit, remoteID: remoteID)
        } else {
            Text("\(digit)")
                .font(.title3.monospacedDigit())
                .foregroundStyle(.quaternary)
                .frame(width: 48, height: 48)
        }
    }
}
