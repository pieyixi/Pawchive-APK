package pw.pawchive.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.math.*

class FeedRefreshAnimationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun completesTheSameArcBeforeRisingAndCanResumeWithoutJumping() {
        val refreshing = mutableStateOf(true)
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnUiThread { compose.activity.setContent {
                Box(Modifier.fillMaxSize().background(Color.White).clipToBounds()) {
                    FeedRefreshIndicator(refreshing.value, {0f})
                }
            } }
            compose.mainClock.advanceTimeBy(420)
            val node = compose.onNodeWithTag("feed_refresh_spinner")
            val restingTop = node.fetchSemanticsNode().boundsInRoot.top
            compose.runOnUiThread { refreshing.value = false }
            compose.mainClock.advanceTimeBy(128)
            node.assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription,"刷新完成"))
            assertEquals("Completion must not jump out of position",restingTop,node.fetchSemanticsNode().boundsInRoot.top,1f)
            compose.mainClock.advanceTimeBy(160)
            val full = node.captureToImage().toPixelMap()
            val radius = 11.75f * compose.activity.resources.displayMetrics.density
            var pinkSamples = 0
            for (i in 0 until 36) {
                val theta = i * Math.PI / 18
                val x = (full.width/2f+cos(theta)*radius).roundToInt().coerceIn(0,full.width-1)
                val y = (full.height/2f+sin(theta)*radius).roundToInt().coerceIn(0,full.height-1)
                val color = full[x,y]
                if(color.red>.8f && color.green<.7f && color.blue<.8f) pinkSamples++
            }
            assertEquals("Refresh must finish as a complete pink ring",36,pinkSamples)
            compose.mainClock.advanceTimeBy(128)
            val risingTop = node.fetchSemanticsNode().boundsInRoot.top
            assertTrue("Completed ring must rise before disappearing",risingTop<restingTop)
            compose.runOnUiThread { refreshing.value = true }
            compose.mainClock.advanceTimeBy(16)
            // The outgoing motion can advance one frame before recomposition cancels it.
            // It must stay continuous, rather than snapping back to its fully exposed position.
            assertEquals("Restarting while retracting must preserve position",risingTop,node.fetchSemanticsNode().boundsInRoot.top,
                8f*compose.activity.resources.displayMetrics.density)
            compose.mainClock.advanceTimeBy(240)
            node.assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription,"正在刷新"))
            compose.runOnUiThread { refreshing.value = false }
            compose.mainClock.advanceTimeBy(800)
            compose.onNodeWithTag("feed_refresh_spinner").assertDoesNotExist()
        } finally {
            compose.runOnUiThread { compose.activity.setContent {} }
            compose.mainClock.autoAdvance = true
        }
    }
}
