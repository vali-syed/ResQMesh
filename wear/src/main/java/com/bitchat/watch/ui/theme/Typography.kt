package com.bitchat.watch.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Typography
import com.bitchat.watch.R

val ResQMeshFontFamily = FontFamily.SansSerif

val ResQMeshWearTypography = Typography(
    defaultFontFamily = ResQMeshFontFamily,
)

object ChatVisualTokens {
    val MessageBodyStyle = TextStyle(
        fontFamily = ResQMeshFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 17.sp,
    )

    val SenderStyle = TextStyle(
        fontFamily = ResQMeshFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 15.sp,
    )

    val SystemActionStyle = TextStyle(
        fontFamily = ResQMeshFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
    )
}
