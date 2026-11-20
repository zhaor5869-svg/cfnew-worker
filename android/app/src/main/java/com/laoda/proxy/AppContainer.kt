package com.laoda.proxy

import android.content.Context
import androidx.room.Room
import com.laoda.proxy.core.CoreManager
import com.laoda.proxy.data.AppDatabase
import com.laoda.proxy.data.ProfileRepository
import com.laoda.proxy.data.SettingsStore
import com.laoda.proxy.net.SubscriptionManager

/**
 * 应用依赖容器（模块 A）。
 * Profile、ProfileRepository、SettingsStore、SubscriptionManager、AppDatabase
 * 由模块 B 按 CONTRACTS.md 提供；这里只引用，不再创建。
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    val db: AppDatabase = Room.databaseBuilder(
        appContext,
        AppDatabase::class.java,
        "laoda-proxy.db",
    ).fallbackToDestructiveMigration().build()

    val settingsStore: SettingsStore = SettingsStore(appContext)

    val subscriptionManager: SubscriptionManager = SubscriptionManager(appContext)

    val profileRepository: ProfileRepository =
        ProfileRepository(db, appContext, subscriptionManager)

    val coreManager: CoreManager = CoreManager(appContext)
}
