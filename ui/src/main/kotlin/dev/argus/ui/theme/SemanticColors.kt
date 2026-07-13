package dev.argus.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

data class RolePair(val fg: Color, val bg: Color)

data class ArgusSemantic(
    val armed: RolePair, val pending: RolePair, val error: RolePair,
    val needsReview: RolePair, val disabled: RolePair,
    val generative: RolePair, val cloud: RolePair, val codeText: Color,
)

val ArgusSemanticDark = ArgusSemantic(
    armed = RolePair(Color(0xFF7FE0A0), Color(0xFF123A29)),
    pending = RolePair(Color(0xFFFFCF7A), Color(0xFF4A3300)),
    error = RolePair(Color(0xFFFFB4AB), Color(0xFF2E0F0B)),
    needsReview = RolePair(Color(0xFFFFB59D), Color(0xFF4D1C12)),
    disabled = RolePair(Color(0xFFA7ADB5), Color(0xFF2A2D31)),
    generative = RolePair(Color(0xFFD4BBFF), Color(0xFF372A4D)),
    cloud = RolePair(Color(0xFFFFB787), Color(0xFF3D2A17)),
    codeText = Color(0xFFA6E3B8),
)
val LocalArgusSemantic = staticCompositionLocalOf { ArgusSemanticDark }
