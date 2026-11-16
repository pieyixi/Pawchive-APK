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

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import pw.pawchive.media.R
import pw.pawchive.data.ReaderLayout
import pw.pawchive.data.ReaderOrientation
import pw.pawchive.data.ReaderSettings as Settings

/** What the sheet offers as a row of buttons above everything else. */
class ReaderQuickAction(
    val label: String,
    val icon: @Composable () -> Painter,
    val onClick: () -> Unit,
)

/** The reader's [quickActions], [actions] and options, in a bottom sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderOptionsSheet(
    quickActions: List<ReaderQuickAction>,
    actions: List<ReaderAction>,
    optionsState: Settings,
    onOptionsChange: ((Settings) -> Settings) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        // Taller than the sheet once the options are unfolded, and the
        // navigation bar takes a bite out of what is left.
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                quickActions.forEach { action ->
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(MaterialTheme.shapes.small)
                            .clickable(onClick = action.onClick)
                            .padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(action.icon(), contentDescription = null)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            action.label,
                            style = MaterialTheme.typography.labelMedium,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            actions.forEach { action ->
                ReaderActionItem(action) {
                    onDismiss()
                    action.onClick()
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            ReaderOptions(optionsState, onOptionsChange)
        }
    }
}

/** The ways of reading a gallery that the options mostly come down to. */
private enum class ReaderPreset(
    val label: Int,
    val orientation: ReaderOrientation,
    val reverse: Boolean,
    val snap: Boolean,
    val padding: Boolean,
) {
    DEFAULT(R.string.reader_preset_default, ReaderOrientation.HORIZONTAL, false, true, true),
    RIGHT_TO_LEFT(R.string.reader_preset_right_to_left, ReaderOrientation.HORIZONTAL, true, true, true),
    WEBTOON(R.string.reader_preset_webtoon, ReaderOrientation.VERTICAL, false, false, false),
}

private fun Settings.matches(preset: ReaderPreset) =
    readerOrientation == preset.orientation &&
        readerReverse == preset.reverse &&
        readerSnap == preset.snap &&
        readerPadding == preset.padding

private fun Settings.with(preset: ReaderPreset) = copy(
    readerOrientation = preset.orientation,
    readerReverse = preset.reverse,
    readerSnap = preset.snap,
    readerPadding = preset.padding,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderOptions(options: Settings, onChange: ((Settings) -> Settings) -> Unit) {
    val vertical = options.readerOrientation == ReaderOrientation.VERTICAL
    var advanced by remember { mutableStateOf(false) }

    Column(Modifier.padding(horizontal = 16.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            ReaderPreset.entries.forEachIndexed { index, preset ->
                SegmentedButton(
                    selected = options.matches(preset),
                    onClick = { onChange { it.with(preset) } },
                    shape = SegmentedButtonDefaults.itemShape(index, ReaderPreset.entries.size),
                ) {
                    Text(stringResource(preset.label), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        OptionRow(stringResource(R.string.reader_option_layout)) {
            Row {
                listOf(
                    Triple(ReaderLayout.SINGLE, SingleImage, R.string.reader_option_layout_single),
                    Triple(ReaderLayout.DOUBLE, DoubleImage, R.string.reader_option_layout_double),
                    Triple(ReaderLayout.AUTO, Icons.Default.AutoFixHigh, R.string.reader_option_layout_auto),
                ).forEach { (layout, icon, label) ->
                    IconButton(onClick = { onChange { it.copy(readerLayout = layout) } }) {
                        Icon(
                            icon,
                            contentDescription = stringResource(label),
                            tint =
                                if (options.readerLayout == layout) MaterialTheme.colorScheme.primary
                                else LocalContentColor.current,
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { advanced = !advanced }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(stringResource(R.string.reader_option_advanced), style = MaterialTheme.typography.titleMedium)
            Icon(
                if (advanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
            )
        }

        if (!advanced) {
            Box(Modifier.height(8.dp))
            return@Column
        }

        OrientationPreview(
            vertical = vertical,
            reversed = options.readerReverse,
            snap = options.readerSnap,
            padding = options.readerPadding,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )

        OptionRow(stringResource(R.string.reader_option_orientation)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.reader_option_horizontal), style = MaterialTheme.typography.labelMedium)
                Switch(
                    checked = vertical,
                    onCheckedChange = { toVertical ->
                        onChange {
                            it.copy(
                                readerOrientation =
                                    if (toVertical) ReaderOrientation.VERTICAL else ReaderOrientation.HORIZONTAL
                            )
                        }
                    },
                )
                Text(stringResource(R.string.reader_option_vertical), style = MaterialTheme.typography.labelMedium)
            }
        }

        if (options.readerLayout != ReaderLayout.SINGLE)
            SwitchOption(
                stringResource(R.string.reader_option_cover),
                options.multipageCover,
            ) { cover ->
                onChange { it.copy(multipageCover = cover) }
            }

        SwitchOption(stringResource(R.string.reader_option_reverse), options.readerReverse) { reverse ->
            onChange { it.copy(readerReverse = reverse) }
        }
        SwitchOption(stringResource(R.string.reader_option_snap), options.readerSnap) { snap ->
            onChange { it.copy(readerSnap = snap) }
        }
        SwitchOption(stringResource(R.string.reader_option_instant_flip), options.instantFlip) { instant ->
            onChange { it.copy(instantFlip = instant) }
        }
        SwitchOption(stringResource(R.string.reader_option_padding), options.readerPadding) { padding ->
            onChange { it.copy(readerPadding = padding) }
        }

        SwitchOption("音量键翻页", options.volumePageTurn) { enabled ->
            onChange { it.copy(volumePageTurn = enabled) }
        }
        SwitchOption("点击左右两侧翻页", options.tapSides) { enabled ->
            onChange { it.copy(tapSides = enabled) }
        }

        Box(Modifier.height(8.dp))
    }
}

@Composable
private fun OptionRow(title: String, control: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        control()
    }
}

@Composable
private fun SwitchOption(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    OptionRow(title) {
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** Pages sliding the way the options make them move: gliding, or stopping at each one. */
@Composable
private fun OrientationPreview(
    vertical: Boolean,
    reversed: Boolean,
    snap: Boolean,
    padding: Boolean,
    modifier: Modifier = Modifier,
) {
    val direction = if (reversed) -1f else 1f
    val spacing by animateFloatAsState(if (padding) 48f else 32f, label = "PreviewSpacing")
    val rotation by animateFloatAsState(if (vertical) 90f else 0f, label = "PreviewRotation")

    val offset by rememberInfiniteTransition(label = "Preview").animateFloat(
        initialValue = direction * (if (snap) 0f else spacing / 2),
        targetValue = direction * (if (snap) -spacing else -spacing / 2),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = if (snap) FastOutSlowInEasing else LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "PreviewOffset",
    )

    Box(
        modifier
            .size(48.dp)
            .clipToBounds()
            .rotate(rotation)
    ) {
        for (i in 0..4)
            Icon(
                SingleImage,
                contentDescription = null,
                modifier = Modifier
                    .size(48.dp)
                    .align(Alignment.CenterStart)
                    .offset { IntOffset((offset + spacing * (i - 2)).dp.roundToPx(), 0) },
            )
    }
}

private val SingleImage: ImageVector by lazy {
    materialIcon(name = "Reader.SingleImage") {
        materialPath {
            moveTo(17.0f, 3.0f)
            lineTo(7.0f, 3.0f)
            curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
            verticalLineToRelative(14.0f)
            curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
            horizontalLineToRelative(10.0f)
            curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
            lineTo(19.0f, 5.0f)
            curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
            close()
            moveTo(17.0f, 19.0f)
            lineTo(7.0f, 19.0f)
            lineTo(7.0f, 5.0f)
            horizontalLineToRelative(10.0f)
            verticalLineToRelative(14.0f)
            close()
        }
    }
}

private val DoubleImage: ImageVector by lazy {
    materialIcon(name = "Reader.DoubleImage") {
        materialPath {
            moveTo(9.0f, 3.0f)
            lineTo(2.0f, 3.0f)
            curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
            verticalLineToRelative(14.0f)
            curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
            horizontalLineToRelative(7.0f)
            curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
            lineTo(11.0f, 5.0f)
            curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
            close()
            moveTo(9.0f, 19.0f)
            lineTo(2.0f, 19.0f)
            lineTo(2.0f, 5.0f)
            horizontalLineToRelative(7.0f)
            verticalLineToRelative(14.0f)
            close()
            moveTo(21.0f, 3.0f)
            lineTo(14.0f, 3.0f)
            curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
            verticalLineToRelative(14.0f)
            curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
            horizontalLineToRelative(7.0f)
            curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
            lineTo(23.0f, 5.0f)
            curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
            close()
            moveTo(21.0f, 19.0f)
            lineTo(14.0f, 19.0f)
            lineTo(14.0f, 5.0f)
            horizontalLineToRelative(7.0f)
            verticalLineToRelative(14.0f)
            close()
        }
    }
}
