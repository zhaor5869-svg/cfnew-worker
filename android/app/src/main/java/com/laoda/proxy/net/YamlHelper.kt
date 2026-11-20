package com.laoda.proxy.net

import org.yaml.snakeyaml.Yaml
import java.io.File

object YamlHelper {

    data class GroupSummary(
        val name: String,
        val type: String,
        val proxies: List<String>,
    )

    data class ConfigSummary(
        val mode: String?,
        val proxyGroups: List<GroupSummary>,
        val proxyNames: List<String>,
        val rules: List<String>,
    )

    private fun emptySummary() = ConfigSummary(
        mode = null,
        proxyGroups = emptyList(),
        proxyNames = emptyList(),
        rules = emptyList(),
    )

    fun parseConfig(file: File): ConfigSummary {
        return try {
            if (!file.exists()) return emptySummary()
            val parsed: Any? = file.bufferedReader().use { reader ->
                Yaml().load<Any>(reader)
            }
            val top = parsed as? Map<*, *> ?: return emptySummary()

            val mode = top["mode"] as? String

            val proxyGroups = (top["proxy-groups"] as? List<*>).orEmpty().mapNotNull { item ->
                val group = item as? Map<*, *> ?: return@mapNotNull null
                val name = group["name"] as? String ?: return@mapNotNull null
                val type = group["type"] as? String ?: ""
                val proxies = (group["proxies"] as? List<*>).orEmpty()
                    .mapNotNull { it?.toString() }
                GroupSummary(name = name, type = type, proxies = proxies)
            }

            val proxyNames = (top["proxies"] as? List<*>).orEmpty().mapNotNull { item ->
                (item as? Map<*, *>)?.get("name") as? String
            }

            val rules = (top["rules"] as? List<*>).orEmpty()
                .mapNotNull { it?.toString() }

            ConfigSummary(
                mode = mode,
                proxyGroups = proxyGroups,
                proxyNames = proxyNames,
                rules = rules,
            )
        } catch (_: Exception) {
            emptySummary()
        }
    }
}
