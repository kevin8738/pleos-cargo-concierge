package ai.vipseat.cargoconcierge.pleos

import org.junit.Assert.*
import org.junit.Test

class PleosSeatOccupancyTest {
    @Test fun `verified SDK true proves vacancy but false and null remain unknown`() {
        assertEquals(false, PleosSeatOccupancy.decodeConfirmedVacancy(true, true))
        assertNull(PleosSeatOccupancy.decodeConfirmedVacancy(false, true))
        assertNull(PleosSeatOccupancy.decodeConfirmedVacancy(null, true))
    }

    @Test fun `unverified service or image never grants vacancy`() {
        assertTrue(PleosSeatOccupancy.isVerified("2.3.22", 74, "crp_avd_arm64", 34))
        assertFalse(PleosSeatOccupancy.isVerified("2.3.23", 74, "crp_avd_arm64", 34))
        assertFalse(PleosSeatOccupancy.isVerified("2.3.22", 75, "crp_avd_arm64", 34))
        assertFalse(PleosSeatOccupancy.isVerified("2.3.22", 74, "actual-car", 34))
        assertFalse(PleosSeatOccupancy.isVerified("2.3.22", 74, "crp_avd_arm64", 35))
        assertFalse(PleosSeatOccupancy.isVerified(null, null, "crp_avd_arm64", 34))
        assertNull(PleosSeatOccupancy.decodeConfirmedVacancy(true, false))
        assertNull(PleosSeatOccupancy.decodeConfirmedVacancy(false, false))
    }
}
