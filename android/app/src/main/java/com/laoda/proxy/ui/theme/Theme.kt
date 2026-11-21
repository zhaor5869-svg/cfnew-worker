package com.laoda.proxy.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * LaoDa Proxy 主题（v0.1.3 UI 改版，对标 FlClash 的视觉语言）。
 *
 * FlClash（Prism 设计系统）的视觉特征：分层表面（页面/卡片/内块逐层提亮）、
 * 超大圆角（手机卡片 34dp、内块 20dp）、柔和阴影、数字用等宽 tabular 字体、
 * 延迟按 600ms 分绿/琥珀两档。这里用 Material3 的 ColorScheme/Shape 重新实现，
 * 品牌色是我们自己的蓝色，不复制 FlClash 的任何代码、图标或设计资源。
 *
 * 默认不用系统动态取色（dynamicColor=false），因为壁纸取色会盖掉这套配色；
 * 主题模式（SYSTEM/LIGHT/DARK）由 SettingsStore.theme 决定，MainActivity
 * 把算好的 darkTheme 传进来。
 */

/** 蓝色系浅色配色：白底卡片 + 蓝色主色。 */
private val LightBlueScheme = lightColorScheme(
    primary = Color(0xFF2E6BEE),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE6FF),
    onPrimaryContainer = Color(0xFF0A2A6B),
    secondary = Color(0xFF5B6472),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE3E7EE),
    onSecondaryContainer = Color(0xFF1B2432),
    tertiary = Color(0xFF2E8B7A),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFCDEEE6),
    onTertiaryContainer = Color(0xFF0B3D33),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFF5F6F8),
    onBackground = Color(0xFF191B20),
    surface = Color(0xFFF5F6F8),
    onSurface = Color(0xFF191B20),
    surfaceVariant = Color(0xFFE4E6EA),
    onSurfaceVariant = Color(0xFF5A5E68),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFECEEF1),
    surfaceContainerHigh = Color(0xFFE4E6EA),
    surfaceContainerHighest = Color(0xFFD8DADE),
    outline = Color(0xFF767A84),
    outlineVariant = Color(0xFFC6C9D1),
)

/** 蓝色系深色配色：近黑底 + 分层提亮的表面。 */
private val DarkBlueScheme = darkColorScheme(
    primary = Color(0xFF8AB4FF),
    onPrimary = Color(0xFF002E6B),
    primaryContainer = Color(0xFF1E3A6E),
    onPrimaryContainer = Color(0xFFD3E3FF),
    secondary = Color(0xFFA8C7FA),
    onSecondary = Color(0xFF12233D),
    secondaryContainer = Color(0xFF2A3446),
    onSecondaryContainer = Color(0xFFD3E3FF),
    tertiary = Color(0xFF7DD8C3),
    onTertiary = Color(0xFF00382E),
    tertiaryContainer = Color(0xFF0F4A3E),
    onTertiaryContainer = Color(0xFFB9F0E2),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0F0F13),
    onBackground = Color(0xFFE8E8EC),
    surface = Color(0xFF0F0F13),
    onSurface = Color(0xFFE8E8EC),
    surfaceVariant = Color(0xFF2A2A34),
    onSurfaceVariant = Color(0xFFA9A9B3),
    surfaceContainerLowest = Color(0xFF0A0A0D),
    surfaceContainerLow = Color(0xFF17171D),
    surfaceContainer = Color(0xFF1E1E26),
    surfaceContainerHigh = Color(0xFF2A2A34),
    surfaceContainerHighest = Color(0xFF35353F),
    outline = Color(0xFF8E8E9A),
    outlineVariant = Color(0xFF3A3A44),
)

/**
 * 圆角：FlClash 手机卡片 34dp、内块 20dp、徽章 14dp。
 * 这里取稍收敛的一档——大卡 24dp、内块 16dp、徽章 14dp，
 * 既有 FlClash 的圆润感，又不至于在小屏上显得臃肿。
 */
private val LaoDaShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun LaoDaProxyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkBlueScheme
        else -> LightBlueScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = LaoDaShapes,
        content = content,
    )
}
