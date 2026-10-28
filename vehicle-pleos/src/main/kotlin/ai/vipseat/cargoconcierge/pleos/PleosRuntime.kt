package ai.vipseat.cargoconcierge.pleos

import ai.pleos.playground.location.fused.constant.PleosPermission as FusedPermission

/** Runtime permissions the Pleos flavor must request from the Activity. */
object PleosRuntime {
    val runtimePermissions: List<String> = listOf(FusedPermission.FUSED_LOCATION)
}
