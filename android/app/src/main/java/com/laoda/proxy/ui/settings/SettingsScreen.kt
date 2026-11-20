package com.laoda.proxy.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.laoda.proxy.LaoDaApplication
import com.laoda.proxy.data.ThemeMode
import com.laoda.proxy.ui.vmFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页（模块 C）：主题三选一、语言三选一（只存储，提示重启后生效）、
 * 备份（CreateDocument 导出当前 config.yaml 文本）、恢复（GetContent 导入）、
 * 关于（版本 0.1.0、mihomo v1.19.34、GPL-3.0 说明）。
 */
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as android.app.Application
    val container = (app as LaoDaApplication).container
    val viewModel: SettingsViewModel = viewModel(
        factory = vmFactory {
            SettingsViewModel(app, container.settingsStore, container.profileRepository)
        },
    )
    val scope = rememberCoroutineScope()

    val theme by viewModel.theme.collectAsState()
    val language by viewModel.language.collectAsState()
    val currentProfile by viewModel.currentProfile.collectAsState()
    val message by viewModel.message.collectAsState()

    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.consumeMessage()
        }
    }

    // 备份：CreateDocument，文件名建议 "laoda-proxy-config.yaml"，内容为当前档案 YAML 文本。
    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/yaml"),
    ) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                val text = viewModel.currentConfigText()
                if (text == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "无可用配置可导出", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(text.toByteArray(Charsets.UTF_8))
                    }
                }.onSuccess {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "已导出当前配置", Toast.LENGTH_SHORT).show()
                    }
                }.onFailure {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "导出失败：${it.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    // 恢复：GetContent 选 YAML 文件，导入为新档案。
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            val name = uri.lastPathSegment
                ?.substringAfterLast('/')
                ?.ifEmpty { null }
                ?: "恢复的配置"
            viewModel.restoreFromFile(name, uri)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("设置", style = MaterialTheme.typography.headlineMedium)

        // ---- 主题 ----
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .selectableGroup(),
            ) {
                Text("主题", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                listOf(
                    ThemeMode.SYSTEM to "跟随系统",
                    ThemeMode.LIGHT to "浅色",
                    ThemeMode.DARK to "深色",
                ).forEach { (mode, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.setTheme(mode) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = theme == mode,
                            onClick = { viewModel.setTheme(mode) },
                        )
                        Text(label)
                    }
                }
            }
        }

        // ---- 语言 ----
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .selectableGroup(),
            ) {
                Text("语言", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                listOf(
                    "system" to "跟随系统",
                    "zh" to "中文",
                    "en" to "English",
                ).forEach { (value, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.setLanguage(value)
                                Toast.makeText(context, "语言设置已保存，重启后生效", Toast.LENGTH_SHORT).show()
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = language == value,
                            onClick = {
                                viewModel.setLanguage(value)
                                Toast.makeText(context, "语言设置已保存，重启后生效", Toast.LENGTH_SHORT).show()
                            },
                        )
                        Text(label)
                    }
                }
                Text(
                    "语言只做存储，重启应用后生效。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // ---- 备份 / 恢复 ----
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("备份与恢复", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "当前配置：${currentProfile?.name ?: "无"}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { backupLauncher.launch("laoda-proxy-config.yaml") },
                        enabled = currentProfile != null,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("备份（导出当前配置）")
                    }
                    OutlinedButton(
                        onClick = { restoreLauncher.launch("text/*") },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("恢复（导入 YAML）")
                    }
                }
            }
        }

        // ---- 关于 ----
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("关于", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text("应用版本：0.1.0")
                Text("核心：mihomo v1.19.34")
                Spacer(Modifier.height(8.dp))
                Text("本应用基于 Mihomo（GPL-3.0），源码按 GPL-3.0 提供。")
            }
        }
    }
}
