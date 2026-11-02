package com.ny.my.health

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import com.ny.my.health.core.DataType
import com.ny.my.health.core.DayWindow
import com.ny.my.health.core.HistoryPhase
import com.ny.my.health.core.HistoryReadStatus
import com.ny.my.health.core.SyncStatus
import java.time.Instant
import java.time.format.DateTimeFormatter

internal data class HistoryUiActions(
    val requestHealthConsent: () -> Unit, val refreshPermissions: () -> Unit,
    val changeOptIn: (Boolean) -> Unit, val authorize: () -> Unit,
    val continueUpload: () -> Unit, val requestDelete: () -> Unit,
    val cancelDelete: () -> Unit, val confirmDelete: () -> Unit,
    val retryDelete: () -> Unit, val keepExisting: () -> Unit, val reload: () -> Unit,
)

@Composable
internal fun HealthHomeScreen(state: SyncUiState, actions: HistoryUiActions) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val session = state.history
    val readable = session?.read?.status in setOf(HistoryReadStatus.READY, HistoryReadStatus.ABSENT)
    val history = session?.read?.history
    val attempt = history?.attempt
    val success = history?.success
    val available = !state.busy && readable && session?.error == null && session?.deleteFailed != true && session?.deleteDialog != true
    val loading = session == null || session.operation == HistoryOperation.LOADING
    val storageError = !loading && (session?.error != null || !readable)
    val status = when {
        session?.operation == HistoryOperation.DELETING -> "로컬 이력 삭제 중…"
        session?.deleteFailed == true -> "이력 삭제에 실패했습니다. 저장된 이력이 남아 있을 수 있습니다."
        loading -> "로컬 이력 불러오는 중…"
        storageError && state.transientRemoteStatus == SyncStatus.SUCCESS -> "업로드는 확인됐지만 이력 저장에 실패했습니다."
        session?.error == HistoryError.WRITE -> "이력 저장에 실패했습니다. 다시 불러오거나 로컬 이력을 삭제해 주세요."
        session?.error == HistoryError.RECOVERY_WRITE -> "복구 이력 저장에 실패했습니다. 다시 불러오거나 로컬 이력을 삭제해 주세요."
        storageError -> "이력을 읽을 수 없습니다. 다시 불러오거나 로컬 이력을 삭제해 주세요."
        state.syncing -> "업로드 실행 중…"
        state.pendingConsent -> "동의 결과 대기 중…"
        state.refreshing -> "현재 권한 확인 중…"
        else -> when (attempt?.phase) {
            HistoryPhase.AUTHORIZATION_ABANDONED -> "인증 단계에서 종료됨 · 업로드 미시작"
            HistoryPhase.UPLOAD_NOT_STARTED -> "업로드 시작 전 종료됨 · 다시 인증해 주세요."
            HistoryPhase.INTERRUPTED -> "실행 중 종료됨 · 업로드 결과를 확인할 수 없습니다."
            HistoryPhase.FAILED -> "업로드 실패 · 일부 파일이 저장됐을 수 있습니다."
            HistoryPhase.EMPTY -> "허용된 항목에 업로드할 데이터가 없습니다."
            HistoryPhase.PERMISSION_REQUIRED -> "읽기 권한이 필요합니다."
            HistoryPhase.AUTHORIZATION_FAILED -> "Google 권한 확인에 실패했습니다."
            HistoryPhase.AUTHORIZING -> "Google 권한 확인 중…"
            HistoryPhase.AWAITING_UPLOAD -> "Google 권한 확인됨 · 업로드 계속을 눌러 주세요."
            HistoryPhase.RUNNING -> "업로드 실행 중…"
            HistoryPhase.SUCCESS -> "최근 시도의 업로드가 확인되었습니다."
            null -> "저장된 동기화 이력이 없습니다."
        }
    }
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Health App · 수동 동기화", style = MaterialTheme.typography.headlineMedium)
        Text("이번 동기화 대상: 어제, ${state.date} (한국 시간)")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("현재 상태", style = MaterialTheme.typography.titleMedium)
                Text(status, color = if (storageError || session?.deleteFailed == true || attempt?.phase == HistoryPhase.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                Text(state.message)
                if (state.syncing) Text("화면을 벗어나면 중단됩니다")
                if (session?.deleteFailed == true) {
                    Text(if (session.retainedAfterDeleteFailure) "삭제되지 않은 기존 이력" else "기존 로컬 이력의 보존 여부를 확인할 수 없습니다.")
                    Text("현재 업로드 동의와 준비된 인증은 폐기되었습니다. 새 업로드는 다시 동의하고 인증해 주세요.")
                    Button(enabled = !state.busy, onClick = actions.retryDelete) { Text("삭제 다시 시도") }
                    TextButton(enabled = !state.busy, onClick = actions.keepExisting) { Text("기존 이력 유지 · 다시 확인") }
                } else if (storageError) {
                    Button(enabled = !state.busy, onClick = actions.reload) { Text("다시 불러오기") }
                    TextButton(enabled = !state.busy, onClick = actions.requestDelete) { Text("로컬 이력 삭제") }
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("마지막 업로드 확인", style = MaterialTheme.typography.titleMedium)
                if (success != null && readable) {
                    Text("대상 날짜: ${success.date}")
                    Text("확인 시각: ${time(success.confirmedAt)}")
                    Text("당시 허용 항목: ${coverage(success.coverage)}")
                    Text("확인된 파일 수: ${success.confirmedFiles}개")
                    Text("이 기기의 과거 기록이며 현재 계정·원본 전체의 완료 여부는 확인되지 않았습니다.")
                } else {
                    Text(when {
                        loading -> "과거 업로드 이력을 불러오고 있습니다."
                        readable -> "확인된 업로드 이력이 없습니다."
                        else -> "과거 업로드 이력을 확인할 수 없습니다."
                    })
                }
            }
        }
        Text("수동 동기화", style = MaterialTheme.typography.titleMedium)
        Text("Health Connect: ${when (state.availability) {
            HealthConnectClient.SDK_AVAILABLE -> "사용 가능"
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "설치 또는 업데이트 필요"
            else -> "사용 불가"
        }}")
        Text("읽기 권한: ${state.grantedCount}/5개 · 걸음, 체중, 심박, 수면, 운동")
        Text("일부 허용 시 허용된 항목만 처리합니다. 미허용·빈 데이터는 완료된 5개 항목으로 간주하지 않습니다.")
        Button(enabled = available && state.availability == HealthConnectClient.SDK_AVAILABLE, onClick = actions.requestHealthConsent) { Text("Health Connect 권한 요청") }
        Row {
            Checkbox(checked = state.uploadOptIn, enabled = available, onCheckedChange = actions.changeOptIn)
            Text("내 Google Drive의 비공개 HealthApp 폴더 생성·사용과 날짜별 CSV 갱신에 동의합니다. 공유 권한은 추가하지 않습니다.")
        }
        Button(enabled = available && state.uploadOptIn && !state.readyToUpload, onClick = actions.authorize) { Text("Google 권한 확인") }
        if (state.readyToUpload) Button(enabled = available, onClick = actions.continueUpload) { Text("업로드 계속") }
        Text("화면을 벗어나면 중단됩니다. 일부 원격 파일은 남을 수 있으며, 다시 실행하면 같은 날짜 파일을 갱신합니다.")
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "상세·설정 접기" else "상세·설정 펼치기") }
        if (expanded) {
            Text("인증 요청 시각: ${time(attempt?.authorizationRequestedAt)}")
            Text("실행 시작 시각: ${attempt?.startedAt?.let(::time) ?: "미시작"}")
            Text("종료 시각: ${attempt?.finishedAt?.let(::time) ?: "확인 불가"}")
            Text("최근 시도 허용 항목: ${coverage(attempt?.coverage.orEmpty())}")
            Text("최근 시도에서 확인된 업로드: ${attempt?.confirmedFiles?.let { "${it}개 파일" } ?: "확인되지 않음"}")
            Text("오류 분류: ${session?.error?.name ?: attempt?.failureCode ?: "없음"}")
            Button(enabled = !state.busy, onClick = actions.refreshPermissions) { Text("권한 다시 확인") }
            if (!storageError && session?.deleteFailed != true) {
                Button(enabled = !state.busy, onClick = actions.reload) { Text("다시 불러오기") }
                TextButton(enabled = !state.busy, onClick = actions.requestDelete) { Text("로컬 이력 삭제") }
            }
            if (state.busy) Text("작업 중에는 로컬 이력을 삭제할 수 없습니다. 작업이 끝난 뒤 다시 시도해 주세요.")
            Text("권한 철회: 시스템 Health Connect 앱 권한 및 Google 계정의 연결된 앱 설정을 이용하세요.")
        }
    }
    if (session?.deleteDialog == true) {
        AlertDialog(
            onDismissRequest = actions.cancelDelete,
            title = { Text("로컬 이력 삭제") },
            text = { Text("이 기기의 동기화 이력과 현재 업로드 동의를 지웁니다. Drive 파일과 Health Connect·Google 권한은 유지됩니다.") },
            dismissButton = { TextButton(enabled = !state.busy, onClick = actions.cancelDelete) { Text("취소") } },
            confirmButton = { TextButton(enabled = !state.busy, onClick = actions.confirmDelete) { Text("이력 삭제") } },
        )
    }
}

private fun time(value: Instant?): String = value?.atZone(DayWindow.zone)?.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss (한국 시간)")) ?: "기록 없음"

private fun coverage(types: Set<DataType>): String = types.sortedBy { it.ordinal }.joinToString { type ->
    when (type) {
        DataType.STEPS -> "걸음"
        DataType.WEIGHT -> "체중"
        DataType.HEART_RATE -> "심박"
        DataType.SLEEP -> "수면"
        DataType.EXERCISE -> "운동"
    }
}.ifEmpty { "없음" }
