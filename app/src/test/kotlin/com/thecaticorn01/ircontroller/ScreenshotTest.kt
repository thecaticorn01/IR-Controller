package com.thecaticorn01.ircontroller

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.thecaticorn01.ircontroller.data.ThemeMode
import com.thecaticorn01.ircontroller.ui.App
import com.thecaticorn01.ircontroller.ui.theme.IRTheme
import com.thecaticorn01.ircontroller.core.database.DatabaseIndexer
import com.thecaticorn01.ircontroller.core.database.DatabaseSource
import com.thecaticorn01.ircontroller.core.database.RemoteImport
import com.thecaticorn01.ircontroller.core.model.DeviceCategory
import com.thecaticorn01.ircontroller.core.model.IRButton
import com.thecaticorn01.ircontroller.core.model.IRRemote
import com.thecaticorn01.ircontroller.core.model.IRSignal
import com.thecaticorn01.ircontroller.core.model.ParsedSignal
import com.thecaticorn01.ircontroller.core.model.RawSignal
import com.thecaticorn01.ircontroller.core.model.RemoteOrigin
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Starts the app without touching the network. */
class TestApplication : IRApplication() {
    override val refreshDatabasesOnLaunch: Boolean = false
}

/**
 * Renders the real app (navigation, screens, theme) with demo data and saves
 * screenshots to `app/screenshots/`. Doubles as a smoke test of every screen.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = TestApplication::class, qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val container get() = ApplicationProvider.getApplicationContext<IRApplication>().container

    @Before fun seed() = runBlocking {
        container.library.awaitLoaded()
        DemoData.remotes().forEach { container.library.add(it) }
        container.database.installForTesting(DatabaseSource.flipperIRDB.id, DemoData.flipperEntries(), DemoData.previews())
        container.database.installForTesting(DatabaseSource.irdb.id, DemoData.irdbEntries())
    }

    private fun launch(theme: ThemeMode = ThemeMode.LIGHT) {
        compose.setContent {
            IRTheme(themeMode = theme, dynamicColor = false) { App(incoming = emptyFlow()) }
        }
        compose.waitForIdle()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("screenshots/$name.png")
    }

    @Test fun library() {
        launch()
        compose.onNodeWithText("Living Room TV").assertExists()
        capture("library")
    }

    @Test fun remoteKeypad() {
        launch()
        compose.onNodeWithText("Living Room TV").performClick()
        compose.onNodeWithText("VOL").assertExists()
        capture("remote")
    }

    @Test fun remoteKeypadDark() {
        launch(ThemeMode.DARK)
        compose.onNodeWithText("Living Room TV").performClick()
        capture("remote-dark")
    }

    @Test fun remoteButtonList() {
        launch()
        compose.onNodeWithText("Bedroom Air Conditioner").performClick()
        compose.onNodeWithText("Buttons").performClick()
        compose.onNodeWithText("Temp Up").assertExists()
        capture("remote-list")
        compose.onNodeWithText("Remote").performClick()
    }

    @Test fun browse() {
        launch()
        compose.onNodeWithText("Browse").performClick()
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput("samsung")
        // The search is debounced on a real dispatcher, so wait for the filtered list.
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Daikin").fetchSemanticsNodes().isEmpty() }
        compose.onAllNodesWithText("BN59-01315J").onFirst().assertExists()
        capture("browse")
    }

    @Test fun preview() {
        launch()
        compose.onNodeWithText("Browse").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("BN59-01315J").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("BN59-01315J").onFirst().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Try the buttons").fetchSemanticsNodes().isNotEmpty() }
        // The demo library already holds this remote, so the preview offers to open it.
        compose.onNodeWithText("Open in My Remotes").assertExists()
        capture("preview")
    }

    @Test fun learn() {
        launch()
        compose.onNodeWithText("Learn").performClick()
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput("Desk Fan")
        capture("learn-setup")
        compose.onNodeWithText("Start learning").performScrollTo().performClick()
        compose.onNodeWithText("Button 1 of ", substring = true).assertExists()
        assertTrue(compose.onAllNodesWithText("Desk Fan").fetchSemanticsNodes().isNotEmpty())
        capture("learn")
    }

    @Test fun settings() {
        launch()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("No IR blaster").assertExists()
        capture("settings")
    }

    @Test fun enterCode() {
        launch()
        compose.onNodeWithText("Living Room TV").performClick()
        compose.onNode(hasContentDescription("Add button")).performClick()
        compose.onNodeWithText("Enter a code").performClick()
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput("Netflix")
        compose.onAllNodesWithText("Protocol").onFirst().assertExists()
        capture("button-editor")
    }

    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    @Test fun tabletLibrary() {
        launch()
        compose.onAllNodesWithText("Living Room TV").onFirst().assertExists()
        capture("tablet-library")
    }

    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    @Test fun tabletBrowse() {
        launch()
        compose.onNodeWithText("Browse").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("BN59-01315J").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("BN59-01315J").onFirst().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Try the buttons").fetchSemanticsNodes().isNotEmpty() }
        capture("tablet-browse")
    }

    @Test fun addingFromPreviewOpensTheRemote() {
        launch()
        compose.onNodeWithText("Browse").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("AA59-00600A").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("AA59-00600A").onFirst().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Add to My Remotes").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Add to My Remotes").performClick()
        compose.waitForIdle()
        assertTrue(container.library.remotes.value.any { it.origin?.path == "TVs/Samsung/Samsung_AA59-00600A.ir" })
        compose.onNodeWithText("VOL").assertExists()
    }
}

/** Remotes and database entries used by the screenshot tests. */
object DemoData {
    private fun samsung(name: String, command: Long) = IRButton(name = name, signal = IRSignal.Parsed(ParsedSignal("Samsung32", 0x07, command)))
    private fun nec(name: String, command: Long) = IRButton(name = name, signal = IRSignal.Parsed(ParsedSignal("NEC", 0x04, command)))

    private val tvButtons = listOf(
        samsung("Power", 0x02), samsung("Source", 0x01), samsung("Mute", 0x0F),
        samsung("Vol_up", 0x07), samsung("Vol_dn", 0x0B), samsung("Ch_next", 0x12), samsung("Ch_prev", 0x10),
        samsung("Up", 0x60), samsung("Down", 0x61), samsung("Left", 0x65), samsung("Right", 0x62), samsung("Ok", 0x68),
        samsung("Back", 0x58), samsung("Home", 0x79), samsung("Menu", 0x1A), samsung("Info", 0x1F), samsung("Guide", 0x4F),
        samsung("Rewind", 0x45), samsung("Play", 0x47), samsung("Pause", 0x4A), samsung("Stop", 0x46), samsung("Fast_fo", 0x48),
    ) + (1..9).map { samsung("$it", it.toLong() + 3) } + samsung("0", 0x11) + listOf(
        samsung("Red", 0x6C), samsung("Green", 0x14), samsung("Yellow", 0x15), samsung("Blue", 0x16),
        samsung("Netflix", 0xF3), samsung("Prime Video", 0xF4), samsung("Subtitles", 0x25), samsung("Sleep", 0x03),
    )

    fun remotes(): List<IRRemote> = listOf(
        IRRemote(
            name = "Living Room TV", brand = "Samsung", model = "BN59-01315J", category = DeviceCategory.TV,
            buttons = tvButtons, isFavorite = true,
            origin = RemoteOrigin(DatabaseSource.flipperIRDB.id, "Flipper-IRDB", "TVs/Samsung/Samsung_BN59-01315J.ir"),
        ),
        IRRemote(
            name = "Bedroom Air Conditioner", brand = "Daikin", category = DeviceCategory.AIR_CONDITIONER,
            buttons = listOf(
                nec("Power", 0x01), nec("Temp Up", 0x02), nec("Temp Down", 0x03), nec("Mode", 0x04),
                nec("Fan Speed", 0x05), nec("Swing", 0x06), nec("Timer", 0x07),
                IRButton(name = "Turbo", signal = IRSignal.Raw(RawSignal(timings = listOf(3400, 1700, 450, 1300, 450, 450, 450, 1300, 450, 40000)))),
            ),
        ),
        IRRemote(
            name = "Soundbar", brand = "Sony", model = "HT-S100F", category = DeviceCategory.SOUNDBAR, isFavorite = true,
            buttons = listOf("Power" to 0x15L, "Vol_up" to 0x12L, "Vol_dn" to 0x13L, "Mute" to 0x14L, "Input" to 0x69L).map { (name, cmd) ->
                IRButton(name = name, signal = IRSignal.Parsed(ParsedSignal("SIRC15", 0x10, cmd)))
            },
        ),
        IRRemote(name = "LED Strip", category = DeviceCategory.LIGHTING, buttons = listOf(nec("On", 0x40), nec("Off", 0x41), nec("Brightness Up", 0x42))),
    )

    fun flipperEntries() = DatabaseIndexer.entries(
        listOf(
            "TVs/Samsung/Samsung_BN59-01315J.ir", "TVs/Samsung/Samsung_AA59-00600A.ir", "TVs/Samsung/Samsung_UE40.ir",
            "TVs/Sony/Sony_RMT_TB400U.ir", "TVs/LG/LG_AKB75095307.ir", "ACs/Daikin/Daikin_ARC433A1.ir",
            "SoundBars/Samsung/Samsung_HW-Q60R.ir", "Projectors/Epson/Epson_EH-TW650.ir",
            "_Converted_/Pronto/S/Samsung/BN59-00940A.ir", "LED_Lighting/Generic/RGB_strip.ir",
        ),
        DatabaseSource.flipperIRDB,
    )

    fun irdbEntries() = DatabaseIndexer.entries(
        listOf("codes/Samsung/TV/7,7.csv", "codes/Samsung/DVD Player/32,-1.csv", "codes/Sony/TV/1,-1.csv"),
        DatabaseSource.irdb,
    )

    fun previews(): Map<String, RemoteImport> =
        flipperEntries().filter { it.model == "BN59-01315J" || it.model == "AA59-00600A" }.associate { entry ->
            val remote = IRRemote(
                name = entry.title, brand = entry.brand, model = entry.model, category = entry.category, buttons = tvButtons,
                origin = RemoteOrigin(DatabaseSource.flipperIRDB.id, "Flipper-IRDB", entry.path),
            )
            entry.id to RemoteImport(remote, emptyList())
        }
}
