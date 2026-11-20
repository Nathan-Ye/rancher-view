package com.nathan.rancherview.model

enum class DeployPhase { READY, ROLLING, FAILED }

data class PodInfo(
    val name: String,
    val status: String,
    val ready: String,
    val restarts: Int,
    val age: String,
    val node: String,
    val image: String,
    val failed: Boolean,
    val terminating: Boolean
)

data class ServiceInfo(
    val name: String,
    val image: String,
    val desiredReplicas: Int,
    val readyReplicas: Int,
    val updatedReplicas: Int,
    val availableReplicas: Int,
    val age: String,
    val pods: List<PodInfo>
) {
    val phase: DeployPhase
        get() {
            if (pods.any { it.failed }) return DeployPhase.FAILED
            if (desiredReplicas <= 0) return DeployPhase.READY
            val done = readyReplicas >= desiredReplicas &&
                updatedReplicas >= desiredReplicas &&
                availableReplicas >= desiredReplicas
            return if (done) DeployPhase.READY else DeployPhase.ROLLING
        }

    val readyRatio: String get() = "$readyReplicas/$desiredReplicas"

    val imageTag: String get() = image.substringAfterLast('/')
}

data class NamespaceSnapshot(
    val namespace: String,
    val services: List<ServiceInfo>,
    val fetchedAt: Long
) {
    val total: Int get() = services.size
    val readyCount: Int get() = services.count { it.phase == DeployPhase.READY }
    val rollingCount: Int get() = services.count { it.phase == DeployPhase.ROLLING }
    val failedCount: Int get() = services.count { it.phase == DeployPhase.FAILED }

    val phase: DeployPhase
        get() = when {
            failedCount > 0 -> DeployPhase.FAILED
            rollingCount > 0 -> DeployPhase.ROLLING
            else -> DeployPhase.READY
        }
}
