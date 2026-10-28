package dev.argus.ui.preview

import android.content.res.Configuration
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import dev.argus.ui.theme.ArgusTheme

/**
 * Anteprima multipla: ogni composable annotato appare nel tema chiaro e nel tema scuro.
 * `ArgusTheme` segue `isSystemInDarkTheme()`, quindi basta il `uiMode` dell'anteprima.
 *
 * Uso: `@ArgusThemePreviews @Composable private fun XxxPreview() = ArgusPreviewSurface { Xxx(...) }`.
 */
@Preview(name = "Chiaro", group = "Tema", showBackground = true, widthDp = 390, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "Scuro", group = "Tema", showBackground = true, widthDp = 390, uiMode = Configuration.UI_MODE_NIGHT_YES)
annotation class ArgusThemePreviews

/** Tema Argus + fondo `page`: il contenitore standard delle anteprime. */
@Composable
fun ArgusPreviewSurface(content: @Composable () -> Unit) {
    ArgusTheme {
        Surface(color = ArgusTheme.surfaces.page, content = content)
    }
}
