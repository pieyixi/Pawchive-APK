/*
 *     Pupil, Hitomi.la viewer for Android
 *     Copyright (C) 2019  tom5079
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package pw.pawchive.media.reader

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridLayoutInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.roundToInt

private val TouchWidth = 48.dp
private val TouchLength = 64.dp
private val EndInset = 10.dp

private val Thickness = 6.dp
private val DraggedThickness = 10.dp

private val ThumbLength = 48.dp

private const val ThumbVisibilityDuration = 1000L

/**
 * A draggable thumb along the end edge of a [LazyListState]'s list, shown while
 * the list scrolls and for [ThumbVisibilityDuration] after. [modifier] sizes the
 * track the thumb travels.
 */
@Composable
fun FastScroller(state: LazyListState, modifier: Modifier = Modifier) {
    val scrollable by remember(state) {
        derivedStateOf { state.canScrollBackward || state.canScrollForward }
    }
    val scrollFraction by remember(state) { derivedStateOf { state.scrollFraction() } }

    FastScroller(
        scrollable = scrollable,
        scrollFraction = scrollFraction,
        scrolling = state.isScrollInProgress,
        onScrollToFraction = state::scrollToFraction,
        modifier = modifier,
    )
}

/** The same thumb for a grid, which travels by rows rather than by items. */
@Composable
fun FastScroller(state: LazyGridState, modifier: Modifier = Modifier) {
    val scrollable by remember(state) {
        derivedStateOf { state.canScrollBackward || state.canScrollForward }
    }
    val scrollFraction by remember(state) { derivedStateOf { state.scrollFraction() } }

    FastScroller(
        scrollable = scrollable,
        scrollFraction = scrollFraction,
        scrolling = state.isScrollInProgress,
        onScrollToFraction = state::scrollToFraction,
        modifier = modifier,
    )
}

@Composable
private fun FastScroller(
    scrollable: Boolean,
    scrollFraction: Float,
    scrolling: Boolean,
    onScrollToFraction: suspend (Float) -> Unit,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val thumbLength = with(density) { ThumbLength.toPx() }
    val touchLength = with(density) { TouchLength.toPx() }

    var trackHeight by remember { mutableIntStateOf(0) }

    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }

    val active = dragging || scrolling
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(active) {
        if (active) visible = true
        else {
            delay(ThumbVisibilityDuration)
            visible = false
        }
    }

    val thumbAlpha by animateFloatAsState(
        targetValue = if (visible && scrollable) 1f else 0f,
        label = "FastScrollerAlpha",
    )
    val thickness by animateDpAsState(
        targetValue = if (dragging) DraggedThickness else Thickness,
        label = "FastScrollerThickness",
    )

    val draggableState = rememberDraggableState { delta ->
        val travel = trackHeight - thumbLength
        if (travel <= 0f) return@rememberDraggableState

        dragFraction = (dragFraction + delta / travel).coerceIn(0f, 1f)
        scope.launch { onScrollToFraction(dragFraction) }
    }

    fun thumbTop(): Float {
        val fraction = if (dragging) dragFraction else scrollFraction

        return fraction * (trackHeight - thumbLength)
    }

    fun touchTop(): Float =
        (thumbTop() - (touchLength - thumbLength) / 2f)
            .coerceIn(0f, (trackHeight - touchLength).coerceAtLeast(0f))

    Box(modifier.width(TouchWidth).onSizeChanged { trackHeight = it.height }) {
        if (thumbAlpha > 0f)
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(0, touchTop().roundToInt()) }
                    .size(width = TouchWidth, height = TouchLength)
                    .draggable(
                        state = draggableState,
                        orientation = Orientation.Vertical,
                        startDragImmediately = true,
                        onDragStarted = {
                            dragFraction = scrollFraction
                            dragging = true
                        },
                        onDragStopped = { dragging = false },
                    ),
            ) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset { IntOffset(0, (thumbTop() - touchTop()).roundToInt()) }
                        .padding(end = EndInset)
                        .size(width = thickness, height = ThumbLength)
                        .graphicsLayer { alpha = thumbAlpha }
                        .background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape),
                )
            }
    }
}

private val LazyListLayoutInfo.averageItemSpan: Float
    get() {
        val first = visibleItemsInfo.first()
        val last = visibleItemsInfo.last()

        return (last.offset + last.size - first.offset + mainAxisItemSpacing).toFloat() /
                visibleItemsInfo.size
    }

private fun LazyListLayoutInfo.scrollRange(itemSpan: Float): Float =
    totalItemsCount * itemSpan - mainAxisItemSpacing +
            beforeContentPadding + afterContentPadding -
            (viewportEndOffset - viewportStartOffset)

private fun LazyListState.scrollFraction(): Float {
    val info = layoutInfo

    return when {
        info.visibleItemsInfo.isEmpty() || !canScrollBackward -> 0f
        !canScrollForward -> 1f
        else -> {
            val itemSpan = info.averageItemSpan
            val scrolled = firstVisibleItemIndex * itemSpan + firstVisibleItemScrollOffset

            (scrolled / info.scrollRange(itemSpan)).coerceIn(0f, 1f)
        }
    }
}

private suspend fun LazyListState.scrollToFraction(fraction: Float) {
    val info = layoutInfo
    if (info.visibleItemsInfo.isEmpty()) return

    val itemSpan = info.averageItemSpan
    val target = fraction * info.scrollRange(itemSpan).coerceAtLeast(0f)
    val index = (target / itemSpan).toInt().coerceIn(0, info.totalItemsCount - 1)

    scrollToItem(index, (target - index * itemSpan).roundToInt())
}

// A grid scrolls by rows, so its measurements are per row: the columns of a row
// share one offset and travel together.
private val LazyGridLayoutInfo.columnCount: Int
    get() = visibleItemsInfo.maxOf { it.column } + 1

private val LazyGridLayoutInfo.averageRowSpan: Float
    get() {
        val first = visibleItemsInfo.first()
        val last = visibleItemsInfo.last()

        return (last.offset.y + last.size.height - first.offset.y + mainAxisItemSpacing).toFloat() /
                (last.row - first.row + 1)
    }

private fun LazyGridLayoutInfo.scrollRange(rowSpan: Float): Float {
    val rows = ceil(totalItemsCount.toFloat() / columnCount)

    return rows * rowSpan - mainAxisItemSpacing +
            beforeContentPadding + afterContentPadding -
            (viewportEndOffset - viewportStartOffset)
}

private fun LazyGridState.scrollFraction(): Float {
    val info = layoutInfo

    return when {
        info.visibleItemsInfo.isEmpty() || !canScrollBackward -> 0f
        !canScrollForward -> 1f
        else -> {
            val rowSpan = info.averageRowSpan
            val scrolled =
                firstVisibleItemIndex / info.columnCount * rowSpan + firstVisibleItemScrollOffset

            (scrolled / info.scrollRange(rowSpan)).coerceIn(0f, 1f)
        }
    }
}

private suspend fun LazyGridState.scrollToFraction(fraction: Float) {
    val info = layoutInfo
    if (info.visibleItemsInfo.isEmpty()) return

    val rowSpan = info.averageRowSpan
    val target = fraction * info.scrollRange(rowSpan).coerceAtLeast(0f)
    val row = (target / rowSpan).toInt()
    val index = (row * info.columnCount).coerceIn(0, info.totalItemsCount - 1)

    scrollToItem(index, (target - row * rowSpan).roundToInt())
}
