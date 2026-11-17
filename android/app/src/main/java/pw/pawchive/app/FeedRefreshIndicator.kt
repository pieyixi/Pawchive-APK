package pw.pawchive.app

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private enum class RefreshPhase { Idle, Pulling, Refreshing, Completing, Exiting }

/** The same arc stays mounted through pulling, completion and retraction. No opaque surface. */
@Composable
internal fun FeedRefreshIndicator(isRefreshing: Boolean, distanceFraction: () -> Float, modifier: Modifier = Modifier) {
    var phase by remember { mutableStateOf(if (isRefreshing) RefreshPhase.Refreshing else RefreshPhase.Idle) }
    val position = remember { Animatable(if (isRefreshing) 1f else 0f) }
    val angle = remember { Animatable(-90f) }
    val sweep = remember { Animatable(70f) }
    val latestDistance by rememberUpdatedState(distanceFraction)
    val latestRefreshing by rememberUpdatedState(isRefreshing)
    val hasPull by remember { derivedStateOf { latestDistance() > 0f } }
    val latestHasPull by rememberUpdatedState(hasPull)

    LaunchedEffect(isRefreshing, hasPull) {
        when {
            isRefreshing && phase != RefreshPhase.Refreshing -> {
                if (phase == RefreshPhase.Idle || phase == RefreshPhase.Pulling) {
                    position.snapTo(latestDistance().coerceIn(0f, 1.5f))
                }
                phase = RefreshPhase.Refreshing
            }
            !isRefreshing && phase == RefreshPhase.Refreshing -> phase = RefreshPhase.Completing
            hasPull && phase == RefreshPhase.Idle -> phase = RefreshPhase.Pulling
            !hasPull && phase == RefreshPhase.Pulling -> phase = RefreshPhase.Idle
        }
    }
    LaunchedEffect(phase) {
        when (phase) {
            RefreshPhase.Pulling, RefreshPhase.Refreshing -> coroutineScope {
                if (phase == RefreshPhase.Refreshing) launch { position.animateTo(1f, tween(160, easing = FastOutSlowInEasing)) }
                launch { while (true) angle.animateTo(angle.value + 360f, tween(1100, easing = LinearEasing)) }
                launch { while (true) {
                    sweep.animateTo(285f, tween(680, easing = FastOutSlowInEasing))
                    sweep.animateTo(55f, tween(680, easing = FastOutSlowInEasing))
                } }
            }
            RefreshPhase.Completing -> {
                // Continue from the exact visible arc; do not replace it with a second indicator.
                coroutineScope {
                    launch { position.animateTo(1f, tween(100, easing = FastOutSlowInEasing)) }
                    launch { angle.animateTo(angle.value + 60f, tween(240, easing = FastOutSlowInEasing)) }
                    launch { sweep.animateTo(360f, tween(240, easing = FastOutSlowInEasing)) }
                }
                phase = RefreshPhase.Exiting
            }
            RefreshPhase.Exiting -> {
                position.animateTo(-.15f, keyframes {
                    durationMillis = 360
                    1f at 60 using FastOutSlowInEasing
                    -.15f at 360
                })
                phase = when { latestRefreshing -> RefreshPhase.Refreshing; latestHasPull -> RefreshPhase.Pulling; else -> RefreshPhase.Idle }
            }
            RefreshPhase.Idle -> Unit
        }
    }
    if (phase == RefreshPhase.Idle && !isRefreshing && !hasPull) return
    Box(modifier.offset {
        val fraction = if (phase == RefreshPhase.Pulling || phase == RefreshPhase.Idle) latestDistance().coerceIn(0f, 1.5f) else position.value
        IntOffset(0, (fraction * 60 - 48).dp.roundToPx())
    }.size(44.dp).testTag("feed_refresh_spinner").semantics {
        stateDescription = if (phase == RefreshPhase.Completing || phase == RefreshPhase.Exiting) "刷新完成" else "正在刷新"
    }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(26.dp)) {
            val stroke = 2.5.dp.toPx()
            drawArc(Pink, angle.value, sweep.value, false,
                topLeft = Offset(stroke / 2, stroke / 2), size = Size(size.width - stroke, size.height - stroke),
                style = Stroke(stroke, cap = StrokeCap.Round))
        }
    }
}
