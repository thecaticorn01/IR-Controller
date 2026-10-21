import SwiftUI
import IRCore

/// Downloads a database remote, lets the user try its buttons, and imports it.
struct DatabasePreviewView: View {
    let entry: DatabaseEntry

    @Environment(DatabaseStore.self) private var database
    @Environment(LibraryStore.self) private var library
    @Environment(AppNavigation.self) private var navigation
    @State private var filter = ""

    private var existing: IRRemote? {
        library.remotes.first { $0.origin?.path == entry.path && $0.origin?.sourceID == entry.sourceID }
    }

    var body: some View {
        Group {
            switch database.preview(for: entry) {
            case .loaded(let result)?:
                loaded(result)
            case .failed(let message)?:
                ContentUnavailableView {
                    Label("Couldn’t Load Remote", systemImage: "wifi.exclamationmark")
                } description: {
                    Text(message)
                } actions: {
                    Button("Try Again") { Task { await database.loadPreview(entry, force: true) } }
                        .buttonStyle(.glass)
                }
            case .loading?, nil:
                ProgressView("Downloading \(entry.title)…")
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .task(id: entry.id) { await database.loadPreview(entry) }
    }

    private func loaded(_ result: RemoteImport) -> some View {
        let remote = result.remote
        let key = filter.searchKey
        let buttons = key.isEmpty ? remote.buttons : remote.buttons.filter { $0.name.searchKey.contains(key) }
        return ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                header(remote)

                if !result.warnings.isEmpty {
                    VStack(alignment: .leading, spacing: 4) {
                        ForEach(result.warnings, id: \.self) { warning in
                            Label(warning, systemImage: "exclamationmark.triangle")
                        }
                    }
                    .font(.caption)
                    .foregroundStyle(.orange)
                }

                actionBar(result)

                HStack {
                    Text("Try the buttons")
                        .font(.headline)
                    Text("Click any button to send it with your blaster.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Spacer()
                    if remote.buttons.count > 12 {
                        TextField("Filter", text: $filter)
                            .textFieldStyle(.roundedBorder)
                            .frame(width: 160)
                    }
                }

                GlassEffectContainer(spacing: 8) {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 130), spacing: 8)], spacing: 8) {
                        ForEach(buttons) { button in
                            RemoteButtonView(button: button)
                        }
                    }
                }
            }
            .padding(24)
        }
    }

    private func header(_ remote: IRRemote) -> some View {
        HStack(alignment: .top, spacing: 16) {
            CategoryBadge(category: entry.category, size: 56)
            VStack(alignment: .leading, spacing: 4) {
                Text(entry.brand.isEmpty ? entry.model : entry.brand)
                    .font(.title2.weight(.semibold))
                if !entry.brand.isEmpty {
                    Text(entry.model)
                        .font(.title3)
                        .foregroundStyle(.secondary)
                }
                HStack(spacing: 6) {
                    Text(entry.category.displayName)
                    Text("·")
                    Text("\(remote.buttons.count) buttons")
                    if let source = database.source(id: entry.sourceID) {
                        Text("·")
                        Text(source.name)
                    }
                }
                .font(.callout)
                .foregroundStyle(.secondary)
                Text(entry.path)
                    .font(.caption.monospaced())
                    .foregroundStyle(.tertiary)
                    .textSelection(.enabled)
                    .lineLimit(2)
            }
            Spacer()
        }
    }

    private func actionBar(_ result: RemoteImport) -> some View {
        HStack(spacing: 10) {
            if let existing {
                Button("Open in Library", systemImage: "checkmark.circle.fill") {
                    navigation.open(existing)
                }
                .buttonStyle(.glassProminent)
                .tint(.green)
            } else {
                Button("Add to My Remotes", systemImage: "plus.circle.fill") {
                    let added = library.add(result.remote)
                    navigation.open(added)
                }
                .buttonStyle(.glassProminent)
                .keyboardShortcut(.defaultAction)
            }
            if let power = result.remote.powerButton {
                Button("Test Power", systemImage: "power") {
                    navigation.send(power)
                }
                .buttonStyle(.glass)
            }
            Spacer()
            if let source = database.source(id: entry.sourceID), let url = source.webURL(for: entry.path) {
                Link(destination: url) {
                    Label("View on GitHub", systemImage: "arrow.up.right.square")
                }
            }
        }
        .controlSize(.large)
    }
}
