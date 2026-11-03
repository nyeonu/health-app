package com.ny.my.health

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
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
import com.ny.my.health.core.ProfileStage

/** 진단 자료는 인자로만 받고 펼침 여부만 복원한다. 스크롤은 상위 화면이 제공한다. */
@Composable
internal fun PhoneProfilingSection(state: PhoneProfilingUiState, onCopy: () -> Unit) {
    if (!state.enabled) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "성능 진단 접기" else "성능 진단 보기")
        }
        if (expanded) {
            Text("최근 수동 시도 성능 진단", style = MaterialTheme.typography.titleMedium)
            Text("단계별 경과 시간이며 업로드 성공이나 건강 자료의 완전성을 뜻하지 않습니다.")
            Text("복사 요약에는 고정 단계·작업 분류와 시간·호출 횟수만 포함됩니다. 건강 값·건강 기록 건수·날짜·계정·식별자·원시 오류는 포함하지 않습니다.")
            Text("앱의 진단은 메모리에만 보관됩니다. 앱에서 진단을 지워도 이미 복사한 시스템 클립보드는 별도로 남을 수 있습니다. 공유 전 내용을 확인해 주세요.")
            val report = state.report.takeUnless { state.collecting }
            when {
                state.collecting -> Text("진단 수집 중… 이전 측정은 표시하지 않습니다.")
                report == null -> Text("표시할 진단이 없습니다.")
                else -> report.rows.forEach { row -> Text("${stageLabel(row.stage)}: ${row.value}") }
            }
            Button(enabled = report != null, onClick = onCopy) { Text("안전한 진단 요약 복사") }
            if (report != null) {
                when (state.copyState) {
                    PhoneCopyState.NONE -> Unit
                    PhoneCopyState.COPIED -> Text("진단 요약을 복사했습니다.")
                    PhoneCopyState.FAILED -> Text("진단 요약을 복사하지 못했습니다. 다시 눌러 주세요.")
                }
            }
        }
    }
}

private fun stageLabel(stage: ProfileStage): String = when (stage) {
    ProfileStage.AUTHORIZATION_HISTORY -> "인증 요청 이력 저장"
    ProfileStage.AUTHORIZATION_WAIT -> "인증 대기"
    ProfileStage.CONTINUE_WAIT -> "업로드 계속 대기"
    ProfileStage.EXECUTION -> "전체 실행"
    ProfileStage.START_HISTORY -> "실행 시작 이력 저장"
    ProfileStage.HEALTH_READ -> "건강 자료 읽기"
    ProfileStage.NORMALIZE -> "자료 정형"
    ProfileStage.CSV_EXPORT -> "CSV 생성"
    ProfileStage.DRIVE_TOTAL -> "Drive 처리 전체"
    ProfileStage.TERMINAL_HISTORY -> "종료 이력 저장"
}
