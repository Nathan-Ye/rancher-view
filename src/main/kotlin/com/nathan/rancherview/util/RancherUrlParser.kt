package com.nathan.rancherview.util

import java.net.URI

data class RancherTarget(
    val baseUrl: String,
    val clusterId: String,
    val namespace: String
)

object RancherUrlParser {
    private val dashboardPattern = Regex(
        """^(https?://.+?)/dashboard/c/([^/]+)/explorer/[^/?#]+/([^/?#]+)""",
        RegexOption.IGNORE_CASE
    )
    private val baseUrlPattern = Regex("""^https?://[^/?#\s]+(?:/[^/?#\s]*)*$""", RegexOption.IGNORE_CASE)

    fun parse(text: String): RancherTarget? {
        val match = dashboardPattern.find(text.trim()) ?: return null
        val baseUrl = normalizeBaseUrl(match.groupValues[1]) ?: return null
        return RancherTarget(
            baseUrl = baseUrl,
            clusterId = match.groupValues[2],
            namespace = match.groupValues[3]
        )
    }

    /**
     * 归一化为 scheme://host[:port][/prefix]：去掉尾斜杠和 Rancher UI 的 /v3 后缀，
     * scheme/host 统一小写（同一台服务器的凭据 key 才能稳定），非法时返回 null。
     */
    fun normalizeBaseUrl(raw: String): String? {
        var text = raw.trim().trimEnd('/')
        if (text.isEmpty()) return null
        if (text.endsWith("/v3", ignoreCase = true)) {
            text = text.dropLast(3).trimEnd('/')
        }
        if (!baseUrlPattern.matches(text)) return null
        if (text.contains("/dashboard", ignoreCase = true)) return null
        val uri = try {
            URI(text)
        } catch (e: Exception) {
            return null
        }
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host ?: return null
        if (uri.userInfo != null || uri.query != null || uri.fragment != null) return null
        val port = if (uri.port >= 0) ":${uri.port}" else ""
        val path = uri.path.orEmpty().trimEnd('/')
        return "$scheme://${host.lowercase()}$port$path"
    }
}
