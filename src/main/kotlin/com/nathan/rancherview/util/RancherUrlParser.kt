package com.nathan.rancherview.util

data class RancherTarget(
    val baseUrl: String,
    val clusterId: String,
    val namespace: String
)

object RancherUrlParser {
    private val dashboardPattern = Regex(
        """^(https?://[^/]+).*?/dashboard/c/([^/]+)/explorer/[^/]+/([^/?#]+)"""
    )

    fun parse(text: String): RancherTarget? {
        val match = dashboardPattern.find(text.trim()) ?: return null
        return RancherTarget(
            baseUrl = match.groupValues[1].trimEnd('/'),
            clusterId = match.groupValues[2],
            namespace = match.groupValues[3]
        )
    }
}
