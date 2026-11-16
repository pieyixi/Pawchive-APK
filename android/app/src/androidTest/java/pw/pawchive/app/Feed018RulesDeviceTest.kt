package pw.pawchive.app

import android.content.ContextWrapper
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
import org.junit.Test
import org.junit.runner.RunWith
import pw.pawchive.data.*
import java.io.File
import java.io.IOException
import java.util.Collections

@RunWith(AndroidJUnit4::class)
class Feed018RulesDeviceTest {
    @Test fun normalRefreshRequestsOnlyChangedFollowedCreatorAndRetryRetainsCache(): Unit = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val vault = File(app.cacheDir, "018-fixture-vault").apply { mkdirs() }
        val context = object : ContextWrapper(app) { override fun getNoBackupFilesDir() = vault }
        val db = Room.inMemoryDatabaseBuilder(context, PawDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var updated = 2000L
        var fail = false
        var resumeFixture = false
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val fresh = Post("late-import", "one", "qa", published = JsonPrimitive(500), added = JsonPrimitive(2000))
        val old = Post("previous", "one", "qa", published = JsonPrimitive(1500), added = JsonPrimitive(1500))
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url
            val json = when (url.encodedPath) {
                "/api/v1/account/favorites" -> {
                    assertEquals("artist", url.queryParameter("type")); requests += "followed"
                    wireJson.encodeToString(listOf(Creator("one", "qa", "One", updated = JsonPrimitive(updated))))
                }
                "/api/v1/qa/user/one" -> {
                    requests += "posts:${url.queryParameter("o") ?: "0"}"
                    if (fail) throw IOException("Fixture unavailable")
                    wireJson.encodeToString(if (!resumeFixture) listOf(fresh, old) else if ((url.queryParameter("o") ?: "0") == "0")
                        listOf(fresh) + (1..49).map { old.copy(id = "page-$it") } else emptyList())
                }
                else -> throw AssertionError("Unexpected global index / favorite-post request: ${url.encodedPath}")
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(json.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val repo = PawRepository(context, scope, client, db, client)
        try {
            repo.account.first { !it.restoring }
            repo.account.value = AccountState("qa-018", false)
            val cache = "account:qa-018:feed"
            repo.storePosts(cache, listOf(old))
            repo.dao.mark(SyncMark(cache, "qa:one", 1_000_000, 0))
            repo.refreshFeed()
            assertNull(repo.feedStatus.value.error)
            assertEquals(listOf("followed", "posts:0"), requests.toList())
            assertEquals(fresh.key, repo.feed().first().first().key)
            assertEquals(1, repo.feedMetrics.value.postRequests)
            requests.clear()
            repo.refreshFeed()
            assertEquals(listOf("followed"), requests.toList())
            assertEquals(0, repo.feedMetrics.value.postRequests)
            val mark = repo.dao.marks(cache).single()
            updated = 3000; fail = true
            repo.refreshFeed()
            assertNotNull(repo.feedStatus.value.error)
            assertEquals(mark, repo.dao.marks(cache).single())
            assertEquals(fresh.key, repo.feed().first().first().key)
            fail = false; requests.clear(); repo.refreshFeed()
            assertEquals(listOf("followed", "posts:0"), requests.toList())
            assertEquals(3_000_000, repo.dao.marks(cache).single().updated)
            repo.dao.metadata(Metadata("feed.backfill:$cache:qa:one", "3000000,150"))
            updated = 4000; resumeFixture = true; requests.clear(); repo.refreshFeed()
            assertEquals(listOf("followed", "posts:0", "posts:150"), requests.toList())
            assertNull(repo.dao.metadata("feed.backfill:$cache:qa:one"))
        } finally { scope.cancel(); db.close(); vault.delete() }
    }

    @Test fun authorFiltersUseStableSqlOrderingAndWebsiteAiTags(): Unit = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val vault = File(app.cacheDir, "018-filter-fixture-vault").apply { mkdirs() }
        val context = object : ContextWrapper(app) { override fun getNoBackupFilesDir() = vault }
        val db = Room.inMemoryDatabaseBuilder(context, PawDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val repo = PawRepository(context, scope, database = db)
        try {
            val authors = listOf(Creator("1", "fanbox", "Alpha"), Creator("2", "patreon", "Beta"), Creator("3", "fanbox", "Gamma"))
            repo.dao.creators(authors.mapIndexed { i, a -> CreatorRow(a.key, a.name, a.service, i.toLong(), wireJson.encodeToString(a), (2-i).toLong(), (i*10).toLong()) })
            repo.dao.metadata(Metadata("creator.ai:fanbox:3", "1"))
            assertEquals(listOf("Gamma", "Alpha"), repo.searchAuthors("", filters = SearchFilters(service = "fanbox", sort = "updated", order = "desc")).map { it.name })
            assertEquals(listOf("Alpha", "Beta"), repo.searchAuthors("", filters = SearchFilters(ai = "hide")).map { it.name })
            assertEquals(listOf("Gamma"), repo.searchAuthors("", filters = SearchFilters(ai = "only")).map { it.name })
            assertEquals(listOf("Gamma", "Beta", "Alpha"), repo.searchAuthors("", filters = SearchFilters(sort = "hot", order = "desc")).map { it.name })
            assertTrue(repo.searchAuthors("%' OR 1=1 --").isEmpty())
        } finally { scope.cancel(); db.close(); vault.delete() }
    }
}
