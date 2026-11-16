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

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pw.pawchive.media.reader.PreviewReaderSource as Cache

private const val PAGE_GRID_COLUMNS = 3

private val PageThumbnailRatio = 3f / 4f
private val PageGridSpacing = 8.dp

/**
 * Every page of the gallery as a thumbnail, for reading on from one of them.
 *
 * The sheet opens over the row [currentPage] is in, since the pages around the
 * one being read are the ones worth looking at first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderPageGridSheet(
    cache: Cache,
    pageCount: Int,
    currentPage: Int,
    downloadedPages: List<Boolean>,
    onPageSelected: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val navigationBars = WindowInsets.navigationBars.asPaddingValues()
    val layoutDirection = LocalLayoutDirection.current

    val gridState = rememberLazyGridState(
        initialFirstVisibleItemIndex =
            (currentPage - 1).coerceAtLeast(0) / PAGE_GRID_COLUMNS * PAGE_GRID_COLUMNS
    )

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Box {
            LazyVerticalGrid(
                columns = GridCells.Fixed(PAGE_GRID_COLUMNS),
                modifier = Modifier.testTag("reader_page_grid"),
                state = gridState,
                // The grid scrolls under the navigation bar rather than stopping
                // above it, so the last row can still be reached.
                contentPadding = PaddingValues(
                    start = PageGridSpacing + navigationBars.calculateStartPadding(layoutDirection),
                    top = PageGridSpacing,
                    end = PageGridSpacing + navigationBars.calculateEndPadding(layoutDirection),
                    bottom = PageGridSpacing + navigationBars.calculateBottomPadding(),
                ),
                horizontalArrangement = Arrangement.spacedBy(PageGridSpacing),
                verticalArrangement = Arrangement.spacedBy(PageGridSpacing),
            ) {
                items(pageCount) { index ->
                    PageThumbnail(
                        cache = cache,
                        page = index + 1,
                        downloaded = downloadedPages.getOrElse(index) { false },
                        current = index + 1 == currentPage,
                        onClick = { onPageSelected(index + 1) },
                    )
                }
            }

            // A gallery is hundreds of pages long, and the thumb is the only way
            // across it that does not mean flinging the grid over and over.
            FastScroller(
                state = gridState,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .fillMaxHeight()
                    .padding(
                        end = navigationBars.calculateEndPadding(layoutDirection),
                        bottom = navigationBars.calculateBottomPadding(),
                    ),
            )
        }
    }
}

@Composable
private fun PageThumbnail(
    cache: Cache,
    page: Int,
    downloaded: Boolean,
    current: Boolean,
    onClick: () -> Unit,
) {
    // Keyed on the page having downloaded, so one that lands while the sheet is
    // open is looked up again instead of staying blank.
    val uri by produceState<Uri?>(null, cache, page, downloaded) {
        value = withContext(Dispatchers.IO) { runCatching { cache.pageFile(page - 1, false).let { Uri.fromFile(it) } }.getOrNull() }
    }

    val shape = MaterialTheme.shapes.small

    Box(
        Modifier
            .aspectRatio(PageThumbnailRatio)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
    ) {
        uri?.let {
            AsyncImage(
                model = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (current)
            Box(
                Modifier
                    .fillMaxSize()
                    .border(2.dp, MaterialTheme.colorScheme.primary, shape)
            )

        Text(
            page.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.inverseOnSurface,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(4.dp)
                .background(MaterialTheme.colorScheme.inverseSurface, shape)
                .padding(horizontal = 4.dp, vertical = 2.dp),
        )
    }
}
