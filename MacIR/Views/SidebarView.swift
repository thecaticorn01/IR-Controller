import SwiftUI
import UniformTypeIdentifiers
import IRCore

struct SidebarView: View {
    @Environment(LibraryStore.self) private var library
    @Environment(AppNavigation.self) private var navigation
    @State private var isDropTargeted = false
    @State private var remoteToDelete: IRRemote?

    var body: some View {
        @Bindable var navigation = navigation
        List(selection: $navigation.selection) {
            Section {
                Label("Browse Databases", systemImage: "globe")
                    .tag(SidebarItem.browse)
                Label("Learn New Remote", systemImage: "dot.radiowaves.left.and.right")
                    .tag(SidebarItem.learn)
            }

            Section("My Remotes") {
                if library.remotes.isEmpty {
                    Text("Remotes you import or learn appear here.")
                        .font(.callout)
                        .foregroundStyle(.secondary)
                        .selectionDisabled()
                }
                ForEach(library.remotes) { remote in
                    RemoteRow(remote: remote)
                        .tag(SidebarItem.remote(remote.id))
                        .contextMenu { contextMenu(for: remote) }
                }
                .onMove { library.move(fromOffsets: $0, toOffset: $1) }
            }
        }
        .listStyle(.sidebar)
        .safeAreaInset(edge: .bottom) {
            DeviceStatusPill()
                .padding(10)
        }
        .toolbar {
            ToolbarItem {
                Menu {
                    Button("New Remote…", systemImage: "plus.rectangle") { navigation.sheet = .newRemote }
                    Button("Learn New Remote", systemImage: "dot.radiowaves.left.and.right") { navigation.selection = .learn }
                    Button("Browse Databases", systemImage: "globe") { navigation.selection = .browse }
                    Divider()
                    Button("Import File…", systemImage: "square.and.arrow.down") { navigation.isImporting = true }
                } label: {
                    Label("Add Remote", systemImage: "plus")
                }
                .help("Add a remote")
            }
        }
        .dropDestination(for: URL.self) { urls, _ in
            let supported = urls.filter { ["ir", "csv", "json"].contains($0.pathExtension.lowercased()) }
            guard !supported.isEmpty else { return false }
            let summary = library.importFiles(supported)
            if let first = summary.imported.first { navigation.open(first) }
            if !summary.messages.isEmpty {
                navigation.alert = AlertMessage(title: "Import", message: summary.messages.joined(separator: "\n"))
            }
            return !summary.imported.isEmpty
        } isTargeted: { isDropTargeted = $0 }
        .overlay {
            if isDropTargeted {
                RoundedRectangle(cornerRadius: 12)
                    .strokeBorder(Color.accentColor, style: StrokeStyle(lineWidth: 2, dash: [6]))
                    .padding(6)
                    .allowsHitTesting(false)
            }
        }
        .confirmationDialog(
            "Delete “\(remoteToDelete?.name ?? "")”?",
            isPresented: Binding { remoteToDelete != nil } set: { if !$0 { remoteToDelete = nil } },
            presenting: remoteToDelete
        ) { remote in
            Button("Delete Remote", role: .destructive) {
                if navigation.selection == .remote(remote.id) { navigation.selection = nil }
                library.delete(remote.id)
            }
        } message: { _ in
            Text("This removes the remote and all of its buttons from your library.")
        }
    }

    @ViewBuilder
    private func contextMenu(for remote: IRRemote) -> some View {
        Button(remote.isFavorite ? "Remove from Favorites" : "Add to Favorites",
               systemImage: remote.isFavorite ? "star.slash" : "star") {
            library.toggleFavorite(remote.id)
        }
        Button("Edit Details…", systemImage: "pencil") { navigation.sheet = .editRemote(remote.id) }
        Button("Duplicate", systemImage: "plus.square.on.square") {
            if let copy = library.duplicate(remote.id) { navigation.open(copy) }
        }
        Button("Export as Flipper .ir…", systemImage: "square.and.arrow.up") {
            navigation.exportingRemoteID = remote.id
        }
        Divider()
        Button("Delete…", systemImage: "trash", role: .destructive) { remoteToDelete = remote }
    }
}

private struct RemoteRow: View {
    let remote: IRRemote

    var body: some View {
        Label {
            HStack(spacing: 4) {
                VStack(alignment: .leading, spacing: 1) {
                    Text(remote.name)
                        .lineLimit(1)
                    if !remote.brand.isEmpty || !remote.model.isEmpty {
                        Text([remote.brand, remote.model].filter { !$0.isEmpty }.joined(separator: " "))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                }
                Spacer(minLength: 0)
                if remote.isFavorite {
                    Image(systemName: "star.fill")
                        .font(.caption)
                        .foregroundStyle(.yellow)
                        .accessibilityLabel("Favorite")
                }
            }
        } icon: {
            Image(systemName: remote.category.symbolName)
        }
    }
}
