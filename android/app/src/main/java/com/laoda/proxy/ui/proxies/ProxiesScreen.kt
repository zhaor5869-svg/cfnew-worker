package com.laoda.proxy.ui.proxies

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.laoda.proxy.LaoDaApplication
import com.laoda.proxy.core.CoreState
import com.laoda.proxy.core.ProxyGroup
import com.laoda.proxy.ui.theme.AppCard
import com.laoda.proxy.ui.theme.DelayBadge
import com.laoda.proxy.ui.vmFactory

/**
 * 代理页（v0.1.3 UI 改版）：FlClash 式节点列表——
 * 组头带延迟徽章、节点行带延迟徽章、选中态用主色容器底 + 对勾图标。
 * 组展开、节点切换、测速的 ViewModel 接线与 v0.1.0 完全一致，只改表现层。
 */
@Composable
fun ProxiesScreen() {
    val context = LocalContext.current
    val container = (context.applicationContext as LaoDaApplication).container
    val viewModel: ProxiesViewModel = viewModel(
        factory = vmFactory { ProxiesViewModel(container.coreManager) },
    )

    val proxies by viewModel.proxies.collectAsState()
    val proxiesStatus by viewModel.proxiesStatus.collectAsState()
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
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            "代理",
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
        )
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
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "核心未运行，请先在首页连接。",
                    modifier = Modifier.padding(20.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        if (proxies.isEmpty()) {
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        "暂无代理组，请检查配置。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        proxiesStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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

    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        group.name,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                    Text(
                        "${group.type} · 当前：${group.now ?: "-"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                DelayBadge(delayMs = group.delayMs)
                Icon(
                    imageVector = if (expanded) {
                        Icons.Filled.KeyboardArrowUp
                    } else {
                        Icons.Filled.KeyboardArrowDown
                    },
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .size(20.dp),
                )
            }

            if (expanded) {
                Spacer(Modifier.height(12.dp))
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
                    Text(
                        "该组暂无成员",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) scheme.primaryContainer else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = "已选中",
                tint = scheme.primary,
                modifier = Modifier
                    .size(20.dp)
                    .padding(end = 8.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                ),
                color = if (selected) scheme.onPrimaryContainer else scheme.onSurface,
                maxLines = 1,
            )
            if (type.isNotEmpty()) {
                Text(
                    type,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) {
                        scheme.onPrimaryContainer.copy(alpha = 0.7f)
                    } else {
                        scheme.onSurfaceVariant
                    },
                )
            }
        }
        DelayBadge(delayMs = delayMs)
    }
}
