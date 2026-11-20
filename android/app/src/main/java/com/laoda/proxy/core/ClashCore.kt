package com.laoda.proxy.core

import io.github.oviron.libmihomo.Clash
import io.github.oviron.libmihomo.InvokeInterface
import io.github.oviron.libmihomo.TunInterface
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Clash facade 的薄包装（模块 A）。
 *
 * 只用 CONTRACTS.md 里列出的方法名，不编造 Clash 不存在的方法。
 * 已用 javap 核实 AAR（v0.3.14）签名：
 * - Clash 是 Kotlin object：load(String)、bridgeABI()、invokeAction(String, InvokeInterface/Function1)、
 *   quickSetup(String, String, InvokeInterface/Function1)、startTUN(Int, TunInterface, String, String,
 *   String, String, Int)、stopTun()、setEventListener(InvokeInterface/Function1)、
 *   getTraffic(): String、getTotalTraffic(): String、suspended(Boolean)。
 * - TunInterface.protect(fd: Int) 返回 Unit；resolverProcess(Int, String, String, Int): String。
 * - InvokeInterface.onResult(String)。
 */
object ClashCore {

    private val loaded = AtomicBoolean(false)

    /**
     * 进程启动调一次；幂等。校验 bridgeABI 须等于 EXPECTED_BRIDGE_ABI。
     * 已加载时直接返回。
     */
    fun ensureLoaded(nativeLibDir: String) {
        if (loaded.get()) return
        if (Clash.isLoaded()) {
            checkBridgeAbi()
            loaded.set(true)
            return
        }
        Clash.load(nativeLibDir)
        checkBridgeAbi()
        loaded.set(true)
    }

    private fun checkBridgeAbi() {
        val abi = Clash.bridgeABI()
        check(abi == Clash.EXPECTED_BRIDGE_ABI) {
            "libmihomo bridge ABI mismatch: got $abi, expected ${Clash.EXPECTED_BRIDGE_ABI}"
        }
    }

    /**
     * quickSetup：回调字符串空 = 成功，非空 = 错误信息。
     * initJson = {"home-dir": ..., "version": ...}；
     * setupJson = {"selected-map": {...}}。
     */
    suspend fun quickSetup(
        homeDir: File,
        version: Int,
        selectedMap: Map<String, String>,
    ): Result<Unit> = suspendCancellableCoroutine { cont ->
        val initJson = JSONObject()
            .put("home-dir", homeDir.absolutePath)
            .put("version", version)
            .toString()
        val setupJson = JSONObject()
            .put("selected-map", JSONObject(selectedMap))
            .toString()
        Clash.quickSetup(initJson, setupJson) { err ->
            if (cont.isActive) {
                if (err.isNullOrEmpty()) {
                    cont.resume(Result.success(Unit))
                } else {
                    cont.resume(Result.failure(IllegalStateException(err)))
                }
            }
        }
    }

    /**
     * invokeAction：请求 {"id","method","data"}；回调是结果 JSON
     * {"id","method","code":0|-1,"data":...}。
     * data 为 null 时不写 data 字段；非 null 时按 JSON 字符串值写入
     * （需要 data 为字符串的方法，调用方传已序列化的 JSON 字符串，即二次编码）。
     */
    suspend fun invoke(method: String, data: String? = null): ActionResult =
        suspendCancellableCoroutine { cont ->
            val request = JSONObject()
                .put("id", UUID.randomUUID().toString())
                .put("method", method)
            if (data != null) request.put("data", data)
            Clash.invokeAction(request.toString()) { raw ->
                if (cont.isActive) {
                    cont.resume(parseActionResult(raw))
                }
            }
        }

    private fun parseActionResult(raw: String?): ActionResult {
        if (raw.isNullOrEmpty()) return ActionResult(-1, raw)
        return try {
            val json = JSONObject(raw)
            val code = json.optInt("code", -1)
            val data = if (json.has("data") && !json.isNull("data")) json.opt("data") else null
            ActionResult(code, data)
        } catch (_: Exception) {
            ActionResult(-1, raw)
        }
    }

    /** getTraffic：返回 (up, down) 字节/秒；失败返回 (0, 0)。 */
    fun getTraffic(): Pair<Long, Long> = parseTrafficJson(
        runCatching { Clash.getTraffic() }.getOrNull(),
    )

    /** getTotalTraffic：返回 (totalUp, totalDown) 累计字节；失败返回 (0, 0)。 */
    fun getTotalTraffic(): Pair<Long, Long> = parseTrafficJson(
        runCatching { Clash.getTotalTraffic() }.getOrNull(),
    )

    private fun parseTrafficJson(raw: String?): Pair<Long, Long> {
        if (raw.isNullOrEmpty()) return 0L to 0L
        return try {
            val json = JSONObject(raw)
            json.optLong("up", 0L) to json.optLong("down", 0L)
        } catch (_: Exception) {
            0L to 0L
        }
    }

    /** 设置事件监听。清除监听用 [clearEventListener]。 */
    fun setEventListener(listener: (String) -> Unit) {
        // Clash.setEventListener 有 InvokeInterface / Function1 两个重载，
        // 直接传 (String) -> Unit 的 lambda 匹配不到 ((String?) -> Unit)?。
        // 用显式类型 (String?) -> Unit 的变量消除重载歧义，空值直接丢弃。
        val cb: (String?) -> Unit = { s -> if (s != null) listener(s) }
        Clash.setEventListener(cb)
    }

    /**
     * 清除事件监听。setEventListener 有 InvokeInterface / Function1 两个重载，
     * 直接传 null 会有重载歧义，所以用带 InvokeInterface 的重载并传 null。
     */
    fun clearEventListener() {
        Clash.setEventListener(null as InvokeInterface?)
    }

    fun startTun(
        fd: Int,
        tun: TunInterface,
        device: String,
        stack: String,
        address: String,
        dns: String,
        mtu: Int,
    ) {
        Clash.startTUN(fd, tun, device, stack, address, dns, mtu)
    }

    fun stopTun() {
        Clash.stopTun()
    }

    fun suspended(s: Boolean) {
        Clash.suspended(s)
    }
}
