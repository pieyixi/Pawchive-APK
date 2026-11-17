package pw.pawchive.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.LruCache
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.res.ResourcesCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.roundToInt

@Immutable
internal data class NavigationBitmaps(val pixels: Int, val home: ImageBitmap, val selectedHome: ImageBitmap, val feed: ImageBitmap, val mine: ImageBitmap, val selectedMine: ImageBitmap)

/** Original vector XML is rasterized once per density, off the UI thread. */
internal class NavigationIconCache(private val context: Context, private val scope: CoroutineScope) {
    val images = MutableStateFlow<NavigationBitmaps?>(null)
    private val cache = LruCache<Int, NavigationBitmaps>(2)
    private var job: Job? = null
    private var requested = 0

    init { request((context.resources.displayMetrics.density * ReferenceMetrics.BottomIconViewport.value).roundToInt()) }

    fun request(pixels: Int) {
        if (images.value?.pixels == pixels || (requested == pixels && job?.isActive == true)) return
        requested = pixels
        job?.cancel()
        job = scope.launch(Dispatchers.Default) {
            val ready = cache[pixels] ?: NavigationBitmaps(pixels,
                render(R.drawable.nav_home, pixels), render(R.drawable.nav_home_active, pixels), render(R.drawable.nav_dynamic, pixels),
                render(R.drawable.nav_mine, pixels), render(R.drawable.nav_mine_active, pixels)).also { cache.put(pixels, it) }
            ensureActive()
            images.value = ready
        }
    }

    private fun render(resource: Int, pixels: Int): ImageBitmap {
        val vector = requireNotNull(ResourcesCompat.getDrawable(context.resources, resource, context.theme)).mutate()
        val rasterPixels = pixels * 4
        val bitmap = Bitmap.createBitmap(rasterPixels, rasterPixels, Bitmap.Config.ARGB_8888)
        vector.setBounds(0, 0, rasterPixels, rasterPixels)
        vector.draw(Canvas(bitmap))
        return requireNotNull(bitmap.copy(Bitmap.Config.ARGB_8888, false)).asImageBitmap().also { bitmap.recycle() }
    }
}
