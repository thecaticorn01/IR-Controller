import Foundation
import Observation
import IRCore

enum SidebarItem: Hashable {
    case browse
    case learn
    case remote(IRRemote.ID)
}

/// Modal sheets presented from anywhere in the app.
enum AppSheet: Identifiable {
    case newRemote
    case editRemote(IRRemote.ID)
    case addButton(IRRemote.ID, AddButtonSheet.Mode)
    case editButton(IRRemote.ID, IRButton.ID)
    case powerScan

    var id: String {
        switch self {
        case .newRemote: "new"
        case .editRemote(let id): "edit-\(id)"
        case .addButton(let id, let mode): "add-\(id)-\(mode)"
        case .editButton(let remote, let button): "button-\(remote)-\(button)"
        case .powerScan: "scan"
        }
    }
}

struct AlertMessage: Identifiable {
    let id = UUID()
    var title: String
    var message: String
}

/// Window-wide navigation and presentation state.
@MainActor @Observable
final class AppNavigation {
    static let shared = AppNavigation()

    var selection: SidebarItem?
    var sheet: AppSheet?
    var isImporting = false
    var alert: AlertMessage?
    /// Remote to export as a Flipper `.ir` file.
    var exportingRemoteID: IRRemote.ID?

    private init() {}

    func show(_ error: Error, title: String = "Something Went Wrong") {
        alert = AlertMessage(title: title, message: error.localizedDescription)
    }

    func open(_ remote: IRRemote) {
        selection = .remote(remote.id)
    }

    /// Sends a button, surfacing failures as an alert.
    func send(_ button: IRButton, extraRepeats: Int = 0) {
        let blaster = BlasterManager.shared
        let repeats = extraRepeats + UserDefaults.standard.integer(forKey: SettingsKey.extraRepeats)
        Task {
            do {
                try await blaster.send(button.signal, name: button.name, extraRepeats: repeats)
            } catch {
                show(error, title: "Couldn’t Send “\(button.name)”")
            }
        }
    }
}

/// UserDefaults keys shared by @AppStorage declarations.
enum SettingsKey {
    static let extraRepeats = "extraRepeats"
    static let learnTimeout = "learnTimeout"
    static let learnFrequency = "learnFrequency"
    static let showMenuBarExtra = "showMenuBarExtra"
    static let menuBarRemoteID = "menuBarRemoteID"
    static let preferDecodedProtocol = "preferDecodedProtocol"
    static let keyboardShortcuts = "keyboardShortcuts"
}
