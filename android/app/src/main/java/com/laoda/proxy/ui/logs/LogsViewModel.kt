package com.laoda.proxy.ui.logs

import androidx.lifecycle.ViewModel
import com.laoda.proxy.core.CoreManager
import com.laoda.proxy.core.LogLine
import kotlinx.coroutines.flow.StateFlow

/**
 * 日志页 ViewModel（模块 C）。
 * 展示 CoreManager.logs；清空调 CoreManager.clearLogs()。
 */
class LogsViewModel(
    private val coreManager: CoreManager,
) : ViewModel() {

    val logs: StateFlow<List<LogLine>> = coreManager.logs

    fun clearLogs() {
        coreManager.clearLogs()
    }
}
