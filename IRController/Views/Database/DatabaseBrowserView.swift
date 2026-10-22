import SwiftUI
import IRCore

/// Search and filter remotes across online databases, preview them, and import.
struct DatabaseBrowserView: View {
    @Environment(DatabaseStore.self) private var database
    @Environment(AppNavigation.self) private var navigation
    @State private var showingBrandPicker = false

    var body: some View {
        @Bindable var database = database
        VStack(spacing: 0) {
            FilterBar(showingBrandPicker: $showingBrandPicker)
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
            Divider()
            // Both panes, and the split view itself, fill the height below the filter bar.
            // Otherwise a short pane (like the "No Results" message) shrinks the split view
            // and the whole page ends up centred halfway down the window.
            HSplitView {
                resultsList
                    .frame(minWidth: 300, idealWidth: 360, maxWidth: 520, maxHeight: .infinity)
                preview
                    .frame(minWidth: 380, maxWidth: .infinity, maxHeight: .infinity)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .navigationTitle("Browse Databases")
        .navigationSubtitle(subtitle)
        .searchable(text: $database.query.text, placement: .toolbar, prompt: "Brand, model or device")
        .toolbar {
            ToolbarItemGroup(placement: .primaryAction) {
                Button {
                    navigation.sheet = .powerScan
                } label: {
                    Label("Power Scan", systemImage: "power.circle")
                }
                .help("Try the power button of each matching remote until your device responds")
                .disabled(database.results.isEmpty)

                Button {
                    Task { await database.refreshAll() }
                } label: {
                    Label("Refresh", systemImage: "arrow.clockwise")
                }
                .help("Download the latest database indexes")
                .disabled(database.isLoadingAny)
            }
        }
        .task { await database.refreshIfNeeded() }
        .onChange(of: database.results) {
            if let selected = database.selectedEntryID, !database.results.contains(where: { $0.id == selected }) {
                database.selectedEntryID = nil
            }
        }
    }

    private var selectionBinding: Binding<DatabaseEntry.ID?> {
        Binding { database.selectedEntryID } set: { database.selectedEntryID = $0 }
    }

    private var subtitle: String {
        if database.isLoadingAny && database.totalEntryCount == 0 { return "Downloading indexes…" }
        let count = database.results.count
        let suffix = count >= database.resultLimit ? "+" : ""
        return "\(count.formatted())\(suffix) of \(database.totalEntryCount.formatted()) remotes"
    }

    @ViewBuilder
    private var resultsList: some View {
        if database.totalEntryCount == 0 {
            sourcesStatus
        } else if database.results.isEmpty && !database.isSearching {
            ContentUnavailableView.search(text: database.query.text)
        } else {
            List(database.results, selection: selectionBinding) { entry in
                ResultRow(entry: entry, sourceName: database.source(id: entry.sourceID)?.name ?? "")
                    .tag(entry.id)
            }
            .listStyle(.inset)
        }
    }

    @ViewBuilder
    private var sourcesStatus: some View {
        VStack(spacing: 14) {
            ForEach(database.enabledSources) { source in
                HStack {
                    Image(systemName: "externaldrive.connected.to.line.below")
                    Text(source.name)
                    Spacer()
                    switch database.loadStates[source.id] ?? .idle {
                    case .loading, .idle:
                        ProgressView().controlSize(.small)
                    case .loaded:
                        Image(systemName: "checkmark.circle.fill").foregroundStyle(.green)
                    case .failed(let message):
                        Image(systemName: "exclamationmark.triangle.fill")
                            .foregroundStyle(.orange)
                            .help(message)
                    }
                }
            }
            if database.loadStates.values.contains(where: { if case .failed = $0 { true } else { false } }) {
                Text("Some databases couldn’t be downloaded. Check your internet connection, then click Refresh.")
                    .font(.callout)
                    .foregroundStyle(.secondary)
                Button("Retry", systemImage: "arrow.clockwise") { Task { await database.refreshAll() } }
                    .buttonStyle(.glass)
            }
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    @ViewBuilder
    private var preview: some View {
        if let selected = database.selectedEntryID, let entry = database.results.first(where: { $0.id == selected }) {
            DatabasePreviewView(entry: entry)
                .id(entry.id)
        } else {
            ContentUnavailableView(
                "Select a Remote",
                systemImage: "av.remote",
                description: Text("Pick a result to preview its buttons. You can try them with your blaster before importing.")
            )
        }
    }
}

/// Category, brand, source and curation filters.
private struct FilterBar: View {
    @Environment(DatabaseStore.self) private var database
    @Binding var showingBrandPicker: Bool

    var body: some View {
        @Bindable var database = database
        HStack(spacing: 10) {
            Menu {
                Picker("Device Type", selection: $database.query.category) {
                    Text("All Device Types").tag(DeviceCategory?.none)
                    Divider()
                    ForEach(database.categoryFacets, id: \.value) { facet in
                        Label("\(facet.value.displayName) (\(facet.count.formatted()))", systemImage: facet.value.symbolName)
                            .tag(Optional(facet.value))
                    }
                }
                .pickerStyle(.inline)
            } label: {
                Label(database.query.category?.displayName ?? "All Device Types",
                      systemImage: database.query.category?.symbolName ?? "square.grid.2x2")
            }
            .menuStyle(.button)
            .buttonStyle(.glass)
            .fixedSize()

            Button {
                showingBrandPicker.toggle()
            } label: {
                Label(database.query.brand ?? "All Brands", systemImage: "tag")
            }
            .buttonStyle(.glass)
            .popover(isPresented: $showingBrandPicker, arrowEdge: .bottom) {
                BrandPicker(selection: $database.query.brand, facets: database.brandFacets) {
                    showingBrandPicker = false
                }
                .frame(width: 300, height: 380)
            }

            if database.enabledSources.count > 1 {
                Menu {
                    Picker("Source", selection: sourceBinding) {
                        Text("All Sources").tag(String?.none)
                        ForEach(database.enabledSources) { source in
                            Text(source.name).tag(Optional(source.id))
                        }
                    }
                    .pickerStyle(.inline)
                } label: {
                    Label(sourceTitle, systemImage: "externaldrive.connected.to.line.below")
                }
                .menuStyle(.button)
                .buttonStyle(.glass)
                .fixedSize()
            }

            Toggle(isOn: $database.query.curatedOnly) {
                Label("Curated Only", systemImage: "checkmark.seal")
            }
            .toggleStyle(.button)
            .buttonStyle(.glass)
            .help("Hide bulk-converted files and show only hand-curated remotes")

            if hasFilters {
                Button("Clear", systemImage: "xmark.circle.fill") {
                    database.query = DatabaseQuery()
                }
                .buttonStyle(.borderless)
                .foregroundStyle(.secondary)
            }

            Spacer()

            if database.isSearching || database.isLoadingAny {
                ProgressView()
                    .controlSize(.small)
            }
        }
        .controlSize(.regular)
    }

    private var hasFilters: Bool {
        let q = database.query
        return !q.text.isEmpty || q.category != nil || q.brand != nil || q.curatedOnly || !q.sourceIDs.isEmpty
    }

    private var sourceBinding: Binding<String?> {
        Binding {
            database.query.sourceIDs.first
        } set: { newValue in
            database.query.sourceIDs = newValue.map { [$0] } ?? []
        }
    }

    private var sourceTitle: String {
        guard let id = database.query.sourceIDs.first else { return "All Sources" }
        return database.source(id: id)?.name ?? "Source"
    }
}

/// Searchable brand list with match counts.
private struct BrandPicker: View {
    @Binding var selection: String?
    var facets: [FacetCount<String>]
    var onPick: () -> Void
    @State private var search = ""

    var body: some View {
        VStack(spacing: 0) {
            TextField("Search Brands", text: $search)
                .textFieldStyle(.roundedBorder)
                .padding(10)
            List {
                Button {
                    selection = nil
                    onPick()
                } label: {
                    HStack {
                        Text("All Brands")
                        Spacer()
                        if selection == nil { Image(systemName: "checkmark") }
                    }
                }
                .buttonStyle(.plain)
                ForEach(filtered, id: \.value) { facet in
                    Button {
                        selection = facet.value
                        onPick()
                    } label: {
                        HStack {
                            Text(facet.value)
                            Spacer()
                            Text(facet.count.formatted())
                                .foregroundStyle(.secondary)
                                .monospacedDigit()
                            if selection?.searchKey == facet.value.searchKey {
                                Image(systemName: "checkmark")
                            }
                        }
                        .contentShape(.rect)
                    }
                    .buttonStyle(.plain)
                }
            }
            .listStyle(.plain)
        }
    }

    private var filtered: [FacetCount<String>] {
        let key = search.searchKey
        guard !key.isEmpty else { return facets }
        return facets.filter { $0.value.searchKey.contains(key) }
    }
}

private struct ResultRow: View {
    let entry: DatabaseEntry
    let sourceName: String

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: entry.category.symbolName)
                .frame(width: 24)
                .foregroundStyle(.tint)
            VStack(alignment: .leading, spacing: 2) {
                Text(entry.brand.isEmpty ? entry.model : entry.brand)
                    .font(.body.weight(.semibold))
                    .lineLimit(1)
                if !entry.brand.isEmpty {
                    Text(entry.model)
                        .lineLimit(1)
                        .foregroundStyle(.secondary)
                }
                HStack(spacing: 6) {
                    Text(sourceName)
                    if !entry.collection.isEmpty { Text("· \(entry.collection)") }
                    if !entry.categoryName.isEmpty { Text("· \(entry.categoryName)") }
                }
                .font(.caption)
                .foregroundStyle(.tertiary)
                .lineLimit(1)
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 2)
    }
}
