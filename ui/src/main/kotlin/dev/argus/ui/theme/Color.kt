package dev.argus.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// =============================================================================
// Palette del redesign A (Sistema-di-design · «Colore · ruoli Material 3»).
// Fallback statico (minSdk 30): il colore dinamico di Material You non e' usato.
// Sorgente dei valori: tavola «Sistema di design A», variabili .argus / .argus[data-theme="light"].
// I ruoli Argus (page, card, card-2, field, nav, sheet, hero, codice) stanno in SemanticColors.kt.
// =============================================================================

/**
 * Valori grezzi del tema scuro. Internal: i composable leggono i ruoli da `MaterialTheme.colorScheme`,
 * da [ArgusTheme.semantic] o da [ArgusTheme.surfaces], mai da qui (regola «solo token», CLAUDE.md).
 */
internal object ArgusDarkPalette {
    val Primary = Color(0xFF9ECAFF)
    val OnPrimary = Color(0xFF003258)
    val PrimaryContainer = Color(0xFF00497D)
    val OnPrimaryContainer = Color(0xFFD1E4FF)

    val Secondary = Color(0xFFB6C8E8)
    val OnSecondary = Color(0xFF1F3150)
    val SecondaryContainer = Color(0xFF1B355C)
    val OnSecondaryContainer = Color(0xFFD7E3FF)

    val Tertiary = Color(0xFFD0BCFF)
    val OnTertiary = Color(0xFF381E72)
    val TertiaryContainer = Color(0xFF4F378B)
    val OnTertiaryContainer = Color(0xFFEADDFF)

    val Error = Color(0xFFFFB4AB)
    val OnError = Color(0xFF690005)
    val ErrorContainer = Color(0xFF93000A)
    val OnErrorContainer = Color(0xFFFFDAD6)

    val Surface = Color(0xFF0E1217)
    val SurfaceContainerLowest = Color(0xFF090C10)
    val SurfaceContainerLow = Color(0xFF151A20)
    val SurfaceContainer = Color(0xFF1A2028)
    val SurfaceContainerHigh = Color(0xFF232A33)
    val SurfaceContainerHighest = Color(0xFF2D353F)
    // Non nella tavola: derivati dalla stessa rampa blu notte (servono solo ai componenti M3 che li leggono).
    val SurfaceDim = Color(0xFF0E1217)
    val SurfaceBright = Color(0xFF343C47)

    val OnSurface = Color(0xFFE2E7EF)
    val OnSurfaceVariant = Color(0xFFA8B2C0)
    val Outline = Color(0xFF7A8492)
    val OutlineVariant = Color(0xFF343C47)

    val InverseSurface = Color(0xFFE2E7EF)
    val InverseOnSurface = Color(0xFF2B3138)
    val InversePrimary = Color(0xFF0061A4)
    val Scrim = Color(0xFF04060A)
}

/** Valori grezzi del tema chiaro (vedi [ArgusDarkPalette]). */
internal object ArgusLightPalette {
    val Primary = Color(0xFF0061A4)
    val OnPrimary = Color(0xFFFFFFFF)
    val PrimaryContainer = Color(0xFFD1E4FF)
    val OnPrimaryContainer = Color(0xFF001D36)

    val Secondary = Color(0xFF3E5A86)
    val OnSecondary = Color(0xFFFFFFFF)
    val SecondaryContainer = Color(0xFFD6E3FF)
    val OnSecondaryContainer = Color(0xFF0E1D38)

    val Tertiary = Color(0xFF6750A4)
    val OnTertiary = Color(0xFFFFFFFF)
    val TertiaryContainer = Color(0xFFEADDFF)
    val OnTertiaryContainer = Color(0xFF21005D)

    val Error = Color(0xFFBA1A1A)
    val OnError = Color(0xFFFFFFFF)
    val ErrorContainer = Color(0xFFFFDAD6)
    val OnErrorContainer = Color(0xFF410002)

    val Surface = Color(0xFFF8FAFD)
    val SurfaceContainerLowest = Color(0xFFFFFFFF)
    val SurfaceContainerLow = Color(0xFFF2F5F9)
    val SurfaceContainer = Color(0xFFECEFF4)
    val SurfaceContainerHigh = Color(0xFFE6EAF0)
    val SurfaceContainerHighest = Color(0xFFE0E5EB)
    // Non nella tavola: derivati (vedi dark).
    val SurfaceDim = Color(0xFFD8DCE2)
    val SurfaceBright = Color(0xFFF8FAFD)

    val OnSurface = Color(0xFF171C22)
    val OnSurfaceVariant = Color(0xFF424954)
    val Outline = Color(0xFF727A86)
    val OutlineVariant = Color(0xFFC2C8D1)

    val InverseSurface = Color(0xFF2C3137)
    val InverseOnSurface = Color(0xFFEDF1F6)
    val InversePrimary = Color(0xFF9ECAFF)
    val Scrim = Color(0xFF0A0E14)
}

/**
 * Schema Material 3 scuro.
 *
 * Due scelte per far funzionare le schermate esistenti (che leggono `background` come fondo e
 * `surfaceVariant` come card) senza toccarne il layout:
 *  - `background` = alias `page` della tavola (scuro: `surface`; chiaro: `surfaceContainer`), cosi' il
 *    `Scaffold` dipinge il fondo che le tavole chiamano `--page`;
 *  - `surfaceVariant` = alias `card` (scuro: `surfaceContainer`; chiaro: `surfaceContainerLowest`).
 * Il `surface` M3 resta quello della tavola.
 */
val ArgusDarkColors: ColorScheme = ArgusDarkPalette.run {
    darkColorScheme(
        primary = Primary, onPrimary = OnPrimary,
        primaryContainer = PrimaryContainer, onPrimaryContainer = OnPrimaryContainer,
        inversePrimary = InversePrimary,
        secondary = Secondary, onSecondary = OnSecondary,
        secondaryContainer = SecondaryContainer, onSecondaryContainer = OnSecondaryContainer,
        tertiary = Tertiary, onTertiary = OnTertiary,
        tertiaryContainer = TertiaryContainer, onTertiaryContainer = OnTertiaryContainer,
        background = Surface, onBackground = OnSurface,
        surface = Surface, onSurface = OnSurface,
        surfaceVariant = SurfaceContainer, onSurfaceVariant = OnSurfaceVariant,
        surfaceTint = Primary,
        inverseSurface = InverseSurface, inverseOnSurface = InverseOnSurface,
        error = Error, onError = OnError,
        errorContainer = ErrorContainer, onErrorContainer = OnErrorContainer,
        outline = Outline, outlineVariant = OutlineVariant,
        scrim = Scrim,
        surfaceBright = SurfaceBright, surfaceDim = SurfaceDim,
        surfaceContainer = SurfaceContainer,
        surfaceContainerHigh = SurfaceContainerHigh,
        surfaceContainerHighest = SurfaceContainerHighest,
        surfaceContainerLow = SurfaceContainerLow,
        surfaceContainerLowest = SurfaceContainerLowest,
    )
}

/** Schema Material 3 chiaro (vedi [ArgusDarkColors] per le scelte su `background` e `surfaceVariant`). */
val ArgusLightColors: ColorScheme = ArgusLightPalette.run {
    lightColorScheme(
        primary = Primary, onPrimary = OnPrimary,
        primaryContainer = PrimaryContainer, onPrimaryContainer = OnPrimaryContainer,
        inversePrimary = InversePrimary,
        secondary = Secondary, onSecondary = OnSecondary,
        secondaryContainer = SecondaryContainer, onSecondaryContainer = OnSecondaryContainer,
        tertiary = Tertiary, onTertiary = OnTertiary,
        tertiaryContainer = TertiaryContainer, onTertiaryContainer = OnTertiaryContainer,
        background = SurfaceContainer, onBackground = OnSurface,
        surface = Surface, onSurface = OnSurface,
        surfaceVariant = SurfaceContainerLowest, onSurfaceVariant = OnSurfaceVariant,
        surfaceTint = Primary,
        inverseSurface = InverseSurface, inverseOnSurface = InverseOnSurface,
        error = Error, onError = OnError,
        errorContainer = ErrorContainer, onErrorContainer = OnErrorContainer,
        outline = Outline, outlineVariant = OutlineVariant,
        scrim = Scrim,
        surfaceBright = SurfaceBright, surfaceDim = SurfaceDim,
        surfaceContainer = SurfaceContainer,
        surfaceContainerHigh = SurfaceContainerHigh,
        surfaceContainerHighest = SurfaceContainerHighest,
        surfaceContainerLow = SurfaceContainerLow,
        surfaceContainerLowest = SurfaceContainerLowest,
    )
}
