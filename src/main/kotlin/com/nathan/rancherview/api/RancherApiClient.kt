package com.nathan.rancherview.api

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nathan.rancherview.model.NamespaceSnapshot
import com.nathan.rancherview.model.PodInfo
import com.nathan.rancherview.model.ServiceInfo
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

class RancherApiException(message: String, val authError: Boolean = false) : Exception(message)

class RancherApiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    fun fetchNamespace(
        baseUrl: String,
        token: String,
        clusterId: String,
        namespace: String
    ): NamespaceSnapshot {
        val deploymentsJson = getJson(
            "$baseUrl/k8s/clusters/$clusterId/v1/apps.deployments/$namespace",
            token
        )
        val podsJson = getJson(
            "$baseUrl/k8s/clusters/$clusterId/v1/pods/$namespace",
            token
        )
        return parseNamespace(namespace, deploymentsJson, podsJson)
    }

    fun fetchLog(
        baseUrl: String,
        token: String,
        clusterId: String,
        namespace: String,
        podName: String,
        tailLines: Int = 300
    ): String {
        val request = Request.Builder()
            .url("$baseUrl/k8s/clusters/$clusterId/api/v1/namespaces/$namespace/pods/$podName/log?tailLines=$tailLines")
            .header("Authorization", "Bearer $token")
            .header("Cache-Control", "no-cache")
            .header("Pragma", "no-cache")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw apiError(response.code, body)
            return body
        }
    }

    private fun getJson(url: String, token: String): JsonObject {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Cache-Control", "no-cache")
            .header("Pragma", "no-cache")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw apiError(response.code, body)
            return JsonParser.parseString(body).asJsonObject
        }
    }

    private fun apiError(code: Int, body: String): RancherApiException = when (code) {
        401, 403 -> RancherApiException("Rancher 认证失败（HTTP $code），请检查 API Token 是否有效、是否已过期", authError = true)
        404 -> RancherApiException("未找到资源（HTTP 404），请检查集群 ID / 命名空间配置")
        else -> RancherApiException("请求失败（HTTP $code）：${body.take(200)}")
    }

    private fun items(json: JsonObject): JsonArray =
        json.getAsJsonArray("data") ?: json.getAsJsonArray("items") ?: JsonArray()

    private fun parseNamespace(
        namespace: String,
        deploymentsJson: JsonObject,
        podsJson: JsonObject
    ): NamespaceSnapshot {
        val podEntries = items(podsJson).map { item ->
            val obj = item.asJsonObject
            val labels = obj.getAsJsonObject("metadata")?.getAsJsonObject("labels")
                ?.entrySet()?.associate { it.key to it.value.asString }
                ?: emptyMap()
            labels to parsePod(obj)
        }

        val services = items(deploymentsJson).map { item ->
            val obj = item.asJsonObject
            val metadata = obj.getAsJsonObject("metadata")
            val name = metadata?.get("name")?.asString.orEmpty()
            val spec = obj.getAsJsonObject("spec")
            val status = obj.getAsJsonObject("status") ?: JsonObject()
            val selector = spec?.getAsJsonObject("selector")?.getAsJsonObject("matchLabels")
                ?.entrySet()?.associate { it.key to it.value.asString }
                ?: emptyMap()

            val pods = if (selector.isEmpty()) {
                emptyList()
            } else {
                podEntries
                    .filter { (labels, _) -> selector.all { (key, value) -> labels[key] == value } }
                    .map { it.second }
                    .sortedBy { it.name }
            }

            ServiceInfo(
                name = name,
                image = imageOf(spec),
                desiredReplicas = spec?.get("replicas")?.asInt ?: 1,
                readyReplicas = status.get("readyReplicas")?.asInt ?: 0,
                updatedReplicas = status.get("updatedReplicas")?.asInt ?: 0,
                availableReplicas = status.get("availableReplicas")?.asInt ?: 0,
                age = formatAge(metadata?.get("creationTimestamp")?.asString),
                pods = pods
            )
        }.filter { it.name.isNotEmpty() }.sortedBy { it.name }

        return NamespaceSnapshot(namespace, services, System.currentTimeMillis())
    }

    private fun imageOf(spec: JsonObject?): String =
        spec?.getAsJsonObject("template")
            ?.getAsJsonObject("spec")
            ?.getAsJsonArray("containers")
            ?.firstOrNull()?.asJsonObject
            ?.get("image")?.asString
            .orEmpty()

    private fun parsePod(podJson: JsonObject): PodInfo {
        val metadata = podJson.getAsJsonObject("metadata")
        val spec = podJson.getAsJsonObject("spec")
        val status = podJson.getAsJsonObject("status")

        val containerStatuses = status?.getAsJsonArray("containerStatuses")
            ?.map { it.asJsonObject }
            .orEmpty()
        val total = spec?.getAsJsonArray("containers")?.size() ?: containerStatuses.size
        val readyCount = containerStatuses.count { it.get("ready")?.asBoolean == true }
        val restarts = containerStatuses.sumOf { it.get("restartCount")?.asInt ?: 0 }

        val states = containerStatuses.mapNotNull { it.getAsJsonObject("state") }
        val reasons = states.mapNotNull { state ->
            val detail = state.getAsJsonObject("waiting") ?: state.getAsJsonObject("terminated")
            detail?.get("reason")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
        }
        val failedReason = reasons.firstOrNull { it in FAILED_REASONS }
        val anyRunning = states.any { it.get("running")?.let { r -> !r.isJsonNull } == true }
        val allTerminated = states.isNotEmpty() &&
            states.all { it.get("terminated")?.let { t -> !t.isJsonNull } == true }
        val deletionStarted = listOf("deletionTimestamp", "deletionGracePeriodSeconds")
            .any { key -> metadata?.get(key)?.let { !it.isJsonNull } == true }
        // 容器已全部终止却没有 reason，通常是被优雅终止的旧 Pod
        val terminating = deletionStarted || (allTerminated && reasons.isEmpty())
        val phase = status?.get("phase")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

        // 正在删除的旧 Pod（滚动发布淘汰）不参与失败判定
        val failed = !terminating && (failedReason != null || phase == "Failed")
        val displayStatus = when {
            terminating -> "Terminating"
            failedReason != null -> failedReason
            reasons.isNotEmpty() -> reasons.first()
            phase.isNotBlank() -> phase
            anyRunning -> "Running"
            else -> "Unknown"
        }

        val podImage = containerStatuses.firstOrNull()?.get("image")?.asString
            ?: spec?.getAsJsonArray("containers")?.firstOrNull()?.asJsonObject?.get("image")?.asString
        val age = formatAge(metadata?.get("creationTimestamp")?.asString)

        return PodInfo(
            name = metadata?.get("name")?.asString.orEmpty(),
            status = displayStatus,
            ready = "$readyCount/$total",
            restarts = restarts,
            age = age,
            node = spec?.get("nodeName")?.asString.orEmpty(),
            image = podImage.orEmpty(),
            failed = failed,
            terminating = terminating
        )
    }

    private fun formatAge(createdAt: String?): String {
        if (createdAt.isNullOrBlank()) return "-"
        val created = runCatching { Instant.parse(createdAt) }.getOrNull() ?: return "-"
        val seconds = Duration.between(created, Instant.now()).seconds.coerceAtLeast(0)
        return when {
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> "${seconds / 60}m"
            seconds < 86400 -> "${seconds / 3600}h"
            else -> "${seconds / 86400}d"
        }
    }

    companion object {
        private val FAILED_REASONS = setOf(
            "CrashLoopBackOff", "ImagePullBackOff", "ErrImagePull", "InvalidImageName",
            "CreateContainerError", "RunContainerError", "ContainerCannotRun", "OOMKilled", "Error"
        )
    }
}
