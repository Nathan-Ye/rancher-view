package com.nathan.rancherview.api

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.nathan.rancherview.model.NamespaceSnapshot
import com.nathan.rancherview.model.PodInfo
import com.nathan.rancherview.model.ServiceInfo
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

class RancherApiException(message: String) : Exception(message)

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
            if (response.header("Content-Type").orEmpty().contains("text/html", ignoreCase = true)) {
                throw RancherApiException(HTML_RESPONSE_MESSAGE)
            }
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
            // Token 失效时 Rancher 可能返回登录页 HTML（HTTP 200），Gson 的原始报错无法阅读
            val parsed = try {
                JsonParser.parseString(body)
            } catch (e: JsonParseException) {
                throw RancherApiException(
                    if (body.isBlank()) "Rancher 返回了空响应，请检查网络/代理后重试" else HTML_RESPONSE_MESSAGE
                )
            }
            if (!parsed.isJsonObject) throw RancherApiException(HTML_RESPONSE_MESSAGE)
            return parsed.asJsonObject
        }
    }

    private fun apiError(code: Int, body: String): RancherApiException = when (code) {
        401, 403 -> RancherApiException("Rancher 认证失败（HTTP $code），请检查 API Token 是否有效、是否已过期")
        404 -> RancherApiException("未找到资源（HTTP 404），请检查集群 ID / 命名空间配置")
        else -> RancherApiException("请求失败（HTTP $code）：${body.take(200)}")
    }

    // Steve API 在集群异常时可能返回 "data": null，直接用 getAsJsonArray 会抛 ClassCastException
    private fun items(json: JsonObject): JsonArray =
        json.arr("data") ?: json.arr("items") ?: JsonArray()

    private fun JsonObject?.obj(key: String): JsonObject? =
        this?.get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject?.arr(key: String): JsonArray? =
        this?.get(key)?.takeIf { it.isJsonArray }?.asJsonArray

    private fun JsonObject?.str(key: String): String? =
        this?.get(key)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject?.int(key: String, default: Int): Int =
        this?.get(key)?.takeIf { it.isJsonPrimitive }?.asInt ?: default

    private fun JsonObject?.strMap(): Map<String, String> =
        this?.entrySet()
            ?.mapNotNull { (key, value) -> value.takeIf { it.isJsonPrimitive }?.asString?.let { key to it } }
            ?.toMap()
            ?: emptyMap()

    private fun parseNamespace(
        namespace: String,
        deploymentsJson: JsonObject,
        podsJson: JsonObject
    ): NamespaceSnapshot {
        val podEntries = items(podsJson).filter { it.isJsonObject }.map { item ->
            val obj = item.asJsonObject
            obj.obj("metadata").obj("labels").strMap() to parsePod(obj)
        }

        val services = items(deploymentsJson).filter { it.isJsonObject }.map { item ->
            val obj = item.asJsonObject
            val metadata = obj.obj("metadata")
            val name = metadata.str("name").orEmpty()
            val spec = obj.obj("spec")
            val status = obj.obj("status") ?: JsonObject()
            val selector = spec.obj("selector").obj("matchLabels").strMap()

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
                desiredReplicas = spec.int("replicas", 1),
                readyReplicas = status.int("readyReplicas", 0),
                updatedReplicas = status.int("updatedReplicas", 0),
                availableReplicas = status.int("availableReplicas", 0),
                age = formatAge(metadata.str("creationTimestamp")),
                pods = pods
            )
        }.filter { it.name.isNotEmpty() }.sortedBy { it.name }

        return NamespaceSnapshot(namespace, services, System.currentTimeMillis())
    }

    private fun imageOf(spec: JsonObject?): String =
        spec.obj("template")
            .obj("spec")
            .arr("containers")
            ?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
            .str("image")
            .orEmpty()

    private fun parsePod(podJson: JsonObject): PodInfo {
        val metadata = podJson.obj("metadata")
        val spec = podJson.obj("spec")
        val status = podJson.obj("status")

        val containerStatuses = status.arr("containerStatuses")
            ?.filter { it.isJsonObject }?.map { it.asJsonObject }
            .orEmpty()
        val total = spec.arr("containers")?.size() ?: containerStatuses.size
        val readyCount = containerStatuses.count { it.get("ready")?.asBoolean == true }
        val restarts = containerStatuses.sumOf { it.int("restartCount", 0) }

        val states = containerStatuses.mapNotNull { it.obj("state") }
        val reasons = states.mapNotNull { state ->
            val detail = state.obj("waiting") ?: state.obj("terminated")
            detail.str("reason")?.takeIf { it.isNotBlank() }
        }
        val failedReason = reasons.firstOrNull { it in FAILED_REASONS }
        val anyRunning = states.any { it.get("running")?.let { r -> !r.isJsonNull } == true }
        val allTerminated = states.isNotEmpty() &&
            states.all { it.get("terminated")?.let { t -> !t.isJsonNull } == true }
        val deletionStarted = listOf("deletionTimestamp", "deletionGracePeriodSeconds")
            .any { key -> metadata?.get(key)?.let { !it.isJsonNull } == true }
        // 容器已全部终止却没有 reason，通常是被优雅终止的旧 Pod
        val terminating = deletionStarted || (allTerminated && reasons.isEmpty())
        val phase = status.str("phase").orEmpty()

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

        val podImage = containerStatuses.firstOrNull().str("image")
            ?: spec.arr("containers")?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject.str("image")
        val age = formatAge(metadata.str("creationTimestamp"))

        return PodInfo(
            name = metadata.str("name").orEmpty(),
            status = displayStatus,
            ready = "$readyCount/$total",
            restarts = restarts,
            age = age,
            node = spec.str("nodeName").orEmpty(),
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
        private const val HTML_RESPONSE_MESSAGE =
            "Rancher 返回了网页而不是数据，通常是 Token 失效被跳转到了登录页，请重新配置 API Token"

        private val FAILED_REASONS = setOf(
            "CrashLoopBackOff", "ImagePullBackOff", "ErrImagePull", "InvalidImageName",
            "CreateContainerError", "RunContainerError", "ContainerCannotRun", "OOMKilled", "Error"
        )
    }
}
