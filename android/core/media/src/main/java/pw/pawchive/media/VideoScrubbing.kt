package pw.pawchive.media

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs
import kotlin.math.roundToLong

private class ScrubActions(
    val start: () -> Long?, val duration: () -> Long,
    val preview: (Long) -> Unit, val finish: () -> Unit,
    val cancel: () -> Unit, val tap: (Offset) -> Unit,
)

/** Surface: relative 100s per viewport, measured on device; track: absolute full duration. */
@Composable
internal fun Modifier.videoScrubInput(
    enabled: Boolean, track: Boolean = false, onStart: () -> Long?, duration: () -> Long,
    onPreview: (Long) -> Unit, onFinish: () -> Unit, onCancel: () -> Unit,
    onTap: (Offset) -> Unit = {},
    onVolumeStart: (Offset, Int) -> Boolean = { _, _ -> false },
    onVolumeDrag: (Float) -> Unit = {},
    onVolumeEnd: () -> Unit = {},
): Modifier {
    val actions by rememberUpdatedState(ScrubActions(onStart, duration, onPreview, onFinish, onCancel, onTap))
    val volumeStart by rememberUpdatedState(onVolumeStart)
    val volumeDrag by rememberUpdatedState(onVolumeDrag)
    val volumeEnd by rememberUpdatedState(onVolumeEnd)
    return pointerInput(enabled, track) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            // A child button or the track owns its gesture before the surrounding surface.
            if (down.isConsumed) return@awaitEachGesture
            if (track && enabled) down.consume()
            var active = false
            var volume = false
            var finished = false
            var base = 0L
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (change.isConsumed || event.changes.any { it.id != down.id && it.pressed }) break
                    val delta = change.position - down.position
                    if (!change.pressed) {
                        if (volume) {
                            change.consume()
                        } else if (active) {
                            change.consume(); finished = true; actions.finish()
                        } else if (delta.getDistance() <= viewConfiguration.touchSlop) {
                            if (track && enabled) {
                                if (actions.start() != null) {
                                    actions.preview((change.position.x / size.width.coerceAtLeast(1) * actions.duration()).roundToLong())
                                    actions.finish()
                                }
                                change.consume()
                            } else if (!track) { change.consume(); actions.tap(change.position) }
                        }
                        break
                    }
                    if (!active && !volume) {
                        if (abs(delta.y) > viewConfiguration.touchSlop && abs(delta.y) > abs(delta.x)) {
                            if (track || !volumeStart(down.position, size.width)) break
                            volume = true
                        }
                    }
                    if (volume) {
                        change.consume()
                        // Slop decides ownership. Once claimed, count the entire drag.
                        volumeDrag(-delta.y / size.height.coerceAtLeast(1))
                        continue
                    }
                    if (!active) {
                        if (abs(delta.x) <= viewConfiguration.touchSlop) continue
                        if (!enabled) break
                        base = actions.start() ?: break
                        active = true
                    }
                    change.consume()
                    val width = size.width.coerceAtLeast(1).toFloat()
                    val target = if (track) (change.position.x / width * actions.duration()).roundToLong()
                        else ((base + delta.x / width * 100_000.0) / 1000).roundToLong() * 1000
                    actions.preview(target)
                }
            } finally {
                if (volume) volumeEnd()
                if (active && !finished) actions.cancel()
            }
        }
    }
}
