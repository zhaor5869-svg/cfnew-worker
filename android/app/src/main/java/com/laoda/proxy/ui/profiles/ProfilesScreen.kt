package com.laoda.proxy.ui.profiles

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.laoda.proxy.LaoDaApplication
import com.laoda.proxy.data.Profile
import com.laoda.proxy.data.ProfileRepository
import com.laoda.proxy.ui.theme.AppCard
import com.laoda.proxy.ui.theme.Pill
import com.laoda.proxy.ui.util.formatTime
import com.laoda.proxy.ui.vmFactory

/**
 * 配置页（v0.1.3 UI 改版）：档案卡片改成 FlClash 式大圆角卡片，
 * “当前”改成主色胶囊徽章，操作改成轻量文字按钮。
 * 添加订阅 / 文件导入 / 粘贴配置内容的 ViewModel 接线与 v0.1.2 完全一致，只改表现层。
 */
@Composable
fun ProfilesScreen() {
    val context = LocalContext.current
    val container = (context.applicationContext as LaoDaApplication).container
    val viewModel: ProfilesViewModel = viewModel(
        factory = vmFactory {
            ProfilesViewModel(container.profileRepository, container.coreManager)
        },
    )

    val profiles by viewModel.profiles.collectAsState()
    val currentProfile by viewModel.currentProfile.collectAsState()
    val message by viewModel.message.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var showPasteDialog by remember { mutableStateOf(false) }
    var profileToDelete by remember { mutableStateOf<Profile?>(null) }

    // 文件选择：GetContent，mime 用 "*/*" 而不用 "text/*"——
    // .yaml/.yml 文件在很多手机上是 application/x-yaml 或
    // application/octet-stream，用 "text/*" 时文件选择器里
    // 根本选不到 yaml 文件（v0.1.0 的导入失败很可能就是这个）。
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            val name = uri.lastPathSegment
                ?.substringAfterLast('/')
                ?.ifEmpty { null }
                ?: "导入配置"
            viewModel.addFromFile(name, uri)
        }
    }

    LaunchedEffect(message) {
        message?.let {
            // 用 LENGTH_LONG：失败原因要让用户读完，2 秒的 SHORT 根本看不清。
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.consumeMessage()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            "配置",
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
        )
        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { showAddDialog = true },
                modifier = Modifier.weight(1f),
            ) {
                Text("添加订阅")
            }
            OutlinedButton(
                onClick = { filePicker.launch("*/*") },
                modifier = Modifier.weight(1f),
            ) {
                Text("从文件导入")
            }
        }
        Spacer(Modifier.height(8.dp))
        // 粘贴配置内容：订阅服务器有 Cloudflare 验证、App 直连被 403 时用。
        // 用户在手机浏览器里打开订阅地址，等验证通过后全选复制，再粘贴到这里。
        OutlinedButton(
            onClick = { showPasteDialog = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("粘贴配置内容")
        }
        Spacer(Modifier.height(12.dp))

        if (profiles.isEmpty()) {
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "暂无配置，点击上方按钮添加订阅或导入文件。",
                    modifier = Modifier.padding(20.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(profiles, key = { it.id }) { profile ->
                    ProfileCard(
                        profile = profile,
                        isCurrent = profile.id == currentProfile?.id,
                        onUpdate = { viewModel.updateProfile(profile) },
                        onSetCurrent = { viewModel.setCurrent(profile) },
                        onDelete = { profileToDelete = profile },
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AddSubscriptionDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { name, url ->
                viewModel.addSubscription(name, url)
                showAddDialog = false
            },
        )
    }

    if (showPasteDialog) {
        PasteConfigDialog(
            onDismiss = { showPasteDialog = false },
            onConfirm = { name, content ->
                viewModel.addFromContent(name, content)
                showPasteDialog = false
            },
        )
    }

    profileToDelete?.let { profile ->
        AlertDialog(
            onDismissRequest = { profileToDelete = null },
            title = { Text("删除配置") },
            text = { Text("确定删除「${profile.name}」吗？对应的配置文件也会一起删除。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteProfile(profile)
                        profileToDelete = null
                    },
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { profileToDelete = null }) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun ProfileCard(
    profile: Profile,
    isCurrent: Boolean,
    onUpdate: () -> Unit,
    onSetCurrent: () -> Unit,
    onDelete: () -> Unit,
) {
    val typeLabel = when (profile.type) {
        "subscription" -> "订阅"
        "file" -> "本地文件"
        else -> profile.type
    }
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    profile.name,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                if (isCurrent) {
                    Pill(
                        text = "当前",
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "$typeLabel · 更新于 ${formatTime(profile.updatedAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!profile.url.isNullOrEmpty()) {
                Text(
                    profile.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onUpdate) { Text("更新") }
                if (!isCurrent) {
                    TextButton(onClick = onSetCurrent) { Text("设为当前") }
                }
                TextButton(
                    onClick = onDelete,
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("删除")
                }
            }
        }
    }
}

@Composable
private fun PasteConfigDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, content: String) -> Unit,
) {
    var name by remember { mutableStateOf("我的订阅") }
    var content by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("粘贴配置内容") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "订阅地址打不开时用这个：在手机浏览器里打开订阅地址，" +
                        "等验证通过后全选复制配置内容，再粘贴到这里。",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("配置内容") },
                    minLines = 6,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim(), content.trim()) },
                enabled = name.isNotBlank() && content.isNotBlank(),
            ) {
                Text("导入")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun AddSubscriptionDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, url: String) -> Unit,
) {
    var name by remember { mutableStateOf("家宽订阅") }
    var url by remember { mutableStateOf(ProfileRepository.DEFAULT_SUBSCRIPTION_URL) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加订阅") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("订阅地址") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim(), url.trim()) },
                enabled = name.isNotBlank() && url.isNotBlank(),
            ) {
                Text("添加")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
