---
plan_id: 2026-11-02-sync-profiling
title: 수동 동기화 단계별 측정 실행 계획
kind: single
status: in_progress
author: Codex
created: 2026-11-02
target: nyeonu/health-app
spec: spec.md
spec_rev: 9ef46ec98406caf48840d510c017b481742d1e80
tasks:
  - id: T1
    title: 제한된 계측 기반
    depends_on: []
    status: done
    tier: standard
  - id: T2
    title: 코어 실행 구간 계측
    depends_on: [T1]
    status: done
    tier: standard
  - id: T3
    title: Drive 작업별 요청 계측
    depends_on: [T2]
    status: done
    tier: standard
  - id: T4
    title: 로컬 이력 커밋 계측
    depends_on: [T3]
    status: in_progress
    tier: standard
  - id: T5
    title: 실제 수동 인증 흐름 연결
    depends_on: [T4]
    status: pending
    tier: standard
  - id: T6
    title: 실제 TLS 경계 회귀
    depends_on: [T5]
    status: pending
    tier: standard
  - id: T7
    title: 반복 가능한 합성 기준선
    depends_on: [T6]
    status: pending
    tier: standard
  - id: T8
    title: 증거와 기기 절차 정리
    depends_on: [T7]
    status: pending
    tier: standard
---

# 수동 동기화 단계별 측정 실행 계획

승인 기록: 2026-11-03 소유자가 “좋아 구현 시작해”로 계획 실행을 승인했다.

## 1. 선택한 방식과 이유

기존 실행 경로에 선택적 메모리 수집기 하나를 전달한다. 고정 enum만 받는 수집기로 값·날짜·식별자가 진단 모델에 들어갈 경로를 줄이고, System.nanoTime 경과 시간을 기록한다. 실행 전체·단계·작업·요청 시간은 중첩되므로 합산하지 않는다. 기존 순차 실행과 mutex를 유지해 현재 경로를 관찰한다.

인증 요청 이후의 외부 대기와 인증 수락 이후의 명시적 계속 대기를 구분한다. 실행 시간은 ‘업로드 계속’ 수락부터 종결/중단 이력 커밋 반환까지이며 업로드 전용 시간이 아니다. 화면 대시보드 대신 debug 앱에서만 종료 시 고정 요약을 logcat에 1회 남긴다. 실제 기기의 전체 로그/스크린샷은 공개하지 않는다.

검토한 대안은 호출자 병렬화와 모든 HTTP 이벤트 기록이다. 전자는 기존 mutex 때문에 효과를 보장할 수 없고 후자는 URL 유출·무제한 로그 위험이 있어 채택하지 않는다. 새 의존성·스키마·인증 전략을 도입하지 않는 되돌릴 수 있는 계측이므로 새 ADR 후보는 없다. 계획은 draft이며 별도 세션 리뷰가 사용자 승인을 대신하지 않는다.

## 2. 공통 설계

### 변경 파일

| 파일 | 신규/수정 | 변경 요지 | 작업 |
|---|---|---|---|
| `android/core/src/main/kotlin/com/ny/my/health/core/SyncProfiling.kt` | 신규 | 고정 enum·단조 시계·제한된 요약·예외 투명 계측 | T1 |
| `android/core/src/test/kotlin/com/ny/my/health/core/SyncProfilingTest.kt` | 신규 | 수치 의미·비실행·실패·취소·수집기 결함 검증 | T1 |
| `android/core/src/main/kotlin/com/ny/my/health/core/SyncCoordinator.kt` | 수정 | 읽기·정규화·CSV·Drive 전체 구간 | T2 |
| `android/core/src/test/kotlin/com/ny/my/health/core/SyncCoordinatorTest.kt` | 수정 | 계측 유무 결과/순서 동일성 | T2 |
| `android/core/src/main/kotlin/com/ny/my/health/core/PrivateDriveUploader.kt` | 수정 | 기존 operation 및 실제 transport 호출별 계측 | T3 |
| `android/core/src/test/kotlin/com/ny/my/health/core/PrivateDriveUploaderTest.kt` | 수정 | 요청 수·페이지·거부·취소 계측 | T3 |
| `android/app/src/main/java/com/ny/my/health/HistorySession.kt` | 수정 | HistoryEffects의 시작·종결/중단 커밋 구간 | T4 |
| `android/app/src/test/java/com/ny/my/health/HistorySessionTest.kt` | 수정 | 실패 저장과 취소 시 진단 및 효과 순서 | T4 |
| `android/app/src/main/java/com/ny/my/health/ManualSyncProfiling.kt` | 신규 | 비동기 인증/계속 대기와 단일 시도 메모리 수명 | T5 |
| `android/app/src/test/java/com/ny/my/health/ManualSyncProfilingTest.kt` | 신규 | 지연 콜백·거부·명시적 계속·안전 출력 | T5 |
| `android/app/src/main/java/com/ny/my/health/SyncViewModel.kt` | 수정 | 실제 수동 흐름 연결 및 debug 전용 제한 요약 출력 | T5 |
| `android/app/src/test/java/com/ny/my/health/GoogleDriveTransportTest.kt` | 수정 | 계측한 실제 local TLS 경계 회귀 | T6 |
| `android/app/src/test/java/com/ny/my/health/SyncProfilingBaselineTest.kt` | 신규 | 고정 합성 fixture와 실제 local TLS 반복 실행 | T7 |
| `docs/2026-11-02-sync-profiling/baseline.md` | 신규 | revision·환경·표본·분포·한계·후속 후보 | T7 |
| `docs/2026-11-02-sync-profiling/execution-report.md` | 신규 | 실제 실행/검증·잔여 항목 | T8 |
| `docs/2026-11-02-sync-profiling/device-profiling.md` | 신규 | 안전한 실제 기기 수집 절차 | T8 |
| `docs/backlog/sync-performance.md` | 수정 | 측정 결과와 다음 최적화 후보 상태 | T8 |

### 인터페이스

```kotlin
// SyncProfiling — core의 신규 SyncProfiling.kt
// 고정 enum map만 보관. 중복 start와 대응하지 않는 finish는 중복 집계하지 않는다.
// elapsedNanos는 종료된 호출의 합이며 active=true인 미완료 호출 시간은 포함하지 않는다.
// 시계 실패/음수 차분은 관찰된0으로 만들지 않고 해당 측정을 버린다.
// snapshot은 복사본, count/elapsed는 비음수 포화 합, block 예외/취소 identity 보존.
// 단계/operation/request는 별도 집계하며 중첩 시간을 합산하지 않는다.
fun interface ProfilingClock { fun nowNanos(): Long }
enum class ProfileStage {
    AUTHORIZATION_HISTORY, AUTHORIZATION_WAIT, CONTINUE_WAIT,
    EXECUTION, START_HISTORY, HEALTH_READ, NORMALIZE, CSV_EXPORT,
    DRIVE_TOTAL, TERMINAL_HISTORY
}
enum class ProfileOutcome { COMPLETED, FAILED, CANCELLED }
data class ProfileStats(
    val started: Long, val completed: Long, val failed: Long,
    val cancelled: Long, val elapsedNanos: Long, val active: Boolean
)
data class ProfileSummary(
    val stages: Map<ProfileStage, ProfileStats>,
    val operations: Map<DriveOperation, ProfileStats>,
    val requests: Map<DriveOperation, ProfileStats>
)
class SyncProfiling(clock: ProfilingClock = ProfilingClock(System::nanoTime)) {
    fun start(stage: ProfileStage)
    fun finish(stage: ProfileStage, outcome: ProfileOutcome)
    fun snapshot(): ProfileSummary
    suspend fun <T> measure(stage: ProfileStage, block: suspend () -> T): T
    fun <T> measureValue(stage: ProfileStage, block: () -> T): T
    suspend fun <T> operation(kind: DriveOperation, block: suspend () -> T): T
    suspend fun <T> request(kind: DriveOperation, block: suspend () -> T): T
}
```

```kotlin
// SyncCoordinator — 기존 생성자 끝에 선택 인자 추가
// SyncCoordinator(..., clock: Clock, profiling: SyncProfiling? = null)
// PrivateDriveUploader — 같은 시도의 collector 공유
// PrivateDriveUploader(transport: DriveTransport, profiling: SyncProfiling? = null)
// HistoryEffects — execute의 마지막 effect 앞에 선택 인자 추가
// execute(..., profiling: SyncProfiling? = null,
//         effect: suspend (LocalDate) -> HistoryExecutionResult): HistoryExecutionResult?
```

```kotlin
// ManualSyncProfiling — app의 신규 ManualSyncProfiling.kt, Android IO와 분리
internal class ManualSyncProfiling(clock: ProfilingClock = ProfilingClock(System::nanoTime)) {
    val collector: SyncProfiling
    fun authorizationStarted()
    fun authorizationReturned(accepted: Boolean)
    fun executionStarted()
    fun finish(outcome: ProfileOutcome): ProfileSummary
    fun safeSummary(): String
    fun publish(debugEnabled: Boolean, emit: (String) -> Unit)
}
```

### 결정 로직 위치

- SyncProfiling의 순수 통계 갱신·차분 계산은 같은 파일에 둔다. 고정 enum 수만큼만 유지하며 count/elapsed는 비음수·포화 덧셈이다. 미시작 단계는 map에 없고 같은 tick에서 완료된 단계는 elapsed0으로 존재한다. 진행 중은 active로 표시한다. 시계 획득 실패/음수 차분은 측정0으로 위조하지 않고 해당 측정을 폐기한다.
- start/finish는 동기화하고 이중 시작/종료를 중복 집계하지 않는다. 단계/operation/request는 별도 map이다. 페이지별 값이나 이벤트 목록을 저장하지 않는다. snapshot은 복사본이며 외부 변경이 내부 상태에 영향을 주지 않는다.
- block의 예외·CancellationException은 같은 객체로 전파하고 수집기 자신의 clock/정형/출력 고장만 격리한다. 기존 함수가 FAILED/거부/저장실패를 값으로 반환하면 명시적으로 FAILED를 기록하며 정상 반환만으로 완료라 하지 않는다.
- operation은 기존 uploader.operation 검증 전체, request는 transport.execute 호출 직전부터 HTTP/JSON 판정까지다. request.started는 시도 횟수이며 성공 업로드 수나 wire 송신 보장이 아니다. 실제 전송 수는 TLS 서버 관찰로 별도 확인한다. 모든 조회 페이지가 request에 포함되며 raw URL/query/ID는 전달하지 않는다.
- ManualSyncProfiling의 순수 상태 전이·safeSummary 정형은 같은 파일에 둔다. 임의 문자열/Throwable/SyncResult.errorCode/날짜/파일수/건강 기록 건수를 받지 않는다. 출력은 고정 enum과 정수만 고정 순서로 최대8192자, 종료 시1회다. publish는 false일 때 출력0, sink 고장도 업무 결과를 바꾸지 않는다.

### 관련된 암묵적 동작

- HistoryEffects.execute는 시작·종결/중단 커밋을 NonCancellable에서 완료하고 remoteConfirmed를 종결 저장보다 먼저 알린다. 저장 실패가 정상 반환할 수 있어 HistorySessionState.error까지 확인한다. 순서를 바꾸지 않는다. (확인: android/app/src/main/java/com/ny/my/health/HistorySession.kt)
- SyncCoordinator는 일반 예외를 FAILED로 바꾸고 CancellationException은 재전파한다. (확인: android/core/src/main/kotlin/com/ny/my/health/core/SyncCoordinator.kt)
- uploader mutex는 전체 파일 작업을 직렬화하며 request가 HTTP/JSON, operation이 private-target 검증 실패를 감싼다. (확인: android/core/src/main/kotlin/com/ny/my/health/core/PrivateDriveUploader.kt)
- GoogleDriveTransport는 취소 시 Call.cancel, 성공 body 읽기 후 재개한다. redirect/retry는 꺼져 있고 쓰기는 one-shot이다. (확인: android/app/src/main/java/com/ny/my/health/GoogleDriveTransport.kt)
- debug 판정은 ApplicationInfo.FLAG_DEBUGGABLE을 사용한다. 실제 debug APK 플래그와 출력 억제는 T5에서 확인한다. (추정: 이 진단 출력 경로는 현재 없음)
- JVM/local TLS는 실제 Google/OAuth/Health Connect/SQLite 커밋 측정이 아니다. 기존 Android/SQLite CI는 유지한다. (확인: .github/workflows/android-debug-apk.yml, android/app/src/androidTest)

## 3. 추적성

출처: ①성공 기준, ②동등 분할·경계, ③오류, ④상태·순서, ⑤과거 버그. 새 테스트명은 Kotlin backtick 함수명으로 사용한다. 기존 테스트는 충분하면 중복하지 않고 재사용한다.

| 스펙 기준 | 테스트 이름 (출처/층) | 충족 위치 |
|---|---|---|
| 인증·사용자 대기와 실제 동기화 실행이 구분되어 대기 시간을 upload-only latency로 보고하지 않는다. | Authorization and continuation waits never contribute to execution elapsed (①④/유닛) | T5 |
| 실행된 읽기·변환·Drive 작업·로컬 이력 저장의 경과 시간과 요청 작업별 횟수를 구분하고, 실행하지 않은 단계는 측정된0과 구분한다. | Unstarted stages are absent while completed zero duration stages remain present; Drive request counts match warm cold and paginated calls (①②/유닛) | T1·T3 |
| 경과 시간은 벽시계 변경에 영향을 받지 않는 단조 시계로 측정하며 오류·취소 시 완료한 구간과 미완료 구간을 구분한다. | Monotonic stage timing survives wall clock changes and preserves failed cancelled and active states (①②③/유닛) | T1·T2 |
| 진단은 기존 SyncResult, 부분 업로드 수, 취소 전파, 권한/비공개 대상 판정 및 이력의 커밋·실패 보장을 바꾸거나 새 실행·재시도를 만들지 않는다. | Profiling preserves sync outcomes partial confirmations cancellation and history commit failures (①③④/유닛 + 기존 SQLite 통합) | T2·T4·T6, 전체 검증2 |
| 진단에는 건강 수치·건강 기록 건수·보고 날짜·CSV·계정/파일/레코드 식별자·토큰·URL/query·공급자 오류 원문을 포함하지 않는다. | Safe profiling summary contains only fixed enums and numeric statistics (①②③/유닛) | T1·T5 |
| 공개 산출물은 고정 합성 fixture만 사용하고 실제 기기 진단은 명시적으로 공유한 안전한 결과 외에는 비공개로 유지한다. | — (문서/fixture 일치와 비공개 경계는 코드·문서 리뷰) | T7·T8, 전체 검증3 |
| 계측 상태는 제한된 작업 종류의 요약으로 관리하며 페이지·기록마다 무제한 로그나 새 영속 사용자 이력을 만들지 않는다. | Profiling storage remains bounded after repeated requests and terminal snapshots (①②/유닛) | T1·T5 |
| 동일한 fixture·원격 트리 상태·측정 환경의 반복 결과에 source revision, 조건, 표본 수와 산출 방법을 기록해 비교 가능한 기준선을 제공한다. | Local TLS baseline records repeatable scenarios sample counts and nearest rank percentiles (①/통합) | T7 |
| 기존 요청 순서·동일 파일 갱신·권한/EMPTY·위험 대상 거부·취소 회귀가 유지되고, 측정 코드 때문에 추가 원격 요청이나 암묵 재시도가 생기지 않는다. | Instrumented local TLS keeps request order private validation and cancellation without replay (①③④/통합, 기존 uploader/coordinator 회귀 재사용) | T2·T3·T6 |
| 보고서는 실제 관찰과 코드상 후보·외삽을 구분하고, 실제 기기 증거 없이 사용자 체감 개선이나 실사용 지배 병목을 확정하지 않는다. | — (실측/가설 표현과 증거 경계는 문서 리뷰) | T7·T8, 전체 검증3 |
| 최적화 후보와 필요한 추가 검증을 측정 결과에서 도출하되 실제 최적화 구현과 수치 목표는 별도 승인 대상으로 남긴다. | — (최적화 코드/수치 목표 부재는 정적·문서 확인) | T8, 전체 검증3 |

## 4. 작업

### T1: 제한된 계측 기반

- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `android/core/src/main/kotlin/com/ny/my/health/core/SyncProfiling.kt` | 신규 | 고정 enum·단조 시계·제한된 요약·예외 투명 계측 |
  | `android/core/src/test/kotlin/com/ny/my/health/core/SyncProfilingTest.kt` | 신규 | 수치 의미·비실행·실패·취소·수집기 결함 검증 |

- **인터페이스**: 2절 `SyncProfiling` 참조.
- **결정 로직**:

  ```text
  미시작 → 누락
  같은 tick 종료 → 관찰된0
  정상/예외/취소 → 해당 종결 통계
  포화·시계 고장 → 무제한 증가/예외 유출 없음
  ```
- **작성할 테스트**: 신규: Unstarted stages are absent while completed zero duration stages remain present; Monotonic stage timing survives wall clock changes and preserves failed cancelled and active states; Profiling storage remains bounded after repeated requests and terminal snapshots. clock 고장에서도 block 반환값/원래 예외 identity를 보존한다.
- **완료 기준**: 최소1개 테스트로 새 collector 문맥이 기동하며 새 테스트 전부 통과. 제품 경로 연결은 아직 하지 않는다.
- **검증 방법**:

  ```bash
  cd android
  python /workspace/tooling/run-health-command.py ./gradlew --offline --no-daemon :core:test --tests com.ny.my.health.core.SyncProfilingTest
  ```
- **주의사항**: 새 runtime 수집기 기반 도입을 별도 작업으로 분리한다. 기존 의존성만 사용한다. 추정이 틀리면 우회하지 말고 blocked 보고.

### T2: 코어 실행 구간 계측

T1 완료 후 시작.

- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `android/core/src/main/kotlin/com/ny/my/health/core/SyncCoordinator.kt` | 수정 | 읽기·정규화·CSV·Drive 전체 구간 |
  | `android/core/src/test/kotlin/com/ny/my/health/core/SyncCoordinatorTest.kt` | 수정 | 계측 유무 결과/순서 동일성 |

- **인터페이스**: 2절 `SyncCoordinator`, `SyncProfiling` 참조.
- **결정 로직**:

  ```text
  PERMISSION_REQUIRED/EMPTY → 후속 미실행
  FAILED 값 → 업무 결과 보존·Drive 단계 FAILED
  부분 성공/취소 → 확인 수와 취소 전파 보존
  ```
- **호출 순서**:

  ```text
  1. read → normalize → export → 기존 순차 upsert. 기존 경계만 관찰하고 재시도하지 않는다.
  2. 업무 예외 변환을 보존하고 수집기 고장을 업무 catch에 섞지 않는다.
  ```
- **작성할 테스트**: 신규: Profiling preserves sync outcomes partial confirmations cancellation and history commit failures의 core 부분. 기존 Empty and denied reads never upload or report success, Partial upload failure reports only confirmed files, Cancellation propagates without reporting success를 계측 유/무로 재사용.
- **완료 기준**: read/normalize/CSV/Drive-total 별도 측정, 결과·호출 순서·예외 identity 보존.
- **검증 방법**:

  ```bash
  cd android
  python /workspace/tooling/run-health-command.py ./gradlew --offline --no-daemon :core:test --tests com.ny.my.health.core.SyncCoordinatorTest
  ```
- **주의사항**: EXECUTION 전체는 앱 소유로 core에서 이중 시작하지 않는다. 중첩 시간은 합산하지 않는다.

### T3: Drive 작업별 요청 계측

T2 완료 후 시작.

- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `android/core/src/main/kotlin/com/ny/my/health/core/PrivateDriveUploader.kt` | 수정 | 기존 operation 및 실제 transport 호출별 계측 |
  | `android/core/src/test/kotlin/com/ny/my/health/core/PrivateDriveUploaderTest.kt` | 수정 | 요청 수·페이지·거부·취소 계측 |

- **인터페이스**: 2절 `PrivateDriveUploader`, `SyncProfiling` 참조.
- **결정 로직**:

  ```text
  transport 호출 시작 → operation별 request.started 증가
  HTTP/JSON 실패 → request FAILED
  private-target 검증 실패 → operation FAILED
  여러 page → 각 request 집계·page 값 미보관
  ```
- **호출 순서**:

  ```text
  1. 기존 operation wrapper의 전체 검증 경계를 operation으로 관찰한다.
  2. 기존 request의 transport 직전부터 HTTP/JSON 판정까지 request로 관찰한다.
  3. 기존 private 검증·오류 변환·취소 전파를 보존한다.
  ```
- **작성할 테스트**: 신규: Drive request counts match warm cold and paginated calls. warm GET3+PATCH1, cold7, warm CSV3의12회를 실행에서 단언. HTTP·JSON·공유/모호 대상 거부·취소는 기존9개 회귀 재사용.
- **완료 기준**: operation과 request 수/경과를 구분하고 계측 자체 추가 remote 호출0.
- **검증 방법**:

  ```bash
  cd android
  python /workspace/tooling/run-health-command.py ./gradlew --offline --no-daemon :core:test --tests com.ny.my.health.core.PrivateDriveUploaderTest
  ```
- **주의사항**: request 시간은 HTTP/JSON까지 포함하며 DNS/TLS/전송 세부분해가 아니다. 요청 완료와 private-target 거부를 operation 결과로 구별한다.

체크포인트: T3 후 :core:test 전체 실행, 최적화·새 의존성·외부 진단 전송이 diff에 없는지 확인.

### T4: 로컬 이력 커밋 계측

T3 완료 후 시작.

- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `android/app/src/main/java/com/ny/my/health/HistorySession.kt` | 수정 | HistoryEffects의 시작·종결/중단 커밋 구간 |
  | `android/app/src/test/java/com/ny/my/health/HistorySessionTest.kt` | 수정 | 실패 저장과 취소 시 진단 및 효과 순서 |

- **인터페이스**: 2절 `HistoryEffects`, `SyncProfiling` 참조.
- **결정 로직**:

  ```text
  beginExecution false → START_HISTORY 실패·effect 미호출
  commit 뒤 history.error → TERMINAL_HISTORY 실패·remote 결과 보존
  취소 → 중단 commit을 관찰·원래 취소 전파
  ```
- **호출 순서**:

  ```text
  1. 기존 NonCancellable 시작 커밋 관찰. 실패하면 effect 미호출.
  2. effect → remoteConfirmed → 기존 NonCancellable 종결 커밋 관찰.
  3. 취소 시 기존 중단 커밋 관찰 뒤 원래 취소 재전파.
  ```
- **작성할 테스트**: 신규: Profiling preserves sync outcomes partial confirmations cancellation and history commit failures의 저장 부분. 기존 HistorySessionTest의 시작/종결 저장 실패, 취소, remoteConfirmed 순서 회귀를 계측 유/무로 재사용.
- **완료 기준**: 선택 인자 기본값으로 기존 호출 호환. 시작/종결/중단 시간 획득, 저장 실패를 성공으로 바꾸지 않는다.
- **검증 방법**:

  ```bash
  cd android
  python /workspace/tooling/run-health-command.py ./gradlew --offline --no-daemon :app:testDebugUnitTest --tests com.ny.my.health.HistorySessionTest
  ```
- **주의사항**: NonCancellable/remoteConfirmed 선행 의미는2절 참조. SQLite schema/store의 쓰기 처리 변경 금지. effect 마지막 인자를 유지해 trailing lambda 호환.

### T5: 실제 수동 인증 흐름 연결

T4 완료 후 시작.

- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `android/app/src/main/java/com/ny/my/health/ManualSyncProfiling.kt` | 신규 | 비동기 인증/계속 대기와 단일 시도 메모리 수명 |
  | `android/app/src/test/java/com/ny/my/health/ManualSyncProfilingTest.kt` | 신규 | 지연 콜백·거부·명시적 계속·안전 출력 |
  | `android/app/src/main/java/com/ny/my/health/SyncViewModel.kt` | 수정 | 실제 수동 흐름 연결 및 debug 전용 제한 요약 출력 |

- **인터페이스**: 2절 `ManualSyncProfiling`, `SyncProfiling` 참조.
- **결정 로직**:

  ```text
  새 유효 인증 → 이전 collector 폐기·단일 시도
  수락 callback → AUTHORIZATION_WAIT 종료·CONTINUE_WAIT 시작
  명시적 계속 수락 → CONTINUE_WAIT 종료·EXECUTION 시작
  실패/opt-out/날짜 변경/폐기 → 명시 중단·늦은 callback 격리
  ```
- **호출 순서**:

  ```text
  1. 인증 시작 저장은 AUTHORIZATION_HISTORY, 외부 인증부터 반환까지는 AUTHORIZATION_WAIT으로 관찰.
  2. 실행 시 동일 collector를 HistoryEffects/coordinator/uploader로 전달.
  3. finally에서 실행/잔여 대기 종료 후 debug 한정 안전 요약1회 출력.
  ```
- **작성할 테스트**: 신규: Authorization and continuation waits never contribute to execution elapsed; Safe profiling summary contains only fixed enums and numeric statistics. 긴 가상 대기, 거부/취소/늦은 callback/opt-out/foreground 취소, 시작/종결 실패, 출력 비활성·throwing sink 검증. 실제 VM 연결은 정적 리뷰와 기존 Activity emulator 회귀를 병행.
- **완료 기준**: 대기와 실행 별도. non-debug 출력0, debug도 고정 enum·정수만. token/ticket/date/error/provider 값을 요약에 전달하지 않는다.
- **검증 방법**:

  ```bash
  cd android
  python /workspace/tooling/run-health-command.py ./gradlew --offline --no-daemon :app:testDebugUnitTest --tests com.ny.my.health.ManualSyncProfilingTest :app:assembleDebug :app:lintDebug
  ```
- **주의사항**: 추정: ApplicationInfo.FLAG_DEBUGGABLE을 APK에서 확인. 구현 전 읽기 전용 확인 후 위임 범위 안에서 구현·보고하며 기동/컴파일/검증 실패 시 blocked. 초기화·재개만으로 인증/업로드하지 않는다. 출력 고장은 업무 결과를 바꾸지 않는다.

체크포인트: T5 후 core+app JVM 전체, debug build/lint. 계측 유/무 결과와 비출력·보관 상한 확인.

### T6: 실제 TLS 경계 회귀

T5 완료 후 시작.

- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `android/app/src/test/java/com/ny/my/health/GoogleDriveTransportTest.kt` | 수정 | 계측한 실제 local TLS 경계 회귀 |

- **인터페이스**: 2절 `SyncProfiling`, `PrivateDriveUploader` 참조.
- **결정 로직**:

  ```text
  실제 TLS 응답/끊김/취소 → 완료/실패/취소 구별
  서버 재시도 지시 → 기존처럼 replay 없음
  ```
- **작성할 테스트**: 신규: Instrumented local TLS keeps request order private validation and cancellation without replay. 기존 GoogleDriveTransportTest6개를 collector와 함께 재사용, 실제 MockWebServer의 request 수/순서를 요약과 대조. 거부 URL/redirect credential 보호 회귀 보존.
- **완료 기준**: 실제 OkHttp/local TLS에서 정상·실패·취소 일치. production transport code 약화 없음.
- **검증 방법**:

  ```bash
  cd android
  python /workspace/tooling/run-health-command.py ./gradlew --offline --no-daemon :app:testDebugUnitTest --tests com.ny.my.health.GoogleDriveTransportTest
  ```
- **주의사항**: local rewrite는 기존 테스트 경계만. 실제 Google 계정/지연 검증이라 하지 않는다.

### T7: 반복 가능한 합성 기준선

T6 완료 후 시작.

- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `android/app/src/test/java/com/ny/my/health/SyncProfilingBaselineTest.kt` | 신규 | 고정 합성 fixture와 실제 local TLS 반복 실행 |
  | `docs/2026-11-02-sync-profiling/baseline.md` | 신규 | revision·환경·표본·분포·한계·후속 후보 |

- **인터페이스**: 2절 `SyncProfiling`, `SyncCoordinator`, `PrivateDriveUploader` 참조.
- **결정 로직**:

  ```text
  고정 조건·fixture별 → 분포 분리
  각 scenario warmup3회 제외·관찰10회 → nearest-rank p50/p95·min/max
  합성 실패/취소 → 성공 분포와 별도 결과
  ```
- **작성할 테스트**: 신규: Local TLS baseline records repeatable scenarios sample counts and nearest rank percentiles. 고정 날짜/값/권한 합성 snapshot의 warm1CSV/cold1CSV/warm3CSV를 실제 coordinator→uploader→GoogleDriveTransport→local TLS로 반복, 매회 request 순서/수 단언. 통계 계산은 알려진 배열로 검증.
- **완료 기준**: build/reports/sync-profiling-baseline에 합성 요약만 생성. baseline.md에 각 표본 시간·작업 수, source revision, JDK/OS/SDK/의존, fixture, remote 상태 reset법, 연결 재사용 조건과 산출법 기록.
- **검증 방법**:

  ```bash
  cd android
  python /workspace/tooling/run-health-command.py ./gradlew --offline --no-daemon :app:testDebugUnitTest --tests com.ny.my.health.SyncProfilingBaselineTest --rerun-tasks
  ```
- **주의사항**: 실제 건강/credential 사용 금지. 응답은 고정 합성 metadata. local TLS 시간을 Google 지연으로 외삽하지 않는다. auth/SQLite/실HC 미측정을 명시한다. 후보는 관찰한 단계 분포·요청 수에서만 도출.

### T8: 증거와 기기 절차 정리

T7 완료 후 시작.

- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `docs/2026-11-02-sync-profiling/execution-report.md` | 신규 | 실제 실행/검증·잔여 항목 |
  | `docs/2026-11-02-sync-profiling/device-profiling.md` | 신규 | 안전한 실제 기기 수집 절차 |
  | `docs/backlog/sync-performance.md` | 수정 | 측정 결과와 다음 최적화 후보 상태 |

- **인터페이스**: 새 공유 API 없음.
- **결정 로직**:

  ```text
  확인된 자동 증거 → revision/명령/결과 기록
  미실행 device/account 수용 → 미확인 유지
  ```
- **작성할 테스트**: 새 앱 테스트 불필요. 전체 검증1~3 및 별도 세션 spec-conformance/code/security/performance 리뷰.
- **완료 기준**: device-profiling.md에 debug 전용 단일 tag 요약의 비공개 수집·동일 조건 반복·공유 전 allowlist 확인 절차. raw log/CSV/실데이터를 repo에 넣지 않고 실행 보고와 backlog를 실제 증거에 맞춘다.
- **검증 방법**:

  ```bash
  cd android
  git diff --check
  ```
- **주의사항**: 이 문서 전용 검증 명령은 저장소 루트에서 실행한다(cd android 불필요). 실제 기기 미측정을 자동 테스트로 대체하지 않는다. 최적화·목표는 별도 승인. PR 코멘트에 head·지적→수정→검증→verdict.

## 5. 전체 검증

1. `cd android && python /workspace/tooling/run-health-command.py ./gradlew --offline --no-daemon :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`를 실행해 XML의 실수/failure/error/skip을 집계한다. SDK 불필요 확인은 `./gradlew -PcoreOnly :core:test`. 외부 wrapper는 현재 환경 보조이며 저장소 의존이 아니다. 다른 환경은 README의 JDK/SDK 설정으로 같은 Gradle task를 실행한다. 기준선의 독립 실행은 `cd android && ./gradlew :app:testDebugUnitTest --tests com.ny.my.health.SyncProfilingBaselineTest --rerun-tasks`이며 출력 위치는 android/app/build/reports/sync-profiling-baseline이다.
2. 기존 API35 CI의 계정 없는 Android/SQLite/lifecycle/process-recovery 전체를 유지하고 정확한 revision을 기록한다. Galaxy/TalkBack/P0 미확인은 그대로 둔다. JVM mock 저장을 실제 SQLite 커밋 시간의 증거로 삼지 않는다.
3. 합성 원시 통계와 baseline 보고 표를 대조한다. revision·10관찰/3warmup·nearest-rank 산식·remote 상태·연결 재사용·조건 차이·실패/취소 분리를 확인한다. diff/소스 검색으로 진단에 raw data/URL/ID/Throwable을 전달하는 경로, 추가 통신·재시도, 영속 진단, 새 의존성, 병렬화·캐시, 임의 SLA 부재를 확인한다. 스펙만 제공한 독립 적합성 검증 후 코드/보안/성능 리뷰를 별도 세션에서 수행하고 현재 PR head 결과를 코멘트한다. 수정 시 재검토한다.

## 6. 위험과 롤백

계측 예외가 업무 catch에 섞이면 동작을 바꿀 수 있어 collector 고장만 내부 격리하고 block 예외는 변환하지 않는다. 중첩 시간은 합산하지 않는다. debug 로그는 고정 요약만, 공유 전 allowlist 확인이다. 시스템 시각·SQLite schema·credential 저장을 바꾸지 않아 데이터 이관은 없다. 회귀 시 계측 인자/배선을 제거하거나 해당 구현 커밋을 revert하되 기존 store/원격 파일은 지우지 않는다.

## 복구 기록

| 작업 | 회차/2 | 실패 증거·원인 | 새 근거·수정 가설 | 계획 보정 전→후·유지 기준 | 수정 파일·보존 대상 | 검증 명령·결과 | 실행자·다음 조치 |
|---|---|---|---|---|---|---|---|
| T1 | 1/2 | 조립된 브리프가2절 산문 불변식(중복 시작/종료·진행 중 경과 의미)을 전달하지 못해 실행자가 확인 요청 | 원래 중복 집계 금지 규칙을 선언 주석으로 옮겨 전달하고 종료 구간 합/active 별도 의미를 명시 | 기존 고정 map·단조 시계·실패 격리 유지; 새 동작/의존성 없음 | plan2절 표기 및 T1 두 파일; 기존 코드 보존 | 브리프 재조립 후 해소 실패0; 실행자4개 테스트 통과, root 동일 명령 BUILD SUCCESSFUL·XML4/0실패/0오류/0skip | profiling_t1 부분 지시 후 검증 |

## 환경 준비 기록

| 원인·근거 | 준비 조치 | 조치 사용/1 | 확인 명령·결과 | 확인 사용/1 | 다음 조치 |
|---|---|---|---|---|---|
| Gradle 시작 시 샌드박스에서 wildcard IP를 찾지 못함 | 기존 wrapper/JDK/SDK 유지, 로컬 socket 동작을 허용하는 실행 권한 선택 | 1/1 | `:core:compileKotlin :app:compileDebugKotlin` → BUILD SUCCESSFUL, 29s; 제품 코드 변경 전 확인 | 1/1 | 모든 Gradle 검증은 같은 실행 권한으로 진행 |
