package com.laoda.proxy.core

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 核心管理器（模块 A）：Mihomo 核心的生命周期 + 状态聚合。
 *
 * - homeDir = filesDir/mihomo；首次使用把 assets 的 Country.mmdb / geoip.dat /
 *   geosite.dat 复制到 homeDir（不存在才复制）。
 * - 核心只读 <home-dir>/config.yaml：start/reload 都是先把目标 YAML 复制过去。
 * - 事件监听在 init 里注册：Log 追加进 logs（最多 500 条），Connections 更新
 *   connections，GroupDelay 写回 proxies 的延迟。
 * - 所有后台调用都在 IO 协程；StateFlow 本身线程安全，直接赋值即可。
 */
class CoreManager(private val context: Context) {

    companion object {
        private const val TAG = "CoreManager"
        private const val MAX_LOGS = 500
        private const val DEFAULT_TEST_URL = "http://www.gstatic.com/generate_204"
        private const val SELECT_GROUP_NAME = "🚀 节点选择"
        private val GEO_ASSETS = arrayOf("Country.mmdb", "geoip.dat", "geosite.dat")
    }

    val homeDir: File = File(context.filesDir, "mihomo")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val eventBus = CoreEventBus()
    private val trafficMonitor = TrafficMonitor(
        getTraffic = { ClashCore.getTraffic() },
        getTotal = { ClashCore.getTotalTraffic() },
    )

    private val _state = MutableStateFlow<CoreState>(CoreState.Idle)
    val state: StateFlow<CoreState> = _state.asStateFlow()

    /** 实时流量（TrafficMonitor 每 1 秒更新）。 */
    val traffic: StateFlow<TrafficSnapshot> = trafficMonitor.traffic

    private val _proxies = MutableStateFlow<List<ProxyGroup>>(emptyList())
    val proxies: StateFlow<List<ProxyGroup>> = _proxies.asStateFlow()

    /** 最近一次刷新代理组的结果（诊断用，代理页空态时展示）。 */
    private val _proxiesStatus = MutableStateFlow("未加载")
    val proxiesStatus: StateFlow<String> = _proxiesStatus.asStateFlow()

    private val _connections = MutableStateFlow<List<ConnectionInfo>>(emptyList())
    val connections: StateFlow<List<ConnectionInfo>> = _connections.asStateFlow()

    private val _logs = MutableStateFlow<List<LogLine>>(emptyList())
    val logs: StateFlow<List<LogLine>> = _logs.asStateFlow()

    private val _currentNode = MutableStateFlow<String?>(null)
    val currentNode: StateFlow<String?> = _currentNode.asStateFlow()

    private val _outboundIp = MutableStateFlow<String?>(null)
    val outboundIp: StateFlow<String?> = _outboundIp.asStateFlow()

    init {
        try {
            ClashCore.setEventListener { raw -> eventBus.onRawEvent(raw) }
        } catch (e: Exception) {
            // 核心尚未加载时注册监听会失败；start() 里 ensureLoaded 之后重注册。
            Log.w(TAG, "setEventListener in init failed, will retry on start", e)
        }
        scope.launch {
            eventBus.events.collect { event ->
                when (event) {
                    is CoreEvent.Log -> appendLog(event.line)
                    is CoreEvent.Connections -> _connections.value = event.list
                    is CoreEvent.GroupDelay -> applyGroupDelays(event.items)
                }
            }
        }
    }

    // ---- 生命周期 ----

    /**
     * 启动核心：复制 profile 到 homeDir/config.yaml → ensureLoaded →
     * quickSetup → startLog/startListener/subscribeConnections → 启动流量监控 →
     * refreshProxies。成功后 state=Running。
     */
    suspend fun start(profileFile: File): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            _state.value = CoreState.Starting
            copyGeoAssetsIfNeeded()
            if (!profileFile.exists()) {
                error("配置文件不存在：${profileFile.absolutePath}")
            }
            profileFile.copyTo(File(homeDir, "config.yaml"), overwrite = true)
            ClashCore.ensureLoaded(context.applicationInfo.nativeLibraryDir)
            // init 里若注册失败，在核心加载完成后再注册一次。
            runCatching {
                ClashCore.setEventListener { raw -> eventBus.onRawEvent(raw) }
            }
            val setup = ClashCore.quickSetup(homeDir, Build.VERSION.SDK_INT, emptyMap())
            if (setup.isFailure) {
                // 同进程内重复启动：核心可能已初始化，quickSetup 失败时退到 setupConfig。
                Log.w(TAG, "quickSetup failed, falling back to setupConfig", setup.exceptionOrNull())
                val fallback = ClashCore.invoke("setupConfig", """{"selected-map":{}}""")
                if (!fallback.isSuccess) {
                    throw setup.exceptionOrNull()
                        ?: IllegalStateException("quickSetup 失败且 setupConfig 也失败")
                }
            }
            ClashCore.invoke("startLog")
            ClashCore.invoke("startListener")
            ClashCore.invoke("subscribeConnections")
            trafficMonitor.start(scope)
            refreshProxies()
            _state.value = CoreState.Running
        }.onFailure { e ->
            Log.e(TAG, "start failed", e)
            _state.value = CoreState.Error(e.message ?: "启动失败")
        }
    }

    /** 停止核心：停流量监控 → 取消订阅/日志/监听（忽略错误）→ stopTun → state=Idle。 */
    suspend fun stop() = withContext(Dispatchers.IO) {
        _state.value = CoreState.Stopping
        trafficMonitor.stop()
        runCatching { ClashCore.invoke("unsubscribeConnections") }
        runCatching { ClashCore.invoke("stopLog") }
        runCatching { ClashCore.invoke("stopListener") }
        runCatching { ClashCore.stopTun() }
        _state.value = CoreState.Idle
    }

    /**
     * 重载配置：复制到 config.yaml 后调 setupConfig（data 是 SetupParams JSON 字符串）。
     * 成功后 refreshProxies。
     */
    suspend fun reload(profileFile: File): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (!profileFile.exists()) {
                error("配置文件不存在：${profileFile.absolutePath}")
            }
            profileFile.copyTo(File(homeDir, "config.yaml"), overwrite = true)
            val result = ClashCore.invoke("setupConfig", """{"selected-map":{}}""")
            if (!result.isSuccess) {
                error("setupConfig 失败：code=${result.code} data=${result.data}")
            }
            refreshProxies()
        }
    }

    // ---- 代理组 / 节点 ----

    /** 刷新代理组列表（同时更新 currentNode）。 */
    suspend fun refreshProxies() = withContext(Dispatchers.IO) {
        val result = runCatching { ClashCore.invoke("getProxies", null) }.getOrNull()
        if (result == null) {
            _proxiesStatus.value = "getProxies 调用异常"
            return@withContext
        }
        if (!result.isSuccess) {
            _proxiesStatus.value = "getProxies 失败：code=${result.code}"
            Log.w(TAG, "getProxies failed: code=${result.code}")
            return@withContext
        }
        val groups = parseProxyGroups(result.data)
        _proxiesStatus.value = "已加载 ${groups.size} 个组"
        // 保留已有的延迟（getProxies 本身不带实时延迟）。
        val oldByName = _proxies.value.associateBy { it.name }
        _proxies.value = groups.map { g ->
            val old = oldByName[g.name]
            if (old == null) g else g.copy(delayMs = old.delayMs, memberDelays = old.memberDelays)
        }
        updateCurrentNode(_proxies.value)
    }

    /** 切换组内选中节点；成功后 refreshProxies。 */
    suspend fun selectProxy(group: String, proxy: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val data = JSONObject()
                    .put("group-name", group)
                    .put("proxy-name", proxy)
                    .toString()
                val result = ClashCore.invoke("changeProxy", data)
                if (!result.isSuccess) {
                    error("changeProxy 失败：code=${result.code} data=${result.data}")
                }
                refreshProxies()
            }
        }

    /** 测单个节点延迟（毫秒）；失败返回 -1。 */
    suspend fun testDelay(
        proxyName: String,
        url: String = DEFAULT_TEST_URL,
        timeoutMs: Long = 5000,
    ): Int = withContext(Dispatchers.IO) {
        val data = JSONObject()
            .put("proxy-name", proxyName)
            .put("test-url", url)
            .put("timeout", timeoutMs)
            .toString()
        val result = runCatching { ClashCore.invoke("testDelay", data) }.getOrNull()
            ?: return@withContext -1
        val delay = (result.data as? Number)?.toInt() ?: -1
        if (delay >= 0) {
            _proxies.value = _proxies.value.map { g ->
                if (g.all.contains(proxyName)) {
                    g.copy(memberDelays = g.memberDelays + (proxyName to delay))
                } else {
                    g
                }
            }
        }
        delay
    }

    /** 测多个组的延迟；失败返回空 map。groupDelay 事件也会推送，EventBus 已接。 */
    suspend fun testGroups(
        groupNames: List<String>,
        url: String = DEFAULT_TEST_URL,
        timeoutMs: Long = 5000,
    ): Map<String, Int> = withContext(Dispatchers.IO) {
        val data = JSONObject()
            .put("group-names", JSONArray(groupNames))
            .put("test-url", url)
            .put("timeout", timeoutMs)
            .toString()
        val result = runCatching { ClashCore.invoke("testGroups", data) }.getOrNull()
            ?: return@withContext emptyMap()
        if (!result.isSuccess) return@withContext emptyMap()
        val delays = parseTestGroupsData(result.data, groupNames)
        if (delays.isNotEmpty()) {
            _proxies.value = _proxies.value.map { g ->
                val d = delays[g.name] ?: return@map g
                g.copy(delayMs = d)
            }
        }
        delays
    }

    // ---- 连接 ----

    /** 刷新连接列表。 */
    suspend fun refreshConnections() = withContext(Dispatchers.IO) {
        val result = runCatching { ClashCore.invoke("getConnections", null) }.getOrNull()
            ?: return@withContext
        if (!result.isSuccess) return@withContext
        _connections.value = parseConnectionsData(result.data)
    }

    /** 关闭一条连接；成功返回 true。 */
    suspend fun closeConnection(id: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { ClashCore.invoke("closeConnection", id) }
            .getOrNull()?.isSuccess == true
    }

    /** 关闭全部连接；成功返回 true。 */
    suspend fun closeAllConnections(): Boolean = withContext(Dispatchers.IO) {
        runCatching { ClashCore.invoke("closeAllConnections", null) }
            .getOrNull()?.isSuccess == true
    }

    /** 重置连接（网络变化时调）。 */
    suspend fun resetConnections() = withContext(Dispatchers.IO) {
        runCatching { ClashCore.invoke("resetConnections", null) }
    }

    /** 探测出口 IP；成功写 outboundIp 并返回，否则返回 null。 */
    suspend fun probeOutboundIp(): String? = withContext(Dispatchers.IO) {
        val result = runCatching { ClashCore.invoke("probeCurrentProxyIp", "") }.getOrNull()
            ?: return@withContext null
        val ip = (result.data as? String)?.ifEmpty { null } ?: return@withContext null
        _outboundIp.value = ip
        ip
    }

    /** 清空日志。 */
    fun clearLogs() {
        _logs.value = emptyList()
    }

    // ---- 内部 ----

    /** assets 的地理数据库首次复制到 homeDir（不存在才复制）。 */
    private fun copyGeoAssetsIfNeeded() {
        try {
            homeDir.mkdirs()
            for (name in GEO_ASSETS) {
                val dest = File(homeDir, name)
                if (dest.exists()) continue
                context.assets.open(name).use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "copy geo assets failed", e)
        }
    }

    private fun appendLog(line: LogLine) {
        val current = _logs.value
        val next = if (current.size >= MAX_LOGS) {
            current.drop(current.size - MAX_LOGS + 1) + line
        } else {
            current + line
        }
        _logs.value = next
    }

    /** groupDelay 事件：按 name 匹配组写 delayMs；匹配到成员名时也写 memberDelays。 */
    private fun applyGroupDelays(items: List<GroupDelayItem>) {
        if (items.isEmpty()) return
        val byName = items.associate { it.name to it.value }
        _proxies.value = _proxies.value.map { g ->
            var updated = g
            byName[g.name]?.let { v -> updated = updated.copy(delayMs = v) }
            val memberUpdates = g.all.mapNotNull { member ->
                byName[member]?.let { v -> member to v }
            }
            if (memberUpdates.isNotEmpty()) {
                updated = updated.copy(memberDelays = updated.memberDelays + memberUpdates)
            }
            updated
        }
    }

    /**
     * currentNode：先取 "🚀 节点选择" 组的 now，没有则取第一个 Selector 组的 now，
     * 再取不到则取 GLOBAL 的 now。
     */
    private fun updateCurrentNode(groups: List<ProxyGroup>) {
        val node = groups.find { it.name == SELECT_GROUP_NAME }?.now
            ?: groups.firstOrNull { it.type == "Selector" }?.now
            ?: groups.find { it.name == "GLOBAL" }?.now
        _currentNode.value = node
    }

    /**
     * testGroups 的 data 是各组延迟数组。容错解析两种形状：
     * - 对象数组 [{"name":"..","value":123}]
     * - 数字数组 [123, 456]（按传入 groupNames 顺序对应）。
     */
    private fun parseTestGroupsData(data: Any?, groupNames: List<String>): Map<String, Int> {
        val array = data as? JSONArray ?: return emptyMap()
        val out = LinkedHashMap<String, Int>()
        for (i in 0 until array.length()) {
            when (val item = array.opt(i)) {
                is JSONObject -> {
                    val name = item.optString("name", "")
                    if (name.isNotEmpty()) out[name] = item.optInt("value", -1)
                }
                is Number -> {
                    val name = groupNames.getOrNull(i) ?: continue
                    out[name] = item.toInt()
                }
            }
        }
        return out
    }
}
