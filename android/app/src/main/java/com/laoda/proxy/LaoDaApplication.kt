package com.laoda.proxy

import android.app.Application
import android.util.Log
import com.laoda.proxy.core.ClashCore

/**
 * 应用入口（模块 A）。
 * onCreate 时加载一次 libmihomo（失败只记日志，不抛异常）。
 */
class LaoDaApplication : Application() {

    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        try {
            ClashCore.ensureLoaded(applicationInfo.nativeLibraryDir)
        } catch (e: Exception) {
            Log.e("LaoDaApplication", "ClashCore.ensureLoaded failed", e)
        }
    }
}
