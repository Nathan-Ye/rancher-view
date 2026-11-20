package com.nathan.rancherview.util

import com.nathan.rancherview.model.DeployPhase

object RancherText {

    fun servicePhase(phase: DeployPhase): String = when (phase) {
        DeployPhase.READY -> RancherBundle.message("phase.ready")
        DeployPhase.ROLLING -> RancherBundle.message("phase.rolling")
        DeployPhase.FAILED -> RancherBundle.message("phase.failed")
    }

    fun namespacePhase(phase: DeployPhase): String = when (phase) {
        DeployPhase.READY -> RancherBundle.message("phase.nsReady")
        DeployPhase.ROLLING -> RancherBundle.message("phase.nsRolling")
        DeployPhase.FAILED -> RancherBundle.message("phase.nsFailed")
    }
}
