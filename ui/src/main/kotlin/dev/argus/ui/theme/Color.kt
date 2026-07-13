package dev.argus.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// --- superfici dark (design §5.1) ---
val SurfaceBase = Color(0xFF0E1216)
val Surface1 = Color(0xFF161B21)
val Surface2 = Color(0xFF1A1F27)
val SurfaceNav = Color(0xFF151A20)
val OutlineWeak = Color(0xFF242B33)
val OutlineStrong = Color(0xFF2F3944)

// --- testo (design §5.2) ---
val TextPrimary = Color(0xFFEEF1F5)
val TextBody = Color(0xFFE3E6EB)
val TextMuted = Color(0xFF9AA2AD)
val TextFaint = Color(0xFF6F7883)

// --- accento (design §5.3) ---
val AccentPrimary = Color(0xFF9ECAFF)
val AccentOn = Color(0xFF003257)
val AccentContainer = Color(0xFF00497D)
val OnAccentContainer = Color(0xFFD3E4FF)

val ArgusDarkColors = darkColorScheme(
    primary = AccentPrimary, onPrimary = AccentOn,
    primaryContainer = AccentContainer, onPrimaryContainer = OnAccentContainer,
    background = SurfaceBase, onBackground = TextBody,
    surface = SurfaceBase, onSurface = TextBody,
    surfaceVariant = Surface1, onSurfaceVariant = TextMuted,
    outline = OutlineStrong, outlineVariant = OutlineWeak,
)

// Light: derivata dal riferimento §11 1f (Material 3 light standard con stesso accento).
val ArgusLightColors = lightColorScheme(primary = Color(0xFF0061A4), onPrimary = Color.White)
