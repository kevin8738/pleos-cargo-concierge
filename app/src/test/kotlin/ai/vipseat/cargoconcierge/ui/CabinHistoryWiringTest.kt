package ai.vipseat.cargoconcierge.ui

import ai.vipseat.cargoconcierge.core.engine.CargoState
import ai.vipseat.cargoconcierge.core.history.*
import ai.vipseat.cargoconcierge.core.model.CargoSpot
import ai.vipseat.cargoconcierge.core.port.*
import ai.vipseat.cargoconcierge.vm.UiState
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CabinHistoryWiringTest {
    private fun row(phase: CabinHistoryPhase = CabinHistoryPhase.RESULT) = CabinHistoryRow(
        "cycle", CabinAddress("Pleos Vehicle SDK 2.3.2", CabinProperty.TEMPERATURE, "ROW_1_RIGHT"),
        CargoSpot.FRONT_PASSENGER, setOf(CabinFeature.TEMPERATURE), 1, 1000,
        "22.0", "18.0", "18.05", "18.0", 1000, false, phase,
        FeatureStatus.APPLIED, FeatureConfirmation.VALUE_CONFIRMED)

    @Test fun `display preserves requested versus observed precision and uncertainty`() {
        val row = row()
        assertEquals("18.0 °C", row.value(row.applyRequested))
        assertEquals("18.05 °C", row.value(row.confirmed))
        assertEquals("기록 없음 / 미확인", row.value(null))
        assertTrue(historyStatus(row(CabinHistoryPhase.BEFORE_WRITE)).contains("실제 요청/변경 여부 미확인"))
        assertTrue(historyStatus(row).contains("현재 상태는 알 수 없음"))
    }

    @Test fun `loaded history UiState remains idle unknown motion no retry authority`() = runTest {
        val archive = CabinHistoryArchive(current = CabinHistorySession("past", 1000, rows=listOf(row())))
        val store = CabinHistoryRecorder(backgroundScope, { CabinHistoryCodec.encode(archive) }, {})
        runCurrent()
        val ui = UiState(cabinHistory = store.view.value)
        assertEquals(CargoState.Idle, ui.state)
        assertFalse(ui.cabinRestorePending); assertFalse(ui.showCabinHistory); assertFalse(ui.cabinHistoryAvailable)
        assertFalse(ui.snapshot.isParkedStill); assertNull(ui.snapshot.passengerTempC)
        assertEquals("past", ui.cabinHistory.archive.previous!!.id)
    }

    @Test fun `screen callback and session wiring expose no historical control action`() {
        val root = listOf(File("."), File(".."), File("app")).first { File(it, "src/main/kotlin/ai/vipseat/cargoconcierge/ui/CabinHistoryScreen.kt").exists() }
        fun source(path: String) = File(root, "src/main/kotlin/ai/vipseat/cargoconcierge/$path").readText()
        val screen = source("ui/CabinHistoryScreen.kt")
        for (forbidden in listOf("VehiclePort", "CargoSession", "retryRestore", "restoreCabin", "applyCabin", "startGuardService", "setCabinSessionPending")) {
            assertFalse("history screen must not access $forbidden", screen.contains(forbidden))
        }
        val runtime = source("runtime/CargoSession.kt")
        val toggle = runtime.substringAfter("fun showCabinHistory(show: Boolean)").substringBefore("fun selectPreset")
        assertTrue(toggle.contains("sm.canControlCabin(now())"))
        assertFalse(toggle.contains("dispatch(")); assertFalse(toggle.contains("graph.vehicle")); assertFalse(toggle.contains("graph.settings"))
        assertTrue(source("ui/CargoRoute.kt").indexOf("!ui.interactive") < source("ui/CargoRoute.kt").indexOf("ui.showCabinHistory"))
        assertTrue(runtime.contains("showCabinHistory = it.showCabinHistory && !r"))
        assertTrue(runtime.contains("cabinHistoryAvailable = sm.canControlCabin(t)"))
        val retry = runtime.substringAfter("fun retryRestore()").substringBefore("fun resetOfferSuppression")
        assertTrue(retry.contains("val retryHistoryId = graph.cabinHistory.begin()"))
        assertTrue(retry.contains("handle(Effect.RestoreCabin, cabinGate.advance(), retryHistoryId)"))
        assertTrue(runtime.contains("retryHistoryId?.let { graph.cabinHistory.finish(it, normal = retryCompleted) }"))
    }
}
