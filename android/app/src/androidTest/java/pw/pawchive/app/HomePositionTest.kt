package pw.pawchive.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.activity.compose.setContent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.cancel
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
    @Test fun bottomReselectReturnsCurrentHomeAndFeedToTopWithoutRefreshing() {
        move(1)
        compose.onNodeWithTag("bottom_tab_0").performClick()
        compose.waitUntil(10000) { position(1)<.01f }
        compose.onNodeWithText("作者").performClick()
        move(0)
        compose.onNodeWithTag("bottom_tab_0").performClick()
        compose.waitUntil(10000) { position(0)<.01f }
        // Feed UI uses an isolated in-memory cache, never the user's account or feed.
        val activity=compose.activity
        val vault=java.io.File(activity.cacheDir,"feed-top-fixture").apply {mkdirs()}
        val context=object:android.content.ContextWrapper(activity) {override fun getNoBackupFilesDir()=vault}
        val db=androidx.room.Room.inMemoryDatabaseBuilder(context,pw.pawchive.data.PawDatabase::class.java).build()
        val scope=kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()+kotlinx.coroutines.Dispatchers.IO)
        val requests=java.util.concurrent.atomic.AtomicInteger()
        val client=okhttp3.OkHttpClient.Builder().addInterceptor {requests.incrementAndGet();throw AssertionError("Return to top must not request the network")}.build()
        val fixture=pw.pawchive.data.PawRepository(context,scope,client,db,client)
        val store=androidx.lifecycle.ViewModelStore()
        try {
            kotlinx.coroutines.runBlocking {
                fixture.account.first { !it.restoring }
                fixture.storePosts("guest:feed",(0..20).map {pw.pawchive.data.Post("$it","fixture","qa",title="Feed fixture $it",added=kotlinx.serialization.json.JsonPrimitive(2000-it))})
                assertEquals(21,fixture.feed().first().size)
            }
            lateinit var fixtureApp:AppViewModel
            compose.runOnUiThread {
                fixtureApp=AppViewModel(fixture);store.put("feed-top",fixtureApp)
                activity.setContent {PawTheme(false) {FeedScreen(fixtureApp,{},{})}}
            }
            compose.waitUntil(10000) {compose.onAllNodesWithText("Feed fixture 0").fetchSemanticsNodes().isNotEmpty()}
            val feed=compose.onNodeWithTag("feed_list")
            feed.performScrollToIndex(4)
            fun feedPosition()=feed.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            assertTrue(feedPosition()>1f)
            compose.runOnUiThread {fixtureApp.scrollToTop.tryEmit(1)}
            compose.waitUntil(10000) {feedPosition()<.01f}
            assertEquals(0,requests.get())
            assertFalse(fixture.feedStatus.value.running)
        } finally {
            compose.runOnUiThread {activity.setContent {};store.clear()}
            scope.cancel();db.close();vault.delete()
        }
    }
}
