package ai.vipseat.cargoconcierge.core

import ai.vipseat.cargoconcierge.core.port.CabinFeature
import ai.vipseat.cargoconcierge.core.port.CabinSettingOwner
import ai.vipseat.cargoconcierge.core.port.FeatureOutcome
import ai.vipseat.cargoconcierge.core.port.FeatureStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CabinSettingOwnerTest {
    private class Setting {
        var value = 22
        var readable = true
        var accepts = true
        var echoes = true
        var guardStatus: FeatureStatus? = null
        var clock = 0L
        var guardDelay = 0L
        val writes = mutableListOf<Int>()
        val owner = CabinSettingOwner(
            feature = CabinFeature.TEMPERATURE,
            read = { if (readable) value else null },
            write = { target ->
                writes += target
                if (accepts && echoes) value = target
                FeatureOutcome(CabinFeature.TEMPERATURE, if (accepts) FeatureStatus.APPLIED else FeatureStatus.PERMISSION_DENIED)
            },
            guard = { clock += guardDelay; guardStatus?.let { FeatureOutcome(CabinFeature.TEMPERATURE, it) } },
            nowMs = { clock },
        )
    }

    @Test fun `missing original prevents command and creates no restore obligation`() = runTest {
        val s = Setting().apply { readable = false }
        assertEquals(FeatureStatus.FAILED, s.owner.apply(18).status)
        assertTrue(s.writes.isEmpty())
        assertFalse(s.owner.needsRestore)
    }

    @Test fun `matching original does not take ownership or issue a restore`() = runTest {
        val s = Setting()
        assertEquals(FeatureStatus.UNCHANGED, s.owner.apply(22).status)
        s.owner.restore()
        assertTrue(s.writes.isEmpty())
    }

    @Test fun `success restores first original after multiple app targets`() = runTest {
        val s = Setting()
        assertEquals(FeatureStatus.APPLIED, s.owner.apply(18).status)
        s.owner.apply(20)
        assertEquals(FeatureStatus.APPLIED, s.owner.restore().status)
        assertEquals(listOf(18, 20, 22), s.writes)
        assertFalse(s.owner.needsRestore)
    }

    @Test fun `external value change survives restore and further apply in same session`() = runTest {
        val s = Setting()
        s.owner.apply(18)
        s.value = 24
        assertEquals(FeatureStatus.UNCHANGED, s.owner.restore().status)
        assertEquals(FeatureStatus.UNCHANGED, s.owner.apply(19).status)
        assertEquals(24, s.value)
        assertEquals(listOf(18), s.writes)
        assertFalse(s.owner.needsRestore)
    }

    @Test fun `original read failure at restore preserves retry obligation`() = runTest {
        val s = Setting()
        s.owner.apply(18)
        s.readable = false
        assertEquals(FeatureStatus.FAILED, s.owner.restore().status)
        assertTrue(s.owner.needsRestore)
        s.readable = true
        assertEquals(FeatureStatus.APPLIED, s.owner.restore().status)
        assertEquals(22, s.value)
    }

    @Test fun `rejected restore remains retryable`() = runTest {
        val s = Setting()
        s.owner.apply(18)
        s.accepts = false
        assertEquals(FeatureStatus.PERMISSION_DENIED, s.owner.restore().status)
        assertTrue(s.owner.needsRestore)
        s.accepts = true
        assertEquals(FeatureStatus.APPLIED, s.owner.restore().status)
    }

    @Test fun `acknowledged setter without matching state is not applied`() = runTest {
        val s = Setting().apply { echoes = false }
        assertEquals(FeatureStatus.FAILED, s.owner.apply(18).status)
        assertTrue(s.owner.needsRestore)
        assertEquals(FeatureStatus.UNCHANGED, s.owner.restore().status)
        assertEquals(listOf(18), s.writes)
    }

    @Test fun `apply permission failure does not write a restore over unchanged property`() = runTest {
        val s = Setting().apply { accepts = false }
        assertEquals(FeatureStatus.PERMISSION_DENIED, s.owner.apply(18).status)
        assertEquals(FeatureStatus.UNCHANGED, s.owner.restore().status)
        assertEquals(listOf(18), s.writes)
    }

    @Test fun `guard is rechecked before restore and keeps pending state`() = runTest {
        val s = Setting()
        s.owner.apply(18)
        s.guardStatus = FeatureStatus.DRIVING_RESTRICTED
        assertEquals(FeatureStatus.DRIVING_RESTRICTED, s.owner.restore().status)
        assertEquals(listOf(18), s.writes)
        assertTrue(s.owner.needsRestore)
    }

    @Test fun `slow guard expires earlier property read without a command`() = runTest {
        val s = Setting().apply { guardDelay = 5_001 }
        assertEquals(FeatureStatus.FAILED, s.owner.apply(18).status)
        assertTrue(s.writes.isEmpty())
    }

    @Test fun `separate properties restore only their own successful changes`() = runTest {
        val temperature = Setting()
        val rearTemperature = Setting()
        val fan = Setting().apply { readable = false }
        temperature.owner.apply(18)
        rearTemperature.owner.apply(19)
        fan.owner.apply(3)
        temperature.value = 25
        temperature.owner.restore()
        rearTemperature.owner.restore()
        fan.owner.restore()
        assertEquals(25, temperature.value)
        assertEquals(listOf(19, 22), rearTemperature.writes)
        assertTrue(fan.writes.isEmpty())
    }
}
