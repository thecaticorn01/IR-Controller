import Foundation

/// Semantic role of a remote button, inferred from its (often inconsistent) name.
/// Used to lay out a physical-remote-like keypad and to pick icons.
public enum ButtonRole: Hashable, Sendable {
    case power, powerOn, powerOff
    case volumeUp, volumeDown, mute
    case channelUp, channelDown
    case up, down, left, right, ok
    case menu, back, home, exit, info, guide, settings
    case input
    case play, pause, playPause, stop, record
    case next, previous, rewind, fastForward
    case digit(Int)
    case red, green, yellow, blue
    case temperatureUp, temperatureDown, mode, fanSpeed, swing, timer
    case brightnessUp, brightnessDown
    case subtitles, audioTrack
    case other

    public init(buttonName: String) {
        var name = buttonName.replacingOccurrences(of: "+", with: " plus ")
        if name.hasSuffix("-") { name = String(name.dropLast()) + " minus" }
        let key = name.searchKey
        let compact = key.replacingOccurrences(of: " ", with: "")
        self = ButtonRole.lookup[compact] ?? ButtonRole.fuzzy(key: key, compact: compact)
    }

    /// SF Symbol name, if the role has a natural icon.
    public var symbolName: String? {
        switch self {
        case .power: "power"
        case .powerOn: "power.circle"
        case .powerOff: "poweroff"
        case .volumeUp: "speaker.plus"
        case .volumeDown: "speaker.minus"
        case .mute: "speaker.slash"
        case .channelUp: "chevron.up"
        case .channelDown: "chevron.down"
        case .up: "chevron.up"
        case .down: "chevron.down"
        case .left: "chevron.left"
        case .right: "chevron.right"
        case .ok: nil
        case .menu: "line.3.horizontal"
        case .back: "arrow.uturn.backward"
        case .home: "house"
        case .exit: "xmark"
        case .info: "info.circle"
        case .guide: "list.bullet.rectangle"
        case .settings: "gearshape"
        case .input: "rectangle.on.rectangle"
        case .play: "play.fill"
        case .pause: "pause.fill"
        case .playPause: "playpause.fill"
        case .stop: "stop.fill"
        case .record: "record.circle"
        case .next: "forward.end.fill"
        case .previous: "backward.end.fill"
        case .rewind: "backward.fill"
        case .fastForward: "forward.fill"
        case .digit: nil
        case .red, .green, .yellow, .blue: "circle.fill"
        case .temperatureUp: "thermometer.sun"
        case .temperatureDown: "thermometer.snowflake"
        case .mode: "dial.medium"
        case .fanSpeed: "fan"
        case .swing: "arrow.up.and.down.and.sparkles"
        case .timer: "timer"
        case .brightnessUp: "sun.max"
        case .brightnessDown: "sun.min"
        case .subtitles: "captions.bubble"
        case .audioTrack: "waveform"
        case .other: nil
        }
    }

    public var isDigit: Bool {
        if case .digit = self { return true }
        return false
    }

    private static let lookup: [String: ButtonRole] = {
        var map: [String: ButtonRole] = [:]
        func add(_ role: ButtonRole, _ names: String...) { for n in names { map[n] = role } }
        add(.power, "power", "pwr", "onoff", "powertoggle", "standby", "power1", "powerbutton", "keypower", "toggle")
        add(.powerOn, "on", "poweron", "pwron", "discreteon")
        add(.powerOff, "off", "poweroff", "pwroff", "discreteoff")
        add(.volumeUp, "volup", "volumeup", "volplus", "vup", "keyvolumeup", "volumeplus")
        add(.volumeDown, "voldn", "voldown", "volumedown", "volminus", "vdn", "vdown", "keyvolumedown", "volumeminus", "voldwn")
        add(.mute, "mute", "keymute", "muting", "audiomute")
        add(.channelUp, "chnext", "chup", "channelup", "chplus", "progup", "prup", "pgup", "programup", "keychannelup", "pageup")
        add(.channelDown, "chprev", "chdn", "chdown", "channeldown", "chminus", "progdown", "prdown", "pgdn", "programdown", "keychanneldown", "pagedown")
        add(.up, "up", "keyup", "cursorup", "arrowup", "navup", "dpadup")
        add(.down, "down", "keydown", "cursordown", "arrowdown", "navdown", "dpaddown")
        add(.left, "left", "keyleft", "cursorleft", "arrowleft", "navleft", "dpadleft")
        add(.right, "right", "keyright", "cursorright", "arrowright", "navright", "dpadright")
        add(.ok, "ok", "enter", "select", "keyok", "keyenter", "keyselect", "confirm", "center")
        add(.menu, "menu", "keymenu", "mainmenu", "setupmenu", "popupmenu", "topmenu")
        add(.back, "back", "return", "keyback", "previouschannel", "prevch", "last", "keyreturn")
        add(.home, "home", "keyhome", "smarthub", "smart")
        add(.exit, "exit", "keyexit", "close", "cancel")
        add(.info, "info", "display", "keyinfo", "osd", "status")
        add(.guide, "guide", "epg", "keyepg", "tvguide")
        add(.settings, "settings", "setup", "options", "tools", "keysetup")
        add(.input, "input", "source", "inputs", "keysource", "src", "av", "tvav", "inputselect", "hdmi", "keyinput")
        add(.play, "play", "keyplay")
        add(.pause, "pause", "keypause")
        add(.playPause, "playpause", "playstop", "keyplaypause")
        add(.stop, "stop", "keystop")
        add(.record, "rec", "record", "keyrecord")
        add(.next, "next", "skipnext", "keynext", "skip", "skipfwd", "nexttrack", "nextchapter")
        add(.previous, "prev", "previous", "skipprev", "keyprevious", "skipback", "prevtrack", "previoustrack")
        add(.rewind, "rew", "rewind", "keyrewind", "fastback", "rwd")
        add(.fastForward, "ff", "fastfo", "fastforward", "fwd", "keyfastforward", "ffwd", "forward")
        add(.red, "red", "keyred", "a")
        add(.green, "green", "keygreen", "b")
        add(.yellow, "yellow", "keyyellow", "c")
        add(.blue, "blue", "keyblue", "d")
        add(.temperatureUp, "tempup", "temperatureup", "tempplus", "warmer", "heathi")
        add(.temperatureDown, "tempdown", "tempdn", "temperaturedown", "tempminus", "cooler", "coollo")
        add(.mode, "mode", "keymode")
        add(.fanSpeed, "speed", "fanspeed", "fan", "speedup", "wind")
        add(.swing, "swing", "oscillate", "osc", "rotate", "oscillation")
        add(.timer, "timer", "sleep", "keysleep", "sleeptimer")
        add(.brightnessUp, "brightnessup", "brightup", "brighter", "dimup", "lightup")
        add(.brightnessDown, "brightnessdown", "brightdown", "dimmer", "dimdown", "dim", "lightdown")
        add(.subtitles, "subtitle", "subtitles", "cc", "subt", "keysubtitle")
        add(.audioTrack, "audio", "language", "lang", "keyaudio")
        let digitNames = ["zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine"]
        for digit in 0...9 {
            add(.digit(digit), "\(digit)", "key\(digit)", "num\(digit)", "digit\(digit)", "btn\(digit)", digitNames[digit])
        }
        return map
    }()

    private static func fuzzy(key: String, compact: String) -> ButtonRole {
        let words = Set(key.split(separator: " ").map(String.init))
        func has(_ w: String) -> Bool { words.contains(w) }
        if has("power") || has("pwr") {
            if has("on") { return .powerOn }
            if has("off") { return .powerOff }
            return .power
        }
        if has("vol") || has("volume") {
            if has("up") || has("plus") { return .volumeUp }
            if has("dn") || has("down") || has("minus") { return .volumeDown }
        }
        if has("ch") || has("channel") || has("prog") {
            if has("up") || has("next") { return .channelUp }
            if has("down") || has("dn") || has("prev") { return .channelDown }
        }
        if has("mute") { return .mute }
        if has("temp") || has("temperature") {
            if has("up") { return .temperatureUp }
            if has("down") || has("dn") { return .temperatureDown }
        }
        if has("bright") || has("brightness") {
            if has("up") { return .brightnessUp }
            if has("down") || has("dn") { return .brightnessDown }
        }
        return .other
    }
}
