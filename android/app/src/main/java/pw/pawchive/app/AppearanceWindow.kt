package pw.pawchive.app

import android.app.UiModeManager
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import pw.pawchive.data.AppearancePreference

/** Suppress drawing during the one-time legacy migration, never paint an opposite-theme placeholder. */
internal class AppearanceWindow(private val activity: ComponentActivity, initial: AppearancePreference?) {
    private companion object { var reportedMode: Int? = null }
    private var ready = false
    init {
        val dark = initial?.dark ?: (activity.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES)
        activity.setTheme(if (dark) R.style.Theme_Pawchive_Dark else R.style.Theme_Pawchive)
    }

    fun awaitFirstComposition() {
        val view = activity.window.decorView
        view.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (!ready) return false
                view.viewTreeObserver.removeOnPreDrawListener(this)
                return true
            }
        })
    }

    fun apply(dark: Boolean, preference: Boolean?) {
        activity.setTheme(if (dark) R.style.Theme_Pawchive_Dark else R.style.Theme_Pawchive)
        activity.window.setBackgroundDrawable(ColorDrawable(if (dark) 0xFF19191D.toInt() else android.graphics.Color.WHITE))
        val transparent = android.graphics.Color.TRANSPARENT
        val bars = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent)
        activity.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        if (Build.VERSION.SDK_INT >= 31 && preference != null) {
            val manager = activity.getSystemService(UiModeManager::class.java)
            val mode = if (dark) UiModeManager.MODE_NIGHT_YES else UiModeManager.MODE_NIGHT_NO
            // Android 12+ persists this for the system-created splash on the next cold start.
            if (reportedMode != mode) {
                reportedMode = mode
                manager.setApplicationNightMode(mode)
            }
        }
        ready = true
    }
}
