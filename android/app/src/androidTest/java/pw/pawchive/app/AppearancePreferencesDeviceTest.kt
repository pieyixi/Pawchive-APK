package pw.pawchive.app

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import pw.pawchive.data.AppearancePreferences

class AppearancePreferencesDeviceTest {
    @Test fun migratesLegacyDarkOnceAndKeepsChangesAcrossNewInstances() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "appearance-migration-test"
        context.deleteSharedPreferences(name)
        try {
            val first = AppearancePreferences(context, name)
            assertNull(first.state.value)
            first.migrate { true }
            assertEquals(true, first.state.value?.dark)
            val restored = AppearancePreferences(context, name)
            assertEquals(true, restored.state.value?.dark)
            restored.migrate { error("Legacy settings must not overwrite the new preference") }
            restored.setDark(false)
            assertEquals(false, AppearancePreferences(context, name).state.value?.dark)
            restored.setDark(true)
            assertEquals(true, AppearancePreferences(context, name).state.value?.dark)
        } finally { context.deleteSharedPreferences(name) }
    }

    @Test fun followsSystemIsReadyAndDiffersFromMigrationPending() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "appearance-system-test"
        context.deleteSharedPreferences(name)
        try {
            val preferences = AppearancePreferences(context, name)
            assertNull(preferences.state.value)
            preferences.migrate { null }
            assertNotNull(preferences.state.value)
            assertNull(preferences.state.value?.dark)
            val restored = AppearancePreferences(context, name)
            assertNotNull(restored.state.value)
            assertNull(restored.state.value?.dark)
        } finally { context.deleteSharedPreferences(name) }
    }
}
