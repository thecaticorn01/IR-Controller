package com.thecaticorn01.ircontroller.core

import com.thecaticorn01.ircontroller.core.database.DatabaseFormat
import com.thecaticorn01.ircontroller.core.database.DatabaseIndexer
import com.thecaticorn01.ircontroller.core.database.DatabaseQuery
import com.thecaticorn01.ircontroller.core.database.DatabaseSearch
import com.thecaticorn01.ircontroller.core.database.DatabaseSource
import com.thecaticorn01.ircontroller.core.database.NaturalOrder
import com.thecaticorn01.ircontroller.core.database.RemoteImport
import com.thecaticorn01.ircontroller.core.formats.FlipperIRFile
import com.thecaticorn01.ircontroller.core.formats.IRCodeImporter
import com.thecaticorn01.ircontroller.core.formats.IRDBCSV
import com.thecaticorn01.ircontroller.core.formats.IRFormatException
import com.thecaticorn01.ircontroller.core.library.LibraryArchive
import com.thecaticorn01.ircontroller.core.model.DeviceCategory
import com.thecaticorn01.ircontroller.core.model.IRButton
import com.thecaticorn01.ircontroller.core.model.IRRemote
import com.thecaticorn01.ircontroller.core.model.IRSignal
import com.thecaticorn01.ircontroller.core.model.ParsedSignal
import com.thecaticorn01.ircontroller.core.model.RawSignal
import com.thecaticorn01.ircontroller.core.model.searchKey
import com.thecaticorn01.ircontroller.core.protocols.IRProtocolDecoder
import com.thecaticorn01.ircontroller.core.protocols.IRProtocolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.math.abs

class FlipperFileTest {
    companion object {
        val sample = listOf(
            "Filetype: IR signals file", "Version: 1", "# ", "name: Power", "type: parsed", "protocol: SIRC",
            "address: 01 00 00 00", "command: 15 00 00 00", "# ", "name: Vol_up", "type: raw", "frequency: 38000",
            "duty_cycle: 0.330000", "data: 9024 4512 579 552 579 1683", "#", "name: Broken", "type: parsed",
            "protocol: NEC", "address: zz", "command: 01 00 00 00",
        ).joinToString("\r\n")
    }

    @Test fun parsesParsedAndRawSignalsWithCRLF() {
        val result = FlipperIRFile.parse(sample)
        assertEquals(listOf("Power", "Vol_up"), result.buttons.map { it.name })
        assertEquals(IRSignal.Parsed(ParsedSignal("SIRC", 1, 0x15)), result.buttons[0].signal)
        assertEquals(IRSignal.Raw(RawSignal(38_000, 0.33, listOf(9024, 4512, 579, 552, 579, 1683))), result.buttons[1].signal)
        assertEquals(listOf("Broken"), result.skipped.map { it.name })
    }

    @Test fun roundTrips() {
        val original = FlipperIRFile.parse(sample).buttons
        val reparsed = FlipperIRFile.parse(FlipperIRFile.serialize(original)).buttons
        assertEquals(original.map { it.name }, reparsed.map { it.name })
        assertEquals(original.map { it.signal }, reparsed.map { it.signal })
    }

    @Test fun exportsNonFlipperProtocolsAsRaw() {
        val button = IRButton(name = "Power", signal = IRSignal.Parsed(ParsedSignal("JVC", 3, 23)))
        val text = FlipperIRFile.serialize(listOf(button))
        assertTrue(text.contains("type: raw"))
        assertTrue(FlipperIRFile.parse(text).buttons.first().signal.isRaw)
    }

    @Test fun rejectsNonIRText() {
        assertThrows(IRFormatException::class.java) { FlipperIRFile.parse("hello world") }
    }

    @Test fun littleEndianAddresses() {
        assertEquals(7L, FlipperIRFile.littleEndianHex("07 00 00 00"))
        assertEquals(0x7F80L, FlipperIRFile.littleEndianHex("80 7F 00 00"))
        assertEquals("80 7F 00 00", FlipperIRFile.littleEndianHexString(0x7F80))
        assertEquals("FF FF FF FF", FlipperIRFile.littleEndianHexString(0xFFFF_FFFFL))
    }
}

class IRDBAndCodeImportTest {

    @Test fun parsesIRDBCSV() {
        val csv = """
            functionname,protocol,device,subdevice,function
            KEY_POWER,NEC1,64,-1,18
            KEY_VOLUMEUP,Sony12,1,-1,18
            Odd, name,NECx2,7,7,2
            Magic,XMP-1,1,2,3
        """.trimIndent()
        val result = IRDBCSV.parse(csv)
        assertEquals(listOf("Power", "Volume Up", "Odd, name"), result.buttons.map { it.name })
        assertEquals(IRSignal.Parsed(ParsedSignal("NEC", 64, 18)), result.buttons[0].signal)
        assertEquals(IRSignal.Parsed(ParsedSignal("NECx", 7, 2, subdevice = 7)), result.buttons[2].signal)
        assertEquals(setOf("XMP-1"), result.unsupportedProtocols)
    }

    @Test fun parsesProntoHex() {
        val (raw, format) = IRCodeImporter.parse("0000 006D 0002 0000 0157 00AC 0015 0689")
        assertEquals(IRCodeImporter.Format.PRONTO, format)
        assertTrue(abs(raw.frequency - 38_029) < 10)
        assertEquals(4, raw.timings.size)
        assertTrue(abs(raw.timings[0] - 9_020) < 30)
    }

    @Test fun parsesSendIR() {
        val (raw, format) = IRCodeImporter.parse("sendir,1:1,1,40000,1,1,96,24,48,24,24,24")
        assertEquals(IRCodeImporter.Format.SENDIR, format)
        assertEquals(40_000, raw.frequency)
        assertEquals(listOf(2_400, 600, 1_200, 600, 600, 600), raw.timings)
    }

    @Test fun parsesSignedTimings() {
        val (raw, format) = IRCodeImporter.parse("+9000 -4500 +560 -560")
        assertEquals(IRCodeImporter.Format.TIMINGS, format)
        assertEquals(listOf(9000, 4500, 560, 560), raw.timings)
    }

    @Test fun prontoRoundTrip() {
        val original = IRProtocolRegistry.encode(ParsedSignal("NEC", 4, 8))
        val (parsed, _) = IRCodeImporter.parse(IRCodeImporter.prontoHex(original))
        assertEquals(ParsedSignal("NEC", 4, 8), IRProtocolDecoder.decode(parsed))
    }
}

class DatabaseTest {
    companion object {
        val flipperPaths = listOf(
            "README.md",
            "TVs/Samsung/Samsung_AA59-00600A.ir",
            "TVs/Sony/Sony_RMT_TB400U.ir",
            "ACs/Daikin/Daikin_ARC433A1.ir",
            "Audio_and_Video_Receivers/Panasonic/Panasonic_N2QAYB000210.ir",
            "_Converted_/Pronto/S/Sony/EVS5000_VTR_2.ir",
            "_Converted_/CSV/Y/Yamaha/Unknown_RAV207/122_-1.ir",
            "LED_Lighting/Generic/RGB_strip.ir",
            ".github/workflow.ir",
        )
    }

    @Test fun indexesFlipperLayout() {
        val entries = DatabaseIndexer.entries(flipperPaths, DatabaseSource.flipperIRDB)
        assertEquals(7, entries.size)
        val samsung = entries.first { it.brand == "Samsung" }
        assertEquals("AA59-00600A", samsung.model)
        assertEquals(DeviceCategory.TV, samsung.category)
        assertTrue(samsung.isCurated)
        assertEquals(DeviceCategory.AIR_CONDITIONER, entries.first { it.brand == "Daikin" }.category)
        assertEquals(DeviceCategory.AUDIO, entries.first { it.brand == "Panasonic" }.category)
        val converted = entries.first { it.collection == "CSV" }
        assertEquals("Yamaha", converted.brand)
        assertFalse(converted.isCurated)
        assertEquals(DeviceCategory.LIGHTING, entries.first { it.brand == "Generic" }.category)
    }

    @Test fun indexesIRDBLayout() {
        val entries = DatabaseIndexer.entries(listOf("codes/Sony/TV/1,-1.csv", "codes/3M/Video Projector/134,-1.csv", "codes/index"), DatabaseSource.irdb)
        assertEquals(2, entries.size)
        assertEquals(DeviceCategory.PROJECTOR, entries.first { it.brand == "3M" }.category)
        assertEquals(DeviceCategory.TV, entries.first { it.brand == "Sony" }.category)
    }

    @Test fun searchRanksBrandMatchesFirst() {
        val entries = DatabaseIndexer.entries(flipperPaths, DatabaseSource.flipperIRDB)
        val results = DatabaseSearch.search(entries, DatabaseQuery(text = "sony"))
        assertEquals(2, results.size)
        assertTrue(results.first().isCurated)
        assertEquals("Samsung", DatabaseSearch.search(entries, DatabaseQuery(text = "aa59 00600")).first().brand)
        assertEquals("Samsung", DatabaseSearch.search(entries, DatabaseQuery(text = "AA5900600A")).first().brand)
        assertTrue(DatabaseSearch.search(entries, DatabaseQuery(text = "nonexistent")).isEmpty())
    }

    @Test fun filtersAndFacets() {
        val entries = DatabaseIndexer.entries(flipperPaths, DatabaseSource.flipperIRDB)
        val tvs = DatabaseSearch.search(entries, DatabaseQuery(category = DeviceCategory.TV))
        assertEquals(setOf("Samsung", "Sony"), tvs.map { it.brand }.toSet())
        assertEquals(1, DatabaseSearch.search(entries, DatabaseQuery(brand = "sony", curatedOnly = true)).size)
        val brands = DatabaseSearch.brandFacets(entries, DatabaseQuery(category = DeviceCategory.TV))
        assertEquals(listOf("Samsung", "Sony"), brands.map { it.value })
        val categories = DatabaseSearch.categoryFacets(entries, DatabaseQuery(brand = "Sony"))
        assertTrue(categories.any { it.value == DeviceCategory.TV && it.count == 1 })
    }

    @Test fun rawURLsAreEscaped() {
        assertEquals(
            "https://raw.githubusercontent.com/probonopd/irdb/master/codes/3M/Video%20Projector/134,-1.csv",
            DatabaseSource.irdb.rawUrl("codes/3M/Video Projector/134,-1.csv"),
        )
    }

    @Test fun parsesRepositoryNames() {
        assertEquals("Lucaslhm" to "Flipper-IRDB", DatabaseSource.parseRepository("https://github.com/Lucaslhm/Flipper-IRDB"))
        assertEquals("a" to "b", DatabaseSource.parseRepository("github.com/a/b.git"))
        assertEquals("a" to "b", DatabaseSource.parseRepository("a/b"))
        assertEquals(null, DatabaseSource.parseRepository("nonsense"))
    }

    @Test fun remoteImportFromIRDB() {
        val entry = DatabaseIndexer.entries(listOf("codes/Sony/TV/1,-1.csv"), DatabaseSource.irdb)[0]
        val result = RemoteImport.make(
            "functionname,protocol,device,subdevice,function\nPOWER,Sony12,1,-1,21\n",
            DatabaseFormat.IRDB_CSV, entry, DatabaseSource.irdb,
        )
        assertEquals("Sony", result.remote.brand)
        assertEquals("Power", result.remote.powerButton?.name)
        assertEquals(DatabaseSource.irdb.id, result.remote.origin?.sourceId)
    }

    @Test fun naturalOrder() {
        assertEquals(listOf("Model 2", "model 9", "Model 10"), listOf("Model 10", "model 9", "Model 2").sortedWith(NaturalOrder))
    }

    @Test fun searchKeyFoldsDiacriticsAndPunctuation() {
        assertEquals("lowe tv 42", "Löwe — TV_42!".searchKey)
    }
}

class LibraryArchiveTest {

    @Test fun roundTrip() {
        val remote = IRRemote(
            name = "Living Room TV", brand = "Sony", category = DeviceCategory.TV,
            buttons = listOf(
                IRButton(name = "Power", signal = IRSignal.Parsed(ParsedSignal("SIRC", 1, 21))),
                IRButton(name = "Learned", signal = IRSignal.Raw(RawSignal(timings = listOf(9000, 4500, 560)))),
            ),
        )
        val loaded = LibraryArchive.decode(LibraryArchive(remotes = listOf(remote)).encode())
        assertEquals(listOf(remote), loaded.remotes)
    }

    /** Reads a library file in the documented format: enum-style signals, ISO 8601 dates, optionals omitted. */
    @Test fun readsLibraryFormat() {
        val json = """
            {
              "remotes" : [
                {
                  "brand" : "Samsung",
                  "buttons" : [
                    {
                      "id" : "6B1C7A43-5D0B-4E7E-9E2A-2A1D3C4B5E6F",
                      "name" : "Power",
                      "signal" : { "parsed" : { "_0" : { "address" : 7, "command" : 2, "protocolName" : "Samsung32" } } }
                    },
                    {
                      "id" : "7C2D8B54-6E1C-4F8F-AF3B-3B2E4D5C6F70",
                      "name" : "Learned",
                      "signal" : { "raw" : { "_0" : { "dutyCycle" : 0.33, "frequency" : 38000, "timings" : [ 9000, 4500, 560 ] } } }
                    }
                  ],
                  "category" : "airConditioner",
                  "createdAt" : "2026-01-02T03:04:05Z",
                  "id" : "1A2B3C4D-0000-4000-8000-123456789ABC",
                  "isFavorite" : true,
                  "model" : "UE40",
                  "modifiedAt" : "2026-01-02T03:04:05Z",
                  "name" : "Bedroom",
                  "notes" : "",
                  "origin" : { "path" : "TVs/Samsung/x.ir", "sourceID" : "flipper-irdb", "sourceName" : "Flipper-IRDB", "url" : "https://github.com/x" }
                }
              ],
              "version" : 1
            }
        """.trimIndent()
        val remote = LibraryArchive.decode(json).remotes.single()
        assertEquals("Bedroom", remote.name)
        assertEquals(DeviceCategory.AIR_CONDITIONER, remote.category)
        assertTrue(remote.isFavorite)
        assertEquals(Instant.parse("2026-01-02T03:04:05Z"), remote.createdAt)
        assertEquals("flipper-irdb", remote.origin?.sourceId)
        assertEquals(IRSignal.Parsed(ParsedSignal("Samsung32", 7, 2)), remote.buttons[0].signal)
        assertEquals(IRSignal.Raw(RawSignal(38_000, 0.33, listOf(9000, 4500, 560))), remote.buttons[1].signal)

        // Written back in the same shape: optionals omitted, enum wrapped in `_0`.
        val written = LibraryArchive(remotes = listOf(remote)).encode(pretty = false)
        assertTrue(written, written.contains("\"signal\":{\"parsed\":{\"_0\":{\"protocolName\":\"Samsung32\",\"address\":7,\"command\":2}}}"))
        assertTrue(written, written.contains("\"createdAt\":\"2026-01-02T03:04:05Z\""))
        assertTrue(written, written.contains("\"sourceID\":\"flipper-irdb\""))
        assertFalse(written, written.contains("null"))
    }
}
