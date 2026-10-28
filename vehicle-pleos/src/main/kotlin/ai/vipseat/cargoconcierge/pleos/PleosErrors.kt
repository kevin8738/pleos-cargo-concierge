package ai.vipseat.cargoconcierge.pleos

import ai.pleos.playground.vehicle.constant.ErrorCode
import ai.vipseat.cargoconcierge.core.port.FeatureStatus

/**
 * The Vehicle SDK's exception class is obfuscated (ai.pleos.playground.vehicle.callback.exception.a),
 * but it carries a public `ai.pleos.playground.vehicle.constant.ErrorCode` (vehicle-data-1.1.11).
 * We read it reflectively by return type, then fall back to message heuristics.
 */
internal object PleosErrors {
    fun errorCodeOf(e: Throwable): ErrorCode? = runCatching {
        e.javaClass.methods.firstOrNull { it.parameterCount == 0 && it.returnType == ErrorCode::class.java }
            ?.invoke(e) as? ErrorCode
    }.getOrNull()

    fun classify(e: Throwable): FeatureStatus {
        if (e is SecurityException) return FeatureStatus.PERMISSION_DENIED
        return when (errorCodeOf(e)) {
            ErrorCode.ERROR_SECURITY_PERMISSION -> FeatureStatus.PERMISSION_DENIED
            ErrorCode.ERROR_NOT_SUPPORTED_PROPERTY,
            ErrorCode.ERROR_NOT_SUPPORTED_AREA,
            ErrorCode.ERROR_NOT_SUPPORTED_VALUE_TYPE -> FeatureStatus.UNSUPPORTED
            ErrorCode.ERROR_DRIVING_RESTRICTED -> FeatureStatus.DRIVING_RESTRICTED
            null -> when {
                e.message?.contains("PERMISSION", ignoreCase = true) == true -> FeatureStatus.PERMISSION_DENIED
                e.message?.contains("NOT_SUPPORTED", ignoreCase = true) == true -> FeatureStatus.UNSUPPORTED
                e.message?.contains("DRIVING", ignoreCase = true) == true -> FeatureStatus.DRIVING_RESTRICTED
                else -> FeatureStatus.FAILED
            }
            else -> FeatureStatus.FAILED
        }
    }

    fun describe(e: Throwable): String = errorCodeOf(e)?.name ?: (e.message ?: e.javaClass.simpleName)
}
