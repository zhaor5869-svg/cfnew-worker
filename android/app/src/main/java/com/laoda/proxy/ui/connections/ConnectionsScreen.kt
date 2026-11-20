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
import androidx.compose.material3.Card
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.laoda.proxy.LaoDaApplication
import com.laoda.proxy.core.ConnectionInfo
import com.laoda.proxy.ui.util.formatBytes
import com.laoda.proxy.ui.vmFactory

/**
 * 连接页（模块 C）：目标 host/IP、协议 network/type、规则 rule、
 * 上下行、进程 process；每条有关闭按钮；顶部“全部关闭”+刷新按钮；
 * 未运行显示空态。
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
            .padding(16.dp),
    ) {
        Text("连接", style = MaterialTheme.typography.headlineMedium)
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
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "核心未运行，请先在首页连接。",
                    modifier = Modifier.padding(16.dp),
                )
            }
            return@Column
        }

        if (connections.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "当前没有活跃连接。",
                    modifier = Modifier.padding(16.dp),
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
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(target, style = MaterialTheme.typography.titleMedium)
                Text("协议：${connection.network} / ${connection.type}")
                Text("规则：${connection.rule.ifEmpty { "-" }}")
                if (connection.process.isNotEmpty()) {
                    Text("进程：${connection.process}")
                }
                Text(
                    "上行 ${formatBytes(connection.upload)} · 下行 ${formatBytes(connection.download)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (connection.chains.isNotEmpty()) {
                    Text(
                        "链路：${connection.chains.joinToString(" → ")}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "关闭连接")
            }
        }
    }
}
