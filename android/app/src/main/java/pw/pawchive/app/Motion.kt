package pw.pawchive.app

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.IntOffset

/** Shared parent/child motion; NavDisplay owns interruption and predictive progress. */
internal object PawMotion {
    fun page() = tween<IntOffset>(durationMillis = 220, easing = FastOutSlowInEasing)
    fun <T> section() = tween<T>(durationMillis = 220, easing = FastOutSlowInEasing)
}
