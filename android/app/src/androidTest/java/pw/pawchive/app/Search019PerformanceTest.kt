package pw.pawchive.app

import android.content.ContextWrapper
import android.os.Bundle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import pw.pawchive.data.*
import java.io.File
import java.util.Collections

@RunWith(AndroidJUnit4::class)
class Search019PerformanceTest {
    @Test fun returningToAnAttachmentFilterUsesExactCachedCursorWithoutRequest(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val vault = File(target.cacheDir, "019-cache-fixture-vault").apply { mkdirs() }
        val context = object : ContextWrapper(target) { override fun getNoBackupFilesDir() = vault }
        val db = Room.inMemoryDatabaseBuilder(context, PawDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("/api/v1/posts", chain.request().url.encodedPath)
            val query = chain.request().url.queryParameter("q").orEmpty()
            requests += query
            val media = if ("has:video" in query) "video" else "image"
            val extension = if (media == "video") "mp4" else "png"
            val posts = (0..49).map { Post("$media-$it", "qa", "qa", content = "<p>Fixture preview</p>", file = RemoteFile("file.$extension", "/$it.$extension")) }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(wireJson.encodeToString(posts).toResponseBody("application/json".toMediaType())).build()
        }.build()
        val repo = PawRepository(context, scope, client, db)
        val store = ViewModelStore()
        lateinit var vm: HomeViewModel
        try {
            repo.account.first { !it.restoring }
            val popular = (0..49).map { Post("popular-$it", "qa", "qa") }
            repo.storePosts("public:home:popular", popular)
            repo.dao.metadata(Metadata("popular.checked", System.currentTimeMillis().toString()))
            instrumentation.runOnMainSync { vm = HomeViewModel(repo, SavedStateHandle(), 1); store.put("cache-fixture", vm) }
            withTimeout(10000) { vm.state.first { !it.loading && it.posts.isNotEmpty() } }
            instrumentation.runOnMainSync { vm.query("JK"); vm.filters(SearchFilters(media = "image")) }
            withTimeout(10000) { vm.state.first { !it.loading && it.posts.firstOrNull()?.id == "image-0" } }
            instrumentation.runOnMainSync { vm.filters(SearchFilters(media = "video")) }
            withTimeout(10000) { vm.state.first { !it.loading && it.posts.firstOrNull()?.id == "video-0" } }
            assertEquals(2, requests.size)
            val before = android.os.SystemClock.elapsedRealtime()
            instrumentation.runOnMainSync { vm.filters(SearchFilters(media = "image")) }
            withTimeout(10000) { vm.state.first { !it.loading && it.posts.firstOrNull()?.id == "image-0" } }
            assertEquals(2, requests.size)
            val switchMs = android.os.SystemClock.elapsedRealtime() - before
            val key = "public:home:JK:${wireJson.encodeToString(SearchFilters(media = "image"))}"
            val cached = repo.cachedWorkPage(key)!!
            assertEquals((0..49).map { "image-$it" }, cached.page.rawPosts.map { it.id })
            assertEquals(50, cached.page.nextOffset); assertTrue(cached.page.more)
            repo.storeWorkPage("public:home:empty-fixture", WorkSearchPage(emptyList(), 0, false))
            assertNotNull(repo.cachedWorkPage("public:home:empty-fixture"))
            repo.dao.clearPosts(key)
            assertNull(repo.cachedWorkPage(key)) // A trimmed page must not masquerade as an empty result.
            instrumentation.addResults(Bundle().apply { putString("cachedFilterSwitchMs", switchMs.toString()); putString("coldRequestsThenCachedRequests", "2+0") })
        } finally { instrumentation.runOnMainSync { store.clear() }; scope.cancel(); db.close(); vault.delete() }
    }

    @Test fun measurePreparedLocalAuthorSorts(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val repository = (instrumentation.targetContext.applicationContext as PawApplication).container.repository
        val count = repository.dao.creatorCount()
        assumeTrue("Requires an existing public creator index", count > 0)
        val timings = mutableListOf<String>()
        for (sort in listOf("name", "updated", "indexed", "hot")) {
            val options = SearchFilters(sort = sort, order = if (sort == "name") "asc" else "desc")
            val started = android.os.SystemClock.elapsedRealtime()
            repository.prepareAuthorFilters(options)
            val page = repository.searchAuthors("", filters = options)
            timings += "$sort:${android.os.SystemClock.elapsedRealtime() - started}ms"
            assertTrue(page.isNotEmpty()); assertEquals(minOf(count, 50), page.size)
        }
        instrumentation.addResults(Bundle().apply { putString("localAuthorSortTiming", timings.joinToString(",")); putString("publicCreatorIndexCount", count.toString()) })
    }
}
