package pw.pawchive.app

import android.view.WindowInsets
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real touch/IME checks: semantic text injection alone cannot exercise the focus hand-off. */
@RunWith(AndroidJUnit4::class)
class Search019InteractionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun imeOpen() = compose.activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
    private fun tapSearch() = compose.onNodeWithTag("home_search_input").performTouchInput { click(center) }
    private fun waitForPanel() {
        compose.waitUntil(6000) { !imeOpen() && compose.onAllNodesWithTag("advanced_search_panel").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("advanced_search_panel").assertIsDisplayed()
    }

    @Test fun singleTapReturnsFromFilterToTypingAcrossRepeatedCycles() {
        val header = compose.onNodeWithTag("home_search_outline", useUnmergedTree = true).getUnclippedBoundsInRoot()
        repeat(5) {
            tapSearch()
            compose.onNodeWithTag("home_search_input").assertIsFocused()
            compose.waitUntil(6000) { imeOpen() }
            assertEquals(header, compose.onNodeWithTag("home_search_outline", useUnmergedTree = true).getUnclippedBoundsInRoot())
            compose.onNodeWithTag("advanced_search_button").performTouchInput { click(center) }
            waitForPanel()
            compose.onNodeWithTag("home_search_input").assertIsNotFocused()
            compose.onNodeWithText("排除标题词").assertDoesNotExist()
            compose.onAllNodes(hasSetTextAction()).assertCountEquals(1)
            // One actual touch must focus the original field and reopen its IME.
            tapSearch()
            compose.onNodeWithTag("home_search_input").assertIsFocused()
            compose.waitUntil(6000) { imeOpen() && compose.onAllNodesWithTag("advanced_search_panel").fetchSemanticsNodes().isEmpty() }
            assertEquals(header, compose.onNodeWithTag("home_search_outline", useUnmergedTree = true).getUnclippedBoundsInRoot())
        }
        Espresso.pressBack()
        compose.waitUntil(6000) { !imeOpen() }
    }

    @Test fun rapidLastIntentAndBackNeverReopenAnObsoletePanel() {
        tapSearch(); compose.waitUntil(6000) { imeOpen() }
        compose.onNodeWithTag("advanced_search_button").performTouchInput { click(center) }
        // Reverse while the system is still hiding the keyboard.
        tapSearch()
        compose.waitUntil(6000) { imeOpen() }
        compose.onNodeWithTag("home_search_input").assertIsFocused()
        compose.onNodeWithTag("advanced_search_panel").assertDoesNotExist()
        compose.onNodeWithTag("home_search_input").performTextReplacement("JK")
        compose.onNodeWithTag("advanced_search_button").performTouchInput { click(center) }
        waitForPanel()
        compose.onNodeWithContentDescription("选择开始日期").performTouchInput { click(center) }
        compose.onNodeWithTag("reference_date_picker").assertIsDisplayed()
        Espresso.pressBack()
        compose.onNodeWithTag("reference_date_picker").assertDoesNotExist()
        compose.onNodeWithTag("advanced_search_panel").assertIsDisplayed()
        Espresso.pressBack()
        compose.waitUntil(6000) { compose.onAllNodesWithTag("advanced_search_panel").fetchSemanticsNodes().isEmpty() }
        assertFalse(imeOpen())
        tapSearch(); compose.waitUntil(6000) { imeOpen() }
        compose.onNodeWithTag("home_search_input").assertTextEquals("JK")
    }
}
