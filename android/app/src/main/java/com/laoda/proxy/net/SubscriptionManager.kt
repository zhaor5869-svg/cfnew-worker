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
                // 用 Clash 客户端的 UA：有些订阅面板只认常见客户端，
                // 自定义 UA 更容易被 Cloudflare 当成机器人拦下。
                .header("User-Agent", "ClashforAndroid/2.5.12")
                .header("Accept", "*/*")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful || response.code != 200) {
                    if (response.code == 403) {
                        throw IllegalStateException(
                            "HTTP 403：订阅服务器拒绝了 App 的直接访问（Cloudflare 验证）。" +
                                "请用「粘贴配置内容」导入：在手机浏览器里打开订阅地址，" +
                                "等验证通过后复制配置内容粘贴进来。",
                        )
                    }
                    throw IllegalStateException("HTTP ${response.code}")
                }
                val bytes = response.body?.bytes()
                    ?: throw IllegalStateException("Empty response body")
                if (bytes.size < 1024) {
                    throw IllegalStateException("Subscription content too small (${bytes.size} bytes)")
                }
                // 有些订阅服务返回的是 base64 编码后的 Clash 配置，
                // 明文里没有 proxies/proxy-groups。先按原文判断，
                // 不通过就试 base64 解码，解出来有 proxies 才算有效。
                val rawText = bytes.toString(Charsets.UTF_8)
                val text = if (rawText.contains("proxies") || rawText.contains("proxy-groups")) {
                    rawText
                } else {
                    val decoded = runCatching {
                        android.util.Base64.decode(
                            rawText.trim(),
                            android.util.Base64.DEFAULT,
                        ).toString(Charsets.UTF_8)
                    }.getOrNull()
                    if (decoded != null &&
                        (decoded.contains("proxies") || decoded.contains("proxy-groups"))
                    ) {
                        decoded
                    } else {
                        throw IllegalStateException(
                            "Invalid subscription: no proxies or proxy-groups",
                        )
                    }
                }
                destFile.parentFile?.mkdirs()
                destFile.writeText(text, Charsets.UTF_8)
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
