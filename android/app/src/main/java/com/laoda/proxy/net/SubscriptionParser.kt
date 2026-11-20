package com.laoda.proxy.net

import com.laoda.proxy.data.Node
import java.net.URLDecoder
import java.util.Base64

/**
 * 订阅导入（阶段 2，Cloudflare 分支）。
 * 支持：
 *  - VLESS 分享链接：vless://uuid@host:port?encryption=none&security=tls&sni=...#name
 *  - Trojan 分享链接：trojan://password@host:port?sni=...#name
 *  - Clash YAML 里 proxies: 下的 "- name:" 行（只提名字，用于列表展示，不解析完整 OpenVPN 证书）
 *
 * 不支持：sing-box JSON、Stash / Surge 专属格式。
 * 家宽订阅是 Clash 格式，mihomo 内核 >= 1.19.25 才能用 openvpn + dialer-proxy。
 * 本文件只做解析，不联网、不保存密码到日志。
 */
object SubscriptionParser {

    fun parseVless(link: String): Node? {
        if (!link.startsWith("vless://")) return null
        return try {
            val body = link.removePrefix("vless://")
            val name = body.substringAfter("#", "").let { URLDecoder.decode(it, "UTF-8") }
            val main = body.substringBefore("#")
            val uuid = main.substringBefore("@")
            val hostPort = main.substringAfter("@").substringBefore("?")
            val host = hostPort.substringBefore(":")
            val port = hostPort.substringAfter(":").toInt()
            Node(name = name.ifEmpty { "VLESS $host" }, host = host, port = port, token = uuid)
        } catch (_: Exception) {
            null
        }
    }

    fun parseTrojan(link: String): Node? {
        if (!link.startsWith("trojan://")) return null
        return try {
            val body = link.removePrefix("trojan://")
            val name = body.substringAfter("#", "").let { URLDecoder.decode(it, "UTF-8") }
            val main = body.substringBefore("#")
            val password = main.substringBefore("@")
            val hostPort = main.substringAfter("@").substringBefore("?")
            val host = hostPort.substringBefore(":")
            val port = hostPort.substringAfter(":").toInt()
            Node(name = name.ifEmpty { "Trojan $host" }, host = host, port = port, token = password)
        } catch (_: Exception) {
            null
        }
    }

    /** 解析 Base64 订阅文本（V2Ray 格式），每行一个分享链接。 */
    fun parseBase64Subscription(text: String): List<Node> {
        val decoded = try {
            String(Base64.getDecoder().decode(text.trim()), Charsets.UTF_8)
        } catch (_: Exception) {
            return emptyList()
        }
        return decoded.lines()
            .mapNotNull { line ->
                parseVless(line.trim()) ?: parseTrojan(line.trim())
            }
    }

    /** 从 Clash YAML 文本里提取 proxies 的名字列表，用于展示家宽节点。 */
    fun parseClashProxyNames(yaml: String): List<String> {
        val names = mutableListOf<String>()
        var inProxies = false
        for (raw in yaml.lines()) {
            val line = raw.trim()
            if (line == "proxies:") { inProxies = true; continue }
            if (inProxies && line.startsWith("proxy-groups:")) break
            if (inProxies && line.startsWith("- name:")) {
                val name = line.removePrefix("- name:").trim().trim('"', '\'')
                if (name.isNotEmpty()) names.add(name)
            }
        }
        return names
    }
}
