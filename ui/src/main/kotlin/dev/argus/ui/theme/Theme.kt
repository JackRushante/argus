package dev.argus.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Raggi della tavola «Forme e raggi». I composable li leggono da qui, mai come numeri sparsi.
 * `full` (pulsanti, badge, chip) e' [androidx.compose.foundation.shape.CircleShape] / `RoundedCornerShape(50)`.
 */
object ArgusRadius {
    /** xs 6: angoli interni dei gruppi. */
    val Xs: Dp = 6.dp
    /** sm 10: icone piccole, chip interni. */
    val Sm: Dp = 10.dp
    /** md 14: campi, contenitori trigger. */
    val Md: Dp = 14.dp
    /** lg 20: tessere, nodi dell'albero. */
    val Lg: Dp = 20.dp
    /** xl 24: esterni dei gruppi, pannelli. */
    val Xl: Dp = 24.dp
    /** 2xl 28: riepilogo, card bozza, sheet. */
    val Xxl: Dp = 28.dp
}

/** Spaziature della tavola «Spaziature» (multipli di 4 con il passo di 3 tra gli elementi di un gruppo). */
object ArgusSpacing {
    /** 3: tra gli elementi di un gruppo segmentato. */
    val GroupGap: Dp = 3.dp
    /** 4: unita' base, gap etichetta-valore. */
    val Unit: Dp = 4.dp
    /** 8: tra chip e badge, tra tessere. */
    val Small: Dp = 8.dp
    /** 12: padding righe, icona-testo, tra gruppi. */
    val Medium: Dp = 12.dp
    /** 16: margine laterale schermo, padding pannelli. */
    val Large: Dp = 16.dp
    /** 24: tra sezioni, inset gesture sotto i footer. */
    val Section: Dp = 24.dp
    /** 48: area tattile minima (pulsanti 48-56). */
    val TouchTarget: Dp = 48.dp
}

/** Forme M3: ogni slot punta a un raggio della tavola. Il «full» dei pulsanti lo gestisce M3 stesso. */
val ArgusShapes = Shapes(
    extraSmall = RoundedCornerShape(ArgusRadius.Xs),
    small = RoundedCornerShape(ArgusRadius.Sm),
    medium = RoundedCornerShape(ArgusRadius.Md),
    large = RoundedCornerShape(ArgusRadius.Lg),
    extraLarge = RoundedCornerShape(ArgusRadius.Xxl),
)

/**
 * Tema Argus: chiaro o scuro dal sistema ([isSystemInDarkTheme]). Nessuna scelta in Impostazioni.
 * Fornisce schema M3, tipografia, forme e i due insiemi Argus ([ArgusSemantic] e [ArgusSurfaces]).
 */
@Composable
fun ArgusTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) ArgusDarkColors else ArgusLightColors
    val semantic = if (darkTheme) ArgusSemanticDark else ArgusSemanticLight
    val surfaces = if (darkTheme) ArgusSurfacesDark else ArgusSurfacesLight
    CompositionLocalProvider(
        LocalArgusSemantic provides semantic,
        LocalArgusSurfaces provides surfaces,
    ) {
        MaterialTheme(colorScheme = colors, typography = ArgusType, shapes = ArgusShapes, content = content)
    }
}

/** Accesso ai token Argus che M3 non ha: `ArgusTheme.semantic.armed.fg`, `ArgusTheme.surfaces.card`, ... */
object ArgusTheme {
    val semantic: ArgusSemantic
        @Composable @ReadOnlyComposable get() = LocalArgusSemantic.current

    val surfaces: ArgusSurfaces
        @Composable @ReadOnlyComposable get() = LocalArgusSurfaces.current
}
