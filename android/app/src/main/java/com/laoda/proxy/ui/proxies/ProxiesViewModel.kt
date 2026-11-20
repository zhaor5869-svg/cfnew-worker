package com.laoda.proxy.ui.proxies

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.laoda.proxy.core.CoreManager
import com.laoda.proxy.core.CoreState
import com.laoda.proxy.core.ProxyGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 代理页 ViewModel（模块 C）。
 * 展示 CoreManager.proxies；测速全部调 testGroups（全部组名）。
 */
class ProxiesViewModel(
    private val coreManager: CoreManager,
) : ViewModel() {

    companion object {
        const val TEST_URL = "http://www.gstatic.com/generate_204"
        const val TEST_TIMEOUT_MS = 5000L
    }

    val proxies: StateFlow<List<ProxyGroup>> = coreManager.proxies
    val state: StateFlow<CoreState> = coreManager.state

    private val _testing = MutableStateFlow(false)
    val testing: StateFlow<Boolean> = _testing.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun consumeMessage() {
        _message.value = null
    }

    /** 点节点行：切换组内选中节点。 */
    fun selectProxy(group: String, proxy: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = coreManager.selectProxy(group, proxy)
            if (result.isFailure) {
                _message.value = "切换失败：${result.exceptionOrNull()?.message}"
            }
        }
    }

    /** 顶部“测速全部”：对全部组调 testGroups。 */
    fun testAllGroups() {
        viewModelScope.launch(Dispatchers.IO) {
            _testing.value = true
            try {
                val groupNames = coreManager.proxies.value.map { it.name }
                if (groupNames.isEmpty()) {
                    _message.value = "没有可测速的代理组"
                    return@launch
                }
                coreManager.testGroups(groupNames, TEST_URL, TEST_TIMEOUT_MS)
            } finally {
                _testing.value = false
            }
        }
    }

    fun refreshProxies() {
        viewModelScope.launch(Dispatchers.IO) {
            coreManager.refreshProxies()
        }
    }
}
