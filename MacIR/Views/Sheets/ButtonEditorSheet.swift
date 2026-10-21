import SwiftUI
import IRCore

/// Adds a button to a remote (learned or entered) or edits an existing one.
struct AddButtonSheet: View {
    enum Mode: String, CaseIterable, Identifiable, Hashable {
        case learn = "Learn from Remote"
        case code = "Enter Code"
        var id: String { rawValue }
    }

    let remoteID: IRRemote.ID
    /// Button being edited; nil when adding.
    var existingButtonID: IRButton.ID?
    @State var mode: Mode

    @Environment(LibraryStore.self) private var library
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var learnedSignal: IRSignal?
    @State private var enteredSignal: IRSignal?
    @State private var loaded = false

    init(remoteID: IRRemote.ID, mode: Mode, existingButtonID: IRButton.ID? = nil) {
        self.remoteID = remoteID
        self.existingButtonID = existingButtonID
        _mode = State(initialValue: mode)
    }

    private var existingButton: IRButton? {
        guard let existingButtonID else { return nil }
        return library.remote(id: remoteID)?.buttons.first { $0.id == existingButtonID }
    }

    private var signal: IRSignal? {
        switch mode {
        case .learn: learnedSignal
        case .code: enteredSignal
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            Text(existingButtonID == nil ? "Add Button" : "Edit Button")
                .font(.title2.weight(.semibold))

            TextField("Button Name", text: $name, prompt: Text("e.g. Power"))
                .textFieldStyle(.roundedBorder)
                .font(.title3)

            if !suggestions.isEmpty && existingButtonID == nil {
                ScrollView(.horizontal) {
                    HStack(spacing: 6) {
                        ForEach(suggestions, id: \.self) { suggestion in
                            Button(suggestion) { name = suggestion }
                                .buttonStyle(.glass)
                                .controlSize(.small)
                        }
                    }
                }
                .scrollIndicators(.hidden)
            }

            Picker("Source", selection: $mode) {
                ForEach(Mode.allCases) { Text($0.rawValue).tag($0) }
            }
            .pickerStyle(.segmented)
            .labelsHidden()

            switch mode {
            case .learn:
                if let existing = existingButton, learnedSignal == nil {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Current code")
                            .font(.subheadline.weight(.semibold))
                        SignalSummaryView(signal: existing.signal)
                    }
                }
                SignalCaptureView(signal: $learnedSignal, buttonName: name.isEmpty ? "the button" : name)
            case .code:
                ManualCodeView(signal: $enteredSignal, initial: existingButton?.signal)
            }

            HStack {
                if let signal {
                    Button("Test", systemImage: "dot.radiowaves.right") {
                        AppNavigation.shared.send(IRButton(name: name, signal: signal))
                    }
                    .buttonStyle(.glass)
                }
                Spacer()
                Button("Cancel", role: .cancel) { dismiss() }
                    .keyboardShortcut(.cancelAction)
                Button(existingButtonID == nil ? "Add Button" : "Save") { save() }
                    .keyboardShortcut(.defaultAction)
                    .buttonStyle(.glassProminent)
                    .disabled(!canSave)
            }
        }
        .padding(24)
        .frame(width: 520)
        .onAppear {
            guard !loaded else { return }
            loaded = true
            if let existing = existingButton { name = existing.name }
        }
    }

    private var canSave: Bool {
        let hasName = !name.trimmingCharacters(in: .whitespaces).isEmpty
        // When editing, renaming alone is allowed.
        return hasName && (signal != nil || existingButtonID != nil)
    }

    private var suggestions: [String] {
        guard let remote = library.remote(id: remoteID) else { return [] }
        let existing = Set(remote.buttons.map { $0.name.searchKey })
        return LearningTemplate.buttonNames(for: remote.category).filter { !existing.contains($0.searchKey) }
    }

    private func save() {
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        if var button = existingButton {
            button.name = trimmed
            if let signal { button.signal = signal }
            library.updateButton(button, in: remoteID)
        } else if let signal {
            library.addButton(IRButton(name: trimmed, signal: signal), to: remoteID)
        }
        dismiss()
    }
}
