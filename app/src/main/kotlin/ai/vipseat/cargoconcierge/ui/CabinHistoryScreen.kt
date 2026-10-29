package ai.vipseat.cargoconcierge.ui

import ai.vipseat.cargoconcierge.core.history.CabinHistoryPhase
import ai.vipseat.cargoconcierge.core.history.CabinHistoryRow
import ai.vipseat.cargoconcierge.core.history.CabinHistorySession
import ai.vipseat.cargoconcierge.core.port.FeatureConfirmation
import ai.vipseat.cargoconcierge.core.port.FeatureStatus
import ai.vipseat.cargoconcierge.ui.theme.MistDim
import ai.vipseat.cargoconcierge.vm.UiState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** No control callbacks or vehicle/session object enter this display-only screen. */
@Composable
fun CabinHistoryScreen(ui: UiState, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(40.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("이전 실행 기록", style = MaterialTheme.typography.headlineLarge)
        Text("과거 조회값이며 현재 차량 상태와 다를 수 있어요. 차량 설정에서 직접 확인해 주세요.\n이 화면은 설정을 복원하지 않으며, 기록된 값을 그대로 적용하라는 권장이 아니에요.",
            style = MaterialTheme.typography.bodyLarge, color = MistDim)
        BigButton("기록 닫기", onClick = onClose, tonal = true)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val archive = ui.cabinHistory.archive
            if (ui.cabinHistory.unavailable) Text("일부 기록을 저장하거나 읽지 못했어요. 실제 변경 여부는 차량에서 확인해 주세요.")
            if (archive.previous == null && archive.current == null) Text("남아 있는 설정 기록이 없어요. 변경 전 값과 실제 변경 여부를 확인할 수 없어요.")
            archive.previous?.let { HistorySession("이전 미완료 실행", it) }
            archive.current?.let { HistorySession(if (it.ended) "이번 실행의 과거 기록" else "이번 실행에서 수집한 기록", it) }
            Text("남아 있는 미완료 기록 1건과 이번 실행 1건만 보관해요. 기록이 빠질 수 있으며 모든 변경을 보장하지 않아요.",
                style = MaterialTheme.typography.bodyMedium, color = MistDim)
        }
    }
}

@Composable
private fun HistorySession(label: String, session: CabinHistorySession) {
    Text("$label · ${historyTime(session.startedAtMs)}", style = MaterialTheme.typography.titleLarge)
    if (session.gap) Text("기록 일부 누락 · 확인되지 않은 결과가 있어요.")
    if (!session.normalEnd) Text("실행 종료 또는 복원 완료를 확인하지 못한 기록이에요.", color = MistDim)
    if (session.rows.isEmpty()) Text("설정 항목 기록 없음 · 실제 변경 여부 미확인")
    session.rows.forEach { row ->
        Panel(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${row.address.property.label} · ${row.spot.label}", style = MaterialTheme.typography.titleMedium)
                Text("제어 출처: ${row.address.source}\n실제 영역: ${row.address.area}", color = MistDim)
                Text("당시 변경 전 조회값: ${row.value(row.original)}")
                Text("당시 적용 요청값: ${row.value(row.applyRequested)}")
                if (row.restoring) Text("당시 복원 요청값: ${row.value(row.requested)}")
                Text("마지막 확인값: ${row.value(row.confirmed)}${row.confirmedAtMs?.let { " · ${historyTime(it)}" }.orEmpty()}")
                Text("${historyStatus(row)}\n기록 시각: ${historyTime(row.atMs)} · 작업 ${row.operation}", color = MistDim)
            }
        }
    }
}

internal fun CabinHistoryRow.value(value: String?): String = value?.let { "$it ${address.property.unit}" } ?: "기록 없음 / 미확인"
private fun historyTime(atMs: Long): String = SimpleDateFormat("yyyy.MM.dd HH:mm:ss", Locale.KOREA).format(Date(atMs))
internal fun historyStatus(row: CabinHistoryRow): String = when {
    row.phase == CabinHistoryPhase.BEFORE_WRITE -> "요청 직전 기록만 있음 · 실제 요청/변경 여부 미확인"
    row.phase == CabinHistoryPhase.REQUEST_ACCEPTED -> "요청 응답 수신 · 결과 조회 기록 없음"
    row.confirmation == FeatureConfirmation.VALUE_CONFIRMED -> if (row.restoring) "당시 복원값 조회 확인 · 현재 상태는 알 수 없음" else "당시 차량 값 조회 확인 · 현재 상태는 알 수 없음"
    row.confirmation == FeatureConfirmation.REQUEST_ACCEPTED -> "요청 응답 수신 · 값 확인 실패 또는 불일치"
    row.status == FeatureStatus.UNCHANGED -> "변경 명령 없이 종료 · 외부 변경/소유권 해제 가능, 차량 확인 필요"
    else -> "당시 요청 보류 또는 실패 · 실제 변경 여부 미확인"
}
