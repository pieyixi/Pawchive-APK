package pw.pawchive.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import android.graphics.Bitmap
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import pw.pawchive.data.Post
import pw.pawchive.data.RemoteFile

/** Navigation-only: never enters credentials or changes the real account's follows/favorites. */
@RunWith(AndroidJUnit4::class)
class NativeSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun pagerCanHoldReverseCancelThenCommit() {
        compose.onNodeWithText("作品").performClick()
        val pager = compose.onNodeWithTag("home_pager")
        pager.performTouchInput {
            down(androidx.compose.ui.geometry.Offset(width * .2f, height * .45f))
            repeat(14) { step -> moveTo(androidx.compose.ui.geometry.Offset(width * (.2f + .4f * (step + 1) / 14f), height * .45f), delayMillis = 16) }
        }
        val held = compose.onNodeWithTag("home_grid_1").fetchSemanticsNode().boundsInRoot.left
        assertTrue("A held drag must actually displace the page", held > 60f)
        compose.mainClock.advanceTimeBy(160)
        assertEquals(held, compose.onNodeWithTag("home_grid_1").fetchSemanticsNode().boundsInRoot.left, 1f)
        pager.performTouchInput {
            repeat(14) { step -> moveTo(androidx.compose.ui.geometry.Offset(width * (.6f - .4f * (step + 1) / 14f), height * .45f), delayMillis = 16) }
            up()
        }
        compose.onNodeWithText("作品").assertIsSelected()
        assertEquals(0f, compose.onNodeWithTag("home_grid_1").fetchSemanticsNode().boundsInRoot.left, 1f)
        pager.performTouchInput { swipeRight() }
        compose.onNodeWithText("作者").assertIsSelected()
        compose.onNodeWithText("搜索作者").assertIsDisplayed()
    }

    @Test fun popMovesChildOutAndKeepsParentGeometry() {
        compose.onNode(hasText("我的") and hasClickAction()).performClick()
        val parentHeight = compose.onNodeWithTag("root_destination_2").fetchSemanticsNode().boundsInRoot.height
        val parentTextX = compose.onNodeWithText("我的关注", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.left
        compose.onNodeWithText("设置").performClick()
        val childTextX = compose.onNodeWithTag("page_title").fetchSemanticsNode().boundsInRoot.left
        compose.mainClock.autoAdvance = false
        try {
            Espresso.pressBack()
            compose.mainClock.advanceTimeBy(80)
            val restored = compose.onNodeWithText("我的关注", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val outgoing = compose.onNodeWithTag("page_title").fetchSemanticsNode().boundsInRoot
            assertTrue("Parent should enter from the left during pop", restored.left < parentTextX)
            assertTrue("Child should exit to the right during pop", outgoing.left > childTextX)
            assertEquals(parentHeight, compose.onNodeWithTag("root_destination_2").fetchSemanticsNode().boundsInRoot.height, 1f)
            compose.mainClock.advanceTimeBy(500)
        } finally { compose.mainClock.autoAdvance = true }
        compose.onNodeWithText("我的关注").assertIsDisplayed()
        compose.onNodeWithTag("page_title").assertDoesNotExist()
    }

    @Test fun threeTabsThemeSettingsAndNativeBack() {
        compose.onNodeWithText("我的", useUnmergedTree = true).performClick()
        compose.onNodeWithText("下载管理").assertIsDisplayed()
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("版本").assertIsDisplayed()
        val originallyDark = compose.onNodeWithTag("settings_dark_switch").fetchSemanticsNode().config[SemanticsProperties.ToggleableState]
        compose.onNodeWithTag("settings_dark_switch").performClick()
        compose.waitUntil { compose.onNodeWithTag("settings_dark_switch").fetchSemanticsNode().config[SemanticsProperties.ToggleableState] != originallyDark }
        compose.onNodeWithTag("settings_dark_switch").performClick()
        compose.waitUntil { compose.onNodeWithTag("settings_dark_switch").fetchSemanticsNode().config[SemanticsProperties.ToggleableState] == originallyDark }
        val repo = (compose.activity.application as PawApplication).container.repository
        val originalFilter = runBlocking { repo.hideMediaLessWorks.first() }
        try {
            compose.onNodeWithTag("settings_media_switch").performClick()
            compose.waitUntil { runBlocking { repo.hideMediaLessWorks.first() } != originalFilter }
            Espresso.pressBack()
            compose.onNodeWithText("设置").performClick()
            if (originalFilter) compose.onNodeWithTag("settings_media_switch").assertIsOff()
            else compose.onNodeWithTag("settings_media_switch").assertIsOn()
        } finally { runBlocking { repo.setHideMediaLessWorks(originalFilter) } }
        Espresso.pressBack()
        compose.onNodeWithText("我的关注").assertIsDisplayed()
        compose.onNodeWithText("动态", useUnmergedTree = true).performClick()
        compose.onNodeWithText("最近更新").assertIsDisplayed()
        Espresso.pressBack()
        compose.onNodeWithText("作者").assertIsDisplayed()
        compose.onNodeWithText("作品").assertIsDisplayed()
        compose.onNodeWithTag("bottom_navigation").assertHeightIsEqualTo(48.dp)
        compose.onNodeWithTag("nav_icon_2", useUnmergedTree = true).assertWidthIsEqualTo(27.5.dp)
        compose.onNode(hasText("我的") and hasClickAction()).performClick()
        val bottom = compose.onNodeWithTag("bottom_navigation").captureToImage().asAndroidBitmap()
        assertEquals(132, bottom.height)
        File(compose.activity.cacheDir, "qa-bottom-navigation.png").outputStream().use { bottom.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val icon = compose.onNodeWithTag("nav_icon_2", useUnmergedTree = true).captureToImage().asAndroidBitmap()
        val eyeColumns = (icon.width * .3f).toInt() until (icon.width * .7f).toInt()
        val eyeRows = (icon.height * .45f).toInt() until (icon.height * .68f).toInt()
        var strokes = 0
        var previousColumn = false
        for (x in eyeColumns) {
            val hasStroke = eyeRows.count { y ->
                val ink = icon.getPixel(x, y)
                android.graphics.Color.red(ink) - android.graphics.Color.green(ink) > 50
            } >= icon.height * .12f
            if (hasStroke && !previousColumn) strokes++
            previousColumn = hasStroke
        }
        assertEquals("Selected mine icon must have two visible vertical eyes", 2, strokes)
        compose.runOnUiThread {
            compose.activity.setContent {
                PawTheme(true) { Surface(Modifier.fillMaxSize()) { Column {
                    DetailFollowButton(true) {}
                    ProfileFollowButton(true) {}
                } } }
            }
        }
        val detail = compose.onNodeWithTag("detail_follow_visual", useUnmergedTree = true).captureToImage().asAndroidBitmap()
        val profile = compose.onNodeWithTag("profile_follow_visual", useUnmergedTree = true).captureToImage().asAndroidBitmap()
        assertEquals(198, detail.width); assertEquals(66, detail.height)
        assertEquals(504, profile.width); assertEquals(83, profile.height)
        File(compose.activity.cacheDir, "qa-detail-follow.png").outputStream().use { detail.compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(compose.activity.cacheDir, "qa-profile-follow.png").outputStream().use { profile.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun searchPlaceholderTracksSelectedCategory() {
        compose.onNodeWithText("作品").performClick()
        compose.onNodeWithText("搜索作品").assertIsDisplayed()
        compose.onNodeWithText("作者").performClick()
        compose.onNodeWithText("搜索作者").assertIsDisplayed()
        compose.onNodeWithText("作品").performClick()
        compose.onNodeWithText("搜索作品").assertIsDisplayed()
        // Both directions use real pointer input, with selection and search scope checked.
        compose.onNodeWithTag("home_pager").performTouchInput { swipeRight() }
        compose.onNodeWithText("作者").assertIsSelected()
        compose.onNodeWithText("搜索作者").assertIsDisplayed()
        compose.waitUntil(15000) { compose.onAllNodesWithTag("home_avatar_follow", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithTag("home_avatar_follow", useUnmergedTree = true).onFirst().assertWidthIsEqualTo(20.dp).assertHeightIsEqualTo(20.dp)
        compose.onNodeWithTag("home_grid_0").performScrollToIndex(12)
        val position = compose.onNodeWithTag("home_grid_0").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertTrue(position > 0f)
        compose.onNodeWithTag("home_pager").performTouchInput { swipeLeft() }
        compose.onNodeWithText("作品").assertIsSelected()
        compose.onNodeWithText("搜索作品").assertIsDisplayed()
        compose.onNodeWithTag("home_pager").performTouchInput { swipeRight() }
        val restored = compose.onNodeWithTag("home_grid_0").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertEquals(position, restored, .01f)
        compose.onNodeWithText("作品").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("work-only-qa")
        compose.onNodeWithText("作者").performClick()
        compose.onNodeWithText("搜索作者").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextReplacement("author-only-qa")
        compose.onNodeWithText("作品").performClick()
        compose.onNode(hasSetTextAction()).assertTextEquals("work-only-qa")
        compose.onNodeWithText("作者").performClick()
        compose.onNode(hasSetTextAction()).assertTextEquals("author-only-qa")
        compose.onNode(hasText("我的") and hasClickAction()).performClick()
        compose.onNodeWithText("我的关注").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("compact_follow_visual", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithTag("compact_follow_visual", useUnmergedTree = true).onFirst().assertWidthIsEqualTo(72.dp).assertHeightIsEqualTo(24.dp)
        compose.onAllNodesWithTag("compact_follow_visual", useUnmergedTree = true).onFirst().performClick()
        compose.onNodeWithText("取消关注？").assertIsDisplayed()
        compose.onNodeWithText("保留关注").performClick()
        Espresso.pressBack()
        compose.onNode(hasText("首页") and hasClickAction()).performClick()
        compose.onNodeWithText("作者").assertIsSelected()
        compose.onNode(hasSetTextAction()).assertTextEquals("author-only-qa")
    }

    @Test fun mediaTabsSwipeRetainScrollAndDoNotStartVideo() {
        val fixture = Post("tabs", "qa", "qa", attachments = (1..18).map { RemoteFile("$it.png", "/qa-tabs/$it.png") } + RemoteFile("1.mp4", "/qa-tabs/1.mp4"))
        var chosenVideo = -1
        compose.runOnUiThread { compose.activity.setContent {
            PawTheme(true) { Surface(Modifier.fillMaxSize()) {
                DetailMediaLayout(fixture, {}, { chosenVideo = it }) {
                    item { Text("QA shared header", Modifier.height(180.dp)) }
                }
            } }
        } }
        compose.onNodeWithText("18 张").assertIsDisplayed()
        compose.onNodeWithTag("detail_media_grid_0").performScrollToIndex(12)
        val before = compose.onNodeWithTag("detail_media_grid_0").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        compose.onNodeWithTag("detail_media_pager").performTouchInput { swipeLeft() }
        compose.onNodeWithText("视频").assertIsSelected()
        compose.onNodeWithText("1 个").assertIsDisplayed()
        assertEquals(-1, chosenVideo)
        compose.onNodeWithTag("detail_media_pager").performTouchInput { swipeRight() }
        compose.onNodeWithText("图片").assertIsSelected()
        compose.onNodeWithText("18 张").assertIsDisplayed()
        val restored = compose.onNodeWithTag("detail_media_grid_0").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertEquals(before, restored, .01f)
        compose.onNodeWithText("视频").performClick()
        compose.onNodeWithText("1 个").assertIsDisplayed()
        assertEquals(-1, chosenVideo)
    }

    @Test fun switchingToFeedAndActiveRetapOnlyReadCache() {
        val repo = (compose.activity.application as PawApplication).container.repository
        val owner = runBlocking { withTimeout(15000) { repo.account.first { !it.restoring }.owner } }
        val before = runBlocking { repo.dao.marks("$owner:feed") }
        assertFalse(repo.feedStatus.value.running)
        compose.onNode(hasText("动态") and hasClickAction()).performClick()
        compose.onNodeWithText("最近更新").assertIsDisplayed()
        assertFalse(repo.feedStatus.value.running)
        compose.onNode(hasText("我的") and hasClickAction()).performClick()
        compose.onNodeWithText("设置").performClick()
        Espresso.pressBack()
        compose.onNode(hasText("动态") and hasClickAction()).performClick()
        compose.onNodeWithText("最近更新").assertIsDisplayed()
        assertTrue("Navigation unexpectedly changed feed sync markers", before == runBlocking { repo.dao.marks("$owner:feed") })
        assertFalse(repo.feedStatus.value.running)
        // A real account with follows additionally verifies the explicit active-tab trigger.
        if (runBlocking { repo.followed().first().isNotEmpty() }) {
            compose.onNode(hasText("动态") and hasClickAction()).performClick()
            compose.waitForIdle()
            assertFalse(repo.feedStatus.value.running)
            assertEquals(before, runBlocking { repo.dao.marks("$owner:feed") })
        }
    }
}
