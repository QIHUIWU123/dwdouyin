// 抖音作品解析下载工具 - dwdouyin（仅用于个人技术学习，禁止商用、批量爬取，遵守版权法规）
package com.dwdouyin.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ============ 设计令牌（与 PC 端同源） ============
val Teal = Color(0xFF00C9A7)
val SkyBlue = Color(0xFF0EA5E9)
val BgDeep = Color(0xFF0F1115)
val CardBg = Color(0xFF16191F)
val TextPrimary = Color(0xFFF2F4F8)
val TextSecondary = Color(0xFF8A90A0)

/** 深色方案：与 PC 端样式表同源的深夜蓝黑。 */
private val DarkScheme = darkColorScheme(
    primary = Teal,
    onPrimary = BgDeep,
    primaryContainer = Color(0xFF00352D),
    onPrimaryContainer = Color(0xFFB8FFF2),
    secondary = SkyBlue,
    onSecondary = BgDeep,
    background = BgDeep,
    onBackground = TextPrimary,
    surface = CardBg,
    onSurface = TextPrimary,
    surfaceVariant = Color(0xFF1A1E27),
    onSurfaceVariant = TextSecondary,
    surfaceContainer = CardBg,
    surfaceContainerHigh = Color(0xFF1A1E27),
    surfaceContainerHighest = Color(0xFF1E222C),
    outline = Color(0xFF2A2F3A),
    outlineVariant = Color(0xFF23272F),
    error = Color(0xFFE07A7F),
    onError = Color(0xFF2A1A1D),
    errorContainer = Color(0xFF2A1A1D),
    onErrorContainer = Color(0xFFF0B8B8),
)

/** 浅色方案：保持一致的青蓝主色。 */
private val LightScheme = lightColorScheme(
    primary = Color(0xFF00A88C),
    onPrimary = Color.White,
    secondary = Color(0xFF0284C7),
    onSecondary = Color.White,
    background = Color(0xFFF7F8FA),
    onBackground = Color(0xFF1A1D23),
    surface = Color.White,
    onSurface = Color(0xFF1A1D23),
    surfaceVariant = Color(0xFFEDEFF4),
    onSurfaceVariant = Color(0xFF5A6070),
    outline = Color(0xFFD6DAE3),
)

/** 深浅色主题跟随系统，深色为默认设计语言（与 PC 端统一）。 */
@Composable
fun DwdouyinTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        content = content,
    )
}
