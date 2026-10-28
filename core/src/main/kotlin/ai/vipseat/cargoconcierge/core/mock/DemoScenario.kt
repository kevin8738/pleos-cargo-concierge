package ai.vipseat.cargoconcierge.core.mock

import ai.vipseat.cargoconcierge.core.model.CargoKind
import ai.vipseat.cargoconcierge.core.model.DoorId
import ai.vipseat.cargoconcierge.core.model.Gear
import ai.vipseat.cargoconcierge.core.model.TurnDirection

/** One step of the scripted PT demo. [atMs] is relative to scenario start. */
data class DemoStep(val atMs: Long, val caption: String, val run: (DemoActors) -> Unit)

/** What a step may touch. [selectPreset] goes through the state machine like a tap would. */
class DemoActors(
    val vehicle: MockVehicle,
    val navi: MockNavi,
    val selectPreset: (CargoKind) -> Unit,
)

/**
 * The 3-minute PT condensed into ~70 s of car signals (the talk track fills the rest — see
 * demo/PT_SCRIPT.md). Pure data, so the same timeline drives the in-app player, the adb scripts
 * and the unit test that replays it against the state machine.
 *
 * Speeds ramp at ≈1 m/s² (0.10 g) so that only the *intended* moments cross the 0.15 g cake limit:
 *   20 s  45° @ 30 km/h  → 0.13 g  (85 %, soft chime)
 *   26 s 100° @ 30 km/h  → 0.29 g  (over → TTS coaching)
 *   33 s navi sharp curve → "100미터 앞 급커브, 조금 줄여 부드럽게"
 *   41 s  85° @ 22 km/h  → 0.13 g  (slowed down as coached: chime only)
 *   51 s 30→12 km/h in 1 s → 0.51 g (hard brake → TTS coaching)
 *   70 s P + driver door → "조수석 케이크 챙기세요" + trip report
 */
object DemoScenario {
    val cakeRun: List<DemoStep> = listOf(
        DemoStep(0, "베이커리 앞 정차 · 기어 P") { a -> a.vehicle.setGear(Gear.PARK); a.vehicle.setSpeedKph(0.0); a.vehicle.setPassengerOccupied(false) },
        DemoStep(1_500, "조수석 도어 열림") { a -> a.vehicle.setDoor(DoorId.PASSENGER, true) },
        DemoStep(4_000, "케이크 상자를 시트에 놓고 도어 닫힘 → 프리셋 제안") { a -> a.vehicle.setDoor(DoorId.PASSENGER, false) },
        DemoStep(7_000, "\"케이크\" 선택 → 조수석 18℃ · 열선 OFF") { a -> a.selectPreset(CargoKind.CAKE) },
        DemoStep(11_000, "출발 · 기어 D") { a -> a.vehicle.setGear(Gear.DRIVE) },
        DemoStep(12_000, "부드러운 가속 10 km/h") { a -> a.vehicle.setSpeedKph(10.0) },
        DemoStep(14_000, "가속 17 km/h") { a -> a.vehicle.setSpeedKph(17.0) },
        DemoStep(16_000, "가속 24 km/h") { a -> a.vehicle.setSpeedKph(24.0) },
        DemoStep(18_000, "순항 30 km/h") { a -> a.vehicle.setSpeedKph(30.0) },
        DemoStep(20_000, "완만한 코너 · 핸들 45° (≈0.13G, 80 % 경고음)") { a -> a.vehicle.setSteering(45.0) },
        DemoStep(23_000, "핸들 복원") { a -> a.vehicle.setSteering(0.0); a.vehicle.setSpeedKph(30.0) },
        DemoStep(26_000, "급한 코너 · 핸들 100° (≈0.29G, 한계 초과 → TTS 코칭)") { a -> a.vehicle.setSteering(100.0) },
        DemoStep(29_000, "핸들 복원") { a -> a.vehicle.setSteering(0.0); a.vehicle.setSpeedKph(30.0) },
        DemoStep(33_000, "내비: 100 m 앞 급커브 → \"조금 줄여 부드럽게\"") { a -> a.navi.sharpCurve(100, TurnDirection.RIGHT) },
        DemoStep(35_000, "감속 27 km/h") { a -> a.vehicle.setSpeedKph(27.0) },
        DemoStep(37_000, "감속 24 km/h") { a -> a.vehicle.setSpeedKph(24.0) },
        DemoStep(39_000, "감속 22 km/h") { a -> a.vehicle.setSpeedKph(22.0) },
        DemoStep(41_000, "코너 통과 · 핸들 85° @ 22 km/h (≈0.13G, 한계 안)") { a -> a.vehicle.setSteering(85.0) },
        DemoStep(44_000, "핸들 복원 · 25 km/h") { a -> a.vehicle.setSteering(0.0); a.vehicle.setSpeedKph(25.0) },
        DemoStep(47_000, "순항 30 km/h") { a -> a.vehicle.setSpeedKph(30.0) },
        DemoStep(50_000, "순항 30 km/h") { a -> a.vehicle.setSpeedKph(30.0) },
        DemoStep(51_000, "급제동 30→12 km/h (≈0.5G → TTS 코칭)") { a -> a.vehicle.setBrake(true, 60.0); a.vehicle.setSpeedKph(12.0) },
        DemoStep(52_000, "정지") { a -> a.vehicle.setSpeedKph(0.0); a.vehicle.setBrake(false) },
        DemoStep(56_000, "재출발 10 km/h") { a -> a.vehicle.setSpeedKph(10.0) },
        DemoStep(59_000, "20 km/h") { a -> a.vehicle.setSpeedKph(20.0) },
        DemoStep(62_000, "목적지 앞 감속 12 km/h") { a -> a.vehicle.setSpeedKph(12.0) },
        DemoStep(64_000, "감속 6 km/h") { a -> a.vehicle.setSpeedKph(6.0) },
        DemoStep(66_000, "정차") { a -> a.vehicle.setSpeedKph(0.0) },
        DemoStep(68_000, "기어 P") { a -> a.vehicle.setGear(Gear.PARK) },
        DemoStep(70_000, "운전석 도어 열림 → \"조수석 케이크 챙기세요\" · 리포트") { a -> a.vehicle.setDoor(DoorId.DRIVER, true) },
    )

    val durationMs: Long get() = cakeRun.last().atMs + 1_000
}
