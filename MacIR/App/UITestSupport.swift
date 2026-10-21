#if DEBUG
import Foundation
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
        case "remote":
            if let remote = LibraryStore.shared.remotes.first { AppNavigation.shared.open(remote) }
        default: break
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
