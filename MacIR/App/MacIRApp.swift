import SwiftUI
import IRCore

@main
struct MacIRApp: App {
    @State private var library = LibraryStore.shared
    @State private var blaster = BlasterManager.shared
    @State private var database = DatabaseStore.shared
    @State private var navigation = AppNavigation.shared
    @AppStorage(SettingsKey.showMenuBarExtra) private var showMenuBarExtra = true

    init() {
        BlasterManager.shared.start()
        #if DEBUG
        UITestSupport.apply()
        #endif
    }

    var body: some Scene {
        Window("MacIR", id: "main") {
            ContentView()
                .environment(library)
                .environment(blaster)
                .environment(database)
                .environment(navigation)
                .frame(minWidth: 900, minHeight: 600)
                .task { await database.refreshIfNeeded() }
        }
        .defaultSize(width: 1200, height: 780)
        .commands {
            AppCommands(navigation: navigation, blaster: blaster)
        }

        Settings {
            SettingsView()
                .environment(library)
                .environment(blaster)
                .environment(database)
                .environment(navigation)
        }

        MenuBarExtra("MacIR", systemImage: "av.remote", isInserted: $showMenuBarExtra) {
            MenuBarRemoteView()
                .environment(library)
                .environment(blaster)
                .environment(database)
                .environment(navigation)
        }
        .menuBarExtraStyle(.window)
    }
}

/// Menu bar commands.
struct AppCommands: Commands {
    let navigation: AppNavigation
    let blaster: BlasterManager

    var body: some Commands {
        CommandGroup(replacing: .newItem) {
            Button("New Remote…") { navigation.sheet = .newRemote }
                .keyboardShortcut("n")
            Button("Learn New Remote") { navigation.selection = .learn }
                .keyboardShortcut("l", modifiers: [.command, .shift])
            Button("Browse Databases") { navigation.selection = .browse }
                .keyboardShortcut("b", modifiers: [.command, .shift])
            Divider()
            Button("Import…") { navigation.isImporting = true }
                .keyboardShortcut("o")
        }
        CommandMenu("Blaster") {
            Button("Reconnect IR Blaster") { blaster.reconnect() }
                .keyboardShortcut("r", modifiers: [.command, .shift])
        }
    }
}
