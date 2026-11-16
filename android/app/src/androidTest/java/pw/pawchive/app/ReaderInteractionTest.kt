package pw.pawchive.app

import android.graphics.Bitmap
import android.graphics.Color
import android.view.KeyEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import pw.pawchive.data.*
import pw.pawchive.media.reader.PreviewReaderSource
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ReaderInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun key(url: String) = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
    @Test fun closingReaderNeverClosesTlsConnectionOnMainThread(): Unit = runBlocking {
        val certificate = okhttp3.tls.HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverTls = okhttp3.tls.HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = okhttp3.tls.HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val client = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).build()
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.useHttps(serverTls.sslSocketFactory(), false)
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("reader-close-fixture"))
            withContext(Dispatchers.IO) { client.newCall(Request.Builder().url(server.url("/fixture")).build()).execute().use { it.body!!.string() } }
            assertEquals(1, client.connectionPool.idleConnectionCount())
            val directory = File(compose.activity.cacheDir, "qa-close-${UUID.randomUUID()}")
            val source = PreviewReaderSource(compose.activity, emptyList(), client, directory)
            compose.runOnUiThread {
                val old = android.os.StrictMode.getThreadPolicy()
                try {
                    android.os.StrictMode.setThreadPolicy(android.os.StrictMode.ThreadPolicy.Builder().detectNetwork().penaltyDeathOnNetwork().build())
                    source.close()
                } finally { android.os.StrictMode.setThreadPolicy(old) }
            }
            withTimeout(5000) { while (client.connectionPool.connectionCount() != 0) delay(20) }
            directory.delete()
        }
    }
    private fun fixtureBytes(): ByteArray {
        val image = Bitmap.createBitmap(300, 450, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(76, 118, 167)) }
        return try { ByteArrayOutputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() } } finally { image.recycle() }
    }

    @Test fun migratedReaderTurnsImmediatelyHasOriginalSheetGridAndVolumeNavigation(): Unit = runBlocking {
        val activity = compose.activity
        val repo = (activity.application as PawApplication).container.repository
        val originalSettings = repo.readerSettings.first()
        val id = UUID.randomUUID().toString()
        val service = "qa_reader"; val user = id; val postId = "fixture"
        val cacheScope = "post:$service:$user:$postId"
        val files = (1..12).map { RemoteFile("QA-$it.png", "/fixtures/$id/$it.png") }
        val post = Post(postId, user, service, "Reader fixture QA", attachments = files)
        val cacheFiles = files.map { File(activity.cacheDir, "reader-pages/${key(it.thumbnail)}") }
        try {
            repo.setReaderSettings(ReaderSettings())
            repo.dao.posts(listOf(PostRow(cacheScope, post.key, 0, wireJson.encodeToString(post))))
            val bytes = fixtureBytes()
            withContext(Dispatchers.IO) { cacheFiles.forEach { it.parentFile!!.mkdirs(); it.writeBytes(bytes) } }
            val readerIntent = ReaderActivity.intent(activity, service, user, postId, 0)
            assertEquals(BuildConfig.APPLICATION_ID, readerIntent.component!!.packageName)
            android.util.Log.i("ReaderQA", "launch reader")
            compose.runOnUiThread { activity.startActivity(readerIntent) }
            compose.waitUntil(10000) { compose.onAllNodesWithTag("reader_bottom_bar").fetchSemanticsNodes().isNotEmpty() }
            android.util.Log.i("ReaderQA", "reader visible")
            compose.onNodeWithTag("reader_pages").performTouchInput { click(Offset(width * .85f, height * .45f)) }
            compose.onNodeWithTag("reader_bottom_bar").assertDoesNotExist()
            compose.onNodeWithTag("reader_pages").performTouchInput { click(Offset(width * .85f, height * .45f)) }
            compose.onNodeWithContentDescription("第 2 页").assertIsDisplayed()
            android.util.Log.i("ReaderQA", "page two")
            compose.onNodeWithTag("reader_pages").performTouchInput { swipeLeft() }
            compose.onNodeWithContentDescription("第 3 页").assertIsDisplayed()
            compose.onNodeWithTag("reader_pages").performTouchInput { swipeRight() }
            compose.onNodeWithContentDescription("第 2 页").assertIsDisplayed()
            val readerBounds = compose.onNodeWithTag("reader_pages").fetchSemanticsNode().boundsInRoot
            fun nativeTap() {
                val downTime = android.os.SystemClock.uptimeMillis()
                val x = readerBounds.left + readerBounds.width * .85f
                val y = readerBounds.top + readerBounds.height * .45f
                val down = android.view.MotionEvent.obtain(downTime, downTime, android.view.MotionEvent.ACTION_DOWN, x, y, 0)
                val up = android.view.MotionEvent.obtain(downTime, downTime + 15, android.view.MotionEvent.ACTION_UP, x, y, 0)
                down.source = android.view.InputDevice.SOURCE_TOUCHSCREEN; up.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                try {
                    InstrumentationRegistry.getInstrumentation().sendPointerSync(down)
                    android.os.SystemClock.sleep(15)
                    InstrumentationRegistry.getInstrumentation().sendPointerSync(up)
                } finally { down.recycle(); up.recycle() }
            }
            nativeTap(); android.os.SystemClock.sleep(35); nativeTap()
            // ReaderActivity has its own real frame clock; wait for actual landing,
            // rather than treating a not-yet-advanced frame as a dropped click.
            try {
                compose.waitUntil(5000) { runBlocking { repo.dao.metadata("reader:$service:$user:$postId") } == "3" }
            } catch (error: Throwable) {
                android.util.Log.i("ReaderQA", "Native rapid landing progress=" + repo.dao.metadata("reader:$service:$user:$postId"))
                compose.onNodeWithTag("reader_pages").printToLog("ReaderQA")
                throw error
            }
            compose.onNodeWithContentDescription("第 4 页").assertIsDisplayed()

            compose.onNodeWithTag("reader_pages").performTouchInput { longClick(center, durationMillis = 650) }
            compose.onNodeWithText("保存").assertIsDisplayed()
            compose.onNodeWithText("保存到…").assertIsDisplayed()
            compose.onNodeWithText("查看原图").assertIsDisplayed()
            android.util.Log.i("ReaderQA", "long press menu")
            Espresso.pressBack()
            compose.onNodeWithTag("reader_pages").assertIsDisplayed()

            compose.onNodeWithTag("reader_pages").performTouchInput { click(center) }
            val gridLabel = activity.getString(pw.pawchive.media.R.string.reader_page_grid)
            compose.onNodeWithContentDescription(gridLabel).performClick()
            compose.onNodeWithTag("reader_page_grid").performScrollToIndex(7)
            compose.onNodeWithText("8").performClick()
            compose.onNodeWithTag("reader_page_grid").assertDoesNotExist()
            compose.onNodeWithContentDescription("第 8 页").assertIsDisplayed()
            android.util.Log.i("ReaderQA", "grid jump")

            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_VOLUME_DOWN)
            compose.onNodeWithContentDescription("第 9 页").assertIsDisplayed()
            android.util.Log.i("ReaderQA", "volume turn")
            val optionsLabel = activity.getString(pw.pawchive.media.R.string.reader_options)
            compose.onNodeWithContentDescription(optionsLabel).performClick()
            compose.onNodeWithText("条漫").performClick()
            withTimeout(5000) { repo.readerSettings.first { it.readerOrientation == ReaderOrientation.VERTICAL && !it.readerSnap } }
            Espresso.pressBack()
            compose.onNodeWithTag("reader_pages").assertIsDisplayed()
            Espresso.pressBack()
            compose.onNodeWithTag("bottom_navigation").assertIsDisplayed()
        } finally {
            repo.setReaderSettings(originalSettings)
            repo.dao.clearPosts(cacheScope)
            repo.dao.removeMetadata("reader:$service:$user:$postId")
            withContext(Dispatchers.IO) { cacheFiles.forEach { it.delete() } }
        }
    }

    @Test fun previewAdapterNeverRequestsOriginalBeforeExplicitChoice(): Unit = runBlocking {
        val activity = compose.activity
        val id = UUID.randomUUID().toString()
        val file = RemoteFile("fixture.png", "/fixtures/$id/image.png")
        val requests = mutableListOf<String>()
        val bytes = fixtureBytes()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests.add(chain.request().url.toString())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(bytes.toResponseBody("image/png".toMediaType())).build()
        }.build()
        val directory = File(activity.cacheDir, "qa-reader-source-$id")
        val source = PreviewReaderSource(activity, listOf(file), client, directory)
        try {
            source.pageFile(0, false)
            assertEquals(listOf(file.thumbnail), requests)
            source.pageFile(0, false)
            assertEquals(1, requests.size)
            source.pageFile(0, true)
            assertEquals(listOf(file.thumbnail, file.original), requests)
        } finally {
            source.close()
            withContext(Dispatchers.IO) { directory.listFiles()?.forEach { it.delete() }; directory.delete() }
        }
    }
}
