package pw.pawchive.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomePositionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun grid(page: Int) = compose.onNodeWithTag("home_grid_$page")
    private fun position(page: Int) = grid(page).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
    private fun ready(page: Int) {
        compose.waitUntil(20000) {
            compose.onAllNodes(hasAnyAncestor(hasTestTag("home_grid_$page")) and hasClickAction()).fetchSemanticsNodes().size >= 4
        }
    }
    private fun move(page: Int): Float {
        ready(page)
        grid(page).performScrollToIndex(24)
        grid(page).performTouchInput { swipeUp(startY = height * .75f, endY = height * .55f, durationMillis = 350) }
        compose.waitForIdle()
        return position(page).also { assertTrue("Expected a scrolled list", it > 15f) }
    }
    private fun openAndReturn(page: Int, before: Float) {
        // Click the visible card itself, rather than its follow/menu action.
        val cards = if (page == 0) compose.onAllNodesWithTag("home_author_card")
            else compose.onAllNodes(hasAnyAncestor(hasTestTag("home_grid_$page")) and hasClickAction())
        val viewport = grid(page).fetchSemanticsNode().boundsInRoot
        // The first partially clipped author's centre can land on its follow action.
        val index = cards.fetchSemanticsNodes().indexOfFirst {
            val bounds = it.boundsInRoot
            bounds.top >= viewport.top && bounds.bottom <= viewport.bottom && bounds.height > viewport.height * .2f
        }
        assertTrue("No fully visible card to open", index >= 0)
        cards[index].performClick()
        grid(page).assertDoesNotExist()
        Espresso.pressBack()
        grid(page).assertIsDisplayed()
        assertEquals("Detail return changed the saved list anchor", before, position(page), .001f)
    }
    @Test fun workDetailBackAndTabSwitchKeepPosition() {
        val before = move(1)
        repeat(2) { openAndReturn(1, before) }
        compose.onNodeWithText("作者").performClick()
        ready(0)
        compose.onNodeWithText("作品").performClick()
        assertEquals(before, position(1), .001f)
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        assertEquals("Activity recreation lost the work position", before, position(1), .001f)
    }
    @Test fun authorDetailBackAndPagerKeepIndependentPosition() {
        val works = move(1)
        compose.onNodeWithText("作者").performClick()
        val authors = move(0)
        repeat(2) { openAndReturn(0, authors) }
        compose.onNodeWithTag("home_pager").performTouchInput { swipeLeft() }
        assertEquals(works, position(1), .001f)
        compose.onNodeWithTag("home_pager").performTouchInput { swipeRight() }
        assertEquals(authors, position(0), .001f)
        compose.onNodeWithContentDescription("高级搜索").performClick()
        compose.onNodeWithTag("filter_sort_updated").performClick()
        compose.onNodeWithTag("filter_apply").performClick()
        compose.waitUntil(10000) { position(0) < 1f }
    }
}
