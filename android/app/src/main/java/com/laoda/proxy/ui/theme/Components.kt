package com.laoda.proxy.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 共享 UI 组件（v0.1.3 UI 改版）：FlClash 式的分层表面 + 大圆角 + 徽章。
 *
 * 层级：页面 background → [AppCard] surfaceContainerLow（24dp 圆角）
 * → [InnerBlock] surfaceContainerHigh（16dp 圆角）→ 徽章（14dp 圆角）。
 * 数字统一用等宽 tabular 字体（见各调用处 .copy(fontFeatureSettings = "tnum")）。
 */

/** FlClash 式卡片：低层表面色 + 24dp 圆角 + 轻微阴影。 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        content = { content() },
    )
}

/** 卡片内的第二层区块：更高一档的表面色 + 16dp 圆角。 */
@Composable
fun InnerBlock(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .background(
                MaterialTheme.colorScheme.surfaceContainerHigh,
                RoundedCornerShape(16.dp),
            )
            .padding(12.dp),
        content = { content() },
    )
}

/** 小节标题：FlClash 卡片标题样式——小字号、半粗、次要文字色。 */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/** 通用胶囊徽章：14dp 圆角 + 容器色底。 */
@Composable
fun Pill(
    text: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(containerColor, RoundedCornerShape(14.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            color = contentColor,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/**
 * 延迟徽章：FlClash 的 delay_color 规则——<600ms 绿、>=600ms 琥珀、
 * 失败（<0）红、无数据灰。深色下用更亮的文字色保证对比度。
 */
@Composable
fun DelayBadge(delayMs: Int?, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val dark = isSystemInDarkTheme()
    val (container, content, text) = when {
        delayMs == null -> Triple(
            scheme.surfaceContainerHigh, scheme.onSurfaceVariant, "-",
        )
        delayMs < 0 -> Triple(
            scheme.errorContainer, scheme.onErrorContainer, "超时",
        )
        delayMs < 600 -> Triple(
            if (dark) Color(0xFF14532D) else Color(0xFFDCFCE7),
            if (dark) Color(0xFF4ADE80) else Color(0xFF166534),
            "$delayMs ms",
        )
        else -> Triple(
            if (dark) Color(0xFF78350F) else Color(0xFFFEF3C7),
            if (dark) Color(0xFFFBBF24) else Color(0xFF92400E),
            "$delayMs ms",
        )
    }
    Pill(text = text, containerColor = container, contentColor = content, modifier = modifier)
}

/** 运行状态徽章：运行中绿、已停止灰。 */
@Composable
fun StatusPill(running: Boolean, modifier: Modifier = Modifier) {
    val dark = isSystemInDarkTheme()
    if (running) {
        Pill(
            text = "运行中",
            containerColor = if (dark) Color(0xFF14532D) else Color(0xFFDCFCE7),
            contentColor = if (dark) Color(0xFF4ADE80) else Color(0xFF166534),
            modifier = modifier,
        )
    } else {
        Pill(
            text = "已停止",
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
    }
}

/** 日志 warning 级别的颜色（深浅主题分别取色）。 */
@Composable
fun warningColor(): Color =
    if (isSystemInDarkTheme()) Color(0xFFFBBF24) else Color(0xFFB26A00)
