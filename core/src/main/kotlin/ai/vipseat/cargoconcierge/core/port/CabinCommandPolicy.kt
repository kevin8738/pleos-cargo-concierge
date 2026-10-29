package ai.vipseat.cargoconcierge.core.port

import ai.vipseat.cargoconcierge.core.model.CabinPlan

/** Submission policy until thermal/contact tests establish a usable heating profile. */
object CabinCommandPolicy {
    data class Request(val plan: CabinPlan, val withheld: List<FeatureOutcome>)

    fun prepare(plan: CabinPlan, heldByPassenger: Boolean): Request {
        if (heldByPassenger) return Request(plan.copy(seatHeaterLevel = null, seatVentLevel = null), emptyList())
        if ((plan.seatHeaterLevel ?: 0) <= 0) return Request(plan, emptyList())
        return Request(
            plan.copy(seatHeaterLevel = null),
            listOf(FeatureOutcome(CabinFeature.SEAT_HEATER, FeatureStatus.PENDING_VALIDATION,
                "보온 열선은 실측 검증 전 적용하지 않아요")),
        )
    }
}
