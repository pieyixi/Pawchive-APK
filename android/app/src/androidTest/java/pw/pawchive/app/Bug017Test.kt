package pw.pawchive.app

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import pw.pawchive.data.*

@RunWith(AndroidJUnit4::class)
class Bug017Test {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun blockedFirstPageStillLoadsOtherAuthorsOnNextPage() {
        val isolatedVault = java.io.File(compose.activity.cacheDir, "017-isolated-vault").apply { mkdirs() }
        val context = object : android.content.ContextWrapper(compose.activity) {
            override fun getNoBackupFilesDir() = isolatedVault
        }
        val database = androidx.room.Room.inMemoryDatabaseBuilder(context, PawDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val client = okhttp3.OkHttpClient.Builder().addInterceptor { throw java.io.IOException("Unexpected network in cached pagination fixture") }.build()
        val repo = PawRepository(context, scope, client, database)
        try {
            runBlocking {
                repo.account.first { !it.restoring }
                val authors = listOf("a", "b", "c", "d").map { Creator(it, "qa", "Author $it") }
                repo.dao.creators(authors.map { CreatorRow(it.key, it.name, it.service, 0, wireJson.encodeToString(it)) })
                repo.dao.metadata(Metadata("index.checked", System.currentTimeMillis().toString()))
                val posts = (0..100).map { index ->
                    val author = when { index < 40 -> "a"; index < 50 -> "b"; index < 100 -> "c"; else -> "d" }
                    Post("017-$index", author, "qa", title = "Visible $index")
                }
                repo.dao.posts(posts.mapIndexed { index, post -> PostRow("public:home:popular", post.key, -index.toLong(), wireJson.encodeToString(post)) })
                repo.dao.metadata(Metadata("popular.checked", System.currentTimeMillis().toString()))
                repo.blockAuthor(authors.first(), true)
            }
            lateinit var vm: HomeViewModel
            compose.runOnUiThread {
                val app = androidx.lifecycle.ViewModelProvider(compose.activity, vmFactory { AppViewModel(repo) })["017-app", AppViewModel::class.java]
                compose.activity.setContent { PawTheme(true) { HomeScreen(app, {}, {}, {}) } }
            }
            compose.waitForIdle()
            compose.runOnUiThread { vm = androidx.lifecycle.ViewModelProvider(compose.activity)["home-page-1", HomeViewModel::class.java] }
            compose.waitUntil(10000) { !vm.state.value.loading && vm.state.value.posts.size >= 100 }
            compose.onNodeWithTag("home_grid_1").performScrollToIndex(10)
            compose.onNodeWithText("Visible 50").assertIsDisplayed()
            compose.onNodeWithText("Visible 0").assertDoesNotExist()
        } finally {
            compose.runOnUiThread { compose.activity.setContent {}; compose.activity.viewModelStore.clear() }
            scope.cancel(); database.close(); isolatedVault.delete()
        }
    }
    @Test fun legacyBlockIsAuthorAndNewBlockDoesNotStorePost() = runBlocking {
        val repo = (compose.activity.application as PawApplication).container.repository
        repo.account.first { !it.restoring }
        val owner = repo.account.value.owner
        val post = Post("017-one", "017-author", "qa", title = "Must not be author name")
        try {
            repo.dao.collect(CollectionRow(owner, "blocked", post.authorKey, 1, wireJson.encodeToString(post)))
            val legacy = repo.blockedAuthors().first().first { it.key == post.authorKey }
            assertEquals(post.user, legacy.id); assertNotEquals(post.title, legacy.name)
            repo.localCollection("blocked", post.copy(id = "017-two"), true)
            val rows = repo.dao.collection(owner, "blocked").filter { it.key == post.authorKey }
            assertEquals(1, rows.size)
            assertEquals(post.user, wireJson.decodeFromString<Creator>(rows.single().payload).id)
        } finally { repo.localCollection("blocked", post, false) }
    }
    @Test fun clearingRecordsPreservesActiveAndFile() = runBlocking {
        val database = androidx.room.Room.inMemoryDatabaseBuilder(compose.activity, PawDatabase::class.java).build()
        val dao = database.dao()
        val file = java.io.File(compose.activity.cacheDir, "017-record-file").apply { writeText("keep") }
        val completed = DownloadRow("017-complete", "fixture.txt", "https://example.invalid", file.toURI().toString(), status = "complete")
        val running = completed.copy(id = "017-running", status = "running")
        val queued = completed.copy(id = "017-queued", status = "queued")
        try {
            dao.download(completed); dao.download(running); dao.download(queued)
            dao.removeDownloadRecord(running.id)
            assertNotNull(dao.download(running.id))
            dao.clearDownloadRecords()
            assertNotNull(dao.download(running.id))
            assertNull(dao.download(completed.id)); assertEquals("keep", file.readText())
            assertNotNull(dao.download(queued.id))
        } finally {
            database.close(); file.delete()
        }
    }
    @Test fun emptyMediaHasOnlyCounts() {
        compose.runOnUiThread { compose.activity.setContent { PawTheme(true) { DetailMediaLayout(Post("017", "qa", "qa"), {}, {}) {} } } }
        compose.onNodeWithText("0 张").assertIsDisplayed()
        compose.onNodeWithText("没有图片").assertDoesNotExist()
        compose.onNodeWithText("视频").performClick()
        compose.onNodeWithText("0 个").assertIsDisplayed()
        compose.onNodeWithText("没有视频").assertDoesNotExist()
    }
    @Test fun keyboardDoesNotLiftNavigation() {
        compose.onNode(hasSetTextAction()).performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("bottom_navigation").fetchSemanticsNodes().isEmpty() }
        Espresso.pressBack()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("bottom_navigation").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("bottom_navigation").assertIsDisplayed()
    }
    @Test fun unblockMessageDisappearsBeforeOldFourSecondTimeout() {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val manager = compose.activity.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
            org.junit.Assume.assumeTrue(manager.getRecommendedTimeoutMillis(1600, android.view.accessibility.AccessibilityManager.FLAG_CONTENT_TEXT) <= 1600)
        }
        compose.onNodeWithText("作者").assertIsDisplayed()
        val app = androidx.lifecycle.ViewModelProvider(compose.activity)[AppViewModel::class.java]
        runBlocking { app.messages.emit("已取消屏蔽") }
        compose.waitUntil(5000) { compose.onAllNodesWithText("已取消屏蔽").fetchSemanticsNodes().isNotEmpty() }
        val start = android.os.SystemClock.elapsedRealtime()
        compose.waitUntil(3500) { compose.onAllNodesWithText("已取消屏蔽").fetchSemanticsNodes().isEmpty() }
        assertTrue(android.os.SystemClock.elapsedRealtime() - start < 3500)
    }
    @androidx.test.filters.SdkSuppress(minSdkVersion = 29)
    @Test fun completedDownloadOpensWithReadGrantAndClearsOnlyRecord() {
        org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 29)
        val container = (compose.activity.application as PawApplication).container
        val repo = container.repository
        val resolver = compose.activity.contentResolver
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "pawchive-017-open-fixture.zip")
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
        }
        val uri = requireNotNull(resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val captured = java.util.concurrent.atomic.AtomicReference<android.content.Intent?>()
        val monitor = object : android.app.Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: android.content.Intent): android.app.Instrumentation.ActivityResult? {
                if (intent.action != android.content.Intent.ACTION_CHOOSER) return null
                captured.set(intent.getParcelableExtra<android.content.Intent>(android.content.Intent.EXTRA_INTENT))
                return android.app.Instrumentation.ActivityResult(0, null)
            }
        }
        try {
            resolver.openOutputStream(uri)!!.use { stream -> stream.write(byteArrayOf(0x50, 0x4b, 0x05, 0x06) + ByteArray(18)) }
            runBlocking { repo.dao.download(DownloadRow("017-open", "017-open.zip", "https://example.invalid", uri.toString(), status = "complete")) }
            instrumentation.addMonitor(monitor)
            compose.runOnUiThread {
                val app = androidx.lifecycle.ViewModelProvider(compose.activity, vmFactory { AppViewModel(repo) })["017-download", AppViewModel::class.java]
                compose.activity.setContent { PawTheme(true) { TransferScreen(app, container.downloads) {} } }
            }
            compose.waitUntil(5000) { compose.onAllNodesWithText("017-open.zip").fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithText("打开").onFirst().performClick()
            compose.waitUntil(5000) { captured.get() != null }
            assertEquals(uri, captured.get()!!.data)
            assertEquals("application/zip", captured.get()!!.type)
            assertTrue(captured.get()!!.flags and android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            compose.onAllNodesWithText("清除记录").onFirst().performClick()
            compose.waitUntil(5000) { runBlocking { repo.dao.download("017-open") == null } }
            resolver.openAssetFileDescriptor(uri, "r")!!.close()
        } finally {
            instrumentation.removeMonitor(monitor)
            runBlocking { repo.dao.removeDownloadRecord("017-open") }
            resolver.delete(uri, null, null)
        }
    }
}
