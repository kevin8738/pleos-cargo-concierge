package ai.vipseat.cargoconcierge.core

import ai.vipseat.cargoconcierge.core.engine.Action
import ai.vipseat.cargoconcierge.core.engine.CargoState
import ai.vipseat.cargoconcierge.core.engine.CargoStateMachine
import ai.vipseat.cargoconcierge.core.engine.Effect
import ai.vipseat.cargoconcierge.core.mock.MockVehicle
import ai.vipseat.cargoconcierge.core.model.CargoKind
import ai.vipseat.cargoconcierge.core.model.CargoPreset
import ai.vipseat.cargoconcierge.core.model.CargoSpot
import ai.vipseat.cargoconcierge.core.model.DoorId
import ai.vipseat.cargoconcierge.core.model.Gear
import ai.vipseat.cargoconcierge.core.model.VehicleSignal
import ai.vipseat.cargoconcierge.core.port.ApplyConsent
import ai.vipseat.cargoconcierge.core.port.CabinFeature
import ai.vipseat.cargoconcierge.core.port.FeatureStatus
import ai.vipseat.cargoconcierge.core.voice.VoiceIntent
import ai.vipseat.cargoconcierge.core.voice.VoiceIntentParser
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The VIP may ride anywhere: passenger seat, rear left/centre/right, trunk. */
class CargoSpotTest {
    private fun sm() = CargoStateMachine()
    private fun CargoStateMachine.v(signal: VehicleSignal, now: Long) = dispatch(Action.Vehicle(signal), now)
    private fun CargoStateMachine.park(now: Long) { v(VehicleSignal.GearChanged(Gear.PARK), now); v(VehicleSignal.Speed(0.0), now) }
    private fun CargoStateMachine.doorCycle(door: DoorId, now: Long): List<Effect> { v(VehicleSignal.Door(door, true), now); return v(VehicleSignal.Door(door, false), now + 100) }
    private fun List<Effect>.spoken() = filterIsInstance<Effect.Speak>().map { it.text }

    @Test
    fun `a rear door cycle offers the sheet for that rear seat`() {
        val m = sm(); m.park(0)
        m.doorCycle(DoorId.REAR_RIGHT, 1_000)
        val o = m.state as CargoState.Offering
        assertEquals(CargoSpot.REAR_RIGHT, o.spot)
        assertEquals(DoorId.REAR_RIGHT, o.trigger)
        m.dispatch(Action.SelectPreset(CargoPreset.HOT_FOOD), 2_000)
        val g = m.state as CargoState.Guarding
        assertEquals(CargoSpot.REAR_RIGHT, g.spot)
        assertFalse(g.heldByPassenger)
        assertEquals(CargoSpot.REAR_RIGHT, m.preferredSpot)
    }

    @Test
    fun `occupancy suppresses only the offer for that seat`() {
        val m = sm(); m.park(0)
        m.v(VehicleSignal.SeatOccupancy(CargoSpot.REAR_RIGHT, true), 500)
        m.doorCycle(DoorId.REAR_RIGHT, 1_000)
        assertEquals(CargoState.Idle, m.state)
        m.doorCycle(DoorId.PASSENGER, 2_000)
        assertEquals(CargoSpot.FRONT_PASSENGER, (m.state as CargoState.Offering).spot)
    }

    @Test
    fun `an occupied seat can still be chosen - the passenger holds the VIP and the heater stays off`() {
        val m = sm(); m.park(0)
        m.v(VehicleSignal.SeatOccupancy(CargoSpot.FRONT_PASSENGER, true), 500)
        m.dispatch(Action.SelectSpot(CargoSpot.FRONT_PASSENGER), 600)
        val fx = m.dispatch(Action.VoicePreset(CargoPreset.HOT_FOOD), 1_000)
        val g = m.state as CargoState.Guarding
        assertTrue(g.heldByPassenger)
        val apply = fx.filterIsInstance<Effect.ApplyCabin>().single()
        assertTrue(apply.heldByPassenger)
        assertEquals(CargoSpot.FRONT_PASSENGER, apply.spot)
        assertTrue(fx.spoken().single().contains("들고"))
        // the same seat reporting occupied again, or another seat, must not end the session
        m.v(VehicleSignal.SeatOccupancy(CargoSpot.FRONT_PASSENGER, true), 2_000)
        m.v(VehicleSignal.SeatOccupancy(CargoSpot.REAR_LEFT, true), 2_100)
        assertTrue(m.state is CargoState.Guarding)
    }

    @Test
    fun `someone sitting into the VIP's empty seat ends the session - another seat does not`() {
        val m = sm(); m.park(0)
        m.dispatch(Action.SelectSpot(CargoSpot.REAR_LEFT), 100)
        m.dispatch(Action.SelectPreset(CargoPreset.HOT_FOOD), 1_000)
        m.v(VehicleSignal.SeatOccupancy(CargoSpot.FRONT_PASSENGER, true), 2_000)
        assertTrue(m.state is CargoState.Guarding)
        val fx = m.v(VehicleSignal.SeatOccupancy(CargoSpot.REAR_LEFT, true), 3_000)
        assertEquals(CargoState.Idle, m.state)
        assertTrue(fx.contains(Effect.RestoreCabin))
        assertTrue(fx.spoken().single().startsWith("뒷좌석 왼쪽에 사람이 탔어요"))
    }

    @Test
    fun `moving the VIP while parked re-applies the cabin at the new spot - refused while driving`() {
        val m = sm(); m.park(0)
        m.dispatch(Action.SelectPreset(CargoPreset.CAKE), 1_000)
        val fx = m.dispatch(Action.SelectSpot(CargoSpot.REAR_RIGHT), 2_000)
        assertTrue(fx.indexOf(Effect.RestoreCabin) < fx.indexOfFirst { it is Effect.ApplyCabin })
        assertEquals(CargoSpot.REAR_RIGHT, fx.filterIsInstance<Effect.ApplyCabin>().single().spot)
        assertEquals(CargoSpot.REAR_RIGHT, (m.state as CargoState.Guarding).spot)
        m.v(VehicleSignal.GearChanged(Gear.DRIVE), 3_000)
        m.v(VehicleSignal.Speed(10.0), 3_100)
        val fx2 = m.dispatch(Action.SelectSpot(CargoSpot.TRUNK), 4_000)
        assertTrue(fx2.filterIsInstance<Effect.ApplyCabin>().isEmpty())
        assertEquals(CargoSpot.REAR_RIGHT, (m.state as CargoState.Guarding).spot)
    }

    @Test
    fun `the arrival reminder names the spot`() {
        val m = sm(); m.park(0)
        m.dispatch(Action.VoicePreset(CargoPreset.GROCERIES, CargoSpot.TRUNK), 1_000)
        m.v(VehicleSignal.GearChanged(Gear.DRIVE), 2_000)
        m.v(VehicleSignal.Speed(10.0), 3_000)
        m.v(VehicleSignal.Speed(0.0), 50_000)
        m.v(VehicleSignal.GearChanged(Gear.PARK), 50_500)
        val fx = m.v(VehicleSignal.Door(DoorId.DRIVER, true), 51_000)
        val a = m.state as CargoState.Arrived
        assertEquals(CargoSpot.TRUNK, a.spot)
        assertEquals(CargoSpot.TRUNK, a.report.spot)
        assertEquals("트렁크 장바구니 챙기세요", fx.spoken().single())
    }

    @Test
    fun `voice phrases carry the spot`() {
        fun load(t: String) = VoiceIntentParser.parse(t) as VoiceIntent.LoadPreset
        load("뒷자리 오른쪽에 떡볶이 실었어").let { assertEquals(CargoSpot.REAR_RIGHT, it.spot); assertEquals(CargoKind.HOT_FOOD, it.kind); assertEquals("떡볶이", it.preset.displayName) }
        load("트렁크에 수박 실었어").let { assertEquals(CargoSpot.TRUNK, it.spot); assertEquals(CargoKind.GENERAL, it.kind); assertTrue(it.isNew); assertEquals("수박", it.preset.displayName) }
        load("조수석 뒤에 강아지 탔어").let { assertEquals(CargoSpot.REAR_RIGHT, it.spot); assertEquals(CargoKind.PET, it.kind) }
        load("운전석 뒤에 노트북 놨어").let { assertEquals(CargoSpot.REAR_LEFT, it.spot); assertEquals(CargoKind.ELECTRONICS, it.kind); assertEquals("노트북", it.preset.displayName) }
        load("뒷좌석 가운데 케이크 실었어").let { assertEquals(CargoSpot.REAR_CENTER, it.spot); assertEquals(CargoKind.CAKE, it.kind) }
        load("케이크 뒷자리에 실었어").let { assertEquals(CargoSpot.REAR_RIGHT, it.spot); assertEquals("케이크", it.preset.displayName) }
        assertNull(load("케이크 실었어").spot)
        assertEquals(CargoSpot.FRONT_PASSENGER, load("조수석에 도자기 실었어").spot)
    }

    @Test
    fun `mock vehicle maps the plan onto the spot`() = runTest {
        val v = MockVehicle(); v.connect()
        v.setOccupied(CargoSpot.REAR_RIGHT, true)
        val rear = v.applyCabin(CargoPreset.HOT_FOOD.cabin, ApplyConsent(false), CargoSpot.REAR_RIGHT)
        assertEquals(FeatureStatus.APPLIED, rear.status(CabinFeature.SEAT_HEATER))   // occupied → no consent needed
        assertEquals(2, v.cabin.rearHeaterRight)
        assertEquals(0, v.cabin.heater)
        assertEquals(FeatureStatus.UNSUPPORTED, rear.status(CabinFeature.AIRFLOW))   // rear vents are not controllable
        v.restoreCabin()
        val center = v.applyCabin(CargoPreset.HOT_FOOD.cabin, ApplyConsent(true), CargoSpot.REAR_CENTER)
        assertEquals(FeatureStatus.UNSUPPORTED, center.status(CabinFeature.SEAT_HEATER))
        v.restoreCabin()
        val trunk = v.applyCabin(CargoPreset.CAKE.cabin, ApplyConsent(true), CargoSpot.TRUNK)
        assertTrue(trunk.outcomes.isNotEmpty())
        assertTrue(trunk.outcomes.all { it.status == FeatureStatus.UNSUPPORTED })
        assertEquals(22.0, v.cabin.tempC, 1e-9)
        assertEquals(listOf(CargoSpot.REAR_RIGHT, CargoSpot.REAR_CENTER, CargoSpot.TRUNK), v.appliedSpots)
    }
}
