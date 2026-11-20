package com.laoda.proxy.core

/**
 * 核心层数据模型（模块 A）。
 * 时间单位：延迟毫秒，流量字节。
 */

/** 流量快照：速率（字节/秒）+ 累计（字节）。 */
data class TrafficSnapshot(
    val upBytesPerSec: Long = 0L,
    val downBytesPerSec: Long = 0L,
    val totalUp: Long = 0L,
    val totalDown: Long = 0L,
)

/** 单个代理节点（getProxies 返回的节点对象）。 */
data class ProxyNode(
    val name: String,
    val type: String,
    val delayMs: Int? = null,
    val udp: Boolean = false,
)

/** 代理组（getProxies 返回的组对象）。 */
data class ProxyGroup(
    val name: String,
    val type: String,
    /** 当前选中的节点名（Selector 等组的 now）。 */
    val now: String? = null,
    /** 成员节点名。 */
    val all: List<String> = emptyList(),
    /** 成员节点明细（name/type/udp）。 */
    val nodes: List<ProxyNode> = emptyList(),
    /** 组延迟（testGroups / groupDelay 事件），毫秒；未测为 null。 */
    val delayMs: Int? = null,
    /** 各成员节点的延迟（testDelay 结果），key=节点名。 */
    val memberDelays: Map<String, Int> = emptyMap(),
)

/** 一条连接（getConnections / connections 事件）。 */
data class ConnectionInfo(
    val id: String,
    val network: String = "",
    val type: String = "",
    val sourceIP: String = "",
    val sourcePort: Int = 0,
    val destinationIP: String = "",
    val destinationPort: Int = 0,
    val host: String = "",
    val process: String = "",
    val processPath: String = "",
    val upload: Long = 0L,
    val download: Long = 0L,
    val start: String = "",
    val chains: List<String> = emptyList(),
    val rule: String = "",
    val rulePayload: String = "",
)

/** 一行日志（log 事件）。 */
data class LogLine(
    /** 级别：info / warning / error / debug。 */
    val level: String,
    val message: String,
    val time: Long = System.currentTimeMillis(),
)

/** 一组延迟（groupDelay 事件的一项）。 */
data class GroupDelayItem(
    val name: String,
    val url: String = "",
    val value: Int = -1,
)

/** invokeAction 的结果：code 0 成功、-1 失败；data 是 org.json 类型或原始值。 */
data class ActionResult(
    val code: Int,
    val data: Any? = null,
) {
    val isSuccess: Boolean get() = code == 0
}

/** 核心运行状态。 */
sealed class CoreState {
    /** 未启动。 */
    object Idle : CoreState()

    /** 正在启动。 */
    object Starting : CoreState()

    /** 运行中。 */
    object Running : CoreState()

    /** 正在停止。 */
    object Stopping : CoreState()

    /** 启动/运行出错，message 为原因。 */
    data class Error(val message: String) : CoreState()
}
