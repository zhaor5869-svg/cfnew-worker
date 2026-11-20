package com.laoda.proxy.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.util.Log
import com.laoda.proxy.LaoDaApplication
import com.laoda.proxy.core.ClashCore
import com.laoda.proxy.core.CoreState
import io.github.oviron.libmihomo.TunInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * VPN 服务（模块 A 重写：LPX1 逻辑已全部删除）。
 *
 * 职责：建立 TUN 交给 Mihomo（ClashCore.startTun），前台服务保活，
 * 网络变化时调 CoreManager.resetConnections()。
 * 不引用 Node、ProxyProtocol、TlsSocket、ConnectionManager。
 */
class ProxyVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.laoda.proxy.START"
        const val ACTION_STOP = "com.laoda.proxy.STOP"

        /** 要启动的档案 id；-1 表示用当前档案。 */
        const val EXTRA_PROFILE_ID = "profile_id"

        @Volatile
        var isRunning: Boolean = false

        private const val TAG = "ProxyVpn"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "laoda_proxy"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val profileId = intent.getLongExtra(EXTRA_PROFILE_ID, -1L)
                scope.launch { startProxy(profileId) }
            }
            ACTION_STOP -> {
                scope.launch { stopProxy() }
            }
        }
        return START_STICKY
    }

    private suspend fun startProxy(profileId: Long) {
        if (isRunning) return
        val container = (application as LaoDaApplication).container
        val profile = if (profileId >= 0) {
            container.profileRepository.profiles.first()
                .find { it.id == profileId }
                ?: container.profileRepository.currentProfile.first()
        } else {
            container.profileRepository.currentProfile.first()
        }
        if (profile == null) {
            Log.e(TAG, "start requested but no profile found (profileId=$profileId)")
            stopSelf()
            return
        }
        val profileFile = container.profileRepository.profileFile(profile)
        if (!profileFile.exists()) {
            Log.e(TAG, "profile file missing: ${profileFile.absolutePath}")
            stopSelf()
            return
        }

        // 先拉起前台服务（FGS 超时前必须完成），再做耗时启动。
        startForegroundService(profile.name)

        val started = container.coreManager.start(profileFile)
        if (started.isFailure) {
            Log.e(TAG, "core start failed", started.exceptionOrNull())
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        // TUN 建立：establish() 后 detachFd() 交给 Mihomo，库接管 fd，
        // stopTun() 时由库关闭——不要自己再关 ParcelFileDescriptor。
        val tun = Builder()
            .setSession("LaoDa Proxy")
            .addAddress("172.19.0.1", 30)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1")
            .setMetered(false)
            .establish()
        if (tun == null) {
            Log.e(TAG, "establish() returned null (permission?)")
            scope.launch { stopProxy() }
            return
        }
        try {
            val fd = tun.detachFd()
            ClashCore.startTun(fd, tunInterface, "LaoDaProxy", "system", "172.19.0.1/30", "1.1.1.1,1.0.0.1", 1400)
        } catch (e: Exception) {
            Log.e(TAG, "startTun failed", e)
            scope.launch { stopProxy() }
            return
        }

        isRunning = true
        registerNetworkCallback()
        updateNotification(profile.name)
        Log.i(TAG, "proxy started, profile=${profile.name}")
    }

    private suspend fun stopProxy() {
        val container = (application as LaoDaApplication).container
        runCatching { container.coreManager.stop() }
        unregisterNetworkCallback()
        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---- TunInterface（必须独立成对象） ----
    //
    // TunInterface.protect 返回 Unit，VpnService.protect 返回 Boolean，
    // 两者同名但返回类型冲突：服务类同时继承两者时，任何一个 override
    // 都无法同时满足（Unit 不是 Boolean 的子类型，反之亦然）。
    // 所以 TunInterface 单独实现成一个对象，protect 里调服务继承来的
    // VpnService.protect，Boolean 结果忽略。
    private val tunInterface = object : TunInterface {
        override fun protect(fd: Int) {
            this@ProxyVpnService.protect(fd)
        }

        override fun resolverProcess(protocol: Int, source: String, target: String, uid: Int): String {
            return try {
                packageManager.getPackagesForUid(uid)?.firstOrNull() ?: ""
            } catch (_: Exception) {
                ""
            }
        }
    }

    // ---- 前台通知 ----

    private fun startForegroundService(profileName: String) {
        createNotificationChannel()
        val notification = buildNotification(profileName)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(profileName: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(profileName))
    }

    private fun buildNotification(profileName: String): Notification {
        val container = (application as LaoDaApplication).container
        val node = container.coreManager.currentNode.value
        val text = if (node.isNullOrEmpty()) {
            "运行中 · $profileName"
        } else {
            "运行中 · $node"
        }
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = launchIntent?.let {
            android.app.PendingIntent.getActivity(
                this,
                0,
                it,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
            )
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("LaoDa Proxy")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "LaoDa Proxy",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "LaoDa Proxy VPN 连接状态"
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    // ---- 网络变化 ----

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                onNetworkChanged()
            }

            override fun onLost(network: Network) {
                onNetworkChanged()
            }
        }
        networkCallback = callback
        try {
            connectivityManager.registerNetworkCallback(
                NetworkRequest.Builder().build(),
                callback,
            )
        } catch (e: Exception) {
            Log.w(TAG, "registerNetworkCallback failed", e)
            networkCallback = null
        }
    }

    private fun onNetworkChanged() {
        scope.launch {
            runCatching {
                val container = (application as LaoDaApplication).container
                // 只在运行中重置连接；未启动时 resetConnections 是 no-op 调用。
                if (container.coreManager.state.value is CoreState.Running) {
                    container.coreManager.resetConnections()
                }
            }
        }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        networkCallback = null
        try {
            val connectivityManager =
                getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            connectivityManager.unregisterNetworkCallback(callback)
        } catch (_: Exception) {
            // 注销失败无需处理。
        }
    }

    override fun onDestroy() {
        unregisterNetworkCallback()
        // stopTun 已在 CoreManager.stop() 里做；这里只做兜底（服务被系统杀掉时）。
        runCatching { ClashCore.stopTun() }
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        scope.cancel()
        super.onDestroy()
    }
}
