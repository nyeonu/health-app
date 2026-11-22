package com.ny.my.health

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ny.my.health.core.BatchStatus
import com.ny.my.health.core.DataType
import com.ny.my.health.core.DayWindow
import java.time.LocalDate
import java.time.format.DateTimeFormatter

internal data class ManualDriveActions(
    val changeConsent: (Boolean) -> Unit,
    val changeCategories: (Set<DataType>) -> Unit,
    val requestHealthConsent: () -> Unit,
    val refreshGrants: () -> Unit,
    val selectAccount: () -> Unit,
    val authorize: () -> Unit,
    val continueManual: () -> Unit,
    val resetConfiguration: () -> Unit,
    val setAutomatic: (Boolean) -> Unit = {},
    val requestBackgroundPermission: () -> Unit = {},
)

/** 구성 중에는 외부 동작을 시작하지 않고 화면 상태만 투영한다. */
@Composable
internal fun ManualDriveUi(
    state: ManualDriveState,
    actions: ManualDriveActions,
    selectedCategories: Set<DataType> = state.categories.ifEmpty { DataType.entries.toSet() },
    todayInSeoul: LocalDate = LocalDate.now(DayWindow.zone),
    automaticState: SyncUiState? = null,
) {
    var resetDialog by rememberSaveable { mutableStateOf(false) }
    var automaticDialog by rememberSaveable { mutableStateOf(false) }
    var backgroundPermissionDialog by rememberSaveable { mutableStateOf(false) }
    val idle = state.foreground && state.phase != ManualDrivePhase.RUNNING
    val permitted = idle && state.privateFolderConsent && state.grantedCategories.isNotEmpty()
    val selectionAllowed = permitted && selectedCategories.any { it in state.grantedCategories }
    val configuredAccess = permitted && state.configured && selectedCategories == state.categories && state.categories.any { it in state.grantedCategories }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("V1 수동 Drive 동기화", style = MaterialTheme.typography.titleMedium)
            Text(manualDriveStatus(state))
            if (state.phase == ManualDrivePhase.READY && state.outcome in setOf(BatchStatus.BLOCKED, BatchStatus.STALE, BatchStatus.RETRYABLE)) {
                Text("이전 시도는 완료되지 않았습니다. 새 인증은 날짜별 차단·누락이 해결되었다는 뜻이 아닙니다.")
            }
            Text("읽기 권한: ${state.grantedCategories.size}/5개 · 선택 범위: ${selectedCategories.size}/5개")
            Text("허용된 선택 항목만 읽습니다. 미허용 항목과 빈 데이터는 전체 업로드 완료가 아닙니다.")
            Button(enabled = idle, onClick = actions.requestHealthConsent) { Text("Health Connect 권한 요청") }
            Button(enabled = idle, onClick = actions.refreshGrants) { Text("권한 다시 확인") }
            for (type in DataType.entries) {
                Row {
                    Checkbox(checked = type in selectedCategories, enabled = idle,
                        onCheckedChange = { checked -> actions.changeCategories(if (checked) selectedCategories + type else selectedCategories - type) },
                        modifier = Modifier.semantics { contentDescription = "동기화 항목: ${manualCategory(type)}" })
                    Text(manualCategory(type))
                }
            }
            Row {
                Checkbox(checked = state.privateFolderConsent, enabled = idle,
                    onCheckedChange = actions.changeConsent,
                    modifier = Modifier.semantics { contentDescription = "V1 비공개 Drive 업로드 동의" })
                Text("내 Google Drive의 비공개 HealthApp 폴더 생성·사용과 날짜별 CSV 갱신에 동의합니다. 공유 권한은 추가하지 않습니다.")
            }
            if (state.configured && selectedCategories != state.categories) {
                Text("선택 항목이 저장된 구성과 다릅니다. Google 계정 변경에서 계정을 선택해야 변경 범위가 적용됩니다.")
            }
            Button(enabled = selectionAllowed, onClick = actions.selectAccount) {
                Text(if (state.configured) "Google 계정 변경" else "Google 계정 선택")
            }
            Button(enabled = configuredAccess && state.phase != ManualDrivePhase.READY, onClick = actions.authorize) { Text("Google 권한 확인") }
            Button(enabled = configuredAccess && state.phase == ManualDrivePhase.READY, onClick = actions.continueManual) { Text("업로드 계속") }
            Text("외부 동의 화면에서 돌아와도 자동 업로드하지 않습니다. 권한 확인 후 업로드 계속을 눌러 주세요.")
            Text("화면을 벗어나면 중단됩니다. 일부 원격 파일은 남을 수 있습니다. 복구는 같은 구성으로 다시 인증하고 명시적으로 계속합니다.")
            val coverage = state.coverage
            Text("V1 전체 항목 확인 날짜: ${coverage?.checkedThrough ?: "확인되지 않음"}")
            Text("V1 전체 항목 업로드 날짜: ${coverage?.uploadedThrough ?: "확인되지 않음"}")
            Text("현재 범위 확인 날짜: ${coverage?.scopeCheckedThrough ?: "확인되지 않음"}")
            Text("현재 범위 업로드 날짜: ${coverage?.scopeUploadedThrough ?: "확인되지 않음"}")
            Text("대기 날짜: ${coverage?.scopePendingCount ?: "확인되지 않음"} · 차단 날짜: ${coverage?.scopeBlockedCount ?: "확인되지 않음"}")
            Text("마지막 V1 업로드 확인 시각: ${coverage?.lastUploadAt?.atZone(DayWindow.zone)?.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss (한국 시간)")) ?: "확인되지 않음"}")
            Text("현재 목적지 구성의 모든 범위 중 마지막 업로드 확인 시각입니다. 현재 범위를 다시 확인한 시각은 아닙니다.")
            if (coverage?.lastUploadAt != null) Text("이전 V1 업로드 확인이 있습니다. 현재 누락·차단·최신성과 별도로 확인하세요.")
            for (line in manualRecentDates(state, todayInSeoul)) Text(line)
            Text("최근 대상 날짜의 읽기·업로드 확인 기록이며 오늘 다시 갱신되었다는 뜻은 아닙니다. 빈 데이터는 읽기 확인만 있고 업로드 확인은 없을 수 있습니다.")
            TextButton(enabled = idle && state.configured, onClick = { resetDialog = true }) { Text("V1 구성·확인 범위 초기화") }
            if (automaticState != null) {
                Text(automaticStatusLabel(automaticState.automaticKnown, automaticState.automaticEnabled))
                Text("선택된 비공개 Drive에 허용된 걸음·수면·운동 요약과 최신 체중을 저장합니다. 심박 상세는 자동 범위에서 제외됩니다.")
                Text("약 24시간 간격으로 예약하며 배터리, 네트워크, 절전 및 늦게 도착하는 원본 데이터에 따라 지연될 수 있습니다. 정확한 시각이나 당일 완료는 보장되지 않습니다.")
                automaticState.automaticMessage?.let { Text(it) }
                Button(enabled = idle && !automaticState.automaticBusy, onClick = { backgroundPermissionDialog = true }) { Text("백그라운드 읽기 권한 확인") }
                Button(enabled = idle && automaticState.automaticKnown && !automaticState.automaticBusy && state.configured,
                    onClick = { if (automaticState.automaticEnabled) actions.setAutomatic(false) else automaticDialog = true }) {
                    Text(if (automaticState.automaticEnabled) "자동 동기화 끄기" else "자동 동기화 켜기")
                }
                val autoCoverage = automaticState.automaticCoverage
                Text(automaticGapLabel(autoCoverage?.scopePendingCount, autoCoverage?.scopeBlockedCount))
                if (automaticState.automaticBlockers.isNotEmpty()) Text("날짜별 차단 사유: ${automaticState.automaticBlockers.joinToString { automaticBlockerLabel(it) }}")
                if (automaticState.automaticRemoteStale) Text("원격 파일이 오래된 상태입니다. 명시적 복구가 필요합니다.")
                Text("자동 범위 제외 항목: ${autoCoverage?.excludedTypes?.joinToString { manualCategory(it) } ?: "확인되지 않음"}")
                Text("자동 범위 확인 날짜: ${autoCoverage?.scopeCheckedThrough ?: "확인되지 않음"} · 업로드 날짜: ${autoCoverage?.scopeUploadedThrough ?: "확인되지 않음"}")
                Text("마지막 업로드(전체 범위): ${autoCoverage?.lastUploadAt?.atZone(DayWindow.zone)?.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss (한국 시간)")) ?: "확인되지 않음"}")
                Text("자동 작업: ${automaticWorkLabel(automaticState.automaticWork)}")
                Text("끄기나 초기화는 이미 발행된 원격 쓰기를 되돌리지 않습니다.")
            }
        }
    }
    if (backgroundPermissionDialog) AlertDialog(
        onDismissRequest = { backgroundPermissionDialog = false },
        title = { Text("백그라운드 읽기 권한") },
        text = { Text("앱을 사용하지 않을 때도 허용한 Health Connect 항목을 읽을 수 있도록 권한을 요청합니다. 이 권한만으로 자동 동기화가 켜지지는 않으며, 자동 Drive 동기화는 별도 동의가 필요합니다. 심박 상세는 자동 범위에서 제외됩니다.") },
        dismissButton = { TextButton(onClick = { backgroundPermissionDialog = false }) { Text("취소") } },
        confirmButton = { TextButton(enabled = idle, onClick = { backgroundPermissionDialog = false; actions.requestBackgroundPermission() }) { Text("권한 요청 계속") } },
    )
    if (automaticDialog) AlertDialog(
        onDismissRequest = { automaticDialog = false },
        title = { Text("자동 Drive 동기화 동의") },
        text = { Text("선택한 항목: ${state.categories.joinToString { manualCategory(it) }}. 선택한 비공개 Drive와 허용된 항목의 요약을 백그라운드에서 갱신합니다. 절전·배터리·네트워크 및 늦은 원본 데이터로 지연될 수 있으며 정확한 실행 시각과 당일 완료는 보장되지 않습니다. 심박 상세는 제외됩니다.") },
        dismissButton = { TextButton(onClick = { automaticDialog = false }) { Text("취소") } },
        confirmButton = { TextButton(onClick = { automaticDialog = false; actions.setAutomatic(true) }) { Text("동의하고 켜기") } },
    )
    if (resetDialog) AlertDialog(
        onDismissRequest = { resetDialog = false },
        title = { Text("V1 구성 초기화") },
        text = { Text("이 기기의 V1 목적지 구성과 날짜별 확인 범위만 지웁니다. 이전 동기화 이력과 Drive 파일은 삭제하지 않으며 Health Connect·Google 권한은 철회하지 않습니다. 다시 동의하고 계정을 선택해야 합니다.") },
        dismissButton = { TextButton(onClick = { resetDialog = false }) { Text("초기화 취소") } },
        confirmButton = { TextButton(enabled = idle, onClick = { resetDialog = false; actions.resetConfiguration() }) { Text("V1 초기화 확인") } },
    )
}

/** 최근 대상 날짜의 증거만 표시하며 연속 확인 날짜나 업로드 시각으로 최신성을 추정하지 않는다. */
private fun manualRecentDates(state: ManualDriveState, todayInSeoul: LocalDate): List<String> {
    val enrolled = state.enrolledDay
    if (!state.configured || enrolled == null) return listOf("최근 대상 날짜: 확인되지 않음 · 구성을 확인해 주세요.")
    val dates = listOf(todayInSeoul.minusDays(2), todayInSeoul.minusDays(1)).filter { it >= enrolled }
    if (dates.isEmpty()) return listOf("최근 대상 날짜: 등록일 이후 대상 없음")
    val coverage = state.coverage
    return listOf("최근 대상 날짜 · 현재 허용 범위 (제외 항목 수: ${coverage?.excludedTypes?.size ?: "확인되지 않음"})") + dates.map { date ->
        val checked = if (date in coverage?.recentCheckedDays.orEmpty()) "확인됨" else "미확인"
        val uploaded = if (date in coverage?.recentUploadedDays.orEmpty()) "확인됨" else "미확인"
        "최근 대상 $date · 읽기 $checked · 업로드 $uploaded"
    }
}

/** 성공 이력과 현재 EMPTY·부분 범위·차단·오래된 결과를 합치지 않는다. */
private fun manualDriveStatus(state: ManualDriveState): String = when {
    state.phase == ManualDrivePhase.RUNNING -> "수동 동기화 실행 중…"
    state.phase == ManualDrivePhase.READY -> "Google 권한 확인됨 · 업로드 계속을 눌러 주세요."
    state.phase == ManualDrivePhase.BLOCKED || state.outcome == BatchStatus.BLOCKED -> "동기화가 차단되었습니다. 권한·계정을 확인하고 다시 인증한 뒤 계속해 주세요."
    state.outcome == BatchStatus.STALE -> "현재 결과가 오래되었거나 구성이 바뀌었습니다. 다시 확인하고 인증해 주세요."
    state.outcome == BatchStatus.RETRYABLE -> "일시적인 실패입니다. 다시 인증한 뒤 계속해 주세요."
    state.phase == ManualDrivePhase.UNCONFIGURED -> "미구성 · 업로드에 동의하고 Google 계정을 선택해 주세요."
    state.outcome == BatchStatus.PENDING || (state.coverage?.scopePendingCount ?: 0) > 0 -> "확인이 끝나지 않은 날짜가 남아 있습니다. 다시 인증한 뒤 계속해 주세요."
    (state.coverage?.scopeBlockedCount ?: 0) > 0 -> "차단된 날짜가 남아 있습니다. 권한·계정을 확인해 주세요."
    state.outcome == BatchStatus.COMPLETED && state.coverage?.scopeUploadedThrough == null -> "현재 범위 확인 완료 · 업로드 확인은 없습니다. 빈 데이터는 업로드 성공이 아닙니다."
    state.outcome == BatchStatus.COMPLETED && state.coverage?.excludedTypes?.isNotEmpty() == true -> "허용된 일부 범위만 확인되었습니다. 전체 항목 완료가 아닙니다."
    state.outcome == BatchStatus.COMPLETED -> "이번 수동 확인이 끝났습니다. 날짜별 확인 범위를 확인해 주세요."
    else -> "업로드하려면 Google 권한을 새로 확인해 주세요."
}

private fun manualCategory(type: DataType): String = when (type) {
    DataType.STEPS -> "걸음"
    DataType.WEIGHT -> "체중"
    DataType.HEART_RATE -> "심박"
    DataType.SLEEP -> "수면"
    DataType.EXERCISE -> "운동"
}

/** 작업 대기와 선행 작업 차단은 권한·날짜별 차단과 별개다. */
internal fun automaticWorkLabel(states: Set<androidx.work.WorkInfo.State>): String {
    val labels = buildList {
        if (androidx.work.WorkInfo.State.RUNNING in states) add("실행 중")
        if (androidx.work.WorkInfo.State.ENQUEUED in states) add("예약 대기 중 (제약 또는 재시도 지연 가능)")
        if (androidx.work.WorkInfo.State.BLOCKED in states) add("선행 작업 대기 중")
        if (androidx.work.WorkInfo.State.FAILED in states) add("작업 실패 · 다시 확인 필요")
        if (androidx.work.WorkInfo.State.CANCELLED in states) add("작업 취소됨")
    }
    return when {
        labels.isNotEmpty() -> labels.joinToString(" · ")
        states.isEmpty() -> "확인되지 않음"
        else -> "진행 중인 예약 없음"
    }
}

internal fun automaticGapLabel(pending: Long?, blocked: Long?): String =
    "자동 범위 미완료 날짜: ${pending ?: "확인되지 않음"} · 차단 날짜: ${blocked ?: "확인되지 않음"}"

internal fun automaticStatusLabel(known: Boolean, enabled: Boolean): String =
    "자동 Drive 동기화: ${if (!known) "확인되지 않음" else if (enabled) "켜짐" else "꺼짐"}"

private fun automaticBlockerLabel(reason: com.ny.my.health.core.BlockReason): String = when (reason) {
    com.ny.my.health.core.BlockReason.HISTORY_ACCESS_REQUIRED -> "과거 데이터 접근 필요"
    com.ny.my.health.core.BlockReason.SOURCE_ACCESS_REQUIRED -> "Health Connect 권한 필요"
    com.ny.my.health.core.BlockReason.SOURCE_READ_FAILED -> "원본 읽기 실패"
    com.ny.my.health.core.BlockReason.SOURCE_LIMIT_EXCEEDED -> "원본 조회 한도 초과"
    com.ny.my.health.core.BlockReason.DESTINATION_BLOCKED -> "저장 위치 확인 필요"
    com.ny.my.health.core.BlockReason.STORE_UNAVAILABLE -> "저장 상태 확인 불가"
    com.ny.my.health.core.BlockReason.RETRY_EXHAUSTED -> "재시도 횟수 소진"
    com.ny.my.health.core.BlockReason.DESTINATION_TRANSIENT_FAILURE -> "저장 위치 일시 오류"
    com.ny.my.health.core.BlockReason.DESTINATION_RECONCILIATION_REQUIRED -> "저장 위치 재확인 필요"
    com.ny.my.health.core.BlockReason.ACCOUNT_AUTHORIZATION_BLOCKED -> "계정 권한 확인 필요"
}
