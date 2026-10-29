package ai.vipseat.cargoconcierge.core

import ai.vipseat.cargoconcierge.core.port.CabinFeature
import ai.vipseat.cargoconcierge.core.port.CabinSettingOwner
import ai.vipseat.cargoconcierge.core.port.FeatureConfirmation
import ai.vipseat.cargoconcierge.core.port.FeatureOutcome
import ai.vipseat.cargoconcierge.core.port.FeatureStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
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
        val result = s.owner.apply(18)
        assertEquals(FeatureStatus.FAILED, result.status)
        assertEquals(FeatureConfirmation.NONE, result.confirmation)
        assertNull(result.confirmedValue)
        assertTrue(s.writes.isEmpty())
        assertFalse(s.owner.needsRestore)
    }

    @Test fun `matching original does not take ownership or issue a restore`() = runTest {
        val s = Setting()
        val result = s.owner.apply(22)
        assertEquals(FeatureStatus.UNCHANGED, result.status)
        assertEquals(FeatureConfirmation.VALUE_CONFIRMED, result.confirmation)
        assertEquals("22", result.confirmedValue)
        s.owner.restore()
        assertTrue(s.writes.isEmpty())
    }

    @Test fun `success restores first original after multiple app targets`() = runTest {
        val s = Setting()
        val applied = s.owner.apply(18)
        assertEquals(FeatureStatus.APPLIED, applied.status)
        assertEquals(FeatureConfirmation.VALUE_CONFIRMED, applied.confirmation)
        assertEquals("18", applied.confirmedValue)
        s.owner.apply(20)
        val restored = s.owner.restore()
        assertEquals(FeatureStatus.APPLIED, restored.status)
        assertEquals(FeatureConfirmation.VALUE_CONFIRMED, restored.confirmation)
        assertEquals("22", restored.confirmedValue)
        assertEquals(listOf(18, 20, 22), s.writes)
        assertFalse(s.owner.needsRestore)
    }

    @Test fun `delayed restore is confirmed by reads without resending the setter`() = runTest {
        var value = 22
        var restoreSentAt: Long? = null
        val writes = mutableListOf<Int>()
        val owner = CabinSettingOwner(
            feature = CabinFeature.TEMPERATURE,
            read = {
                if (restoreSentAt?.let { testScheduler.currentTime - it >= 150 } == true) value = 22
                value
            },
            write = { target ->
                writes += target
                if (target == 18) value = target else restoreSentAt = testScheduler.currentTime
                FeatureOutcome(CabinFeature.TEMPERATURE, FeatureStatus.APPLIED)
            },
            guard = { null },
            nowMs = { testScheduler.currentTime },
        )
        assertEquals(FeatureStatus.APPLIED, owner.apply(18).status)
        assertEquals(FeatureStatus.APPLIED, owner.restore().status)
        assertEquals(listOf(18, 22), writes)
        assertTrue(testScheduler.currentTime in 150..1_000)
        assertFalse(owner.needsRestore)
    }

    @Test fun `delayed apply is confirmed within the readback budget`() = runTest {
        var sentAt: Long? = null
        val writes = mutableListOf<Int>()
        val owner = CabinSettingOwner(
            feature = CabinFeature.TEMPERATURE,
            read = { if (sentAt?.let { testScheduler.currentTime - it >= 150 } == true) 18 else 22 },
            write = { target ->
                writes += target
                sentAt = testScheduler.currentTime
                FeatureOutcome(CabinFeature.TEMPERATURE, FeatureStatus.APPLIED)
            },
            guard = { null },
            nowMs = { testScheduler.currentTime },
        )
        assertEquals(FeatureStatus.APPLIED, owner.apply(18).status)
        assertEquals(listOf(18), writes)
        assertTrue(testScheduler.currentTime in 150..1_000)
        assertTrue(owner.needsRestore)
    }

    @Test fun `old value never becomes applied after the one second budget`() = runTest {
        val s = Setting().apply { echoes = false }
        assertEquals(FeatureStatus.FAILED, s.owner.apply(18).status)
        assertEquals(1_000L, testScheduler.currentTime)
        assertEquals(listOf(18), s.writes)
        assertTrue(s.owner.needsRestore)
    }

    @Test fun `null or third value after apply acknowledgement fails without retrying`() = runTest {
        for (observed in listOf(null, 24)) {
            val readings = mutableListOf<Int?>(22, observed)
            val writes = mutableListOf<Int>()
            val owner = CabinSettingOwner(
                feature = CabinFeature.TEMPERATURE,
                read = { readings.removeAt(0) },
                write = { target -> writes += target; FeatureOutcome(CabinFeature.TEMPERATURE, FeatureStatus.APPLIED) },
                guard = { null },
            )
            val result = owner.apply(18)
            assertEquals(FeatureStatus.FAILED, result.status)
            assertEquals(FeatureConfirmation.REQUEST_ACCEPTED, result.confirmation)
            assertNull(result.confirmedValue)
            assertTrue(readings.isEmpty())
            assertEquals(listOf(18), writes)
            assertEquals(0L, testScheduler.currentTime)
            assertTrue(owner.needsRestore)
        }
    }

    @Test fun `null or third value after restore acknowledgement preserves failure`() = runTest {
        for (observed in listOf(null, 24)) {
            val readings = mutableListOf<Int?>(22, 18, 18, observed)
            val writes = mutableListOf<Int>()
            val owner = CabinSettingOwner(
                feature = CabinFeature.TEMPERATURE,
                read = { readings.removeAt(0) },
                write = { target -> writes += target; FeatureOutcome(CabinFeature.TEMPERATURE, FeatureStatus.APPLIED) },
                guard = { null },
            )
            assertEquals(FeatureStatus.APPLIED, owner.apply(18).status)
            val result = owner.restore()
            assertEquals(FeatureStatus.FAILED, result.status)
            assertEquals(FeatureConfirmation.REQUEST_ACCEPTED, result.confirmation)
            assertNull(result.confirmedValue)
            assertTrue(readings.isEmpty())
            assertEquals(listOf(18, 22), writes)
            assertEquals(0L, testScheduler.currentTime)
            assertTrue(owner.needsRestore)
        }
    }

    @Test fun `readback budget includes a stalled getter`() = runTest {
        var reads = 0
        var readCancelled = false
        val writes = mutableListOf<Int>()
        val owner = CabinSettingOwner(
            feature = CabinFeature.TEMPERATURE,
            read = {
                if (++reads == 1) 22 else {
                    try { delay(2_000); 18 } finally { readCancelled = true }
                }
            },
            write = { target -> writes += target; FeatureOutcome(CabinFeature.TEMPERATURE, FeatureStatus.APPLIED) },
            guard = { null },
        )
        assertEquals(FeatureStatus.FAILED, owner.apply(18).status)
        assertEquals(1_000L, testScheduler.currentTime)
        assertTrue(readCancelled)
        assertEquals(listOf(18), writes)
        assertTrue(owner.needsRestore)
    }

    @Test fun `caller cancellation during readback propagates without another command`() = runTest {
        var reads = 0
        var returnedResult = false
        val writes = mutableListOf<Int>()
        val owner = CabinSettingOwner(
            feature = CabinFeature.TEMPERATURE,
            read = { if (++reads == 1) 22 else awaitCancellation() },
            write = { target -> writes += target; FeatureOutcome(CabinFeature.TEMPERATURE, FeatureStatus.APPLIED) },
            guard = { null },
        )
        val job = launch { owner.apply(18); returnedResult = true }
        testScheduler.runCurrent()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertFalse(returnedResult)
        assertEquals(listOf(18), writes)
        assertTrue(owner.needsRestore)
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
        for (blocked in listOf(FeatureStatus.DRIVING_RESTRICTED, FeatureStatus.FAILED)) {
            val s = Setting()
            s.owner.apply(18)
            s.guardStatus = blocked
            val result = s.owner.restore()
            assertEquals(blocked, result.status)
            assertFalse(result.ok)
            assertEquals(FeatureConfirmation.NONE, result.confirmation)
            assertEquals(listOf(18), s.writes)
            assertTrue(s.owner.needsRestore)
            // A failed guard (including unknown/SYNC-on) must not silently clear the original.
            s.guardStatus = null
            assertEquals(FeatureStatus.APPLIED, s.owner.restore().status)
            assertEquals(listOf(18, 22), s.writes)
            assertFalse(s.owner.needsRestore)
        }
    }

    @Test fun `slow guard expires earlier property read without a command`() = runTest {
        val s = Setting().apply { guardDelay = 5_001 }
        assertEquals(FeatureStatus.FAILED, s.owner.apply(18).status)
        assertTrue(s.writes.isEmpty())
    }

    @Test fun `slow restore guard retains the original for a later fresh retry`() = runTest {
        val s = Setting()
        s.owner.apply(18)
        s.guardDelay = 5_001
        val result = s.owner.restore()
        assertEquals(FeatureStatus.FAILED, result.status)
        assertFalse(result.ok)
        assertEquals(FeatureConfirmation.NONE, result.confirmation)
        assertEquals(listOf(18), s.writes)
        assertTrue(s.owner.needsRestore)
        s.guardDelay = 0
        assertEquals(FeatureStatus.APPLIED, s.owner.restore().status)
        assertEquals(listOf(18, 22), s.writes)
        assertFalse(s.owner.needsRestore)
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

    @Test fun `confirmed value records the matching getter rather than requested target`() = runTest {
        val readings = mutableListOf(22.0f, 18.05f)
        val owner = CabinSettingOwner(
            feature = CabinFeature.TEMPERATURE,
            read = { readings.removeAt(0) },
            write = { FeatureOutcome(CabinFeature.TEMPERATURE, FeatureStatus.APPLIED) },
            guard = { null },
            matches = { a, b -> kotlin.math.abs(a - b) < 0.1f },
        )
        val result = owner.apply(18.0f)
        assertEquals(FeatureConfirmation.VALUE_CONFIRMED, result.confirmation)
        assertEquals("18.05", result.confirmedValue)
        assertNull(result.hvacPowerAtCheck)
        assertTrue(readings.isEmpty())
    }
}
