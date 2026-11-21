package com.laoda.proxy.ui.profiles

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.laoda.proxy.core.CoreManager
import com.laoda.proxy.core.CoreState
import com.laoda.proxy.data.Profile
import com.laoda.proxy.data.ProfileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 配置页 ViewModel（模块 C）。
 * 档案列表读 ProfileRepository.profiles；设为当前后若正在运行，
 * 调 CoreManager.reload 重新加载，失败则提示用户重启连接。
 */
class ProfilesViewModel(
    private val profileRepository: ProfileRepository,
    private val coreManager: CoreManager,
) : ViewModel() {

    val profiles: StateFlow<List<Profile>> = profileRepository.profiles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val currentProfile: StateFlow<Profile?> = profileRepository.currentProfile
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val isRunning: StateFlow<Boolean> = coreManager.state
        .map { it is CoreState.Running }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun consumeMessage() {
        _message.value = null
    }

    /** 添加订阅（URL 对话框调）。 */
    fun addSubscription(name: String, url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = profileRepository.addSubscription(name, url)
            _message.value = if (result.isSuccess) {
                "订阅添加成功"
            } else {
                "添加失败：${result.exceptionOrNull()?.message}"
            }
        }
    }

    /** 从文件导入（GetContent 调）。名字默认取文件名。 */
    fun addFromFile(name: String, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = profileRepository.addFromFile(name, uri)
            _message.value = if (result.isSuccess) {
                "导入成功"
            } else {
                "导入失败：${result.exceptionOrNull()?.message}"
            }
        }
    }

    /** 粘贴配置内容导入（订阅服务器有 Cloudflare 验证、App 直连被 403 时用）。 */
    fun addFromContent(name: String, content: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = profileRepository.addFromContent(name, content)
            _message.value = if (result.isSuccess) {
                "导入成功"
            } else {
                "导入失败：${result.exceptionOrNull()?.message}"
            }
        }
    }

    /** 更新一条档案（重新下载订阅或校验本地文件）。 */
    fun updateProfile(profile: Profile) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = profileRepository.updateProfile(profile)
            _message.value = if (result.isSuccess) {
                "更新成功"
            } else {
                "更新失败：${result.exceptionOrNull()?.message}"
            }
        }
    }

    /** 设为当前；若正在运行，调 CoreManager.reload 重新加载。 */
    fun setCurrent(profile: Profile) {
        viewModelScope.launch(Dispatchers.IO) {
            profileRepository.setCurrent(profile)
            if (coreManager.state.value is CoreState.Running) {
                val result = coreManager.reload(profileRepository.profileFile(profile))
                _message.value = if (result.isSuccess) {
                    "已切换并重新加载"
                } else {
                    "已切换，重新加载失败：请重启连接"
                }
            } else {
                _message.value = "已设为当前配置"
            }
        }
    }

    /** 删除档案（调用前 UI 已用 AlertDialog 确认）。 */
    fun deleteProfile(profile: Profile) {
        viewModelScope.launch(Dispatchers.IO) {
            profileRepository.deleteProfile(profile)
            _message.value = "已删除「${profile.name}」"
        }
    }
}
