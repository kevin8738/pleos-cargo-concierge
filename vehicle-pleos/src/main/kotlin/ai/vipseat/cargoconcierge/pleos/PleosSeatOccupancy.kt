package ai.vipseat.cargoconcierge.pleos

/**
 * Vehicle 2.3.2 collapses SEAT_OCCUPANCY to `raw == 1` in BOTH getter and listener.
 * On the verified Connect v2.1 image / Vehicle Service 2.3.22 (74), raw values follow
 * AOSP UNKNOWN=0, VACANT=1, OCCUPIED=2. Only SDK true proves vacancy; SDK false loses
 * the distinction between occupied, unknown and invalid. NEVER invert this Boolean.
 *
 * Vehicle is strictly pinned in build.gradle.kts. A new SDK, service or target image
 * requires revalidation or an authorized raw-enum reader before enabling this mapping.
 */
object PleosSeatOccupancy {
    fun isVerified(serviceVersion: String?, serviceCode: Long?, product: String, api: Int): Boolean =
        serviceVersion == "2.3.22" && serviceCode == 74L && product == "crp_avd_arm64" && api == 34

    fun decodeConfirmedVacancy(sdkValue: Boolean?, verified: Boolean): Boolean? =
        if (verified && sdkValue == true) false else null
}
