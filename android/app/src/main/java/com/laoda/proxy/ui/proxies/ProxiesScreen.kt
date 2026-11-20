package com.laoda.proxy.ui.proxies

import android.widget.Toast
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.laoda.proxy.LaoDaApplication
import com.laoda.proxy.core.CoreState
import com.laoda.proxy.core.ProxyGroup
import com.laoda.proxy.ui.vmFactory

/**
 * 代理页（模块 C）：组可展开（type、当前选中 now、成员列表）；
 * 节点行显示名字、类型、延迟；点节点行切换；顶部“测速全部”。
 * 未运行时显示空态提示。
 */
@Composable
fun ProxiesScreen() {
    val context = LocalContext.current
    val container = (context.applicationContext as LaoDaApplication).container
    val viewModel: ProxiesViewModel = viewModel(
        factory = vmFactory { ProxiesViewModel(container.coreManager) },
    )

    val proxies by viewModel.proxies.collectAsState()
    val state by viewModel.state.collectAsState()
    val testing by viewModel.testing.collectAsState()
    val message by viewModel.message.collectAsState()

    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.consumeMessage()
        }
    }

    val isRunning = state is CoreState.Running

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Text("代理", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))

        Button(
            onClick = { viewModel.testAllGroups() },
            enabled = isRunning && !testing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (testing) "测速中…" else "测速全部")
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

        if (proxies.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "暂无代理组，请检查配置。",
                    modifier = Modifier.padding(16.dp),
                )
            }
            return@Column
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(proxies, key = { it.name }) { group ->
                ProxyGroupCard(
                    group = group,
                    onSelectProxy = { proxy -> viewModel.selectProxy(group.name, proxy) },
                )
            }
        }
    }
}

@Composable
private fun ProxyGroupCard(
    group: ProxyGroup,
    onSelectProxy: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val groupDelayText = group.delayMs?.let { if (it < 0) "-" else "$it ms" } ?: "-"

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(group.name, style = MaterialTheme.typography.titleMedium)
                    Text("类型：${group.type} · 延迟：$groupDelayText")
                    Text("当前选中：${group.now ?: "-"}")
                }
                Icon(
                    imageVector = if (expanded) {
                        Icons.Filled.KeyboardArrowUp
                    } else {
                        Icons.Filled.KeyboardArrowDown
                    },
                    contentDescription = if (expanded) "收起" else "展开",
                )
            }

            if (expanded) {
                Spacer(Modifier.height(8.dp))
                // 优先用节点明细；若解析没给 nodes，用成员名列表兜底。
                if (group.nodes.isNotEmpty()) {
                    group.nodes.forEach { node ->
                        val delay = group.memberDelays[node.name] ?: node.delayMs
                        ProxyNodeRow(
                            name = node.name,
                            type = node.type,
                            delayMs = delay,
                            selected = node.name == group.now,
                            onClick = { onSelectProxy(node.name) },
                        )
                    }
                } else {
                    group.all.forEach { name ->
                        val delay = group.memberDelays[name]
                        ProxyNodeRow(
                            name = name,
                            type = "",
                            delayMs = delay,
                            selected = name == group.now,
                            onClick = { onSelectProxy(name) },
                        )
                    }
                }
                if (group.all.isEmpty() && group.nodes.isEmpty()) {
                    Text("该组暂无成员", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun ProxyNodeRow(
    name: String,
    type: String,
    delayMs: Int?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val delayText = when {
        delayMs == null -> "-"
        delayMs < 0 -> "-"
        else -> "$delayMs ms"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                name + if (selected) " ✓" else "",
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            if (type.isNotEmpty()) {
                Text(type, style = MaterialTheme.typography.bodySmall)
            }
        }
        Text(delayText, style = MaterialTheme.typography.bodyMedium)
    }
}
