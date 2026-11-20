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
            val id = dao.insert(profile)
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
            val id = dao.insert(profile)
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
