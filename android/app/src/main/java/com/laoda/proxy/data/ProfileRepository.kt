package com.laoda.proxy.data

import android.content.Context
import android.net.Uri
import com.laoda.proxy.net.SubscriptionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

class ProfileRepository(
    private val db: AppDatabase,
    private val context: Context,
    private val subscriptionManager: SubscriptionManager,
) {

    companion object {
        const val DEFAULT_SUBSCRIPTION_URL =
            "https://cdn.jsdelivr.net/gh/zhaor5869-svg/cfnew-worker@main/jk_home_cmcc.yaml"
        const val DEFAULT_PROFILE_NAME = "家宽订阅"
    }

    private val dao: ProfileDao get() = db.profileDao()

    val profiles: Flow<List<Profile>> = dao.observeAll()

    val currentProfile: Flow<Profile?> = profiles
        .map { list -> list.firstOrNull { it.isCurrent } }
        .distinctUntilChanged()

    fun profileFile(profile: Profile): File =
        File(File(context.filesDir, "profiles"), profile.fileName)

    private fun profilesDir(): File =
        File(context.filesDir, "profiles").also { it.mkdirs() }

    suspend fun addSubscription(name: String, url: String): Result<Profile> =
        withContext(Dispatchers.IO) {
            val fileName = "sub_${System.currentTimeMillis()}.yaml"
            val destFile = File(profilesDir(), fileName)
            val downloaded = subscriptionManager.download(url, destFile)
            if (downloaded.isFailure) {
                if (destFile.exists()) destFile.delete()
                return@withContext Result.failure(
                    downloaded.exceptionOrNull() ?: IllegalStateException("Download failed"),
                )
            }
            val validated = subscriptionManager.validateYaml(destFile)
            if (validated.isFailure) {
                if (destFile.exists()) destFile.delete()
                return@withContext Result.failure(
                    validated.exceptionOrNull() ?: IllegalStateException("Invalid YAML"),
                )
            }
            val now = System.currentTimeMillis()
            val profile = Profile(
                name = name,
                type = "subscription",
                url = url,
                fileName = fileName,
                updatedAt = now,
                isCurrent = false,
            )
            // insert 失败也要包成 Result.failure 返回，否则异常直接抛到
            // ViewModel，界面上什么提示都没有，用户只看到“没反应”。
            val id = runCatching { dao.insert(profile) }.getOrElse {
                if (destFile.exists()) destFile.delete()
                return@withContext Result.failure(it)
            }
            Result.success(profile.copy(id = id))
        }

    suspend fun addFromFile(name: String, sourceUri: Uri): Result<Profile> =
        withContext(Dispatchers.IO) {
            val fileName = "file_${System.currentTimeMillis()}.yaml"
            val destFile = File(profilesDir(), fileName)
            try {
                val input = context.contentResolver.openInputStream(sourceUri)
                    ?: return@withContext Result.failure(
                        IllegalStateException("Cannot open file: $sourceUri"),
                    )
                input.use { inputStream ->
                    destFile.outputStream().use { output ->
                        inputStream.copyTo(output)
                    }
                }
            } catch (e: Exception) {
                if (destFile.exists()) destFile.delete()
                return@withContext Result.failure(e)
            }
            val validated = subscriptionManager.validateYaml(destFile)
            if (validated.isFailure) {
                if (destFile.exists()) destFile.delete()
                return@withContext Result.failure(
                    validated.exceptionOrNull() ?: IllegalStateException("Invalid YAML"),
                )
            }
            val now = System.currentTimeMillis()
            val profile = Profile(
                name = name,
                type = "file",
                url = null,
                fileName = fileName,
                updatedAt = now,
                isCurrent = false,
            )
            // insert 失败也要包成 Result.failure 返回，否则异常直接抛到
            // ViewModel，界面上什么提示都没有，用户只看到“没反应”。
            val id = runCatching { dao.insert(profile) }.getOrElse {
                if (destFile.exists()) destFile.delete()
                return@withContext Result.failure(it)
            }
            Result.success(profile.copy(id = id))
        }

    /** 粘贴配置内容导入（订阅服务器有 Cloudflare 验证、App 直连被 403 时用）：
     * 用户在手机浏览器里打开订阅地址，等验证通过后全选复制配置内容，
     * 再粘贴到这里。支持明文 YAML 和 base64 编码的订阅内容。 */
    suspend fun addFromContent(name: String, content: String): Result<Profile> =
        withContext(Dispatchers.IO) {
            val trimmed = content.trim()
            if (trimmed.length < 100) {
                return@withContext Result.failure(
                    IllegalStateException("内容太短，不像一份完整的配置"),
                )
            }
            val text = if (trimmed.contains("proxies") || trimmed.contains("proxy-groups")) {
                trimmed
            } else {
                val decoded = runCatching {
                    android.util.Base64.decode(
                        trimmed,
                        android.util.Base64.DEFAULT,
                    ).toString(Charsets.UTF_8)
                }.getOrNull()
                if (decoded != null &&
                    (decoded.contains("proxies") || decoded.contains("proxy-groups"))
                ) {
                    decoded
                } else {
                    return@withContext Result.failure(
                        IllegalStateException("内容里没有 proxies 或 proxy-groups，请确认粘贴的是完整配置"),
                    )
                }
            }
            val fileName = "paste_${System.currentTimeMillis()}.yaml"
            val destFile = File(profilesDir(), fileName)
            destFile.writeText(text, Charsets.UTF_8)
            val validated = subscriptionManager.validateYaml(destFile)
            if (validated.isFailure) {
                if (destFile.exists()) destFile.delete()
                return@withContext Result.failure(
                    validated.exceptionOrNull() ?: IllegalStateException("Invalid YAML"),
                )
            }
            val profile = Profile(
                name = name,
                type = "file",
                url = null,
                fileName = fileName,
                updatedAt = System.currentTimeMillis(),
                isCurrent = false,
            )
            // insert 失败也要包成 Result.failure 返回，否则异常直接抛到
            // ViewModel，界面上什么提示都没有，用户只看到“没反应”。
            val id = runCatching { dao.insert(profile) }.getOrElse {
                if (destFile.exists()) destFile.delete()
                return@withContext Result.failure(it)
            }
            Result.success(profile.copy(id = id))
        }

    suspend fun updateProfile(profile: Profile): Result<Unit> =
        withContext(Dispatchers.IO) {
            if (profile.type == "subscription") {
                val result = subscriptionManager.updateProfile(profile, profileFile(profile))
                if (result.isSuccess) {
                    dao.touch(profile.id, System.currentTimeMillis())
                }
                result
            } else {
                val result = subscriptionManager.validateYaml(profileFile(profile))
                if (result.isSuccess) {
                    dao.touch(profile.id, System.currentTimeMillis())
                }
                result
            }
        }

    suspend fun deleteProfile(profile: Profile) = withContext(Dispatchers.IO) {
        val wasCurrent = profile.isCurrent
        dao.delete(profile)
        val file = profileFile(profile)
        if (file.exists()) file.delete()
        if (wasCurrent) {
            val remaining = dao.observeAll().first()
            val first = remaining.firstOrNull()
            if (first != null) {
                dao.clearCurrent()
                dao.markCurrent(first.id)
            }
        }
    }

    suspend fun setCurrent(profile: Profile) = withContext(Dispatchers.IO) {
        dao.clearCurrent()
        dao.markCurrent(profile.id)
    }

    suspend fun ensureDefaultProfile(): Profile = withContext(Dispatchers.IO) {
        val existing = dao.observeAll().first()
        if (existing.isNotEmpty()) {
            return@withContext existing.firstOrNull { it.isCurrent } ?: existing.first()
        }
        val fileName = "sub_${System.currentTimeMillis()}.yaml"
        val destFile = File(profilesDir(), fileName)
        // 下载失败也先 insert，文件缺失时 CoreManager 启动会报错并提示用户更新订阅。
        runCatching {
            subscriptionManager.download(DEFAULT_SUBSCRIPTION_URL, destFile).getOrThrow()
            subscriptionManager.validateYaml(destFile).getOrThrow()
        }.onFailure {
            if (destFile.exists()) destFile.delete()
        }
        val profile = Profile(
            name = DEFAULT_PROFILE_NAME,
            type = "subscription",
            url = DEFAULT_SUBSCRIPTION_URL,
            fileName = fileName,
            updatedAt = System.currentTimeMillis(),
            isCurrent = false,
        )
        val id = dao.insert(profile)
        val inserted = profile.copy(id = id)
        dao.clearCurrent()
        dao.markCurrent(id)
        inserted.copy(isCurrent = true)
    }
}
