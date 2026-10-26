package com.thecaticorn01.ircontroller

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.thecaticorn01.ircontroller.data.ThemeMode
import com.thecaticorn01.ircontroller.ui.App
import com.thecaticorn01.ircontroller.ui.theme.IRTheme
import com.thecaticorn01.ircontroller.core.model.DeviceCategory
import com.thecaticorn01.ircontroller.core.model.LearningTemplate
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The "Suggested buttons" chips on the Learn screen choose which buttons the wizard asks for. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = TestApplication::class, qualifiers = "w411dp-h891dp-xxhdpi")
class LearnSuggestionsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val tvButtons = LearningTemplate.buttonNames(DeviceCategory.TV)

    @Before fun open() = runBlocking {
        ApplicationProvider.getApplicationContext<IRApplication>().container.library.awaitLoaded()
        compose.setContent {
            IRTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) { App(incoming = emptyFlow()) }
        }
        compose.onNodeWithText("Learn").performClick()
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput("Hallway TV")
    }

    private fun startLearning() {
        compose.onNodeWithText("Start learning").performScrollTo().performClick()
        compose.waitForIdle()
    }

    @Test fun allSuggestionsAreLearnedByDefault() {
        startLearning()
        compose.onNodeWithText("Button 1 of ${tvButtons.size}").assertExists()
    }

    @Test fun tappingASuggestionLeavesItOut() {
        compose.onNodeWithText("Power").performScrollTo().performClick()
        startLearning()
        compose.onNodeWithText("Button 1 of ${tvButtons.size - 1}").assertExists()
        compose.onNodeWithText("Power").assertDoesNotExist()
    }

    @Test fun tappingItAgainPutsItBack() {
        compose.onNodeWithText("Power").performScrollTo().performClick()
        compose.onNodeWithText("Power").performClick()
        startLearning()
        compose.onNodeWithText("Button 1 of ${tvButtons.size}").assertExists()
        check(compose.onAllNodesWithText("Power").fetchSemanticsNodes().isNotEmpty())
    }
}
