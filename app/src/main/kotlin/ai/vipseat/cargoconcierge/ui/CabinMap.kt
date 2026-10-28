package ai.vipseat.cargoconcierge.ui

import ai.vipseat.cargoconcierge.R
import ai.vipseat.cargoconcierge.core.model.CargoSpot
import ai.vipseat.cargoconcierge.core.model.DoorId
import ai.vipseat.cargoconcierge.core.model.Gear
import ai.vipseat.cargoconcierge.core.model.VehicleSnapshot
import ai.vipseat.cargoconcierge.ui.theme.Champagne
import ai.vipseat.cargoconcierge.ui.theme.Hairline
import ai.vipseat.cargoconcierge.ui.theme.Leaf
import ai.vipseat.cargoconcierge.ui.theme.Line
import ai.vipseat.cargoconcierge.ui.theme.Mint
import ai.vipseat.cargoconcierge.ui.theme.Mist
import ai.vipseat.cargoconcierge.ui.theme.MistDim
import ai.vipseat.cargoconcierge.ui.theme.Panel
import ai.vipseat.cargoconcierge.ui.theme.PanelHigh
import ai.vipseat.cargoconcierge.vm.UiState
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Right column of the home screen: a top view of the cabin where the driver picks the VIP's spot
 * (passenger seat, rear left/centre/right, trunk). Seats the occupant-classification sensor reports as
 * taken glow green — they stay selectable, because a passenger can hold the VIP. Live gear/speed/steering
 * sit above the map so the demo signals remain visible.
 */
@Composable
fun CabinMapPanel(ui: UiState, modifier: Modifier = Modifier, onSelect: (CargoSpot) -> Unit) {
    val s = ui.snapshot
    Panel(modifier) {
        Column(Modifier.fillMaxSize()) {
            SectionTitle("VIP 자리 고르기", icon = R.drawable.ic_car, trailing = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Dot(Leaf)
                    Text("LIVE", style = MaterialTheme.typography.labelSmall, color = Leaf)
                }
            })
            Text("${ui.vehicleSource} · 착좌 센서(OCS)가 탑승을 초록으로 표시해요", style = MaterialTheme.typography.bodySmall, color = MistDim)
            Spacer(Modifier.height(14.dp))
            LiveStrip(s, ui)
            Spacer(Modifier.height(16.dp))
            CarTopView(s, ui.spot, onSelect, Modifier.weight(1f))
            Spacer(Modifier.height(12.dp))
            Text(
                "문이 닫힌 자리로 자동 제안돼요 · 사람이 타 있어도 들고 탈 수 있어 선택할 수 있어요 (열선·통풍은 건드리지 않아요)",
                style = MaterialTheme.typography.bodySmall, color = MistDim,
            )
        }
    }
}

@Composable
private fun LiveStrip(s: VehicleSnapshot, ui: UiState) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MiniStat("기어", gearLabel(s.gear), R.drawable.ic_gear, Modifier.weight(1f))
        MiniStat("속도", s.speedMps?.let { "${(it * 3.6).roundToInt()} km/h" } ?: "—", R.drawable.ic_speed, Modifier.weight(1f))
        MiniStat("핸들", s.steeringDeg?.let { "${it.roundToInt()}°" } ?: "—", R.drawable.ic_steering, Modifier.weight(1f))
        MiniStat("브레이크", when (s.brakePressed) { true -> "밟음"; false -> "뗌"; null -> "—" }, R.drawable.ic_brake, Modifier.weight(1f))
        MiniStat("내비", if (ui.navigating) "안내 중" else "대기", R.drawable.ic_nav, Modifier.weight(1f))
    }
}

@Composable
private fun MiniStat(label: String, value: String, @DrawableRes icon: Int, modifier: Modifier) {
    Column(modifier.cardSurface(16.dp, PanelHigh).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LineIcon(icon, size = 16.dp)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MistDim)
        }
        Text(value, style = MaterialTheme.typography.titleMedium, color = Mist, maxLines = 1)
    }
}

@Composable
private fun CarTopView(s: VehicleSnapshot, selected: CargoSpot, onSelect: (CargoSpot) -> Unit, modifier: Modifier) {
    Column(modifier.fillMaxWidth().cardSurface(44.dp, PanelHigh).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // windscreen
        Box(Modifier.fillMaxWidth(0.6f).align(Alignment.CenterHorizontally).height(6.dp).clip(RoundedCornerShape(50)).background(Line))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DriverCell(Modifier.weight(1f).fillMaxHeight())
            SpotCell(CargoSpot.FRONT_PASSENGER, s, selected, onSelect, Modifier.weight(1f).fillMaxHeight())
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(CargoSpot.REAR_LEFT, CargoSpot.REAR_CENTER, CargoSpot.REAR_RIGHT).forEach { sp ->
                SpotCell(sp, s, selected, onSelect, Modifier.weight(1f).fillMaxHeight())
            }
        }
        SpotCell(CargoSpot.TRUNK, s, selected, onSelect, Modifier.fillMaxWidth().weight(0.7f))
    }
}

@Composable
private fun DriverCell(modifier: Modifier) {
    val shape = RoundedCornerShape(22.dp)
    Column(modifier.clip(shape).background(Panel).border(1.dp, Hairline, shape).padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LineIcon(R.drawable.ic_steering, tint = MistDim, size = 22.dp)
            Text("운전석", style = MaterialTheme.typography.titleMedium, color = MistDim)
        }
        Text("운전자 · 선택 안 함", style = MaterialTheme.typography.bodySmall, color = MistDim)
    }
}

/** One selectable place. Occupied → green fill; selected → mint ring; both → "들고 타기". */
@Composable
private fun SpotCell(spot: CargoSpot, s: VehicleSnapshot, selected: CargoSpot, onSelect: (CargoSpot) -> Unit, modifier: Modifier) {
    val isSel = spot == selected
    val occ = s.occupancy[spot]
    val occupied = occ == true
    val doorOpen = spot.door?.let { s.doors[it] == true } ?: false
    val shape = RoundedCornerShape(22.dp)
    val fill = when {
        occupied -> Leaf.copy(alpha = 0.20f)
        isSel -> Mint.copy(alpha = 0.14f)
        else -> Panel
    }
    val border = when {
        isSel -> Mint
        occupied -> Leaf.copy(alpha = 0.7f)
        doorOpen -> Mint.copy(alpha = 0.5f)
        else -> Hairline
    }
    Column(
        modifier.clip(shape).background(fill).border(if (isSel) 2.dp else 1.dp, border, shape).clickable { onSelect(spot) }.padding(14.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LineIcon(
                    if (spot == CargoSpot.TRUNK) R.drawable.ic_trunk else R.drawable.ic_seat,
                    tint = when { occupied -> Leaf; isSel -> Mint; else -> MistDim }, size = 22.dp,
                )
                Text(spot.short, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            }
            if (isSel) Chip("VIP 자리", color = Mint)
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                when {
                    occupied -> "● 탑승 중"
                    doorOpen -> "문 열림"
                    occ == false -> "비어 있음"
                    spot == CargoSpot.TRUNK -> when (s.doors[DoorId.TRUNK]) { true -> "열림"; false -> "닫힘"; null -> "—" }
                    else -> "—"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = when { occupied -> Leaf; doorOpen -> Mint; else -> MistDim },
            )
            Text(
                if (isSel && occupied) "들고 타기 · 열선·통풍은 그대로" else capabilityNote(spot),
                style = MaterialTheme.typography.bodySmall, color = if (isSel && occupied) Champagne else MistDim, maxLines = 2,
            )
        }
    }
}

private fun capabilityNote(spot: CargoSpot): String = when (spot) {
    CargoSpot.FRONT_PASSENGER -> "공조 · 열선 · 통풍"
    CargoSpot.REAR_LEFT, CargoSpot.REAR_RIGHT -> "열선 · 후석 존(차종별)"
    CargoSpot.REAR_CENTER -> "가드만 · 공조 공유"
    CargoSpot.TRUNK -> "공조 없음 · 가드 + 도착 챙김"
}

private fun gearLabel(g: Gear) = when (g) { Gear.PARK -> "P"; Gear.REVERSE -> "R"; Gear.NEUTRAL -> "N"; Gear.DRIVE -> "D"; Gear.UNKNOWN -> "—" }

/** Bottom strip of the home screen: the event log laid out sideways, newest card on the left. */
@Composable
fun EventLogStrip(ui: UiState, modifier: Modifier = Modifier) {
    Panel(modifier, padding = 20.dp) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(150.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LineIcon(R.drawable.ic_wave, tint = Mint, size = 22.dp)
                    Text("이벤트 로그", style = MaterialTheme.typography.titleMedium)
                }
                Text("최근 항목이 왼쪽 · 주입 신호와 실제 호출 모두 기록", style = MaterialTheme.typography.bodySmall, color = MistDim)
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f).fillMaxHeight()) {
                itemsIndexed(ui.log.asReversed().take(16)) { _, line ->
                    Row(
                        Modifier.width(300.dp).fillMaxHeight().cardSurface(16.dp, PanelHigh).padding(12.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Dot(if (line.text.startsWith("TTS:")) Champagne else MistDim.copy(alpha = 0.5f), 6.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(line.text, style = MaterialTheme.typography.bodySmall, maxLines = 4)
                    }
                }
            }
        }
    }
}
