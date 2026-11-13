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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ny.my.health.core.BatchStatus
import com.ny.my.health.core.DataType
import com.ny.my.health.core.DayWindow
import com.ny.my.health.core.DestinationGeneration
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** 합성 메타데이터를 표시하며 모든 외부 동작은 명시적인 클릭으로만 요청한다. */
@Composable
internal fun SafManualUi(state: ManualSafState, actions: SafManualActions) {
    val idle = state.foreground && state.phase != ManualSafPhase.RUNNING
    val resetAllowed = idle && state.configured && state.generation != null
    // 일시 정지·실행·세대 변경 후에는 과거 확인 창을 재사용하지 않는다.
    var resetGeneration by remember(state.generation, resetAllowed) { mutableStateOf<DestinationGeneration?>(null) }
    val treeAllowed = idle && state.privacyConsent && state.intendedCategories.any { it in state.grantedCategories }
    val configuredAccess = treeAllowed && state.configured && state.generation != null && state.intendedCategories == state.categories
    val needsRetry = state.phase == ManualSafPhase.BLOCKED ||
        state.outcome in setOf(BatchStatus.STALE, BatchStatus.RETRYABLE, BatchStatus.PENDING) ||
        (state.coverage?.scopePendingCount ?: 0) > 0 || (state.coverage?.scopeBlockedCount ?: 0) > 0
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("수동 로컬 폴더 동기화", style = MaterialTheme.typography.titleMedium)
            Text("저장 목적지: 선택한 로컬 폴더")
            Text(safStatus(state))
            Text("선택 범위: ${state.intendedCategories.size}/5개 · 저장된 구성: ${state.categories.size}/5개 · 현재 읽기 권한: ${state.grantedCategories.size}/5개")
            Text("허용된 선택 항목만 읽습니다. 미허용 항목을 선택에서 자동 제외하지 않으며 일부 범위 확인은 전체 완료가 아닙니다.")
            Button(enabled = idle, onClick = actions.requestHealthConsent) { Text("Health Connect 권한 요청") }
            Button(enabled = idle, onClick = actions.refreshGrants) { Text("권한 다시 확인") }
            for (type in DataType.entries) Row {
                Checkbox(checked = type in state.intendedCategories, enabled = idle,
                    onCheckedChange = { checked -> actions.changeCategories(if (checked) state.intendedCategories + type else state.intendedCategories - type) },
                    modifier = Modifier.semantics { contentDescription = "로컬 동기화 항목: ${safCategory(type)}" })
                Text(safCategory(type))
            }
            Row {
                Checkbox(checked = state.privacyConsent, enabled = idle, onCheckedChange = actions.changeConsent,
                    modifier = Modifier.semantics { contentDescription = "로컬 폴더 저장 동의" })
                Text("선택한 로컬 폴더에 건강 CSV를 저장·갱신하는 데 동의합니다. 폴더에 접근하는 다른 앱이나 사람이 파일을 볼 수 있으며 기기·폴더 백업에도 포함될 수 있습니다.")
            }
            if (state.configured && state.intendedCategories != state.categories) Text("선택 범위가 저장된 구성과 다릅니다. 폴더를 다시 선택해 전체 선택 범위를 적용해 주세요.")
            Button(enabled = treeAllowed, onClick = actions.selectTree) { Text("로컬 폴더 선택") }
            Button(enabled = configuredAccess && state.phase == ManualSafPhase.READY, onClick = actions.continueManual) { Text("로컬 업로드 계속") }
            Button(enabled = configuredAccess && needsRetry, onClick = actions.retry) { Text("로컬 동기화 재시도") }
            Text("폴더 선택에서 돌아와도 자동 업로드하지 않습니다. 계속 또는 재시도를 직접 눌러 주세요. 화면을 벗어나면 중단되며 일부 파일은 남을 수 있습니다.")
            val coverage = state.coverage
            Text("전체 항목 확인 날짜: ${coverage?.checkedThrough ?: "확인되지 않음"}")
            Text("전체 항목 업로드 날짜: ${coverage?.uploadedThrough ?: "확인되지 않음"}")
            Text("현재 범위 확인 날짜: ${coverage?.scopeCheckedThrough ?: "확인되지 않음"}")
            Text("현재 범위 업로드 날짜: ${coverage?.scopeUploadedThrough ?: "확인되지 않음"}")
            Text("현재 범위 대기 날짜: ${coverage?.scopePendingCount ?: "확인되지 않음"} · 차단 날짜: ${coverage?.scopeBlockedCount ?: "확인되지 않음"}")
            Text("현재 범위 제외 항목: ${coverage?.excludedTypes?.size ?: "확인되지 않음"}개")
            Text("로컬 폴더 마지막 업로드 확인")
            Text("마지막 업로드 확인 시각: ${coverage?.lastUploadAt?.atZone(DayWindow.zone)?.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss (한국 시간)")) ?: "확인되지 않음"}")
            Text("현재 목적지 구성의 모든 범위 중 마지막 업로드 확인이며 현재 범위의 최신성을 보장하지 않습니다.")
            if (coverage?.lastUploadAt != null) Text("과거 업로드 확인이 있습니다. 현재 대기·차단·오래된 결과와 별개입니다.")
            for (line in safRecentDates(state)) Text(line)
            Text("날짜별 읽기와 업로드 확인은 별개입니다. EMPTY(빈 데이터)는 읽기 확인만 가능하며 업로드 성공이 아닙니다.")
            TextButton(enabled = resetAllowed, onClick = { resetGeneration = state.generation }) { Text("로컬 구성·확인 범위 초기화") }
            Text("로컬 폴더 자동·백그라운드 동기화는 지원하지 않습니다.")
        }
    }
    val expected = resetGeneration
    if (expected != null && resetAllowed && expected == state.generation) AlertDialog(
        onDismissRequest = { resetGeneration = null },
        title = { Text("로컬 구성 초기화") },
        text = { Text("이 기기의 로컬 목적지 구성과 날짜별 확인 범위만 지웁니다. 기존 파일·이력은 삭제하지 않으며 시스템 권한은 철회하지 않습니다.") },
        dismissButton = { TextButton(onClick = { resetGeneration = null }) { Text("초기화 취소") } },
        confirmButton = { TextButton(onClick = {
            if (resetGeneration == expected && resetAllowed && state.generation == expected) {
                resetGeneration = null
                actions.resetConfiguration(expected)
            }
        }) { Text("로컬 초기화 확인") } },
    )
}

private fun safStatus(state: ManualSafState): String = when {
    state.phase == ManualSafPhase.RUNNING -> "로컬 동기화 실행 중"
    state.phase == ManualSafPhase.BLOCKED || state.outcome == BatchStatus.BLOCKED -> "로컬 동기화 차단 · 권한과 구성을 확인해 주세요."
    state.outcome == BatchStatus.STALE -> "현재 결과가 오래되었거나 구성이 바뀌었습니다."
    state.outcome == BatchStatus.RETRYABLE -> "일시적인 실패 · 명시적으로 재시도해 주세요."
    state.outcome == BatchStatus.PENDING || (state.coverage?.scopePendingCount ?: 0) > 0 -> "현재 범위에 확인이 끝나지 않은 날짜가 남아 있습니다."
    (state.coverage?.scopeBlockedCount ?: 0) > 0 -> "현재 범위에 차단된 날짜가 남아 있습니다."
    state.outcome == BatchStatus.COMPLETED && state.coverage?.scopeUploadedThrough == null -> "현재 범위 읽기 확인 완료 · 업로드 확인 없음. 빈 데이터는 업로드 성공이 아닙니다."
    state.outcome == BatchStatus.COMPLETED && state.coverage?.excludedTypes?.isNotEmpty() == true -> "일부 허용 범위 확인 완료 · 전체 항목 완료가 아닙니다."
    state.outcome == BatchStatus.COMPLETED -> "이번 수동 확인이 끝났습니다. 날짜별 증거를 확인해 주세요."
    state.phase == ManualSafPhase.UNCONFIGURED -> "미구성 · 저장 동의와 항목 선택 후 로컬 폴더를 선택해 주세요."
    state.phase == ManualSafPhase.SELECTION_REQUIRED -> "로컬 폴더를 다시 선택해 주세요."
    state.phase == ManualSafPhase.READY -> "로컬 폴더 준비됨 · 명시적으로 계속해 주세요."
    else -> "로컬 폴더 구성을 확인해 주세요."
}

private fun safRecentDates(state: ManualSafState): List<String> {
    val enrolled = state.enrolledDay
    if (!state.configured || enrolled == null) return listOf("최근 대상 날짜: 확인되지 않음")
    val today = LocalDate.now(DayWindow.zone)
    val dates = listOf(today.minusDays(2), today.minusDays(1)).filter { it >= enrolled }
    if (dates.isEmpty()) return listOf("최근 대상 날짜: 등록일 이후 대상 없음")
    return dates.map { date ->
        val checked = if (date in state.coverage?.recentCheckedDays.orEmpty()) "확인됨" else "미확인"
        val uploaded = if (date in state.coverage?.recentUploadedDays.orEmpty()) "확인됨" else "미확인"
        "최근 대상 $date · 읽기 $checked · 업로드 $uploaded"
    }
}

private fun safCategory(type: DataType): String = when (type) {
    DataType.STEPS -> "걸음"
    DataType.WEIGHT -> "체중"
    DataType.HEART_RATE -> "심박"
    DataType.SLEEP -> "수면"
    DataType.EXERCISE -> "운동"
}
