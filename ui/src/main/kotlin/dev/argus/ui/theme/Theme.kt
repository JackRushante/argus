package dev.argus.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

private val ArgusShapes = Shapes(
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
)

@Composable
fun ArgusTheme(darkTheme: Boolean = true, content: @Composable () -> Unit) {
    val colors = if (darkTheme) ArgusDarkColors else ArgusLightColors
    CompositionLocalProvider(LocalArgusSemantic provides ArgusSemanticDark) {
        MaterialTheme(colorScheme = colors, typography = ArgusType, shapes = ArgusShapes, content = content)
    }
}
