import Foundation

/// A single button of a remote control.
public struct IRButton: Codable, Hashable, Identifiable, Sendable {
    public var id: UUID
    public var name: String
    public var signal: IRSignal

    public init(id: UUID = UUID(), name: String, signal: IRSignal) {
        self.id = id
        self.name = name
        self.signal = signal
    }

    /// The semantic role inferred from the button name.
    public var role: RemoteButtonRole { RemoteButtonRole(buttonName: name) }
}

/// Where an imported remote came from.
public struct RemoteOrigin: Codable, Hashable, Sendable {
    public var sourceID: String
    public var sourceName: String
    public var path: String
    public var url: URL?

    public init(sourceID: String, sourceName: String, path: String, url: URL? = nil) {
        self.sourceID = sourceID
        self.sourceName = sourceName
        self.path = path
        self.url = url
    }
}

/// A remote control in the user's library.
public struct IRRemote: Codable, Hashable, Identifiable, Sendable {
    public var id: UUID
    public var name: String
    public var brand: String
    public var model: String
    public var category: DeviceCategory
    public var buttons: [IRButton]
    public var isFavorite: Bool
    public var notes: String
    public var origin: RemoteOrigin?
    public var createdAt: Date
    public var modifiedAt: Date

    public init(
        id: UUID = UUID(),
        name: String,
        brand: String = "",
        model: String = "",
        category: DeviceCategory = .other,
        buttons: [IRButton] = [],
        isFavorite: Bool = false,
        notes: String = "",
        origin: RemoteOrigin? = nil,
        createdAt: Date = Date(),
        modifiedAt: Date = Date()
    ) {
        self.id = id
        self.name = name
        self.brand = brand
        self.model = model
        self.category = category
        self.buttons = buttons
        self.isFavorite = isFavorite
        self.notes = notes
        self.origin = origin
        self.createdAt = createdAt
        self.modifiedAt = modifiedAt
    }

    public func button(named name: String) -> IRButton? {
        let key = name.searchKey
        return buttons.first { $0.name.searchKey == key }
    }

    /// The best candidate for a power button, used by power scanning.
    public var powerButton: IRButton? {
        buttons.first { $0.role == .power } ?? buttons.first { $0.role == .powerOn }
    }
}

/// Broad device categories used for icons, filtering and learning templates.
public enum DeviceCategory: String, Codable, CaseIterable, Hashable, Sendable, Identifiable {
    case tv
    case projector
    case audio
    case soundbar
    case speaker
    case streaming
    case cableBox
    case mediaPlayer
    case airConditioner
    case fan
    case heater
    case lighting
    case camera
    case toy
    case other

    public var id: String { rawValue }

    public var displayName: String {
        switch self {
        case .tv: "TV"
        case .projector: "Projector"
        case .audio: "Audio Receiver"
        case .soundbar: "Soundbar"
        case .speaker: "Speaker"
        case .streaming: "Streaming Device"
        case .cableBox: "Cable / Satellite Box"
        case .mediaPlayer: "Disc / Media Player"
        case .airConditioner: "Air Conditioner"
        case .fan: "Fan"
        case .heater: "Heater"
        case .lighting: "Lighting"
        case .camera: "Camera"
        case .toy: "Toy"
        case .other: "Other"
        }
    }

    /// SF Symbol name for this category.
    public var symbolName: String {
        switch self {
        case .tv: "tv"
        case .projector: "videoprojector"
        case .audio: "hifireceiver"
        case .soundbar: "hifispeaker.and.homepod"
        case .speaker: "hifispeaker"
        case .streaming: "appletv"
        case .cableBox: "tv.and.mediabox"
        case .mediaPlayer: "opticaldiscdrive"
        case .airConditioner: "air.conditioner.horizontal"
        case .fan: "fan"
        case .heater: "heater.vertical"
        case .lighting: "lightbulb.led"
        case .camera: "camera"
        case .toy: "car"
        case .other: "av.remote"
        }
    }

    /// Maps a database folder or device-type name (e.g. "TVs", "Audio_and_Video_Receivers",
    /// "Video Projector") to a category.
    public init(databaseName: String) {
        let key = databaseName.searchKey
        func has(_ words: String...) -> Bool { words.contains { key.contains($0) } }
        if has("soundbar", "sound bar") { self = .soundbar }
        else if has("projector", "beamer") { self = .projector }
        else if has("receiver", "amplifier", "avr") { self = .audio }
        else if has("streaming", "apple tv", "appletv", "roku", "chromecast", "fire tv", "firetv", "media center", "htpc", "kodi") { self = .streaming }
        else if has("cable", "satellite", "set top", "settop", "dvb", "stb", "decoder", "freeview", "iptv", "tivo", "tv tuner", "tuner") { self = .cableBox }
        else if has("air conditioner", "aircon", "hvac", "climate", "acs") || key == "ac" || key.hasPrefix("ac ") { self = .airConditioner }
        else if has("fan", "purifier", "humidifier") { self = .fan }
        else if has("heater", "fireplace") { self = .heater }
        else if has("camera", "cctv") { self = .camera }
        else if has("tv", "television", "monitor", "display", "plasma", "screen") { self = .tv }
        else if has("blu ray", "bluray", "dvd", "vcr", "laserdisc", "disc player", "video", "multimedia", "media player") { self = .mediaPlayer }
        else if has("speaker", "subwoofer") { self = .speaker }
        else if has("audio", "hifi", "hi fi", "stereo", "cd player", "minidisc", "tape", "radio", "music") { self = .audio }
        else if has("led", "light", "lamp", "bulb") { self = .lighting }
        else if has("toy", "robot", "helicopter") { self = .toy }
        else { self = .other }
    }
}

extension String {
    /// Lowercased, diacritic-folded, punctuation-normalised form used for matching.
    public var searchKey: String {
        let folded = self.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: nil)
        var out = ""
        out.reserveCapacity(folded.count)
        var lastWasSpace = true
        for scalar in folded.unicodeScalars {
            if CharacterSet.alphanumerics.contains(scalar) {
                out.unicodeScalars.append(scalar)
                lastWasSpace = false
            } else if !lastWasSpace {
                out.append(" ")
                lastWasSpace = true
            }
        }
        if out.hasSuffix(" ") { out.removeLast() }
        return out
    }
}
