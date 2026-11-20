package com.laoda.proxy.ui

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.laoda.proxy.LaoDaApplication
import com.laoda.proxy.data.ThemeMode
import com.laoda.proxy.service.ProxyVpnService
import com.laoda.proxy.ui.connections.ConnectionsScreen
import com.laoda.proxy.ui.home.HomeScreen
import com.laoda.proxy.ui.logs.LogsScreen
import com.laoda.proxy.ui.profiles.ProfilesScreen
import com.laoda.proxy.ui.proxies.ProxiesScreen
import com.laoda.proxy.ui.settings.SettingsScreen
import com.laoda.proxy.ui.theme.LaoDaProxyTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 应用主 Activity（模块 C 重写：旧文件引用已删除的旧逻辑和
 * 旧服务字段，已整文件重写）。
 *
 * 职责：ensureDefaultProfile、VPN 授权（VpnService.prepare）、主题决定、
 * 底部导航（5 个 tab + logs 目的地）。
 * 启动/停止 VPN 的实际发 Intent 逻辑在 HomeViewModel 里；这里只负责
 * 授权流程：授权通过后才发 ACTION_START。
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private val container by lazy { (application as LaoDaApplication).container }

    /** 授权通过后要启动的档案 id（-1 = 用当前档案）。 */
    private var pendingProfileId: Long = -1L

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpnService(pendingProfileId)
        } else {
            Log.i(TAG, "user declined VPN permission")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 确保默认档案存在（IO 协程，出错只记日志，不阻塞启动）。
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { container.ensureDefaultProfile() }
                .onFailure { Log.e(TAG, "ensureDefaultProfile failed", it) }
        }

        setContent {
            val themeMode by container.settingsStore.theme.collectAsState(
                initial = ThemeMode.SYSTEM,
            )
            val darkTheme = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            LaoDaProxyTheme(darkTheme = darkTheme) {
                AppNav(onVpnStartRequest = ::requestVpnStart)
            }
        }
    }

    /**
     * HomeViewModel 请求启动 VPN 时调这里：先走 VpnService.prepare 授权，
     * 已授权直接启动，未授权弹系统授权页，授权通过后在 launcher 回调里启动。
     */
    fun requestVpnStart(profileId: Long) {
        pendingProfileId = profileId
        val intent = VpnService.prepare(this)
        if (intent == null) {
            // 已有授权，直接启动。
            startVpnService(profileId)
        } else {
            vpnPermissionLauncher.launch(intent)
        }
    }

    private fun startVpnService(profileId: Long) {
        val intent = Intent(this, ProxyVpnService::class.java).apply {
            action = ProxyVpnService.ACTION_START
            putExtra(ProxyVpnService.EXTRA_PROFILE_ID, profileId)
        }
        startForegroundService(intent)
    }
}

/** 底部 tab 定义：路由 / 标题 / 图标。 */
private data class TabItem(val route: String, val label: String, val icon: ImageVector)

private val TAB_ITEMS = listOf(
    TabItem(NavRoutes.HOME, "首页", Icons.Filled.Home),
    TabItem(NavRoutes.PROXIES, "代理", Icons.Filled.List),
    TabItem(NavRoutes.PROFILES, "配置", Icons.Filled.Folder),
    TabItem(NavRoutes.CONNECTIONS, "连接", Icons.Filled.SwapHoriz),
    TabItem(NavRoutes.SETTINGS, "设置", Icons.Filled.Settings),
)

@Composable
private fun AppNav(onVpnStartRequest: (Long) -> Unit) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            // logs 页不在 tab 栏，进去后隐藏底部导航。
            if (currentRoute != NavRoutes.LOGS) {
                NavigationBar {
                    TAB_ITEMS.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = NavRoutes.HOME,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(NavRoutes.HOME) {
                HomeScreen(
                    onNavigateToLogs = { navController.navigate(NavRoutes.LOGS) },
                    onNavigateToProfiles = { navController.navigate(NavRoutes.PROFILES) },
                    onVpnStartRequest = onVpnStartRequest,
                )
            }
            composable(NavRoutes.PROXIES) {
                ProxiesScreen()
            }
            composable(NavRoutes.PROFILES) {
                ProfilesScreen()
            }
            composable(NavRoutes.CONNECTIONS) {
                ConnectionsScreen()
            }
            composable(NavRoutes.SETTINGS) {
                SettingsScreen()
            }
            composable(NavRoutes.LOGS) {
                LogsScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
