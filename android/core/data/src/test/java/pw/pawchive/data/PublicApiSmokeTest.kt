package pw.pawchive.data

import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit opt-in, read-only: no real account or original files are accessed. */
class PublicApiSmokeTest {
    @Test fun publicListsProfileDetailAndThumbnail(): Unit = runBlocking {
        assumeTrue(System.getenv("PAWCHIVE_LIVE_SMOKE") == "1")
        val client = siteClient(); val api = pawApi(client)
        val list = api.posts(); assertTrue(list.isNotEmpty())
        val first = list.first()
        val profile = api.author(first.service, first.user); assertEquals(first.user, profile.id)
        val detail = api.post(first.service, first.user, first.id); assertEquals(first.key, detail.key)
        assertTrue(api.authorPosts(first.service, first.user).isNotEmpty())
        detail.images.firstOrNull()?.let { image ->
            client.newCall(Request.Builder().url(image.thumbnail).build()).execute().use { response ->
                assertTrue("thumbnail status ${response.code}", response.isSuccessful); assertTrue(response.body!!.bytes().isNotEmpty())
            }
        }
    }
}
