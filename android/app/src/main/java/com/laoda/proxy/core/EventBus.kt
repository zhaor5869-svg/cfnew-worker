package com.laoda.proxy.core

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * 核心事件总线（模块 A）。
 *
 * 解析 setEventListener 收到的原始 JSON 事件为 SharedFlow<CoreEvent>。
 * 事件外层：{"id":...,"method":"message","data":{"type":"log"|"connections"|"groupDelay","data":...}}。
 * 解析全部容错：任何一步取不到就丢弃该事件，绝不抛异常（Clash 回调在 Go 工作线程上）。
 */
class CoreEventBus {

    private val _events = MutableSharedFlow<CoreEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<CoreEvent> = _events.asSharedFlow()

    fun onRawEvent(json: String) {
        try {
            val event = parseRawEvent(json) ?: return
            _events.tryEmit(event)
        } catch (_: Exception) {
            // 解析容错：坏事件直接丢弃。
        }
    }

    private fun parseRawEvent(json: String): CoreEvent? {
        val outer = JSONObject(json)
        val data = outer.optJSONObject("data") ?: return null
        val type = data.optString("type", "")
        val inner = data.opt("data") ?: return null
        return when (type) {
            "log" -> {
                val line = parseLogData(inner) ?: return null
                CoreEvent.Log(line)
            }
            "connections" -> CoreEvent.Connections(parseConnectionsData(inner))
            "groupDelay" -> CoreEvent.GroupDelay(parseGroupDelayData(inner))
            else -> null
        }
    }

    private fun parseLogData(inner: Any?): LogLine? {
        if (inner is JSONObject) {
            val level = inner.optString("type", "info").ifEmpty { "info" }
            val payload = if (inner.has("payload") && !inner.isNull("payload")) {
                inner.opt("payload").toString()
            } else {
                inner.toString()
            }
            return LogLine(level = level, message = payload)
        }
        // 非对象时用整条 data 的 toString 兜底。
        return LogLine(level = "info", message = inner.toString())
    }
}

/** 核心事件。 */
sealed class CoreEvent {
    data class Log(val line: LogLine) : CoreEvent()
    data class Connections(val list: List<ConnectionInfo>) : CoreEvent()
    data class GroupDelay(val items: List<GroupDelayItem>) : CoreEvent()
}

// ---- 以下解析函数供 CoreManager 复用（解析 getProxies/getConnections 的返回） ----

/** 解析 connections 快照：JSONObject{"connections":[...]} 或直接 JSONArray。 */
internal fun parseConnectionsData(data: Any?): List<ConnectionInfo> {
    val array: JSONArray? = when (data) {
        is JSONArray -> data
        is JSONObject -> data.optJSONArray("connections")
        else -> null
    } ?: return emptyList()
    val out = ArrayList<ConnectionInfo>(array.length())
    for (i in 0 until array.length()) {
        val obj = array.optJSONObject(i) ?: continue
        parseConnectionInfo(obj)?.let { out.add(it) }
    }
    return out
}

internal fun parseConnectionInfo(obj: JSONObject): ConnectionInfo? {
    val id = obj.optString("id", "").ifEmpty { return null }
    val metadata = obj.optJSONObject("metadata")
    val chainsJson = obj.optJSONArray("chains")
    val chains = ArrayList<String>()
    if (chainsJson != null) {
        for (i in 0 until chainsJson.length()) chains.add(chainsJson.optString(i, ""))
    }
    return ConnectionInfo(
        id = id,
        network = metadata?.optString("network", "") ?: "",
        type = metadata?.optString("type", "") ?: "",
        sourceIP = metadata?.optString("sourceIP", "") ?: "",
        sourcePort = metadata?.optInt("sourcePort", 0) ?: 0,
        destinationIP = metadata?.optString("destinationIP", "") ?: "",
        destinationPort = metadata?.optInt("destinationPort", 0) ?: 0,
        host = metadata?.optString("host", "") ?: "",
        process = metadata?.optString("process", "") ?: "",
        processPath = metadata?.optString("processPath", "") ?: "",
        upload = obj.optLong("upload", 0L),
        download = obj.optLong("download", 0L),
        start = obj.optString("start", ""),
        chains = chains,
        rule = obj.optString("rule", ""),
        rulePayload = obj.optString("rulePayload", ""),
    )
}

/** 解析 groupDelay：[{"name","url","value"}]。 */
internal fun parseGroupDelayData(data: Any?): List<GroupDelayItem> {
    val array = data as? JSONArray ?: return emptyList()
    val out = ArrayList<GroupDelayItem>(array.length())
    for (i in 0 until array.length()) {
        val obj = array.optJSONObject(i) ?: continue
        out.add(
            GroupDelayItem(
                name = obj.optString("name", ""),
                url = obj.optString("url", ""),
                value = obj.optInt("value", -1),
            ),
        )
    }
    return out
}

/**
 * 解析 getProxies 的 data：{"proxies": {"名字": {...}}}（mihomo REST 形状）。
 * 组对象含 name/type/now/all/history；单个节点对象含 name/type/history/udp。
 * GLOBAL 也作为一个组返回（now 用于 currentNode 兜底）。
 */
internal fun parseProxyGroups(data: Any?): List<ProxyGroup> {
    val root = data as? JSONObject ?: return emptyList()
    val proxies = root.optJSONObject("proxies") ?: return emptyList()
    val out = ArrayList<ProxyGroup>()
    val keys = proxies.keys()
    while (keys.hasNext()) {
        val name = keys.next()
        val obj = proxies.optJSONObject(name) ?: continue
        val type = obj.optString("type", "")
        val now = obj.optString("now", "").ifEmpty { null }
        val allJson = obj.optJSONArray("all")
        val all = ArrayList<String>()
        if (allJson != null) {
            for (i in 0 until allJson.length()) all.add(allJson.optString(i, ""))
        }
        // 成员节点明细：all 里的名字若在 proxies 里有对象，取其 type/udp。
        val nodes = all.mapNotNull { memberName ->
            val member = proxies.optJSONObject(memberName) ?: return@mapNotNull null
            ProxyNode(
                name = memberName,
                type = member.optString("type", ""),
                udp = member.optBoolean("udp", false),
            )
        }
        out.add(
            ProxyGroup(
                name = name,
                type = type,
                now = now,
                all = all,
                nodes = nodes,
            ),
        )
    }
    return out
}
