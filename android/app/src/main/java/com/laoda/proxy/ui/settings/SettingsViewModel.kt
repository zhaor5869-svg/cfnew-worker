package com.laoda.proxy.ui.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.laoda.proxy.data.Profile
import com.laoda.proxy.data.ProfileRepository
import com.laoda.proxy.data.SettingsStore
import com.laoda.proxy.data.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页 ViewModel（模块 C）。
 *
 * 主题 / 语言只做存储（语言 v1 不做运行时切换，UI 提示“重启后生效”）。
 * 备份：读当前档案的 YAML 文本，UI 通过 CreateDocument 写到用户选的文件。
 * 恢复：调 ProfileRepository.addFromFile 导入为新档案。
 */
class SettingsViewModel(
    application: Application,
    private val settingsStore: SettingsStore,
    private val profileRepository: ProfileRepository,
) : AndroidViewModel(application) {

    val theme: StateFlow<ThemeMode> = settingsStore.theme
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ThemeMode.SYSTEM)

    val language: StateFlow<String> = settingsStore.language
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "system")

    val currentProfile: StateFlow<Profile?> = profileRepository.currentProfile
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun consumeMessage() {
        _message.value = null
    }

    fun setTheme(mode: ThemeMode) {
        viewModelScope.launch(Dispatchers.IO) {
            settingsStore.setTheme(mode)
        }
    }

    fun setLanguage(lang: String) {
        viewModelScope.launch(Dispatchers.IO) {
            settingsStore.setLanguage(lang)
        }
    }

    /** 备份用：当前档案的 YAML 文本；无档案或文件缺失返回 null。 */
    suspend fun currentConfigText(): String? = withContext(Dispatchers.IO) {
        val profile = profileRepository.currentProfile.firstOrNull() ?: return@withContext null
        val file = profileRepository.profileFile(profile)
        if (!file.exists()) return@withContext null
        runCatching { file.readText() }.getOrNull()
    }

    /** 恢复：把选中的 YAML 导入为新档案，名字默认取文件名。 */
    fun restoreFromFile(name: String, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = profileRepository.addFromFile(name, uri)
            _message.value = if (result.isSuccess) {
                "恢复成功，已导入为新配置"
            } else {
                "恢复失败：${result.exceptionOrNull()?.message}"
            }
        }
    }
}
