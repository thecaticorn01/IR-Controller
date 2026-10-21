import AppIntents
import IRCore

/// Lets Shortcuts and automations press a button on a saved remote.
struct SendRemoteButtonIntent: AppIntent {
    static let title: LocalizedStringResource = "Press Remote Button"
    static var description: IntentDescription {
        IntentDescription("Sends an infrared command from one of your IR Controller remotes using the connected IR blaster.")
    }

    @Parameter(title: "Remote", description: "The name of a remote in your IR Controller library.")
    var remoteName: String

    @Parameter(title: "Button", description: "The name of the button to press, e.g. Power.")
    var buttonName: String

    @Parameter(title: "Repeat", description: "Extra times to repeat the code (for buttons that need to be held).", default: 0, inclusiveRange: (0, 20))
    var repeats: Int

    static var parameterSummary: some ParameterSummary {
        Summary("Press \(\.$buttonName) on \(\.$remoteName)") {
            \.$repeats
        }
    }

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog {
        let library = LibraryStore.shared
        guard let remote = library.remote(named: remoteName) else {
            throw IntentError.message("There is no remote named “\(remoteName)”.")
        }
        guard let button = remote.button(named: buttonName) else {
            throw IntentError.message("“\(remote.name)” has no button named “\(buttonName)”.")
        }
        let blaster = BlasterManager.shared
        blaster.start()
        if !blaster.state.isReady {
            // Give a freshly launched app a moment to find the dongle.
            for _ in 0..<20 where !blaster.state.isReady {
                try await Task.sleep(for: .milliseconds(150))
            }
        }
        try await blaster.send(button.signal, name: button.name, extraRepeats: repeats)
        return .result(dialog: "Sent \(button.displayName) to \(remote.name).")
    }
}

enum IntentError: Error, CustomLocalizedStringResourceConvertible {
    case message(String)

    var localizedStringResource: LocalizedStringResource {
        switch self {
        case .message(let text): "\(text)"
        }
    }
}

struct IRControllerShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: SendRemoteButtonIntent(),
            phrases: ["Press a remote button with \(.applicationName)"],
            shortTitle: "Press Remote Button",
            systemImageName: "av.remote"
        )
    }
}
