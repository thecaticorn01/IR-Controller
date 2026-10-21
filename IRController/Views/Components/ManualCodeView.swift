import SwiftUI
import IRCore

/// Enter a code by protocol + address/command, or paste Pronto / sendir / raw timings.
struct ManualCodeView: View {
    @Binding var signal: IRSignal?
    var initial: IRSignal?

    enum Mode: String, CaseIterable, Identifiable {
        case protocolCode = "Protocol"
        case paste = "Paste Code"
        var id: String { rawValue }
    }

    @State private var mode: Mode = .protocolCode
    @State private var protocolName = "NEC"
    @State private var addressText = "0x00"
    @State private var commandText = "0x00"
    @State private var subdeviceText = ""
    @State private var pastedText = ""
    @State private var message: String?
    @State private var didLoadInitial = false

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Picker("Entry Method", selection: $mode) {
                ForEach(Mode.allCases) { Text($0.rawValue).tag($0) }
            }
            .pickerStyle(.segmented)
            .labelsHidden()

            switch mode {
            case .protocolCode:
                Form {
                    Picker("Protocol", selection: $protocolName) {
                        ForEach(IRProtocolRegistry.protocols) { info in
                            Text(info.name).tag(info.name)
                        }
                    }
                    if let info = IRProtocolRegistry.info(for: protocolName) {
                        Text(info.summary + " · \(info.frequency / 1000) kHz")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    TextField("Address", text: $addressText, prompt: Text("e.g. 0x04"))
                    TextField("Command", text: $commandText, prompt: Text("e.g. 0x08"))
                    TextField("Sub-device", text: $subdeviceText, prompt: Text("Optional"))
                }
                .formStyle(.columns)
            case .paste:
                VStack(alignment: .leading, spacing: 6) {
                    TextEditor(text: $pastedText)
                        .font(.body.monospaced())
                        .frame(minHeight: 90)
                        .scrollContentBackground(.hidden)
                        .padding(6)
                        .background(.quaternary.opacity(0.4), in: .rect(cornerRadius: 10))
                    Text("Accepts Pronto hex (0000 006D …), Global Caché sendir commands, or mark/space timings in microseconds.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }

            if let signal {
                SignalSummaryView(signal: signal)
            } else if let message {
                Label(message, systemImage: "exclamationmark.triangle")
                    .font(.callout)
                    .foregroundStyle(.orange)
            }
        }
        .onAppear(perform: loadInitial)
        .onChange(of: mode) { rebuild() }
        .onChange(of: protocolName) { rebuild() }
        .onChange(of: addressText) { rebuild() }
        .onChange(of: commandText) { rebuild() }
        .onChange(of: subdeviceText) { rebuild() }
        .onChange(of: pastedText) { rebuild() }
    }

    private func loadInitial() {
        guard !didLoadInitial else { return }
        didLoadInitial = true
        switch initial {
        case .parsed(let parsed)?:
            mode = .protocolCode
            protocolName = IRProtocolRegistry.canonicalName(for: parsed.protocolName) ?? parsed.protocolName
            addressText = String(format: "0x%X", parsed.address)
            commandText = String(format: "0x%X", parsed.command)
            subdeviceText = parsed.subdevice.map { String(format: "0x%X", $0) } ?? ""
        case .raw(let raw)?:
            mode = .paste
            pastedText = raw.timings.map(String.init).joined(separator: " ")
        case nil:
            break
        }
        rebuild()
    }

    private func rebuild() {
        message = nil
        switch mode {
        case .protocolCode:
            guard let address = Self.parseNumber(addressText), let command = Self.parseNumber(commandText) else {
                signal = nil
                message = "Enter the address and command as decimal or hex (0x…) numbers."
                return
            }
            let subdevice = subdeviceText.trimmingCharacters(in: .whitespaces).isEmpty ? nil : Self.parseNumber(subdeviceText)
            signal = .parsed(ParsedSignal(protocolName: protocolName, address: address, command: command, subdevice: subdevice))
        case .paste:
            guard !pastedText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                signal = nil
                return
            }
            do {
                let (raw, _) = try IRCodeImporter.parse(pastedText)
                signal = .raw(raw)
            } catch {
                signal = nil
                message = error.localizedDescription
            }
        }
    }

    static func parseNumber(_ text: String) -> UInt32? {
        let trimmed = text.trimmingCharacters(in: .whitespaces).lowercased()
        if trimmed.hasPrefix("0x") { return UInt32(trimmed.dropFirst(2), radix: 16) }
        if trimmed.hasSuffix("h") { return UInt32(trimmed.dropLast(), radix: 16) }
        return UInt32(trimmed)
    }
}
