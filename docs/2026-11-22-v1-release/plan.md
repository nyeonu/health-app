---
plan_id: 2026-11-22-v1-release
title: V1 completion delivery plan
kind: single
status: in_progress
author: Codex
created: 2026-11-22
target: android
spec: spec.md
spec_rev: 30ffde9529d993306a6d5c04c93abf7fd0f271e2
tasks:
  - {id: T1, title: SQLite retry 원자성, depends_on: [], status: in_progress, tier: standard}
  - {id: T2, title: 요약 모델, depends_on: [], status: done, tier: standard}
  - {id: T3, title: 계정 고정 silent 인증, depends_on: [], status: in_progress, tier: standard}
  - {id: T4, title: 요약 CSV와 revision, depends_on: [T2], status: done, tier: standard}
  - {id: T5, title: 자동 session 연결, depends_on: [T1], status: pending, tier: standard}
  - {id: T6, title: 배경 Health Connect 조회, depends_on: [T4], status: done, tier: standard}
  - {id: T7, title: WorkManager 실행과 예약, depends_on: [T3, T5, T6], status: pending, tier: standard}
  - {id: T8, title: 자동 설정과 상태, depends_on: [T7], status: pending, tier: standard}
  - {id: T9, title: 화면 연결과 접근성, depends_on: [T8], status: pending, tier: standard}
  - {id: T10, title: 1.0 APK 후보와 안내, depends_on: [T9], status: pending, tier: standard}
---

# V1 completion delivery plan

## 1. 선택한 방식과 이유

Reuse the approved [parent plan](../2026-11-05-v1-completion/plan.md) and [automation plan](../2026-11-05-v1-completion/automation-ui/plan.md), including their contracts and safety details. The owner's latest priority permits a technical cadence revision within the already approved V1 scope; no health or security gate is weakened. Small disjoint tasks expose interface and SQLite defects early, while full native testing remains mandatory on each integration PR head. Existing ADR-012 is Proposed; acceptance requires independent conformance. Before execution, pin `spec_rev` to the actual committed reviewed spec and reconcile this draft with current source.

## 2. 공통 설계

경로 약어: C=`android/core/src/main/kotlin/com/ny/my/health/core`, CT=`android/core/src/test/kotlin/com/ny/my/health/core`, A=`android/app/src/main/java/com/ny/my/health`, AT=`android/app/src/test/java/com/ny/my/health`, AI=`android/app/src/androidTest/java/com/ny/my/health`. 각 작업은 테스트를 포함해 최대 5파일이다. 같은 파일은 의존성 순서대로 수정한다. 기존 승인 부모 `docs/2026-11-05-v1-completion/plan.md`의 상세 정책과 automation-ui 작업 계약을 유지한다. 아래 선언은 승인된 계약이며 현재 store에 구현되었다는 뜻이 아니다.

```kotlin
// AutomaticStoreContracts — 승인된 store 여섯 멤버
suspend fun loadAutomaticBudget(expectedGeneration: DestinationGeneration, scopeKey: ReadScopeKey): AutomaticBudgetRead
suspend fun preflightAutomaticAdmission(expectedGeneration: DestinationGeneration, now: Instant): AutomaticPreflight
suspend fun claimAutomaticAttempt(expectedGeneration: DestinationGeneration, expectedLineageIdOrNull: String?, expectedClaimVersionOrNull: Long?, initialProposalOrNull: InitialAutomaticProposal?, expectedScopeKey: ReadScopeKey, expectedRequestedScopes: Map<DataType, ReadScope>, expectedFrozenDates: List<LocalDate>, attemptId: String, trigger: SyncTrigger, now: Instant): RetryClaim
suspend fun finishAutomaticAttempt(expectedGeneration: DestinationGeneration, lineageId: String, attemptId: String, expectedClaimVersion: Long, outcome: AutomaticFinishOutcome, now: Instant): SyncCommit
suspend fun setAutomaticCAS(expectedGeneration: DestinationGeneration, enabled: Boolean, now: Instant): SyncCommit
suspend fun recoverAutomaticAttempt(expectedGeneration: DestinationGeneration, owner: AutomaticOwner, now: Instant): SyncCommit

// AutomaticSummaryContracts — 승인된 값과 기본값
class AggregateDuration(duration: Duration?, dataOrigins: Set<String>) {
    val duration: Duration? = duration.also { require(it == null || !it.isNegative) }
    val dataOrigins: Set<String> = Collections.unmodifiableSet(LinkedHashSet(dataOrigins))
    val minutes: Double?
        get() = duration?.let { it.seconds / 60.0 + it.nano / 60000000000.0 }

    override fun equals(other: Any?): Boolean =
        other is AggregateDuration && duration == other.duration && dataOrigins == other.dataOrigins
    override fun hashCode(): Int = 31 * (duration?.hashCode() ?: 0) + dataOrigins.hashCode()
}

// Append to both HealthSnapshot and NormalizedDay:
// val sleepSummary: AggregateDuration? = null, val exerciseSummary: AggregateDuration? = null

// BoundDriveAuthorizationContracts — 현재 source의 계정과 ticket
class BoundDriveAuthorization {
    data class AccountSelection(val name: String, val type: String)
    data class Ticket(val id: Long, val generation: DestinationGeneration, val account: AccountSelection)
    suspend fun <T> withVerifiedTransport(isCurrent: () -> Boolean, block: suspend (DriveTransport) -> T): T
}
```

- `AutomaticAdmissionOrder` — 공용 gate 안에서 DB preflight 성공 → fresh HC feature/grant metadata로 계획 → atomic claim → fresh silent bound-account proof → health records와 원격 IO 순이다. off, due 이전, stale, 미해결 recovery는 외부 호출 0이다. 조회 직전 grant를 재검사한다.
- `AutomaticSessionRoutingContracts` — worker는 별도 automatic reader/session을 생성하되 수동과 canonical DB path gate를 공유하고, access와 engine에 같은 reader를 쓴다. automatic scope와 manual coverage를 혼합하지 않는다.
- `BackgroundAuthorizationContracts` — 기존 ticket과 동일 token의 principal proof를 사용한다. resolution, missing token/scope, stale callback은 차단하고 interactive UI는 실행하지 않는다.
- `AutomaticHealthContracts` — feature/background/category grant별로 판단하며 steps/sleep/exercise는 aggregate, weight는 당일 실제 최신 값이다. production workout HR overlap proof 미입증이므로 HR만 BLOCKED이고 다른 category는 독립 진행한다.
- `AutomaticWorkContracts` — 24시간 unique periodic, connected/battery-not-low, 최대 7일 catch-up과 최근 2일 refresh. 시각과 완료를 보장하지 않는다.
- `AutomaticStatusContracts` — 실제 `SyncUiState`는 `SyncViewModel.kt`에, 동작 묶음은 `ManualDriveUi.kt`에 있다. generation 무효화, 취소, gap 및 blocker를 사실대로 투영한다.

## 3. 추적성

테스트 근거는 성공 기준·경계·오류·순서·회귀이며 검증 층은 JVM, 실제 SQLite/WorkManager instrumentation 또는 검토다.

| Spec criterion | Verification (source/layer) | Tasks |
|---|---|---|
| 수동 Drive와 로컬 SAF의 기존 동의·권한·목적지·복구 동작을 유지하고 background 작업은 SAF를 사용하지 않는다. | Existing manual and SAF regressions plus automatic SAF rejection (regression/error, instrumentation) | T5, T7, full gate |
| 자동 Drive는 명시적 opt-in 전까지 꺼져 있고 background 기능 지원과 background 및 각 category 권한을 요구한다. | Default off and revoked/missing feature and grants cause no reads (boundary/error, unit and instrumentation) | T6, T7, T8 |
| 지원 요약은 Health Connect Aggregate를 사용하고 체중은 최신 실제 기록이며 결측은 결측으로 둔다. 미입증 운동 심박은 BLOCKED로 표시하고 다른 허용 category는 독립 진행한다. | Null/zero/provenance and latest weight; unproven HR blocked, other categories progress (boundary/error, unit) | T2, T4, T6 |
| 자동 작업은 connected 및 battery-not-low 제약의 24시간 unique WorkManager 일정과 최근 2일 refresh를 포함한 최대 7일 catch-up을 사용한다. | Unique constrained requests and seven-date/two-refresh boundary (boundary/ordering, instrumentation and unit) | T7 |
| 영속 retry lineage는 최초 실행 외 최대 3회 재시도하며 새 작업이나 원본 revision은 미완료 budget을 초기화하지 않는다. | Claims race/reopen, due and exhaustion, changed revision retains count (boundary/error/ordering, real SQLite) | T1, T5 |
| 수동 실행과 worker는 동일 canonical gate를 사용하며 worker는 health record 조회 전에 선택 계정의 새 silent 인증과 proof를 수행하고 UI를 띄우지 않는다. | Concurrent manual/worker gate; account mismatch, resolution, stale callback and proof failure prevent reads (ordering/error, unit and instrumentation) | T3, T5, T7 |
| 영속 metadata는 재시작 후에도 확정 결과와 불확실한 create 및 취소 상태를 보존하며 blocked·stale·손상·미확인 상태를 완료로 표시하거나 자동 초기화하지 않는다. | Reopen, rollback, cancellation and uncertainty preserve prior outcomes and fail closed (error/ordering/regression, real SQLite) | T1, T5 |
| 설정은 명시적 opt-in·끄기·reset과 함께 queued·blocked·coverage gap을 표시하고 끄기나 목적지 변경 시 이전 작업을 안전하게 무효화한다. | UI semantics and late callbacks preserve generation and show true state (success/error/ordering, instrumentation) | T8, T9 |
| 각 통합 PR 병합과 최종 1.0 전에 현재 head의 API 35·36 전체 Native와 기존 격리 host, JVM, lint 및 APK 빌드가 통과하며 테스트 삭제·skip·완화는 없다. | Full runner inventories and raw results, current-head CI, build output (regression/integration) | Gate |
| 독립 적합성 검증과 새 세션 PR 리뷰 후 병합하며 수행하지 않은 실기기·계정·P0·접근성 수용은 pending으로 남긴다. | Independent spec-only conformance, fresh full diff review and evidence audit (review; not reducible to unit test) | Gate |
| APK 후보는 versionName 1.0.0 및 1보다 큰 versionCode를 사용하고 간결한 설치 안내와 미완료 수용 항목을 제공한다. | Inspect APK version and setup/handoff against evidence (review/build) | T10, Gate |

## 4. 작업

기준 head `a70fee6`: T2 모델, T3 schema/migration, T20 fixture 및 T21 진단은 존재한다. 과거 T2 준비 1/2/3·복구 4–7, T3 준비 1/2·복구 3–11, T20 복구 1/2를 유지하며 재계획으로 초기화하지 않는다. `7f07823` 객체는 이 환경에 없고 root가 확인한 알려진 GitHub ref도 404다. 기존 이력을 보존하고 필요한 기능을 재구성한다. a70 baseline full CI는 별도 진행 중이며 결과는 아직 미확인이다. 이전 migration 8건 양 API 및 SAF-store 2건 양 API 통과는 현 head 전체 gate가 아니다. 이전 API36 전체 143건 중 142건만 통과했고 나머지 future fixture는 a70에서 교정됐으나 host 전체는 미실행이다.

### T1: SQLite retry 원자성

- **depends_on**: []; **tier**: standard; **status**: pending.
- **변경 파일**

  | 파일 | 구분 |
  |---|---|
  | `A/SqliteSyncStateStore.kt` | 수정 |
  | `A/SqliteSyncRetryBudget.kt` | 신규 |
  | `AI/SyncRetryBudgetStoreInstrumentedTest.kt` | 신규 |
  | `C/SyncRetryBudget.kt` | 수정 |
  | `CT/SyncRetryBudgetTest.kt` | 수정 |
- **필수 읽기**: `docs/2026-11-05-v1-completion/automation-ui/plan.md`의 T4 전체와 `docs/2026-11-05-v1-completion/plan.md` 2절 `AutomaticRetryContracts` 전체를 읽고 적용한다.
- **인터페이스**: 2절 `AutomaticStoreContracts` 참조. 승인된 이전 automation T4의 전체 CAS, private owner/link 도출, recovery, capacity, rollback 계약을 유지한다.
- **결정 로직**: 동일 generation/lineage/version/scope에서 claim과 attempt를 한 transaction에 기록하고 불일치·손상은 fail closed한다. initial 외 최대 3회와 durable due를 보존한다.
- **작성할 테스트**:
  - "실제 SQLite race, replay, rollback, reopen, due 경계, exhausted, orphan, 변경 revision과 uncertainty 보존."
- **완료 기준 및 검증**: `./gradlew :app:assembleDebugAndroidTest`; 기존 `retry-store` 진단을 양 API에서 해당 class 대상으로 실행한다. 후속 통합 전 결과 확인한다.

### T2: 요약 모델

- **depends_on**: []; **tier**: standard; **status**: done.
- **변경 파일**

  | 파일 | 구분 |
  |---|---|
  | `C/Model.kt` | 수정 |
  | `C/Normalization.kt` | 수정 |
  | `CT/NormalizationTest.kt` | 수정 |
- **인터페이스**: 2절 `AutomaticSummaryContracts` 참조. 기존 manual constructor 기본값과 origin 방어 복사를 유지한다.
- **작성할 테스트**:
  - "null, zero, fractional, 음수 거절, 최대 duration, provenance 및 기존 latest weight. 관련 JVM 테스트와 `./gradlew -PcoreOnly :core:test` 통과가 완료 기준이다."

### T3: 계정 고정 silent 인증

- **depends_on**: []; **tier**: standard; **status**: pending.
- **변경 파일**

  | 파일 | 구분 |
  |---|---|
  | `A/BackgroundDriveAuthorization.kt` | 신규 |
  | `AT/BackgroundDriveAuthorizationTest.kt` | 신규 |
  | `AI/BackgroundAuthorizationInstrumentedTest.kt` | 신규 |
- **필수 읽기**: `docs/2026-11-05-v1-completion/automation-ui/plan.md` T10 전체 및 기존 `BoundDriveAuthorization.kt`를 읽고 기존 acceptResult/동일 token proof를 재사용한다.
- **인터페이스**: 2절 `BoundDriveAuthorizationContracts`, `BackgroundAuthorizationContracts` 참조. 실제 SDK request와 기존 `acceptResult`를 먼저 대조한다.
- **작성할 테스트**:
  - "선택 계정, fresh token/scope 및 같은 token principal proof; resolution, null, mismatch, 취소와 늦은 callback은 read/UI 없이 차단. 관련 JVM 및 SDK adapter instrumentation 양 API 통과가 완료 기준이다. 실제 계정 성공 증거로 해석하지 않는다."

### T4: 요약 CSV와 revision

- **depends_on**: [T2]; **tier**: standard; **status**: done.
- **변경 파일**

  | 파일 | 구분 |
  |---|---|
  | `C/VersionedCsvExporter.kt` | 수정 |
  | `C/SyncRevision.kt` | 수정 |
  | `CT/CsvExporterTest.kt` | 수정 |
  | `CT/SyncRevisionTest.kt` | 수정 |
- **인터페이스**: 2절 `AutomaticSummaryContracts` 참조. summary-only daily와 출처 정렬 semantic revision을 추가하고 manual bytes/hash golden을 유지한다.
- **작성할 테스트**:
  - "null이면 blank, 실제 zero, summary-only, scope 불일치, origin 변화와 기존 manual golden. 관련 JVM 테스트 및 `./gradlew -PcoreOnly :core:test` 통과가 완료 기준이다."

### T5: 자동 session 연결

- **depends_on**: [T1]; **tier**: standard; **status**: pending.
- **변경 파일**

  | 파일 | 구분 |
  |---|---|
  | `A/SyncExecutionSession.kt` | 수정 |
  | `C/DestinationSync.kt` | 수정 |
  | `AI/SyncExecutionSessionInstrumentedTest.kt` | 수정 |
  | `CT/DestinationSyncTest.kt` | 수정 |
- **인터페이스**: 2절 `AutomaticStoreContracts`, `AutomaticAdmissionOrder`, `AutomaticSessionRoutingContracts` 참조. 기존 manual `runPrepared` 유지; 승인된 T5 계약에 따라 claim/finish와 source exception 경계를 연결한다.
- **작성할 테스트**:
  - "manual/worker 동시 gate, cancellation, revision 변경 시 retry 불변, preflight 차단 시 외부 호출 0과 uncertain create 보존. 관련 core 테스트 및 `retry-session` 실제 SQLite 진단 양 API 통과 후 T7에 인계한다."

### T6: 배경 Health Connect 조회

- **depends_on**: [T4]; **tier**: standard; **status**: done.
- **변경 파일**

  | 파일 | 구분 |
  |---|---|
  | `A/AutomaticHealthReader.kt` | 신규 |
  | `A/HealthConnectReader.kt` | 수정 |
  | `android/app/src/main/AndroidManifest.xml` | 수정 |
  | `AT/AutomaticHealthReaderTest.kt` | 신규 |
- **인터페이스**: 2절 `AutomaticHealthContracts`, `AutomaticSummaryContracts` 참조. 실제 SDK 1.1.0 API를 확인하고 background permission만 추가한다.
- **작성할 테스트**:
  - "feature 및 각 grant 부재, null aggregate, 최신 실제 weight, production HR BLOCKED와 다른 category 독립 진행. 관련 JVM 테스트 통과가 완료 기준이다. 실 provider proof가 생기기 전 가상의 proof=true HR 조회 알고리즘은 개발하지 않는다."

### T7: WorkManager 실행과 예약

- **depends_on**: [T3, T5, T6]; **tier**: standard; **status**: pending.
- **변경 파일**

  | 파일 | 구분 |
  |---|---|
  | `A/SyncWorkScheduler.kt` | 신규 |
  | `A/SyncWorker.kt` | 신규 |
  | `AI/SyncWorkSchedulerInstrumentedTest.kt` | 신규 |
  | `A/HealthApplication.kt` | 수정 |
- **인터페이스**: 2절 `AutomaticWorkContracts`, `AutomaticAdmissionOrder`, `AutomaticSessionRoutingContracts` 참조. 기존 starter와 WorkManager API를 먼저 확인한다. 전역 test manager 초기화 없이 `TestListenableWorkerBuilder` 및 실제 production WorkManager 경계를 사용한다. 실제 helper 초기화가 필요하다는 근거가 생긴 경우에만 별도 registry 작업을 계획한다.
- **작성할 테스트**:
  - "unique 24h 요청과 제약, 일곱 날짜 및 최근 두 날짜, off/due/stale의 외부 호출 0, 재시작·retry budget·manual gate. 양 API의 해당 instrumentation 및 관련 JVM 통과가 완료 기준이다."

### T8: 자동 설정과 상태

- **depends_on**: [T7]; **tier**: standard; **status**: pending.
- **변경 파일**

  | 파일 | 구분 |
  |---|---|
  | `A/SyncViewModel.kt` | 수정 |
  | `A/ManualDriveUi.kt` | 수정 |
  | `AT/AutomaticSettingsTest.kt` | 신규 |
- **인터페이스**: 2절 `AutomaticStatusContracts` 참조. 기존 `SyncUiState`와 actions를 확장하고 별도 DTO를 추측하지 않는다.
- **작성할 테스트**:
  - "명시 opt-in/off, generation 무효화, 취소/늦은 callback, reset과 queued/blocked/gap 투영. 관련 JVM 테스트 통과가 완료 기준이다."

### T9: 화면 연결과 접근성

- **depends_on**: [T8]; **tier**: standard; **status**: pending.
- **변경 파일**

  | 파일 | 구분 |
  |---|---|
  | `A/HealthHomeScreen.kt` | 수정 |
  | `A/MainActivity.kt` | 수정 |
  | `AI/HealthHomeScreenInstrumentedTest.kt` | 수정 |
  | `AI/ManualDriveFlowInstrumentedTest.kt` | 수정 |
- **인터페이스**: 2절 `AutomaticStatusContracts` 참조. 기존 manual 및 SAF 흐름을 유지한다.
- **작성할 테스트**:
  - "설정/동의/실행/reset 접근성 semantics, 밝은/어두운 화면과 큰 글자, restore 부수효과 없음, gap과 blocker 표시. 해당 instrumentation 양 API 통과가 완료 기준이다. 실제 TalkBack은 별도 pending이다."

### T10: 1.0 APK 후보와 안내

- **depends_on**: [T9]; **tier**: standard; **status**: pending.
- **변경 파일**

  | 파일 | 구분 |
  |---|---|
  | `android/app/build.gradle.kts` | 수정 |
  | `README.md` | 수정 |
- **테스트 및 완료 기준**: versionName `1.0.0`, versionCode `2`로 변경하고 APK metadata를 확인한다. 최소 사용 안내 및 미완료 실기기/계정 수용을 기재한다. Play 공개 배포는 범위 밖이다.

T1/T2/T3는 파일이 겹치지 않아 병렬 가능하다. 각 작업의 실제 인터페이스와 관련 테스트를 확인한 뒤 종속 작업을 시작한다. 공유 emulator/build는 직렬화한다. 공통 모델 gap이 실제로 발견되면 근거와 제한된 수정 범위를 기록해 별도 계획 보정하며 선제적인 빈 작업은 만들지 않는다.

## 5. 전체 검증

For each integration head and final head: from `android/`, run `./gradlew -PcoreOnly :core:test` and `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest`. On both API 35/36 run repository-root `GITHUB_EVENT_NAME=workflow_dispatch TEST_SCOPE=full bash android/ci/run-emulator-validation.sh`, including the existing isolated host registry, verify raw counts, errors, skips, duplicates, process recovery and head identity. Preserve all existing tests and expected assertions. Review dependency/manifest/privacy evidence required by the approved parent plan. If environment unavailable record pending or run corresponding current-head CI, never claim pass without results.

Fresh-context separate reviewer examines final head and full relevant diff; Korean PR comments record findings, evidence, limitations and reviewed head; blocking fixes receive fresh review. Independent conformance receives approved spec and implementation, not plan or earlier review conclusions. Preserve original P0 and actual Galaxy, Doze/reboot, account, provider, TalkBack, private-backup acceptance as pending until evidence exists. Development merge is not physical release acceptance.

## 6. 위험과 롤백

Do not replace unknown/corrupt state with empty state or discard uncertain creates. Invalidate generation and cancel queued work before disabling or reverting; preserve private metadata and acknowledge remote writes cannot be undone by cancellation. Missing SDK proof or inaccessible testing blocks affected completion, not a reason to relax criteria.

## 복구 기록

See retained original counts in section 4 and approved parent history; append new evidence and corrections there without resetting counts.

## 환경 준비 기록

No execution or emulator runs were performed while drafting this plan.

### 1.0 준비 검토 교정1 및 자동 승인 적용

2026-11-22 fresh Terra 준비 검토에서 T1 상세 승인 계약 전달 누락과 T3 파일 신규 표기 오기를 확인했다. T1 필수 원문 읽기 경로와 T3 신규/기존 인증 재사용을 명시하고 실제 없는 T7 두 파일도 신규로 정정했다. 검토자는 해당 교정 후 초기 T1/T2/T3 병렬 착수를 허용했다. 산문 Shared design 경고는 원문 계약이 전달됨을 확인했고 미해결 참조는 0이다. 기존 소유자 자동 승인 위임 및 최신 1.0 우선 지시에 따라 승인하며 과거 복구 회차를 보존한다.

### T2 환경 및 완료 근거

기본 sandbox의 Gradle daemon IP 판별 실패 뒤 제품 변경 없이 필요한 network 권한으로 root가 같은 coreOnly 명령을 실행했다. 88 tests, failures/errors/skips 0, 기존 86 유지와 신규 2개를 XML/diff 대조했다. 기능 교정은 없고 환경 권한 확인만 수행했다. 실제 provider 수용을 뜻하지 않는다. T2 완료 후 disjoint T4를 시작한다.

### T1 계획 보정1 — 인증 실패의 원자 finalization 분류

기존 부모는 source 조회 전 인증 실패 시 frozen cohort의 ACCOUNT_AUTHORIZATION_BLOCKED 행을 attempt 종료와 한 commit에 요구하지만 기존 AutomaticFinishOutcome 세 값으로는 비어 있는 journal에서 그 이유를 도출할 수 없다. 기존 정책을 그대로 전달하기 위해 enum에 ACCOUNT_AUTHORIZATION_BLOCKED만 추가하고 pure 모델에서는 BLOCKED로 분류한다. 여섯 store 서명은 유지하며 store가 명시 인증 실패에만 그 blocker를 원자 기록한다. T1 소유에 기존 모델 및 해당 테스트를 추가해 총5파일로 제한한다. 새로운 retry 권한·실패 추정·schema는 추가하지 않는다. T1 보정1로 집계하고 모델/SQLite 테스트로 확인한다.

### T4 완료 근거

root가 안정된 core 소스에서 coreOnly 전체를 직접 실행해 90 tests, failures/errors/skips/중복 0 및 기존 manual CSV/hash golden 보존을 XML/diff로 확인했다. 요약 전용 daily/null/실제0/fractional과 summary scope·정렬된 origins 변경 revision 테스트 2개를 추가했다. 로그 /workspace/tooling/v1-t4-core-test.log. T6 종속 작업을 시작한다.

### T6 교정1 — 권한 확인 위치와 체중 동률 선택

root 정적 검토에서 latestWeight가 time만 비교해 승인된 time/source/id/kg 정렬 동률 규칙을 누락하고, 중간 metric/page 호출 전 fresh 권한을 확인하지 않아 철회 후 다른 조회가 이어질 수 있음을 확인했다. T6 기존4파일 안에서 매 metric/page 조회 직전 feature/background/해당 category grant를 확인하고 grant 상실은 SOURCE_ACCESS_REQUIRED로 안전 중단한다. 체중은 당일 범위와 기존 tuple 정렬로 최신 실제 값을 고른다. known missing page100/1000/repeated-token·SecurityException·feature/sdk 부재 테스트도 같은 승인 기준으로 보완한다. 새로운 metric/권한/HR proof를 추가하지 않는다.

### T1 교정2 — cross-module Kotlin nullable 속성

첫 isolated compile는 store 270/273/285행에서 core 공개 nullable start/revision/pendingTriggers의 smart cast와 Set<Any?> 추론으로 실패했다(로그 /workspace/tooling/v1-t1-isolated-verification.log). 해당 속성을 stable local val로 snapshot하여 같은 분기/검증/값을 유지한다. T1 기존 store 파일만 수정하고 동일 core:test + assembleDebugAndroidTest 1회로 확인한다. 기능 정책·단언은 변경하지 않는다. 이전 보정1을 보존하고 2로 누적한다.

### 초기 기능 통합 검증

T1 교정2와 T3/T6를 포함한 12개 변경 소스를 격리 검증 worktree에 그대로 복사하고 root가 :core:test :app:testDebugUnitTest :app:assembleDebugAndroidTest를 실행했다. BUILD SUCCESSFUL 2m8s, XML core90/app106, 실패·오류·skip·중복0이고 main의 12파일과 검증 snapshot bytes가 모두 같음을 확인했다. 기존 app97 + 인증1 + 조회8이다. T6 완료이며 T1의 실제SQLite 및 T3 SDK adapter 양API 검증은 아직 pending이므로 두 작업은 in_progress다. 실제기기/계정 증거는 추가되지 않았다.
