/*
 *     Pupil, Hitomi.la viewer for Android
 *     Copyright (C) 2026  tom5079
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

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import pw.pawchive.media.R

private const val BAR_ALPHA = 0.85f

private val PageLabelWidth = 40.dp

private const val DOWNLOADED_PAGE_ALPHA = 0.4f

// The default track's thumb, the gap it keeps around itself and the corners
// facing it, which the page track has to match.
private val SliderThumbWidth = 4.dp
private val SliderThumbGap = 6.dp
private val SliderInsideCorner = 2.dp

@Composable
private fun ReaderBar(content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background.copy(alpha = BAR_ALPHA), content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderTopBar(title: String, onBack: () -> Unit) {
    ReaderBar {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(TopAppBarDefaults.windowInsets)
                .heightIn(min = 48.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
            }

            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            )
        }
    }
}

/**
 * The current page on a slider across the gallery, laid out right to left when
 * the pages are, so it moves the way they do. The page turns when the slider is
 * let go. The track ahead of the thumb is tinted where [downloadedPages] have
 * arrived, the way a video's buffered part is. The end of the bar opens the
 * page grid and the reader's sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderBottomBar(
    currentPage: Int,
    pageCount: Int,
    reversed: Boolean,
    downloadedPages: List<Boolean>,
    onPageSelected: (Int) -> Unit,
    onInteraction: () -> Unit,
    onPageGridClick: () -> Unit,
    onOptionsClick: () -> Unit,
) {
    var dragged by remember { mutableStateOf<Float?>(null) }
    val shownPage = dragged?.roundToInt() ?: currentPage

    ReaderBar {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .heightIn(min = 64.dp)
                .testTag("reader_bottom_bar")
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompositionLocalProvider(LocalLayoutDirection provides if (reversed) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PageLabel(if (pageCount > 0) shownPage.toString() else "")

                    if (pageCount > 1)
                        Slider(
                            value = dragged ?: currentPage.toFloat(),
                            onValueChange = {
                                onInteraction()
                                dragged = it
                            },
                            onValueChangeFinished = {
                                dragged?.let { onPageSelected(it.roundToInt()) }
                                dragged = null
                            },
                            valueRange = 1f..pageCount.toFloat(),
                            track = { state -> PageTrack(state, downloadedPages) },
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp),
                        )
                    else
                        Spacer(Modifier.weight(1f))

                    PageLabel(if (pageCount > 0) pageCount.toString() else "")
                }
            }

            IconButton(
                onClick = {
                    onInteraction()
                    onPageGridClick()
                }
            ) {
                Icon(Icons.Default.GridView, contentDescription = stringResource(R.string.reader_page_grid))
            }

            IconButton(
                onClick = {
                    onInteraction()
                    onOptionsClick()
                }
            ) {
                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.reader_options))
            }
        }
    }
}

/**
 * The slider's own track, over the pages ahead of the thumb drawn by whether
 * they have downloaded. The default track keeps the thumb, its gap and the part
 * already read; its unread part is left clear to show these pages through.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PageTrack(state: SliderState, downloadedPages: List<Boolean>) {
    val downloadedColor = MaterialTheme.colorScheme.primary.copy(alpha = DOWNLOADED_PAGE_ALPHA)
    val missingColor = MaterialTheme.colorScheme.secondaryContainer
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    Box {
        Canvas(
            Modifier
                .matchParentSize()
                .rotate(if (rtl) 180f else 0f)
        ) {
            val range = state.valueRange
            val fraction = ((state.value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
            val unreadStart = fraction * size.width + (SliderThumbWidth / 2 + SliderThumbGap).toPx()
            if (unreadStart >= size.width) return@Canvas

            val outerCorner = CornerRadius(size.height / 2)
            val innerCorner = CornerRadius(SliderInsideCorner.toPx())

            clipPath(
                Path().apply {
                    addRoundRect(
                        RoundRect(
                            left = unreadStart,
                            top = 0f,
                            right = size.width,
                            bottom = size.height,
                            topLeftCornerRadius = innerCorner,
                            topRightCornerRadius = outerCorner,
                            bottomRightCornerRadius = outerCorner,
                            bottomLeftCornerRadius = innerCorner,
                        )
                    )
                }
            ) {
                drawRect(missingColor)

                if (downloadedPages.size < 2) return@clipPath

                val step = size.width / (downloadedPages.size - 1)
                var page = 0

                while (page < downloadedPages.size) {
                    if (!downloadedPages[page]) {
                        page++
                        continue
                    }

                    var last = page
                    while (last + 1 < downloadedPages.size && downloadedPages[last + 1]) last++

                    val left = (page - 0.5f) * step
                    val right = (last + 0.5f) * step
                    drawRect(downloadedColor, topLeft = Offset(left, 0f), size = Size(right - left, size.height))

                    page = last + 1
                }
            }
        }

        SliderDefaults.Track(
            sliderState = state,
            colors = SliderDefaults.colors(inactiveTrackColor = Color.Transparent),
        )
    }
}

@Composable
private fun PageLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        textAlign = TextAlign.Center,
        modifier = Modifier.widthIn(min = PageLabelWidth),
    )
}
