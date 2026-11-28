package pw.pawchive.app

import android.content.ContextWrapper
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import pw.pawchive.data.*
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class NovelSearchPipelineTest {
    @Test fun publishOnlyAcceptedRowsAndKeepTheirOrder(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val vault = File(target.cacheDir, "novel-fixture-vault").apply { mkdirs() }
        val context = object : ContextWrapper(target) { override fun getNoBackupFilesDir() = vault }
        val db = Room.inMemoryDatabaseBuilder(context, PawDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val requests = AtomicInteger()
        val completeNovel = CountDownLatch(1)
        val novel = Post("novel", "same-author", "qa", title = "第十章", content = "<p>「${"字".repeat(750)}」</p>".repeat(8), file = RemoteFile("cover.jpg", "/cover.jpg"))
        val art = novel.copy(id = "art", title = "イラスト", content = "<p>一张画作</p>")
        val video = novel.copy(id = "video", file = RemoteFile("clip.mp4", "/clip.mp4"))
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests.incrementAndGet()
            val post = if (chain.request().url.encodedPath.endsWith("/novel")) {
                if (!completeNovel.await(6, TimeUnit.SECONDS)) throw java.io.IOException("Test fixture timed out")
                novel
            } else art
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(wireJson.encodeToString(post).toResponseBody("application/json".toMediaType())).build()
        }.build()
        val repo = PawRepository(context, scope, client, db)
        val previous = repo.hideSuspectedNovels.first()
        val store = ViewModelStore()
        lateinit var vm: HomeViewModel
        try {
            repo.account.first { !it.restoring }
            repo.setHideSuspectedNovels(true)
            val rows = listOf(video, novel, art).map { it.compact() }
            val key = "public:home:JK:${wireJson.encodeToString(SearchFilters())}"
            repo.storeWorkPage(key, WorkSearchPage(rows, 3, false))
            repo.storePosts("public:home:popular", rows)
            repo.dao.metadata(Metadata("popular.checked", System.currentTimeMillis().toString()))
            instrumentation.runOnMainSync { vm = HomeViewModel(repo, SavedStateHandle(mapOf("query" to "JK")), 1); store.put("novel-fixture", vm) }
            withTimeout(10000) { vm.state.first { it.loading && it.posts.map(Post::id) == listOf("video") } }
            completeNovel.countDown()
            val filtered = withTimeout(10000) { vm.state.onEach {
                assertFalse("An unchecked novel appeared in the grid", it.posts.any { post -> post.id == "novel" })
            }.first { !it.loading } }
            assertEquals(listOf("video", "art"), filtered.posts.map(Post::id))
            assertEquals(2, requests.get()) // Video requires no inspection.
            val replay = mutableListOf<Post>()
            repo.filterSearchNovels(rows) { replay.addAll(it) }
            assertEquals(filtered.posts, replay)
            assertEquals(2, requests.get()) // Both positive and negative verdicts are cached.
            val fromSearch = mutableListOf<Post>()
            repo.filterSearchNovels(listOf(novel.copy(id = "new-novel"), art.copy(id = "new-art"))) { fromSearch.addAll(it) }
            assertEquals(listOf("new-art"), fromSearch.map(Post::id))
            assertEquals(2, requests.get()) // A search row with its body needs no detail request.
            assertTrue(repo.dao.collection("guest", "blocked").isEmpty())
            instrumentation.runOnMainSync { vm.query("") }
            withTimeout(10000) { vm.state.first { !it.loading && it.posts.size == 3 } } // Popular is unaffected.
            repo.setHideSuspectedNovels(false)
            instrumentation.runOnMainSync { vm.query("JK") }
            withTimeout(10000) { vm.state.first { !it.loading && it.posts.size == 3 } }
        } finally {
            completeNovel.countDown()
            repo.setHideSuspectedNovels(previous)
            instrumentation.runOnMainSync { store.clear() }; scope.cancel(); db.close(); vault.delete()
        }
    }
}
