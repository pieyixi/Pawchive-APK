package pw.pawchive.app

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Explicitly invoked after the user signs in. Read-only; never prints or transfers credentials. */
@RunWith(AndroidJUnit4::class)
class AccountRestoreDeviceTest {
    @Test fun encryptedSessionRestoresAndWebsiteCollectionsRemainReadable(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as PawApplication
        val repository = application.container.repository
        val state = withTimeout(30000) { repository.account.first { !it.restoring } }
        assertNotNull("An existing device sign-in is required", state.username)
        // Real authenticated GET only: an expired/rejected session fails this call.
        withTimeout(60000) { repository.syncFavorites() }
        assertTrue("Session owner changed during validation", state.owner == repository.account.value.owner)
        val authors = repository.followed().first().size
        val favorites = repository.collected("favorite").first().size
        val encrypted = File(application.noBackupFilesDir, "session.aesgcm").readBytes()
        assertTrue("Encrypted session payload missing", encrypted.size > 28)
        assertFalse("Session payload contains plaintext identity", encrypted.decodeToString().contains(state.username!!))
        instrumentation.addResults(Bundle().apply {
            putString("encryptedSessionRestored", "true")
            putString("authenticatedCollectionsReadable", "true")
            putString("followedCreators", authors.toString())
            putString("favoritePosts", favorites.toString())
        })
    }
}
