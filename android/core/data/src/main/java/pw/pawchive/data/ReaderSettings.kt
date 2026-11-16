/* Adapted from Pupil / Hitomi Settings.kt. Copyright (C) 2026 tom5079.
 * GPL-3.0-or-later; see third_party/hitomi/COPYING. */
package pw.pawchive.data

import kotlinx.serialization.Serializable

@Serializable enum class ReaderOrientation { VERTICAL, HORIZONTAL }
@Serializable enum class ReaderLayout { SINGLE, DOUBLE, AUTO }
@Serializable enum class MultipageFit { SCREEN, FILL }
@Serializable enum class MultipageOrder { AUTO, LTR, RTL }
@Serializable data class ReaderSettings(
    val tapSides: Boolean = true,
    val instantFlip: Boolean = false,
    val readerLayout: ReaderLayout = ReaderLayout.AUTO,
    val readerOrientation: ReaderOrientation = ReaderOrientation.HORIZONTAL,
    val readerReverse: Boolean = false,
    val readerSnap: Boolean = true,
    val readerPadding: Boolean = true,
    val multipagePortrait: Boolean = false,
    val multipageFit: MultipageFit = MultipageFit.SCREEN,
    val multipageOrder: MultipageOrder = MultipageOrder.AUTO,
    val multipageCover: Boolean = true,
    val volumePageTurn: Boolean = true
)
