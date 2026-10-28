package ai.vipseat.cargoconcierge.ui

import ai.vipseat.cargoconcierge.R
import ai.vipseat.cargoconcierge.core.model.CargoSpot
import ai.vipseat.cargoconcierge.core.model.DoorId
import ai.vipseat.cargoconcierge.core.model.Gear
import ai.vipseat.cargoconcierge.ui.theme.Leaf
import ai.vipseat.cargoconcierge.ui.theme.Mint
import ai.vipseat.cargoconcierge.ui.theme.Mist
import ai.vipseat.cargoconcierge.ui.theme.MistDim
import ai.vipseat.cargoconcierge.vm.UiState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Right-hand column (parked only): live vehicle snapshot + event log. Judges love seeing the signals. */
@Composable
fun StatusColumn(ui: UiState, modifier: Modifier = Modifier) {
    val s = ui.snapshot
    Column(modifier, verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Panel(Modifier.fillMaxWidth()) {
            Column {
                SectionTitle("차량 신호", icon = R.drawable.ic_car, trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Dot(Leaf)
                        Text("LIVE", style = MaterialTheme.typography.labelSmall, color = Leaf)
                    }
                })
                Text(ui.vehicleSource, style = MaterialTheme.typography.bodySmall, color = MistDim, modifier = Modifier.padding(bottom = 10.dp))
                KeyValue("기어", gearLabel(s.gear), R.drawable.ic_gear)
                KeyValue("속도", s.speedMps?.let { "${(it * 3.6).roundToInt()} km/h" } ?: "—", R.drawable.ic_speed)
                KeyValue("핸들 각도", s.steeringDeg?.let { "${it.roundToInt()}°" } ?: "—", R.drawable.ic_steering)
                KeyValue("브레이크", when (s.brakePressed) { true -> "밟음" + (s.brakePercent?.let { " ${it.roundToInt()}%" } ?: ""); false -> "뗌"; null -> "—" }, R.drawable.ic_brake)
                KeyValue("조수석 도어", doorLabel(s, DoorId.PASSENGER), R.drawable.ic_door, if (s.doors[DoorId.PASSENGER] == true) Mint else Mist)
                KeyValue("운전석 도어", doorLabel(s, DoorId.DRIVER), R.drawable.ic_door, if (s.doors[DoorId.DRIVER] == true) Mint else Mist)
                KeyValue("트렁크", doorLabel(s, DoorId.TRUNK), R.drawable.ic_trunk, if (s.doors[DoorId.TRUNK] == true) Mint else Mist)
                KeyValue("조수석 착좌", when (s.passengerOccupied) { true -> "있음"; false -> "비어 있음"; null -> "—" }, R.drawable.ic_seat, if (s.passengerOccupied == true) Leaf else Mist)
                KeyValue(
                    "뒷좌석 착좌", REAR_SEATS.joinToString(" · ") { "${it.short.removePrefix("뒤 ")} ${occMark(s.occupancy[it])}" }, R.drawable.ic_seat,
                    if (REAR_SEATS.any { s.occupancy[it] == true }) Leaf else Mist,
                )
                KeyValue(
                    "뒷문 (왼·오)", "${doorLabel(s, DoorId.REAR_LEFT)} · ${doorLabel(s, DoorId.REAR_RIGHT)}", R.drawable.ic_door,
                    if (s.doors[DoorId.REAR_LEFT] == true || s.doors[DoorId.REAR_RIGHT] == true) Mint else Mist,
                )
                KeyValue("조수석 설정온도", s.passengerTempC?.let { String.format(java.util.Locale.US, "%.1f℃", it) } ?: "—", R.drawable.ic_thermo)
                KeyValue("주행 모드", s.drivingMode.name, R.drawable.ic_car)
                KeyValue("내비", if (ui.navigating) "경로 안내 중" else "대기", R.drawable.ic_nav)
                if (s.sourceDetail.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(s.sourceDetail, style = MaterialTheme.typography.bodySmall, color = MistDim)
                }
            }
        }
        Panel(Modifier.fillMaxWidth().weight(1f)) {
            Column {
                SectionTitle("이벤트 로그", icon = R.drawable.ic_wave)
                LazyColumn(reverseLayout = true, modifier = Modifier.fillMaxWidth()) {
                    items(ui.log.asReversed()) { line ->
                        Row(verticalAlignment = Alignment.Top) {
                            Dot(if (line.text.startsWith("TTS:")) ai.vipseat.cargoconcierge.ui.theme.Champagne else MistDim.copy(alpha = 0.5f), 6.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(line.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 0.dp))
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
        }
    }
}

private val REAR_SEATS = listOf(CargoSpot.REAR_LEFT, CargoSpot.REAR_CENTER, CargoSpot.REAR_RIGHT)

private fun occMark(v: Boolean?) = when (v) { true -> "●"; false -> "○"; null -> "—" }

private fun gearLabel(g: Gear) = when (g) { Gear.PARK -> "P"; Gear.REVERSE -> "R"; Gear.NEUTRAL -> "N"; Gear.DRIVE -> "D"; Gear.UNKNOWN -> "—" }

private fun doorLabel(s: ai.vipseat.cargoconcierge.core.model.VehicleSnapshot, id: DoorId) =
    when (s.doors[id]) { true -> "열림"; false -> "닫힘"; null -> "—" }
