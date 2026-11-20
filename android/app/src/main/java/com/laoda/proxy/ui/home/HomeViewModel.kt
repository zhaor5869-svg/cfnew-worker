package com.laoda.proxy.ui.home

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.laoda.proxy.core.CoreManager
import com.laoda.proxy.core.CoreState
import com.laoda.proxy.data.Profile
import com.laoda.proxy.data.ProfileRepository
import com.laoda.proxy.service.ProxyVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 首页 ViewModel（模块 C）。
 *
 * 持有 CoreManager + ProfileRepository。启动/停止 VPN 的逻辑在这里：
 * 停止直接发 ACTION_STOP；启动先调 MainActivity 传进来的 onVpnStartRequest
 *（走 VpnService.prepare 授权，授权通过后 MainActivity 发 ACTION_START）。
 */
class HomeViewModel(
    application: Application,
    private val coreManager: CoreManager,
    private val profileRepository: ProfileRepository,
    private val onVpnStartRequest: (Long) -> Unit,
) : AndroidViewModel(application) {

    val state: StateFlow<CoreState> = coreManager.state
    val traffic = coreManager.traffic
    val currentNode = coreManager.currentNode
    val outboundIp = coreManager.outboundIp

    val profiles: StateFlow<List<Profile>> = profileRepository.profiles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val currentProfile: StateFlow<Profile?> = profileRepository.currentProfile
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun consumeMessage() {
        _message.value = null
    }

    /** 大号连接/断开按钮：运行中则停止，否则请求启动（走 VPN 授权流程）。 */
    fun toggleConnection() {
        when (coreManager.state.value) {
            is CoreState.Running,
            is CoreState.Starting,
            is CoreState.Stopping -> stopVpn()
            else -> {
                val profileId = currentProfile.value?.id ?: -1L
                onVpnStartRequest(profileId)
            }
        }
    }

    private fun stopVpn() {
        val intent = Intent(getApplication(), ProxyVpnService::class.java).apply {
            action = ProxyVpnService.ACTION_STOP
        }
        getApplication<Application>().startService(intent)
    }

    /** 探测出口 IP；失败时提示。 */
    fun probeOutboundIp() {
        viewModelScope.launch(Dispatchers.IO) {
            val ip = coreManager.probeOutboundIp()
            if (ip == null) {
                _message.value = "探测出口 IP 失败"
            }
        }
    }
}
