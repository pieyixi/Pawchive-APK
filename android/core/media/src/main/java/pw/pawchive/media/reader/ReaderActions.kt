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

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.delay
import pw.pawchive.media.R

private const val PROGRESS_ALPHA = 0.5f

/** [progress], from 0 to 1, fills the item from its start while the action is under way. */
class ReaderAction(
    val label: String,
    val icon: @Composable () -> Painter,
    val progress: Float? = null,
    val onClick: () -> Unit,
)

@Composable
fun ReaderActionItem(action: ReaderAction, onClick: () -> Unit) {
    val progress by animateFloatAsState(action.progress ?: 0f, label = "ReaderActionProgress")
    val progressColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = PROGRESS_ALPHA)

    ListItem(
        headlineContent = { Text(action.label) },
        leadingContent = { Icon(action.icon(), contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .clickable(onClick = onClick)
            .drawBehind {
                if (action.progress == null) return@drawBehind

                val width = size.width * progress

                drawRect(
                    color = progressColor,
                    topLeft = Offset(if (layoutDirection == LayoutDirection.Rtl) size.width - width else 0f, 0f),
                    size = Size(width, size.height),
                )
            },
    )
}
