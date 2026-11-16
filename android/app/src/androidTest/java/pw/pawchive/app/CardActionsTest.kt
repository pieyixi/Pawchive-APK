package pw.pawchive.app

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.graphics.asAndroidBitmap
import android.graphics.Bitmap
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import pw.pawchive.data.Post

@RunWith(AndroidJUnit4::class)
class CardActionsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun localListsPersistAndCanBeRemovedWithoutChangingWebsiteCollections() = runBlocking {
        val repo = (compose.activity.application as PawApplication).container.repository
        withTimeout(10000) { repo.account.first { !it.restoring } }
        val post = Post("local-actions-fixture", "local-actions-fixture", "qa", title = "QA", content = "not persisted")
        try {
            repo.localCollection("later", post, true)
            assertEquals("", withTimeout(5000) { repo.collected("later").first { rows -> rows.any { it.key == post.key } } }.first { it.key == post.key }.content)
            repo.localCollection("blocked", post, true)
            assertTrue(withTimeout(5000) { repo.blockedAuthors().first { rows -> rows.any { it.key == post.authorKey } } }.any { it.key == post.authorKey })
        } finally {
            repo.localCollection("later", post, false); repo.localCollection("blocked", post, false)
        }
        assertFalse(repo.collected("later").first().any { it.key == post.key })
        assertFalse(repo.blockedAuthors().first().any { it.key == post.authorKey })
    }
    @Test fun cardSheetHasThreeActionsAndAuthorCardHasNoMenu() {
        val container = (compose.activity.application as PawApplication).container
        val app = AppViewModel(container.repository)
        val post = Post("sheet-fixture", "sheet-fixture", "qa", title = "测试封面")
        compose.runOnUiThread { compose.activity.setContent { PawTheme(true) { WorkCard(post, app) {} } } }
        compose.onNodeWithContentDescription("爪印封面").assertExists()
        val placeholder = compose.onRoot().captureToImage().asAndroidBitmap()
        File(compose.activity.cacheDir, "qa-placeholder-016.png").outputStream().use { placeholder.compress(Bitmap.CompressFormat.PNG, 100, it) }
        compose.onNodeWithContentDescription("作品菜单").performClick()
        compose.onNodeWithText("稍后再看").assertIsDisplayed()
        compose.onNodeWithText("屏蔽作者").assertIsDisplayed()
        compose.onNodeWithText("收藏").assertIsDisplayed()
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithText("稍后再看").assertDoesNotExist()
        try {
            compose.onNodeWithContentDescription("作品菜单").performClick()
            compose.onNodeWithText("稍后再看").performClick()
            compose.waitUntil(5000) { app.later.value.any { it.key == post.key } }
            compose.onNodeWithContentDescription("作品菜单").performClick()
            compose.onNodeWithText("移出稍后再看").performClick()
            compose.waitUntil(5000) { app.later.value.none { it.key == post.key } }
            compose.onNodeWithContentDescription("作品菜单").performClick()
            compose.onNodeWithText("屏蔽作者").performClick()
            compose.waitUntil(5000) { app.blocked.value.any { it.key == post.authorKey } }
            compose.onNodeWithContentDescription("作品菜单").performClick()
            compose.onNodeWithText("取消屏蔽作者").performClick()
            compose.waitUntil(5000) { app.blocked.value.none { it.key == post.authorKey } }
        } finally { runBlocking {
            container.repository.localCollection("later", post, false)
            container.repository.localCollection("blocked", post, false)
        } }
        compose.runOnUiThread { compose.activity.setContent { PawTheme(true) { WorkCard(post, app, showMenu = false) {} } } }
        compose.onNodeWithContentDescription("作品菜单").assertDoesNotExist()
    }
}
