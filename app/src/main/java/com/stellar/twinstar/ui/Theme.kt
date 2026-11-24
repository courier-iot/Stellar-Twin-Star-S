package com.stellar.twinstar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Ts {
    val Bg = Color(0xFF000000)
    val Card = Color(0xFF050507)
    val Line = Color(0xFF1E1E22)
    val Line2 = Color(0xFF2A2A2F)
    val Text = Color(0xFFE6E6E6)
    val Dim = Color(0xFF9A9AA0)
    val Faint = Color(0xFF6E6E74)
    val Mono = FontFamily.Monospace
    val Swatches = listOf(
        0xFFB69CFF, 0xFF8AB4FF, 0xFFF2C46D, 0xFF6EE7C8, 0xFFFF8FB1, 0xFFFF9A5C, 0xFFE6E6E6, 0xFF7CE38B,
    ).map { it.toInt() }
}

data class TsStyle(val accent: Color, val glow: Float)

val LocalTs = staticCompositionLocalOf { TsStyle(Color(0xFFB69CFF), 0.6f) }

@Composable
fun TwinStarTheme(accent: Color, glow: Float, content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = accent, onPrimary = Color.Black, secondary = accent, onSecondary = Color.Black,
        tertiary = accent, background = Ts.Bg, onBackground = Ts.Text, surface = Ts.Bg, onSurface = Ts.Text,
        surfaceVariant = Color(0xFF0C0C0F), onSurfaceVariant = Ts.Dim, outline = Ts.Line2, outlineVariant = Ts.Line,
        surfaceTint = Color.Transparent, primaryContainer = Color(0xFF0C0C0F), onPrimaryContainer = accent,
        surfaceContainer = Color(0xFF0A0A0C), surfaceContainerHigh = Color(0xFF0E0E11),
        surfaceContainerHighest = Color(0xFF131316), surfaceContainerLow = Color(0xFF07070A), surfaceContainerLowest = Ts.Bg,
    )
    val base = TextStyle(fontFamily = Ts.Mono, color = Ts.Text)
    val type = Typography(
        bodyLarge = base.copy(fontSize = 15.sp, lineHeight = 24.sp),
        bodyMedium = base.copy(fontSize = 13.sp, lineHeight = 20.sp),
        bodySmall = base.copy(fontSize = 11.sp, lineHeight = 16.sp),
        titleLarge = base.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold),
        titleMedium = base.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold),
        titleSmall = base.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold),
        labelLarge = base.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
        labelMedium = base.copy(fontSize = 11.sp, letterSpacing = 1.sp),
        labelSmall = base.copy(fontSize = 10.sp, letterSpacing = 1.sp),
        headlineSmall = base.copy(fontSize = 22.sp, fontWeight = FontWeight.Bold),
    )
    CompositionLocalProvider(LocalTs provides TsStyle(accent, glow)) {
        MaterialTheme(colorScheme = scheme, typography = type, content = content)
    }
}

/** Soft accent light around a box. [strength] 0..1. */
fun Modifier.glow(color: Color, strength: Float, radius: Dp = 22.dp, corner: Dp = 0.dp): Modifier =
    if (strength <= 0.01f) this else drawBehind {
        val r = radius.toPx() * strength
        drawIntoCanvas { c ->
            val p = Paint().asFrameworkPaint().apply {
                isAntiAlias = true
                this.color = android.graphics.Color.TRANSPARENT
                setShadowLayer(r, 0f, 0f, color.copy(alpha = (0.35f + 0.5f * strength).coerceAtMost(0.9f)).toArgb())
            }
            c.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, corner.toPx(), corner.toPx(), p)
        }
    }

@Composable
fun glowText(color: Color): TextStyle {
    val g = LocalTs.current.glow
    return if (g <= 0.01f) TextStyle.Default else TextStyle(shadow = Shadow(color = color.copy(alpha = 0.9f), blurRadius = 18f * g))
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, color = Ts.Dim, fontSize = 11.sp, letterSpacing = 2.sp, fontFamily = Ts.Mono)
}

@Composable
fun TsSwitch(checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val acc = LocalTs.current
    val c = if (checked && enabled) acc.accent else Color(0xFF3A3A40)
    Box(
        Modifier.size(52.dp, 44.dp).clickable(enabled = enabled) { onChange(!checked) },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.size(44.dp, 24.dp).border(1.dp, c).padding(2.dp),
            horizontalArrangement = if (checked) Arrangement.End else Arrangement.Start,
        ) {
            Box(Modifier.size(18.dp).then(if (checked) Modifier.glow(c, acc.glow, 10.dp) else Modifier).background(c))
        }
    }
}

@Composable
fun SettingRow(title: String, sub: String? = null, onClick: (() -> Unit)? = null, trailing: @Composable (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp).border(1.dp, Color(0xFF1C1C20))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = Ts.Mono, color = Ts.Text)
            if (sub != null) { Spacer(Modifier.height(3.dp)); Text(sub, fontSize = 11.sp, color = Ts.Dim, fontFamily = Ts.Mono, lineHeight = 15.sp) }
        }
        if (trailing != null) { Spacer(Modifier.width(8.dp)); trailing() }
    }
}
