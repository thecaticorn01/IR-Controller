import SwiftUI
import AppKit
import IRCore

extension IRButton {
    /// Button name with Flipper-style underscores turned into spaces.
    var displayName: String {
        name.replacingOccurrences(of: "_", with: " ")
    }
}

/// A single tappable remote button rendered with Liquid Glass.
struct RemoteButtonView: View {
    enum Style {
        /// Name, with an icon when the role has one.
        case standard
        /// Icon only (falls back to the name).
        case icon
        /// Large circular icon button.
        case round
        /// Number keypad digit.
        case digit
    }

    let button: IRButton
    var style: Style = .standard
    var tint: Color?
    var prominent = false
    /// When set, the context menu offers editing actions for this remote.
    var remoteID: IRRemote.ID?

    @Environment(AppNavigation.self) private var navigation
    @Environment(LibraryStore.self) private var library
    @State private var pressCount = 0

    var body: some View {
        styled(
            Button {
                pressCount += 1
                navigation.send(button)
            } label: {
                label
            }
        )
        .tint(tint)
        .help("\(button.displayName)\n\(button.signal.summary)")
        .accessibilityLabel(button.displayName)
        .contextMenu { menu }
    }

    @ViewBuilder
    private func styled(_ base: some View) -> some View {
        let shaped = Group {
            switch style {
            case .round: base.buttonBorderShape(.circle)
            case .digit: base.buttonBorderShape(.circle)
            default: base.buttonBorderShape(.capsule)
            }
        }
        if prominent {
            shaped.buttonStyle(.glassProminent)
        } else {
            shaped.buttonStyle(.glass)
        }
    }

    @ViewBuilder
    private var label: some View {
        let symbol = button.role.symbolName
        switch style {
        case .icon, .round:
            Group {
                if let symbol {
                    Image(systemName: symbol)
                        .symbolEffect(.bounce, value: pressCount)
                } else {
                    Text(button.displayName)
                        .lineLimit(1)
                        .minimumScaleFactor(0.6)
                }
            }
            .font(style == .round ? .title3.weight(.semibold) : .body.weight(.medium))
            .frame(width: style == .round ? 40 : 34, height: style == .round ? 40 : 26)
        case .digit:
            Text(button.displayName)
                .font(.title3.weight(.medium).monospacedDigit())
                .frame(width: 36, height: 36)
        case .standard:
            HStack(spacing: 6) {
                if let symbol {
                    Image(systemName: symbol)
                        .symbolEffect(.bounce, value: pressCount)
                }
                Text(button.displayName)
                    .lineLimit(1)
                    .truncationMode(.middle)
            }
            .frame(maxWidth: .infinity, minHeight: 26)
        }
    }

    @ViewBuilder
    private var menu: some View {
        Button("Send", systemImage: "dot.radiowaves.right") { navigation.send(button) }
        Button("Send as Long Press", systemImage: "hand.tap") { navigation.send(button, extraRepeats: 5) }
        Divider()
        Button("Copy Pronto Hex", systemImage: "doc.on.doc") {
            if let raw = try? button.signal.rendered(toggle: false) {
                NSPasteboard.general.clearContents()
                NSPasteboard.general.setString(IRCodeImporter.prontoHex(for: raw), forType: .string)
            }
        }
        if let remoteID {
            Divider()
            Button("Edit Button…", systemImage: "pencil") {
                navigation.sheet = .editButton(remoteID, button.id)
            }
            Button("Delete Button", systemImage: "trash", role: .destructive) {
                library.deleteButton(button.id, from: remoteID)
            }
        }
    }
}

/// Placeholder that keeps keypad geometry when a remote lacks a button.
struct MissingButton: View {
    var systemImage: String
    var round = false

    var body: some View {
        Image(systemName: systemImage)
            .font(round ? .title3.weight(.semibold) : .body)
            .frame(width: round ? 40 : 34, height: round ? 40 : 26)
            .padding(round ? 10 : 6)
            .foregroundStyle(.quaternary)
            .accessibilityHidden(true)
    }
}
