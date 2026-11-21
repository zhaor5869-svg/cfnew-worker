package com.laoda.proxy.ui.connections

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.laoda.proxy.LaoDaApplication
import com.laoda.proxy.core.ConnectionInfo
import com.laoda.proxy.ui.theme.AppCard
import com.laoda.proxy.ui.util.formatBytes
import com.laoda.proxy.ui.vmFactory

/**
 * 连接页（v0.1.3 UI 改版）：卡片改成 FlClash 式大圆角，
 * 目标 host 加粗、其余信息用次要文字色分层。
 * 关闭/刷新/全部关闭的 ViewModel 接线与 v0.1.0 完全一致，只改表现层。
 */
@Composable
fun ConnectionsScreen() {
    val context = LocalContext.current
    val container = (context.applicationContext as LaoDaApplication).container
    val viewModel: ConnectionsViewModel = viewModel(
        factory = vmFactory { ConnectionsViewModel(container.coreManager) },
    )

    val connections by viewModel.connections.collectAsState()
    val isRunning by viewModel.isRunning.collectAsState()
    val message by viewModel.message.collectAsState()

    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.consumeMessage()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            "连接",
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
        )
        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { viewModel.closeAllConnections() },
                enabled = isRunning && connections.isNotEmpty(),
                modifier = Modifier.weight(1f),
            ) {
                Text("全部关闭")
            }
            OutlinedButton(
                onClick = { viewModel.refreshConnections() },
                enabled = isRunning,
                modifier = Modifier.weight(1f),
            ) {
                Text("刷新")
            }
        }
        Spacer(Modifier.height(12.dp))

        if (!isRunning) {
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "核心未运行，请先在首页连接。",
                    modifier = Modifier.padding(20.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        if (connections.isEmpty()) {
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "当前没有活跃连接。",
                    modifier = Modifier.padding(20.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(connections, key = { it.id }) { connection ->
                ConnectionCard(
                    connection = connection,
                    onClose = { viewModel.closeConnection(connection.id) },
                )
            }
        }
    }
}

@Composable
private fun ConnectionCard(
    connection: ConnectionInfo,
    onClose: () -> Unit,
) {
    val target = connection.host.ifEmpty {
        "${connection.destinationIP}:${connection.destinationPort}"
    }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    target,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    maxLines = 1,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "协议：${connection.network} / ${connection.type} · 规则：${connection.rule.ifEmpty { "-" }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                )
                if (connection.process.isNotEmpty()) {
                    Text(
                        "进程：${connection.process}",
                        style = MaterialTheme.typography.bodySmall,
                        color = muted,
                    )
                }
                Text(
                    "上行 ${formatBytes(connection.upload)} · 下行 ${formatBytes(connection.download)}",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFeatureSettings = "tnum",
                    ),
                    color = muted,
                )
                if (connection.chains.isNotEmpty()) {
                    Text(
                        "链路：${connection.chains.joinToString(" → ")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = muted,
                        maxLines = 2,
                    )
                }
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "关闭连接")
            }
        }
    }
}
