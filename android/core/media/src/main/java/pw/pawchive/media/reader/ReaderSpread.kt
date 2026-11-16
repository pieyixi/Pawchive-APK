/* Pupil, Hitomi.la viewer for Android. Copyright (C) 2026 tom5079.
 * GPL-3.0-or-later; see third_party/hitomi/COPYING.
 * Adapted image acquisition for Pawchive. Immediate tap, zoom and long-press logic
 * are migrated from Hitomi ReaderSpread.kt. */
package pw.pawchive.media.reader

import android.net.Uri
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import coil3.request.ImageRequest
import coil3.size.Size
import kotlinx.coroutines.*
import me.saket.telephoto.zoomable.*
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage

private sealed interface SpreadImage {
    data object Loading : SpreadImage
    data class Failed(val message: String) : SpreadImage
    data class Ready(val uri: Uri) : SpreadImage
}

@Composable
fun ReaderSpread(source: PreviewReaderSource, pages: List<Int>, originalPage: Int,
    label: String, size: IntSize, contentScale: ContentScale,
    onLongPress: (Int) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    val currentPages by rememberUpdatedState(pages)
    var width by remember { mutableIntStateOf(0) }
    var retry by remember { mutableIntStateOf(0) }
    val onLongClick = remember { { offset: Offset -> currentOnLongPress(pressedPage(currentPages, offset.x / width.coerceAtLeast(1))) } }
    val image by produceState<SpreadImage>(SpreadImage.Loading, source, pages, originalPage, retry) {
        value = SpreadImage.Loading
        try {
            val images = pages.map { source.pageFile(it, it == originalPage) }
            value = SpreadImage.Ready(source.spreadImage(pages, images))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            value = SpreadImage.Failed(e.message ?: "图片暂不可用")
        }
    }
    Box(modifier.onSizeChanged { width = it.width }, contentAlignment = Alignment.Center) {
        when (val current = image) {
            is SpreadImage.Ready -> {
                val request = remember(current.uri, size) {
                    ImageRequest.Builder(context).data(current.uri)
                        .size(Size(size.width.coerceAtLeast(1), size.height.coerceAtLeast(1)))
                        .placeholderMemoryCacheKey(source.files[pages.first()].thumbnail).build()
                }
                ZoomableAsyncImage(model = request, contentDescription = "第 $label 页",
                    modifier = Modifier.fillMaxSize(),
                    state = rememberZoomableImageState(rememberZoomableState(ZoomSpec(maxZoomFactor = if (pages.size > 1) 4f else 2f))),
                    contentScale = contentScale, onLongClick = onLongClick, onDoubleClick = NoZoomOnDoubleClick)
            }
            else -> Column(Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures(onLongPress = { currentOnLongPress(pressedPage(currentPages, it.x / size.width.coerceAtLeast(1))) })
            }, verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                if (current is SpreadImage.Failed) {
                    Text(current.message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 16.dp))
                    TextButton(onClick = { retry++ }) { Text(if (originalPage in pages) "重试原图" else "重试预览") }
                } else {
                    LinearProgressIndicator(); Text(label, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/**
 * Turns the page on the tap itself rather than on what the tap turns out to be.
 *
 * Telephoto holds a tap for the length of the double tap window before reporting
 * it, which a page turn that slides covers with its animation and one that does
 * not leaves as a fifth of a second of nothing. The tap is read here first, on
 * the initial pass, and nothing is consumed, so panning and pinching still reach
 * the image below. Double tapping to zoom is given up for it -- see
 * [NoZoomOnDoubleClick].
 */
internal suspend fun PointerInputScope.turnOnTap(onTap: (Offset) -> Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)

        val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)

                if (event.changes.size > 1) return@withTimeoutOrNull null

                val change = event.changes.first()

                if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop)
                    return@withTimeoutOrNull null

                if (change.changedToUpIgnoreConsumed()) {
                    // Let scroll/zoom consume this UP before starting a new page scroll.
                    awaitPointerEvent(PointerEventPass.Final)
                    return@withTimeoutOrNull change
                }
            }

            @Suppress("UNREACHABLE_CODE")
            null
        }

        if (up != null) onTap(down.position)
    }
}

/**
 * A double tap is two page turns now that the first one is acted on at once, so
 * it no longer zooms as well. Pinching still does.
 */
private object NoZoomOnDoubleClick : DoubleClickToZoomListener {
    override suspend fun onDoubleClick(state: ZoomableState, centroid: Offset) = Unit
}

/** The page of a spread under a press, from the fraction across it. */
private fun pressedPage(pages: List<Int>, x: Float) = pages[if (x < 0.5f) 0 else pages.lastIndex]
