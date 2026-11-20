package com.laoda.proxy.ui.connections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.laoda.proxy.core.ConnectionInfo
import com.laoda.proxy.core.CoreManager
import com.laoda.proxy.core.CoreState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 连接页 ViewModel（模块 C）。
 * 展示 CoreManager.connections；关闭单条 / 全部关闭 / 刷新。
 */
class ConnectionsViewModel(
    private val coreManager: CoreManager,
) : ViewModel() {

    val connections: StateFlow<List<ConnectionInfo>> = coreManager.connections

    val isRunning: StateFlow<Boolean> = coreManager.state
        .map { it is CoreState.Running }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun consumeMessage() {
        _message.value = null
    }

    init {
        refreshConnections()
    }

    fun refreshConnections() {
        viewModelScope.launch(Dispatchers.IO) {
            coreManager.refreshConnections()
        }
    }

    fun closeConnection(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = coreManager.closeConnection(id)
            if (!ok) {
                _message.value = "关闭连接失败"
            } else {
                coreManager.refreshConnections()
            }
        }
    }

    fun closeAllConnections() {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = coreManager.closeAllConnections()
            if (!ok) {
                _message.value = "关闭全部连接失败"
            } else {
                coreManager.refreshConnections()
            }
        }
    }
}
