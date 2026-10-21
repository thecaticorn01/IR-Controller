import SwiftUI
import IRCore

/// Glass pill showing the blaster connection state; opens details on click.
struct DeviceStatusPill: View {
    @Environment(BlasterManager.self) private var blaster
    @State private var showingDetails = false

    var body: some View {
        Button {
            showingDetails.toggle()
        } label: {
            HStack(spacing: 10) {
                StatusIndicator(state: blaster.state, activity: blaster.activity, sendCount: blaster.sendCount)
                VStack(alignment: .leading, spacing: 1) {
                    Text(title)
                        .font(.callout.weight(.semibold))
                        .lineLimit(1)
                    Text(subtitle)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .glassEffect(.regular.interactive(), in: .rect(cornerRadius: 14))
        .popover(isPresented: $showingDetails, arrowEdge: .top) {
            DeviceDetailsView()
                .frame(width: 340)
        }
        .accessibilityLabel("IR blaster: \(title). \(subtitle)")
    }

    private var title: String {
        switch blaster.state {
        case .searching: "No IR Blaster"
        case .connecting: "Connecting…"
        case .ready(let info): info.model.name.replacingOccurrences(of: "ELK Smart ", with: "")
        case .failed: "Blaster Error"
        }
    }

    private var subtitle: String {
        switch blaster.activity {
        case .sending(let name): return "Sending \(name)…"
        case .learning: return "Listening for a remote…"
        case .idle: break
        }
        switch blaster.state {
        case .searching: return "Plug in an ELK Smart USB-C blaster"
        case .connecting(let name): return name
        case .ready: return blaster.lastSentName.map { "Last sent: \($0)" } ?? "Ready"
        case .failed(let message): return message
        }
    }
}

/// Coloured dot with a pulse while transmitting.
struct StatusIndicator: View {
    var state: BlasterManager.State
    var activity: BlasterManager.Activity
    var sendCount: Int

    var body: some View {
        ZStack {
            Circle()
                .fill(color.opacity(0.25))
                .frame(width: 26, height: 26)
            Image(systemName: symbol)
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(color)
                .symbolEffect(.bounce, value: sendCount)
                .symbolEffect(.pulse, isActive: activity == .learning || isConnecting)
        }
    }

    private var isConnecting: Bool {
        if case .connecting = state { return true }
        return false
    }

    private var color: Color {
        switch state {
        case .ready: .green
        case .connecting: .orange
        case .searching: .secondary
        case .failed: .red
        }
    }

    private var symbol: String {
        switch state {
        case .ready: activity == .learning ? "dot.radiowaves.left.and.right" : "dot.radiowaves.right"
        case .connecting: "cable.connector"
        case .searching: "cable.connector.slash"
        case .failed: "exclamationmark.triangle.fill"
        }
    }
}

/// Connection details, troubleshooting and the activity log.
struct DeviceDetailsView: View {
    @Environment(BlasterManager.self) private var blaster

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                StatusIndicator(state: blaster.state, activity: blaster.activity, sendCount: blaster.sendCount)
                Text("IR Blaster")
                    .font(.headline)
                Spacer()
                Button("Reconnect", systemImage: "arrow.clockwise") { blaster.reconnect() }
                    .buttonStyle(.glass)
                    .controlSize(.small)
            }

            switch blaster.state {
            case .ready(let info):
                Grid(alignment: .leading, horizontalSpacing: 12, verticalSpacing: 6) {
                    row("Model", info.model.name)
                    row("USB ID", String(format: "%04X:%04X", ElkSmartModel.vendorID, info.model.productID))
                    if let serial = info.serialNumber { row("Serial", serial) }
                    row("Protocol", info.encoding == .huffman ? "Huffman-coded (newer firmware)" : "Plain")
                    row("Identity", info.identity.hexString)
                    row("Transport", info.endpoints)
                }
                .font(.callout)
            case .searching:
                Text("Plug your ELK Smart USB-C IR blaster into a USB-C port. If you use a hub or adapter, try connecting it directly to your Mac.")
                    .font(.callout)
                    .foregroundStyle(.secondary)
            case .connecting(let name):
                ProgressView("Connecting to \(name)…")
                    .controlSize(.small)
            case .failed(let message):
                VStack(alignment: .leading, spacing: 6) {
                    Text(message)
                        .font(.callout)
                    Text("Unplug the blaster, wait a few seconds, plug it back in, then choose Reconnect. Some dongles are picky about cables and hubs.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }

            if !blaster.log.isEmpty {
                Divider()
                Text("Activity")
                    .font(.subheadline.weight(.semibold))
                ScrollView {
                    VStack(alignment: .leading, spacing: 4) {
                        ForEach(blaster.log.prefix(30)) { entry in
                            HStack(alignment: .firstTextBaseline, spacing: 6) {
                                Text(entry.date, style: .time)
                                    .foregroundStyle(.tertiary)
                                Text(entry.message)
                                    .foregroundStyle(entry.isError ? .red : .primary)
                            }
                            .font(.caption)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .frame(maxHeight: 140)
            }
        }
        .padding(16)
    }

    private func row(_ label: String, _ value: String) -> some View {
        GridRow {
            Text(label)
                .foregroundStyle(.secondary)
            Text(value)
                .textSelection(.enabled)
        }
    }
}
