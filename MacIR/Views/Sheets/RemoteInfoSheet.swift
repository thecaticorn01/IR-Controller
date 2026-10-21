import SwiftUI
import IRCore

/// Creates a new empty remote or edits a remote's details.
struct RemoteInfoSheet: View {
    var remoteID: IRRemote.ID?

    @Environment(LibraryStore.self) private var library
    @Environment(AppNavigation.self) private var navigation
    @Environment(\.dismiss) private var dismiss

    @State private var name = ""
    @State private var brand = ""
    @State private var model = ""
    @State private var category: DeviceCategory = .tv
    @State private var notes = ""
    @State private var loaded = false

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack(spacing: 14) {
                CategoryBadge(category: category, size: 48)
                Text(remoteID == nil ? "New Remote" : "Remote Details")
                    .font(.title2.weight(.semibold))
            }
            Form {
                TextField("Name", text: $name, prompt: Text("Living Room TV"))
                TextField("Brand", text: $brand, prompt: Text("Optional"))
                TextField("Model", text: $model, prompt: Text("Optional"))
                Picker("Device Type", selection: $category) {
                    ForEach(DeviceCategory.allCases) { category in
                        Label(category.displayName, systemImage: category.symbolName).tag(category)
                    }
                }
                TextField("Notes", text: $notes, prompt: Text("Optional"), axis: .vertical)
                    .lineLimit(2...5)
            }
            .formStyle(.grouped)
            .scrollDisabled(true)

            HStack {
                Spacer()
                Button("Cancel", role: .cancel) { dismiss() }
                    .keyboardShortcut(.cancelAction)
                Button(remoteID == nil ? "Create" : "Save") { save() }
                    .keyboardShortcut(.defaultAction)
                    .buttonStyle(.glassProminent)
                    .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        }
        .padding(24)
        .frame(width: 460)
        .onAppear(perform: load)
    }

    private func load() {
        guard !loaded else { return }
        loaded = true
        guard let remoteID, let remote = library.remote(id: remoteID) else { return }
        name = remote.name
        brand = remote.brand
        model = remote.model
        category = remote.category
        notes = remote.notes
    }

    private func save() {
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        if let remoteID {
            library.modify(remoteID) { remote in
                remote.name = trimmed
                remote.brand = brand
                remote.model = model
                remote.category = category
                remote.notes = notes
            }
        } else {
            let remote = library.add(IRRemote(name: trimmed, brand: brand, model: model, category: category, notes: notes))
            navigation.open(remote)
        }
        dismiss()
    }
}

/// Rounded glass tile with the category symbol.
struct CategoryBadge: View {
    var category: DeviceCategory
    var size: CGFloat = 32

    var body: some View {
        Image(systemName: category.symbolName)
            .font(.system(size: size * 0.45, weight: .medium))
            .foregroundStyle(.tint)
            .frame(width: size, height: size)
            .glassEffect(.regular.tint(Color.accentColor.opacity(0.15)), in: .rect(cornerRadius: size * 0.28))
            .accessibilityHidden(true)
    }
}
