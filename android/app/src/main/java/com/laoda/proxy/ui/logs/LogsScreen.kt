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
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.laoda.proxy.LaoDaApplication
import com.laoda.proxy.core.LogLine
import com.laoda.proxy.ui.util.formatTime
import com.laoda.proxy.ui.vmFactory

/**
 * 日志页（模块 C）：CoreManager.logs 滚动列表，级别着色
 *（error 红色、warning 琥珀色、info 默认）；顶部“清空”按钮。
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
            .padding(16.dp),
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
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { viewModel.clearLogs() }) {
                Text("清空")
            }
        }
        Spacer(Modifier.height(12.dp))

        if (logs.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "暂无日志。",
                    modifier = Modifier.padding(16.dp),
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
        "warning" -> Color(0xFFB26A00)
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
