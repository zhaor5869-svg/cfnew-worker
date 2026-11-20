package com.laoda.proxy.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 流量监控（模块 A）：每 1 秒轮询速率与累计流量。
 * getTraffic/getTotalTraffic 是轻量 JNI 调用，轮询放在 IO 线程。
 */
class TrafficMonitor(
    private val getTraffic: () -> Pair<Long, Long>,
    private val getTotal: () -> Pair<Long, Long>,
) {

    private val _traffic = MutableStateFlow(TrafficSnapshot())
    val traffic: StateFlow<TrafficSnapshot> = _traffic.asStateFlow()

    private var job: Job? = null

    /** 开始每 1 秒轮询；重复调用是 no-op。 */
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val (up, down) = runCatching { getTraffic() }.getOrDefault(0L to 0L)
                val (totalUp, totalDown) = runCatching { getTotal() }.getOrDefault(0L to 0L)
                _traffic.value = TrafficSnapshot(
                    upBytesPerSec = up,
                    downBytesPerSec = down,
                    totalUp = totalUp,
                    totalDown = totalDown,
                )
                delay(1000L)
            }
        }
    }

    /** 停止轮询。 */
    fun stop() {
        job?.cancel()
        job = null
    }
}
