import SwiftUI
import AppKit
import IRCore

/// Compact remote shown from the menu bar for quick access without opening the main window.
struct MenuBarRemoteView: View {
    @Environment(LibraryStore.self) private var library
    @Environment(BlasterManager.self) private var blaster
    @Environment(\.openWindow) private var openWindow
    @AppStorage(SettingsKey.menuBarRemoteID) private var selectedIDString = ""

    private var candidates: [IRRemote] {
        let favorites = library.favorites
        return favorites.isEmpty ? library.remotes : favorites
    }

    private var selected: IRRemote? {
        candidates.first { $0.id.uuidString == selectedIDString } ?? candidates.first
    }

    var body: some View {
        VStack(spacing: 14) {
            HStack {
                StatusIndicator(state: blaster.state, activity: blaster.activity, sendCount: blaster.sendCount)
                if candidates.count > 1 {
                    Picker("Remote", selection: $selectedIDString) {
                        ForEach(candidates) { remote in
                            Text(remote.name).tag(remote.id.uuidString)
                        }
                    }
                    .labelsHidden()
                } else {
                    Text(selected?.name ?? "MacIR")
                        .font(.headline)
                }
                Spacer()
                Button {
                    openWindow(id: "main")
                    NSApplication.shared.activate()
                } label: {
                    Image(systemName: "macwindow")
                }
                .buttonStyle(.glass)
                .help("Open MacIR")
            }

            if let remote = selected {
                let layout = RemoteLayout(buttons: remote.buttons)
                ScrollView {
                    VStack(spacing: 16) {
                        RemoteKeypadView(layout: layout)
                        if !layout.others.isEmpty {
                            GlassEffectContainer(spacing: 8) {
                                LazyVGrid(columns: [GridItem(.adaptive(minimum: 100), spacing: 8)], spacing: 8) {
                                    ForEach(layout.others.prefix(24)) { button in
                                        RemoteButtonView(button: button)
                                    }
                                }
                            }
                        }
                    }
                    .padding(.vertical, 4)
                }
                .frame(maxHeight: 520)
            } else {
                ContentUnavailableView("No Remotes", systemImage: "av.remote",
                                       description: Text("Add a remote in MacIR to control it from here."))
            }
        }
        .padding(16)
        .frame(width: 340)
    }
}
