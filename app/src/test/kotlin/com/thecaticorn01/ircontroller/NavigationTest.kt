package com.thecaticorn01.ircontroller

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.thecaticorn01.ircontroller.data.ThemeMode
import com.thecaticorn01.ircontroller.ui.App
import com.thecaticorn01.ircontroller.ui.theme.IRTheme
import com.thecaticorn01.ircontroller.core.database.DatabaseSource
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Tapping the navigation bar, including re-selecting the current tab. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = TestApplication::class, qualifiers = "w411dp-h891dp-xxhdpi")
class NavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val container get() = ApplicationProvider.getApplicationContext<IRApplication>().container

    @Before fun seed() = runBlocking {
        container.library.awaitLoaded()
        DemoData.remotes().forEach { container.library.add(it) }
        container.database.installForTesting(DatabaseSource.flipperIRDB.id, DemoData.flipperEntries(), DemoData.previews())
        container.database.installForTesting(DatabaseSource.irdb.id, DemoData.irdbEntries())
        compose.setContent {
            IRTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) { App(incoming = emptyFlow()) }
        }
        compose.waitForIdle()
    }

    private fun tap(tab: String) {
        compose.onNodeWithText(tab).performClick()
        compose.waitForIdle()
    }

    /** Two taps on the same tab, the second one while the first switch is still animating. */
    private fun doubleTap(tab: String) {
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText(tab).performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText(tab).performClick()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
    }

    private fun assertOnRemotes() {
        compose.onNodeWithText("Living Room TV").assertExists()
        compose.onNodeWithText("BN59-01315J").assertDoesNotExist()
    }

    private fun assertOnBrowse() {
        check(compose.onAllNodesWithText("BN59-01315J").fetchSemanticsNodes().isNotEmpty()) { "Browse isn't showing" }
        compose.onNodeWithText("Living Room TV").assertDoesNotExist()
    }

    @Test fun reselectingRemotesKeepsTheList() {
        tap("Remotes")
        assertOnRemotes()
        tap("Remotes")
        assertOnRemotes()
    }

    @Test fun reselectingBrowseStaysOnBrowse() {
        tap("Browse")
        assertOnBrowse()
        tap("Browse")
        assertOnBrowse()
    }

    @Test fun doubleTappingRemotesFromAnotherTab() {
        tap("Browse")
        tap("Remotes")
        tap("Remotes")
        assertOnRemotes()
        tap("Browse")
        assertOnBrowse()
        tap("Remotes")
        assertOnRemotes()
    }

    @Test fun quickDoubleTapOnRemotes() {
        doubleTap("Remotes")
        assertOnRemotes()
        tap("Browse")
        doubleTap("Remotes")
        assertOnRemotes()
    }

    @Test fun quickDoubleTapOnBrowse() {
        doubleTap("Browse")
        assertOnBrowse()
    }
}
