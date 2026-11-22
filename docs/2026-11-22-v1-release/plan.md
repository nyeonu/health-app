---
plan_id: 2026-11-22-v1-release
title: V1 completion delivery plan
kind: single
status: done
author: Codex
created: 2026-11-22
target: android
spec: spec.md
spec_rev: 30ffde9529d993306a6d5c04c93abf7fd0f271e2
tasks:
  - {id: T1, title: SQLite retry 원자성, depends_on: [], status: done, tier: standard}
  - {id: T2, title: 요약 모델, depends_on: [], status: done, tier: standard}
  - {id: T3, title: 계정 고정 silent 인증, depends_on: [], status: done, tier: standard}
  - {id: T4, title: 요약 CSV와 revision, depends_on: [T2], status: done, tier: standard}
  - {id: T5, title: 자동 session 연결, depends_on: [T1, T11], status: done, tier: standard}
  - {id: T6, title: 배경 Health Connect 조회, depends_on: [T4], status: done, tier: standard}
  - {id: T7, title: WorkManager 실행과 예약, depends_on: [T3, T6], status: done, tier: standard}
  - {id: T8, title: 자동 설정과 상태, depends_on: [T1], status: done, tier: standard}
  - {id: T13, title: 자동 활성 시 수동 Drive 공존, depends_on: [T1], status: done, tier: standard}
  - {id: T9, title: 화면 연결과 접근성, depends_on: [T1], status: done, tier: standard}
  - {id: T11, title: 자동 engine 경계, depends_on: [], status: done, tier: standard}
  - {id: T12, title: 실제 동작에 맞는 개인정보 안내, depends_on: [], status: done, tier: standard}
  - {id: T14, title: 기존 runner의 1.0 고정 진단, depends_on: [], status: done, tier: standard}
  - {id: T15, title: 수동 category 변경 실제 SQLite 회귀, depends_on: [T1], status: done, tier: standard}
  - {id: T10, title: 1.0 APK 후보와 안내, depends_on: [T12], status: done, tier: standard}
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
```kotlin
// AutomaticSchedulerApi — SDK 2.11.0 public API로 확인한 T7→T8 연결
class SyncWorkScheduler(context: Context) {
    fun schedule(generation: DestinationGeneration)
    fun enqueueCatchUp(generation: DestinationGeneration)
    fun cancel(generation: DestinationGeneration)
    fun workInfos(generation: DestinationGeneration): Flow<List<WorkInfo>>
}
```

- enqueue/cancel은 비동기 Operation 호출이며 Unit 반환을 영속 enqueue/완료/실제 실행 증거로 쓰지 않는다. workInfos는 같은 generation tag로 periodic/catch-up을 함께 관측한다. Data에는 generation UUID만 둔다. 자동 off 복원은 scheduler를 호출하지 않는다. 끄기/변경/reset은 durable CAS 성공으로 이전 generation을 무효화한 뒤 cancel한다.

- `AutomaticStatusContracts` — 실제 `SyncUiState`는 `SyncViewModel.kt`에, 동작 묶음은 `ManualDriveUi.kt`에 있다. generation 무효화, 취소, gap 및 blocker를 사실대로 투영한다.

## 3. 추적성

테스트 근거는 성공 기준·경계·오류·순서·회귀이며 검증 층은 JVM, 실제 SQLite/WorkManager instrumentation 또는 검토다.

| Spec criterion | 실제 테스트 또는 실행 gate |
|---|---|
| 수동 Drive와 로컬 SAF의 기존 동의·권한·목적지·복구 동작을 유지하고 background 작업은 SAF를 사용하지 않는다. | `ManualDriveFlowInstrumentedTest.automaticEnabledManualFlowRequiresForegroundConsentAndFreshProofWithoutCreatingRetryLineage`; `ManualDriveFlowInstrumentedTest.sameAccountCategoryAdditionAndRemovalPreserveConfirmedExportAndRequireFreshForegroundProof`; `SafManualFlowInstrumentedTest.safActualActivitySelectionConfiguresWithoutExportingAndExplicitContinueConfirmsOnlySelectedLocalTarget`; `SyncWorkSchedulerInstrumentedTest.disabledAndStaleWorkersDoNotAccessHealthOrPrepareDestination` |
| 자동 Drive는 명시적 opt-in 전까지 꺼져 있고 background 기능 지원과 background 및 각 category 권한을 요구한다. | `AutomaticSettingsTest.Explicit opt in schedules and enqueues after verified commit`; `AutomaticHealthReaderTest.Feature and each grant missing block their scopes while HR remains blocked`; `BackgroundPermissionFlowInstrumentedTest.unavailableOrUnknownFeatureNeverLaunchesAndExplicitResultClearsBusy` |
| 지원 요약은 Health Connect Aggregate를 사용하고 체중은 최신 실제 기록이며 결측은 결측으로 둔다. 미입증 운동 심박은 BLOCKED로 표시하고 다른 허용 category는 독립 진행한다. | `AutomaticHealthReaderTest.Null aggregates retain missing values and per metric origins while other categories proceed`; `AutomaticHealthReaderTest.Bounded weight read returns the latest actual measurement`; `SyncExecutionSessionInstrumentedTest.automaticUsesPositiveGrantedScopesAndBlocksWhenNoScopesAreAvailable`; `CsvExporterTest.Summary only daily exports blank missing values and observed zero` |
| 자동 작업은 connected 및 battery-not-low 제약의 24시간 unique WorkManager 일정과 최근 2일 refresh를 포함한 최대 7일 catch-up을 사용한다. | `SyncWorkSchedulerInstrumentedTest.uniqueDailyWorkUsesNetworkAndBatteryConstraintsAndCatchUpIsUnique`; `SyncPlannerTest.Recent two days reserve slots before oldest eligible gaps and retain blocked pending days` |
| 영속 retry lineage는 최초 실행 외 최대 3회 재시도하며 새 작업이나 원본 revision은 미완료 budget을 초기화하지 않는다. | `SyncRetryBudgetStoreInstrumentedTest.sqliteRetryClaimsSurviveReopenAndRacingAttemptsCannotExceedThreeRetriesOrEraseOldMetadata`; `SyncExecutionSessionInstrumentedTest.automaticRetryKeepsLineageAndConsumesBudgetWhenSourceRevisionChanges`; `SyncRetryBudgetStoreInstrumentedTest.removedCategoryCannotClaimCarriedRetryAndReselectionRetainsBudgetAndDue` |
| 수동 실행과 worker는 동일 canonical gate를 사용하며 worker는 health record 조회 전에 선택 계정의 새 silent 인증과 proof를 수행하고 UI를 띄우지 않는다. | `SyncExecutionSessionInstrumentedTest.automaticAndManualSessionsShareOneGateAndOnlyOwnerPrepares`; `BackgroundDriveAuthorizationTest.Background authorization pins the selected account and proves the same fresh token before any health read without launching resolution`; `BackgroundDriveAuthorizationTest.Suspending durable checks block stale callbacks and stale proof before reads and propagate cancellation` |
| 영속 metadata는 재시작 후에도 확정 결과와 불확실한 create 및 취소 상태를 보존하며 blocked·stale·손상·미확인 상태를 완료로 표시하거나 자동 초기화하지 않는다. | `SyncRetryBudgetStoreInstrumentedTest.failedSourceReadPreservesOldRevisionAndPriorWithoutRecordingANewObservation`; `SyncRetryBudgetStoreInstrumentedTest.driveOptOutCarriesUnknownAndStaleJournalsConfirmedMetadataAndRejectsStaleCallbacks`; `SyncExecutionSessionInstrumentedTest.automaticCancellationPreservesUncertainCreateAndConsumedBudgetAcrossReopen`; `SyncRetryBudgetStoreInstrumentedTest.authorizationAndExhaustionPreserveRemoteStaleAndPriorConfirmationThroughReopen` |
| 설정은 명시적 opt-in·끄기·reset과 함께 queued·blocked·coverage gap을 표시하고 끄기나 목적지 변경 시 이전 작업을 안전하게 무효화한다. | `AutomaticSettingsTest.Opt out commits generation invalidation before cancelling old work`; `SyncRetryBudgetStoreInstrumentedTest.sameDriveCategoryChangesPreserveHistoricalTargetsUnknownsPriorAndAccountIsolation`; `AutomaticSettingsTest.Work observation failures and unavailable store produce unknown state`; `AutomaticSettingsInstrumentedTest.cancellationBeforeCommitLeavesAutomaticOffAfterSqliteReopenAndDoesNotEnqueue`; `HealthHomeScreenInstrumentedTest.automaticControlsRequireExplicitConsentAndRestoreDoesNotStartActions` |
| 각 통합 PR 병합과 최종 1.0 전에 현재 head의 API 35·36 전체 Native와 기존 격리 host, JVM, lint 및 APK 빌드가 통과하며 테스트 삭제·skip·완화는 없다. | [양API 전체186·host 및 JVM210/lint/APK 원본](execution-report.md) |
| 독립 적합성 검증과 새 세션 PR 리뷰 후 병합하며 수행하지 않은 실기기·계정·P0·접근성 수용은 pending으로 남긴다. | [격리 적합성 PASS](spec-conformance.md), [독립 PR 검토 APPROVE](pr-review.md), [최종 한국어 댓글](https://github.com/nyeonu/health-app/pull/20#issuecomment-7073833100) |
| APK 후보는 versionName 1.0.0 및 1보다 큰 versionCode를 사용하고 간결한 설치 안내와 미완료 수용 항목을 제공한다. | versionName1.0.0/code2 APK 메타데이터와 [설치 안내](../V1/1.0-installation.md) |

파일별 링크와 단언 연결은 [추적성 상세](traceability.md)에 보존한다.

## 4. 작업

기준 head `a70fee6`: T2 모델, T3 schema/migration, T20 fixture 및 T21 진단은 존재한다. 과거 T2 준비 1/2/3·복구 4–7, T3 준비 1/2·복구 3–11, T20 복구 1/2를 유지하며 재계획으로 초기화하지 않는다. `7f07823` 객체는 이 환경에 없고 root가 확인한 알려진 GitHub ref도 404다. 기존 이력을 보존하고 필요한 기능을 재구성한다. a70 baseline full CI는 별도 진행 중이며 결과는 아직 미확인이다. 이전 migration 8건 양 API 및 SAF-store 2건 양 API 통과는 현 head 전체 gate가 아니다. 이전 API36 전체 143건 중 142건만 통과했고 나머지 future fixture는 a70에서 교정됐으나 host 전체는 미실행이다.

### T1: SQLite retry 원자성

- **depends_on**: []; **tier**: standard; **status**: done.
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

- **depends_on**: []; **tier**: standard; **status**: done.
- **변경 파일**

  | 파일 | 구분 |
  |---|---|
  | `A/BackgroundDriveAuthorization.kt` | 신규 |
  | `AT/BackgroundDriveAuthorizationTest.kt` | 신규 |
  | `AI/BackgroundAuthorizationInstrumentedTest.kt` | 신규 |
- **필수 읽기**: `docs/2026-11-05-v1-completion/automation-ui/plan.md` T10 전체 및 기존 `BoundDriveAuthorization.kt`를 읽고 기존 acceptResult/동일 token proof를 재사용한다.
- **인터페이스**: 2절 `BoundDriveAuthorizationContracts`, `BackgroundAuthorizationContracts` 참조. 실제 SDK request와 기존 `acceptResult`를 먼저 대조한다.
- **작성할 테스트**:
  - "선택 계정, fresh token/scope 및 같은 token principal proof; resolution, null, mismatch, 취소와 늦은 callback은 read/UI 없이 차단. 관련 JVM 테스트와 실제 SDK 서명·계측 소스 컴파일이 작업별 완료 기준이며 SDK adapter의 양 API runtime은 통합 PR 현재 head 전체 Native에서 필수 수행한다. 실제 계정 성공 증거로 해석하지 않는다."

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

- **depends_on**: [T1, T11]; **tier**: standard; **status**: done.
- **변경 파일**

  | 파일 | 구분 |
  |---|---|
  | `A/SyncExecutionSession.kt` | 수정 |
  | `AI/SyncExecutionSessionInstrumentedTest.kt` | 수정 |
- **인터페이스**: 2절 `AutomaticStoreContracts`, `AutomaticAdmissionOrder`, `AutomaticSessionRoutingContracts` 참조. 기존 manual `runPrepared` 유지; 승인된 T5 계약에 따라 claim/finish와 source exception 경계를 연결한다.
- **작성할 테스트**:
  - "manual/worker 동시 gate, cancellation, revision 변경 시 retry 불변, preflight 차단 시 외부 호출 0과 uncertain create 보존. 관련 core 테스트 및 `retry-session` 실제 SQLite 진단 양 API 통과 후 T7에 인계한다."


- **실제 SQLite preflight 통합 테스트**: 복수 scope ACTIVE waiting의 fractional Instant 최대 future due·owner recovery 우선·다른 scope terminal 비전역 차단을 검증하고 deferred/stale/off/unresolved recovery에서 auth·HC metadata·records·remote 호출0을 확인한다.

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

- **depends_on**: [T3, T6]; T5 실제 Native 통과는 검증·통합 선행 조건; **tier**: standard; **status**: done.
- **변경 파일**

  | 파일 | 구분 |
  |---|---|
  | `A/SyncWorkScheduler.kt` | 신규 |
  | `A/SyncWorker.kt` | 신규 |
  | `AI/SyncWorkSchedulerInstrumentedTest.kt` | 신규 |
  | `A/HealthApplication.kt` | 수정 |
- **인터페이스**: 2절 `AutomaticWorkContracts`, `AutomaticAdmissionOrder`, `AutomaticSessionRoutingContracts`, `AutomaticSchedulerApi` 참조. 기존 starter와 WorkManager API를 먼저 확인한다. 전역 test manager 초기화 없이 `TestListenableWorkerBuilder` 및 실제 production WorkManager 경계를 사용한다. 실제 helper 초기화가 필요하다는 근거가 생긴 경우에만 별도 registry 작업을 계획한다.
- **작성할 테스트**:
  - "unique 24h 요청과 제약, 일곱 날짜 및 최근 두 날짜, off/due/stale의 외부 호출 0, 재시작·retry budget·manual gate. 양 API의 해당 instrumentation 및 관련 JVM 통과가 완료 기준이다."

### T8: 자동 설정과 상태

- **depends_on**: [T1]; T7 검증·통합은 완료 선행 조건; **tier**: standard; **status**: done.
- **변경 파일**

  | 파일 | 구분 |
  |---|---|
  | `A/SyncViewModel.kt` | 수정 |
  | `A/ManualDriveUi.kt` | 수정 |
  | `AT/AutomaticSettingsTest.kt` | 신규 |
  | `AI/AutomaticSettingsInstrumentedTest.kt` | 신규 |
- **인터페이스**: 2절 `AutomaticStatusContracts`, `AutomaticSchedulerApi` 참조. 기존 `SyncUiState`와 actions를 확장하고 별도 DTO를 추측하지 않는다.
- **작성할 테스트**:
  - "명시 opt-in/off, generation 무효화, 취소/늦은 callback, reset과 queued/blocked/gap 투영. 관련 JVM 테스트 통과가 완료 기준이다."

### T9: 화면 연결과 접근성

- **depends_on**: [T1]; T8 검증 및 T13 같은 native 파일 인계는 완료 선행 조건; **tier**: standard; **status**: done.
- **변경 파일**

  | 파일 | 구분 |
  |---|---|
  | `A/HealthHomeScreen.kt` | 수정 |
  | `A/MainActivity.kt` | 수정 |
  | `AI/HealthHomeScreenInstrumentedTest.kt` | 수정 |
  | `AI/BackgroundPermissionFlowInstrumentedTest.kt` | 신규 |
- **인터페이스**: 2절 `AutomaticStatusContracts` 참조. 기존 manual 및 SAF 흐름을 유지한다.
- **작성할 테스트**:
  - "설정/동의/실행/reset 접근성 semantics, 밝은/어두운 화면과 큰 글자, restore 부수효과 없음, gap과 blocker 표시. 해당 instrumentation 양 API 통과가 완료 기준이다. 실제 TalkBack은 별도 pending이다."

### T10: 1.0 APK 후보와 안내

- **depends_on**: [T12]; T9 compiler 통과 후 후보를 준비하고 최종 Native 완료는 수용 선행 조건; **tier**: standard; **status**: done.
- **변경 파일**

  | 파일 | 구분 |
  |---|---|
  | `android/app/build.gradle.kts` | 수정 |
  | `README.md` | 수정 |
- **테스트 및 완료 기준**: versionName `1.0.0`, versionCode `2`로 변경하고 APK metadata를 확인한다. 최소 사용 안내 및 미완료 실기기/계정 수용을 기재한다. Play 공개 배포는 범위 밖이다.

T1/T2/T3는 파일이 겹치지 않아 병렬 가능하다. 각 작업의 실제 인터페이스와 관련 테스트를 확인한 뒤 종속 작업을 시작한다. 공유 emulator/build는 직렬화한다. 공통 모델 gap이 실제로 발견되면 근거와 제한된 수정 범위를 기록해 별도 계획 보정하며 선제적인 빈 작업은 만들지 않는다.


- **보안 검토 반영**: T12의 개인정보 안내를 실제 최종 동작과 대조하고 개발 머지/테스트를 실제 기기 수용으로 표현하지 않는다.

### T11: 자동 engine 경계

- **depends_on**: []; **tier**: standard; **status**: done.
- **변경 파일**

  | 파일 | 구분 |
  |---|---|
  | `C/DestinationSync.kt` | 수정 |
  | `CT/DestinationSyncTest.kt` | 수정 |
- **필수 읽기**: 부모 계획 `docs/2026-11-05-v1-completion/plan.md` 2절 AutomaticRetryContracts와 automation-ui T5의 engine 계약 전체를 유지한다.
- **인터페이스**: `run(trigger, generation, attemptId, automaticOwner: AutomaticOwner? = null, finishAttempt: Boolean = true)`; 기존 manual 호출은 기본값으로 byte/finish 의미를 유지한다. 모든 automatic mutation에 실제 owner를 전달하고 day 준비 mutation이 실제 revision을 포함한다. automatic finish는 session 소유로 false를 사용한다.
- **작성할 테스트**:
  - "readScoped 및 pure identify/normalize/CSV만 source catch, Cancellation 재throw, 두 SourceReadException reason 보존, 나머지 SOURCE_READ_FAILED; store/destination IO는 source catch 밖이며 기존 unknown/prior/성공 파일을 보존한다. 자동은 첫 실패에서 중단하고 수동 기존 동작 유지, owner 전달과 중복 finish 없음. 관련 core 전체 통과가 완료 기준이다."

### T12: 실제 동작에 맞는 개인정보 안내

- **depends_on**: []; **tier**: standard; **status**: done.
- **변경 파일**

  | 파일 | 구분 |
  |---|---|
  | `A/PrivacyActivity.kt` | 수정 |
- **기준**: 독립 보안 검토의 foreground-only/예약 없음 오표기를 수정한다. 수동 Drive·SAF, 명시 opt-in한 자동 Drive의 background 권한 및 범위, 끄기와 시스템 권한 철회, 로컬 reset과 원격파일 삭제의 차이를 실제 승인 동작에 맞게 설명한다. backend/상시서비스/정각·완료 보장/실제 수용을 주장하지 않는다. 최소 계정 binding은 앱 비공개 noBackup DB, token은 메모리라는 기존 경계를 유지한다.
- **완료 기준**: static copy만 변경하고 실제 source/승인 계약과 대조한다. 앱 컴파일과 기존 개인정보 화면/최종 Native gate를 유지하며 저위험 정적 문구에 구현을 복제하는 별도 테스트는 추가하지 않는다.

### T13: 자동 활성 시 수동 Drive 공존

- **depends_on**: [T1]; **tier**: standard; **status**: done.
- **변경 파일**

  | 파일 | 구분 |
  |---|---|
  | `A/ManualDriveSession.kt` | 수정 |
  | `AI/ManualDriveFlowInstrumentedTest.kt` | 수정 |
- **기준**: T8 실제 연결 검토에서 checkCurrent의 !config.automatic 조건이 자동을 켜면 기존 전경 수동 Drive까지 막음을 확인했다. 승인 spec은 수동 경로 유지와 manual/worker 공용 gate 공존을 요구한다. 이 불필요한 automatic=false 제약만 제거하고 generation/categories/account/epoch/foreground/private consent 및 fresh proof 검사는 모두 유지한다. SAF의 자동 차단 조건은 유지한다.
- **작성할 테스트**:
  - "실제 SQLite automatic=true 구성에서도 전경 별도 동의와 새 계정 proof를 거친 manual 읽기가 동작하고 automatic flag를 끄거나 retry lineage를 생성하지 않는다. 동의/foreground/late generation 거부 회귀는 유지한다. 기존 해당 class 및 saf-flow 진단 양 API 또는 최종 full에서 실제 검증한다."
- **완료 기준**: 소스·회귀 검증 후 T9가 같은 native file을 순차 인계한다. 별도 UI나 의존성은 추가하지 않는다.

### T14: 기존 runner의 1.0 고정 진단

- **depends_on**: []; **tier**: standard; **status**: done.
- **변경 파일**

  | 파일 | 구분 |
  |---|---|
  | `.github/workflows/android-emulator-tests.yml` | 수정 |
  | `android/ci/run-emulator-validation.sh` | 수정 |
- **기준**: 기존 T21 workflow_dispatch 고정 allowlist 패턴에 automatic-v1 옵션만 추가한다. AutomaticSettingsInstrumentedTest, BackgroundAuthorizationInstrumentedTest, BackgroundPermissionFlowInstrumentedTest, SyncWorkSchedulerInstrumentedTest, HealthHomeScreenInstrumentedTest의5개 source 존재를 확인하고 그 class만 실행한다. 실제 XML method inventory/digest를 대조한다.
- **제약**: PR event와 manual full의 기존 runner/격리 host/테스트/timeout/업로드/permissions는 byte 그대로 유지한다. 동적 selector, sharding, 새 framework/의존성/인프라나 full PASS 대체는 없다. unknown scope나 source 부재는 실패한다.
- **검증**: bash -n, 기존 full body byte 비교 및 실제 양API 해당 진단; 최종 PR에는 양API whole full을 별도로 필수 실행한다.

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

### T5 병렬 분리 — T11 pure engine

DB 실제 검증 대기 중 기존 T5의 SDK-free engine/source 경계는 이미 존재하는 core owner/type 계약만 사용하고 store API 구현을 읽지 않는다. 해당2파일을 T11로 분리해 병렬 구현·core 검증하며, T5 app session2파일은 T1 실제 SQLite 및 T11 완료 뒤 인계한다. 원래 T5 정책/복구 횟수를 나누어 초기화하지 않는다. 신규 기능·의존성·스펙 변경 없이 사용자 병렬 진행 위임을 적용한다.

### 독립 의존성·보안 중간 검토

4 actual Gradle 구성 129/130/135/110, 합계 고유 Maven 좌표155에 OSV HTTP200/조회오류0/매치0을 확인했다. 최종head의 Gradle파일 hash 및 mergedmanifest/artifact검사는 별도필수다. PrivacyActivity가 예약조회 없음을 주장하는 Important를 T10 기존안내 범위의3번째파일로 배정한다. 원본보고서는 /workspace/v1-security-audit-report.md, 좌표·scope·시각·buildhash는 /workspace/v1-dependency-audit.json이며 실제계정/기기/전체source감사통과를 의미하지 않는다.

### T1 교정3 — 독립 리뷰의 실제 관측·stale·수동 복구 경계

6dc3308 독립 정적 리뷰가 세 Important를 확인했다. 같은 day revision도 lineage 관측값과 다르면 실제 source reread day mutation과 같은 commit에서 기록한다. auth/exhaustion 시 기존 REMOTE_STALE phase/datasets/prior/blocker를 그대로 유지하고 ledger outcome으로 실패를 보존한다. 명시 fresh proof 이후의 manual reopen은 비eligible budget을 실패 반환하여 다른 manual 실행 전체를 막지 않고 원상 보존한다. 이미 관측/날짜 journal이 전혀 없는 finished blocked claim은 명시 manual 경계에서만 lifecycle 종료할 수 있으며 health 성공이나 EMPTY 행을 만들지 않는다. 기존 journal blocker의 해제는 기존4종만 허용하고 unsafe/unknown/stale 상태는 그대로다. 자동 tick이나 앱 열기는 이 종료를 하지 않는다.

T1 기존 store와 native 테스트만 보정하고 same-revision/reopen/rollback, auth 및 exhaustion stale 보존, claim 직후 crash→recovery→explicit manual 및 다른 eligible/unsafe cohort 공존을 실제 SQLite로 검증한다. 부모의 4종 blocker 해제 정책 및 자동 initial+3 한도는 바뀌지 않는다. 보정1·2를 보존하고 이번3으로 누적한다. 독립 리뷰의 새로운 실제 경로 근거와 AGENTS.md 소유자 V1 가역 수정 자동 위임에 따라 동일 범위 교정을 계속하며, 차단 수정 재검토와 실제 양API 검증은 필수다. 다중 날짜 변경 중 예외는 transaction을 빠져나가 rollback되도록 하고 INVALID status를 통한 부분 commit은 허용하지 않는다.

### T11 보정1·2 — 검증 경로 및 manual 결과 우선순위

최초 실행자가 지시된 owned tooling 대신 ./gradlew를 사용해 read-only /home/agent/.gradle lock 단계에서 실패했다. 보정1은 제품 변경 없이 root가 /workspace/tooling/run-health-command.py 및 owned Gradle로 같은 coreOnly 테스트를 실행한 것이며 92 tests 실패/오류/skip0이다. 원래 T5의 분리 작업이므로 이 이력은 재분리로 초기화하지 않는다.

root diff 검토는 rows에 null이 있을 때 PENDING을 우선하던 기존 manual의 결과 순서가 automatic용으로 전역 변경된 것을 발견했다. 보정2는 automatic owner가 없는 manual/legacy에서 기존 순서를 보존하고 automatic만 첫 차단/재시도를 먼저 표시한다. 기존 T11 두 파일 안에서 automatic destination 실패의 다음 날짜 미조회, 취소 시 owner journal/취소 전파와 manual 혼합 null/blocked 결과를 의미 있는 테스트로 검증한다. 이미 완성한 소스/테스트는 보존하며 같은 coreOnly 명령1회 후 추가 루프 없이 보고한다.

### T1 교정4 — 미준비 lineage의 독립 수명

R3 독립 재검토는 pristine retirement가 현재 마지막 attempt link와의 일치를 추가로 요구해, A 중단 뒤 B/수동 attempt가 저장되면 A가 영구 BLOCKED가 되는 실제 교차 scope 경로를 확인했다. terminal BLOCKED budget은 validated owner=null이고 전체 observed=null/해당 frozen day 부재라 준비된 source/remote journal이 없음을 기존 ledger로 확인할 수 있다. 명시 fresh-proof manual 경계에서 이 원문 조건을 사용하고 현재 마지막 attempt/link 일치는 요구하지 않는다. 기존 journal/unsafe/unknown 보존·4종 blocker 해제·자동 tick 제한은 유지한다. 기존 store/native 두파일만 수정하고 A recover→B claim/finish→reopen 및 수동 attempt 교체 경계를 실제 SQLite로 검증한다. 보정1~3을 보존하며 독립 새 근거에 의한 같은 범위 가역 교정4다.

### T11 완료 및 기존 SQLite 진단 근거

root가 올바른 owned Gradle/coreOnly 명령을 직접 다시 실행하여 PASS를 확인했고 XML은94 tests/failure/error/skip0이다. 실제source에는 owner 전파·automatic session finish 분리·source catch·첫실패중단과 기존 manual null-row 우선순위가 반영됐다. 로그 /workspace/tooling/v1-t11-root-verify.log. T1 초기6dc3308의 고정 retry-store44968857595도 양API 공식ZIP SHA256과 원본XML을 대조해 각각7/7/실패·오류·skip·중복0으로 확인했으나 R3/R4 신규 수정/회귀의 통과 증거로 대체하지 않는다. T1 최신검증은 여전히 pending이다.

### T3 SDK runtime 검증 배치 조정

T3는 JVM callback/proof 회귀와 실제 SDK API 서명 및 계측 소스 컴파일을 root가 확인했고 독립 정적 인증 검토에 차단 지적이 없다. 별도 SQLite 상태를 구현하지 않는 SDK adapter의 양API 계측은 통합 PR 현재head 전체 Native에서 그대로 수행하되 후속 구현 착수를 위한 별도 full 실행은 요구하지 않는다. 이는 사용자 속도 우선 위임에 따른 배치 변경이며 실제 SDK runtime/실계정 성공을 선행 주장하지 않는다. T1 실제SQLite 대상 검증과 모든 최종머지 gate는 유지한다.

### T1 실제 양API 완료 및 T5 인계

current source1f3c456의 retry-store44969852348 양API jobs SUCCESS다. root가 GitHub 공식 artifact digest(35:e74750930e622f0dcec6bd10757f18278c9becbe6daa4980ced539c8f2c58629, 36:ba44205bd17e44d632779367bc3bfc36d0f25ca7f2c48d55c76faac1c388f03b) 및 실제 XML을 source-derived 14개 method identity와 대조했다. 각각14/실패·오류·skip·중복·누락·추가0이다. R4 독립 중간 검토 APPROVE와 함께 T1을 완료하고 기존 owner/engine 계약의 T5 app session2파일로 인계한다. 전체143 baseline44966834193도 양API 공식digest/원본XML+격리protocol+sourceinventory 및 모든 기존host를 root가 별도 재검증했다. 이는 향후 최종head 전체gate를 대신하지 않는다.

### T10 병렬 분리 — T12 정적 개인정보 안내

T10의 기존 PrivacyActivity 정적 설명1파일은 실행 API 구현과 독립적으로 승인된 동작을 설명할 수 있어 T12로 분리한다. 별도 Terra가 즉시 수정하고 T10 최종 실제 동작과 재대조한다. 기능/권한/범위 확대는 없으며 기존 T10 복구 이력은 분리로 초기화하지 않는다.

### 원래 T5 교정3 — explicit detectPending 기본 trigger fixture

구현자가 기존 detectingAutomaticPolicyUsesAccessMetadataOnlyAndAbsentOrUnavailableStoresNeverFabricateCoverage가 config automatic=true에서 CATCH_UP을 암묵 선택할 것을 기대함을 확인했다. 승인 부모는 detectPending 기본 MANUAL 유지와 config 기반 암묵 trigger 제거를 명시한다. 해당 기존 test를 기본 MANUAL을 설명하는 이름·[MANUAL,MANUAL] 기대값으로 정합화하고 명시 CATCH_UP 호출의 전달도 추가 확인한다. absent/unavailable·읽기/원격0 및 나머지 assertion은 보존한다. T11 분리 작업의 원래T5 교정1·2를 보존해 이번3으로 집계하며 새 정책이나 테스트 약화가 아니다.

### T12 정적 안내 완료

root가 PrivacyActivity diff와 실제 noBackup 저장/token 메모리 경계 및 승인된 자동 Drive/수동 SAF/철회·reset 계약을 대조했고 정적 문자열만 변경됨과 git diff --check PASS를 확인했다. 별도 테스트를 추가하지 않으며 전체 앱 컴파일·Native 및 최종 동작 대조는 통합 gate에 유지한다. 자동 기능의 실제 기기 수용이나 정각 실행 보장은 문구에 포함하지 않았다.

### 원래 T5 교정4 — production 부분 scope 허용

초기 T5 compile/core94/app106 및 계측APK 빌드는 PASS58s다. 독립 리뷰는 state.requestedScopes.keys == config.categories 조건이 production AutomaticHealthReader의 HEART_RATE BLOCKED/나머지4항목 상태를 전면 거부함을 확인했다. 승인 spec의 HR 차단과 다른 category 독립 진행에 따라 planner key 및 configuration의 허용 category 부분집합을 검증하며 전체 동등성은 요구하지 않는다. 최초 fresh positive scopes만 고정하고 claim 후 권한 변경 거부는 그대로다. session/native 기존2파일만 보정해 5-category config+4-positive 자동 완료/HR excluded, steps-only partial 및 zero-positive 차단의 실제 SQLite 회귀를 추가한다. T11 교정1·2 및 T5 fixture교정3을 보존해 이번4로 누적한다. 동일 기존 V1 권한 범위의 실제 production 경로 결함에 대한 가역 교정이며 건강 성공/5종 전체 완료를 꾸미지 않는다.

### T7 격리 staging 병렬화

T5의 실제 API/worker marker는 컴파일 통과했고 하부 T1 원자 SQLite14개는 양API 통과했다. 파일이 겹치지 않는 T7 코드 준비는 이 안정된 API 위에서 T5 대상 Native 대기와 겹쳐 수행한다. root는 T5 후보를 별도 검증 worktree에 고정해 T7 진행 중 소스를 제외하고 검증한다. T7 자체 검증/최종 통합은 T5 현재 후보의 실제 양API session 통과 뒤에만 수행하며, T5 실패·API 변경 시 T7 검증은 보류하고 source를 보존해 한정 보정한다. 어떤 pending도 done/출시수용으로 바꾸지 않는다. 이는 사용자 병렬 속도 위임에 따른 staging 순서 변경이며 전체머지/개인정보/실제SQLite gate는 유지한다.

### T3 교정1 — worker의 suspend durable currentness 검사

T7 실제 연결 검토에서 BackgroundDriveAuthorization의 동기 isCurrent 콜백으로 suspend-only SQLite의 current generation/automatic을 SDK 응답 후 about 직전에 확인할 수 없음을 발견했다. 동기 runBlocking/main-thread 우회나 새 메모리 캐시를 만들지 않는다. 해당 새 background authorizer의 콜백만 suspend () -> Boolean으로 바꾸고 시작/SDK 응답 후 about 전/about 후 block 진입 전의 fresh durable 검사를 await한다. 기존 BoundDriveAuthorization 공개 수동 API는 그대로이며 그 내부 동기 guard에는 ticket sequence/현재 coroutine Job 활성만 사용한다. worker는 store.load()의 generation+automatic을 suspend로 검사한다. token/engine은 gate owner 실행의 메모리에만 두고 callback/engine을 job 간 저장하지 않는다.

기존 T3 authorizer와 해당 JVM 테스트2파일만 보정해 검사 자체의 suspension과 SDK 응답 이후 무효화 시 about0, about 이후 무효화 시 block0·취소 전파를 검증한다. 새 의존성/권한/정책/스키마는 없으며 T3 교정1로 집계한다. 이 보정의 실제 SDK runtime은 기존 약속대로 최종 양API Native에 유지한다.

### T8 격리 staging 병렬화

T1 실제 CAS/복구는 양API14개 통과했고 T7 scheduler의4개 public API 및 실제 SDK 서명이 확정되었다. T8의 disjoint ViewModel/actions/settings test3파일 구현은 T7 native/worker 검증 대기와 병렬 staging한다. root는 T7만 고정 snapshot에 옮겨 T8 WIP 없이 먼저 검증하며 T8 자체 검증/완료/최종통합은 검증된 T7 source 위에서 수행한다. generation invalidate 후 cancel, 자동off 복원시 enqueue/SDK 호출없음 및 기존 manual/SAF 의미를 보존한다. 관련 gate 실패시 T8 source를 보존하고 검증만 보류한다.

### T8 연결 검토 보정 — 기존 수동 Drive의 자동 flag guard

원래 수동 경로 보존 기준의 누락을 실제 source line322에서 확인해 T13 2파일 가역 수정으로 분리한다. T8의3파일/자동설정 작업과 disjoint이며 T9 같은 테스트파일 수정 전에 완료한다. 새로운 수동 실행 권한이나 백그라운드 SAF를 허용하지 않는다. 기존 승인 동작의 연동 교정이고 해당 검토를 T8 준비 근거로 보존한다.

### 원래 T5 교정5 — retry 시각의 합성 confirmation fixture

1745646 대상 Native44971406816의 양API 원본XML은 각각23건 중22 PASS/1 FAIL, automaticRetryKeepsLineageAndConsumesBudgetWhenSourceRevisionChanges의135행에서 COMPLETED 대신 BLOCKED다. 실제 source 대조로 두 번째 실행은 clock+30초인데 fixture confirmation은 원래 time을 고정해 file.startedAt보다 이전 확인 시각을 전달하며 기존 core validator가 거부함을 확인했다. production 확인 시각 검증이나 기대 COMPLETED는 유지하고 해당 새 테스트의 retry write callback만 later.instant()의 확인으로 정합화한다. retriesUsed=1/같은 lineage/새 revision의 검증도 유지한다. 기존 session 제품 코드는 변경하지 않고 기존 native 파일1개만 수정한다. 원래T5 교정1~4를 보존하여 이번5로 누적하며 다른22건 통과는 보존한다. 수정 후 같은 class 양API 실제 진단을 재수행한다.

### T13 교정1 — configure의 명시 default-off fixture

root 정적 대조는 T13 새 테스트가 configure(automatic=true)를 전달하지만 실제 store.configure는 설계대로 항상 false를 저장함을 확인했다. 해당 테스트는 configure 후 setAutomaticCAS(generation,true)를 명시 수행해 실제 켜진 구성을 만들고 이후 manual 검사/automatic=true 유지 기대를 그대로 검증한다. 제품 guard 수정이나 configure의 default-off 정책은 변경하지 않는다. 기존 native 테스트1파일만 보정한다.

### T7 교정1 — 예상 밖 worker 실패와 production factory 증거

독립 리뷰는 worker가 execute의 예상 밖 예외를 Result.success로 기록함과 native 테스트가 custom test factory만 검증함을 지적했다. unknown exception은 failure로 반환하고 CancellationException은 재전파하며, BLOCKED/STALE 정상 no-op과 RETRYABLE 재대기 의미는 구분한다. production runner는 DB READY/current gen/automatic/Drive binding을 먼저 확인하고 stale/default-off에서 SDK 구성·health read를 하지 않는다. 실제 production WorkManager configuration의 factory와 실제 worker builder/DB no-op 실행을 native에서 검증하며 실제 constraints 충족을 임의 가정하지 않는다. 기존4파일 안에 한정하고 새 test helper 전역 초기화/registry는 추가하지 않는다.

### T8 교정1 — 미확인 동의·수집기 실패·중단된 변경 요청

독립 리뷰의 세 Important를 같은 설정 경계에서 보정한다. 저장소 CORRUPT/UNAVAILABLE을 꺼짐으로 단정하지 않고 별도 known/unknown 상태와 확인·복구 안내로 표시한다. WorkInfo 자식 coroutine 내부에서 일반 예외를 가시적 미확인 상태로 처리하고 CancellationException은 재전파한다. controller는 실제 변경 child Job을 등록하고 invalidate에서 취소하여 기존 store.write의 transaction commit 전 coroutine.ensureActive 경계를 사용한다. commit 완료 이후의 취소를 되돌린다고 주장하지 않으며 명시 off/reset은 generation CAS로 별도 처리한다. beforeCommit에서 중단시킨 실제 SQLite 변경을 invalidate/cancel한 뒤 rollback과 automatic=false 유지·enqueue0을 native 회귀로 입증한다. 이를 위한 native 파일1개를 추가해 T8 총4파일이다. 기존 controller/unit 테스트와 manual/SAF 동작은 보존한다. FAILED/CANCELLED WorkInfo도 명시적으로 표시하며 건강 완료로 라벨링하지 않는다. store API/새 lock/cache/권한/의존성은 추가하지 않는다.

### T3 suspend 검사 완료 및 대상 검증 재시작

T3 교정1은 root 격리 snapshot에서 app107 JVM 테스트 실패/오류/skip0 및 실제 계측 소스 컴파일 PASS42s를 확인했고 main의 두 파일과 byte 일치했다. T5 교정5는 confirmation 시각만 바로잡아 제품 세션 source는1745646과 동일하다. T13 교정1은 default-off configure 이후 명시 setAutomaticCAS(true)를 사용한다. 두 실제 Native 고정 진단을 같은 새 checkpoint에서 다시 수행하며 결과 전에는 완료로 판정하지 않는다.

### 마지막 화면의 격리 staging 및 compiler 준비

T7의 실제 SDK/production factory source와 T8의 SyncUiState/ManualDriveActions API가 고정되어 T9의 disjoint Activity/HomeScreen 연결은 후보 compiler/Native 대기와 병렬 staging한다. T13 native 파일은 checkpoint4358ff9로 소유가 이전됐지만 T13 실제 진단 완료 전에는 T9가 해당 ManualDriveFlow 테스트파일을 수정하지 않는다. root의 격리 compiler 실행은 타입/API 준비 확인일 뿐 해당 runtime 통과·완료로 쓰지 않는다. T7/T8/T9 실제 runtime 검증·최종 통합/병합은 앞선 실제 SQLite 및 최종 현재head 전체 gate가 모두 통과해야 한다. source/테스트는 실패 시 보존하고 필요한 원인 작업만 한정 보정한다.

### T5·T13 실제 검증 완료 및 마지막 통합 준비

4358ff9의 retry-session44972727379 양API23/23, saf-flow44972729910 양API9/9를 root가 공식ZIP SHA256/원본XML/source method 집합과 대조했다. 각각 실패·오류·skip·중복·누락·추가0이다. T5 교정5는 실제 기존 확인시각 검증을 유지한 fixture 정합화로 완료했고 T13은 자동=true에서도 별도 수동 동의/proof 경로를 보존함을 실제 SQLite로 검증했다. T7/R1 및 T8/R1 독립 중간 재검토 APPROVE와 root 격리 compile/JVM core94+app115=209/실패·오류·skip·중복0을 확인했다. worker/settings/UI의 새 Native는 최종 현재head full에서 계속 pending이다.

### 1.0 후보 마무리 단계

T9 화면3파일은 root 격리 Kotlin 및 Native 소스 컴파일 PASS18s다. 기존 T13 manual/SAF9개는 양API로 재사용하며 별도 중복 테스트를 만들지 않았다. T10은 버전/사용안내 후보를 준비해 동일 최종 version의 whole Native를 검증하도록 배치한다. 빌드·실기기·계정 미확인은 설명에 유지하며 Play 공개 배포나 실제 권한/동기화 수용을 승인한 것으로 쓰지 않는다.

### T9 교정1 및 T8 교정2 — 별도 background 권한 복귀 경계

독립 화면 리뷰는 background 결과가 기존 Google/Drive 전경 큐에 들어가 BLOCKED·재차 pause에서 버려져 pendingConsent가 남는 실제 경로를 확인했다. background launch/result pending boolean을 별도 보관·복원하고 RESUMED 복귀 시 Drive phase와 무관하게 한 번 소비한다. pause에서 결과를 지우지 않으며 결과 처리는 pending 정리와 metadata뿐이고 자동 활성·record·upload·credential 복원은 하지 않는다. 실제 Activity/제어 가능한 production-used permission boundary를 새 BackgroundPermissionFlowInstrumentedTest에서 검증한다. 새 테스트는 실제 OS 권한 부여 증거로 쓰지 않는다.

T8은 기존 owned UI/VM에서 background 권한 목적 확인/취소 dialog와 별도의 beginBackgroundPermissionRequest, backgroundPermissionUnavailable, backgroundPermissionLaunchFailed metadata-only hooks를 제공한다. T9 실제 launcher는 fresh SDK background feature AVAILABLE일 때만 요청하고 unavailable/unknown은 요청하지 않고 고정 이유를 표시한다. manual5권한 배열은 유지한다. 기존 enable 동의와 권한 부여만으로 자동 활성되지 않는 원칙을 유지한다. T9 4파일(기존3+새Activity test), T8 기존4파일 안으로 한정하고 새 SDK/dependency/권한/비밀 bundle 저장은 없다. T9 교정1/T8 교정2이며 앞 회차는 보존한다. schema/store/worker가 아닌 UI 복귀 경계만 보정한다.

### 기존 고정 진단의 최소 확장

새 worker/permission/설정 Native 오류를 기존 약40분 full 전체의 끝에서 처음 발견하지 않도록 이미 승인된 T21의 정적 allowlist 옵션을 T14 2파일로 확장한다. 기존 full/PR 경로는 그대로이고 짧은 진단을 whole gate로 사용하지 않는다. 새 CI framework 제외 범위를 유지하며 같은 runner의 고정 선택만 추가한다. 소유자의 기능1.0/병렬속도 위임 안의 검증 배치 조정이다.

### 1.0 후보 로컬 최종 빌드

root가 main의 고정된 전체 후보에서 core:test app:testDebugUnitTest assembleDebug lintDebug assembleDebugAndroidTest를 직접 실행해 BUILD SUCCESSFUL2m30s를 확인했다. XML core94/app116=210, failure/error/skip/중복0. lint0 errors/19 warnings(14개 새 버전 알림과 기존5개)이며 dependency upgrade를 수행하지 않았다. aapt2로 package com.ny.my.health/versionName1.0.0/versionCode2/min28/target36을 확인했다. 새 worker/설정/권한/화면 Native5개class 고정 진단 및 최종 full은 여전히 별도 필수다. T8R2/T9R1 독립 중간 재검토도 APPROVE이며 실제 OS권한/기기/계정 수용을 의미하지 않는다.

### T1 교정5 — 실패 journal은 새 source 관측이 아님

최종 source 대조에서 engine이 readScoped/identify 실패 시 이전 day.revision을 보존하고 checkedAt=null/차단으로 journal하지만, store의 owner day mutation은 그 이전 revision도 새 lineage의 실제 관측으로 기록함을 확인했다. 실제 성공한 read 준비는 checkedAt을 함께 기록한다. T1 store는 checkedAt이 존재하는 actual prepared day mutation에서만 observed revision을 갱신하고 source 실패의 보존 revision은 관측 null을 유지한다. 기존 same-revision 실제 reread 관측·owner CAS·count·source 실패와 prior 상태 보존은 그대로다. 기존 store/Native 테스트2파일만 한정하며 새 schema/API/권한은 없다. 이전교정1~4를 보존해 이번5로 누적하고 새 실제 경로 근거에 따른 같은 범위 자동 위임으로 실행한다. Native는 기존14+해당 부정 관측 회귀를 실제 양API로 재검증한다.

### 새 자동 경계 양API 진단 및 최종 검증 배치

4839856의 automatic-v1 실행44974940147을 root가 공식 ZIP SHA256과 원본 XML/source method 집합으로 검증했다. API35 8fa77be13371c6b1b2d04248e6a00e9691e70fbea4c88cc5e1018e214dd5747a, API36 99f99f9914545ca29180829f7c14e8ce32655128dc4c9689b96a7b462312b056이며 각각32/실패·오류·skip·중복·누락·추가0이다. T7/T8/T9/T14의 대상 Native gate를 완료한다. T1교정5는 독립 좁은 재검토 APPROVE와 root compileDebugAndroidTestKotlin/testDebugUnitTest PASS44s를 확인했다. 교정5의 새 실제SQLite 부정경로는 중복 별도 진단 대기 없이 바로 최종 PR 현재head full 양API에 포함한다. 기존14개 통과를 새15개 통과로 주장하지 않으며 최종 whole/격리host gate는 그대로 유지한다. 이는 사용자 속도 우선 위임의 동일 runner 검증 배치 변경이다. 전체 계획은 최종 full 및 격리 스펙 검증까지 in_progress다.

### T1 교정6 — 동일 목적지 opt-out/re-enable의 uncertainty·예산 우회 차단

fresh-context PR20 검토는 fe18ac2에서 setAutomaticCAS(false)가 같은 binding/enrollment의 새 generation을 만들고 구 day/dataset/retry 상태는 이전 generation에만 남겨, 재활성화가 미확정 create와 미완료 예산을 우회할 수 있음을 확인했다. 기존 stale callback/구 SQL 보존 테스트는 재활성화를 검증하지 않는다. 이전 generation의 callback 무효화는 유지하면서 동일 목적지의 불확실한 쓰기·미완료 retry를 새 admission에서 보존 또는 보수적으로 차단한다. 실제 SQLite에서 in-flight unknown create 및 exhausted/미완료 budget의 off→on→reopen과 stale callback을 검증하고 기존 manual/SAF 의미는 보존한다. 새 source/remote 호출이나 자동 명시복구는 없다. 원래 T1 교정1~5를 보존하여 이번6으로 집계하며 승인된 V1 정확성·소유자 자동 위임 안의 실제 결함 교정이다. 범위는 store/native와 꼭 필요한 인접 계약 최대5파일이며 스키마/API 확대가 필요하면 구현 전에 root에 근거와 최소안을 보고한다. 구 head full44976096322는 취소하며 수정 head 전체 gate를 새로 실행한다.

### 교정6 계약 명확화 — 동일 binding에서만 현재 상태 이월

원래 세대 무효화와 구 linked attempt 보관은 유지한다. 현재 private binding의 동일성이 증명되는 opt-out transaction에서만 새 generation으로 day/dataset/prior confirmation을 재키잉하고, 미종결 retry cohort를 동일 frozen scopes/dates/관측/count/claimVersion/status/due/시각으로 이월한다. SQLite PK 유일성 때문에 이월 행의 lineage UUID/key만 새로 발급할 수 있으나 이는 fresh initial admission/예산 초기화가 아니며 구 lineage를 삭제·수정하지 않는다. owner는 기존 종료/중단 정책으로 해제하고 실제 무확정 상태는 보존한다. 두 세대의 원본/이월 행은 같은 transaction에서 검증되며 실패/취소는 전체 rollback한다. 새 schema/column/JSON metadata는 도입하지 않는다.

historical generation에는 account binding이 없어 private-drive 문자열만으로 모든 과거 계정의 handle을 합치지 않는다. 현재 binding이 같은 Drive 재설정에서 미확정/미완료 상태를 버리는 경우는 보수적으로 거부하거나 동일한 안전 이월을 사용하며, 다른 계정 binding을 동일 목적지라고 추정하지 않는다. Drive의 invalidate도 같은 우회가 없어야 한다. 기존 generic/SYNTHETIC foundation의 명시 세대 교체·빈 coverage 계약과 기존 assertion은 유지하고 실제 Drive 경계를 별도 SQLite 회귀로 검증한다. 단순히 테스트 통과를 위해 SYNTHETIC 값을 production Drive의 동일성 근거로 사용하지 않는다. 이 명확화는 원래 budget 제한·반복 실패 보존의 의미를 유지하는 storage-only 교정이며 독립 적합성/PR 재검토가 필수다.

교정6의 현재 binding guard는 기존 confirmed target ID도 Drive lookup에서 누락되었을 때 중복 create를 막는 근거임을 확인해, 같은 Drive 계정에 기록이 남아 있는 category 재구성을 보수적으로 거부한다. 같은 계정·같은 category의 재인증은 기존 ManualDriveSession이 configure 없이 동일 generation을 사용하므로 유지한다. 이 제한은 README에 명시하고, explicit reset은 원격 삭제가 아님을 유지한다. 범위/등록일 변경을 위한 범용 과거 이력 마이그레이션은 1.0에 추가하지 않는다.

### T1 교정7 — 기존 수동 category 변경 보존

80c5861 재검토는 same-binding Drive에 day 하나라도 있으면 configure를 거절하는 교정6 guard가 정상 수동 업로드 후 category 추가·제거 기능을 막는 회귀임을 확인했다. README의 reset 우회는 승인된 수동 동작 보존을 충족하지 못한다. 해당 제한을 최종 1.0으로 채택하지 않으며 앞의 제한 문구는 중간 교정 이력으로 보존한다. 동일 계정의 category 재설정이 기존 파일/미확정 추적과 미완료 budget을 버리지 않고 동작하도록 bounded 설계를 보완한다. generic foundation/다른 계정/SAF 의미와 기존 assertion은 유지하고 실제 Drive SQLite 및 수동 세션 회귀를 검증한다. 원래 T1 교정1~6을 보존하여 이번7로 누적하고 소유자의 기존 정확성·수동기능 보존 및 자동 승인 범위로 수행한다. 원시19개 대상 실행44977241883은 교정6의 좁은 경로 증거로만 계속하고, 교정6 full44977221291은 취소한다. 새 최종full은 수정 source의 독립 정적 재검토 후 시작하여 반복 낭비를 줄인다. schema/API 변경이 필요한 경우 구현 전 최소 기술안을 기록하고 독립 검토하며, 새 제품 범위는 추가하지 않는다.

### 교정7 승인 기술안 — 과거 profile과 현재 선택 권한 분리

동일한 현재 Drive binding의 category 변경은 기존 enrolledDay를 유지하고 새 generation/사용자가 선택한 정확한 category로 전환한다. 교정6 원자 snapshot 이월을 재사용하여 과거 day/dataset/미종결 budget의 동결 scope/date·관측·count/due와 target/uncertainty를 보존한다. 과거 profile의 requestedScopes가 **현재** 선택 category의 부분집합이어야 한다는 저장 유효성 조건만 day/ledger decoder에서 분리한다. UUID/hash/date/FK/owner/JSON/cap 및 실제 profile fingerprint 검증은 유지한다. 이는 현재 읽기 권한이 아니라 과거 metadata의 유효성이다.

새 attempt와 planner/reader는 현재 선택 category를 계속 요구하고, claimAutomaticAttempt에 current config.categories.containsAll(expectedRequestedScopes.keys)를 명시하여 제거한 category의 과거 budget을 직접 재claim하는 우회를 막는다. 기존 전역 preflight due/미해결 owner 경계는 유지하며 제거된 scope의 예산을 새 scope로 바꾸거나 자동 종료하지 않는다. old binding을 추정하지 않고 기존 다른 계정 목적지 전환 계약은 그대로다. store·ledger validator·실제 SQLite3파일을 기본 범위로 실행하고 수동 session 회귀를 별도 disjoint 작업으로 검증한다. 새 schema/API/권한/자동 복구는 없으며 독립 source 검토와 실제 실행 전에는 완료가 아니다.

### T15: 수동 category 변경 실제 SQLite 회귀

- **depends_on**: [T1]; **tier**: standard; **status**: done.
- **변경 파일**

  | 파일 | 구분 |
  |---|---|
  | `AI/ManualDriveFlowInstrumentedTest.kt` | 수정 |
- **필수 읽기**: AGENTS.md, 승인 release spec과 ManualDriveSession의 selectAccount/계정 proof/동의·foreground 경계, 현재 Native FlowFixture. 원래 T1 교정7의 분리 검증이며 교정1~6 이력을 초기화하지 않는다. store/ledger 파일은 다른 실행자가 소유한다.
- **기준**: 같은 Drive 계정에서 정상 수동 export 후 category 추가·제거를 다시 선택하면 설정이 성공하고 새 generation과 정확한 새 categories·원래 enrollment를 사용한다. 기존 confirmed target/prior/unknown 추적과 frozen retry 예산은 실제 SQLite에서 보존하며 foreground consent/fresh proof 이전에 records/remote를 실행하지 않는다. 과거 category metadata 보존은 새 health 읽기 권한이 아니다.
- **작성할 테스트**:
  - "실제 SQLite와 production-used ManualDriveSession에서 같은 계정의 category 변경 성공, 정확한 설정/기존 enrollment/새 generation, 이전 확인 handle 보존을 검증한다. fresh proof+전경 동의 없이 source/remote0, 명시 계속 후 새 선택 범위만 실행하고 구 generation callback은 거부한다. 필요하면 unknown 보존은 별도 store 회귀에 맡기되 실제 수동 경로 성공을 빼지 않는다. 기존7건·SAF2건 및 모든 단언은 유지하며 테스트 삭제/skip/완화는 금지한다."
- **완료 기준**: 소스·Native 컴파일 후 saf-flow 고정 진단 또는 최종 full 양API에서 source method identity와 실제 XML이 일치하고 실패/skip0이다. 실제 계정·OS permission 수용으로 주장하지 않는다. 원래 T1 수정 API가 준비될 때까지 source를 disjoint로 작성하고 자체 Gradle은 실행하지 않는다.

### T15 교정1 — 초기 등록일 fixture

root 실행 전 source 대조에서 새 수동 회귀의 최초 configure가 today-2를 입력하지만 production은 최초 yesterday 등록만 허용함을 확인했다. production guard와 성공 기대는 유지하고 새 테스트 초기 등록일만 유효한 yesterday로 맞춘다. 날짜가 지난 뒤 기존 enrollment 보존은 T1 교정7의 별도 실제 SQLite 테스트에서 today를 증가시켜 검증한다. 기존 수동/SAF 단언은 변경하지 않는다. 원래 T1 교정7의 분리 검증이며 이력을 초기화하지 않는다.

### 교정6 대상 SQLite 실제 결과 및 교정7 준비

80c5861의 retry-store44977241883 양API19/19를 root가 공식ZIP SHA256/source method 집합/원본XML로 대조했다. API35 00d89140f38dc45773390837f2bf8984368d6ef50e415bb51b6a28b85e244e53, API36 5404ab96b4b035d22e875ebb2c53c062b8d7e2e5f4c3c90008c46fed52320ae4이며 실패·오류·skip·중복·누락·추가0이다. 이는 off/on의 실제 보존 근거이며 교정7 category 변경의 성공으로 대체하지 않는다. 80c5861 JVM/lint/APK44977221304도 성공했지만 전체 Native44977221291은 재검토에 따른 취소다. 교정7 source store/ledger/Native3파일과 T15 manual test1파일은 고정됐고 원본 현재head의 compile·실제 대상 진단·독립 재검토·최종 full은 필수 pending이다.

### T8 교정3 — 기존 수동 UI 안내 기대값 정합화 및 외부 runner 실패

최종 full44977958836의 API36 원본XML은 legacy17개 중16PASS/1FAIL/오류·skip0이며 ManualDriveUiInstrumentedTest가 이전 안내인 '자동 동기화와 SAF 저장은 이 화면에서 지원하지 않습니다.'를 찾다가 실패했다. 자동 기능을 추가한 production 안내가 바뀌었으므로 기존 테스트의 마지막 안내 기대값을 실제 수동/자동 분리 계약에 맞게 정합화한다. light/dark·큰 글씨·버튼·동의·권한·범위·시각·상태의 모든 기존 단언과 반복·test method는 유지한다. 제품 코드 변경이나 테스트 삭제/skip/일반화는 없다. T8 교정1·2를 보존하여 이번3으로 누적하고 기존 owned4파일+해당 legacy Native1파일로 최대5파일이다.

같은 full의 API35는 Native 전에 UTP runtime artifact Maven Central 요청이 HTTP429(Too Many Requests)로 실패했다. adb 부팅 경고는 있었으나 최종 실패 원인은 dependency resolution이다. 실행0/원본Native 없음이며 앱 test 실패나 통과로 간주하지 않는다. 새 UI 기대값 head 전체 양API를 같은 기존 runner로 검증하고 이 외부 일시 실패가 반복되면 실제 로그 근거로 같은 head의 한정 재실행만 허용한다. 새 retry framework/테스트 완화/의존성 업그레이드는 하지 않는다. 짧은 진단의 과도한 병렬 다운로드를 추가하지 않고 다음 full matrix+JVM만 실행한다.

### 교정7·T15 대상 gate 완료 및 legacy 안내 교정 준비

f4eb4cb retry-store44978002202는 양API22/22, manual/SAF44978008345는 양API10/10을 공식 ZIP SHA256/원본XML/source method 집합으로 대조해 실패·오류·skip·중복·누락·추가0이다. JVM/lint/APK44977958803도210/실패·오류·skip·중복0이다. T1/T15 대상 gate는 완료하고 T8 교정3은 기존 안내 기대값1행만 변경하여 제품 소스와186개 Native method 집합을 보존한다. 새 후보에서 해당 계측 소스 컴파일과 독립 delta 검토 뒤 전체 gate를 실행하며 최종 merge/적합성/실제 수용은 대체하지 않는다. 전체계획은 in_progress다.

## 2026-11-22 소프트웨어 완료

[현재 1.0 실행 증거](execution-report.md)의 source6efa2d2에서 양API 전체 Native186/기존 격리 host 및 JVM210/lint/APK가 통과했고 독립 스펙 적합성 PASS·최종 PR 검토 APPROVE 뒤 [PR20](https://github.com/nyeonu/health-app/pull/20)을1139553으로 정상 병합했다. 날짜별 과거 실행/복구 이력은 보존하며 이 최신 완료 기록이 이전 진행 상태를 대체한다. 실제 기기·계정·P0·TalkBack·개인 키 백업은 미확인이다. 문서만 완료 상태를 갱신하며 Android tree5ac60a01b547634329ef625a6e05e950ef67f806은 변경하지 않는다.
