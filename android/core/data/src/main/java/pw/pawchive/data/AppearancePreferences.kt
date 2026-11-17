package pw.pawchive.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

data class AppearancePreference(val dark: Boolean?)

/** One small, authoritative preference available before an Activity's first frame.
 * A missing record means migration is pending; a stored -1 means follow system.
 */
class AppearancePreferences(context: Context, name: String = "appearance") {
    private val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(
        if (preferences.contains("mode")) AppearancePreference(when (preferences.getInt("mode", -1)) {
            0 -> false; 1 -> true; else -> null
        }) else null
    )
    val state = mutable.asStateFlow()

    suspend fun migrate(readLegacy: suspend () -> Boolean?) = mutex.withLock {
        if (mutable.value == null) persist(readLegacy())
    }

    suspend fun setDark(dark: Boolean) = mutex.withLock { persist(dark) }

    private suspend fun persist(dark: Boolean?) = withContext(Dispatchers.IO) {
        if (!preferences.edit().putInt("mode", when (dark) { true -> 1; false -> 0; null -> -1 }).commit()) {
            throw IOException("无法保存主题设置")
        }
        // Publish only after it is durable, so a process restart cannot undo a visible toggle.
        mutable.value = AppearancePreference(dark)
    }
}
