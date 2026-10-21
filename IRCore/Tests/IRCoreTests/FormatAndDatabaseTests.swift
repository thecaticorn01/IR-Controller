import Foundation
import Testing
@testable import IRCore

@Suite struct FlipperFileTests {

    static let sample = """
    Filetype: IR signals file\r
    Version: 1\r
    # \r
    name: Power\r
    type: parsed\r
    protocol: SIRC\r
    address: 01 00 00 00\r
    command: 15 00 00 00\r
    # \r
    name: Vol_up\r
    type: raw\r
    frequency: 38000\r
    duty_cycle: 0.330000\r
    data: 9024 4512 579 552 579 1683\r
    #\r
    name: Broken\r
    type: parsed\r
    protocol: NEC\r
    address: zz\r
    command: 01 00 00 00\r
    """

    @Test func parsesParsedAndRawSignalsWithCRLF() throws {
        let result = try FlipperIRFile.parse(Self.sample)
        #expect(result.buttons.map(\.name) == ["Power", "Vol_up"])
        #expect(result.buttons[0].signal == .parsed(ParsedSignal(protocolName: "SIRC", address: 1, command: 0x15)))
        #expect(result.buttons[1].signal == .raw(RawSignal(frequency: 38_000, dutyCycle: 0.33, timings: [9024, 4512, 579, 552, 579, 1683])))
        #expect(result.skipped.map(\.name) == ["Broken"])
    }

    @Test func roundTrips() throws {
        let original = try FlipperIRFile.parse(Self.sample).buttons
        let reparsed = try FlipperIRFile.parse(FlipperIRFile.serialize(original)).buttons
        #expect(reparsed.map(\.name) == original.map(\.name))
        #expect(reparsed.map(\.signal) == original.map(\.signal))
    }

    @Test func exportsNonFlipperProtocolsAsRaw() throws {
        let button = IRButton(name: "Power", signal: .parsed(ParsedSignal(protocolName: "JVC", address: 3, command: 23)))
        let text = FlipperIRFile.serialize([button])
        #expect(text.contains("type: raw"))
        let parsed = try FlipperIRFile.parse(text)
        #expect(parsed.buttons.first?.signal.isRaw == true)
    }

    @Test func rejectsNonIRText() {
        #expect(throws: IRFormatError.self) { try FlipperIRFile.parse("hello world") }
    }

    @Test func littleEndianAddresses() {
        #expect(FlipperIRFile.littleEndianHex("07 00 00 00") == 7)
        #expect(FlipperIRFile.littleEndianHex("80 7F 00 00") == 0x7F80)
        #expect(FlipperIRFile.littleEndianHexString(0x7F80) == "80 7F 00 00")
    }
}

@Suite struct IRDBAndCodeImportTests {

    @Test func parsesIRDBCSV() {
        let csv = """
        functionname,protocol,device,subdevice,function
        KEY_POWER,NEC1,64,-1,18
        KEY_VOLUMEUP,Sony12,1,-1,18
        Odd, name,NECx2,7,7,2
        Magic,XMP-1,1,2,3
        """
        let result = IRDBCSV.parse(csv)
        #expect(result.buttons.map(\.name) == ["Power", "Volume Up", "Odd, name"])
        #expect(result.buttons[0].signal == .parsed(ParsedSignal(protocolName: "NEC", address: 64, command: 18)))
        #expect(result.buttons[2].signal == .parsed(ParsedSignal(protocolName: "NECx", address: 7, command: 2, subdevice: 7)))
        #expect(result.unsupportedProtocols == ["XMP-1"])
    }

    @Test func parsesProntoHex() throws {
        let pronto = "0000 006D 0002 0000 0157 00AC 0015 0689"
        let (raw, format) = try IRCodeImporter.parse(pronto)
        #expect(format == .pronto)
        #expect(abs(raw.frequency - 38_029) < 10)
        #expect(raw.timings.count == 4)
        #expect(abs(raw.timings[0] - 9_020) < 30)
    }

    @Test func parsesSendIR() throws {
        let (raw, format) = try IRCodeImporter.parse("sendir,1:1,1,40000,1,1,96,24,48,24,24,24")
        #expect(format == .sendir)
        #expect(raw.frequency == 40_000)
        #expect(raw.timings == [2_400, 600, 1_200, 600, 600, 600])
    }

    @Test func parsesSignedTimings() throws {
        let (raw, format) = try IRCodeImporter.parse("+9000 -4500 +560 -560")
        #expect(format == .timings)
        #expect(raw.timings == [9000, 4500, 560, 560])
    }

    @Test func prontoRoundTrip() throws {
        let original = try IRProtocolRegistry.encode(ParsedSignal(protocolName: "NEC", address: 4, command: 8))
        let (parsed, _) = try IRCodeImporter.parse(IRCodeImporter.prontoHex(for: original))
        #expect(IRProtocolDecoder.decode(parsed) == ParsedSignal(protocolName: "NEC", address: 4, command: 8))
    }
}

@Suite struct DatabaseTests {

    static let flipperPaths = [
        "README.md",
        "TVs/Samsung/Samsung_AA59-00600A.ir",
        "TVs/Sony/Sony_RMT_TB400U.ir",
        "ACs/Daikin/Daikin_ARC433A1.ir",
        "Audio_and_Video_Receivers/Panasonic/Panasonic_N2QAYB000210.ir",
        "_Converted_/Pronto/S/Sony/EVS5000_VTR_2.ir",
        "_Converted_/CSV/Y/Yamaha/Unknown_RAV207/122_-1.ir",
        "LED_Lighting/Generic/RGB_strip.ir",
        ".github/workflow.ir",
    ]

    @Test func indexesFlipperLayout() throws {
        let entries = DatabaseIndexer.entries(from: Self.flipperPaths, source: .flipperIRDB)
        #expect(entries.count == 7)
        let samsung = try #require(entries.first { $0.brand == "Samsung" })
        #expect(samsung.model == "AA59-00600A")
        #expect(samsung.category == .tv)
        #expect(samsung.isCurated)
        let daikin = try #require(entries.first { $0.brand == "Daikin" })
        #expect(daikin.category == .airConditioner)
        let receiver = try #require(entries.first { $0.brand == "Panasonic" })
        #expect(receiver.category == .audio)
        let converted = try #require(entries.first { $0.collection == "CSV" })
        #expect(converted.brand == "Yamaha")
        #expect(!converted.isCurated)
        #expect(entries.first { $0.brand == "Generic" }?.category == .lighting)
    }

    @Test func indexesIRDBLayout() throws {
        let entries = DatabaseIndexer.entries(from: ["codes/Sony/TV/1,-1.csv", "codes/3M/Video Projector/134,-1.csv", "codes/index"], source: .irdb)
        #expect(entries.count == 2)
        let projector = try #require(entries.first { $0.brand == "3M" })
        #expect(projector.category == .projector)
        #expect(entries.first { $0.brand == "Sony" }?.category == .tv)
    }

    @Test func searchRanksBrandMatchesFirst() {
        let entries = DatabaseIndexer.entries(from: Self.flipperPaths, source: .flipperIRDB)
        let results = DatabaseSearch.search(entries, query: DatabaseQuery(text: "sony"))
        #expect(results.count == 2)
        #expect(results.first?.isCurated == true)
        #expect(DatabaseSearch.search(entries, query: DatabaseQuery(text: "aa59 00600")).first?.brand == "Samsung")
        #expect(DatabaseSearch.search(entries, query: DatabaseQuery(text: "AA5900600A")).first?.brand == "Samsung")
        #expect(DatabaseSearch.search(entries, query: DatabaseQuery(text: "nonexistent")).isEmpty)
    }

    @Test func filtersAndFacets() {
        let entries = DatabaseIndexer.entries(from: Self.flipperPaths, source: .flipperIRDB)
        let tvs = DatabaseSearch.search(entries, query: DatabaseQuery(category: .tv))
        #expect(Set(tvs.map(\.brand)) == ["Samsung", "Sony"])
        #expect(DatabaseSearch.search(entries, query: DatabaseQuery(brand: "sony", curatedOnly: true)).count == 1)
        let brands = DatabaseSearch.brandFacets(entries, query: DatabaseQuery(category: .tv))
        #expect(brands.map(\.value) == ["Samsung", "Sony"])
        let categories = DatabaseSearch.categoryFacets(entries, query: DatabaseQuery(brand: "Sony"))
        #expect(categories.contains { $0.value == .tv && $0.count == 1 })
    }

    @Test func rawURLsAreEscaped() {
        let url = DatabaseSource.irdb.rawURL(for: "codes/3M/Video Projector/134,-1.csv")
        #expect(url?.absoluteString == "https://raw.githubusercontent.com/probonopd/irdb/master/codes/3M/Video%20Projector/134,-1.csv")
    }

    @Test func remoteImportFromIRDB() throws {
        let entry = DatabaseIndexer.entries(from: ["codes/Sony/TV/1,-1.csv"], source: .irdb)[0]
        let result = try RemoteImport.make(from: "functionname,protocol,device,subdevice,function\nPOWER,Sony12,1,-1,21\n", format: .irdbCSV, entry: entry, source: .irdb)
        #expect(result.remote.brand == "Sony")
        #expect(result.remote.powerButton?.name == "Power")
        #expect(result.remote.origin?.sourceID == DatabaseSource.irdb.id)
    }

    @Test func libraryArchiveRoundTrip() throws {
        let remote = IRRemote(name: "Living Room TV", brand: "Sony", category: .tv, buttons: [
            IRButton(name: "Power", signal: .parsed(ParsedSignal(protocolName: "SIRC", address: 1, command: 21))),
            IRButton(name: "Learned", signal: .raw(RawSignal(timings: [9000, 4500, 560]))),
        ])
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).appendingPathComponent("library.json")
        try LibraryArchive(remotes: [remote]).save(to: url)
        let loaded = try LibraryArchive.load(from: url)
        #expect(loaded.remotes.first?.buttons == remote.buttons)
        #expect(loaded.remotes.first?.name == remote.name)
    }
}

@Suite struct CategoryTests {
    @Test(arguments: [
        ("TVs", DeviceCategory.tv), ("ACs", .airConditioner), ("Projectors", .projector),
        ("SoundBars", .soundbar), ("Audio_and_Video_Receivers", .audio), ("Blu-Ray", .mediaPlayer),
        ("Cable_Boxes", .cableBox), ("Streaming_Devices", .streaming), ("LED_Lighting", .lighting),
        ("Fans", .fan), ("Heaters", .heater), ("Speakers", .speaker), ("Video Projector", .projector),
        ("DVD_Players", .mediaPlayer), ("TV_Tuner", .cableBox), ("Miscellaneous", .other),
    ])
    func mapsDatabaseFolders(_ input: (String, DeviceCategory)) {
        #expect(DeviceCategory(databaseName: input.0) == input.1)
    }
}
