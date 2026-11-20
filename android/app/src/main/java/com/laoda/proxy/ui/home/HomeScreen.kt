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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import com.laoda.proxy.core.CoreState
import com.laoda.proxy.ui.util.formatBytes
import com.laoda.proxy.ui.util.formatSpeed
import com.laoda.proxy.ui.vmFactory

/**
 * 首页（模块 C）：状态卡、当前节点、实时上下行、累计流量、出口 IP、
 * 大号连接/断开按钮、日志入口、无档案提示。
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
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("LaoDa Proxy", style = MaterialTheme.typography.headlineMedium)

        // ---- 状态卡 ----
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("状态：$statusText", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text("当前节点：${currentNode ?: "-"}")
                Text("当前配置：${currentProfile?.name ?: "-"}")
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("上行：${formatSpeed(traffic.upBytesPerSec)}")
                    Text("下行：${formatSpeed(traffic.downBytesPerSec)}")
                }
                Text("累计上行：${formatBytes(traffic.totalUp)}")
                Text("累计下行：${formatBytes(traffic.totalDown)}")
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("出口 IP：${outboundIp ?: "-"}")
                    OutlinedButton(
                        onClick = { viewModel.probeOutboundIp() },
                        enabled = isRunning,
                    ) {
                        Text("探测出口 IP")
                    }
                }
            }
        }

        // ---- 大号连接/断开按钮 ----
        Button(
            onClick = { viewModel.toggleConnection() },
            enabled = !isBusy,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) {
            Text(
                if (isRunning) "断开连接" else "连接",
                style = MaterialTheme.typography.titleMedium,
            )
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
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("还没有配置", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("请先去配置页导入默认订阅，再连接。")
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onNavigateToProfiles) {
                        Text("去配置页")
                    }
                }
            }
        }
    }
}
