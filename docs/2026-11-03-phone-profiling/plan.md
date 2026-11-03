---
plan_id: 2026-11-03-phone-profiling
title: PC 없는 진단 보기·복사 실행 계획
kind: single
status: in_progress
author: Codex
created: 2026-11-03
target: nyeonu/health-app
spec: spec.md
spec_rev: 711cb7282e4335814d5fa368c570ab1d1dd63832
tasks:
  - id: T1
    title: 제한된 메모리 진단 상태
    depends_on: []
    status: done
    tier: standard
  - id: T2
    title: 실제 클립보드 쓰기 경계
    depends_on: [T1]
    status: done
    tier: standard
  - id: T3
    title: 선택적 진단 화면
    depends_on: [T2]
    status: done
    tier: standard
  - id: T4
    title: 수동 동기화 흐름 연결
    depends_on: [T3]
    status: done
    tier: standard
  - id: T5
    title: 기기 수집 절차와 실행 증거
    depends_on: [T4]
    status: done
    tier: standard
---

# PC 없는 진단 보기·복사 실행 계획

승인 기록: 2026-11-03 소유자가 “워크플로우 쭉 진행해”로 이 계획의 구현·검증·독립 리뷰 진행을 승인했다.

## 1. 선택한 방식과 이유

기존 `ManualSyncProfiling`의 안전한 요약을 재사용하고, 앱 모듈에 작은 메모리 상태 객체와 선택적으로 펼치는 진단 화면을 추가한다. 새 DB·로그 읽기·공유 파일 대신 최근 1건의 불변 표시값과 복사 텍스트만 남긴다. 화면과 복사 본문이 같은 snapshot을 사용하도록 기존 safeSummary 정형만 순수 함수로 추출한다. 클립보드 쓰기는 Activity의 명시적 UI 동작에만 연결한다. 외부 전송과 클립보드 자동 삭제는 하지 않는다.

검토한 대안인 raw logcat 읽기는 권한/개인정보 경계가 커지고 PC 제약을 안전하게 해결하지 못한다. 공유 파일 방식은 영속 수명과 파일 권한이 추가되므로 선택하지 않는다. 대신 프로세스가 종료되면 진단을 잃는 제한을 수용한다. 새 프레임워크·영속 모델·인증 전략을 도입하지 않는 되돌릴 수 있는 연결 변경으로 새 ADR 후보는 없다. 이 계획은 사용자 승인 전 실행하지 않는다.

## 2. 공통 설계

### 변경 파일

아래 공통 접두사는 실제 경로다. `main`=`android/app/src/main/java/com/ny/my/health`, `unit`=`android/app/src/test/java/com/ny/my/health`, `uiTest`=`android/app/src/androidTest/java/com/ny/my/health`.

| 파일 | 신규/수정 | 변경 요지 | 작업 |
|---|---|---|---|
| `android/app/src/main/java/com/ny/my/health/PhoneProfilingSession.kt` | 신규 | 메모리 수명·고정 표시·명시적 복사 상태 | T1 |
| `android/app/src/main/java/com/ny/my/health/ManualSyncProfiling.kt` | 수정 | 기존 안전 정형을 같은 snapshot에 재사용 | T1 |
| `android/app/src/test/java/com/ny/my/health/PhoneProfilingSessionTest.kt` | 신규 | 상태/시간 경계·무효화·복사 오류 검증 | T1 |
| `android/app/src/main/java/com/ny/my/health/PhoneProfilingClipboard.kt` | 신규 | 기존 OS 서비스에 고정 텍스트 쓰기 | T2 |
| `android/app/src/androidTest/java/com/ny/my/health/PhoneProfilingClipboardInstrumentedTest.kt` | 신규 | 전경 Activity에서 실제 OS 쓰기·읽기 검증 | T2 |
| `android/app/src/main/java/com/ny/my/health/PhoneProfilingSection.kt` | 신규 | 선택적으로 펼치는 진단·복사 UI | T3 |
| `android/app/src/androidTest/java/com/ny/my/health/PhoneProfilingScreenInstrumentedTest.kt` | 신규 | 합성 진단의 표시·명시적 클릭·큰 글꼴 | T3 |
| `android/app/src/main/java/com/ny/my/health/SyncViewModel.kt` | 수정 | 기존 계측 종료와 메모리 상태 연결 | T4 |
| `android/app/src/main/java/com/ny/my/health/HealthHomeScreen.kt` | 수정 | 별도 진단 섹션·복사 액션 연결 | T4 |
| `android/app/src/main/java/com/ny/my/health/MainActivity.kt` | 수정 | 복사 액션의 OS 경계 연결 | T4 |
| `android/app/src/androidTest/java/com/ny/my/health/HealthHomeScreenInstrumentedTest.kt` | 수정 | 기존 actions fixture에 새 액션 배선 | T4 |
| `android/app/src/androidTest/java/com/ny/my/health/HealthAppInstrumentedTest.kt` | 수정 | 실제 Activity 빈 진단·재생성 회귀 | T4 |
| `docs/2026-11-03-phone-profiling/device-validation.md` | 신규 | PC 없는 수집·공유 절차와 한계 | T5 |
| `docs/2026-11-03-phone-profiling/execution-report.md` | 신규 | 실제 검증과 남은 수용 기록 | T5 |
| `docs/2026-11-02-sync-profiling/device-profiling.md` | 수정 | 기존 ADB 절차를 유지하고 새 절차 연결 | T5 |
| `docs/backlog/sync-performance.md` | 수정 | 수집 경로 상태 갱신, 실측 미확인 유지 | T5 |

### 인터페이스

```kotlin
// PhoneProfilingSession — PhoneProfilingSession.kt, Android IO 없는 메모리 상태
// 고정 stage 순서의 불변 rows와 기존24행 safeSummary만 보관한다. 최근1건만 허용한다.
// 활성 ManualSyncProfiling의 객체 동일성으로 현재 시도를 검사하고 종결 뒤 참조를 폐기한다.
// begin/clear는 이전 요약과 복사 결과를 지우며 오래된 complete/복사 결과를 거부한다.
// enabled=false에서는 요약을 보관하거나 writer를 호출하지 않는다.
// write는 명시적 copy에서만1회 호출한다. 실패 원문은 보관/출력하지 않는다.
// 현재 snapshot1회에서 rows와 safeProfilingSummary를 만들고 collector/가변 map을 보관하지 않는다.
// 미관측/시계 결함/종료0회=측정 없음, active=미완료, 종결elapsed0=0 ms.
// 0<ns<1000000은1 ms 미만, 나머지 ns→ms 최대소수3자리. overflow/locale 영향을 피한다.
// 종결 failed/cancelled는 고정 실패/취소 표시도 붙이며 업무의 업로드 성공을 추론하지 않는다.
// copy 중 clear/begin이 재진입하면 이전 writer 결과가 새 copyState를 덮지 않는다.
internal enum class PhoneCopyState { NONE, COPIED, FAILED }
internal data class PhoneProfilingRow(val stage: ProfileStage, val value: String)
internal data class PhoneProfilingReport(val rows: List<PhoneProfilingRow>, val safeText: String)
internal data class PhoneProfilingUiState(
    val enabled: Boolean = false, val collecting: Boolean = false,
    val report: PhoneProfilingReport? = null, val copyState: PhoneCopyState = PhoneCopyState.NONE,
)
internal class PhoneProfilingSession(enabled: Boolean) {
    val state: PhoneProfilingUiState
    fun begin(profile: ManualSyncProfiling)
    fun complete(profile: ManualSyncProfiling)
    fun clear()
    fun copy(write: (String) -> Unit)
}
```

```kotlin
// safeProfilingSummary — ManualSyncProfiling.kt의 기존 정형 body를 그대로 추출
// 기존24행/고정순서/8192자 상한을 유지한다. safeSummary()는 snapshot을 받아 이 함수에 위임한다.
internal fun safeProfilingSummary(summary: ProfileSummary): String
```

```kotlin
// PhoneProfilingClipboard — OS 쓰기만 담당. 실패는 호출자에게 전달하고 로그하지 않는다.
// 입력은 session.copy가 제공하는 고정 safeText뿐이며 읽기/자동 삭제/자동 전송은 없다.
internal fun writePhoneProfilingClipboard(context: android.content.Context, text: String)
```

```kotlin
// PhoneProfilingSection — 기본 접힘, enabled=false이면 UI 자체가 없다.
// 확대/복사는 동기화 권한/이력 정상 여부가 아니라 진단 상태로만 판정한다.
// collecting/진단 없음에서는 복사 불가. 펼침 상태만 rememberSaveable로 보관할 수 있다.
// report/텍스트는 rememberSaveable, Bundle, SavedStateHandle에 넣지 않는다.
@Composable
internal fun PhoneProfilingSection(state: PhoneProfilingUiState, onCopy: () -> Unit)
```

```kotlin
// SyncUiState — 기존 모든 필드/동작을 유지하고 다음 필드만 끝에 추가
// val phoneProfiling: PhoneProfilingUiState = PhoneProfilingUiState()
// SyncViewModel — 기존 클래스에 다음 진입점 추가, 새 권한/외부 효과 없음
// private val phoneProfilingSession: PhoneProfilingSession
internal fun copyPhoneProfiling(write: (String) -> Unit)
// HistoryUiActions — 기존 필드 유지, 새 필드에는 무동작 기본값을 두지 않는다.
// val copyPhoneProfiling: () -> Unit
```

### 결정 로직 위치

- `PhoneProfilingSession`의 판정·시간 정형은 같은 파일의 IO 없는 함수에 둔다. Android/Health Connect/DB/Drive 객체를 입력받지 않는다. `complete`는 이미 종결 관찰한 현재 profile에서 snapshot/safeSummary를 읽어 복사한 불변 표시값을 만들고 collector 참조를 버린다. snapshot의 raw map이나 가변 리스트를 외부와 공유하지 않는다.
- 표시 정형: 미관측/시계 결함 폐기=`측정 없음`, active=`미완료`, 종료 횟수가 없는 값=`측정 없음`, 종결 elapsed0=`0 ms`, 0보다 크고1ms보다 작음=`1 ms 미만`, 나머지는 ns→ms를 overflow 없이 소수 최대3자리로 표시한다. 종결 failed/cancelled가 있으면 고정 실패/취소 표시를 함께 붙인다. 관측 완료를 업로드 성공으로 부르지 않는다. 이는 표시 단위 규칙이며 SLA가 아니다. stage는10개 고정 순서, operation/request 상세는 기존24행 텍스트로만 복사한다.
- `copy`는 현재 종결 report가 있고 enabled이며 collecting이 아닐 때만 writer를 호출한다. writer 실패는 FAILED로만 남기고 원문을 버린다. writer가 재진입해 상태를 지운 경우 늦은 성공/실패 상태가 새 진단을 덮지 않는다. 새로운 보고·시도 목록이나 자동 재시도는 만들지 않는다.
- `clear`는 앱 메모리만 비운다. 이미 복사된 OS 클립보드는 별도 수명이며 다른 클립보드 내용을 읽거나 삭제하지 않는다.

### 관련된 암묵적 동작

- 기존 `finishProfiling`은 profile.finish→debug publish→현재 profile 참조 해제 순서다. 기존 로그와 동기화 결과를 유지하면서 complete를 연결한다. (확인: `SyncViewModel.kt`)
- 기존 로컬 무효화 callback/optIn은 credential·티켓을 폐기한다. 이 경로에서는 session.clear를 종료 관찰보다 먼저 적용해 오래된 종료가 진단을 되살리지 않도록 한다. 유효한 새 인증 시도는 새 profile을 만든 다음 begin으로 이전 진단을 비운다. (확인: `SyncViewModel.beginAuthorization`, `optIn`, HistorySession 생성 callback)
- 화면 재생성 시 같은 ViewModel의 메모리는 유지될 수 있으나 새 ViewModel은 새로운 session을 만든다. 진단을 SavedState에 등록하지 않는다. 실제 populated VM의 OAuth 경로를 자동화로 검증했다고 주장하지 않는다. (확인: Activity의 ViewModelProvider 사용; 합성 UI/기존 lifecycle 테스트 재사용)
- clipboard API는 SDK36의 api-versions.xml에서 ClipboardManager/ClipData 및 setPrimaryClip/newPlainText가API11, clearPrimaryClip이API28임을 확인했다. 현재 minSdk28이다. production은 setPrimaryClip만 쓴다. foreground/clipboard 접근의 실제 OS 동작은 T2에서 검증한다. (확인: SDK metadata, app/build.gradle.kts)
- Compose createComposeRule/createAndroidComposeRule, StateRestorationTester와 UI-test manifest가 이미 있다. 새 UI/clipboard 기반은 각 도입 작업에서 최소1개 테스트로 기동을 확인한다. (확인: 기존 Android 테스트·app/build.gradle.kts)

## 3. 추적성

출처: ①성공 기준, ②경계/동등 분할, ③오류, ④상태/순서, ⑤과거 결함. JVM 테스트명은 아래 한국어 문장, Android instrumentation 테스트명은 아래 공백 없는 camelCase 식별자를 그대로 사용하며 번호를 붙이지 않는다. minSdk28용 DEX는 공백 method name을 허용하지 않으므로 SDK/기대값은 바꾸지 않고 이름만 계층에 맞춘다. 실제 Galaxy/클립보드 동작의 플랫폼별 보장은 에뮬레이터와 구분한다.

| 스펙 기준 | 테스트 이름 (출처/층) | 충족 위치 |
|---|---|---|
| 디버그 앱에서 수동 시도 뒤 PC·ADB 없이 진단 보기를 열 수 있고, 진단이 없거나 아직 수집 중이면 과거 성공 또는 측정된 0으로 표시하지 않는다. | 진단 없음과 수집 중에는 복사와 과거 측정 표시가 없다; missingAndCollectingDiagnosticsNeverShowPreviousMeasurementsOrEnableCopy; opensDiagnosticsExplicitlyAndCallsCopyOnlyOnClick (①④/유닛·Compose) | T1·T3·T4 |
| 표시 시간은 인증/계속 대기와 실행을 분리하고 중첩 구간을 합산하지 않으며, 미관측·진행 중·종결된 실제 0과 표시 단위 때문에 작게 보이는 비영(非零) 시간을 구별한다. | 미관측 미완료 실제 영과 작은 양수 시간은 서로 다르게 표시한다; 단계별 표시와 복사 요약에는 고정 안전 정보만 있다; 기존 Authorization and continuation waits never contribute to execution elapsed (①②/유닛) | T1 |
| 사용자의 명시적 복사 동작만 기존 고정 24행 safe summary를 클립보드에 쓰고, 표시/복사 자체는 권한 요청·건강 읽기·업로드·자동 재시도·외부 전송을 시작하지 않는다. | 단계별 표시와 복사 요약에는 고정 안전 정보만 있다; opensDiagnosticsExplicitlyAndCallsCopyOnlyOnClick; explicitForegroundWritePlacesTheSameSafeSummaryOnSystemClipboard; openingEmptyDiagnosticsAndRecreationNeverStartConsentOrUpload (①④/유닛·Compose·OS 통합) | T2·T3·T4 |
| 복사 성공/실패는 별도 안내하고 실패해도 SyncResult·부분 확인 수·이력·원래 취소 전파와 이후 수동 동기화 가능 여부를 바꾸지 않는다. | 복사 실패는 안전한 안내만 남기고 명시적 재시도만 허용한다; 복사 중 무효화 뒤 늦은 결과는 새 상태를 덮지 않는다; diagnosticsExplainSafeSharingAndSeparateClipboardLifetime; 기존100개 회귀 (①③④/유닛·Compose·기존 통합) | T1·T4·전체 검증1 |
| 진단 표시와 복사에는 고정 단계/작업 라벨과 안전한 통계만 사용하고, 제외 범위의 건강 값·날짜/시각·개인 식별자·자격 정보·공급자 원문을 입력하거나 포함하지 않는다. | 단계별 표시와 복사 요약에는 고정 안전 정보만 있다; 기존 Safe profiling summary contains only fixed enums and numeric statistics (①②③/유닛) | T1·전체 검증1 |
| 진단은 최근 시도 1건의 제한된 불변 요약으로만 메모리에 보관하고 디스크·저장된 상태·복원 번들·이력 DB·공유 파일에 기록하지 않는다. | 최근 한 건의 불변 요약만 유지하고 새 세션은 비어 있다; restorationDoesNotRestoreDiagnosticPayload (①②④/유닛·Compose); 새 영속 경로 없음은 정적 확인 | T1·T3·전체 검증3 |
| 유효한 새 시도 또는 로컬 상태 무효화 시 이전 요약을 지우고, 늦은 이전 콜백이 이를 다시 표시하거나 새 시도의 진단을 덮지 않는다. | 새 시도와 무효화는 이전 요약과 늦은 완료를 폐기한다; 복사 중 무효화 뒤 늦은 결과는 새 상태를 덮지 않는다 (①④/유닛) | T1·T4 |
| 화면 재생성 시 같은 화면 모델이 유지되면 진단을 유지할 수 있지만 새 화면 모델·새 프로세스에서는 진단 없음으로 시작하며, release 앱에는 진단 보기·복사 경로를 노출하지 않는다. | 최근 한 건의 불변 요약만 유지하고 새 세션은 비어 있다; 비디버그에서는 진단과 복사 경로가 없다; nonDebugDiagnosticsExposeNoViewOrCopyControls; restorationDoesNotRestoreDiagnosticPayload; openingEmptyDiagnosticsAndRecreationNeverStartConsentOrUpload (①④/유닛·Compose·Activity) | T1·T3·T4 |
| 진단 경로를 추가해도 기존 동의·권한·명시적 계속·private Drive·동일 파일 갱신·순차 요청·취소·이력 커밋 및 삭제 계약을 유지한다. | 기존 SyncCoordinatorTest: Partial upload failure reports only confirmed files; Cancellation propagates without reporting success; Profiling preserves successful sync outcomes call order and separate stage timings; PrivateDriveUploaderTest: Existing private date file is updated without creating duplicates; Ambiguous or shared targets are rejected before upload; 기존 coordinator/uploader/transport/reader/history 회귀 및 실제 Android/복구 CI; openingEmptyDiagnosticsAndRecreationNeverStartConsentOrUpload (①③④⑤/유닛·통합) | T4·전체 검증1·2 |
| 합성 JVM/Compose 검증으로 정상·실패·취소·빈 진단·새 시도·무효화·지연 콜백·복사 실패·비디버그 조건을 확인하고, 실제 기기에서 수행하지 않은 수집이나 클립보드 동작은 미확인으로 남긴다. | 종결 정상 실패 취소와 미완료를 업무 결과와 혼동하지 않는다; 위 상태/복사/UI 테스트 전체 (①②③④/유닛·Compose); 한계 문서 대조 | T1·T2·T3·T5 |
| 사용자에게 공유 전 안전한 요약만 확인하도록 안내하고 클립보드/프로세스 수명 한계를 명시하며, 원시 로그·건강 화면 첨부나 진단의 자동 외부 전송을 요구하지 않는다. | diagnosticsExplainSafeSharingAndSeparateClipboardLifetime (①/Compose); 실제 절차·자동 전송 부재는 문서/정적 확인 | T3·T5·전체 검증3 |

## 4. 작업

### T1: 제한된 메모리 진단 상태

- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `android/app/src/main/java/com/ny/my/health/PhoneProfilingSession.kt` | 신규 | 위 공통 변경 표의 T1 동작 |
  | `android/app/src/main/java/com/ny/my/health/ManualSyncProfiling.kt` | 수정 | 위 공통 변경 표의 T1 동작 |
  | `android/app/src/test/java/com/ny/my/health/PhoneProfilingSessionTest.kt` | 신규 | 위 공통 변경 표의 T1 동작 |
- **인터페이스**: 2절 `PhoneProfilingSession`, `safeProfilingSummary` 참조.
- **결정 로직**:
  ```text
  disabled → 상태 비어 있음·복사 없음
  begin현재profile → 이전 report/copy 결과 제거·수집 중
  complete현재profile → 고정 불변 report1건·profile 참조 제거
  complete이전profile/clear 뒤 완료 → 무시
  복사 가능한 report → writer1회·결과만 안내, 원문 오류 없음
  시간의 미관측/active/종결0/작은양수 →2절 고정 표시
  ```
- **작성할 테스트**: 신규 `진단 없음과 수집 중에는 복사와 과거 측정 표시가 없다`, `미관측 미완료 실제 영과 작은 양수 시간은 서로 다르게 표시한다`(0/1/999999/1000000ns/Long.MAX_VALUE와 clock 실패), `단계별 표시와 복사 요약에는 고정 안전 정보만 있다`(기존 safeSummary와 동일24행/8192자 이하), `최근 한 건의 불변 요약만 유지하고 새 세션은 비어 있다`(반복 시도/바깥 리스트/맵 변경 격리), `새 시도와 무효화는 이전 요약과 늦은 완료를 폐기한다`, `복사 실패는 안전한 안내만 남기고 명시적 재시도만 허용한다`, `복사 중 무효화 뒤 늦은 결과는 새 상태를 덮지 않는다`, `비디버그에서는 진단과 복사 경로가 없다`, `종결 정상 실패 취소와 미완료를 업무 결과와 혼동하지 않는다`.
- **완료 기준**: 최소1개로 새 상태 기반 기동 및 전 신규 테스트 PASS. 앱 흐름에는 아직 연결하지 않는다. collector 동작/기존 formatter 출력은 변경하지 않는다. formatter 추출만 허용하며 기존 ManualSyncProfilingTest도 재사용한다.
- **검증 방법**: `cd android && ./gradlew :app:testDebugUnitTest --tests com.ny.my.health.PhoneProfilingSessionTest --tests com.ny.my.health.ManualSyncProfilingTest`.
- **주의사항**: 플랫폼 IO는 writer 인자 밖에서 호출하지 않는다. session을 새 티켓 영속 저장이나 보고서 목록으로 확장하지 않는다. 정형/관찰 고장은 원래 업무 결과를 변경하지 않으며 안전하게 빈 진단으로 귀결한다. 고장 후 구현/검증 반복은 오케스트레이터의 기록된 복구로만 진행한다.

### T2: 실제 클립보드 쓰기 경계

T1 완료 후 시작.

- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `android/app/src/main/java/com/ny/my/health/PhoneProfilingClipboard.kt` | 신규 | 위 공통 변경 표의 T2 동작 |
  | `android/app/src/androidTest/java/com/ny/my/health/PhoneProfilingClipboardInstrumentedTest.kt` | 신규 | 위 공통 변경 표의 T2 동작 |
- **인터페이스**: 2절 `writePhoneProfilingClipboard` 및 `PhoneProfilingSession` 참조.
- **결정 로직**: 없음. 성공/실패 상태 판단은 session이 맡는다.
- **호출 순서**:
  ```text
  1. 전경 Activity의 명시적 copy가 제공한 safeText만 받는다.
  2. ClipboardManager.setPrimaryClip/ClipData.newPlainText에 고정 label로1회 쓴다.
  3. 예외는 로그/원문 안내 없이 session.copy로 전달한다.
  ```
- **작성할 테스트**: 신규 `explicitForegroundWritePlacesTheSameSafeSummaryOnSystemClipboard`. 기존 createAndroidComposeRule<MainActivity>와 실제 OS 서비스를 사용하고, 실제 읽기 비교는 합성 clip에만 수행한다. 외부 writer 실패 의미는 T1의 throwing lambda로 검증한다.
- **완료 기준**: 최소1개 실제 OS 연동 테스트로 기반 기동. 같은 합성 텍스트/고정 label의 쓰기를 확인하고 production에서 읽기/clear/자동 전송이 없다.
- **검증 방법**: `cd android && ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.ny.my.health.PhoneProfilingClipboardInstrumentedTest` (API35 계정 없는 에뮬레이터).
- **주의사항**: 합성 전용 에뮬레이터에서 실행한다. 기존 clipboard 내용을 수집/로그/복원하지 않는다. 테스트 정리는 자신이 쓴 label/text가 아직 일치할 때만 수행하며 알 수 없는 clipboard 값을 assertEquals 실패 메시지에 출력하지 않는다. URI 변환/coerceToText로 외부 데이터를 읽지 않는다. 최초 도입에서 SDK/서비스의 읽기 전용 확인은 허용하되 실패 시 설정 우회 없이 blocked로 보고한다.

체크포인트: T2 뒤 T1 JVM·APK compile 및 실제 clipboard 테스트 증거를 확인한다. 에뮬레이터가 없으면 해당 작업을 PASS로 처리하지 않고 CI에서 검증한다.

### T3: 선택적 진단 화면

T2 완료 후 시작.

- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `android/app/src/main/java/com/ny/my/health/PhoneProfilingSection.kt` | 신규 | 위 공통 변경 표의 T3 동작 |
  | `android/app/src/androidTest/java/com/ny/my/health/PhoneProfilingScreenInstrumentedTest.kt` | 신규 | 위 공통 변경 표의 T3 동작 |
- **인터페이스**: 2절 `PhoneProfilingSection` 및 `PhoneProfilingSession` 참조.
- **결정 로직**:
  ```text
  disabled → 진단 노드 없음
  기본 접힘 → 명시적 보기로 펼침
  report없음/collecting → 해당 안내·복사 비활성
  report있음 → 단계별 표시·명시적 copy 콜백만 허용
  ```
- **작성할 테스트**: 신규 `opensDiagnosticsExplicitlyAndCallsCopyOnlyOnClick`, `missingAndCollectingDiagnosticsNeverShowPreviousMeasurementsOrEnableCopy`(Compose층), `nonDebugDiagnosticsExposeNoViewOrCopyControls`(Compose층), `restorationDoesNotRestoreDiagnosticPayload`(StateRestorationTester+새 빈 state), `diagnosticsExplainSafeSharingAndSeparateClipboardLifetime`. light/dark·큰 글꼴에서 진단/복사 버튼을 스크롤해 접근할 수 있는지도 같은 fixture로 확인한다.
- **완료 기준**: 최소1개 테스트로 새 component 기반 기동, 새 UI 테스트 PASS, 새로운 외부 효과는 onCopy 콜백뿐. 실제 HealthHomeScreen 배선은 아직 하지 않는다.
- **검증 방법**: `cd android && ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.ny.my.health.PhoneProfilingScreenInstrumentedTest`.
- **주의사항**: rememberSaveable에는 펼침 boolean만 허용한다. 진단 데이터는 state 인자로만 받는다. callback 호출 수를 단언하며 새로운 인증/읽기/업로드 동작을 넣지 않는다. top app bar/insets/기존 레이아웃 변경 금지. 새 component 자신은 큰 글꼴에서 조작 가능해야 한다.

### T4: 수동 동기화 흐름 연결

T3 완료 후 시작.

- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `android/app/src/main/java/com/ny/my/health/SyncViewModel.kt` | 수정 | 위 공통 변경 표의 T4 동작 |
  | `android/app/src/main/java/com/ny/my/health/HealthHomeScreen.kt` | 수정 | 위 공통 변경 표의 T4 동작 |
  | `android/app/src/main/java/com/ny/my/health/MainActivity.kt` | 수정 | 위 공통 변경 표의 T4 동작 |
  | `android/app/src/androidTest/java/com/ny/my/health/HealthHomeScreenInstrumentedTest.kt` | 수정 | 위 공통 변경 표의 T4 동작 |
  | `android/app/src/androidTest/java/com/ny/my/health/HealthAppInstrumentedTest.kt` | 수정 | 위 공통 변경 표의 T4 동작 |
- **인터페이스**: 2절 `PhoneProfilingSession`, `PhoneProfilingSection`, `SyncUiState`, `SyncViewModel`, `HistoryUiActions`, `writePhoneProfilingClipboard` 참조.
- **결정 로직**:
  ```text
  FLAG_DEBUGGABLE → session.enabled와 UI 초기 상태에 같은 bool 적용
  기존 guard를 통과한 새 profile → begin으로 이전 진단 비우기
  현재 profile의 finish → complete로 안전한 report 반영
  로컬 무효화/optIn 변경 → clear 우선 적용으로 이전 finish의 재표시 거부
  명시적 copy → session.copy만 호출, 기존 sync 결과/자격 판정 유지
  ```
- **호출 순서**:
  ```text
  1. 기존 인증·실행·종결·중단·로그 순서를 유지하고 대응하는 session 이벤트만 연결한다.
  2. session 이벤트 뒤 최신 PhoneProfilingUiState를 StateFlow에 복사한다. 업무 상태/오류를 덮지 않는다.
  3. MainActivity에서 copyPhoneProfiling에 실제 OS writer를 전달한다.
  4. HomeScreen에 별도 섹션을 붙이고 기존 actions fixture도 명시적 새 callback으로 갱신한다.
  ```
- **작성할 테스트**: 신규 `openingEmptyDiagnosticsAndRecreationNeverStartConsentOrUpload`(실제 Activity). 기존 HealthHomeScreen/HealthApp/DurableHistoryActivity 및 HistorySession/ManualSyncProfiling/reader/uploader/transport 회귀 전부 재사용한다. 실제 VM 배선은 소스 대조와 실제 빈 Activity/lifecycle로 검증하며, 합성 UI/session 테스트를 실제 OAuth 완료 시나리오로 과장하지 않는다.
- **완료 기준**:5개 파일 범위에서 배선이 compile되고 전체 JVM/UI 회귀 PASS. history 오류/permission0인 상황도 진단 상태가 있으면 읽기/복사가 가능하며 그 동작이 권한이나 동기화 재시작을 요구하지 않는다. invalidateCurrentAttempt와 기존 무효화 callback에서도 진단 상태를 지운다.
- **검증 방법**: `cd android && ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`; 이후 전체 Android CI는5절2항.
- **주의사항**: current profile 객체와 session이 기록한 객체가 다르면 관찰 결과를 버린다. 이전 collector를 새 report로 강제 종결하거나 과거 이력으로 진단을 복원하지 않는다. DB/schema/credentials/collector/요청 경로는 수정하지 않는다. HistoryUiActions 신규 필드는 기존2개 호출 위치를 전부 명시 갱신하고 임시 무동작 기본값을 남기지 않는다.

체크포인트: T4 뒤 source diff에서 clip 쓰기 진입점이 명시적 버튼뿐인지, diagnostic clear가 늦은 종료보다 우선하는지, release flag 경로에 진단이 없는지 확인한다.

### T5: 기기 수집 절차와 실행 증거

T4 완료 후 시작.

- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `docs/2026-11-03-phone-profiling/device-validation.md` | 신규 | 위 공통 변경 표의 T5 동작 |
  | `docs/2026-11-03-phone-profiling/execution-report.md` | 신규 | 위 공통 변경 표의 T5 동작 |
  | `docs/2026-11-02-sync-profiling/device-profiling.md` | 수정 | 위 공통 변경 표의 T5 동작 |
  | `docs/backlog/sync-performance.md` | 수정 | 위 공통 변경 표의 T5 동작 |
- **인터페이스**: 새 API 없음.
- **결정 로직**: 실제 실행 증거만 PASS, 실제 Galaxy/공유 미실행은 미확인.
- **작성할 테스트**: 새 앱 테스트 불필요. 문서/소스/실행 XML·CI 산출물과 전체 검증3항 대조.
- **완료 기준**: 새 APK에서 동기화 후 진단 보기→복사→안전한24행 확인→사용자가 직접 전달하는 절차, 최근1건/프로세스/클립보드 수명 한계와 복사 실패 안내를 기록한다. 과거 ADB 절차와 미확인 상태는 보존한다. 한글 PR 코멘트에 검토head·지적·수정·재검증·판정을 남긴다.
- **검증 방법**: 저장소 루트 `git diff --check`.
- **주의사항**: 원시 로그/건강화면 첨부를 요구하지 않는다. 고정 합성 데이터만 공개한다. 실행 보고서에는 계획·실제 테스트 추적성, 실행하지 못한 검증과 별도 세션 검증 결과를 구분한다.

## 5. 전체 검증

1. 저장소 `android`에서 `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`를 실행하고 XML의 tests/failures/errors/skipped와 실제 source revision을 확인한다. 현재 환경에서는 기존 `python /workspace/tooling/run-health-command.py ./gradlew --offline --no-daemon`로 같은 task를 실행한다. 외부 wrapper는 repo 의존이 아니다. skip/단언 약화로 PASS를 만들지 않는다.
2. 기존 `.github/workflows/android-emulator-tests.yml`의 API35 전체 suite(hold 전용 class 제외)와 `android/ci/verify-history-process-recovery.sh`를 실행한다. 새 clipboard/UI/Activity 테스트가 실제 발견·실행됐는지 XML로 확인하고 정상 종료된 verifier만 센다. JVM mock으로 OS clipboard·SQLite·프로세스 복구를 입증하지 않는다.
3. approved spec11개 기준과 계획 테스트명/단언을 대조한다. 안전 문자열24행·8192자 상한, release 비노출, 새 영속/로그읽기/전송/권한/의존성 부재, 바뀌지 않은CSV/Drive/취소/이력 계약과 private 기기 절차를 점검한다. 새 summary·UI 데이터를 SavedState나DB에 넣지 않았는지 확인한다. 별도 fresh-context 세션에서 스펙만 보고 적합성 검증 후 코드·보안·성능 리뷰를 하고, 최종 head의 결과를 한글 PR 코멘트에 남긴다.

## 6. 위험과 롤백

진단 무효화 순서가 잘못되면 늦은 결과가 다시 표시될 수 있어 session identity와 clear/complete 순서를 검증한다. OS 쓰기는 실패할 수 있으므로 효과를 callback 경계에 고립하고 동기화 상태를 변경하지 않는다. 진단 UI/배선과 새 session/clipboard 파일을 제거하면 이전 logcat-only 경로로 되돌릴 수 있다. DB 이관·원격 파일 삭제·클립보드 자동 삭제는 필요하지 않다. 앱 메모리 수명과 OS 클립보드 수명은 동일하지 않다.

## 복구 기록

| 작업 | 회차/2 | 실패 증거·원인 | 새 근거·수정 가설 | 계획 보정 전→후·유지 기준 | 수정 파일·보존 대상 | 검증 명령·결과 | 실행자·다음 조치 |
|---|---|---|---|---|---|---|---|
| T1 | 1/2 | 실행자가 android cwd에서 android/ 접두사를 다시 사용해 신규 테스트 파일 생성 실패; 기존6개만 실행 | 절대 경로로 지정한 신규9개 테스트 작성 뒤 같은 대상 검증; 완료 코드 보존 | 기대값/스펙/파일 범위 유지; 미작성 테스트를 PASS로 세지 않음 | T1 세 파일; 기존 세션/정형 보존, formatter 들여쓰기만 정리 허용 | 신규9개+기존6개 실제 통과·실패/오류/skip0; root 같은 명령 및 XML 확인 | phone_t1 부분 복구 |
| T2 | 1/2 | 실행 전 D8 --min-api28 실험에서 공백 method name 거부 확인: DEX040 전 SimpleName 공백 불가 | Android 테스트명만 공백 없는 camelCase로 보정하고 추적성 반영 | minSdk28·실제 OS 검증·기대 동작과 파일 범위 유지; JVM 이름 유지 | plan의 해당 Android 테스트명만 보정 | 브리프 재조립·앱/테스트 APK compile PASS; API35 CI41672143507에서 해당 OS 테스트 포함49개·host20개 실제 통과 | phone_t2 최초 구현·root 원본 산출물 대조 완료 |
| T3 | 1/2 | 실행 전 D8 --min-api28 실험에서 공백 method name 거부 확인: DEX040 전 SimpleName 공백 불가 | Android 테스트명만 공백 없는 camelCase로 보정하고 추적성 반영 | minSdk28·실제 OS 검증·기대 동작과 파일 범위 유지; JVM 이름 유지 | plan의 해당 Android 테스트명만 보정 | 브리프 재조립·앱/테스트 APK compile PASS; API35 CI41675121121 head adf71a7에서 신규5개 포함54개·host20개 실제 통과·실패/오류/skip0 | phone_t3 최초 구현·root 원본 산출물 대조 완료 |
| T4 | 1/2 | 실행 전 D8 --min-api28 실험에서 공백 method name 거부 확인: DEX040 전 SimpleName 공백 불가 | Android 테스트명만 공백 없는 camelCase로 보정하고 추적성 반영 | minSdk28·실제 OS 검증·기대 동작과 파일 범위 유지; JVM 이름 유지 | plan의 해당 Android 테스트명만 보정 | 앱/테스트 APK·전체 JVM109개·lint PASS; API35 CI41681123691 head4a061cb에서 신규 Activity 포함55개·host20개 실제 통과 | phone_t4 최초 구현·root 직접 명령/원본 XML 대조 완료 |
| T2 | 2/2 | 통합 CI41678564632 head fc47a01:55개 중 clipboard 고정 label 단언1개 실패·오류/skip0. 같은 writer/test는 T2·T3 CI에서 통과했고 T4는 이를 수정하지 않음. 원본 합성 log에서 RESUMED와 첫 표시 사이 지연 확인; runOnIdle만으로 window focus를 보장하지 않음 | 테스트가 요구하는 전경/입력 focus를 쓰기 전에 명시적으로 기다리고 실제 쓰기 직전에도 단언한다. focus 미확인을 원인 가설로 한정하며 production 성공으로 추정하지 않는다 | 같은 label·동일24행·한 항목·명시적1회 쓰기·소유 fixture만 정리 기준 유지. 단언 완화/재쓰기 반복/권한·OS 설정 변경 없음 | T2 clipboard instrumentation 파일만 보정; 검증된 T1/T3 및 T4 배선 보존 | 앱/테스트 APK compile 실행자22초/root16초 PASS; CI41681123691 head4a061cb에서55개·host20개 통과, 실패/오류/skip0. 원본 XML·60PNG·artifact digest 대조 | phone_t2 부분 복구 후 root 검증 완료; 사용2/2 |
| T5 | 1/2 | root의 실제 diff 대조에서 execution-report의 T4 변경 파일이 수정하지 않은 workflow로 잘못 표기되고 실제 HealthHomeScreenInstrumentedTest fixture 수정이 목록에서 누락됨 | 원본 git diff의 파일 집합으로 문서 목록을 바로잡는다. 전체 검증3 중 독립 검토는 아직 대기임을 더 명확히 구분한다 | 승인된 앱/문서 범위·실제 테스트·기대값 유지; 문서 사실 교정만 수행 | execution-report.md만; 기기 절차·앱 코드 보존 | git diff --check·실제 changed-file/test-fixture 목록·문서 로컬 링크 대조 PASS | phone_t5 부분 복구 후 root 검증 완료; 사용1/2 |

## 환경 준비 기록

| 원인·근거 | 준비 조치 | 조치 사용/1 | 확인 명령·결과 | 확인 사용/1 | 다음 조치 |
|---|---|---|---|---|---|
| 로컬 에뮬레이터/KVM 없음; 실제 OS 검증은 로컬에서 실행 불가 | 승인된 기존 API35 CI로 T2 실제 검증; source/테스트 APK 컴파일로 대체하지 않음 | 1/1 | API35 CI41672143507 head bc044e8 실제 Android49개·host20개 통과, 실패/오류/skip0; 원본 XML·60PNG·artifact SHA256 확인 | 1/1 | 기반/화면/통합 OS 검증 완료; 후속 실패·복구와 최신 결과는 복구 기록 참조 |

## 구현 검증 체크포인트

T1–T5는 완료했다. 문서 완료 뒤 root 전체 Gradle 검증37초 PASS, core41/app68=109개 실패/오류/skip0, debug 앱/테스트 APK·lint PASS다. Android 및 workflow는 실제 OS 검증 source4a061cb와 byte 차이가 없고, CI41681123691의55개·host20개 통과를 원본으로 확인했다. 전체 검증3의 독립 스펙 적합성·코드/보안/성능 검토와 PR 처분은 아직 남아 plan은 in_progress로 유지한다. 실제 Galaxy·계정·TalkBack·성능 수집 및 기존 P0 미확인은 이 체크포인트로 닫지 않는다.

독립 스펙 적합성 검증: e8191ef에서11/11 PASS, 자체 JVM109개·debug/release 빌드 통과 및 원본 Android55/host20 대조 완료. [PR 검증 기록](https://github.com/nyeonu/health-app/pull/12#issuecomment-6611111570). root는 기준 문장11개·신규16개 테스트명의 source/XML·skip0을 대조했고, 독립 검증에서 사용한 기존 테스트 이름도 추적성에 명시했다. 코드/보안/성능 검토는 다음 단계다.
