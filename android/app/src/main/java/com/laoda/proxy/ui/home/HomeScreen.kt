package com.laoda.proxy.ui.home

import android.app.Application
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.laoda.proxy.core.CoreState
import com.laoda.proxy.ui.theme.AppCard
import com.laoda.proxy.ui.theme.SectionTitle
import com.laoda.proxy.ui.theme.StatusPill
import com.laoda.proxy.ui.util.formatBytes
import com.laoda.proxy.ui.vmFactory

/**
 * 首页（v0.1.3 UI 改版）：FlClash 仪表盘风格——
 * 顶部状态徽章 + 当前节点大卡片 + 上下行速度双卡 + 出口 IP/累计流量双卡。
 * 状态、节点、流量、按钮的 ViewModel 接线与 v0.1.0 完全一致，只改表现层。
 */
@Composable
fun HomeScreen(
    onNavigateToLogs: () -> Unit,
    onNavigateToProfiles: () -> Unit,
    onVpnStartRequest: (Long) -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    val container = (app as LaoDaApplication).container
    val viewModel: HomeViewModel = viewModel(
        factory = vmFactory {
            HomeViewModel(
                application = app,
                coreManager = container.coreManager,
                profileRepository = container.profileRepository,
                onVpnStartRequest = onVpnStartRequest,
            )
        },
    )

    val state by viewModel.state.collectAsState()
    val traffic by viewModel.traffic.collectAsState()
    val currentNode by viewModel.currentNode.collectAsState()
    val outboundIp by viewModel.outboundIp.collectAsState()
    val profiles by viewModel.profiles.collectAsState()
    val currentProfile by viewModel.currentProfile.collectAsState()
    val message by viewModel.message.collectAsState()

    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.consumeMessage()
        }
    }

    val statusText = when (val s = state) {
        is CoreState.Idle -> "已停止"
        is CoreState.Starting -> "启动中…"
        is CoreState.Running -> "运行中"
        is CoreState.Stopping -> "停止中…"
        is CoreState.Error -> "错误：${s.message}"
    }
    val isRunning = state is CoreState.Running
    val isBusy = state is CoreState.Starting || state is CoreState.Stopping

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ---- 顶栏：品牌 + 状态徽章 ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "LaoDa Proxy",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.weight(1f),
            )
            StatusPill(running = isRunning)
        }
        if (!isRunning) {
            Text(
                statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ---- 当前节点大卡片（FlClash CurrentOutboundCard 式） ----
        AppCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(20.dp)) {
                SectionTitle("当前节点")
                Spacer(Modifier.height(6.dp))
                Text(
                    currentNode ?: "未选择节点",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    maxLines = 2,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "配置：${currentProfile?.name ?: "-"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { viewModel.toggleConnection() },
                    enabled = !isBusy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                ) {
                    Icon(
                        imageVector = if (isRunning) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        if (isRunning) "断开连接" else "连接",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }

        // ---- 实时速度双卡（FlClash SpeedCard 式） ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SpeedCard(
                label = "上行",
                icon = Icons.Filled.Upload,
                valueText = formatBytes(traffic.upBytesPerSec),
                modifier = Modifier.weight(1f),
            )
            SpeedCard(
                label = "下行",
                icon = Icons.Filled.Download,
                valueText = formatBytes(traffic.downBytesPerSec),
                modifier = Modifier.weight(1f),
            )
        }

        // ---- 出口 IP / 累计流量双卡 ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppCard(modifier = Modifier.weight(1f)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Public,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            "出口 IP",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.SemiBold,
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        outboundIp ?: "-",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFeatureSettings = "tnum",
                        ),
                        maxLines = 1,
                    )
                    TextButton(
                        onClick = { viewModel.probeOutboundIp() },
                        enabled = isRunning,
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        Text("探测出口 IP")
                    }
                }
            }
            AppCard(modifier = Modifier.weight(1f)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SectionTitle("累计流量")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "↑ ${formatBytes(traffic.totalUp)}",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFeatureSettings = "tnum",
                        ),
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "↓ ${formatBytes(traffic.totalDown)}",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFeatureSettings = "tnum",
                        ),
                        maxLines = 1,
                    )
                }
            }
        }

        // ---- 日志入口 ----
        OutlinedButton(
            onClick = onNavigateToLogs,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("查看日志")
        }

        // ---- 无档案提示 ----
        if (profiles.isEmpty()) {
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("还没有配置", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "请先去配置页导入默认订阅，再连接。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onNavigateToProfiles) {
                        Text("去配置页")
                    }
                }
            }
        }
    }
}

@Composable
private fun SpeedCard(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    valueText: String,
    modifier: Modifier = Modifier,
) {
    AppCard(modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    valueText,
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Bold,
                        fontFeatureSettings = "tnum",
                    ),
                    maxLines = 1,
                )
                Text(
                    "/s",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 2.dp, bottom = 2.dp),
                )
            }
        }
    }
}
