#if DEBUG
import AppKit
import IRCore

/// Debug-only hooks used by CI to launch straight into a screen for smoke-test screenshots:
/// `MacIR -UITestRoute remote -UITestDemoData YES`
@MainActor
enum UITestSupport {
    static func apply() {
        let defaults = UserDefaults.standard
        if defaults.bool(forKey: "UITestDemoData"), LibraryStore.shared.remotes.isEmpty {
            seedDemoLibrary()
        }
        switch defaults.string(forKey: "UITestRoute") {
        case "browse": AppNavigation.shared.selection = .browse
        case "learn": AppNavigation.shared.selection = .learn
        case "preview":
            AppNavigation.shared.selection = .browse
            let database = DatabaseStore.shared
            database.query.text = "samsung tv"
            Task {
                for _ in 0..<40 where database.results.isEmpty {
                    try? await Task.sleep(for: .milliseconds(250))
                }
                database.selectedEntryID = database.results.first?.id
            }
        case "remote":
            if let remote = LibraryStore.shared.remotes.first { AppNavigation.shared.open(remote) }
        default: break
        }
        if defaults.string(forKey: "UITestRoute") != nil {
            // Bring the window to the front so screenshots show the active appearance.
            DispatchQueue.main.asyncAfter(deadline: .now() + 1) {
                NSApplication.shared.activate()
            }
        }
    }

    private static func seedDemoLibrary() {
        let names = LearningTemplate.buttonNames(for: .tv) + ["Netflix", "Sleep", "Picture Mode"]
        let buttons = names.enumerated().map { index, name in
            IRButton(name: name, signal: .parsed(ParsedSignal(protocolName: "Samsung32", address: 0x07, command: UInt32(index + 2))))
        }
        LibraryStore.shared.add(IRRemote(name: "Living Room TV", brand: "Samsung", model: "AA59-00600A",
                                         category: .tv, buttons: buttons, isFavorite: true))
        LibraryStore.shared.add(IRRemote(name: "Bedroom Fan", brand: "Dyson", category: .fan, buttons: [
            IRButton(name: "Power", signal: .parsed(ParsedSignal(protocolName: "NEC", address: 0x01, command: 0x10))),
            IRButton(name: "Speed", signal: .parsed(ParsedSignal(protocolName: "NEC", address: 0x01, command: 0x11))),
            IRButton(name: "Swing", signal: .parsed(ParsedSignal(protocolName: "NEC", address: 0x01, command: 0x12))),
        ]))
    }
}
#endif
