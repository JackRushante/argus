package dev.argus.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

val ArgusType = Typography(
    titleLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.W400, fontSize = 22.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.W400, fontSize = 20.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.W400, fontSize = 14.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.W400, fontSize = 13.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.W500, fontSize = 14.5f.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.W600, fontSize = 11.sp, letterSpacing = 0.12.em),
)
