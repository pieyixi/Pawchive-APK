package pw.pawchive.app

import androidx.compose.ui.unit.dp

/** Measured at 1080px / 440dpi; see qa/reference-measurements.json. */
object ReferenceMetrics {
    val BottomBarHeight = 48.dp // 132px, excluding the separate 44px system gesture inset
    val BottomIconViewport = 27.5.dp // TV width: 42/60 * 27.5 * 2.75 = 52.94px
    val DetailFollowWidth = 72.dp // 198px
    val DetailFollowHeight = 24.dp // 66px
    val DetailFollowRadius = 12.dp // capsule, radius 33px
    val ProfileFollowWidth = 183.273.dp // reference container measured 504px on this phone
    val ProfileFollowHeight = 30.dp // round(30 * 2.75) = 83px
    val ProfileFollowRadius = 4.436.dp // JPEG boundary fit: 12.2px; +/-1px edge uncertainty
}
