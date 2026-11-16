package pw.pawchive.app

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Pink = Color(0xFFEF7B9C)
@Composable
fun PawTheme(dark: Boolean, content: @Composable () -> Unit) {
    val colors = if (dark) darkColorScheme(
        primary = Pink, onPrimary = Color(0xFF211018), background = Color(0xFF19191D), surface = Color(0xFF19191D),
        surfaceVariant = Color(0xFF292930), onSurface = Color(0xFFEDEDF1), onSurfaceVariant = Color(0xFFB4B4BE), outlineVariant = Color(0xFF333339)
    ) else lightColorScheme(
        primary = Color(0xFFAF365B), onPrimary = Color.White, background = Color.White, surface = Color.White,
        surfaceVariant = Color(0xFFF3F3F6), onSurface = Color(0xFF252527), onSurfaceVariant = Color(0xFF696973), outlineVariant = Color(0xFFEDEDF0)
    )
    MaterialTheme(colorScheme = colors, typography = Typography(), content = content)
}
