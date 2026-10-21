import SwiftUI
import IRCore

/// Shows one remote as a physical-style keypad, or as an editable list of buttons.
struct RemoteDetailView: View {
    let remoteID: IRRemote.ID

    enum DisplayMode: String, CaseIterable, Identifiable {
        case remote = "Remote"
        case list = "Buttons"
        var id: String { rawValue }
    }

    @Environment(LibraryStore.self) private var library
    @Environment(AppNavigation.self) private var navigation
    @AppStorage("remoteDisplayMode") private var displayMode: DisplayMode = .remote
    @AppStorage(SettingsKey.keyboardShortcuts) private var keyboardShortcuts = true
    @State private var filter = ""
    @State private var confirmDelete = false
    @FocusState private var focused: Bool

    var body: some View {
        if let remote = library.remote(id: remoteID) {
            content(remote)
                .navigationTitle(remote.name)
                .navigationSubtitle(subtitle(remote))
                .toolbar { toolbar(remote) }
                .searchable(text: $filter, placement: .toolbar, prompt: "Filter Buttons")
                .confirmationDialog("Delete “\(remote.name)”?", isPresented: $confirmDelete) {
                    Button("Delete Remote", role: .destructive) {
                        navigation.selection = nil
                        library.delete(remoteID)
                    }
                } message: {
                    Text("This removes the remote and all of its buttons from your library.")
                }
        } else {
            ContentUnavailableView("Remote Not Found", systemImage: "av.remote")
        }
    }

    private func subtitle(_ remote: IRRemote) -> String {
        [remote.brand, remote.model, "\(remote.buttons.count) buttons"].filter { !$0.isEmpty }.joined(separator: " · ")
    }

    @ViewBuilder
    private func content(_ remote: IRRemote) -> some View {
        if remote.buttons.isEmpty {
            ContentUnavailableView {
                Label("No Buttons Yet", systemImage: "rectangle.grid.2x2")
            } description: {
                Text("Teach this remote its buttons with your IR blaster, or enter codes manually.")
            } actions: {
                HStack {
                    Button("Learn a Button", systemImage: "dot.radiowaves.left.and.right") {
                        navigation.sheet = .addButton(remoteID, .learn)
                    }
                    .buttonStyle(.glassProminent)
                    Button("Enter a Code", systemImage: "number") {
                        navigation.sheet = .addButton(remoteID, .code)
                    }
                    .buttonStyle(.glass)
                }
            }
        } else {
            switch displayMode {
            case .remote: keypad(remote)
            case .list: ButtonListEditor(remoteID: remoteID, filter: filter)
            }
        }
    }

    private func keypad(_ remote: IRRemote) -> some View {
        let visible = filtered(remote.buttons)
        let layout = RemoteLayout(buttons: visible)
        return ScrollView {
            VStack(spacing: 28) {
                RemoteHeader(remote: remote)

                if filter.isEmpty {
                    RemoteKeypadView(layout: layout, remoteID: remoteID)
                        .frame(maxWidth: 460)
                }

                let extra = filter.isEmpty ? layout.others : visible
                if !extra.isEmpty {
                    VStack(alignment: .leading, spacing: 12) {
                        if filter.isEmpty && extra.count != visible.count {
                            Text("More Buttons")
                                .font(.headline)
                                .foregroundStyle(.secondary)
                        }
                        GlassEffectContainer(spacing: 10) {
                            LazyVGrid(columns: [GridItem(.adaptive(minimum: 140), spacing: 10)], spacing: 10) {
                                ForEach(extra) { button in
                                    RemoteButtonView(button: button, remoteID: remoteID)
                                }
                            }
                        }
                    }
                    .frame(maxWidth: 900)
                }
            }
            .padding(28)
            .frame(maxWidth: .infinity)
        }
        .focusable()
        .focusEffectDisabled()
        .focused($focused)
        .onAppear { focused = true }
        .onKeyPress(phases: .down) { press in
            guard keyboardShortcuts, let button = button(for: press, layout: RemoteLayout(buttons: remote.buttons)) else {
                return .ignored
            }
            navigation.send(button)
            return .handled
        }
    }

    /// Maps keyboard keys to remote buttons: arrows → D-pad, Return → OK,
    /// Space → play/pause, +/- → volume, M → mute, Delete → back, digits → keypad.
    private func button(for press: KeyPress, layout: RemoteLayout) -> IRButton? {
        guard press.modifiers.isEmpty || press.modifiers == .shift else { return nil }
        switch press.key {
        case .upArrow: return layout.up ?? layout.channelUp
        case .downArrow: return layout.down ?? layout.channelDown
        case .leftArrow: return layout.left
        case .rightArrow: return layout.right
        case .return: return layout.ok
        case .delete, .escape: return layout.navigation.first { $0.role == .back }
        case .space:
            return layout.transport.first { $0.role == .playPause }
                ?? layout.transport.first { $0.role == .play }
        default: break
        }
        switch press.characters.lowercased() {
        case "+", "=": return layout.volumeUp
        case "-", "_": return layout.volumeDown
        case "m": return layout.mute
        case "p": return layout.power
        case "]": return layout.channelUp
        case "[": return layout.channelDown
        case let digit where digit.count == 1 && digit.first!.isNumber:
            return Int(digit).flatMap { layout.digits[$0] }
        default: return nil
        }
    }

    private func filtered(_ buttons: [IRButton]) -> [IRButton] {
        let key = filter.searchKey
        guard !key.isEmpty else { return buttons }
        return buttons.filter { $0.name.searchKey.contains(key) }
    }

    @ToolbarContentBuilder
    private func toolbar(_ remote: IRRemote) -> some ToolbarContent {
        ToolbarItem(placement: .principal) {
            Picker("Display", selection: $displayMode) {
                Label("Remote", systemImage: "av.remote").tag(DisplayMode.remote)
                Label("Buttons", systemImage: "list.bullet").tag(DisplayMode.list)
            }
            .pickerStyle(.segmented)
            .help("Switch between the remote layout and the editable button list")
        }
        ToolbarItemGroup(placement: .primaryAction) {
            Menu {
                Button("Learn from Remote…", systemImage: "dot.radiowaves.left.and.right") {
                    navigation.sheet = .addButton(remoteID, .learn)
                }
                Button("Enter Code…", systemImage: "number") {
                    navigation.sheet = .addButton(remoteID, .code)
                }
            } label: {
                Label("Add Button", systemImage: "plus")
            }
            .help("Add a button")

            Button {
                library.toggleFavorite(remoteID)
            } label: {
                Label(remote.isFavorite ? "Unfavorite" : "Favorite", systemImage: remote.isFavorite ? "star.fill" : "star")
            }
            .help(remote.isFavorite ? "Remove from Favorites" : "Add to Favorites")

            Menu {
                Button("Edit Details…", systemImage: "pencil") { navigation.sheet = .editRemote(remoteID) }
                Button("Duplicate", systemImage: "plus.square.on.square") {
                    if let copy = library.duplicate(remoteID) { navigation.open(copy) }
                }
                Button("Export as Flipper .ir…", systemImage: "square.and.arrow.up") {
                    navigation.exportingRemoteID = remoteID
                }
                if let url = remote.origin?.url {
                    Link(destination: url) { Label("View Source on GitHub", systemImage: "safari") }
                }
                Divider()
                Button("Delete Remote…", systemImage: "trash", role: .destructive) { confirmDelete = true }
            } label: {
                Label("More", systemImage: "ellipsis")
            }
        }
    }
}

/// Title block above the keypad.
private struct RemoteHeader: View {
    let remote: IRRemote

    var body: some View {
        VStack(spacing: 10) {
            CategoryBadge(category: remote.category, size: 56)
            Text(remote.name)
                .font(.title.weight(.semibold))
                .multilineTextAlignment(.center)
            HStack(spacing: 8) {
                if !remote.brand.isEmpty { Text(remote.brand) }
                if !remote.model.isEmpty { Text(remote.model).foregroundStyle(.secondary) }
                Text(remote.category.displayName).foregroundStyle(.secondary)
            }
            .font(.callout)
            if let origin = remote.origin {
                Label("Imported from \(origin.sourceName)", systemImage: "globe")
                    .font(.caption)
                    .foregroundStyle(.tertiary)
            }
            if !remote.notes.isEmpty {
                Text(remote.notes)
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: 420)
            }
        }
    }
}

/// Editable, reorderable list of a remote's buttons.
private struct ButtonListEditor: View {
    let remoteID: IRRemote.ID
    var filter: String

    @Environment(LibraryStore.self) private var library
    @Environment(AppNavigation.self) private var navigation

    var body: some View {
        let buttons = library.remote(id: remoteID)?.buttons ?? []
        let key = filter.searchKey
        let visible = key.isEmpty ? buttons : buttons.filter { $0.name.searchKey.contains(key) }
        List {
            ForEach(visible) { button in
                HStack(spacing: 12) {
                    Image(systemName: button.role.symbolName ?? "circle.dashed")
                        .frame(width: 22)
                        .foregroundStyle(.tint)
                    VStack(alignment: .leading, spacing: 2) {
                        TextField("Name", text: nameBinding(button))
                            .textFieldStyle(.plain)
                            .font(.body.weight(.medium))
                        Text(button.signal.summary)
                            .font(.caption.monospaced())
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                    Button("Send", systemImage: "dot.radiowaves.right") { navigation.send(button) }
                        .buttonStyle(.glass)
                        .labelStyle(.iconOnly)
                        .help("Send \(button.displayName)")
                    Menu {
                        Button("Edit Code…", systemImage: "number") { navigation.sheet = .editButton(remoteID, button.id) }
                        Button("Delete", systemImage: "trash", role: .destructive) {
                            library.deleteButton(button.id, from: remoteID)
                        }
                    } label: {
                        Image(systemName: "ellipsis.circle")
                    }
                    .menuStyle(.button)
                    .buttonStyle(.borderless)
                    .fixedSize()
                }
                .padding(.vertical, 4)
            }
            .onMove { source, destination in
                guard key.isEmpty else { return }
                library.modify(remoteID) { $0.buttons.move(fromOffsets: source, toOffset: destination) }
            }
            .onDelete { offsets in
                let ids = offsets.map { visible[$0].id }
                for id in ids { library.deleteButton(id, from: remoteID) }
            }
        }
        .listStyle(.inset)
        .alternatingRowBackgrounds()
    }

    private func nameBinding(_ button: IRButton) -> Binding<String> {
        Binding {
            library.remote(id: remoteID)?.buttons.first { $0.id == button.id }?.name ?? button.name
        } set: { newValue in
            var updated = button
            updated.name = newValue
            library.updateButton(updated, in: remoteID)
        }
    }
}
