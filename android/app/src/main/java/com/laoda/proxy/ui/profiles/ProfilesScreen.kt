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
import androidx.compose.material3.Card
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.laoda.proxy.LaoDaApplication
import com.laoda.proxy.data.Profile
import com.laoda.proxy.data.ProfileRepository
import com.laoda.proxy.ui.util.formatTime
import com.laoda.proxy.ui.vmFactory

/**
 * 配置页（模块 C）：档案列表（名字、类型、更新时间、当前标记）；
 * 添加订阅（URL 对话框，默认填默认订阅 URL）+ 文件导入（GetContent）；
 * 每条可更新、设为当前、删除（删除前 AlertDialog 确认）。
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
    var profileToDelete by remember { mutableStateOf<Profile?>(null) }

    // 文件选择：GetContent，mime "text/*"。
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
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.consumeMessage()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Text("配置", style = MaterialTheme.typography.headlineMedium)
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
                onClick = { filePicker.launch("text/*") },
                modifier = Modifier.weight(1f),
            ) {
                Text("从文件导入")
            }
        }
        Spacer(Modifier.height(12.dp))

        if (profiles.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "暂无配置，点击上方按钮添加订阅或导入文件。",
                    modifier = Modifier.padding(16.dp),
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
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    profile.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (isCurrent) {
                    Text(
                        "当前",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
            Text("类型：$typeLabel")
            Text("更新时间：${formatTime(profile.updatedAt)}")
            if (!profile.url.isNullOrEmpty()) {
                Text("订阅地址：${profile.url}", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onUpdate) { Text("更新") }
                if (!isCurrent) {
                    OutlinedButton(onClick = onSetCurrent) { Text("设为当前") }
                }
                OutlinedButton(onClick = onDelete) { Text("删除") }
            }
        }
    }
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
