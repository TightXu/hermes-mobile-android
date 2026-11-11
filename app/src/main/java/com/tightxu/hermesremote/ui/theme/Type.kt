package com.tightxu.hermesremote.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Compact, slightly monospaced-flavored type: terminal DNA without being a gimmick. */
val HermesTypography = Typography(
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 15.sp,
        lineHeight = 21.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 11.sp,
        lineHeight = 15.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 10.sp,
    ),
)
