package pw.pawchive.app

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import pw.pawchive.data.*
import java.io.File

@RunWith(AndroidJUnit4::class)
class DetailHeaderDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun mediaTabsStayAtSameHeightAndExpandedTextSupportsCopyOnly() {
        val activity=compose.activity
        val clipboard=activity.getSystemService(android.content.ClipboardManager::class.java)
        val previousClip=clipboard.primaryClip
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val vault=File(activity.cacheDir,"detail-header-fixture").apply {mkdirs()}
        val context=object:android.content.ContextWrapper(activity) {override fun getNoBackupFilesDir()=vault}
        val db=androidx.room.Room.inMemoryDatabaseBuilder(context,PawDatabase::class.java).build()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val title="A deliberately long title that must remain on one line before opening its complete text. ".repeat(3)
        val content="<p>"+"A long description with details and links that must keep its expand action visible. ".repeat(20)+"</p>"
        val client=OkHttpClient.Builder().addInterceptor {chain ->
            val id=chain.request().url.pathSegments.last()
            val json=if(chain.request().url.encodedPath.contains("/post/")) wireJson.encodeToString(Post(id,"fixture","qa",title=title,content=content,
                attachments=if(id=="video") listOf(RemoteFile("fixture.mp4","/header-fixture.mp4")) else emptyList()))
                else wireJson.encodeToString(Creator("fixture","qa","Fixture author"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(json.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val repo=PawRepository(context,scope,client,db,client)
        val store=androidx.lifecycle.ViewModelStore()
        var tabTop:Float?=null
        try {
            for(id in listOf("plain","video")) {
                compose.runOnUiThread {
                    val vm=DetailViewModel(repo,"qa","fixture",id);val app=AppViewModel(repo)
                    store.put("detail-$id",vm);store.put("app-$id",app)
                    activity.setContent {PawTheme(false) {DetailScreen(vm,app,{},{},{},{},{})}}
                }
                compose.waitUntil(10000) {compose.onAllNodesWithTag("detail_content_expand").fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithTag("detail_title_expand").assertIsDisplayed()
                compose.onNodeWithTag("detail_content_expand").assertIsDisplayed()
                assertTrue("Collapsed body must occupy one line",compose.onNodeWithTag("detail_content_line").fetchSemanticsNode().boundsInRoot.height<=24*activity.resources.displayMetrics.density+1)
                val y=compose.onNodeWithText("图片").fetchSemanticsNode().boundsInRoot.top
                tabTop?.let {assertEquals("Media tabs must not depend on whether a post has video",it,y,1f)}
                tabTop=y
                for(tag in listOf("detail_title","detail_content")) {
                    compose.onNodeWithTag("${tag}_expand").performClick()
                    compose.onNodeWithTag("${tag}_full").assertExists()
                    compose.onNodeWithTag("${tag}_full").performTouchInput {longClick(Offset(18f,18f))}
                    val copy=device.wait(Until.findObject(By.text("复制")),3000)
                    assertNotNull("Expanded text must offer Copy",copy)
                    for(extra in listOf("全选","分享","搜索","翻译","剪切","粘贴")) {
                        assertFalse("Only Copy is permitted: $extra",device.hasObject(By.text(extra)))
                    }
                    copy!!.click()
                    compose.runOnUiThread {
                        val selected=clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                        val full=if(tag=="detail_title") title else org.jsoup.Jsoup.parse(content).text()
                        assertTrue("Copy must contain the selected text",selected.isNotBlank()&&full.contains(selected))
                        assertTrue("A word selection must not copy the whole post",selected.length<full.length)
                    }
                    androidx.test.espresso.Espresso.pressBack()
                    compose.onNodeWithTag("${tag}_expand").assertIsDisplayed()
                    assertEquals(y,compose.onNodeWithText("图片").fetchSemanticsNode().boundsInRoot.top,1f)
                }
            }
        } finally {
            compose.runOnUiThread {activity.setContent {};store.clear()}
            if(previousClip!=null) clipboard.setPrimaryClip(previousClip)
            else if(android.os.Build.VERSION.SDK_INT>=28) clipboard.clearPrimaryClip()
            scope.cancel();db.close();vault.delete()
        }
    }
}
