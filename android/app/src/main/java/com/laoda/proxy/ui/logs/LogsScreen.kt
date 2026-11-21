package com.laoda.proxy.ui.logs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.laoda.proxy.LaoDaApplication
import com.laoda.proxy.core.LogLine
import com.laoda.proxy.ui.theme.AppCard
import com.laoda.proxy.ui.theme.warningColor
import com.laoda.proxy.ui.util.formatTime
import com.laoda.proxy.ui.vmFactory

/**
 * 日志页（v0.1.3 UI 改版）：空态改成大圆角卡片，warning 级别按深浅主题取色。
 * 日志列表、清空的 ViewModel 接线与 v0.1.0 完全一致，只改表现层。
 * 从首页进，不在底部 tab 栏。
 */
@Composable
fun LogsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as LaoDaApplication).container
    val viewModel: LogsViewModel = viewModel(
        factory = vmFactory { LogsViewModel(container.coreManager) },
    )

    val logs by viewModel.logs.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                "日志",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { viewModel.clearLogs() }) {
                Text("清空")
            }
        }
        Spacer(Modifier.height(12.dp))

        if (logs.isEmpty()) {
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "暂无日志。",
                    modifier = Modifier.padding(20.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(logs) { line ->
                LogRow(line)
            }
        }
    }
}

@Composable
private fun LogRow(line: LogLine) {
    val color = when (line.level.lowercase()) {
        "error" -> MaterialTheme.colorScheme.error
        "warning" -> warningColor()
        else -> MaterialTheme.colorScheme.onSurface
    }
    Text(
        text = "${formatTime(line.time)} [${line.level}] ${line.message}",
        color = color,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    )
}
