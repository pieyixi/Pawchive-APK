package pw.pawchive.app

import android.content.res.Configuration
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import pw.pawchive.data.*
import pw.pawchive.media.ReferenceVideo
import pw.pawchive.media.PreviewImage
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancel
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

@RunWith(AndroidJUnit4::class)
class VideoPlayerDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun videoTab() = compose.onNode(hasText("视频") and SemanticsMatcher.expectValue(SemanticsProperties.Role,Role.Tab))
    private fun screenshot(name:String) {
        compose.waitForIdle()
        Thread.sleep(800) // Wait for the system rotation compositor, beyond Compose idleness.
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        try {File(compose.activity.cacheDir,"video-$name.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}} finally {bitmap.recycle()}
    }
    @Test fun playbackSeekAndFullscreenKeepSameSession() {
        val activity=compose.activity
        val fixture=File(activity.cacheDir,"video-player-test.mp4")
        val bytes=runCatching { androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets.open("video-fixture.mp4").use { it.readBytes() } }.getOrNull()
        Assume.assumeTrue("Generate local test video first",bytes!=null)
        fixture.writeBytes(bytes!!)
        try {
            compose.runOnUiThread { activity.setContent { PawTheme(false) { Box(Modifier.fillMaxWidth().height(221.dp)) { ReferenceVideo(RemoteFile("fixture.png","/fixture.png"),"Playback fixture",{}, {},Modifier.fillMaxSize(),fixture.toURI().toString()) } } } }
            compose.onNodeWithContentDescription("开始播放").assertExists().performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithContentDescription("暂停").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("暂停").performClick()
            compose.onNodeWithTag("video_seek").performTouchInput { click(center) }
            compose.waitUntil(5000) { compose.onAllNodes(hasText("00:10/00:20",substring=true)).fetchSemanticsNodes().isNotEmpty() }
            screenshot("portrait")
            compose.onNodeWithContentDescription("全屏").performClick()
            compose.waitUntil(10000) { activity.resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE }
            compose.onNodeWithContentDescription("退出全屏").assertExists()
            compose.onNodeWithText("00:10/00:20").assertExists()
            compose.onNodeWithText("倍速").performClick()
            screenshot("speed")
            compose.onNodeWithText("0.75X").assertExists().performClick()
            compose.onNodeWithText("0.75X").assertExists()
            Espresso.pressBack()
            compose.waitUntil(10000) { activity.resources.configuration.orientation==Configuration.ORIENTATION_PORTRAIT }
            compose.onNodeWithContentDescription("全屏").assertExists()
            compose.onNodeWithText("00:10/00:20").assertExists()
        } finally { fixture.delete() }
    }
    @Test fun remoteVideoFirstFrameIsCached(): Unit = runBlocking {
        val scope=InstrumentationRegistry.getArguments().getString("videoPost")
        Assume.assumeTrue("Provide a locally cached public video scope",scope!=null)
        val repo=(compose.activity.application as PawApplication).container.repository
        val file=repo.cachedPosts(scope!!).firstOrNull()?.videos?.firstOrNull()
        Assume.assumeTrue("Public video must already exist in local metadata cache",file!=null)
        val key=java.security.MessageDigest.getInstance("SHA-256").digest(file!!.original.toByteArray()).joinToString("") {"%02x".format(it)}
        val target=File(compose.activity.cacheDir,"video-frames/$key.jpg")
        compose.runOnUiThread { compose.activity.setContent { PawTheme(false) { PreviewImage(file,Modifier.size(200.dp)) } } }
        compose.waitUntil(25000) {target.exists() && target.length()>0}
        val bounds=android.graphics.BitmapFactory.Options().apply {inJustDecodeBounds=true}
        android.graphics.BitmapFactory.decodeFile(target.absolutePath,bounds)
        assertTrue(bounds.outWidth in 1..480);assertTrue(bounds.outHeight in 1..320)
    }
    @Test fun imageSwipeKeepsPlayerAndBackCollapsesToDetail(): Unit = runBlocking {
        val activity=compose.activity
        val clip=File(activity.cacheDir,"video-detail-test.mp4")
        InstrumentationRegistry.getInstrumentation().context.assets.open("video-fixture.mp4").use {input -> clip.outputStream().use {input.copyTo(it)}}
        val vault=File(activity.cacheDir,"video-detail-fixture").apply {mkdirs()}
        val context=object: android.content.ContextWrapper(activity) {override fun getNoBackupFilesDir()=vault}
        val db=androidx.room.Room.inMemoryDatabaseBuilder(context,PawDatabase::class.java).build()
        val scope=kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()+kotlinx.coroutines.Dispatchers.IO)
        val videos=(1..3).map {RemoteFile("clip$it.mp4","/thumbnail-fixture-$it.mp4")}
        val frameFiles=videos.mapIndexed { index,file ->
            val hash=java.security.MessageDigest.getInstance("SHA-256").digest(file.original.toByteArray()).joinToString("") {"%02x".format(it)}
            File(activity.cacheDir,"video-frames/$hash.jpg").apply {
                parentFile!!.mkdirs()
                val bitmap=android.graphics.Bitmap.createBitmap(240,135,android.graphics.Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(listOf(android.graphics.Color.rgb(210,30,30),android.graphics.Color.rgb(30,210,30),android.graphics.Color.rgb(30,30,210))[index])
                outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,95,it)};bitmap.recycle()
            }
        }
        val post=Post("42","qa","qa",title="Video fixture",content="<p>Original description</p>",file=RemoteFile("cover.png","/cover.png"),attachments=videos)
        val client=okhttp3.OkHttpClient.Builder().addInterceptor {chain ->
            val body=if(chain.request().url.encodedPath.contains("/post/")) wireJson.encodeToString(post) else wireJson.encodeToString(Creator("qa","qa","Fixture author"))
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val repo=PawRepository(context,scope,client,db)
        val store=androidx.lifecycle.ViewModelStore()
        try {
            compose.runOnUiThread {
                val vm=DetailViewModel(repo,"qa","qa","42");val app=AppViewModel(repo)
                store.put("detail",vm);store.put("app",app)
                activity.setContent {PawTheme(false) {DetailScreen(vm,app,{}, {}, {}, {}, {},videoUri={clip.toURI().toString()})}}
            }
            compose.waitUntil(10000) {compose.onAllNodesWithText("Video fixture").fetchSemanticsNodes().isNotEmpty()}
            val before=compose.onNodeWithText("Video fixture").fetchSemanticsNode().boundsInRoot
            val tabTop=videoTab().fetchSemanticsNode().boundsInRoot.top
            compose.onNodeWithTag("detail_media_pager").performTouchInput {swipeLeft()}
            fun tileColor(index:Int):androidx.compose.ui.graphics.Color {
                val image=compose.onNodeWithContentDescription("视频 ${index+1}").captureToImage()
                return image.toPixelMap()[image.width/4,image.height/2]
            }
            compose.waitUntil(15000) {runCatching {tileColor(0).let {it.red>.6f && it.green<.3f}}.getOrDefault(false)}
            val colors=(0..2).map(::tileColor)
            compose.mainClock.autoAdvance=false
            compose.onNodeWithTag("detail_media_grid_1").onChildren().onFirst().performClick()
            compose.mainClock.advanceTimeBy(80)
            val chrome=compose.onNodeWithContentDescription("开始播放").captureToImage().toPixelMap()
            val shade=chrome[(chrome.width*.2f).toInt(),chrome.height/2]
            assertTrue("Play button must be visible DURING entry, not added at the end",shade.red<.7f && shade.green<.3f)
            compose.onNodeWithTag("video_seek").assertExists()
            compose.mainClock.advanceTimeBy(80)
            (0..2).forEach { index ->
                val now=tileColor(index);val beforeColor=colors[index]
                assertEquals(beforeColor.red,now.red,.03f);assertEquals(beforeColor.green,now.green,.03f);assertEquals(beforeColor.blue,now.blue,.03f)
            }
            compose.mainClock.autoAdvance=true
            compose.waitForIdle()
            compose.onNodeWithContentDescription("开始播放").assertExists()
            compose.onNodeWithContentDescription("回到主页").assertDoesNotExist()
            assertEquals(tabTop,videoTab().fetchSemanticsNode().boundsInRoot.top,1f)
            compose.onNodeWithText("Video fixture").assertIsNotDisplayed()
            compose.mainClock.autoAdvance=false
            Espresso.pressBack()
            compose.mainClock.advanceTimeBy(160)
            (0..2).forEach { index ->
                val now=tileColor(index);val beforeColor=colors[index]
                assertEquals(beforeColor.red,now.red,.03f);assertEquals(beforeColor.green,now.green,.03f);assertEquals(beforeColor.blue,now.blue,.03f)
            }
            compose.mainClock.autoAdvance=true
            compose.waitForIdle()
            compose.onNodeWithText("Video fixture").assertIsDisplayed()
            compose.onNodeWithContentDescription("开始播放").assertDoesNotExist()
            compose.onNodeWithTag("detail_media_grid_1").onChildren().onFirst().performClick()
            compose.onNodeWithContentDescription("开始播放").assertExists()
            compose.onNodeWithContentDescription("开始播放").performClick()
            compose.waitUntil(10000) {compose.onAllNodesWithContentDescription("暂停").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("detail_media_pager").performTouchInput {swipeRight()}
            compose.onNodeWithTag("video_seek").assertExists()
            compose.onNodeWithText("Video fixture").assertIsNotDisplayed()
            assertEquals(tabTop,videoTab().fetchSemanticsNode().boundsInRoot.top,1f)
            Espresso.pressBack()
            compose.onNodeWithTag("video_seek").assertDoesNotExist()
            compose.onNodeWithText("Video fixture").assertIsDisplayed()
            compose.onNodeWithText("Original description").assertExists()
            assertEquals(before.top,compose.onNodeWithText("Video fixture").fetchSemanticsNode().boundsInRoot.top,1f)
            // Playing toolbar Back also collapses to this same detail instead of navigating away.
            compose.onNodeWithTag("detail_media_pager").performTouchInput {swipeLeft()}
            compose.onNodeWithTag("detail_media_grid_1").onChildren().onFirst().performClick()
            compose.onNodeWithContentDescription("开始播放").performClick()
            compose.waitUntil(10000) {compose.onAllNodesWithContentDescription("暂停").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithContentDescription("返回").performClick()
            compose.onNodeWithText("Video fixture").assertIsDisplayed()
            compose.onNodeWithTag("video_seek").assertDoesNotExist()
        } finally {
            compose.mainClock.autoAdvance=true
            compose.runOnUiThread {activity.setContent {};store.clear()}
            scope.cancel();db.close();vault.delete();clip.delete();frameFiles.forEach {it.delete()}
        }
    }
}
