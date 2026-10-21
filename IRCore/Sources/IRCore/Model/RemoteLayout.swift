import Foundation

/// Arranges a remote's buttons into the familiar zones of a physical remote
/// (power, navigation pad, volume/channel rockers, transport, keypad…).
/// Each button appears in exactly one zone; anything unrecognised goes to `others`.
public struct RemoteLayout: Sendable {
    public var power: IRButton?
    public var powerOn: IRButton?
    public var powerOff: IRButton?
    public var input: IRButton?
    public var mute: IRButton?

    public var volumeUp: IRButton?
    public var volumeDown: IRButton?
    public var channelUp: IRButton?
    public var channelDown: IRButton?

    public var up: IRButton?
    public var down: IRButton?
    public var left: IRButton?
    public var right: IRButton?
    public var ok: IRButton?

    /// Back, home, menu, exit, info, guide, settings.
    public var navigation: [IRButton] = []
    /// Rewind, play, pause, stop… in a conventional order.
    public var transport: [IRButton] = []
    /// Digit buttons indexed 0…9.
    public var digits: [Int: IRButton] = [:]
    public var colors: [IRButton] = []
    /// Climate / fan / lighting controls.
    public var climate: [IRButton] = []
    public var others: [IRButton] = []

    public var hasDirectionalPad: Bool { up != nil || down != nil || left != nil || right != nil || ok != nil }
    public var hasVolume: Bool { volumeUp != nil || volumeDown != nil }
    public var hasChannel: Bool { channelUp != nil || channelDown != nil }
    public var hasNumberPad: Bool { digits.count >= 3 }

    public init(buttons: [IRButton]) {
        var transportByRole: [Int: IRButton] = [:]
        var navigationByRole: [Int: IRButton] = [:]
        var colorByRole: [Int: IRButton] = [:]

        func place(_ slot: inout IRButton?, _ button: IRButton) -> Bool {
            guard slot == nil else { return false }
            slot = button
            return true
        }
        func placeOrdered(_ map: inout [Int: IRButton], _ order: Int, _ button: IRButton) -> Bool {
            guard map[order] == nil else { return false }
            map[order] = button
            return true
        }

        for button in buttons {
            let placed: Bool
            switch button.role {
            case .power: placed = place(&power, button)
            case .powerOn: placed = place(&powerOn, button)
            case .powerOff: placed = place(&powerOff, button)
            case .input: placed = place(&input, button)
            case .mute: placed = place(&mute, button)
            case .volumeUp: placed = place(&volumeUp, button)
            case .volumeDown: placed = place(&volumeDown, button)
            case .channelUp: placed = place(&channelUp, button)
            case .channelDown: placed = place(&channelDown, button)
            case .up: placed = place(&up, button)
            case .down: placed = place(&down, button)
            case .left: placed = place(&left, button)
            case .right: placed = place(&right, button)
            case .ok: placed = place(&ok, button)
            case .back: placed = placeOrdered(&navigationByRole, 0, button)
            case .home: placed = placeOrdered(&navigationByRole, 1, button)
            case .menu: placed = placeOrdered(&navigationByRole, 2, button)
            case .exit: placed = placeOrdered(&navigationByRole, 3, button)
            case .info: placed = placeOrdered(&navigationByRole, 4, button)
            case .guide: placed = placeOrdered(&navigationByRole, 5, button)
            case .settings: placed = placeOrdered(&navigationByRole, 6, button)
            case .previous: placed = placeOrdered(&transportByRole, 0, button)
            case .rewind: placed = placeOrdered(&transportByRole, 1, button)
            case .play: placed = placeOrdered(&transportByRole, 2, button)
            case .playPause: placed = placeOrdered(&transportByRole, 3, button)
            case .pause: placed = placeOrdered(&transportByRole, 4, button)
            case .stop: placed = placeOrdered(&transportByRole, 5, button)
            case .record: placed = placeOrdered(&transportByRole, 6, button)
            case .fastForward: placed = placeOrdered(&transportByRole, 7, button)
            case .next: placed = placeOrdered(&transportByRole, 8, button)
            case .digit(let digit):
                if digits[digit] == nil { digits[digit] = button; placed = true } else { placed = false }
            case .red: placed = placeOrdered(&colorByRole, 0, button)
            case .green: placed = placeOrdered(&colorByRole, 1, button)
            case .yellow: placed = placeOrdered(&colorByRole, 2, button)
            case .blue: placed = placeOrdered(&colorByRole, 3, button)
            case .temperatureUp, .temperatureDown, .mode, .fanSpeed, .swing, .timer, .brightnessUp, .brightnessDown:
                climate.append(button)
                placed = true
            case .subtitles, .audioTrack, .other:
                placed = false
            }
            if !placed { others.append(button) }
        }

        navigation = navigationByRole.keys.sorted().compactMap { navigationByRole[$0] }
        transport = transportByRole.keys.sorted().compactMap { transportByRole[$0] }
        colors = colorByRole.keys.sorted().compactMap { colorByRole[$0] }

        // A lone number button is more useful in the general grid than as a keypad.
        if digits.count < 3 {
            others.append(contentsOf: digits.keys.sorted().compactMap { digits[$0] })
            digits = [:]
        }
        // Colour keys only make sense as a full set.
        if colors.count < 2 {
            others.append(contentsOf: colors)
            colors = []
        }
    }
}

/// Suggested button names when learning a new remote of a given category.
public enum LearningTemplate {
    public static func buttonNames(for category: DeviceCategory) -> [String] {
        let media = ["Power", "Volume Up", "Volume Down", "Mute"]
        let navigation = ["Up", "Down", "Left", "Right", "OK", "Back", "Home", "Menu"]
        let transport = ["Play", "Pause", "Stop", "Rewind", "Fast Forward", "Previous", "Next"]
        let digits = (1...9).map(String.init) + ["0"]
        switch category {
        case .tv:
            return media + ["Channel Up", "Channel Down", "Input"] + navigation + ["Info", "Guide"] + digits
        case .projector:
            return ["Power", "Input", "Menu", "Up", "Down", "Left", "Right", "OK", "Back", "Volume Up", "Volume Down", "Mute"]
        case .audio, .soundbar, .speaker:
            return media + ["Input", "Play", "Pause", "Previous", "Next", "Mode"]
        case .streaming:
            return ["Power"] + navigation + ["Play Pause", "Volume Up", "Volume Down", "Mute"]
        case .cableBox:
            return media + ["Channel Up", "Channel Down", "Guide", "Info"] + navigation + ["Record"] + digits
        case .mediaPlayer:
            return ["Power", "Eject"] + transport + navigation
        case .airConditioner:
            return ["Power", "Temp Up", "Temp Down", "Mode", "Fan Speed", "Swing", "Timer"]
        case .fan, .heater:
            return ["Power", "Speed", "Swing", "Timer", "Mode"]
        case .lighting:
            return ["On", "Off", "Brightness Up", "Brightness Down", "Red", "Green", "Blue", "White", "Mode"]
        case .camera:
            return ["Shutter", "Record", "Zoom In", "Zoom Out"]
        case .toy, .other:
            return ["Power"]
        }
    }
}
