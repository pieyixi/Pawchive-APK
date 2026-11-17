package pw.pawchive.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import pw.pawchive.data.SearchFilters
import pw.pawchive.data.FeedStatus
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class QuietPress019Test {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun samePixels(a: ImageBitmap, b: ImageBitmap) {
        assertEquals(a.width, b.width); assertEquals(a.height, b.height)
        val first = a.toPixelMap(); val second = b.toPixelMap()
        var changed = 0
        for (y in 0 until a.height) for (x in 0 until a.width) if (first[x, y] != second[x, y]) changed++
        assertEquals("Pressed state added pixels outside the component's existing appearance", 0, changed)
    }
    @Test fun allBottomTabsShowPressedFeedback() {
        repeat(3) { index ->
            val tab=compose.onNodeWithTag("bottom_tab_$index")
            val before=tab.captureToImage()
            tab.performTouchInput {down(center)}
            compose.mainClock.advanceTimeBy(120)
            val pressed=tab.captureToImage();val a=before.toPixelMap();val b=pressed.toPixelMap()
            var changed=0
            for(y in 0 until before.height) for(x in 0 until before.width) if(a[x,y]!=b[x,y]) changed++
            org.junit.Assert.assertTrue("Bottom tab must show its restored pressed indication",changed>32)
            tab.performTouchInput {up()}
        }
    }
    @Test fun filterChoiceHasNoRectangularPressedUnderlay() {
        compose.runOnUiThread { compose.activity.setContent { PawTheme(true) { BoxWithConstraints(Modifier.fillMaxSize()) { AdvancedSearchPanel(false, SearchFilters(), maxWidth, maxHeight, {}, {}) } } } }
        val choice = compose.onNodeWithTag("filter_media_image")
        val before = choice.captureToImage()
        choice.performTouchInput { down(center) }
        compose.waitForIdle()
        samePixels(before, choice.captureToImage())
        choice.performTouchInput { up() }
        choice.assertIsSelected()
    }
    @Test fun calendarDayHasNoSquarePressedUnderlay() {
        val today = LocalDate.now()
        compose.runOnUiThread { compose.activity.setContent { PawTheme(true) { ReferenceDatePicker(today.withDayOfMonth(1), today.withDayOfMonth(1), today, {}, {}) } } }
        val day = compose.onNodeWithTag("calendar_$today")
        val before = day.captureToImage()
        day.performTouchInput { down(center) }
        compose.waitForIdle()
        samePixels(before, day.captureToImage())
        day.performTouchInput { up() }
        day.assertIsSelected()
    }
    @Test fun refreshIndicatorHasNoOpaqueContainer() {
        val repo = (compose.activity.application as PawApplication).container.repository
        val previous = repo.feedStatus.value
        val background = Color(0xFF0C1A28)
        try {
            compose.runOnUiThread {
                val app = androidx.lifecycle.ViewModelProvider(compose.activity, vmFactory { AppViewModel(repo) })["quiet-spinner", AppViewModel::class.java]
                repo.feedStatus.value = FeedStatus(running = true)
                compose.activity.setContent { MaterialTheme(colorScheme = darkColorScheme(background = background, surface = Color.Gray)) { FeedScreen(app, {}, {}) } }
            }
            val image = compose.onNodeWithTag("feed_refresh_spinner").captureToImage()
            val pixels = image.toPixelMap()
            val cx = image.width / 2; val cy = image.height / 2
            for ((x, y) in listOf(cx to cy, cx to image.height / 10, image.width / 10 to cy, cx to image.height * 9 / 10)) {
                assertEquals("Refresh spinner painted a surface behind its pink arc", background.toArgb(), pixels[x, y].toArgb())
            }
        } finally { compose.runOnUiThread { repo.feedStatus.value = previous } }
    }
}
