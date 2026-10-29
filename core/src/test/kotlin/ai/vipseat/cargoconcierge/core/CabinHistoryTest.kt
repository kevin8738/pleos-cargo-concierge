package ai.vipseat.cargoconcierge.core

import ai.vipseat.cargoconcierge.core.history.*
import ai.vipseat.cargoconcierge.core.model.CargoSpot
import ai.vipseat.cargoconcierge.core.port.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CabinHistoryTest {
    private val address = CabinAddress("Pleos Vehicle SDK 2.3.2", CabinProperty.TEMPERATURE, "ROW_1_RIGHT")
    private fun event(id: String = "one", op: Long = 1, phase: CabinHistoryPhase = CabinHistoryPhase.BEFORE_WRITE,
                      confirmed: String? = null, cycle: String = "cycle", area: CabinAddress = address) =
        CabinObservation(id, op, cycle, area, CargoSpot.FRONT_PASSENGER, CabinFeature.TEMPERATURE,
            1000, false, phase, "22.0", "18.0", confirmed, if (confirmed == null) null else FeatureStatus.APPLIED,
            if (confirmed == null) FeatureConfirmation.NONE else FeatureConfirmation.VALUE_CONFIRMED)

    @Test fun `owner records actual first original and precise getter without extra reads`() = runTest {
        val observations = mutableListOf<CabinObservation>()
        var value = 22f; var reads = 0
        val writes = mutableListOf<Float>()
        val owner = CabinSettingOwner(CabinFeature.TEMPERATURE,
            read = { reads++; value }, write = { writes += it; value = it + .05f; FeatureOutcome(CabinFeature.TEMPERATURE, FeatureStatus.APPLIED) },
            guard = { null }, matches = { a, b -> kotlin.math.abs(a-b) < .1f }, historyAddress = address)
        withContext(CabinHistoryContext("one", 1, CargoSpot.FRONT_PASSENGER, observations::add, wallTimeMs = { 1000 })) { owner.apply(18f) }
        assertEquals(2, reads); assertEquals(listOf(18f), writes)
        assertEquals(listOf(CabinHistoryPhase.BEFORE_WRITE, CabinHistoryPhase.REQUEST_ACCEPTED, CabinHistoryPhase.RESULT), observations.map { it.phase })
        val result = observations.last()
        assertEquals("22.0", result.original); assertEquals("18.0", result.requested); assertEquals("18.05", result.confirmed)
        val archive = observations.fold(CabinHistoryArchive().begin("one", 1000)) { a, e -> a.observe(e) }
        assertEquals(archive, CabinHistoryCodec.decode(CabinHistoryCodec.encode(archive)))
    }

    @Test fun `unknown guard denied and already equal never fabricate a sent request or original`() = runTest {
        for (mode in listOf("unknown", "guard", "equal")) {
            val observations = mutableListOf<CabinObservation>(); var writes = 0
            val owner = CabinSettingOwner(CabinFeature.TEMPERATURE,
                read = { if (mode == "unknown") null else if (mode == "equal") 18 else 22 },
                write = { writes++; FeatureOutcome(CabinFeature.TEMPERATURE, FeatureStatus.APPLIED) },
                guard = { FeatureOutcome(CabinFeature.TEMPERATURE, FeatureStatus.DRIVING_RESTRICTED) }, historyAddress = address)
            withContext(CabinHistoryContext("one", 1, CargoSpot.FRONT_PASSENGER, observations::add)) { owner.apply(18) }
            assertEquals(0, writes); assertFalse(owner.needsRestore)
            assertEquals(1, observations.size); assertNull(observations.single().requested); assertNull(observations.single().original)
            assertEquals(if (mode == "equal") "18" else null, observations.single().confirmed)
        }
    }

    @Test fun `accepted null mismatch timeout and cancellation never invent readback`() = runTest {
        for (mode in listOf("null", "third", "timeout", "cancel")) {
            var sent = false; val observations = mutableListOf<CabinObservation>()
            val owner = CabinSettingOwner(CabinFeature.TEMPERATURE,
                read = { if (!sent) 22 else when(mode) { "null" -> null; "third" -> 19; "cancel" -> awaitCancellation(); else -> 22 } },
                write = { sent = true; FeatureOutcome(CabinFeature.TEMPERATURE, FeatureStatus.APPLIED) },
                guard = { null }, historyAddress = address)
            val job = launch(CabinHistoryContext("one", 1, CargoSpot.FRONT_PASSENGER, observations::add)) { owner.apply(18) }
            runCurrent()
            if (mode == "cancel") job.cancelAndJoin() else { testScheduler.advanceUntilIdle(); job.join() }
            assertTrue(sent); assertTrue(owner.needsRestore)
            assertTrue(observations.none { it.confirmed != null })
            assertEquals(FeatureConfirmation.REQUEST_ACCEPTED, observations.last().confirmation)
        }
    }

    @Test fun `throwing sink does not change writes restore or owner lifetime`() = runTest {
        var value = 22; val writes = mutableListOf<Int>()
        val owner = CabinSettingOwner(CabinFeature.TEMPERATURE, read = { value },
            write = { value = it; writes += it; FeatureOutcome(CabinFeature.TEMPERATURE, FeatureStatus.APPLIED) }, guard = { null }, historyAddress = address)
        withContext(CabinHistoryContext("one", 1, CargoSpot.FRONT_PASSENGER, { error("unavailable") })) {
            owner.apply(18); owner.restore()
        }
        assertEquals(listOf(18, 22), writes); assertFalse(owner.needsRestore)
    }

    @Test fun `physical aliases share cycle first original while new cycles and areas stay distinct`() = runTest {
        var value = 3; val events = mutableListOf<CabinObservation>()
        val fanAddress = address.copy(property = CabinProperty.FAN_SPEED)
        val owner = CabinSettingOwner(CabinFeature.FAN, read = { value },
            write = { value = it; FeatureOutcome(CabinFeature.FAN, FeatureStatus.APPLIED) }, guard = { null }, historyAddress = fanAddress)
        withContext(CabinHistoryContext("one", 1, CargoSpot.FRONT_PASSENGER, events::add)) { owner.apply(1) }
        withContext(CabinHistoryContext("one", 2, CargoSpot.FRONT_PASSENGER, events::add)) { owner.apply(0, CabinFeature.AIRFLOW) }
        withContext(CabinHistoryContext("one", 3, CargoSpot.FRONT_PASSENGER, events::add)) { owner.restore() }
        val firstCycle = events.first().cycleId
        withContext(CabinHistoryContext("one", 4, CargoSpot.FRONT_PASSENGER, events::add)) { owner.apply(2) }
        assertNotEquals(firstCycle, events.last().cycleId)
        var archive = events.fold(CabinHistoryArchive().begin("one", 1000)) { a,e -> a.observe(e) }
        val first = archive.current!!.rows.first()
        assertEquals("3", first.original); assertEquals(setOf(CabinFeature.FAN, CabinFeature.AIRFLOW), first.features)
        assertEquals("0", first.applyRequested); assertEquals("3", first.confirmed)
        archive = archive.observe(event(area = address.copy(area = "ROW_2_LEFT")))
        assertEquals(3, archive.current!!.rows.size)
    }

    @Test fun `late callbacks and reversed wall time cannot overwrite another operation or session`() {
        var archive = CabinHistoryArchive().begin("one", 1000).observe(event(op=2).copy(atMs=500))
        archive = archive.observe(event(op=1, confirmed="18.05", phase=CabinHistoryPhase.RESULT))
        assertNull(archive.current!!.rows.single().confirmed)
        archive = archive.begin("two", 400)
        val before = archive
        archive = archive.observe(event(op=3, confirmed="18.05", phase=CabinHistoryPhase.RESULT))
        assertEquals(before, archive)
        assertEquals("one", archive.previous!!.id); assertEquals("two", archive.current!!.id)
    }

    @Test fun `previous incomplete survives normal next session and restart`() {
        val archive = CabinHistoryArchive().begin("one", 1000).observe(event())
            .afterRestart().begin("two", 2000).finish("two", true).afterRestart()
        assertEquals("one", archive.previous!!.id); assertNull(archive.current)
        assertNull(archive.previous!!.rows.single().confirmed)
        assertEquals(CabinHistoryPhase.BEFORE_WRITE, archive.previous!!.rows.single().phase)
    }

    @Test fun `empty later sessions cannot replace the last retained observed reference`() {
        val retained = CabinHistoryArchive().begin("one", 1000).observe(event()).afterRestart()
        val empty = retained.begin("empty", 2000).finish("empty", false)
        assertEquals("one", empty.afterRestart().previous!!.id)
        assertEquals("one", empty.begin("next", 3000).previous!!.id)
        assertEquals("22.0", empty.afterRestart().previous!!.rows.single().original)
    }

    @Test fun `restore uncertainty preserves prior confirmed value and its own timestamp`() {
        val archive = CabinHistoryArchive().begin("one", 1000)
            .observe(event(confirmed="18.05", phase=CabinHistoryPhase.RESULT))
            .observe(event(op=2).copy(restoring=true, requested="22.0", atMs=1500))
        val row = archive.current!!.rows.single()
        assertEquals("18.0", row.applyRequested); assertEquals("22.0", row.requested)
        assertEquals("18.05", row.confirmed); assertEquals(1000L, row.confirmedAtMs)
        assertEquals(FeatureConfirmation.NONE, row.confirmation)
        assertEquals(archive, CabinHistoryCodec.decode(CabinHistoryCodec.encode(archive)))
    }

    @Test fun `codec rejects corrupt unknown version nonfinite and malformed unit values`() {
        val valid = CabinHistoryCodec.encode(CabinHistoryArchive().begin("one", 1000).observe(event()))
        assertNotNull(CabinHistoryCodec.decode(valid))
        for (bad in listOf(null, "", "???", valid.dropLast(8), "A".repeat(CabinHistoryCodec.MAX_ENCODED_SIZE+1))) assertNull(CabinHistoryCodec.decode(bad))
        val bytes = java.util.Base64.getDecoder().decode(valid).also { it[3] = 99 }
        assertNull(CabinHistoryCodec.decode(java.util.Base64.getEncoder().encodeToString(bytes)))
        val invalid = CabinHistoryArchive().begin("one", 1000).observe(event().copy(requested="NaN"))
        assertNull(CabinHistoryCodec.decode(CabinHistoryCodec.encode(invalid)))
    }

    @Test fun `row retention bound marks gaps without inventing evidence`() {
        val archive = (1..40).fold(CabinHistoryArchive().begin("one", 1000)) { a, i -> a.observe(event(cycle="cycle$i")) }
        assertEquals(CabinHistoryArchive.MAX_ROWS, archive.current!!.rows.size)
        assertTrue(archive.current!!.gap)
    }

    @Test fun `overflow is nonblocking and visible even before consumer gets CPU`() = runTest {
        var persisted: String? = null
        val recorder = CabinHistoryRecorder(backgroundScope, { null }, { persisted=it }, { 1000 }, capacity=1)
        val id = recorder.begin()
        recorder.context(id, CargoSpot.FRONT_PASSENGER).offer(event(id))
        assertTrue(recorder.view.value.unavailable)
        runCurrent()
        assertTrue(recorder.view.value.archive.current!!.gap)
        assertNotNull(persisted)
    }

    @Test fun `storage failure preserves last valid bytes and marks display incomplete`() = runTest {
        val previous = CabinHistoryArchive().begin("old", 1000).observe(event(id="old"))
        val bytes = CabinHistoryCodec.encode(previous)
        val recorder = CabinHistoryRecorder(backgroundScope, { bytes }, { error("full") }, { 2000 })
        runCurrent(); recorder.begin(); runCurrent()
        assertTrue(recorder.view.value.unavailable)
        assertEquals("old", recorder.view.value.archive.previous!!.id)
        assertEquals(previous, CabinHistoryCodec.decode(bytes))
    }

    @Test fun `loading historical data never initializes a new owner or restores archived original`() = runTest {
        val archived = CabinHistoryArchive().begin("old", 1000).observe(event(id="old", confirmed="18.05", phase=CabinHistoryPhase.RESULT))
        val recorder = CabinHistoryRecorder(backgroundScope, { CabinHistoryCodec.encode(archived) }, {}, { 2000 })
        var writes = 0; var reads = 0
        val fresh = CabinSettingOwner(CabinFeature.TEMPERATURE, read={ reads++; 23 },
            write={ writes++; FeatureOutcome(CabinFeature.TEMPERATURE, FeatureStatus.APPLIED) }, guard={null}, historyAddress=address)
        runCurrent()
        repeat(3) { assertTrue(cabinHistoryVisible(true, true, true)); assertFalse(cabinHistoryVisible(false, true, true)) }
        assertFalse(fresh.needsRestore); fresh.restore()
        assertEquals(0, writes); assertEquals(0, reads)
        assertEquals("old", recorder.view.value.archive.previous!!.id)
    }

    @Test fun `same plan duplicate physical operations retain unfinished second request`() = runTest {
        val recorder = CabinHistoryRecorder(backgroundScope, { null }, {}, { 1000 })
        val id = recorder.begin(); runCurrent()
        var value = 22
        val owner = CabinSettingOwner(CabinFeature.TEMPERATURE, read = { value },
            write = { if (it == 19) awaitCancellation(); value = it; FeatureOutcome(CabinFeature.TEMPERATURE, FeatureStatus.APPLIED) },
            guard = { null }, historyAddress = address)
        val job = launch(recorder.context(id, CargoSpot.REAR_LEFT)) { owner.apply(18); owner.apply(19) }
        runCurrent(); job.cancelAndJoin(); runCurrent()
        val row = recorder.view.value.archive.current!!.rows.single()
        assertEquals(CabinHistoryPhase.BEFORE_WRITE, row.phase)
        assertEquals("19", row.requested); assertEquals("18", row.confirmed)
        assertEquals("22", row.original)
    }

    @Test fun `manual retry in a new scope records live owner restore and freezes old callbacks`() = runTest {
        val recorder = CabinHistoryRecorder(backgroundScope, { null }, {}, { 1000 })
        var value = 22; val writes = mutableListOf<Int>()
        val owner = CabinSettingOwner(CabinFeature.TEMPERATURE, read={ value },
            write={ value=it; writes+=it; FeatureOutcome(CabinFeature.TEMPERATURE, FeatureStatus.APPLIED) },
            guard={ null }, historyAddress=address)
        val oldId = recorder.begin()
        val oldContext = recorder.context(oldId, CargoSpot.FRONT_PASSENGER)
        withContext(oldContext) { owner.apply(18) }
        recorder.finish(oldId, false); runCurrent()
        val oldRecord = recorder.view.value.archive.current
        val retryId = recorder.begin()
        withContext(recorder.context(retryId, CargoSpot.REAR_LEFT)) { owner.restore() }
        recorder.finish(retryId, true)
        oldContext.offer(event(oldId, op=999, phase=CabinHistoryPhase.RESULT, confirmed="99.0"))
        recorder.finish(oldId, false); runCurrent()
        val archive = recorder.view.value.archive
        assertEquals(oldRecord, archive.previous)
        assertEquals(retryId, archive.current!!.id); assertTrue(archive.current!!.normalEnd)
        val row = archive.current!!.rows.single()
        assertEquals("22", row.original); assertEquals("22", row.requested); assertEquals("22", row.confirmed)
        assertEquals(CargoSpot.FRONT_PASSENGER, row.spot)
        assertNull(row.applyRequested)
        assertEquals(listOf(18, 22), writes); assertFalse(owner.needsRestore)
    }

    @Test fun `cancelled manual retry stays incomplete without accepting old session results`() = runTest {
        val recorder = CabinHistoryRecorder(backgroundScope, { null }, {}, { 1000 })
        var value=22; var restoring=false
        val owner = CabinSettingOwner(CabinFeature.TEMPERATURE,
            read={ if (restoring) awaitCancellation() else value },
            write={ value=it; if(it==22) restoring=true; FeatureOutcome(CabinFeature.TEMPERATURE, FeatureStatus.APPLIED) },
            guard={null}, historyAddress=address)
        val oldId=recorder.begin()
        withContext(recorder.context(oldId, CargoSpot.FRONT_PASSENGER)) { owner.apply(18) }
        recorder.finish(oldId, false); runCurrent()
        val retryId=recorder.begin()
        val retry=launch(recorder.context(retryId, CargoSpot.FRONT_PASSENGER)) {
            var completed=false
            try { owner.restore(); completed=true }
            finally { recorder.finish(retryId, completed) }
        }
        runCurrent(); retry.cancelAndJoin(); runCurrent()
        val archive=recorder.view.value.archive
        assertEquals(oldId, archive.previous!!.id)
        assertEquals(retryId, archive.current!!.id); assertTrue(archive.current!!.ended); assertFalse(archive.current!!.normalEnd)
        val row=archive.current!!.rows.single()
        assertEquals(CabinHistoryPhase.REQUEST_ACCEPTED, row.phase); assertNull(row.confirmed)
        assertEquals("22", row.original); assertTrue(owner.needsRestore)
    }

    @Test fun `history details require fresh parked and unrestricted UI and cannot reopen themselves`() {
        assertFalse(cabinHistoryVisible(true, true, false))
        assertFalse(cabinHistoryVisible(true, false, true))
        val closed = cabinHistoryVisible(true, true, false)
        assertFalse(cabinHistoryVisible(closed, true, true))
    }
}
