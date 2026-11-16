package pw.pawchive.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import pw.pawchive.data.*
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class Search018DeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun homeKeepsQueriesAndAppliedFiltersIndependentAcrossSwipe() {
        compose.onNode(hasSetTextAction()).performTextReplacement("JK")
        compose.onNodeWithContentDescription("高级搜索").performClick()
        compose.onNodeWithTag("filter_range_title").performClick()
        compose.onNodeWithTag("filter_media_image").performClick()
        compose.onNodeWithTag("filter_apply").performClick()
        compose.onNodeWithText("作者").performClick()
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
        compose.onNode(hasSetTextAction()).performTextReplacement("Maple")
        compose.onNodeWithContentDescription("高级搜索").performClick()
        compose.onNodeWithTag("filter_range_title").assertDoesNotExist()
        compose.onNodeWithTag("filter_service_patreon").performClick()
        compose.onNodeWithTag("filter_apply").performClick()
        compose.onNodeWithTag("home_pager").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).assertTextEquals("JK")
        compose.onNodeWithContentDescription("高级搜索").performClick()
        compose.onNodeWithTag("filter_range_title").assertIsSelected()
        compose.onNodeWithTag("filter_media_image").assertIsSelected()
        compose.onNodeWithTag("filter_service_all").assertIsSelected()
        androidx.test.espresso.Espresso.pressBack()
    }

    @Test fun actualSiteAcceptsNativeAdvancedWorkQuery(): Unit = runBlocking {
        val repo = (compose.activity.application as PawApplication).container.repository
        val filters = SearchFilters(range = "title", service = "fanbox", media = "image", start = "2026-01-01", end = "2026-11-16", ai = "hide")
        val posts = withTimeout(30000) { loadWorkSearchPage(repo.api, "JK", 0, false, filters).rawPosts }
        assertTrue("Expected non-empty real filtered results", posts.isNotEmpty())
        assertTrue(posts.all { it.service == "fanbox" && it.title.contains("JK", true) && it.images.isNotEmpty() })
        assertTrue(posts.all { it.timestamp >= timeValue(kotlinx.serialization.json.JsonPrimitive("2026-01-01T00:00:00Z")) && it.timestamp < timeValue(kotlinx.serialization.json.JsonPrimitive("2026-11-17T00:00:00Z")) })
        InstrumentationRegistry.getInstrumentation().addResults(Bundle().apply { putString("verifiedAdvancedQueryResults", posts.size.toString()) })
    }

    @Test fun referencePanelKeepsTextStableAndCommitsOnlyOnApply() {
        var committed = SearchFilters()
        compose.runOnUiThread {
            compose.activity.setContent { PawTheme(true) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    AdvancedSearchPanel(false, SearchFilters(), maxWidth, maxHeight, {}, { committed = it })
                }
            } }
        }
        compose.waitForIdle()
        val button = compose.onNodeWithTag("filter_range_title")
        val before = button.getUnclippedBoundsInRoot()
        val textBefore = compose.onNodeWithText("仅标题").getUnclippedBoundsInRoot()
        button.performClick().assertIsSelected()
        assertEquals(before, button.getUnclippedBoundsInRoot())
        assertEquals(textBefore, compose.onNodeWithText("仅标题").getUnclippedBoundsInRoot())
        assertEquals(SearchFilters(), committed)
        compose.onNodeWithContentDescription("选择开始日期").performClick()
        compose.onNodeWithTag("reference_date_picker").assertIsDisplayed()
        val bounds = compose.onNodeWithTag("reference_date_picker").getUnclippedBoundsInRoot()
        assertEquals(320f, (bounds.right - bounds.left).value, .6f)
        compose.onNodeWithTag("calendar_cancel").performClick()
        compose.onNodeWithTag("reference_date_picker").assertDoesNotExist()
        compose.onNodeWithTag("filter_apply").performClick()
        compose.waitUntil(3000) { committed.range == "title" }
        assertEquals("title", committed.range)
    }

    @Test fun calendarCancelAndConfirmAreIndependentAndFutureDisabled() {
        var chosen: LocalDate? = null
        var closed = false
        val today = LocalDate.now()
        val initial = today.withDayOfMonth(1)
        compose.runOnUiThread { compose.activity.setContent { PawTheme(false) {
            ReferenceDatePicker(initial, LocalDate.of(1900, 1, 1), today, { closed = true }, { chosen = it })
        } } }
        compose.onNodeWithTag("calendar_$initial").assertIsSelected()
        compose.onNodeWithTag("calendar_$today").performClick().assertIsSelected()
        if (today.dayOfMonth < today.lengthOfMonth()) compose.onNodeWithTag("calendar_${today.plusDays(1)}").assertIsNotEnabled()
        assertNull(chosen)
        compose.onNodeWithTag("calendar_confirm").performClick()
        assertEquals(today, chosen); assertFalse(closed)
    }

    /** Real account GETs only. Reports counts and duration, never session data or author identities. */
    @Test fun realFeedRefreshUsesFollowedDiscoveryAndKeepsSession(): Unit = runBlocking {
        val repo = (compose.activity.application as PawApplication).container.repository
        val account = withTimeout(30000) { repo.account.first { !it.restoring } }
        assertNotNull("Existing device sign-in required", account.username)
        withTimeout(120000) { repo.refreshFeed() }
        assertNull(repo.feedStatus.value.error)
        val first = repo.feedMetrics.value
        assertTrue(first.authorListRequests in 1..2)
        val posts = repo.feed().first()
        assertTrue(posts.zipWithNext().all { (a, b) -> a.feedTimestamp >= b.feedTimestamp })
        withTimeout(120000) { repo.refreshFeed() }
        assertNull(repo.feedStatus.value.error)
        val second = repo.feedMetrics.value
        assertEquals(account.owner, repo.account.value.owner)
        InstrumentationRegistry.getInstrumentation().addResults(Bundle().apply {
            putString("firstRefreshMetrics", first.toString())
            putString("repeatRefreshMetrics", second.toString())
            putString("cachedPosts", repo.feed().first().size.toString())
        })
    }
}
