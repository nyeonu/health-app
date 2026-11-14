---
plan_id: 2026-11-05-v1-completion
title: V1 날짜별 복구·목적지·자동 실행 에픽 계획
kind: epic
status: in_progress
author: Codex
created: 2026-11-05
target: /workspace/health-app-v1-completion
spec: spec.md
spec_rev: 97c01b1992ad7935e6fa8f1f8b371a3963457daa
work_plans:
  - id: core-store
    title: 날짜별 복구와 내구성 상태
    path: core-store/plan.md
    depends_on: []
    status: done
  - id: manual-drive
    title: 계정에 연결된 전경 수동 복구
    path: manual-drive/plan.md
    depends_on: [core-store]
    status: done
  - id: saf-local
    title: 검증된 local tree 수동 내보내기
    path: saf-local/plan.md
    depends_on: [manual-drive]
    status: done
  - id: automation-ui
    title: 자동 실행과 통합 상태 화면
    path: automation-ui/plan.md
    depends_on: [saf-local]
    status: in_progress
---

# V1 날짜별 복구·목적지·자동 실행 에픽 계획

## 1. 선택한 방식과 이유

소유자의 “병목 개선부터 V1 끝까지 자동승인” 위임에 따라 approved로 작성했다. task-breakdown·structure-rules·testing-policy의 템플릿을 적용한다. 구현은 별도 plan-executor 세션에서 수행하며 이 문서는 구현 결과를 뜻하지 않는다. 승인된 [스펙](spec.md)이 범위와 성공 기준의 원본이다.

네 PR로 분리한다. SDK-free core와 실제 SQLite 복구를 먼저 검증하고, 계정을 고른 전경 Drive 실행, local SAF 실행, 자동 실행과 통합 UI를 순서대로 연결한다. 각 PR은 하나의 검토 가능한 기능 경로이고 하위 T 하나당 1~5개 제품/테스트 파일을 변경한다(PR 전체를 5파일로 제한하지 않는다). 새 test 기반 도입은 그 기반을 사용하는 기능 테스트와 별도 T로 둔다. 에픽에서는 하위 T를 작성하지 않는다. 이 파일을 먼저 커밋하고 그 revision을 parent_rev로 고정한 뒤 첫 core-store/plan.md만 작성한다. 후속 plan은 선행 병합 head를 직접 조사해 하나씩 작성한다.

coverage는 foundation history와 분리한 versioned SQLite DB에 둔다. 기존 history DB를 확장하면 지원하지 않는 schema·reset 계약을 바꾸게 되므로 기각했다. 단일 큰 JSON 봉투보다 날짜/파일별 행과 transaction을 선택하여 날짜가 늘어날 때 매번 전체 상태를 복제하지 않는다. 설정과 generation CAS를 같은 DB에 두는 비용을 감수한다. ADR-006 대상이다.

Drive는 기존 선택 없는 authorization을 유지하지 않고 AccountPicker의 선택 계정만 사용한다. credential은 실행 메모리에만 있고 최소 name/type만 private 구성에 저장한다. worker는 foreground resolution을 재사용하지 않고 매 실행 bound account로 fresh authorize한다. ADR-007 대상이다.

WorkManager 2.11.0의 work-runtime과 work-testing을 같은 버전으로 도입한다. CoroutineWorker는 runtime에 있으므로 빈 ktx artifact와 multiprocess 의존성은 넣지 않는다. 기존 Kotlin/SDK/coroutines를 일괄 올리지 않는다. 자체 polling/foreground service는 배터리·복구 계약에 맞지 않아 기각했다. unique work만으로 manual 중복을 막을 수 없으므로 모든 trigger가 공용 gate를 사용한다. ADR-008 대상이다.

기존 확인 재사용을 위해 모든 CSV를 media download하는 대안은 추가 전송량과 transport 변경 비용 때문에 선택하지 않았다. 공식 Drive SHA-256 metadata가 있는 경우 재사용하고 없으면 현재 safe target에 기존 upsert로 확인하는 비용을 감수한다.

선행 PR #15의 승인 plan을 읽었다. 실제 병합 전 API를 현재 코드로 간주하지 않는다. 기존 Ports/Android/CI 연결 작업 착수 전 그 PR의 reviewed merge head를 병합하고 Ports/PrivateDriveUploader/SyncCoordinator/test의 변경을 다시 대조한다. 성능 배치의 SDK-free 기능 검증과 독립 기능 적합성이 확인된 동안 별도 worktree의 신규 core 모델·planner·revision(T1/T2)은 기존 포트/API를 쓰지 않아 먼저 구현·유닛 검증할 수 있다. PR #15 코드/검증을 변경하지 않으며 core-store의 T3 및 PR 병합은 선행 성능 PR 병합 뒤 진행한다. 예상 batch API가 다르면 하위 plan과 본 공통 설계를 먼저 보정한다. 성능·원격 privacy 검증을 약화하거나 별도 캐시/병렬화를 추가하지 않는다.

2026-11-13 선행 개발 병합: PR #17은 실제 master `57da82514e8483f5f32f2ad34ae0111c7f1dc431`로 병합됐다. 이 checkout의 `android/`·`.github/`는 검증 대상 `b35c11c8b4651cf9b12b004a7fc4ae3d52a4d3ee`와 diff가 없음을 읽기 전용으로 확인했다(production source `c33467141db12d3953f5c77e5e1e22730d93d424`). manual-drive 개발 완료 근거는 검증 head b35c11c8의 Native run `43431523876` API35/36 각각 120/0/0/0·duplicate0, history host20·sync-state verifier17/cleanup17·killed hold PASS0, JVM/APK run `43431524846` JVM176 통과 및 수동 slice 독립 적합성 PASS와 최종 PR 댓글 `6854608943`이다. 실제 원본 대조와 한계는 root가 갱신한 [최종 검증 기록](manual-drive/final-verification.md)·[Native checkpoint](manual-drive/checkpoint-native.json)·[실행 기록](execution-report.md)을 따른다. 기존 interim 결과를 이 최종 검증으로 소급 치환하지 않는다. ADR-007은 미래 worker authorization 결정까지 포함하여 Proposed를 유지하며 manual milestone만 완료로 구분한다. 실제 Galaxy/Google 계정/TalkBack/P0 수용과 전체 V1 완료는 별개다.

SAF 부모 계약·7T 초안·ADR-011 Proposed 통합은 기존 V1 자동승인 위임 안에서 준비했다. child는 root의 fresh-context 계획 검토 전 `draft`, 에픽 SAF는 `pending`이다. 부모 commit 뒤 child parent_rev 고정·검토/승인 상태 확인을 마쳐 실행한다. T2는 T1 provider runtime과 독립이며 승인·revision 고정 후 시작할 수 있다. T3는 T1 actual API35/36 runtime 완료 전 시작하지 않는다.

## 2. 공통 설계

### 변경 파일

경로 접두어: C=`android/core/src/main/kotlin/com/ny/my/health/core/`, CT=`android/core/src/test/kotlin/com/ny/my/health/core/`, A=`android/app/src/main/java/com/ny/my/health/`, AT=`android/app/src/test/java/com/ny/my/health/`, AI=`android/app/src/androidTest/java/com/ny/my/health/`. 접두어는 실제 존재하는 디렉터리이며 신규 파일명도 여기서 고정한다. 같은 파일을 여러 PR이 수정하면 frontmatter 순차 의존성이 적용된다. 아래는 PR별 소유 파일 목록이며 하위 plan에서 1~5파일 T로 분해한다.

| 파일 | 신규/수정 | 변경 요지 | 작업 |
|---|---|---|---|
| C/SyncState.kt, C/SyncStateCodec.kt | 신규 | 메타데이터 모델·검증·version 계약 | core-store |
| C/SyncPlanner.kt, C/SyncRevision.kt | 신규 | bounded 날짜 계획·semantic digest·coverage 판정 | core-store |
| C/DestinationSync.kt | 신규/수정 | source 재읽기·reconciliation·파일 확인 실행 | core-store; manual-drive |
| C/Ports.kt | 수정 | store/destination/tracked confirmation 포트 | core-store; manual-drive |
| A/SyncStateDatabase.kt | 신규 | 별도 DB·atomic CAS·명시적 reset | core-store; manual-drive |
| A/SqliteSyncStateStore.kt | 신규/수정 | 별도 DB·atomic CAS·명시적 reset·generic known-target 호환 조회 | core-store; manual-drive; saf-local |
| A/SyncStateHotJournalRecovery.kt | 신규 | 검증된 DELETE crash rollback·private raw pair 보존 | core-store |
| A/SyncExecutionSession.kt | 신규 | process 공용 gate·pending merge·중단 복구 | core-store; manual-drive; automation-ui |
| CT/SyncStateTest.kt, CT/SyncPlannerTest.kt, CT/SyncRevisionTest.kt, CT/DestinationSyncTest.kt | 신규 | 의미·경계·실패·동시성 baseline | core-store |
| AI/SqliteSyncStateStoreInstrumentedTest.kt, AI/SyncStateProcessRecoveryInstrumentedTest.kt | 신규 | 실제 재열기·schema·commit 중단·generation | core-store |
| AI/SyncExecutionSessionInstrumentedTest.kt | 신규 | 동일path 공용gate·취소·복구·trigger receipt 실제통합 | core-store |
| android/ci/verify-sync-state-process-recovery.sh | 신규 | 기존 history host harness와 분리한 강제종료 검사 | core-store |
| A/BoundDriveAuthorization.kt, A/DriveApiDestination.kt | 신규 | 계정 고정·fresh credential·file reconciliation | manual-drive |
| C/PrivateDriveUploader.kt | 수정 | 현재 metadata SHA-256 reconciliation·typed 확인 배치 | manual-drive |
| A/MainActivity.kt, A/SyncViewModel.kt, A/HealthHomeScreen.kt | 수정 | picker·명시적 consent/continue·destination 수동 경로 | manual-drive; saf-local; automation-ui |
| A/HealthConnectReader.kt | 수정 | current grant/access 조회·background gate·공유 page/sample budget | manual-drive; automation-ui |
| A/AutomaticHealthReader.kt, AT/AutomaticHealthReaderTest.kt | 신규 | 운동 bounded HR·요약 Aggregate·scope와 불명확 BLOCKED | automation-ui |
| AT/BoundDriveAuthorizationTest.kt, AT/DriveApiDestinationTest.kt | 신규 | 계정·ticket/generation·TLS 실패/재조정 | manual-drive |
| CT/PrivateDriveBatchTest.kt | 수정(선행 PR 신설) | typed ack와 기존 callback 호환·privacy 회귀 | manual-drive |
| AI/DestinationConfigurationInstrumentedTest.kt | 신규 | 선택·취소·설정 저장/실패·명시적 복구 | manual-drive; saf-local |
| A/SafTreeConfiguration.kt, A/SafDestination.kt | 신규 | 실제 flags·grant·probe·정확한 overwrite/readback | saf-local |
| AI/SyntheticDocumentsProvider.java, AI/SyntheticProviderStarterTest.kt | 신규 | Java starter 재사용·target UID grant/read 양API 기동·후속 합성 제어 준비 | saf-local |
| android/app/src/androidTest/AndroidManifest.xml | 신규 | test APK 전용 provider 등록 | saf-local |
| AI/SafDestinationInstrumentedTest.kt | 신규 | 짧은 overwrite·중단·중복·회수·reconciliation | saf-local |
| android/app/build.gradle.kts | 수정 | WorkManager runtime/test 의존성 | automation-ui |
| A/SyncWorker.kt, A/SyncWorkScheduler.kt | 신규 | unique periodic/catch-up·gate·bounded retry | automation-ui |
| A/SyncApplication.kt | 신규 | application 단일 session 소유·worker injection | automation-ui |
| android/app/src/main/AndroidManifest.xml | 수정 | background HC·WorkManager 초기화/application | automation-ui |
| AI/WorkManagerStarterInstrumentedTest.kt, AI/SyncWorkerInstrumentedTest.kt | 신규 | test 기반 기동·constraints·취소·worker 복구 | automation-ui |
| A/SyncCoverageUi.kt, AT/SyncCoverageUiTest.kt | 신규 | IO 없는 화면 상태 projection·의미 검사 | automation-ui |
| AI/V1CompletionScreenInstrumentedTest.kt | 신규 | theme·large text·semantics·모든 복구 동작 | automation-ui |
| AI/HealthHomeScreenInstrumentedTest.kt | 수정 | 기존 foundation/상단 layout 회귀 유지 | automation-ui |
| .github/workflows/android-emulator-tests.yml | 수정 | full host gate와 명시 dispatch-only SAF 고정 class 진단 | core-store; saf-local; automation-ui |
| android/ci/run-emulator-validation.sh | 수정 | 기본 full/host 보존, 고정 허용목록의 component 진단만 분리 | manual-drive; saf-local |
| docs/2026-11-05-v1-completion/execution-report.md | 신규 | PR별 실제 명령·head·독립 검토·한계 | 각 PR 순차 |
| docs/2026-11-05-v1-completion/dependency-audit.json | 신규 | 실제 resolved 좌표·OSV 결과와 한계 | automation-ui |
| docs/2026-11-05-v1-completion/completion-roadmap.md, docs/implementation-plan.md, docs/architecture.md, README.md | 수정 | 검증된 개발 상태만 반영 | automation-ui |
| docs/V1/device-acceptance.md, docs/V1/storage-destinations.md | 수정 | 실제 수용 절차·수행/대기 분리 | automation-ui |
| A/ManualDriveSession.kt, A/ManualDriveUi.kt | 신규 | credential 분리 controller·수동 UI composition | manual-drive |
| AT/ManualDriveSessionTest.kt, AI/ManualDriveUiInstrumentedTest.kt, AI/ManualDriveFlowInstrumentedTest.kt | 신규 | stale proof·gate·명시 continuation·화면/lifecycle 합성 경계 | manual-drive |
| CT/PrivateDriveReconciliationTest.kt | 신규 | read-only lookup·all-profile IDs·blocker reopen | manual-drive |
| AI/ManualDriveStoreInstrumentedTest.kt | 신규/수정 | all-profile current/prior·blocker reopen·generic URI 재열기와 Drive 호환 실제SQLite | manual-drive; saf-local |
| AT/GoogleDriveTransportTest.kt, AT/HealthConnectReaderTest.kt | 수정 | about exact allowlist/local TLS·scoped read 회귀 | manual-drive |
| AI/HealthAppInstrumentedTest.kt, AI/HealthHomeScreenInstrumentedTest.kt | 수정 | 실제 Activity bound flow 기대·기존 경계 회귀 보존 | manual-drive |
| A/ManualSafSession.kt | 신규 | 명시 local 동의·선택 snapshot·전경 계속 controller | saf-local T5 |
| A/ManualSafUiState.kt, A/SafManualUi.kt | 신규 | URI 없는 기존 SAF UI metadata/actions 계약과 pure 화면 조각 | saf-local T9 |
| AT/ManualSafSessionTest.kt | 신규 | stale picker·pause/reset·scope 보존·공용 gate 경계 | saf-local |
| AI/SafTreeConfigurationInstrumentedTest.kt | 신규 | actual returned flags·persisted grant·wt probe·restore 차단 | saf-local |
| AI/SafJournalInstrumentedTest.kt | 신규 | 실제 DestinationSync/SQLite 재열기·unknown create·confirmation 실패 | saf-local |
| AI/SafManualFlowInstrumentedTest.kt, AI/SafManualUiInstrumentedTest.kt | 신규 | 실제 Activity local flow·회복·theme/large text/semantics | saf-local |

### 인터페이스

아래는 내부 계약이다. 기존 P0 API와 SyncHistory/HistoryStore는 유지한다. 세부 SQLite schema SQL과 codec field/size 상한은 core-store 하위 plan에서 이 계약에 맞춰 확정한다. Android account/URI binding은 app store adapter 내부에만 있고 core에는 opaque generation/destination handle만 전달한다.

```kotlin
// SyncState — C/SyncState.kt; exact-scope credential/payload-free metadata
@JvmInline value class DestinationGeneration(val value: String)
@JvmInline value class ReadScopeKey(val value: String)
enum class SyncTrigger { MANUAL, SCHEDULED, CATCH_UP }
enum class SyncStrategy { DAILY_SNAPSHOT }
enum class Dataset { DAILY, WORKOUTS, HEART_RATE, SLEEP }
enum class ReadScope { EXPLICIT_DAY, SUMMARY, WORKOUT_SCOPED, BLOCKED }
enum class StateStatus { ABSENT, READY, CORRUPT, UNSUPPORTED, UNAVAILABLE }
enum class DayPhase { PENDING, RUNNING, EMPTY, CONFIRMED, INDETERMINATE, BLOCKED, REMOTE_STALE }
enum class BlockReason { HISTORY_ACCESS_REQUIRED, SOURCE_ACCESS_REQUIRED, SOURCE_READ_FAILED,
    SOURCE_LIMIT_EXCEEDED, DESTINATION_BLOCKED, STORE_UNAVAILABLE, RETRY_EXHAUSTED,
    DESTINATION_TRANSIENT_FAILURE, DESTINATION_RECONCILIATION_REQUIRED, ACCOUNT_AUTHORIZATION_BLOCKED }
enum class FilePhase { PENDING, RUNNING, CONFIRMED, INDETERMINATE, REMOTE_STALE, BLOCKED }
data class RevisionKey(val digest: String, val generatedAt: Instant)
data class FileKey(val generation: DestinationGeneration, val date: LocalDate,
    val scopeKey: ReadScopeKey, val dataset: Dataset, val revision: String)
data class FileConfirmation(val key: FileKey, val targetHandle: String, val confirmedAt: Instant)
data class ScopedSnapshot(val snapshot: HealthSnapshot, val scopeByType: Map<DataType, ReadScope>,
    val blockedTypes: Set<DataType>)
data class SyncConfiguration(val destinationHandle: String, val enrolledDay: LocalDate,
    val automatic: Boolean, val categories: Set<DataType>)
data class ReadAccess(val available: Boolean, val granted: Set<DataType>,
    val scopeByType: Map<DataType, ReadScope>)
data class DatasetState(val dataset: Dataset, val revision: RevisionKey, val phase: FilePhase,
    val populated: Boolean, val targetHandle: String?, val startedAt: Instant?,
    val confirmedAt: Instant?, val priorConfirmation: FileConfirmation?)
data class DayState(val date: LocalDate, val scopeKey: ReadScopeKey,
    val requestedScopes: Map<DataType, ReadScope>, val revision: RevisionKey?, val phase: DayPhase,
    val scopes: Map<DataType, ReadScope>, val checkedAt: Instant?,
    val datasets: Map<Dataset, DatasetState>, val blocker: BlockReason?)
data class CoverageSummary(val checkedThrough: LocalDate?, val uploadedThrough: LocalDate?,
    val scopeKey: ReadScopeKey?, val scopeCheckedThrough: LocalDate?,
    val scopeUploadedThrough: LocalDate?, val excludedTypes: Set<DataType>,
    val scopePendingCount: Long?, val scopeBlockedCount: Long?,
    val lastUploadAt: Instant?, val recentCheckedDays: Set<LocalDate>,
    val recentUploadedDays: Set<LocalDate>)
data class AttemptState(val id: String, val scopeKey: ReadScopeKey,
    val requestedScopes: Map<DataType, ReadScope>, val trigger: SyncTrigger, val scheduledAt: Instant,
    val startedAt: Instant?, val finishedAt: Instant?, val dates: List<LocalDate>,
    val retryBudgetKey: String, val retriesUsed: Int, val pendingTriggers: Set<SyncTrigger>)
data class SyncState(val version: Int, val generation: DestinationGeneration,
    val configuration: SyncConfiguration?, val attempt: AttemptState?,
    val scopeKey: ReadScopeKey?, val requestedScopes: Map<DataType, ReadScope>,
    val days: Map<LocalDate, DayState>, val oldestPendingDays: List<LocalDate>,
    val coverage: CoverageSummary)
data class SyncStateRead(val status: StateStatus, val state: SyncState?)
data class PlannedBatch(val scopeKey: ReadScopeKey?, val dates: List<LocalDate>,
    val blocked: Map<LocalDate, BlockReason>, val remainingPending: Long)
data class DayRevision(val key: RevisionKey, val scopeKey: ReadScopeKey,
    val scopes: Map<DataType, ReadScope>, val files: Map<Dataset, CsvFile>,
    val remoteStale: Set<Dataset>)
data class PlannedFile(val key: FileKey, val file: CsvFile, val byteSha256: String,
    val previousTargetHandle: String?, val createMayHaveSucceeded: Boolean = false)
data class ReconciledFiles(val confirmed: List<FileConfirmation>,
    val pending: List<PlannedFile>, val blocked: Set<FileKey>)
enum class CommitStatus { COMMITTED, STALE, INVALID, UNAVAILABLE }
data class SyncCommit(val status: CommitStatus, val generation: DestinationGeneration?)
data class SyncMutation(val attemptId: String?, val start: AttemptState?,
    val day: DayState?, val confirmation: FileConfirmation?,
    val pendingTriggers: Set<SyncTrigger>?, val finishAt: Instant?)
enum class BatchStatus { COMPLETED, PENDING, BLOCKED, MERGED, STALE, RETRYABLE }
data class BatchOutcome(val status: BatchStatus, val confirmedFiles: Int,
    val coverage: CoverageSummary?)
fun SyncState.isValid(): Boolean
// SyncPlanner — C/SyncPlanner.kt; current state contains enrolled day and durable gaps
object SyncPlanner {
    fun scopeKey(configuration: SyncConfiguration, access: ReadAccess): ReadScopeKey?
    fun plan(state: SyncState, todayInSeoul: LocalDate, access: ReadAccess): PlannedBatch
    fun coverage(state: SyncState, todayInSeoul: LocalDate): CoverageSummary
}
// SyncRevision — C/SyncRevision.kt
object SyncRevision {
    fun identify(snapshot: ScopedSnapshot, previous: DayState?, observedAt: Instant): DayRevision
}
// SyncStateStore — C/Ports.kt; mutations validate generation and durable journal identity
interface SyncStateStore {
    suspend fun load(): SyncStateRead
    suspend fun transact(expected: DestinationGeneration?, mutation: SyncMutation): SyncCommit
    suspend fun reset(expected: DestinationGeneration?): SyncCommit
    suspend fun loadForScope(expected: DestinationGeneration, todayInSeoul: LocalDate,
        access: ReadAccess): SyncStateRead
    suspend fun loadDay(expected: DestinationGeneration, date: LocalDate,
        scopeKey: ReadScopeKey): DayState?
    suspend fun loadUnresolvedCreates(expected: DestinationGeneration, date: LocalDate): Set<Dataset>?
}
// SyncStateStore.loadUnresolvedCreates: current generation/date, ALL scope profiles.
// Returns null only on generation mismatch; unavailable/corrupt/unsupported throws, never empty.
// Unknown null-handle RUNNING/INDETERMINATE/REMOTE_STALE/BLOCKED is physical-file uncertainty.
// SQLite validates every day/dataset of that date using existing generation/date indexes,
// two ordered cursors or equivalent bounded date scan, not whole history or per-file N+1.
// PlannedFile.createMayHaveSucceeded: memory-only projection of durable unresolved null-handle write.
// Reread/new revision must retain that unresolved state, including through REMOTE_STALE.
// Destination may adopt a freshly proven target or update a safely resolved existing target;
// when true and no safe existing target is proven, never POST/create: reconciliation required.
// StorageDestination — C/Ports.kt; CsvFile is memory-only; reconciliation never blind-creates
interface StorageDestination {
    suspend fun reconcile(files: List<PlannedFile>): ReconciledFiles
    suspend fun writePending(files: List<PlannedFile>, onConfirmed: suspend (FileConfirmation) -> Unit)
}
// DriveUploader — C/Ports.kt; additive API after actual performance PR merge
interface DriveUploader {
    suspend fun upsert(file: CsvFile): String
    suspend fun upsertBatch(files: List<CsvFile>, onConfirmed: suspend (String) -> Unit)
    suspend fun upsertTracked(files: List<CsvFile>, onConfirmed: suspend (CsvFile, String) -> Unit)
}
// SyncExecutionSession — A/SyncExecutionSession.kt; process singleton, no Activity ownership
class SyncExecutionSession {
    suspend fun detectPending(): CoverageSummary
    suspend fun run(trigger: SyncTrigger, generation: DestinationGeneration): BatchOutcome
    suspend fun invalidate(generation: DestinationGeneration): SyncCommit
}
```

`SyncStateRead`는 ABSENT/READY/CORRUPT/UNSUPPORTED/UNAVAILABLE을 구분한다. ABSENT와 error status는 state=null이다. READY의 configuration=null은 fresh generation을 가진 내구성 reset tombstone이며 ABSENT와 다르다. tombstone은 automatic off, attempt/scopeKey=null, requestedScopes/days/oldestPendingDays empty, through dates=null, scope counters=0이다. 구성은 있지만 load()가 scope를 선택하지 않은 경우 candidate days는 empty이고 scope counters=null이므로 gap 0으로 표시하지 않는다. 새 구성 expected=null은 ABSENT에서만 허용하며 READY tombstone의 재구성은 그 generation CAS를 요구한다. load/reopen은 데이터를 덮지 않고 invalid schema/reset-marker는 admission을 차단한다.

`ReadScopeKey`는 canonical big-endian 4-byte length-prefixed UTF-8으로 "sync-scope/1"과 정렬된 DataType name/ReadScope name을 인코딩한 lowercase SHA-256 hex64다. active profile은 configured categories∩현재 granted 중 알려진 non-BLOCKED scope 항목이며 configured-active는 excludedTypes로 유지한다. unknown/empty active profile에는 scopeKey가 없고 완료 empty-profile row를 만들지 않는다. DayState/AttemptState의 requestedScopes는 admission 때 고정한 요청이며 DayState.scopes/ScopedSnapshot.scopeByType은 실제 read 결과다. 실행 중 scope가 실패/BLOCKED로 바뀌어도 요청 key 아래 미확인으로 남기며 더 작은 성공 profile로 바꿔치기하지 않는다. FileKey/DayRevision/attempt와 confirmation은 정확한 scopeKey까지 일치해야 한다.

`loadForScope(expected,today,access)`는 같은 읽기 snapshot에서 generation/config/access를 확인하여 scope filtering → 해당 profile 완료일 제외 → floor와 복구 blocker 판단 → oldest 최대7 LIMIT 순서로 후보를 고른다. full-five 미완료 여부로 partial/automatic 후보를 제한하지 않는다. state.days는 exact profile의 oldest7+recent2(최대9일)만 담고 등록일~어제 상태는 streaming/query로 검사한다. recent eligible 두 날짜를 먼저 예약한 뒤 oldest eligible gaps로 합계 최대7 unique ascending 날짜를 만든다. 완료일은 checkedAt과 요청/실제 scope 일치, EMPTY 또는 intended datasets 전부 CONFIRMED로 판정하며 pending/indeterminate/stale 파일이 있으면 gap이다. blocker와 아직 행이 없는 날짜도 pending/count에 보존한다. `loadDay(expected,date,scopeKey)`의 null은 exact row 부재만 의미하고 stale generation/DB 오류는 예외다. planner의 재계산 scopeKey와 state.scopeKey가 다르면 admission을 중단한다.

`CoverageSummary`의 scopeCheckedThrough는 현재 exact profile에서 등록일부터 연속 checked(EMPTY 포함)인 끝날이며 scopeUploadedThrough는 EMPTY/unknown/stale 없이 실제 파일 확인이 연속된 끝날이다. scopePendingCount/scopeBlockedCount와 recent sets도 선택 profile 기준이다. checkedThrough/uploadedThrough는 별도로 5종 모두 EXPLICIT_DAY인 단일 profile만 집계하며 profile들의 합집합으로 만들지 않는다. full-five through=null이어도 partial/automatic scope에서 이미 끝낸 오래된 날짜가 다음 oldest7 slot을 다시 차지하지 않는다. lastUploadAt은 어느 profile이든 실제 확인의 마지막 시각이며 전체 완료 증거가 아니다.

store transaction은 generation/attempt/scope/revision/current journal을 검사하고 settings·pending·day/file confirmation·retry budget을 한 commit 경계에서 바꾼다. DB는 noBackupFilesDir/sync-state.db의 별도 version1 schema(config/generation/day/dataset/attempt)이며 foundation history DB를 열거나 migration하지 않는다. day PK=(generation,date,scopeKey), dataset PK=(generation,date,scopeKey,dataset)로 같은 날짜의 profile을 공존시킨다. request JSON에서 key를 재계산하여 일치 확인하고 실제 scopes와 별도 저장한다. hash/enum/시각/크기·관계 validation을 적용하며 예상 밖 값은 fail closed한다. codec은 행별 크기 상한·필드 allowlist를 갖고 모든 날짜를 한 JSON으로 읽지 않는다. reset은 새 tombstone generation·자동 off·빈 coverage를 원자 commit하며 unknown/corrupt 교체는 별도 확인·marker/재열기 검증 경계를 사용한다. 실패를 ABSENT나 reset 성공으로 쓰지 않는다.

원격 CSV 경로는 기존 dataset/date.csv이므로 profile rows가 공존해도 같은 remote bytes를 공유한다. 실제 writePending 직전 RUNNING journal transaction은 같은 generation/date/dataset의 다른 scope 파일을 INDETERMINATE로 바꾸고 priorConfirmation에 과거 확인을 보존한다. 다른 scope의 requestedScopes/revision/checkedAt은 유지하지만 그 profile의 uploaded-current/through는 멈춘다. remote write 실패/불명확에도 무효화를 유지하며 이후 해당 profile의 fresh source reread+현재 target reconciliation으로만 복원한다. 처음 PENDING revision commit이나 건강한 현재 파일의 무쓰기 reconciliation에는 cross-scope 무효화를 적용하지 않는다. scoped overwrite는 source deletion/REMOTE_STALE의 증거가 아니다.

이전 목적지 결과 보존을 위해 immutable generation 부모 행과 config singleton의 active_generation FK를 분리한다. day/attempt는 generation(id), dataset은 exact-scope day를 참조한다. 구성 변경은 새 generation 삽입 후 active pointer CAS를 변경하며 이전 부모/결과를 삭제하지 않는다. active private binding만 교체하고 이전 계정 credential/binding은 archive하지 않는다. 모든 현재 조회·복구·콜백 CAS는 active pointer를 기준으로 하므로 이전 결과가 새 coverage로 승격되지 않는다. 명시 reset은 새 tombstone 생성·pointer 변경 뒤 dataset/day/attempt와 이전 generation을 자식 우선 순서로 정리하며 FK 비활성화·지연·destructive migration을 사용하지 않는다. 실제 SQLite foreign_key_check와 reopen/switch/reset 경계는 core-store T4에서 검증한다.

`upsertTracked`의 default는 순차 upsert로 각 file/ID를 짝지어 callback한다. PrivateDriveUploader는 PR #15 배치 구현의 동일 예약/validation/mutex/최대2 concurrency/직렬 callback 경계를 공유하여 typed pair를 전달한다. 기존 upsertBatch는 ID projection으로 보존하고 positional callback 추론이나 ID만으로 dataset을 추측하지 않는다. callback은 file의 pending journal을 commit한 뒤 완료된다. callback 실패·취소 후 자식 종료와 gate 반환 규칙을 유지한다.

Drive reconciliation은 매 실행 root/dataset/file의 현재 private/owned/My Drive/marker/name/parent/identity/완전한 조회를 검증한다. 동일 semantic revision도 과거 성공만으로 skip하지 않는다. 현재 target ID가 같고 metadata sha256Checksum이 현재 exact-scope revision의 고정 generated_at으로 재생성한 실제 UTF-8 CSV bytes의 SHA-256과 같을 때에만 확인을 재사용한다. semantic revision digest와 remote byte digest는 목적이 달라 혼용하지 않는다. Drive v3 공식 discovery File schema의 sha256Checksum은 output-only이며 Drive에 content가 있는 파일에서 가용할 때 제공된다(2026-11-05 https://www.googleapis.com/discovery/v1/apis/drive/v3/rest 직접 조회). 기존 list/write fields에 이 optional field를 추가하고 missing/invalid/mismatch는 현재 확인 재사용을 거절하여 기존 검증된 deterministic upsert를 수행한다. MD5로 보안 확인을 대신하거나 전 파일 media GET을 추가하지 않는다. 기존 GoogleDriveTransport의 metadata 1MiB cap/JSON Accept/no redirect/no retry는 유지한다.

삭제/이동/공유/중복된 기존 target은 BLOCKED로 명시적 복구를 요구하며 suffix나 새 create로 숨기지 않는다. 미확인 create는 lookup과 현재 checksum 대조 후 기존 파일을 채택한다. exact safe existing target의 checksum이 없으면 새 create 대신 같은 ID에 검증된 PATCH로 현재 내용을 확인하고, 명백한 부재가 확인된 경우에만 신규 create를 허용한다. 불명확 조회는 재전송하지 않는다. 현재 metadata+checksum으로 건강함이 확인된 파일은 쓰지 않는다. scope가 BLOCKED이거나 좁아진 read는 원본 dataset 소멸의 증거가 아니다. populated→empty는 REMOTE_STALE로 남기며 자동 삭제하지 않는다.

Core T1의 validation 진입점은 같은 SyncState.kt의 public extension `fun SyncState.isValid(): Boolean`이며 IO 없이 모델/관계 불변식을 검사한다. invalid 입력은 false이고 constructor 예외를 새 계약으로 만들지 않는다. private helper 추출과 안전한 오류 문구는 구현 선택이다. `SyncPlanner.coverage`는 SQL snapshot이 계산한 전 기간 through/count/lastUploadAt을 신뢰하는 view이며 bounded days로 이를 재계산하지 않는다. 현재 exact profile의 recent2 flags만 bounded rows에서 계산할 수 있다. `.days`는 전체 이력이 아니고 수십 년 range를 유닛 helper가 순회하지 않는다. `loadForScope`가 고른 scope key와 현재 access key가 다르면 선택 날짜를 반환하지 않고 재조회 필요/보수적 미확인으로 남긴다. null profile은 complete empty-profile을 만들지 않는다. `PlannedBatch.remainingPending`은 batch 예약으로 차감하지 않는 미완료 수(선택된 pending 포함)이며 key가 맞는 store scopePendingCount를 사용하고 알려지지 않으면 등록~어제 전체 날짜를 보수적 미확인 수로 유지한다. 성공 확인 전 gap 감소를 가정하지 않는다.

### ManualDriveContracts (manual-drive 실행 전 공통 확정)

현재 source 기준은 PR #16 merge `4b98aee1119ded1f1c6553e92198ac004d30fa87`이다. core-store 완료는 에픽 전체 완료가 아니다. 아래 설계는 기존 승인 범위의 실행 구체화이며 하위 plan은 root 검토 전 draft다.

```kotlin
// Ports.kt: P0 API 보존, additive typed callback (default는 순차 upsert).
interface DriveUploader {
    suspend fun upsertTracked(files: List<CsvFile>, onConfirmed: suspend (CsvFile, String) -> Unit)
    // 기존 upsert/upsertBatch 유지
}
// PrivateDriveUploader의 실제 member API로 구현; extension-only dispatch 금지.
// V1 전용 읽기 reconciliation과 journal 이후 쓰기 API; legacy upsert와 경계 공유.
suspend fun PrivateDriveUploader.reconcileTracked(files: List<PlannedFile>, knownTargets: Map<Dataset, Set<String>>): ReconciledFiles
suspend fun PrivateDriveUploader.writeTrackedPending(files: List<PlannedFile>, onConfirmed: suspend (CsvFile, String) -> Unit)
// app-only, current generation/date의 모든 profile targetHandle + priorConfirmation target.
suspend fun SqliteSyncStateStore.loadKnownDriveTargets(expected: DestinationGeneration, date: LocalDate): Map<Dataset, Set<String>>
// app-only admin: fresh proof + 명시 continue 후 gate owner만 호출; 기존 schema 유지.
suspend fun SqliteSyncStateStore.reopenManualBlockers(expected: DestinationGeneration): SyncCommit
// engine 인자 nullable; 기존 run 유지. production은 null, prepare 누락 시 fail closed.
suspend fun SyncExecutionSession.runPrepared(trigger: SyncTrigger, generation: DestinationGeneration, prepare: suspend () -> DestinationSync): BatchOutcome
// BoundDriveAuthorization.kt: Account 객체 대신 plain validated name/type 사용.
// credential은 private 비-data class 메모리, toString/StateFlow/SavedState/DB로 전달 금지.
// ManualDriveSession.kt: binding/ticket/generation/foreground를 before/after proof에 재확인.
```

`upsertTracked`는 DriveUploader member를 뜻하며 기존 `upsertBatch` ID projection을 보존한다. V1 `writeTrackedPending`은 reconcile에서 검증된 safe target에만 PATCH하며 신규 create도 pending RUNNING journal 뒤에만 수행한다. raw token이 closure에서 필요한 동안만 존재하고 session registry에는 token-bound engine을 저장하지 않는다. 기존 engine constructor를 nullable로 바꾸되 legacy tests의 non-null 주입은 유지한다. busy 호출은 prepare/credential 소비 없이 trigger만 병합한다. prepare는 gate owner 등록·중단 복구 뒤, access/planner/raw read 전에 딱 한 번 실행한다. production prepare는 같은 store/reader로 local DestinationSync를 만들고 fresh proof 뒤 blocker reopen을 수행한다. prepare 실패는 raw read/remote write를 시작하지 않으며 cleanup/gate 반환은 기존 경계를 보존한다.

HealthConnectReader는 기존 HealthReader와 ScopedHealthReader를 함께 구현한다. read(date)는 현재 의미를 보존하고 private filterable read가 실제 요청∩현재 grant category만 조회한다. readScoped는 전체 read 후 필터링하지 않는다. MANUAL은 현재 grant의 EXPLICIT_DAY만 노출하고 SCHEDULED/CATCH_UP은 이번 PR에서 BLOCKED다. scope 요청 후 grant 철회는 BLOCKED/미확인이며 EMPTY가 아니다. page-size1000/page100/HR sample100000 및 interval overlap/aggregate/CSV 계약은 유지한다. 한정된 SourceReadException(reason=SOURCE_LIMIT_EXCEEDED 또는 SOURCE_ACCESS_REQUIRED)을 Ports에 추가하고 DestinationSync는 이 타입만 고정 reason으로 매핑하며 generic 오류는 SOURCE_READ_FAILED를 유지한다.

AccountPicker는 항상 표시하고 허용 타입은 com.google만 둔다. RESULT_OK/current ticket/non-null extras/KEY_ACCOUNT_NAME(authAccount)/KEY_ACCOUNT_TYPE(accountType), UTF-8 encode 가능·1..1024bytes·ISO control 없음·type 정확일치를 검사한다. 모든 SDK authorize는 setAccount + drive.file만 쓴다. AuthorizationResult는 identity proof가 아니다. 동일 발급 token으로 body 없는 exact GET `/drive/v3/about?fields=user(emailAddress,me)`만 추가 허용하며 user.emailAddress가 선택 name과 정확히 같고 me=true일 때만 승인한다(별칭/대소문자/점/plus 정규화 없음). missing/mismatch/error는 source raw read 전에 차단한다. SDK Account 생성은 Activity bridge에만 둔다. 외부 consent 복귀는 업로드를 자동 실행하지 않고 별도 continue를 요구한다. 기기/실계정 성공은 pending이다.

Drive reconcile는 read-only다. appFolder()는 create 가능하므로 호출하지 않는다. root→dataset→file을 fresh 완전조회하여 canonical marker/name/parent/private/owned/My Drive 및 known ID를 검증한다. 모든 current-generation profile의 현재/prior ID를 날짜별 bounded validated scan으로 조회한다. fresh 완전 unique canonical path lookup의 ID가 모든 known IDs와 같으면 identity/membership 증거로 충분하며 별도 ID GET을 강제하지 않는다. conflicting IDs·missing/moved/deleted known targets·중복·불완전/unsafe 조회는 blocked다. 다른 generation/account의 archived ID를 현재 identity로 추정하지 않는다. checksum exact match만 이전 확인/adoption을 재사용한다. checksum absent/malformed/mismatch는 reuse를 거절하지만 safe existing target의 같은-ID PATCH를 허용한다. fresh 2xx ACK는 전체 metadata+동일ID 검증으로 확인하며 checksum이 있으면 exact byte digest도 맞아야 한다; absent checksum은 기존 승인 write ACK 계약을 유지한다. metadata 재사용 조건과 fresh write ACK를 혼동하지 않는다.

reconcile pending은 입력 PlannedFile과 값이 정확히 같아야 한다(현재 engine의 intended equality guard). 발견한 safe ID를 pending.copy로 주입하지 않는다. uploader 인스턴스의 private 실행 메모리에 exact FileKey/byte digest/known-ID 검증 결과를 묶은 **단 하나의 single-use prepared batch**를 둔다. 같은 generation/date/scope/revision/bytes의 pending만 RUNNING 뒤 소비하고 성공/실패/새 reconcile에서 clear한다. 다른 batch/run cache는 금지한다. write 직전 입력 일치만 검사하여 같은 batch에서 방금 검증한 root/parents/file identity를 재사용할 수 있다. healthy metadata 파일은 write0, changed3은 중복 전체 lookup 없이 PATCH3이며 최대2와 직렬 callback을 유지한다. memory reservation이 없거나 바뀌면 fail closed하며 registry/DB에 저장하지 않는다. confirmation 시간은 injectable Clock(기본 systemUTC, 기존 constructor 호환)으로 제공한다.

fresh root/dataset/file create는 writePending 안 RUNNING 이후에만 허용한다. createMayHaveSucceeded=true에서 safe existing proof가 없으면 root/dataset/file 부재도 RECONCILIATION_REQUIRED이며 POST하지 않는다. root/dataset POST 실패/응답불명도 같은 file journal의 uncertainty로 보존한다. LOOKUP/PATCH의 IOException/429/500/502/503/504만 transient, 401/403은 account blocked, ambiguous POST·응답형식/identity 불명은 reconciliation required, 나머지는 permanent destination blocked다. transport 자체 retry/redirect/timeout/cap 정책은 유지한다. 최대2쓰기/직렬 callback/root-once-perbatch/structured cancellation과 P0 동작을 유지한다.

`reopenManualBlockers`는 finished/no-current attempt에서만 generation CAS와 기존 validated transaction으로 eligible day blocker(DESTINATION_BLOCKED, DESTINATION_RECONCILIATION_REQUIRED, ACCOUNT_AUTHORIZATION_BLOCKED, RETRY_EXHAUSTED)를 제거하여 계획 재진입을 허용한다. DayPhase.BLOCKED만 PENDING으로 전환하고 REMOTE_STALE/다른 phase는 유지한다(현재 DayState.isValid는 BLOCKED+null blocker를 거부한다). date/profile/revision/file phase/target/prior/REMOTE_STALE/unknown create는 그대로 보존하고 attempt를 변조하지 않는다. generic retry가 REMOTE_STALE를 지우지 않는다. invalidate/new generation으로 uncertainty를 버리는 복구를 금지한다. 명시 account 변경/config reset만 기존 configure/reset 경계와 새 동의를 쓴다. schema/column/format 추가는 없다.

ManualDriveSession은 작은 app controller이며 credential은 UiState 밖에 둔다. ManualDriveUi는 optional state/actions로 HealthHomeScreen에 합성하여 기존 pure component defaults를 보존한다. 실제 MainActivity는 항상 새 경로를 전달하고 기존 unbound authorize/upload를 노출하지 않는다. foundation 이력은 이전 이력/read-only로 표시하고 V1 coverage로 승격하지 않는다. 기존 이력 삭제와 V1 config/coverage reset은 별도 확인·범위·durable 완료를 사용한다. PR14 title/date safe insets를 유지한다. 자동 기능/worker/SAF는 후속이며 임의 profiling을 도입하지 않는다.

확인 근거: 현재 Ports에는 upsertTracked가 아직 없고 HealthConnectReader는 HealthReader만 구현한다. SyncExecutionSession engine은 constructor field이며 gate 획득 전에는 merge만 한다. SqliteSyncStateStore에 configure/readBinding/invalidate/reset 및 all-profile uncertainty scan이 있지만 known-ID map/admin reopen은 없다. GoogleDriveTransport는 files path만 허용한다(현 source 직접 확인). SDK21.4/setAccount 및 AccountPicker constants/about scope/identity와 checksum optional 계약은 `/workspace/tooling/v1-manual-api-inventory.md`, `v1-manual-official-contracts.md`의 공식 URL/설치 API 확인값이다. 해당 메모의 후보 정책/이전 verdict는 승인 계약이 아니며 본 결정이 우선한다. 실제 OAuth/Galaxy/별칭 호환은 미확인이다.

결정 로직 위치: BoundDriveAuthorization.kt의 IO 없는 companion 함수에 picker/result/ticket/proof 판정, PrivateDriveUploader.kt의 IO 없는 metadata/checksum/실패분류 함수에 target 판정, ManualDriveUi.kt의 pure projection에 current status/허용 동작을 둔다. SQL validation은 기존 store decode/transaction 경계를 재사용한다. 별도 validator framework/새 sealed 패턴/새 dependency를 도입하지 않는다. ADR007은 실제 conformance 전 Proposed를 유지한다.

### SafLocalContracts (saf-local 실행 전 공통 확정)

실제 PR #17 merge/rebase 기준은 `57da82514e8483f5f32f2ad34ae0111c7f1dc431`이다. 부모 통합 commit/child revision 고정·fresh-context 계획 검토·승인 상태 확인 뒤에만 구현한다. SAF child §2 전체와 해당 T를 실행자 브리프에 함께 제공한다. 기존 Store/Ports/SyncExecutionSession 계약은 그대로 재사용한다.

```kotlin
// Existing app-only store contracts; no schema change.
internal data class PrivateDestinationBinding(val kind: String,
    val accountName: String? = null, val accountType: String? = null, val treeUri: String? = null)
suspend fun SqliteSyncStateStore.configure(expected: DestinationGeneration?,
    configuration: SyncConfiguration, binding: PrivateDestinationBinding): SyncCommit
suspend fun SqliteSyncStateStore.readBinding(expected: DestinationGeneration): PrivateDestinationBinding?
suspend fun SqliteSyncStateStore.reopenManualBlockers(expected: DestinationGeneration): SyncCommit
suspend fun SqliteSyncStateStore.reset(expected: DestinationGeneration?): SyncCommit
// New member; retain loadKnownDriveTargets as delegate, not extension-only dispatch.
suspend fun SqliteSyncStateStore.loadKnownTargets(expected: DestinationGeneration,
    date: LocalDate): Map<Dataset, Set<String>>
// Existing gate: owner calls prepare only after recovery/config admission; busy only merges.
suspend fun SyncExecutionSession.runPrepared(trigger: SyncTrigger,
    generation: DestinationGeneration, prepare: suspend () -> DestinationSync): BatchOutcome
```

이 코드블록은 member signature를 문서화하며 extension 구현을 지시하지 않는다. `readBinding` stale는 throw, null은 binding 부재다. LOCAL은 accountName/accountType=null 및 private treeUri UTF-8 1..4096bytes이고 configure는 valid 전체 categories/KST 어제·새 generation·automatic=false를 사용한다. `reopenManualBlockers`는 finished/no-current attempt의 eligible day blocker만 해제하고 file phase/target/prior/unknown create/REMOTE_STALE를 보존한다. background permission/WorkManager/새 persistent capability 필드는 SAF에서 도입하지 않는다.

| 이름 | 내부 계약 |
|---|---|
| SafKnownTargets | SqliteSyncStateStore의 신규 `suspend fun loadKnownTargets(expected: DestinationGeneration, date: LocalDate): Map<Dataset, Set<String>>`. 기존 `loadKnownDriveTargets`는 호환 delegate. current generation/date 모든 profile의 current/prior handle을 기존 두 bounded validated index scan으로 읽고 stale/오류는 throw. T2는 lossless SQLite 문자열 왕복만, 실제 resolver identity는 T4. |
| SafTreeProof | SafTreeConfiguration.kt internal 메모리 값 `treeUri: Uri`, `writeMode: String`; 성공한 설정 adapter만 생성하며 redacted 표현을 사용한다. UiState/SavedState/진단에 URI/proof 없음. background=false. |
| SafTreeConfiguration | `suspend fun select(uri: Uri?, returnedFlags: Int, privacyConsent: Boolean): SafTreeProof`, `suspend fun restore(treeUri: String, privacyConsent: Boolean): SafTreeProof`. grant/persist/probe Android IO를 소유하고 IO 없는 companion 판정을 분리한다. CancellationException 재던짐; 나머지는 원시 cause 없는 DestinationException allowlist. |
| SafDestination | `SafDestination(resolver: ContentResolver, proof: SafTreeProof, generation: DestinationGeneration, knownTargets: suspend (DestinationGeneration, LocalDate) -> Map<Dataset, Set<String>>, clock: Clock)`가 StorageDestination 구현. production은 store::loadKnownTargets 전달. |
| ManualSafSession | `state: StateFlow<ManualSafState>`, `setForeground(Boolean)`, `setPrivacyConsent(Boolean)`, `setCategories(Set<DataType>)`, `refresh()`, `beginTreeSelection(): Long?`, `acceptTree(requestId: Long, uri: Uri?, returnedFlags: Int)`, `continueExport()`, `retry()`, `reset(expected: DestinationGeneration)`. suspend IO는 store/reader/configure/reopen/runPrepared test ports로 격리하며 credential/account API 없음. |
| ManualSafState | A/ManualSafUiState.kt(T9)의 exact Kotlin 계약. phase UNCONFIGURED/SELECTION_REQUIRED/READY/RUNNING/BLOCKED, generation/configured/foreground/privacyConsent/intendedCategories/categories/grantedCategories/enrolledDay/coverage/outcome만 공개. intended는 UI 전체 선택, categories는 전체 저장 구성, granted는 현재 허용 집합. URI/provider 원문/proof 없음. |
| SafManualActions | A/ManualSafUiState.kt(T9)의 아래 exact callback 계약. T5는 state/phase/actions를 소비하며 중복 선언하지 않는다. resetConfiguration은 확인한 expected generation을 전달한다. |
| SafManualUi | pure `SafManualUi(state: ManualSafState, actions: SafManualActions)`; consent/tree/continue/retry/reset 확인 동작과 URI 없는 고정 local 이름. 기존 HealthHomeScreen optional 인자/default를 보존하고 SAF optional을 뒤에 추가. |
| SafGrantProtocol | production authority는 `com.android.externalstorage.documents`만; EXTRA_LOCAL_ONLY는 hint. 결과 actual READ/WRITE/PERSISTABLE 요구, READ\|WRITE 교집합만 persist하고 같은 tree persisted read/write·실제 접근 재확인. 명시 동의 뒤 health read 전 unique synthetic probe create→긴 bytes→짧은 wt bytes→close→expected-length+1 exact readback→직접 만든 probe만 cleanup. 오류/cleanup 실패 BLOCKED. test authority는 test constructor 주입만, cloud/background disabled. restore UI는 metadata-only. |
| SafTargetProtocol | 선택 tree가 root. fixed dataset/date relativePath와 FileKey 대조. targetHandle은 DocumentsContract.buildDocumentUriUsingTree(treeUri, opaqueDocumentId).toString() encoded URI. raw ID 저장/수동 인코딩·ID 형식 해석 금지. fresh complete tree/dataset/file 열거·known/current/prior URI identity·MIME/write/partial/loading 검증. null/불완전/duplicate/moved/deleted/불명 identity는 absence가 아니다. reconcile는 read-only; pending은 원래 PlannedFile과 값이 같으며 exact input/proof에 묶인 단 하나의 single-use reservation만 사용. durable RUNNING 뒤 writePending에서만 directory/file create·같은 URI wt overwrite. unknown create+safe existing 없음은 create 금지. close+fresh identity+정확한 expected-length+1 readback 뒤 serial confirmation commit. 취소·local callback 실패를 provider transient로 치환하지 않으며 unknown journal/prior/REMOTE_STALE 보존. |
| SafUiProtocol | setCategories 방어적 copy, generation/epoch/전체 intended snapshot을 picker에 고정. 입력 변경/stale/pause/consent 철회/reset은 proof·지연 결과 무효. current grant∩intended=0이면 configure/read/write0; 일부 grant는 전체 intended 구성을 보존하고 기존 partial scope/coverage 사용. acceptTree는 snapshot 일치일 때 LOCAL private binding, destinationHandle=local-tree/KST 어제/automatic=false로 configure할 뿐 export 없음. restore metadata는 진행 중 UI 편집을 덮지 않음. explicit continue만 intended==stored==proof 범위 및 fresh grant/probe를 공용 runPrepared gate 전후 확인하여 실행. retry는 gate owner의 reopenManualBlockers이며 reset/new generation으로 uncertainty 우회 금지. 단일 destination routing과 raw Intent result flags, 외부 request busy/lifecycle 경계를 연결하고 Drive/history/profiling/insets 보존. |
| `SafActivityIntegration` | T6의 기존 네 파일만 사용한다. MainActivity.kt 안에 internal ManualSafBridge(isBusy():Boolean, selectTree(requestId:Long, result:(Uri?,Int)->Unit))와 instance viewModelFactory:ViewModelProvider.Factory?/safTreeIntent:()->Intent를 둔다. 생산 Intent는 ACTION_OPEN_DOCUMENT_TREE+READ/WRITE/PERSISTABLE/PREFIX+EXTRA_LOCAL_ONLY이며 StartActivityForResult의 RESULT_OK/data?.data/data?.flags를 그대로 전달한다(nonOK→null, 없는flags→0). 요청flags를결과에OR하지않는다. test picker만 explicit GrantActivity Intent로주입하고result용launch에는NEW_TASK/MULTIPLE_TASK를넣지않는다. Activityinstance seam은onActivityPreCreated에주입해실제onCreate전동일factory를쓴다. syntheticallowlist를exportedIntent/staticglobal로선택하지않는다. Drive/SAF request는같은busy경계를쓰고SAF결과buffer는Drivephase/privateFolderConsent와독립이며ActivityRESUMED+SAFforeground뒤만delivery한다. launchfailure는null로현재ticket을소모하고stale결과는새slot을소모하지않는다. |
| `ManualDestinationDependencies` | T6의 SyncViewModel.kt nested class: store:SqliteSyncStateStore, reader:ScopedHealthReader, execution:SyncExecutionSession, configuration:SafTreeConfiguration, clock:Clock. 새primary `(application:Application, dependencies:ManualDestinationDependencies?, sessionFactory:기존4profilingcallback factory?)`, 기존public1arg와기존internal2arg `(application, sessionFactory)` overload를그대로보존한다. 기존 trailing-lambda호출의마지막인자종류를바꾸지않는다. V1Drive/SAF는동일injectedstore/scopedreader/execution을공유하고default도같은canonicalgate를쓴다. legacyreader의availability/grantedPermissions/read는기존HealthConnectReader를보존하고새ScopedHealthReader로해당API를가정하지않는다. 실제T6테스트는실제targetSQLite/execution/resolver+합성scopedreader/forTestingconfiguration을주입한다. |
| `SafDestinationRouting` | T6 SyncViewModel.kt 안 internal ManualDestination(DRIVE,LOCAL), 편집중선택draft와strictprivatebinding의실제committedkind를구분한다. 모드클릭은configure/reset/export를하지않고현재generation/coverage를보존한다. 실제binding과다른form에는다른목적지coverage/success를구성된결과로표시하지않고expectedgeneration만CAS에보존하며기존활성결과는읽기전용으로남긴다. unknownkind에서Drive자동fallback금지. Home optional인자는기존todayInSeoul뒤 safState:ManualSafState?=null,safActions:SafManualActions?=null,selectedDestination:ManualDestination=DRIVE,changeDestination:((ManualDestination)->Unit)?=null 순으로추가한다. hasV1은Drive또는SAF의완전한state/actions쌍으로판정해LOCAL에서구legacyDriveupload가다시나오지않게한다. Drive/history/profiling/insets·기존인자순서·기본값은보존한다. |

기존 LOCAL binding/configure/readBinding/reset 및 noBackup DB를 그대로 사용한다. encoded URI가 기존 UTF-8 1..4096bytes/whitespace·control 금지 validator에 맞지 않으면 BLOCKED이며 schema/column/format/Drive ID regex 변경 없음. 다섯 category/네 CSV dataset/EMPTY/units 계약 유지. SAF에 Drive profiling factory·credential 경계를 복제하지 않고 기존 ManualDriveSession primary/secondary optional profiling callbacks, ManualDrivePorts.profiledDestination default delegate, SyncViewModel sessionFactory 네 callback과 public Application constructor를 보존한다.

T1은 starter 25739c54de7efb37607bf5cb6af70d9441a367f4의 세 test 파일을 rebase 뒤 비교하여 도입하고 actual target UID grant/read를 API35/36 각각 통과해야 한다. 기존 GrantActivity의 ResultReceiver는 raw picker result가 아니므로 T1 같은 세 파일에서 grant 명령의 실제 URI/flags를 setResult로 전달하는 test-only 경계를 보완한다. 후속 ID/operation observation/실패 fixture도 T1 소유 provider 안에서 준비한다. provider와 모든 nested Activity/fixture는 Android framework·Java 표준 API·자체 Java 타입만 사용하고 Kotlin/AndroidX runtime 의존을 도입하지 않는다. 별도 provider UID는 tested APK Kotlin runtime을 공유하지 않으므로 Java 파일을 Kotlin으로 되돌리지 않는다. test URI/원시 provider 오류를 artifact로 출력하지 않는다. early starter run `43433735126`은 API36 actual 1 testcase PASS, API35 두 번째 REVOKE 제어 응답 timeout으로 양API gate 미완료다(root 전달 실제 결과). source 존재/컴파일/한 API 통과를 T1 완료로 취급하지 않는다. Galaxy/local provider/reboot/TalkBack/P0 수용은 수행 전 pending이며 cloud/background=false다.

이 결정은 [ADR-011](../decisions/ADR-011-2026-11-13-2026-11-05-v1-completion-foreground-local-saf-proof.md) Proposed에 기록했다. Accepted ADR-009의 기존 noBackup/exact-scope/generation/reset 모델을 재사용하며 ADR-009를 대체하지 않는다. SAF 적합성/Android 검증 전 Accepted가 아니다.

### ManualSafState / SafManualActions / SafManualUi

```kotlin
// ManualSafState — A/ManualSafUiState.kt; package com.ny.my.health
import com.ny.my.health.core.BatchStatus
import com.ny.my.health.core.CoverageSummary
import com.ny.my.health.core.DataType
import com.ny.my.health.core.DestinationGeneration
import java.time.LocalDate

internal enum class ManualSafPhase { UNCONFIGURED, SELECTION_REQUIRED, READY, RUNNING, BLOCKED }

internal data class ManualSafState(
    val phase: ManualSafPhase = ManualSafPhase.UNCONFIGURED,
    val generation: DestinationGeneration? = null,
    val configured: Boolean = false,
    val foreground: Boolean = false,
    val privacyConsent: Boolean = false,
    val intendedCategories: Set<DataType> = emptySet(),
    val categories: Set<DataType> = emptySet(),
    val grantedCategories: Set<DataType> = emptySet(),
    val enrolledDay: LocalDate? = null,
    val coverage: CoverageSummary? = null,
    val outcome: BatchStatus? = null,
)

```

```kotlin
// SafManualActions — A/ManualSafUiState.kt
internal data class SafManualActions(
    val changeConsent: (Boolean) -> Unit,
    val changeCategories: (Set<DataType>) -> Unit,
    val requestHealthConsent: () -> Unit,
    val refreshGrants: () -> Unit,
    val selectTree: () -> Unit,
    val continueManual: () -> Unit,
    val retry: () -> Unit,
    val resetConfiguration: (DestinationGeneration) -> Unit,
)

```

```kotlin
// SafManualUi — A/SafManualUi.kt; pure two-argument component
@Composable
internal fun SafManualUi(state: ManualSafState, actions: SafManualActions)
```

세 category Set의 초기값은 모두 emptySet이며 intendedCategories는 전체 UI 선택, categories는 전체 저장 범위, grantedCategories는 현재 허용 집합이다. T5의 metadata restore가 기존 SafUiProtocol대로 초기화하며 pure UI가 grant로 범위를 축소하거나 임의 선택하지 않는다. state/actions 파일과 widget은 SafDestination/SafTreeConfiguration/ManualSafSession/ContentResolver/SyncViewModel을 참조하지 않는다. URI/proof/provider 원문/credential/ticket/account/raw data/CSV는 state나 saved state에 없다. background capability는 기존 false를 유지하고 미지원 고정 문구로 표시한다. composition/recomposition/state 변경은 callback을 호출하지 않는다.

`resetConfiguration` explicitly carries the existing session reset CAS input. On dialog open retain the current non-null generation in ordinary Compose memory; confirm is enabled only while idle/configured and the current generation equals that captured generation. Invalidate/dismiss that local confirmation on pause, RUNNING, configuration removal, or generation change, including after later resumption. Confirm dispatches that captured value exactly once; cancel/dismiss dispatches nothing. These local UI updates invoke no action callback. Do not serialize DestinationGeneration or the whole state into rememberSaveable. A plain dialog Boolean may be saveable, but restored visibility alone must never reset or infer an expected generation.

### 결정 로직 위치

- SyncPlanner.kt의 object 함수: active profile canonical key·excludedTypes, enrollment/today/floor 경계, exact-scope 완료 제외 후 recent2+oldest gaps(max7, unique, ascending), blocker 보존, strict full-five와 현재 scope의 checked/uploaded coverage를 각각 계산한다. IO 없음.
- SyncRevision.kt의 object 함수: 기존 DefaultNormalizer와 VersionedCsvExporter로 generatedAt=Instant.EPOCH인 semantic projection을 만들고 "sync-revision/1"·schema0.1·date·scopeKey·정렬된 type/scope·dataset bytes에 big-endian 4-byte 길이 구분자를 붙여 SHA-256한다. 저장한 최초 revision 시각으로 실제 CSV를 재생성한다. generated_at 문자열 replace나 raw source 순서 hash를 쓰지 않는다. previous는 같은 scopeKey의 DayState만 허용한다. 첫 읽기는 admission 요청 scope를 가진 revision=null PENDING row를 사용한다. EMPTY/사라진 dataset/허용 범위 변경은 별도 판정하며 완전한 같은-scope populated→empty read만 REMOTE_STALE 근거다. raw/CSV를 저장하지 않는다. 날짜별 checked 범위·revision에 EXPLICIT_DAY/SUMMARY/WORKOUT_SCOPED와 BLOCKED를 기록하여 manual과 자동을 합쳐 전체 확인으로 만들지 않는다.
- SyncState.kt의 함수: 확인·중단·generation·attempt/retry 상태 전이의 유효성. store는 이 판정에 SQL 원자성을 제공하고 HealthReader/Drive를 호출하지 않는다.
- DestinationSync.kt: access→loadForScope→scope 고정 attempt start 후 access 재확인→exact-scope loadDay→source 재읽기→revision 결정→durable pending→reconcile→write 대상 RUNNING과 다른 profile current 무효화→pending write→scope 일치 파일별 commit→day 결과의 IO orchestration. 단순 상태 판정을 IO 사이에 숨기지 않는다.
- AutomaticHealthReader.kt의 IO 없는 query-plan 함수: 허용된 운동 interval을 날짜와 교차시키고 명시적 bounded HR query를 산출한다. IO adapter는 운동/심박 grant를 각각 확인하고 세션이 없으면 HR query를 하지 않는다. query/page/sample 상한은 날짜 전체에서 기존 budget을 공유한다. source/provider의 record start-time 선택으로 운동 구간의 sample 포함을 안전하게 판정할 근거가 없으면 WORKOUT_SCOPED 상세 결과를 BLOCKED/미확인으로 남긴다. broad before(end)/whole-history 우회는 금지한다. 다른 허용 category는 계속 처리하며 요약은 지원되는 Aggregate만 사용한다. 기존 manual HealthConnectReader의 explicit day/CSV 동작은 보존한다.
- BoundDriveAuthorization.kt의 IO 없는 결과 판정: mode/hasResolution/token/scope/ticket/generation/account 유효성. raw SDK 예외는 allowlist 오류로 바꾼다.
- SafTreeConfiguration.kt companion의 IO 없는 consent/flags/local capability 판정; grant/persist/probe Android IO는 같은 adapter가 소유한다.
- SafDestination.kt companion의 IO 없는 relativePath/known handle/identity/중복/byte 판정; 실제 tree 조회·RUNNING 뒤 write/readback IO는 같은 destination이 소유한다.
- `ManualSafSession`.kt의 generation/ticket/동의/전경 수명과 공용 gate 호출, SafManualUi.kt pure projection의 표시·허용 동작. SQL validation은 기존 decode/transaction 경계를 재사용한다.
- SyncWorkScheduler.kt의 IO 없는 retry 결정: durable batch/revision budget과 오류 종류에 따라 retry/blocked 판정. runAttemptCount만 쓰지 않는다.
- SyncCoverageUi.kt의 pure projection: 과거 성공/현재 stale/EMPTY/partial grants/gap/recent freshness/queued/blocked와 다음 허용 동작을 따로 계산한다.

### 관련된 암묵적 동작

- 현재 HistoryDatabase pathLock은 store 호출만 보호한다. 전체 sync gate가 아니며 같은 canonical DB path의 공유 Entry Mutex를 모든 foreground/worker 세션이 공유한다. 실제 살아 있는 실행을 timeout으로 탈취하지 않는다. (확인: A/HistoryDatabase.kt transaction/pathLock, A/SyncViewModel.kt continueUpload)
- 현재 Activity onPause는 model.pause로 전경 실행을 취소한다. worker 소유 실행과 UI collector 수명은 분리하고 기존 명시적 수동 continuation은 보존한다. (확인: A/MainActivity.kt onPause, A/SyncViewModel.kt pause)
- HC1.1.0 source HealthConnectFeatures.kt:41에 FEATURE_READ_HEALTH_DATA_IN_BACKGROUND, HealthPermission.kt:163에 PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND, PermissionController.kt:36에 getGrantedPermissions가 있다. worker마다 feature available+grant와 현재 category grant를 확인한다. 단일 runtime에 포함된 foreground-service 권한/service 선언은 library metadata이며 앱은 setForeground/상시 서비스를 호출하지 않는다. WorkManager 기본 diagnostics의 식별자 노출을 줄이기 위해 merged manifest의 DiagnosticsReceiver도 제거하고 app custom diagnostics는 allowlist만 쓴다. 실제 최초 grant 시각 API는 만들지 않는다. (확인: /workspace/tooling/health-connect-source/sources.jar 2026-11-05 직접 읽기)
- auth21.4.0의 setAccount(Account), Context authorization client, hasResolution/accessToken/grantedScopes와 basement18.5.0의 AccountPicker/allowable account types가 설치 AAR javap로 확인됐다. 계정 제거 감지에 broad GET_ACCOUNTS를 추가하지 않는다. callback/result account가 선택 계정과 연결되었음을 확인할 수 없으면 blocked로 처리한다. (확인: 2026-11-05 JDK21 javap; app 기존 dependency)
- 공식 WorkManager release notes는 2.11.0 stable(2025-10-22), minSdk23, CoroutineWorker의 runtime 포함을 확인한다. Google Maven runtime/testing 2.11.0 AAR의 실제 minCompileSdk는35, minAGP는8.6.0이며 runtime manifest minSdk23이다. POM Kotlin BOM은2.1.20, runtime coroutines-android는1.9.0이다. 현재 min28/compile36/AGP8.13.2/Kotlin2.2.20/coroutines1.10.2는 이 요구보다 높다. (확인: Context7 공식 release notes 및 https://dl.google.com/dl/android/maven2/androidx/work/ 아래 해당 POM/AAR를 2026-11-05 읽기 전용 조회)
- 현재 cb981df 소스의 네 resolved graph는 release129/debug130/JVM135/instrumentation110 구성요소, 공개 Maven 합집합155좌표다. 2026-11-14 실제 OSV155조회가 HTTP200/오류0/취약점 일치0이며 source/tree/filehash·query/response/log hash를 대조했다. debug/release merger에서 HealthApplication·backup false·single process·다른 Startup metadata 유지 및 WorkManagerInitializer/DiagnosticsReceiver 제거를 확인했다. T1 local JVM181/lint/APK/testAPK와 audit 및 source8b9f38e의 post-bootstrap API35·36 각135 Native/기존host 전체가 실제 통과했다. 독립 품질·보안 최종 검토와 승인 spec 기반 인프라 checkpoint 검토 뒤 PR19는75cc443702a3467c87268d49ce52d4d75b2ed8a5로 병합됐다. 후속 구현은 아래 공통 계약 재고정·독립 readiness를 통과한 뒤 시작한다. (확인: docs/V1/dependency-audit.json, auto-t1-root-audit-proof.json 및 auto-t1-root-generated-output-proof.json)
- unique periodic와 one-time은 별도 이름으로 enqueue되고 서로/manual을 배타화하지 않는다. WorkManager OS timing은 불확정이며 stop 뒤에도 remote writes가 이미 나갔을 수 있다. (확인: 공식 unique work/persistent work 문서; 스펙 6절)
- custom Application Configuration.Provider + WorkerFactory를 사용하고 AndroidX Startup의 WorkManagerInitializer만 manifest merger remove하여 중복 초기화를 막는다. 수동 UI는 기존 manual reader의 세션을 유지하고 각 worker는 자신의 automatic reader와 새 SyncExecutionSession/DestinationSync를 사용한다. 서로 다른 세션은 동일 canonical DB path의 Entry/control/owner/gate를 공유하며 worker가 get(app)의 cached manual reader를 재사용하지 않는다. 정확한 merged manifest/초기화는 starter test에서 검증한다. (로컬 확인: cb981df HealthApplication/merged debug·release manifest와 JVM181/APK/lint, 실제 post-bootstrap 양 API starter/probe 포함 각135 Native 검증 통과, 자동 worker 연결은 후속T11)
- SAF의 실제 returned flags만 persist하고 provider의 w/wt 의미는 구현별 차이가 있으므로 truncation+close+readback probe 성공 전 활성화하지 않는다. test provider는 test APK에만 등록한다. 실제 reboot/background capability 미확인은 별도 disabled 상태다. (확인: 스펙 4절, 공식 ContentResolver/DocumentsContract 계약)


### ManualSafPorts — SDK-free controller와 실제 proof 경계

```kotlin
// ManualSafPorts — A/ManualSafSession.kt 안, 신규 파일 없음
internal interface ManualSafPorts {
    val store: SyncStateStore
    val reader: ScopedHealthReader
    suspend fun binding(generation: DestinationGeneration): String?
    suspend fun validateSelection(treeUri: String, returnedFlags: Int, privacyConsent: Boolean)
    suspend fun configure(expected: DestinationGeneration?, configuration: SyncConfiguration, treeUri: String): SyncCommit
    suspend fun reopen(generation: DestinationGeneration): SyncCommit
    suspend fun runPrepared(generation: DestinationGeneration, prepare: suspend () -> DestinationSync): BatchOutcome
    suspend fun destination(treeUri: String, generation: DestinationGeneration, privacyConsent: Boolean): StorageDestination
}
```

- `ManualSafSession`의 주 생성자는 `(ports: ManualSafPorts, clock: Clock)`이며 실제 보조 생성자는 `(sqliteStore: SqliteSyncStateStore, healthReader: ScopedHealthReader, execution: SyncExecutionSession, resolver: ContentResolver, configuration: SafTreeConfiguration = SafTreeConfiguration(resolver), clock: Clock = Clock.systemUTC())`다. T6에서 기존 Drive와 같은 store/reader/execution을 전달한다. test용 configuration 주입은 기존 forTesting만 사용하며 생산 allowlist는 바꾸지 않는다.
- `ManualSafSession`의 `refresh`, `beginTreeSelection`, `acceptTree`, `continueExport`, `retry`, `reset`은 ports IO가 있는 suspend 메서드다. `acceptTree(requestId, Uri?, flags)`는 내부 `acceptTreeValue(requestId, String?, flags)`로만 위임한다. JVM은 문자열 ingress와 generic fake StorageDestination을 사용하고 Uri 실행·가짜 SafTreeProof·reflection을 쓰지 않는다.
- `ManualSafPorts`.binding은 LOCAL private binding의 treeUri만 반환한다. validateSelection은 실제 `configuration.select(Uri.parse(treeUri), flags, consent)` 후 proof를 보관하지 않는다. configure는 기존 `PrivateDestinationBinding("LOCAL", treeUri = treeUri)`와 전체 intended 범위를 저장한다. 같은 tree·같은 범위는 generation/enrollment/journal을 재사용하며 다른 목적지·범위만 기존 configure CAS로 바꾼다.
- `ManualSafPorts`.destination은 **runPrepared의 owner prepare 안에서만** 실제 `configuration.restore(treeUri, consent)` 후 `SafDestination(resolver, proof, generation, sqliteStore::loadKnownTargets, clock)`를 만든다. 성공 proof는 이 engine 수명에만 있고 StateFlow/saved state에는 없다. fresh store/binding/scope/grants 재확인 → destination/probe → 재확인 → reopenManualBlockers → 재확인 → DestinationSync 순서다. busy MERGED는 factory/probe/reopen/read를 호출하지 않는다.
- `ManualSafSession`.beginTreeSelection은 즉시 launch할 picker ticket에 **외부 왕복 한 번만** 허용한다. T6는 bridge idle 확인 뒤 begin→즉시 launch하며 launch 실패/cancel은 null로 accept한다. 첫 pause는 owner를 취소하고 실행 proof를 없애되 그 ticket만 보존하고, 두 번째 pause/입력·동의·generation·reset 변경은 ticket도 폐기한다. 결과는 session foreground+Activity RESUMED 뒤 actual flags 그대로 전달한다. stale 결과는 새 ticket을 소모하지 않는다.
- `ManualSafSession` 복원 refresh는 metadata-only다. 초기 intended는 저장된 전체 범위로 복원하되 뒤늦은 metadata가 편집을 덮지 않는다. 복원된 binding에 새 privacy consent·전경·같은 intended/stored 범위·grant 교집합이 충족되면 READY는 **명시 계속을 요청할 수 있음**만 뜻한다. provider capability/이번 업로드 성공을 뜻하지 않고 실제 실행은 fresh gate-time probe 통과 전 health read/write를 하지 않는다. pause·동의 철회·입력 변경은 이 readiness를 무효화한다.

- `SafCiProtocol`: saf-local 소유의 고정 진단에 saf-flow→SafManualFlowInstrumentedTest,ManualDriveFlowInstrumentedTest를추가해T6의실제8method를검증한다. 원래6choices와PR/defaultfull/API/host/권한/timeout/최종full gate는유지하고T6source존재전실제dispatch하지않는다. CI기존두파일만이므로T6네파일과병렬작성가능하다.

### 자동 실행 공통 계약 — 통합 후 실행 준비

- `WorkManagerStarter`: 기존 ADR-008의 work-runtime/work-testing **둘 다2.11.0**, min28/compile36/AGP8.13.2/Kotlin2.2.20/coroutines1.10.2를 유지한다. empty work-runtime-ktx와 multiprocess artifact는 넣지 않는다. Configuration.Provider+WorkerFactory와 Application-scoped runtime에 같은 canonical DB와 admission gate를 둔다. T1의 starter와 네 resolved graph/merged manifest/AAR metadata/OSV 초기 감사를 모두 통과해야 T2 이후 기능 구현을 시작한다. T17 최종 재감사는 유지한다. parent에 기록된 공식 Maven/release 호환 조사와 실제 resolved graph/merged manifest 검증은 다른 증거다. 새 의존성을 이번 준비에서 실행·해결한 것으로 쓰지 않는다.

- `AutomaticRetryContracts`는 **기존 필드 재사용만으로 충족할 수 없는 계약**이다. 다음은 최소 추가 제안이며 parent integration/ADR Proposed가 선행되어야 한다.

```kotlin
// AutomaticRetryContracts —
enum class RetryBudgetStatus { ACTIVE, COMPLETED, BLOCKED, EXHAUSTED }
class RetryBudget private constructor(
    val lineageId: String, val generation: DestinationGeneration,
    val scopeKey: ReadScopeKey, val requestedScopes: Map<DataType, ReadScope>,
    val dates: List<LocalDate>, val observedRevisions: Map<LocalDate, String?>,
    val retriesUsed: Int, val retryNotBefore: Instant?, val retryDelayMillis: Long?,
    val claimVersion: Long, val ownerAttemptId: String?, val status: RetryBudgetStatus,
    val createdAt: Instant, val updatedAt: Instant,
) {
    val retryBudgetKey: String
    companion object {
        fun validated(lineageId: String, generation: DestinationGeneration,
            scopeKey: ReadScopeKey, requestedScopes: Map<DataType, ReadScope>,
            dates: List<LocalDate>, observedRevisions: Map<LocalDate, String?>,
            retriesUsed: Int, retryNotBefore: Instant?, retryDelayMillis: Long?,
            claimVersion: Long, ownerAttemptId: String?, status: RetryBudgetStatus,
            createdAt: Instant, updatedAt: Instant): RetryBudget
    }
}
class InitialAutomaticProposal private constructor(
    val expectedGeneration: DestinationGeneration, val scopeKey: ReadScopeKey,
    val requestedScopes: Map<DataType, ReadScope>, val frozenDates: List<LocalDate>,
    val attemptId: String, val trigger: SyncTrigger, val plannedAt: Instant,
    val accessFingerprint: String,
) {
    companion object {
        fun validated(expectedGeneration: DestinationGeneration, scopeKey: ReadScopeKey,
            requestedScopes: Map<DataType, ReadScope>, frozenDates: List<LocalDate>,
            attemptId: String, trigger: SyncTrigger, plannedAt: Instant,
            accessFingerprint: String, enrolledDay: LocalDate,
            todayInSeoul: LocalDate): InitialAutomaticProposal
    }
}
data class AutomaticOwner(val lineageId: String, val attemptId: String, val claimVersion: Long)
sealed interface AutomaticBudgetRead {
    data class Ready(val budget: RetryBudget?): AutomaticBudgetRead
    data object Stale: AutomaticBudgetRead
}
sealed interface RetryClaim {
    data class Claimed(val attempt: AttemptState, val budget: RetryBudget): RetryClaim
    data class Deferred(val retryNotBefore: Instant): RetryClaim
    data object Busy: RetryClaim
    data object Stale: RetryClaim
    data object Blocked: RetryClaim
    data object Exhausted: RetryClaim
}
sealed interface RetryValidation<out T> {
    data class Valid<T>(val value: T): RetryValidation<T>
    data object Invalid: RetryValidation<Nothing>
}
enum class AutomaticFinishOutcome { COMPLETED, RETRYABLE, BLOCKED }
object AutomaticRetryModel {
    fun accessFingerprint(access: ReadAccess): String
    fun initialClaim(proposal: InitialAutomaticProposal, lineageId: String,
        currentGeneration: DestinationGeneration, configuration: SyncConfiguration,
        currentScopeKey: ReadScopeKey, currentRequestedScopes: Map<DataType, ReadScope>,
        enrolledDay: LocalDate, todayInSeoul: LocalDate, now: Instant): RetryValidation<RetryClaim>
    fun reClaim(budget: RetryBudget, expectedGeneration: DestinationGeneration,
        expectedLineageId: String, expectedClaimVersion: Long, expectedScopeKey: ReadScopeKey,
        expectedRequestedScopes: Map<DataType, ReadScope>, expectedFrozenDates: List<LocalDate>,
        attemptId: String, trigger: SyncTrigger, now: Instant): RetryValidation<RetryClaim>
    fun observeRevision(budget: RetryBudget, expectedGeneration: DestinationGeneration,
        owner: AutomaticOwner, date: LocalDate, revisionDigest: String,
        now: Instant): RetryValidation<RetryBudget>
    fun finish(budget: RetryBudget, expectedGeneration: DestinationGeneration,
        owner: AutomaticOwner, outcome: AutomaticFinishOutcome,
        now: Instant): RetryValidation<RetryBudget>
}



// SyncMutation appends val automaticOwner: AutomaticOwner? = null.
sealed interface AutomaticPreflight {
    data object Ready : AutomaticPreflight
    data class Deferred(val retryNotBefore: Instant) : AutomaticPreflight
    data object RecoveryRequired : AutomaticPreflight
    data object Stale : AutomaticPreflight
}
// Concrete SqliteSyncStateStore members:
suspend fun loadAutomaticBudget(expectedGeneration: DestinationGeneration, scopeKey: ReadScopeKey): AutomaticBudgetRead
suspend fun preflightAutomaticAdmission(expectedGeneration: DestinationGeneration, now: Instant): AutomaticPreflight
suspend fun claimAutomaticAttempt(expectedGeneration: DestinationGeneration, expectedLineageIdOrNull: String?, expectedClaimVersionOrNull: Long?, initialProposalOrNull: InitialAutomaticProposal?, expectedScopeKey: ReadScopeKey, expectedRequestedScopes: Map<DataType, ReadScope>, expectedFrozenDates: List<LocalDate>, attemptId: String, trigger: SyncTrigger, now: Instant): RetryClaim
suspend fun finishAutomaticAttempt(expectedGeneration: DestinationGeneration, lineageId: String, attemptId: String, expectedClaimVersion: Long, outcome: AutomaticFinishOutcome, now: Instant): SyncCommit
suspend fun setAutomaticCAS(expectedGeneration: DestinationGeneration, enabled: Boolean, now: Instant): SyncCommit
suspend fun recoverAutomaticAttempt(expectedGeneration: DestinationGeneration, owner: AutomaticOwner, now: Instant): SyncCommit
```

- `AutomaticRetryContracts` **정체성**: generation+scope+고정 최대7 날짜 cohort에 fresh UUID lineage를 준다. initial claim은0, 같은 미종결 lineage의 후속 automatic attempt를 시작하는 transaction에서1씩 증가하여 최대3이다. budget key는 lineage UUID의 domain-separated SHA-256이며 UNREAD/사전 read revision 해시로 만들지 않는다. WorkRequest id/runAttemptCount/new process/new attempt/periodic tick은 budget identity가 아니다.
- `AutomaticRetryContracts` **최초 claim 입력**: gate 안에서 recovery/validated state를 읽고 기존 미종결 lineage/due를 먼저 확인한다. 신규일 때만 fresh feature/grant/access metadata(레코드 읽기 없음) → `loadForScope`/planner → immutable `InitialAutomaticProposal`을 만든다. 최초 claim은 proposal을 필수로 받아 generation/automatic/현재 config와 scopeKey·requestedScopes 일치, 날짜 bounds/최대7일/중복 및 attempt metadata를 검증하고 lineage+frozen scopes/dates+AttemptState를 **한 commit**으로 쓴다. 별도 선행 start/proposal commit이나 이전 manual attempt의 날짜/scopes를 숨은 입력으로 쓰지 않는다. 재claim은 initialProposal=null이고 입력 scopes/dates가 저장값과 정확히 일치해야 하며 lineage/version CAS 실패·cohort 교체는 STALE/거부다. 계획 후 scope/grant 변경을 metadata 재검사로 발견하면 기존 cohort를 바꾸거나 새 initial을 주지 않고 BLOCKED(설정 generation 변경은 STALE)로 남긴다. read 직전에도 grants를 재검사하며 철회는 EMPTY가 아니다.
- `AutomaticRetryContracts` **durable 지연**: 허용 transient이고 retriesUsed<3인 finish와 attempt 종료를 같은 transaction에서 기록할 때 다음 retry1/2/3의 지연을 각각30/60/120초로 계산해 `retryDelayMillis`와 `retryNotBefore=finishNow+delay`를 저장한다. 모든 periodic/catch-up/new process automatic claim은 **같은 claim transaction에서** 현재 generation/lineage/version/owner와 `now >= retryNotBefore`를 검사한다. 직전에는 DEFERRED이며 count/claimVersion/attempt start 변화가 없고 auth/HC metadata·record/remote 호출도0이다(먼저 DB due를 검사하고 최종 CAS에서 재검사). 정각에는 경합 중 하나만 다음 claim을 소비한다. WorkManager backoff는 추가 OS 지연일 뿐 durable due를 대체하지 않는다. DEFERRED는 `Result.retry()`로 재대기하며 내부 sleep/loop/새 initial 없음; WorkManager가 더 늦게 실행해도 허용한다. retry3 실패는 EXHAUSTED이며 다음 due를 만들지 않는다. BLOCKED/EXHAUSTED의 null due는 실행 허가가 아니다. 완료된 batch는 due를 해제하고 정상 다음 batch를 허용한다. explicit manual recovery만 eligible ledger 종료와 blocker reopen을 원자 수행해 이 지연을 종료할 수 있다. finalize 실패/claim 뒤 crash의 unresolved owner는 due=null을 즉시 허가로 해석하지 않고 journal recovery와 fail-closed 상태를 먼저 적용한다. clock 후퇴는 due까지 보수적 대기이며 정확한 wall-clock 실행 보장은 없다.
- `AutomaticRetryContracts` **revision 해석**: 실제 source reread의 revision은 day 준비 commit과 같은 transaction에서 observedRevisions에 기록한다. 진행 중 cohort의 원본 변경은 같은 미종결 batch의 새로운 관찰이며 retry count를 초기화하지 않는다. 이는 변하는 원본이 실패 budget을 무한 재생성하지 않게 한다. cohort 완료 뒤 다음 정상 periodic/recent refresh는 새 batch로 허용된다. 실패한 cohort의 새 source revision을 **그 자체로 새로운 initial-run 권한**으로 해석하지 않는다. 승인 spec의 “한 batch revision”과 “소진은 다음 명시적 trigger까지”를 이 방식으로 연결하는 해석이며 parent에 명문화·독립 검토 후 실행한다. legitimate completed refresh를 영구 차단하는 revision-global budget이 아니다.
- `AutomaticRetryContracts` **재시작**: ACTIVE lineage는 frozen 날짜 그대로 이어서 선택하며 오늘 planner가 바뀌었다고 교체하지 않는다. 오늘의 history floor/feature/grant는 다시 적용한다. 안전성 부족이면 BLOCKED로 남긴다. 신규 날짜는 다음 batch로 대기한다. 새 scope는 별도 coverage이나 기존 BLOCKED 날짜/physical unknown target를 다른 profile이라는 이유로 맹목 create하지 않는다.
- `AutomaticRetryContracts` **원자성**: 공용 canonical-path gate → recover unfinished attempt → validated ledger/due 확인 → fresh metadata-only access와 immutable proposal 또는 frozen cohort 재검증 → budget+AttemptState start/due CAS 한 commit → fresh SDK auth/about → health read/engine. busy caller는 durable pending union만. store day mutation은 active lineage 날짜/owner/claimVersion과 일치할 때 실제 revision vector를 함께 갱신한다. 제안 session API는 `runAutomaticPrepared(trigger, generation, prepare)`이며 `DestinationSync.run(..., finishAttempt: Boolean = true)`에 자동 owner만 false를 전달하여 engine은 outcome만 반환한다. engine의 마지막 finish를 automatic finalization과 따로 중복 commit하지 않도록 session 소유 finish로 모은다. manual finish는 기존 경계 유지.
- `AutomaticRetryContracts` **소진**: initial+retry1+retry2+retry3 후 RETRYABLE이면 같은 finish transaction에서 EXHAUSTED와 미완료 날짜 RETRY_EXHAUSTED blocker를 쓴다. 아직 읽지 않은 날짜도 revision=null/datasets empty/BLOCKED의 명시 행을 만든다. 이미 확정한 파일/day의 target/prior/unknown/REMOTE_STALE를 유지한다. 파일 phase를 fake CONFIRMED로 바꾸지 않는다. 다른 영구 blocker를 RETRY_EXHAUSTED로 덮지 않는다. 완료된 날짜는 성공으로 남기되 실패한 refresh의 미확인 범위는 별도 blocker로 보인다. finalize 실패는 성공/새 initial로 처리하지 않고 admission fail-closed로 보존한다.
- `AutomaticRetryContracts` **명시 복구**: 전경 retry/manual explicit trigger가 기존 `reopenManualBlockers`에 연결될 때 ledger의 eligible exhausted/blocked cohort를 명시 종료하고 새 initial batch를 허용하는 동작도 한 transaction으로 묶는다. 단순 앱 열기/periodic/catch-up/WorkManager 재등록은 reopen이 아니다. dataset rows/target/prior/unknown/remote stale는 그대로다. 새 generation/config reset을 retry 편법으로 쓰지 않는다.
- `AutomaticRetryContracts` **claim 후 crash**: 외부 작업 이전이라도 claim을 되돌리지 않는다. 재시작 시 해당 claim은 소비된 것으로 유지하고 journal을 먼저 결과불명 복구한다. 최대치는 보수적이며 최초 외4번째 retry는 불가능해야 한다. source/remote permanent failure·unknown create는 retry 대상 아님. auth 준비 실패가 source보다 먼저 발생해 아직 day가 없으면 frozen cohort에 ACCOUNT_AUTHORIZATION_BLOCKED 행을 finish와 원자 기록하고 HC record read는0회다. production destination이 이미 분류한 lookup/PATCH IOException/429/500/502/503/504만 허용하며 source/auth generic exception을 transient로 추정하지 않는다.

- `AutomaticRetryContracts` **최소 schema 제안**: 기존 DB `user_version=2`에 `retry_budget` 한 테이블 및 `(generation,scope_key)`당 ACTIVE 한 행을 강제하는 partial unique index만 추가한다. PK lineage UUID; generation FK; validated requestedScopes/dates/revision vector의 전체 JSON 크기 합계<=16KiB (같은 한 테이블의 검증된 JSON 또는 column); retries0..3, validated nullable retryNotBefore와 retryDelayMillis(30/60/120초 및 retry index/status 관계), claimVersion>=0, 고정 status/UUID/hash/time/날짜 관계 검사. 기존 config/domain format1과 모든 기존 테이블은 유지한다. terminal rows도 해당 generation metadata로 보존하며 최대100000행/DB 기존 capacity guard에 포함한다. cap 초과는 STORE_UNAVAILABLE이며 자동 prune/reset 금지. reset의 명시 범위에서만 FK 순서에 맞춰 제거한다. ledger 조회는 해당 접근에 적합한 검증된 indexed scan과 최대7 날짜 metadata를 쓰고 legacy store와 같은 bounded/streaming 검증을 적용한다. 새 per-day N+1/read-all JSON 경로를 도입하지 않는다. 모든 store read/admission은 ledger row/cap/FK/owner 관계도 검증하며 reopenManualBlockers의 명시 복구와 ledger 종료를 같은 기존 write transaction에 통합한다. archived generation은 자동 실행에 재사용하지 않는다.

- `AutomaticRetryContracts` **대안 비교**: (a) 기존 AttemptState의 dates/requestedScopes/retryBudgetKey/retriesUsed를 atomic carry하고 generation당 단일 미종결 cohort만 허용하는 대안은 현재 delete/insert 정책 변경으로 구현 가능성 자체를 배제하지 않는다. 다만 다른 scope의 blocked/exhausted cohort와 due/복구 경계를 보존하면서 정상 scope refresh를 허용하려면 현재 마지막 attempt 행 밖의 이력 계약이 필요하다. 단일 cohort로 모두 차단하는 제한이 spec의 scope별 pending/복구 의미를 보존하는지는 Proposed ADR에서 비교·독립 검토한다. hidden JSON으로 codec/size/validation을 우회하지 않는다. (b) 별도 budget file/DB/preferences는 attempt/day와 한 SQLite commit이 안 되어 claim crash/소진 표시 불일치가 생긴다. (c) runAttemptCount는 새 work/process/periodic/re-enqueue identity와 batch revision을 연결하지 못한다. (d) one additive validated table은 migration 비용이 있지만 기존 rows를 덮지 않고 같은 transaction을 제공하므로 선택 후보다. 되돌리기 비싼 schema/마이그레이션 결정이므로 기존 ADR-006/009를 덮지 않는 새 ADR Proposed가 parent 통합 시 필요하다. ADR-010은 Proposed로 기록하며 구현과 검증을 기다린다.

- `AutomaticRetryContracts` **migration 계약**: read-only exact v1 schema+content+FK/cap 검증을 먼저 통과한다. v1 hot journal이면 기존 raw-save/canary 검증 경계로 먼저 복구하며 malformed/future schema는 원본 writable open 전 차단한다. canonical path lock 아래 migration owner marker를 noBackup 전용 sibling staging에 exclusive 획득하고 symlink/sidecar/source fingerprint를 검사한다. 기존 hot-journal 복구가 끝나고 모든 DB handle을 닫은 뒤, rollback journal이 남지 않고 WAL/SHM이 없음을 확인한 validated v1을 기존 Os.open/O_NOFOLLOW·descriptor identity·해시 검사 방식으로 sibling stage에 복사한다. path lock/owner를 유지한 상태에서 원본 fingerprint가 전후 같아야 하며 stage fsync와 read-only v1 전체 재검증을 통과해야 한다. Android에 존재하지 않는 backup API를 호출한다고 가정하지 않는다. 이 일관된 staged copy에서만 additive table/index+user_version2를 transaction으로 적용하고 재열기 전체 v2 validation 후 durable marker/atomic publish한다. hot/nonempty rollback journal을 무시한 main-file copy 금지. idle zero-length journal 외 미해결 sidecar는 안전 부재로 추측하지 않는다. published v2 재검증 뒤에만 migration 완료다. 중단 시 marker+검증된 source/stage fingerprint로 해당 단계만 재개하며 unknown bytes/불일치는 보존 BLOCKED다. 기존 reset marker/owner와 동시 소유 금지. v1 모든 generation/binding/day/dataset/attempt와 unknown/prior/gap/시각을 의미 그대로 보존한다. 현 production v1 configure는 자동false만 저장하므로 정상 이전 상태는 새 ledger empty로 이전한다. validated v1에 예기치 않은 automatic=true가 있으면 기존 retry 이력을 알 수 없으므로 원본을 보존하고 자동 admission을 차단하여 명시 복구를 요구한다. true를 임의false로 덮거나 새 initial budget을 부여하지 않는다. v1 unfinished manual journal은 새 자동 attempt로 승격하지 않는다. 미래 schema fixture는3 이상으로 고치고, 불명 schema/내용 실패 fixture는 계속 실패해야 한다. 상세 publish/ownership API가 현 hot-journal/reset 구현과 충돌하면 blocked로 보고하고 부모에서 보정한다.

- `AutomaticSummaryContracts`: 승인 spec §4 자동 Aggregate 지향을 다음 additive 값으로 구현하는 해석이다. 부모 data-contract 의미 보완이 먼저 필요하며 원래 P0/manual bytes를 변경하지 않는다.

```kotlin
// AutomaticSummaryContracts —
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
```

- `AutomaticSummaryContracts` daily sleep_min/exercise_min은 Duration.seconds/60.0+nano/60000000000.0, no-data는 blank, 실제 zero만0.0. Normalizer는 granted 해당 category에서만 투영하고, SyncRevision.identify는 aggregate envelope가 있는 category의 scopedSnapshot scope가 SUMMARY인지 require하여 metadata와 값을 일치시킨다. 따라서 SUMMARY가 아닌 scope에서 summary를 받아 성공 revision으로 만들지 않는다. null/empty origins를 fabricated source로 채우지 않는다. origins는 해당 metric 요청 결과만 사용하고 메모리/semantic revision에만 두며 원본 record ID로 전환하지 않는다. CSV header/path/schema0.1과 raw session full bounds는 그대로다. daily의 scope 및 데이터 계약 설명으로 derived summary임을 구분하며 HR 평균을 resting 값으로 쓰지 않는다.
- `AutomaticSummaryContracts` aggregate-only 실제 값이 있으면 daily를 생성한다. null metric들만 있고 다른 record도 없으면 EMPTY가 가능하나 해당 SUMMARY 범위의 확인만 뜻한다. DTO 기본null인 manual export의 파일 존재/CSV bytes/hash를 기존 golden fixture와 정확히 비교한다. summary를 넣어 populated daily가 생겼다가 사라지면 기존 REMOTE_STALE 정책을 적용한다.
- `AutomaticSummaryContracts` 새 duration/provenance는 semantic revision 입력에 포함하며 source origins 순서는 정렬하고 generatedAt은 의미 해시에 넣지 않는다. source origin 변화도 현재 observation 변경으로 취급한다. 기존 manual digest domain은 additive summary가 없으면 유지한다. source/type 허용 오류, 음수 duration/비정상값은 고정 source failure로 거절한다.

- `AutomaticSessionRoutingContracts`: **worker마다** `AutomaticHealthReader`와 이를 생성자에 고정한 새 `SyncExecutionSession`을 runtime factory가 만든다. worker는 `SyncExecutionSession.get(app)`을 호출하지 않는다. manual ViewModel의 기존 캐시/HealthConnectReader는 그대로 유지한다. 두 session 모두 같은 canonical DB path의 기존 static `Entry`(gate/control/owner/receipts/recovery)를 공유하므로 서로 다른 객체여도 admission은 하나다. worker의 `prepare`가 만드는 `DestinationSync`에도 **동일한 automatic reader 인스턴스**를 주입한다. access/planner와 engine read가 서로 다른 manual/automatic reader를 쓸 수 없다. 자동 session은 SCHEDULED/CATCH_UP만, manual session은 MANUAL만 실행하며 잘못된 trigger는 IO 전 거부한다. runtime에서 전역 reader를 바꿔 끼우지 않는다. `detectPending(trigger: SyncTrigger = MANUAL)`은 config.automatic에 따른 암묵 선택을 제거한다. manual refresh는 automatic=true여도 MANUAL, worker metadata 감지는 명시 SCHEDULED/CATCH_UP과 해당 reader를 사용한다. 앱 restore/자동 설정의 gap 표시는 durable metadata만 읽고 이 메서드로 record IO를 시작하지 않는다. T5가 session API/trigger 경계를, T11이 production worker/engine 배선을, T15가 UI metadata 경계를 구현한다.

- `AutomaticHealthContracts`: `AutomaticHealthReader : ScopedHealthReader`를 추가하고 기존 MANUAL은 HealthConnectReader 경로 그대로 유지한다. SCHEDULED/CATCH_UP은 background feature status AVAILABLE+현재 background grant+각 category grant를 모두 검사한다. STEPS/SLEEP/EXERCISE SUMMARY, WEIGHT는 실제 day-bounded raw point read의 EXPLICIT_DAY(최신 실제 값), HR는 입증된 경우에만 WORKOUT_SCOPED다. scope fingerprint가 전체 manual EXPLICIT_DAY와 다르므로 coverage 합치기 금지. EXPLICIT_DAY weight는 전경 trigger의 동의 뜻이 아니라 실제 당일 점 측정 조회 범위를 뜻한다는 parent 설명을 보완한다.

- `AutomaticHealthContracts` production SDK1.1.0/provider의 bounded exercise discovery와 earlier-start HR record 포함 보장은 현재 **미입증**이다. 기본 production HR access는 BLOCKED/excluded라서 engine의 read 전체를 실패시키지 않고 지원 category를 독립 진행시킨다. UI에는 HR 미확인을 남긴다. 필요한 운동 permission·세션 discovery·HR overlap proof가 모두 입증된 adapter만 실제 bounds∩day union의 상세 query를 사용한다. bounds union은 중복 window만 병합하고 서로 다른 source/record의 같은 time/bpm을 합치지 않는다. source record+sample occurrence provenance를 유지한다. 날짜 전체 query/page/sample shared budget과 pagination repeated-token guard를 쓰며 초과는 block한다. **합성 adapter PASS는 production capability proof가 아니다**. no-session은 완전한 discovery 성공일 때만 query0/좁은 범위 empty이며 discovery 불명은 EMPTY가 아니다. manual before(dayEnd) 또는 arbitrary N-hour lookback은 자동 fallback이 아니다.

- `BackgroundAuthorizationContracts`: `BackgroundDriveAuthorization.authorize(boundAccount,generation,isCurrent)`를 application context의 실제 Identity SDK adapter에 둔다. 저장된 account로 setAccount, drive.file만 요청; fresh 결과 token/scope/noResolution 검사 → BoundDriveAuthorization exact selected principal same-token about → 그 transport로 destination 생성 → HC records. gate 밖 credential factory 실행/last token 캐시/worker PendingIntent 실행/AccountPicker/permission UI/refresh-token 저장은 금지한다. SDK Task 취소 후 callback은 현재 ticket/generation/automatic 재검사를 통과해야 한다. app open metadata만으로 silent authorize하지 않는다. 실제 real-account silent 성공은 pending이다.

- `AutomaticWorkContracts`: `SyncWorker : CoroutineWorker`와 `SyncWorkScheduler`는 생성에 application-owned runtime 주입을 쓴다. 같은 process/canonical-path gate가 manual/periodic/one-time admission을 맡으며 reader/session 배선은 `AutomaticSessionRoutingContracts`를 따른다. WorkManager unique work는 gate가 아니다. pending batch 존재와 현재 gap은 DB에서 복원한다. worker는 최대7일 한 번만 실행한다. 남은 gap은 다음 허용 trigger까지 durable pending이다. queued/running/stale/blocker를 UI에 구분하고 scheduling 성공을 health freshness 성공으로 쓰지 않는다. 시간대 KST, 오늘/미래/등록 전 read 없음. exact alarm·custom loop·상시 service·battery exemption·backend 없음. work 입력은 generation만, account/tree/token/dates/CSV/measurements/원시 예외 없음. 자동 LOCAL은 항상BLOCKED/disabled이며 SAF 전경 probe로 background 능력을 승격하지 않는다.

- `AutomaticSettingsContracts`: `AutomaticSyncSession`은 동의/feature/grant/목적지 metadata와 enqueue만 관리하고 health record IO를 하지 않는다. on app open automatic off는 표시만, on은 unique catch-up enqueue. opt-in은 전경에서 purpose/category/selected destination/24h 지연·Doze·배터리·최근데이터 지연을 설명한 뒤 별도 확인. opt-out/settings/reset은 generation invalidate를 먼저 commit하고 이전 generation work를 취소한다. remote 발행 취소는 rollback이 아니며 old callback commit은CAS거부다. foundation history 삭제와 V1 설정/coverage reset 확인은 분리한다. 계정/목적지 변경은 선행 manual/SAF 명시 구성 계약을 재사용한다.

- `CoverageUiContracts`: `SyncCoverageUi`의 IO 없는 mapper는 destination label·등록일 이후 gap count·연속 checked/uploaded·lastUploadAt·recent checked/uploaded date 집합·현재 scope/excluded·queued/running/blocked·다음 허용 action을 따로 투영한다. lastUploadAt은 generation 전체(scope 무관)의 마지막 실제 upload confirmation 시각이다. “마지막 업로드(전체 범위)”로 표시하며 현재 automatic SUMMARY 범위 성공으로 라벨링하지 않는다. currentness/recent/연속 coverage는 현재 scopeKey+requestedScopes의 checked/uploaded/revision/blocker로만 계산하고 manual EXPLICIT_DAY와 auto SUMMARY를 합치지 않는다. 다른 scope의 더 최근 upload는 global 시각만 바꾸며 현재 scope의 stale을 지우지 않는다. EMPTY=read checked/upload0, partial=허용된 범위, unknown count는unknown, 이전 generation은 이전 이력만. REMOTE_STALE와 현재 영구 blocker가 예전 success보다 우선 보인다. WorkInfo.ENQUEUED는 constraints 미충족/대기·backoff를 포함한 queued이고 BLOCKED는 선행 work dependency 대기다. ENQUEUED만으로 network/배터리 중 어느 이유인지 단정하지 않으며 constraint unmet을 WorkInfo.BLOCKED로 번역하지 않는다. 앱의 durable permission/auth/retry-exhausted blocker는 WorkInfo state와 별도 표시한다. 뷰 복원은 설정 읽기 외 부수효과 없이 동작하고 automatic on만 scheduler를 호출한다.

- `ReleaseEvidence`: 기존 ADR-008 Proposed를 기능/검증 전 Accepted로 바꾸지 않는다. 새 retry migration 및 additive automatic derived summary 의미는 부모 integration 때 별도 Proposed ADR 후보다. 스펙 적합성 통과 뒤에만 Accepted. 각 실제 PR은 작성/수정 세션과 분리한 fresh-context 최신 head 독립 리뷰·한국어 PR 기록·차단 수정/재검토가 필요하다. 이 draft는 독립 검토 결과가 아니다.


- `WorkManagerIsolationContracts`: early runtime/testing2.11.0·starter·네 graph audit·양API 실제 회귀를 source 대조해 통합한 뒤 T19→T1 순서로 실행한다. T19는 production Application/runtime을 쓰기 전에 helper 설치 class를 서로 다른 instrumentation invocation/process로 분리한다. app-owned stable manager와 explicit DI는 앱 호출을 보호하지만 SystemJobService의 직접 global lookup까지 격리한다고 주장하지 않는다. public helper close는 delegate를 복원하지 않는다. reflection/restricted setDelegate/custom runner/test Application/new dependency를 쓰지 않는다.

- `WorkManagerIsolationRegistry`: `android/ci/verify-workmanager-isolation.sh`의 고정 registry는 초기 WorkManagerStarterInstrumentedTest, T11에서 SyncWorkSchedulerInstrumentedTest, T12에서 SyncWorkerInstrumentedTest, T13에서 AutomaticSettingsInstrumentedTest로 늘어난다. helper 설치 source와 registry 누락/중복을 검사하고 일반 remainder notClass에는 같은 registry를 사용한다. 두 normal probe method는 normal-before→각helper→normal-after 별도 force-stop/instrument invocation으로 각각1회만 실행한다. pid는 private 비교만 하고 공개 결과는 고정stage/count/pass다. 각 bounded raw invocation은 INSTRUMENTATION_CODE:-1, 정확expectedcount 및 failure/error/skip/crash/abort없음을 parse한다. instrumentation 원문은 private scratch에서 검증하며 고정 protocol·기대 test identity만으로 모든 줄이 안전하다고 검증된 합성 원문과 machine-readable class+method 결과만 기존critical artifact 아래 보존한다. 불허 원문·예외/stack/provider message는 공개하지 않고 고정 stage/count/pass 또는 고정 실패 코드만 남긴다. connected teardown 뒤 두 기존 APK의 bounded reinstall/runner 등록 확인을 수행하며 모든 종료 경로에서 기기 instrumentation/app force-stop과 host wrapper 회수·종료 확인을 수행한다. source inventory는 같은 파일의 여러 top-level class별 실제 method 소유권을 보존한다. adb shell exit0만으로 PASS라고 하지 않는다. 현재 critical15·일반remainder·기존host전체는 삭제/skip/중복없이 보존하고 helper/probe만 해당재실행에서 제외한다. 신규 retry host-only class는 T12 고정exclusion에 추가하고 workflow의 **full mode에서만** 기존fullhelper 뒤 새host를 실행하며 optional SAF diagnostic에 강제로 붙이지 않는다.

- `WorkManagerIsolationProbe`: 신규 WorkManagerIsolationInstrumentedTest의 `normalRuntimeBeforeHelperHasALiveProductionManager`, `normalRuntimeAfterHelperHasALiveProductionManager` 두 실제 method는 public WorkManager의 bounded DB-query 완료·TestDriver없음으로 liveproduction을 검증한다. T1이 production Application을 넣으면 app-owned stable manager와 publiclookup 동일성 및 합성/off Activity launch/recreate 검증을 추가한다. probe는 helper install/close/enqueue/계정/HC/Drive를 하지 않는다. starter1회는 remainder에서 isolated1회로 이동하므로 기존case수는 보존되고 normalprobe2개만 증가한다. 최종기대수는 실제 통합source의class+method집합으로 계산하고 옛121을 하드코딩하지 않는다.


- `WorkManagerIsolationRegistry`: 실제 SAF merge `9ab774beac85bf7a96953bb0a0aa1b8098c76c69`와 WM 통합 `6c5f5b0412029054cc992adc09a2296bcf0c696c` 기준 Source Native는133개(critical15+나머지118, starter1포함)이다. T19후 normal기존132+격리starter1+신규normalprobe2=135개를 예상하되 반드시 실제class/method 집합으로 다시 확인한다. 과거121/critical14를 현재inventory로 쓰지 않는다.

- `WorkManagerIsolationContracts` 확인: source299245214cc76c433ba9ee6909c0f786027dc56b의 full workflow43527182302가 API35·36 각각135 unique Native(critical15+remaining117+별도격리3), 기존132 보존, 오류/실패/skip/중복0, host20 및 sync-state recovery17+cleanup17·killed holds pass0로 실제 통과했다. 공식 ZIP digest와 XML/격리 원문·machine 결과/host 원문을 root가 대조했다(auto-t19-root-native-proof.json). 이는 production Application 도입 전 T19 증거이며 T1의 production bootstrap·새 manifest·현재 네 graph audit를 대신하지 않는다.

- `AutomaticRetryContracts` **모델 불변식**: validated는 고정 메시지 IllegalArgumentException으로 invalid를 거절하고 모든 collection 입력/출력을 방어 복사한 불변 뷰로 제공한다. UUID/hash/date/instant 유효성, 정렬·중복 없는 날짜 1..7과 정확한 nullable observation keys, 비어 있지 않은 non-BLOCKED scopes와 scope fingerprint 일치, 자동 trigger, retriesUsed 0..3, claimVersion >= 0, createdAt <= updatedAt, canonical owner UUID를 검증한다. ACTIVE는 owner 존재 및 due/delay null 또는 owner 부재 및 due/delay 모두 존재만 허용하며 후자는 count 0/1/2에 30/60/120초다. terminal은 owner/due/delay null, EXHAUSTED는 count 3이다. checked arithmetic 실패는 invalid/store fail closed; now<due는 변이 없는 Deferred, 정각 이상은 CAS 성공 시만 count/version 증가다. 순수 validator는 persisted ledger나 실제 read 출처를 보증하지 않는다.
- `AutomaticRetryContracts` **preflight와 범위**: canonical gate와 정상 journal recovery 뒤 검증된 현재 generation 단일 SQLite snapshot에서 ACTIVE owner/due column만 indexed streaming한다(기존 100000 cap). 기존 partial UNIQUE `(generation,scope_key) WHERE status='ACTIVE'`의 generation ACTIVE scan을 EXPLAIN으로 검증해 충분하면 새 index를 추가하지 않는다. row 전체 검증은 별도 유지한다. owner 잔존 시 RecoveryRequired를 우선하며 process-death admission일 때만 기존 recovery를 재시도하고 live owner timeout takeover 금지. 그 외 미래 due 중 최대값으로 Deferred, 정확히 now면 Ready. BLOCKED/EXHAUSTED/COMPLETED는 전역 차단하지 않는다. Deferred는 Result.retry, auth/HC metadata/records/remote 및 count/version 포함 변이 0이다. Ready 후 fresh metadata로 scope를 결정하고 loadAutomaticBudget은 현재 scope의 ACTIVE/BLOCKED/EXHAUSTED 하나 또는 null을 반환, COMPLETED 제외, 중복 미종결은 손상으로 fail closed한다. generation stale은 Stale, invalid now/손상/unavailable은 고정 store 오류. 전체 budget 목록·JSON per-row decode·side DB·N+1 금지. 최종 claim에서 동일 scope CAS/due와 physical unknown/reconciliation을 다시 검사한다. 모든 설정 변경은 generation CAS를 유지한다.
- `AutomaticRetryContracts` **owner와 busy union**: SyncMutation의 automaticOwner 기본 null은 여섯 번째 payload가 아닌 검증 문맥이다. start/day/confirmation/pendingTriggers/finishAt 중 정확히 하나만 허용한다. 유일한 ownerless automatic 예외는 pendingTriggers만 있고 현재 generation과 일치하는 미완료 attempt id 및 기존 집합의 superset인 add-only union이다. store transaction의 현재 snapshot에 대해 검사해 동시 추가를 잃지 않으며 stale 시 기존 bounded session 재조회/재시도; stale과 invalid를 구분하고 malformed는 fail closed한다. 이 연산은 ledger/days/coverage/count/version을 바꾸지 않는다. 순수 validator도 finished attempt의 pending을 거절한다. 자동 day/confirmation은 matching ACTIVE generation/lineage/owner attempt/claimVersion/frozen scope/date 및 실제 read-derived revision과 day hash를 동일 write transaction에서 검사·commit한다. 모든 자동 day/confirmation에 owner를 전달한다. 일반 transact로 자동 start/finish 금지; dedicated claim/finish만 정상 시작/종료한다. recovery/config invalidation은 gate와 generation CAS 아래 좁은 private atomic 예외이며 live takeover가 아니다.
- `AutomaticRetryContracts` **finish/recovery**: 최초 version 0, 성공 reclaim만 +1, observe/finish는 유지한다. 정상 COMPLETED는 모든 frozen rows가 동일 scope checked EMPTY 또는 intended datasets CONFIRMED이고 blocker/unknown/REMOTE_STALE 없음이 같은 transaction에서 확인될 때만 허용한다. RETRYABLE은 기존 transient만: count 0/1/2에 30/60/120초, count 3은 EXHAUSTED와 미완료 날짜 blocker; 영구 BLOCKED 및 분류 불가 PENDING/interruption은 BLOCKED/no due, 불확실성과 확정 내역을 보존한다. eligible explicit manual recovery는 기존 네 eligible blocker만 reopen하고 비eligible 혼합이나 근거가 모호하면 거부하며 같은 transaction에서 해당 BLOCKED/EXHAUSTED lifecycle을 COMPLETED로 종료한다. counts, nullable observations, rows, prior/unknown/REMOTE_STALE 및 uncertainty를 보존하며 health success/coverage를 뜻하지 않는다. 새 status/column/retry rights/prune 금지. recovery API는 process-death admission과 gate에서 frozen rows에 afterInterruption 및 finish/BLOCKED를 원자 적용하고 count/vector/version 보존한다. stale은 변이 0; malformed/unavailable은 owner와 recoveryNeeded를 보존한다.
- `AutomaticRetryContracts` **오류 및 identity**: invalid claim은 input을 포함하지 않는 고정 IllegalArgumentException, stale CAS는 RetryClaim.Stale, finish/set invalid는 SyncCommit INVALID, stale은 STALE, unavailable은 기존 고정 store 오류다. attempt scheduledAt은 최초 proposal.plannedAt, reclaim은 now; startedAt은 claim now, finishedAt null, frozen scopes/dates, trigger, key/count, 빈 pendingTriggers로 만든다. metadata fingerprint freshness 비교는 T5 session/adapter 책임이며 DB가 HC를 읽지 않는다. identity는 revision/work ID/runAttemptCount가 아니다.
- `AutomaticRetryContracts` **hash golden**: 각 UTF-8 component를 unsigned 4-byte big-endian 길이 prefix 후 SHA-256 lowercase hex64. lineage components `automatic-retry/1`, `00000000-0000-4000-8000-000000000001` → `2ae9b57cbb27eccbfdf58955bbbeb1d0ee1bd81e0d2af0463f67c5c4aa54b034`. access는 `automatic-access/1`, availability 1/0, 정렬 granted 개수와 이름, 정렬 scope 개수와 type/scope 쌍. components `automatic-access/1,1,2,SLEEP,STEPS,2,SLEEP,SUMMARY,STEPS,SUMMARY` → `1c28d20a593cd831089fc8b7b157d529cf54146c376f293173f897d31b3bcd9f`; `automatic-access/1,0,0,0` → `fc7f546dd2128a4224bd2904de0cb8fddd24ae37a328ad3ebb604883bc05262b`. UUID canonical lowercase 검증. T2 golden tests는 독립 벡터를 사용한다.
- `AutomaticSummaryContracts` 단일 AggregateDuration은 입력 origins를 복사해 unmodifiable로 노출하며 값 equals/hashCode, derived nullable minutes를 제공한다. 음수 거절, 최대 Duration은 finite, 1440분 상한 없음. DTO null과 metric null은 blank, 실제 ZERO만 0.0. T5는 readScoped와 순수 SyncRevision.identify(normalize/CSV)만 source catch로 묶고 CancellationException 재전파, SourceReadException의 SOURCE_LIMIT_EXCEEDED/SOURCE_ACCESS_REQUIRED 보존, 나머지는 비 transient SOURCE_READ_FAILED. store commit 및 destination IO는 catch 밖이다. 해당 날짜 revision 실패 시 그 날짜 remote write 0; 앞서 완료된 날짜 write는 유지한다.

## 3. 추적성

아래 23행의 기준은 스펙 5절 원문이다. 테스트 이름은 실제 backtick Kotlin 함수 이름으로 사용한다. 한 행의 복합 기준은 해당 이름의 여러 fixture/경계 시나리오로 검증하고 하위 plan은 자기 행만 인용한다. ⑤는 기존 reader pagination/overlap·Drive root/privacy·history/layout regression을 재사용하며 새 과거 결함을 꾸며내지 않는다. 테스트 대역은 실제 계정/provider/배터리 수용 증거가 아니다.

| 스펙 기준 | 테스트 이름 (출처/층) | 충족 위치 |
|---|---|---|
| 새 목적지 구성은 한국 시간 어제를 등록일로 저장하고 기존 foundation 이력을 coverage로 사용하지 않으며 등록 전·오늘·미래 날짜를 읽거나 enqueue하지 않는다. | `Enrollment at KST midnight excludes old history today future and pre-enrollment days` (①·②/유닛 및 SQLite 통합) | core-store 완료 기준 |
| planner는 등록 이후 recent 두 날짜를 포함해 가장 오래된 eligible 누락으로 채운 최대 7개 고유 날짜를 오름차순으로 만들고 남은 gap·history blocker를 내구성 pending으로 유지한다. | `Recent two days reserve slots before oldest eligible gaps and retain blocked pending days` (①·②·④/유닛 및 SQLite 통합) | core-store 완료 기준 |
| 앱 시작은 자동 opt-in이 없으면 감지·표시만 수행하고 자동 opt-in이 있으면 같은 planner의 unique catch-up을 enqueue하며 읽기/업로드를 UI 복원 부수효과로 직접 실행하지 않는다. | `Opening with automatic off only displays gaps and automatic on enqueues one catch up` (①·④/통합 및 Compose E2E) | automation-ui 완료 기준 |
| EMPTY·제한된 category 확인·upload confirmation·연속 checked-through·uploaded-through를 구분하고 일부 허용 범위나 worker 실행 성공만으로 5종 전체 완료·현재성·gap 없는 coverage를 주장하지 않는다. | `Empty and partial grants never claim five categories upload success or continuous uploaded coverage` (①·②·④/유닛 및 Compose E2E) | core-store; automation-ui 완료 기준 |
| 매 실행 한국 시간 오늘에서 30일을 뺀 보수적 HC read floor 이전 또는 가용성/권한이 불명확한 날짜를 HISTORY_ACCESS_REQUIRED와 미확인 gap으로 유지하고 추정 최초 grant 시각을 저장하거나 원본 제한·오류·page/sample 초과를 빈 데이터로 처리하지 않는다. | `Dates below the conservative history floor and unknown access remain blocked instead of empty` (①·②·③/유닛 및 HC adapter 통합)  <br>`Scoped manual read queries only requested current grants and revoked access never becomes empty` (①·②·③·④/아래 manual-drive 작업 층)<br>`Scoped source limits remain blocked and legacy pagination overlap and CSV remain unchanged` (①·②·③·④/아래 manual-drive 작업 층)| core-store; manual-drive 완료 기준 |
| 자동 실행은 하루 전체 원시 심박 스캔을 재사용하지 않고 운동 구간의 bounded 상세 조회와 지원되는 요약 Aggregate를 사용하며 권한·세션·조회 의미가 불명확한 심박 범위는 BLOCKED/미확인으로 표시하고 수동 대상 조회와 coverage를 혼합하지 않는다. | `Automatic workout heart queries stay bounded and uncertain scopes block without mixing manual coverage` (①·②·③·④/HC adapter 통합 및 유닛) | automation-ui 완료 기준 |
| destination generation/date/dataset/revision과 시도·시작·확인 시각을 원자적으로 저장하고 저장 실패·강제 종료 전후 재열기에서 확인되지 않은 파일/날짜의 성공을 만들지 않는다. | `Reopening around atomic commits never confirms an unacknowledged file or date` (①·③·④/실제 SQLite·host process 통합) <br>`validatedHotJournalsRestoreCommittedStateThroughEveryStoreEntryWithoutFabricatingConfirmations`<br>`unsupportedOrMalformedRecoveredPairsPreserveOriginalBytesWithoutAWritableOriginalOpen`<br>`unsafeRollbackSidecarsAndChangedSourcePairsNeverFollowExternalPathsOrDeleteUnknownFiles`<br>`interruptedRollbackPreparationRetainsRawEvidenceAndSafelyRetriesWithoutInventingSuccess`| core-store 완료 기준 |
| canonical content가 같으면 generated_at 차이로 새 revision을 만들지 않고 원본 재읽기로 revision을 확인한 뒤에만 이전 확인을 재사용하며 달라진 snapshot은 새 revision으로 전환한다. | `Source reread reuses canonical revision despite generated time and advances changed snapshots` (①·②·④/유닛) | core-store 완료 기준 |
| 부분 실패·취소·결과불명 후 대상 reconciliation을 거쳐 pending 파일만 다시 처리하고 이미 확인된 동일 revision의 건강한 파일을 중복 create하거나 prior success를 지우지 않는다. | `After partial failure cancellation or unknown create reconciliation retries only pending files` (①·③·④/유닛 및 local TLS·provider 통합) <br>`Reread journal preserves unknown create uncertainty before reconciliation is interrupted again`<br>`Unknown null handle remains unresolved across changed revision and conservative phases`<br>`Unknown create survives empty roundtrip while fresh pending and proven targets remain usable`<br>`New scope preserves unknown create from another scope for the same physical dataset`<br>`Reduced grants adopt a freshly proven target despite another scope unknown create`<br>`Uncertainty queries exclude other dates and generations and fail closed on stale or unavailable reads`<br>`unknownCreateSurvivesRereadCancellationAndSqliteReopenWithoutBlindCreate`<br>`crossScopeUnknownCreatesSurviveNewJournalCancellationAndSqliteReopen`<br>`physicalUncertaintyReadRejectsMalformedOtherProfilesOrphansAndUnavailableDatabases` <br>`Drive reconciliation never writes and unknown folder or file creation never blind posts` (①·②·③·④/아래 manual-drive 작업 층)<br>`All active scope known and prior handles survive reopen and conflicting targets block` (①·②·③·④/아래 manual-drive 작업 층)<br>`allActiveScopeGenericUriAndPriorHandlesSurviveReopenWhileStaleCorruptAndOrphanReadsFailClosed` (①·②·③·④/SAF child 작업 층)<br>`SAF pause reset stale picker and double continue preserve journal and reject delayed results` (①·②·③·④/SAF child 작업 층)<br>`SAF recovery reopens blockers without clearing unknown creation or remote stale` (①·②·③·④/SAF child 작업 층)<br>`safExplicitRecoveryPreservesConfirmedFilesUnknownCreatesAndOldGenerationIsolationAfterReopen` (①·②·③·④/SAF child 작업 층) | core-store; manual-drive; saf-local T2·T4·T5·T7 |
| 이전 populated dataset이 비게 되면 잔존 원격 파일을 REMOTE_STALE로 표시하여 새 revision의 full-current를 막고 원격 삭제·완료를 꾸며내지 않는다. | `Disappearing populated datasets retain remote stale and prevent full current without deletion` (①·④/유닛 및 Compose E2E) | core-store; automation-ui 완료 기준 |
| 전경·주기·catch-up·중복 UI가 동시에 진입해도 공용 gate로 단일 실행만 시작하고 프로세스 손실 후 durable journal을 복구하며 설정 generation 변경 뒤 지연 결과가 새 coverage를 덮지 못한다. | `Concurrent manual periodic and catch up requests admit once and reject old generation callbacks after restart` (①·③·④/유닛 및 SQLite·worker 통합)  <br>`Prepared session admits one credential factory and busy callers only merge triggers` (①·②·③·④/아래 manual-drive 작업 층)| core-store; manual-drive; automation-ui 완료 기준 |
| 자동 기능은 기본 off이고 전경에서 목적지·범위·지연 가능성 설명과 별도 opt-in 후에만 약 24시간 unique periodic CoroutineWorker를 connected/battery-not-low 제약으로 구성하며 exact-time·당일 완료를 보장하지 않는다. | `Explicit automatic opt in creates one constrained daily CoroutineWorker without exact time promises` (①·②·④/worker 통합 및 Compose E2E) | automation-ui 완료 기준 |
| Drive 계정은 전경 AccountPicker로 명시적으로 선택하고 모든 authorization을 저장된 Account로 제한하며 계정 변경/제거 시 새 generation·동의를 요구하고 다른 계정으로 조용히 fallback하지 않는다. | `Account picker selection binds all authorization and account removal never falls back` (①·③·④/adapter 통합 및 Compose E2E)  <br>`Same issued token about proof must exactly match selected account before any raw health read` (①·②·③·④/아래 manual-drive 작업 층)<br>`Picker cancellation malformed values and stale ticket never configure or authorize` (①·②·③·④/아래 manual-drive 작업 층)| manual-drive 완료 기준 |
| worker는 background HC feature/permission과 현재 category grant를 재확인하고 authorize resolution·누락 token/scope·계정 오류는 BLOCKED로 남기며 interactive UI를 시작하거나 token·auth code를 저장하지 않는다. | `Worker missing background health grant token scope or account records blocked without interactive launches` (①·③/worker 통합) | automation-ui 완료 기준 |
| 일시 네트워크/허용된 service 실패만 exponential backoff로 최초 실행 외 최대 3회 재시도하고 영구 실패·불명확 create는 맹목 재전송하지 않으며 retry 소진은 다음 명시적 trigger까지 visible pending/blocked로 남긴다. | `Only transient batch failures retry at most three times and uncertain creates reconcile first` (①·②·③·④/유닛 및 worker·TLS 통합)  <br>`Explicit manual recovery reopens eligible blockers without deleting file uncertainty or remote stale` (①·②·③·④/아래 manual-drive 작업 층)| automation-ui; manual-drive 완료 기준 |
| 자동 opt-out·설정 변경·reset은 queued work와 현재 generation의 admission을 무효화하고 이미 발행된 원격 쓰기를 취소로 rollback했다고 표시하지 않으며 기존 이력 삭제와 설정/coverage reset 범위를 각각 정확히 안내한다. | `Opt out configuration change and confirmed reset invalidate old admission while preserving history reset scope` (①·③·④/SQLite·worker 통합 및 Compose E2E)  <br>`Manual configuration and reset use generation CAS and never erase foundation history` (①·②·③·④/아래 manual-drive 작업 층)| manual-drive; automation-ui 완료 기준 |
| Drive destination은 기존 drive.file·marked private owned My Drive·ambiguous target 거부·deterministic same-file 갱신·확인 응답 계약과 CSV v0.1을 유지한다. | `Drive tracked batches preserve private target validation deterministic same file and CSV schema` (①·③·⑤/기존 core 회귀 및 local TLS 통합)  <br>`Fresh Drive ACK permits absent checksum but rejects wrong checksum and changed target identity` (①·②·③·④/아래 manual-drive 작업 층)| manual-drive 완료 기준 |
| SAF local 구성은 별도 privacy/tree 동의·실제 granted flags·persisted permission·합성 probe가 확인된 capability만 활성화하고 미검증 provider/background capability는 disabled 또는 BLOCKED로 유지한다. | `safConfigurationEnablesOnlyConsentedPersistedGrantedAndSuccessfullyProbedLocalCapabilities` (①·②·③/합성 DocumentsProvider 통합 및 Compose E2E)<br>`syntheticProviderGrantsTargetUidTreeAccessAndResolverReadsTheSeededDocument` (①·②·③·④/SAF child 작업 층)<br>`SAF selection and restore never export without explicit foreground continue` (①·②·③·④/SAF child 작업 층)<br>`SAF intended category snapshots reject edited picker or stored scope mismatches and zero grants without shrinking partial grants` (①·②·③·④/SAF child 작업 층)<br>`safActualActivitySelectionConfiguresWithoutExportingAndExplicitContinueConfirmsOnlySelectedLocalTarget` (①·②·③·④/SAF child 작업 층)<br>`safActualActivityRejectsStaleTreeResultsAndPausedRevokedGrantsWithoutDriveFallback` (①·②·③·④/SAF child 작업 층) | saf-local T1·T3·T9·T5·T6·T7 |
| SAF 동일 날짜의 더 짧은 재내보내기는 같은 대상에 정확히 truncation/readback되고 duplicate·moved/deleted·grant 철회·write interruption은 suffix 재생성이나 false success 없이 reconciliation/명시적 복구로 연결된다. | `SAF shorter overwrite reads back exact bytes and ambiguous revoked moved deleted interrupted targets require recovery` (①·②·③·④/합성 DocumentsProvider 통합)<br>`SAF reconciliation performs no writes and reservations reject changed or reused inputs` (①·②·③·④/SAF child 작업 층)<br>`SAF encoded opaque document URIs retain target resolver identity after SQLite reopen` (①·②·③·④/SAF child 작업 층) | saf-local T2·T4·T7 |
| home은 현재 목적지·등록 이후 gap·연속 확인 범위·마지막 업로드·recent freshness·queued/blocked 사유와 다음 허용 동작을 구분하고 stale/영구 실패가 과거 성공에 가려지지 않으며 EMPTY를 업로드 완료로 쓰지 않는다. | `Home shows gaps coverage freshness last upload blockers and stale separately from prior success and empty` (①·③·④/유닛 및 Compose E2E) | automation-ui 완료 기준 |
| light/dark·큰 글자·Compose semantics 검증에서 동의·계정/폴더 선택·자동 설정·수동 실행·재시도·reset 확인에 도달할 수 있고 실제 TalkBack·Galaxy 관찰은 별도 미확인으로 남긴다. | `Consent account tree automatic manual retry and reset remain reachable in dark light and large text semantics` (①·②·⑤/Compose E2E)  <br>`Manual consent picker authorization and explicit continue remain reachable in light dark and large text` (①·②·③·④/아래 manual-drive 작업 층)<br>`safPrivacyTreeContinueRetryAndResetRemainReachableInLightDarkAndLargeTextWithoutUriDisclosure` (①·②·③·④/SAF child 작업 층) | manual-drive; saf-local T9·T7; automation-ui 완료 기준 |
| app-private 상태/backup 제외와 로그/진단 allowlist를 검증하여 raw 건강 데이터·CSV·token·계정/기기/문서 식별자·URI·provider 본문을 공개 로그·fixture·CI artifact에 포함하지 않는다. | `Private state and allowlisted diagnostics exclude health payload credentials identifiers and provider bodies` (①·③/저장소·TLS·provider 통합 및 정적 검증)  <br>`Manual credentials never enter saved state store diagnostics or public fixtures` (①·②·③·④/아래 manual-drive 작업 층)| 전체 검증 3 완료 기준 |
| 실제 store 재열기·손상/미지원 상태·실행 취소·동시 admission·local TLS·합성 provider·worker 제약/권한·UI 회귀와 APK/lint 검증을 수행하고 각 PR의 독립 최신 head 검토를 마친 개발 완료와 Galaxy/계정/provider/Doze/배터리/TalkBack 수용 대기를 별도로 보고한다. | `Real store cancellation admission TLS provider worker and UI suites pass with device acceptance separately pending` (①·③·④·⑤/통합·E2E; review와 실제 기기는 비자동 항목)  <br>`Manual flow rejects stale proof pause reset and double continue while retaining legacy layout and history regressions` (①·②·③·④/아래 manual-drive 작업 층)| 전체 검증 1·2·4·5 완료 기준 |

## 4. 분할과 순서

| 작업 plan | 전달하는 동작 | 맡는 기준(묶음) | depends_on |
|---|---|---|---|
| core-store | 날짜별 source→revision→reconcile→durable 확인을 합성 destination과 실제 store로 증명 | 날짜·의미, 내구성·복구 | — (구현 전 PR #15 병합 head 반영) |
| manual-drive | 계정을 고른 설정과 명시적 전경 bounded catch-up·개별 확인·reset | 수동 시작, HC blocker, 계정, Drive, 복구 | core-store |
| saf-local | privacy/tree 구성부터 검증된 수동 local upsert/recovery까지 | SAF 구성·overwrite·capability | manual-drive |
| automation-ui | 별도 opt-in부터 constrained worker/catch-up·차단/재시도·전체 상태 접근성까지 | 자동 실행, 화면·보안·증거 | saf-local |

체크포인트: manual-drive 머지 후 — 실제 SQLite 재열기/host 중단, 동시 admission과 fresh account authorization, local TLS의 per-file 확인·불명확 create 복구, 기존 P0/V1 이력 회귀를 함께 실행한다. automation-ui 머지 후 — 전체 검증과 독립 적합성/보안/코드 리뷰 및 최신 head 재검토를 완료한다. 각 하위 plan은 2~3 T마다 부분 통합 체크포인트를 둔다.

하위 plan 크기 제약: PR당 T 개수는 제한하지 않는다(소유자 위임 확인). 각 T 변경 파일 1~5개는 반드시 지키고 최종 통합 PR의 UI/worker/문서도 별도 검토 가능한 T로 나눈다. PR 자체가 검토하기 어렵게 커지면 최대 5번째 통합 PR로 분리하도록 에픽을 먼저 보정한다. 계획/실행 증거 갱신은 해당 제품 파일 한도와 별도지만 거대한 제품 변경을 문서 작업으로 숨기지 않는다. core-store는 모델·planner 검증, revision/destination 실행 검증, 실제 store 검증, process/gate 통합으로 분해한다. manual-drive는 authorization/configuration, tracked Drive reconciliation, 전경 실행/화면, 실제 통합 검증으로 나눈다. saf-local은 합성 provider 도입+starter(별도 T), SAF adapter/probe, tree UI 연결, 실패 통합으로 나눈다. automation-ui는 dependency+worker starter 도입(별도 T), automatic health scope/adapter(별도 T), worker/scheduler, UI/config invalidation, 통합 회귀, 문서 증거로 나눈다. 파일 수가 맞지 않으면 하위 T를 더 작게 나누되 PR 수를 늘리기 전에 에픽 의존성/추적성을 갱신한다. UI 새 파일을 독립 화면 조각으로 분리하여 마지막 PR의 파일 수·세션 부담을 제한한다.

SAF child T9는 state/actions를 작은 UI DTO 파일로 분리하고 pure widget 및 기존 T7 semantics test만 맡는다. root가 2026-11-13 exact 계약을 기존 V1 자동승인으로 승인했으며 T3/T4와 disjoint 파일에서 병렬 작성한다. T5 controller는 여전히 T4 뒤이고 T9의 단일 DTO를 소비한다. T6는 T5와 T9가 모두 완료된 뒤다. T7은 actual Activity/SQLite/journal 복구와 최종 양API 전체 gate를 유지한다. 공유 build/emulator 실행은 root gate로 직렬화한다. SAF 설정·실행·provider 수용 gate는 불변이다.

## 5. 전체 검증

1. 각 PR에서 android/ 기준 `./gradlew -PcoreOnly :core:test`, `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`를 수행한다. 변경된 의미의 개별 테스트는 해당 하위 plan에서 --tests 또는 instrumentation class로 실행하고 전체 affected suite는 위 명령으로 확인한다. 기존 테스트로 충분한 경계는 중복 작성하지 않는다. 실제 SQLite 재열기/commit failure, cancellation, duplicate admission, TLS, 합성 provider는 mock 호출 횟수만으로 대체하지 않는다.
2. API35/36 emulator에서 repo root의 `GITHUB_EVENT_NAME=workflow_dispatch TEST_SCOPE=full bash android/ci/run-emulator-validation.sh`를 실행한다. T19의 고정 registry가 helper 설치 class와 normal-before/helper/normal-after를 각각 별도 force-stop/instrumentation process로 검증하고 기존 critical/remaining/history/sync-state와 등록된 새 retry host를 모두 보존한다. 필터 없는 단독 Gradle 전체 instrumentation은 helper 격리를 우회하므로 이 gate의 증거로 인정하지 않는다. crash harness는 합성 state만 쓰고 실제 PID 변경·read-first·실패/오류/skip/중복0·killed hold 성공 산입0을 검증한다. emulator/KVM 불가이면 해당 CI head/결과/URL을 기록하며 실제 계정·Galaxy 수용으로 표현하지 않는다.
3. automation-ui 도입 T에서 `./gradlew :app:dependencies --configuration debugRuntimeClasspath`, `./gradlew :app:dependencyInsight --dependency androidx.work --configuration debugRuntimeClasspath`, coroutines/stdlib/Guava별 dependencyInsight와 `./gradlew :app:processDebugMainManifest :app:assembleDebugAndroidTest`를 실행한다. 선택/해결 좌표·AAR metadata·compiler/runtime 호환 및 single-process/initializer/backup/permission manifest를 확인한다. 기존 docs/V1/dependency-audit.json 형식으로 releaseRuntimeClasspath/debugRuntimeClasspath/debugUnitTestRuntimeClasspath/debugAndroidTestRuntimeClasspath의 실제 ModuleComponentIdentifier 전체를 수집하여 Maven 좌표를 OSV querybatch로 조회하고 새 dependency-audit.json에 source head·시각·scope·errors·finding/disposition을 남긴다. BOM/variant와 instrumentation 의존성을 포함하며 source reachability/OS 취약점 검증과 구분한다. 조회 불가는 zero findings로 쓰지 않는다. WorkManager starter test는 실제 test context 초기화와 최소 CoroutineWorker 1회 기동/완료를 보여야 한다. 합성 provider starter도 실제 ContentResolver 연결·읽기 1회를 증명한다. 공개 artifact는 allowlist 합성 결과만 보존하고 토큰/CSV/건강값/account/device/document/URI 식별자/provider 예외가 포함되지 않는지 source·fixtures·CI 업로드 경로를 점검한다. app-private 최소 account/tree binding과 backup 제외는 실제 noBackup 경로/manifest 검사로 확인한다.
4. light/dark와 큰 글자에서 Compose semantics로 동의/선택/자동/수동/재시도/reset을 스크롤하여 실행한다. 상태 restoration 자체가 read/upload/authorize를 실행하지 않는지 확인한다. 자동 off의 재시작, auto on unique catch-up, connected/battery-low 지연, HC feature/grant 부재, 운동/심박 개별 grant·세션 없음·cross-midnight interval·경계 sample·공유 query/page/sample 초과·start-time 선택 불명확 시 상세HR BLOCKED와 나머지 category 진행, null token/scope/resolution, transient budget 0~3 후 소진, 다음 명시 trigger, opt-out/변경/reset 중 늦은 callback, worker stop/restart를 합성 환경에서 검사한다. 실제 TalkBack/Galaxy/account/local provider/reboot·Doze·force-stop·배터리·늦은 원본 도착은 기기 절차와 pending으로 별도 기록한다.
5. 각 PR은 최신 head/full relevant diff를 작성자·수정자와 다른 fresh-context 읽기 전용 세션이 검토한다(AGENTS.md 명시 요구). spec conformance에는 approved spec+implementation만 주고 plan/기존 review 결론을 주지 않는다. 한국어 PR 댓글에 reviewed commit·범위·findings/severity·실제 검증/한계·merge 적합성을 남긴다. blocking 수정 후 새 head 재검토를 받고 최종 reviewed head와 CI head를 일치시킨다. source 완료/개발 병합과 physical acceptance/release를 분리한다. 실행 보고서의 실제 테스트 이름과 23행 추적성을 대조하고 ADR Proposed는 독립 적합성 통과 후에만 Accepted로 바꾼다. 이 문서 PR도 동일 리뷰 대상이다.

이 문서 작성 중 Gradle/테스트/기기/계정 작업은 실행하지 않았다. source·설치 API·공식 문서 조사와 문서 정합성 확인만 했다. 위 명령은 실행 단계의 검증 방법이며 PASS 보고가 아니다.

## 6. 위험과 롤백

별도 DB를 되돌릴 때 foundation history는 보존한다. 이미 생성한 새 state DB를 구버전 코드가 무시하는 것은 remote rollback이 아니며 재설치/백업에서 자동 재활성화하지 않는다. UI 설정을 바꾸기 전에 generation invalidation을 commit하고 queued work를 취소하여 이전 account/tree로 새 admission을 막는다. 취소·PR revert는 이미 발행한 remote write를 되돌리지 않는다.

schema/codec를 잘못 읽으면 사용자 명시 reset 전까지 blocked로 남긴다. reset 실패·unknown schema를 자동 empty로 치환하지 않는다. metadata 크기 상한에 도달하면 admission 실패를 표시하고 confirmed/prior success를 삭제해 공간을 만들지 않는다. future schema 지원은 새 ADR/계획 보정이 필요하다.

SAF capability 오판은 truncate 실패나 suffix 중복을 만들 수 있으므로 probe/readback 실패 시 비활성화하고 기존 target/uncertain journal을 보존한다. remote target 안전성은 검증 시점 뒤 외부 변경까지 보장하지 않으므로 매 실행 재검증하며 지속 privacy 보장이라고 말하지 않는다. WorkManager 도입은 별도 T에서 audit/starter를 통과하지 못하면 중단한다. rollback은 자동 off·generation invalidation·work cancel 후 해당 PR을 revert하고 private metadata를 보존하여 사용자가 명시 복구하도록 한다.

## 복구 기록

| 작업 | 회차/2 | 실패 증거·원인 | 새 근거·수정 가설 | 계획 보정 전→후·유지 기준 | 수정 파일·보존 대상 | 검증 명령·결과 | 실행자·다음 조치 |
|---|---|---|---|---|---|---|---|

## 환경 준비 기록

| 원인·근거 | 준비 조치 | 조치 사용/1 | 확인 명령·결과 | 확인 사용/1 | 다음 조치 |
|---|---|---|---|---|---|

실행 중 기술 보정(2026-11-05): core-store T3의 generic PENDING은 자동 재시도 권한을 뜻하지 않는다. 승인 spec의 제한된 transient retry와 불명확 create 금지를 보존하기 위해 고정된 목적지 실패 사유3종과 RETRYABLE outcome을 저장 전 계약에 추가한다. provider 메시지/예외 cause/개인정보는 경계에 전달하지 않는다. 실제 Drive 오류 매핑은 manual-drive, worker budget 소비/백오프는 automation-ui 소유이며 core-store는 합성 경계의 분류·저장·planner 영구차단만 검증한다. source unknown 예외는 기존 SOURCE_READ_FAILED를 유지하고 service의 확인된 transient 매핑은 해당 adapter 계획에서 별도로 정의한다.

실행 중 검증 배치 보정(2026-11-05): 공용session의 실제SQLite concurrency tests를 별도 SyncExecutionSessionInstrumentedTest.kt에 둔다. 기존 SQLstore fixture와 성공기준을 삭제하지 않으며, 측정된 metadata N+1 보정과 session 구현의 파일 충돌을 피한다. 별도 Android synthetic store 측정은 full/partial·30/365/3650행에서 실제 비용을 확인했고, 그 근거에 따라 core-store 안에서 streaming bulk 조회를 보정한다. 기존4개PR/WorkManager·실계정·SAF 범위와 승인 spec은 바꾸지 않는다. 계정없는CI의 GoogleSdkSetup ANR은 image/permission/assertion을 바꾸지 않고 해당 setup package의 시작 간섭만 제거해 재검증한다.

PR16 Important Q1 보정: SQLite hot rollback journal은 정상원자복구에도쓰기권한이필요하다. 정상 READONLY fast path는 유지하되 typed readonly 실패에서만 noBackup raw DB/journal pair를보존하고별도work pair의native rollback·schema/binding/모든active행검증·원본불변확인뒤에만original native rollback을허용한다. unknown/corrupt 입력을자동repair/reset하지않고raw생바이트를보관한다. 정상읽기=항상원본불변이라는절대문구만이한정crash-recovery 예외로보완하며모델/schema/권한/외부서비스범위는불변이다. ADR009 Proposed가ADR006의나머지결정을승계한다. 실제Android cache-spill 미수정실패→수정후같은assertions PASS와최종독립재검토전완료가아니다.

### SAF component 대기 병렬화 승인

소유자의 병렬 진행 지시와 기존 V1 자동승인 범위에서 saf-local의 T4 안정 source 검증 뒤 T5 두 파일 작성과 T10 actual component 검증을 병렬 진행한다. T10은 원래 T4 양API provider/destination gate를 소유하고 T6 actual Activity 연결의 필수 선행조건이다. full Native/host/독립 최종 검토/PR 댓글/머지 기준은 그대로 유지한다. shared DTO·외부 계약·권한·데이터·의존성·schema 변경은 없다.

### SAF Terra 작업 단위 분리

소유자의작업별Terra새세션지시에맞춰saf-local T6의제품배선3파일과새실제Flow테스트1파일을T6→T11로순차분리한다. 원래실제두행동/8methods 양APIgate는T11에그대로유지하고T7은T11뒤다. CI두파일은기존독립T8이소유한다. 원래성공기준·단일목적지/동의/권한/SQL/URI정책및전체최종검증은바꾸지않는다.

### SAF metadata 결과와 kind projection 보완

기존same-generation결과보존·단일현재목적지기준의실제source불일치를교정한다. saf-local의T6 UI3파일과T12 controller/JVM2파일은서로겹치지않고T11 actualflow는둘을의존한다. committed kind는현재generation의strictprivatebinding으로판정하며draft·UNKNOWN을다른목적지완료로표시하지않는다. 결과보존은새범위/세대완료로승격하지않는다. 사용자기존V1자동승인에따라범위·권한·schema·최종fullgate는불변이다.

### SAF 소프트웨어 실행·스펙 검증 완료 — 2026-11-13

소스 `bbde0da5efbc6a6363bdd5f033128e5e2f5770b5`의 PR18 전체 run `43513509886`에서 API35·36 각각 실제 Native132/기존host20/sync-state recovery17·cleanup17 및 killed holds0을 확인했다. JVM run43513510015는core84+app97=181이며root가두ZIP digest/원본XML의실제class·method집합/실패·오류·skip·중복0과hostraw를직접대조했다. 승인스펙과코드/원본실행증거만받은새Terra세션은SAF소프트웨어범위PASS로판정했다. 계획추적성은child의실제실행이름에맞춰대조한다.

SAF의실행계획은done이나PR최신head품질·보안검토/댓글/실제reviewedmerge는별도gate다. 자동화는그실제merge뒤검증된WM기반6파일/증거를통합하고parent·spec·ADR를repin한뒤시작한다. 전체V1스펙상태는approved로유지하며자동화·실제Galaxy/provider/reboot/계정/TalkBack/P0수용을완료로표시하지않는다.

### 자동화 실행 기준 준비

SAF 실제 merge9ab774b 이후 검증된WM6개Android파일과기존실행/audit기록을6c5f5b0에통합했다. API/prod구현을추측으로시작하지않고공통계약·ADR010Proposed를이부모에통합하고child의actualrevision을고정한뒤독립계획검토와기존V1자동승인범위에서T19부터실행한다. 전체19task와5PR체크포인트를유지하고실제Background/계정·기기활성화는포함하지않는다.

### 자동화 준비 교정2 — 격리 runner와 fresh worker session

독립실행준비검토가child10개명령/전체검증과부모전체검증의단독Gradle전체instrumentation이T19helper격리를우회함을발견했다. 모든전체Native증거는기존회귀와host를보존하는registry기반fullrunner로통일한다. 부모/ProposedADR008의동일session표현도manual기존session과worker별freshreader/session+동일canonicalgate로정합화한다. 기존19task/스펙/실제수용pending을유지하며원래준비교정1이력뒤누적2로기록한다.

### T19 구현 검토 교정3 — 격리 invocation 생명주기·증거 보존

3510bee 독립 검토에서 connected teardown 뒤 재설치 누락, 실패 raw의 PID 외 개인정보 미차단, 다중 top-level class inventory 오귀속, timeout 뒤 기기 invocation 정리 누락을 확인했다. 기존 135 unique 예상 identity·모든 기존 회귀·bounded 실제 프로세스 입증·공개 개인정보 금지 기준을 유지하며 T19 세 파일 중 host script만 국소 교정한다. 준비 교정1·2를 보존하고 이번을 원래 T19의 교정3으로 누적한다. 기본 두 회차 이후의 새 근거를 갖춘 같은 V1 가역 교정은 소유자 자동 승인 위임(AGENTS.md)에 따라 수행하며 별도 독립 재검토와 양 API full 실제 검증은 유지한다. 원문 공개 요구는 승인 spec의 개인정보 금지 우선순위에 따라 검증된 합성 protocol 원문에 한정한다.

### 실제 PR19 병합 및 후속 공통 계약 준비 (2026-11-14 UTC)

PR19 current-head8b9f38e의 Native43534668761과 JVM43534668764가 모두 SUCCESS/CLEAN이며 양API135 unique/실패·오류·skip·중복0, history20 및 sync-state17+17/killedholdsPASS0을 root가 공식ZIPdigest/원본으로 대조했다. 별도 fresh 품질·보안·spec slice 검토와 한글댓글6869176673 후75cc443702a3467c87268d49ce52d4d75b2ed8a5로 정상병합했다. master merge tree가 검증 head tree와 동일함을 확인해 이 준비worktree를 fast-forward했다. T1은 완료이며 전체V1/실기기 수용은 미완료다. 구체화된 후속계약은 제품 구현 전에 parent/ADR Proposed를 commit하고 child parent_rev를 재고정·19brief 재조립·fresh 독립readiness 검토를 수행한다. 기존 준비교정1·2와 원래task별 누적회차를 보존한다.
