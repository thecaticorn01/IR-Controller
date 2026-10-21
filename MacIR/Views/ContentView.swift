import SwiftUI
import UniformTypeIdentifiers
import IRCore

struct ContentView: View {
    @Environment(LibraryStore.self) private var library
    @Environment(AppNavigation.self) private var navigation
    @Environment(DatabaseStore.self) private var database

    var body: some View {
        @Bindable var navigation = navigation
        NavigationSplitView {
            SidebarView()
                .navigationSplitViewColumnWidth(min: 230, ideal: 260, max: 340)
        } detail: {
            detail
        }
        .sheet(item: $navigation.sheet) { sheet in
            sheetView(sheet)
        }
        .fileImporter(
            isPresented: $navigation.isImporting,
            allowedContentTypes: [.flipperIR, .commaSeparatedText, .json, .plainText],
            allowsMultipleSelection: true
        ) { result in
            switch result {
            case .success(let urls): importFiles(urls)
            case .failure(let error): navigation.show(error, title: "Import Failed")
            }
        }
        .fileExporter(
            isPresented: exportBinding,
            document: exportDocument,
            contentType: .flipperIR,
            defaultFilename: exportFilename
        ) { result in
            if case .failure(let error) = result {
                navigation.show(error, title: "Export Failed")
            }
            navigation.exportingRemoteID = nil
        }
        .alert(
            navigation.alert?.title ?? "",
            isPresented: Binding { navigation.alert != nil } set: { if !$0 { navigation.alert = nil } },
            presenting: navigation.alert
        ) { _ in
            Button("OK", role: .cancel) {}
        } message: { alert in
            Text(alert.message)
        }
    }

    @ViewBuilder
    private var detail: some View {
        switch navigation.selection {
        case .browse?:
            DatabaseBrowserView()
        case .learn?:
            LearnRemoteView()
        case .remote(let id)?:
            RemoteDetailView(remoteID: id)
                .id(id)
        case nil:
            WelcomeView()
        }
    }

    @ViewBuilder
    private func sheetView(_ sheet: AppSheet) -> some View {
        switch sheet {
        case .newRemote:
            RemoteInfoSheet(remoteID: nil)
        case .editRemote(let id):
            RemoteInfoSheet(remoteID: id)
        case .addButton(let id, let mode):
            AddButtonSheet(remoteID: id, mode: mode)
        case .editButton(let remoteID, let buttonID):
            AddButtonSheet(remoteID: remoteID, mode: .code, existingButtonID: buttonID)
        case .powerScan:
            PowerScanSheet()
        }
    }

    private func importFiles(_ urls: [URL]) {
        let summary = library.importFiles(urls)
        if let first = summary.imported.first {
            navigation.open(first)
        }
        if !summary.messages.isEmpty {
            let title = summary.imported.isEmpty ? "Import Failed" : "Imported with Warnings"
            navigation.alert = AlertMessage(title: title, message: summary.messages.joined(separator: "\n"))
        }
    }

    private var exportBinding: Binding<Bool> {
        Binding {
            navigation.exportingRemoteID != nil
        } set: { presented in
            if !presented { navigation.exportingRemoteID = nil }
        }
    }

    private var exportDocument: IRFileDocument? {
        navigation.exportingRemoteID.flatMap { library.remote(id: $0) }.map(IRFileDocument.init(remote:))
    }

    private var exportFilename: String {
        let name = navigation.exportingRemoteID.flatMap { library.remote(id: $0)?.name } ?? "Remote"
        return name.replacingOccurrences(of: " ", with: "_") + ".ir"
    }
}

/// Shown when nothing is selected, and on first launch.
struct WelcomeView: View {
    @Environment(AppNavigation.self) private var navigation
    @Environment(LibraryStore.self) private var library
    @Environment(DatabaseStore.self) private var database

    var body: some View {
        ScrollView {
            VStack(spacing: 32) {
                VStack(spacing: 12) {
                    Image(systemName: "av.remote.fill")
                        .font(.system(size: 64))
                        .foregroundStyle(.tint)
                        .symbolEffect(.wiggle, options: .nonRepeating)
                    Text("Your Mac is now a remote")
                        .font(.largeTitle.weight(.bold))
                    Text("Control TVs, projectors, air conditioners and more with your ELK Smart USB-C IR blaster.")
                        .font(.title3)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }

                GlassEffectContainer(spacing: 16) {
                    HStack(spacing: 16) {
                        WelcomeCard(
                            title: "Find Your Remote",
                            detail: "Search \(database.totalEntryCount > 0 ? database.totalEntryCount.formatted() : "thousands of") remotes from Flipper-IRDB and IRDB.",
                            systemImage: "magnifyingglass"
                        ) { navigation.selection = .browse }
                        WelcomeCard(
                            title: "Learn a Remote",
                            detail: "Point your existing remote at the blaster and teach MacIR each button.",
                            systemImage: "dot.radiowaves.left.and.right"
                        ) { navigation.selection = .learn }
                        WelcomeCard(
                            title: "Import a File",
                            detail: "Open Flipper Zero .ir files or IRDB .csv files you already have.",
                            systemImage: "square.and.arrow.down"
                        ) { navigation.isImporting = true }
                    }
                }
                .frame(maxWidth: 820)
            }
            .padding(40)
            .frame(maxWidth: .infinity)
        }
    }
}

private struct WelcomeCard: View {
    var title: String
    var detail: String
    var systemImage: String
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 10) {
                Image(systemName: systemImage)
                    .font(.title)
                    .foregroundStyle(.tint)
                    .frame(height: 34, alignment: .leading)
                Text(title)
                    .font(.headline)
                Text(detail)
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 0)
            }
            .frame(maxWidth: .infinity, minHeight: 140, alignment: .topLeading)
            .padding(18)
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .glassEffect(.regular.interactive(), in: .rect(cornerRadius: 22))
    }
}
