package com.laoda.proxy.net

import android.content.Context
import com.laoda.proxy.data.Profile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.util.concurrent.TimeUnit

class SubscriptionManager(private val context: Context) {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    suspend fun download(url: String, destFile: File): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "LaoDaProxy/0.1")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful || response.code != 200) {
                    throw IllegalStateException("HTTP ${response.code}")
                }
                val bytes = response.body?.bytes()
                    ?: throw IllegalStateException("Empty response body")
                if (bytes.size < 1024) {
                    throw IllegalStateException("Subscription content too small (${bytes.size} bytes)")
                }
                val text = bytes.toString(Charsets.UTF_8)
                if (!text.contains("proxies") && !text.contains("proxy-groups")) {
                    throw IllegalStateException("Invalid subscription: no proxies or proxy-groups")
                }
                destFile.parentFile?.mkdirs()
                destFile.writeBytes(bytes)
            }
        }
    }

    fun validateYaml(file: File): Result<Unit> {
        return runCatching {
            if (!file.exists()) {
                throw IllegalStateException("File not found: ${file.absolutePath}")
            }
            val parsed: Any? = file.bufferedReader().use { reader ->
                Yaml().load<Any>(reader)
            }
            val top = parsed as? Map<*, *>
                ?: throw IllegalStateException("YAML top level is not a map")
            if (!top.containsKey("proxies") && !top.containsKey("proxy-groups")) {
                throw IllegalStateException("YAML has neither proxies nor proxy-groups")
            }
        }
    }

    suspend fun updateProfile(profile: Profile, destFile: File): Result<Unit> =
        withContext(Dispatchers.IO) {
            if (profile.type != "subscription") {
                return@withContext Result.failure(
                    IllegalStateException("Only subscription profiles can be updated"),
                )
            }
            val url = profile.url
                ?: return@withContext Result.failure(
                    IllegalStateException("Subscription profile has no url"),
                )
            val parent = destFile.parentFile
            parent?.mkdirs()
            val tmpFile = File(parent, "${destFile.name}.tmp")
            try {
                download(url, tmpFile).getOrElse { return@withContext Result.failure(it) }
                validateYaml(tmpFile).getOrElse { return@withContext Result.failure(it) }
                tmpFile.copyTo(destFile, overwrite = true)
                Result.success(Unit)
            } finally {
                if (tmpFile.exists()) tmpFile.delete()
            }
        }
}
