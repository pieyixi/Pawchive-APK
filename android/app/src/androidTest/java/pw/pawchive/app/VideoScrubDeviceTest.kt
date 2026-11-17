package pw.pawchive.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.content.res.Configuration
import org.junit.*
import org.junit.runner.RunWith
import java.io.File
import pw.pawchive.data.RemoteFile
import pw.pawchive.media.ReferenceVideo

@RunWith(AndroidJUnit4::class)
class VideoScrubDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun trackDragBodyFineAdjustmentCancelAndRotation() {
        val activity = compose.activity
        val clip = File(activity.cacheDir,"seek-device-fixture.mp4")
        InstrumentationRegistry.getInstrumentation().context.assets.open("seek-fixture.mp4").use { input -> clip.outputStream().use { input.copyTo(it) } }
        try {
            compose.runOnUiThread { activity.setContent { PawTheme(false) { Box(Modifier.fillMaxWidth().height(221.dp)) {
                ReferenceVideo(RemoteFile("fixture.png","/seek-fixture.png"),"Seek fixture",{},{},Modifier.fillMaxSize(),clip.toURI().toString())
            } } } }
            compose.onNodeWithContentDescription("开始播放").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithContentDescription("暂停").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("暂停").performClick()
            compose.onNodeWithTag("video_seek").performTouchInput {
                down(Offset(width*.1f,centerY)); moveTo(Offset(width*.6f,centerY),800)
            }
            compose.onNodeWithTag("video_seek_preview").assertIsDisplayed()
            compose.onNodeWithText("01:48").assertIsDisplayed()
            compose.onNodeWithTag("video_seek").performTouchInput { up() }
            compose.waitUntil(5000) { compose.onAllNodesWithText("01:48/03:00").fetchSemanticsNodes().isNotEmpty() }
            // Relative gesture: half a viewport adds exactly 50s, independent of total duration.
            compose.onNodeWithTag("video_surface").performTouchInput {
                down(Offset(width*.2f,height*.4f)); moveTo(Offset(width*.7f,height*.4f),700)
            }
            compose.onNodeWithText("02:38").assertIsDisplayed()
            compose.onNodeWithTag("video_surface").performTouchInput { moveTo(Offset(width*.69f,height*.4f),200) }
            compose.onNodeWithText("02:37").assertIsDisplayed()
            // Cancellation rolls back instead of issuing a seek at the preview position.
            compose.onNodeWithTag("video_surface").performTouchInput { cancel() }
            compose.onNodeWithTag("video_seek_preview").assertDoesNotExist()
            compose.onNodeWithText("01:48/03:00").assertExists()
            compose.onNodeWithTag("video_surface").performTouchInput { swipe(Offset(width*.2f,height*.4f),Offset(width*.3f,height*.4f),600) }
            compose.waitUntil(5000) { compose.onAllNodesWithText("01:58/03:00").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("播放").assertExists() // Paused state survives seeking.
            compose.onNodeWithContentDescription("全屏").performClick()
            compose.waitUntil(10000) { activity.resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE }
            compose.onNodeWithTag("video_surface").performTouchInput {
                down(Offset(width*.2f,height*.4f)); moveTo(Offset(width*.3f,height*.4f),600)
            }
            compose.onNodeWithText("02:08").assertIsDisplayed()
            compose.onNodeWithTag("video_surface").performTouchInput { up() }
            compose.onNodeWithText("02:08/03:00").assertExists()
            androidx.test.espresso.Espresso.pressBack()
            compose.waitUntil(10000) { activity.resources.configuration.orientation==Configuration.ORIENTATION_PORTRAIT }
            compose.onNodeWithText("02:08/03:00").assertExists()
        } finally {
            compose.runOnUiThread { activity.setContent {} }
            clip.delete()
        }
    }
}
