---
plan_id: V1
title: Durable manual history and status-first home
kind: single
status: in_progress
approved: 2026-11-01
author: Codex
created: 2026-11-01
target: nyeonu/health-app/android
spec: spec.md
spec_rev: 3e50cb91633d88402b236bdb2d5924bec9103991
tasks:
  - id: T1
    title: Establish real SQLite reopen test harness
    depends_on: []
    status: done
    tier: standard
  - id: T2
    title: Define bounded history transitions
    depends_on: [T1]
    status: done
    tier: standard
  - id: T3
    title: Commit history atomically
    depends_on: [T2]
    status: in_progress
    tier: standard
  - id: T4
    title: Serialize history session recovery
    depends_on: [T3]
    status: done
    tier: standard
  - id: T5
    title: Connect manual execution to durable history
    depends_on: [T4]
    status: done
    tier: standard
  - id: T6
    title: Render status-first home and deletion recovery
    depends_on: [T5]
    status: done
    tier: standard
  - id: T7
    title: Validate lifecycle and accessible UI states
    depends_on: [T6]
    status: in_progress
    tier: standard
---

# Durable manual history and status-first home

## 1. 선택한 방식과 이유

Use platform SQLite for one bounded history envelope in the app's no-backup private directory. No Room, DataStore, database library or permission is added. The envelope contains only latest attempt and latest confirmed success; it is not a log table. SQLite's explicit transaction boundary makes replacing both records, retaining the prior committed envelope on rollback, and acknowledging empty-state deletion testable with actual close/reopen operations. Store version and payload are committed together. Prefer platform transactions over a custom journal/rename protocol; SharedPreferences does not provide the explicit transaction/test boundary selected here, and a new ORM would add schema/framework cost for one row.

Keep persistence adapters in app, and value/codec/recovery decisions in core. Reuse the existing Kotlin JSON dependency with explicit field parsing, avoiding a serialization compiler plugin. One app-session coordinator serializes local reads/writes on IO, owns busy/generation guards and gates existing manual effects; ViewModel remains the Android lifecycle adapter. Extract a stateless Material 3 screen so every visual state can be tested without real accounts or Health Connect.

**ADR candidate:** persisted format/version policy and SQLite transaction boundary. After plan approval, record a Proposed ADR with adr-writer; do not mark this design Accepted until conformance verification passes.

**Entry disposition (2026-11-02):** the owner approved the plan and instructed continued workflow execution, corrected original-source comparison as already performed, and explicitly directed that all other unverified P0 items remain unverified. Proceed with this bounded V1 development/testing; do not claim P0 completion or release approval. No source values, key material or new device results are recorded here.

## 2. 공통 설계

### 변경 파일

Paths below are relative to `android/`. Existing directories and modified files were inspected; all other paths are proposed new files. Tasks execute sequentially because integration/test ownership overlaps.

| File | New/edit | Purpose | Task |
| --- | --- | --- | --- |
| `app/src/main/java/com/ny/my/health/HistoryDatabase.kt` | New | Private DB opening, schema, transaction helper | T1 |
| `app/src/androidTest/java/com/ny/my/health/HistoryDatabaseInstrumentedTest.kt` | New | Harness open/close/reopen smoke | T1 |
| `core/src/main/kotlin/com/ny/my/health/core/SyncHistory.kt` | New | Values, store port, codec, pure recovery/transition rules | T2 |
| `core/src/test/kotlin/com/ny/my/health/core/SyncHistoryTest.kt` | New | State/codec boundaries | T2 |
| `app/src/main/java/com/ny/my/health/SqliteHistoryStore.kt` | New | Transactional read/replace/reset adapter | T3 |
| `app/src/androidTest/java/com/ny/my/health/SqliteHistoryStoreInstrumentedTest.kt` | New | Actual rollback/reopen/corrupt/version tests | T3 |
| `app/src/main/java/com/ny/my/health/HistorySession.kt` | New | Serialized history operations and generation gates | T4, T5 |
| `app/src/test/java/com/ny/my/health/HistorySessionTest.kt` | New | Deterministic effects, races, store error cases | T4, T5 |
| `app/src/main/java/com/ny/my/health/SyncViewModel.kt` | Edit | Connect manual lifecycle/authorization to history | T5 |
| `app/src/main/java/com/ny/my/health/HealthHomeScreen.kt` | New | Stateless screen, details/dialog/failure recovery | T6 |
| `app/src/main/java/com/ny/my/health/MainActivity.kt` | Edit | Await durable authorization gate, then render extracted screen; keep SDK launchers | T5, T6 |
| `app/src/androidTest/java/com/ny/my/health/HealthHomeScreenInstrumentedTest.kt` | New | Synthetic screen states/screenshots/semantics | T6, T7 |
| `app/src/androidTest/java/com/ny/my/health/HealthAppInstrumentedTest.kt` | Edit | Update approved UI copy without weakening safety, then extend lifecycle evidence | T6, T7 |
| `../docs/V1/device-acceptance.md` | New | Galaxy force-stop/recovery/accessibility evidence template | T7 |
| `../docs/V1/execution-report.md` | New | Actual verification evidence and pending limits | T7 |
| `../.github/workflows/android-emulator-tests.yml` | Edit | Collect only synthetic PNG evidence in existing report artifact | T7 |

### 인터페이스

These are internal contracts, not new remote APIs. Implementations may add private helpers; semantic or public-shape changes require a recorded plan correction.

```kotlin
// SyncHistory — core, same file also owns codec and pure transition helpers
data class SyncHistory(val version: Int, val attempt: HistoryAttempt?, val success: HistorySuccess?)
data class HistoryAttempt(
    val date: LocalDate, val authorizationRequestedAt: Instant,
    val startedAt: Instant?, val finishedAt: Instant?, val phase: HistoryPhase,
    val coverage: Set<DataType>, val confirmedFiles: Int?, val failureCode: String?,
)
data class HistorySuccess(
    val date: LocalDate, val confirmedAt: Instant,
    val coverage: Set<DataType>, val confirmedFiles: Int,
)
enum class HistoryPhase {
    AUTHORIZING, AWAITING_UPLOAD, RUNNING, SUCCESS, EMPTY, PERMISSION_REQUIRED,
    FAILED, AUTHORIZATION_FAILED, AUTHORIZATION_ABANDONED, UPLOAD_NOT_STARTED, INTERRUPTED
}
enum class HistoryReadStatus { READY, ABSENT, UNSUPPORTED, CORRUPT, UNAVAILABLE }
data class HistoryRead(val status: HistoryReadStatus, val history: SyncHistory?)
interface HistoryStore {
    suspend fun load(): HistoryRead
    suspend fun replace(history: SyncHistory)
    suspend fun reset()
}
```

```kotlin
// SyncHistoryCodec — confirmed T2 API
object SyncHistoryCodec {
    const val VERSION = 1
    const val MAX_BYTES = 16384
    fun encode(history: SyncHistory): String
    fun decode(payload: String): HistoryRead
}
// SyncHistory.recover — confirmed T2 helper, same core file
fun SyncHistory.recover(): SyncHistory
// SyncHistory.complete — confirmed T2 helper, same core file
fun SyncHistory.complete(attempt: HistoryAttempt): SyncHistory
// HistoryDatabase — confirmed T1 API, Android app only
internal class HistoryDatabase(context: Context, databaseName: String = "sync-history.db") {
    internal val databaseFile: File
    internal fun openForRead(): SQLiteDatabase?
    internal fun openForWrite(): SQLiteDatabase
    internal fun <T> transaction(beforeCommit: () -> Unit = {}, block: (SQLiteDatabase) -> T): T
}
internal class UnsupportedHistorySchemaException : SQLiteException
// Corruption handler preserves SQLiteDatabaseCorruptException (fixed message, no provider text/cause).
```

```kotlin
// HistorySession — app, plain Kotlin; independent of Android Application and SDK classes
internal class HistorySession(
    store: HistoryStore, scope: CoroutineScope, invalidateCredentials: () -> Unit,
) {
    val state: StateFlow<HistorySessionState>
    suspend fun restore()
    suspend fun beginAuthorization(date: LocalDate, at: Instant): Long?
    suspend fun authorizationReturned(ticket: Long): Boolean
    suspend fun authorizationFailed(ticket: Long, failure: AuthorizationDiagnostics.Failure, at: Instant)
    suspend fun beginExecution(ticket: Long, date: LocalDate, at: Instant): Boolean
    suspend fun finishExecution(ticket: Long, result: SyncResult, coverage: Set<DataType>, at: Instant)
    suspend fun interruptExecution(ticket: Long, at: Instant)
    fun requestDelete(): Boolean
    fun cancelDelete()
    suspend fun confirmDelete()
    suspend fun retryDelete()
    suspend fun keepExistingAfterDeleteFailure()
    suspend fun reload()
}
// Operation gates plus readable committed data (part of HistorySession contract)
data class HistorySessionState(
    val read: HistoryRead, val operation: HistoryOperation,
    val deleteDialog: Boolean, val deleteFailed: Boolean,
    val retainedAfterDeleteFailure: Boolean, val error: HistoryError?,
)
enum class HistoryOperation { IDLE, LOADING, WRITING, DELETING }
enum class HistoryError { READ, WRITE, DELETE, RECOVERY_WRITE }
```

```kotlin
// HealthHomeScreen — stateless Compose UI; callbacks never launch effects during composition
@Composable
internal fun HealthHomeScreen(state: SyncUiState, actions: HistoryUiActions)
// HistoryUiActions — callback bundle declared in HealthHomeScreen.kt
internal data class HistoryUiActions(
    val requestHealthConsent: () -> Unit, val refreshPermissions: () -> Unit,
    val changeOptIn: (Boolean) -> Unit, val authorize: () -> Unit,
    val continueUpload: () -> Unit, val requestDelete: () -> Unit,
    val cancelDelete: () -> Unit, val confirmDelete: () -> Unit,
    val retryDelete: () -> Unit, val keepExisting: () -> Unit, val reload: () -> Unit,
)
```

### 결정 로직 위치

- `SyncHistory.kt`: companion/top-level pure codec validation, terminal transition and recovery functions. No Android, SQL, HTTP or filesystem imports. Same-file helpers follow existing core Kotlin conventions; do not introduce per-field validators or a mapper/config hierarchy.
- `HistorySession`: restore is explicit, local-only; no constructor auto-launch. Use provided scope lifetime for admission, never to restore credentials. Eligibility refers to live authorization/execution, not stale persisted phases after a failed terminal write. Confirmed deletion is allowed while idle awaiting upload and must clear prepared work; live authorization/execution and store operations reject deletion. Reject new authorization while prepared continuation exists. Reload is explicit and invalidates credentials/generation, and cannot run alongside live authorization/execution. Reject busy/stale user operations at arrival rather than queuing them to become valid later. A history read/write error blocks new authorization/execution until explicit successful reload/reset. On confirmed deletion invalidate credentials synchronously before the first store suspension. No automatic health/network effects on restore or keep-existing.
- `HistorySession.kt`: pure eligibility/operation-generation checks remain same-file helpers, separate from suspending store calls. It is the single in-process writer and does not claim cross-process/multi-instance safety.
- Authorization/execution start methods return acceptance only after commit; the in-memory generation ticket is never serialized. Credential invalidation is a callback invoked before deletion's first suspending operation, including failure paths. Session state includes no credential; a rejected/stale authorization callback must discard its token in ViewModel.
- On explicit continuation, compute yesterday once, persist that execution date and call existing `SyncCoordinator.sync(date)` with exactly that date. Do not independently recompute the day between metadata and read across midnight.
- `HealthHomeScreen`: actual T5 model exposes history: HistorySessionState?, transientRemoteStatus: SyncStatus?, transientConfirmedFiles: Int?. Use the committed history.success object for ALL success card fields; latest-attempt or transient counts must not replace its count. Actions: reloadHistory/requestDeleteHistory/cancelDeleteHistory/confirmDeleteHistory/retryDeleteHistory/keepExistingHistory; Activity retains SDK callbacks and only passes these renderer actions. Read the approved section-4 screen/deletion copy excerpt supplied with the brief, not a full plan/spec.
- `HealthHomeScreen.kt`: pure display projection combines latest outcome and independently committed success; never derive success count from the latest attempt.

- `HistoryDatabase`: Use app no-backup private storage; internal DB schema version 1, a single row keyed by 1 with payload format version and payload committed together; rollback journal and synchronous FULL. Read must not create a missing DB. Unknown user_version and unavailable/corrupt DB block writes; no destructive corruption handler or fallback. One transaction acknowledges only after successful endTransaction; precommit fault injection is internal to tests. Reset writes a supported empty envelope atomically, never unlinks files.
- `SyncHistory`: Failure-code allowlist is the existing four coordinator phase codes, SyncDiagnostics safe fallback SYNC_FAILED and exact AuthorizationDiagnostics.Failure enum names; never composed provider diagnostics. Instants are wall-clock values, so validate presence/parseability without imposing monotonic chronology. Codec encode/decode and same-file recover/complete helper names implement the approved state rules. Persist only latest attempt and latest success, format version 1, ISO instants/local dates, known enum coverage/phase, allowlisted errors and nonnegative or unknown counts. Parser cap is 16 KiB UTF-8; reject over-cap, invalid fields, duplicate/unknown categories and unknown enums. Unknown version is UNSUPPORTED, malformed payload CORRUPT; neither means empty. No token/account/file/record identifier or raw measurement.

- `T2Cases`: History codec rejects forbidden fields and oversized or malformed metadata (①/②, JVM); Later non-success outcomes retain independently committed prior success (①/②/④, JVM); Recovery distinguishes abandoned authorization from awaiting-upload interruption (①/②/④, JVM); Running recovery never invents terminal time or confirmed upload count (①/④, JVM); Terminal restoration preserves actual outcome and prior independent success (①/②/④, JVM and SQLite reopen)

- `T3Cases`: Committed manual success survives a fresh store reopen with coherent fields (①/④, SQLite integration); Failed result transaction reopens the prior committed success (①/③, SQLite integration; transient error UI in T5); Absent unsupported corrupt and unavailable stores have distinct reopen results (①/②/③, SQLite integration); Confirmed local deletion invalidates credentials and reopens empty without remote effects (①/④, JVM session plus SQLite integration); Acknowledged empty-state transaction reopens without attempt or success (①/④, SQLite integration); Interrupted reset reopens committed old or empty state without claiming deletion completed (①/④, SQLite integration fixtures plus actual Galaxy process-loss checklist); Stale generation cannot rewrite an acknowledged reset after recovery (①/④, JVM session plus SQLite reopen); Repeated empty deletion is local and persistence failure never becomes success (①/②/③, SQLite integration and JVM session); Terminal restoration preserves actual outcome and prior independent success (①/②/④, JVM and SQLite reopen); Reading incompatible corrupt or unavailable history never overwrites it (①/②/③, SQLite integration); Recovery-save failure shows derived interruption and local storage error (①/③, JVM session plus SQLite rollback integration)

- `T4Cases`: Restoring unfinished execution reports unknown outcome without retry (①/④, JVM session); Duplicate starts and delayed completions cannot overwrite acknowledged deletion (①/④, JVM session); Confirmed local deletion invalidates credentials and reopens empty without remote effects (①/④, JVM session plus SQLite integration); Cancelling delete confirmation preserves credentials and durable history (①/④, JVM session and Compose instrumentation); Busy sessions reject stale delete requests and explain disabled deletion (①/②/④, JVM session and Compose instrumentation); Confirmed deletion invalidates credentials before the first store suspension (①/④, JVM session); Failed deletion offers retry without claiming completion or restoring credentials (①/③, JVM session and Compose instrumentation); Failed deletion labels retained history while unreadable history remains unknown (①/②/③, JVM session and Compose instrumentation); Delete failure blocks new authorization until reset or explicit keep and reload succeeds (①/③/④, JVM session); Stale generation cannot rewrite an acknowledged reset after recovery (①/④, JVM session plus SQLite reopen); Repeated empty deletion is local and persistence failure never becomes success (①/②/③, SQLite integration and JVM session); Delete confirmation states its local-only scope and triggers no external deletion (①, Compose instrumentation and effect assertions); Unreadable history blocks upload until explicit successful reload or reset (①/③/④, JVM session); Recovery-save failure shows derived interruption and local storage error (①/③, JVM session plus SQLite rollback integration)

- `T5Cases`: Cancelled authorization has a request time and no execution start (①/④, JVM session); Failed running-state commit prevents every reader and uploader effect (①/③, JVM session); Failed result transaction reopens the prior committed success (①/③, SQLite integration; transient error UI in T5); Reopened history grants no consent token continuation or external execution (①/④, JVM session and Activity instrumentation); Restored success is labelled historical and never bypasses manual execution (①/④, Compose instrumentation and session); CSV headers ordering quoting and missing values follow v0_1; Empty and denied reads never upload or report success; Successful partial-grant sync uploads only authorized populated datasets (①/⑤, existing JVM regression suites; reuse PrivateDriveUploaderTest and reader suite too); Reopen after abandoned authorization or failed deletion never restores credentials (①/③/④, JVM session and Activity instrumentation)

- `T6Cases`: Details distinguish authorization execution and result times with partial scope (①/②, Compose instrumentation); Restored success is labelled historical and never bypasses manual execution (①/④, Compose instrumentation and session); Collapsed home shows current error prior success target day and next action (①, Compose instrumentation); Success card count date time and coverage come from one committed success (①/④, Compose instrumentation); Details render missing execution and terminal times without invented timestamps (①/②, Compose instrumentation); Latest failure and empty outcome remain distinct from prior successful upload (①/②, Compose instrumentation); Expanding details or restoring UI never authorizes reads or uploads (①/④, Compose instrumentation); Cancelling delete confirmation preserves credentials and durable history (①/④, JVM session and Compose instrumentation); Busy sessions reject stale delete requests and explain disabled deletion (①/②/④, JVM session and Compose instrumentation); Failed deletion offers retry without claiming completion or restoring credentials (①/③, JVM session and Compose instrumentation); Failed deletion labels retained history while unreadable history remains unknown (①/②/③, JVM session and Compose instrumentation); Delete confirmation states its local-only scope and triggers no external deletion (①, Compose instrumentation and effect assertions)

- `T7Cases`: Reopened history grants no consent token continuation or external execution (①/④, JVM session and Activity instrumentation); CSV headers ordering quoting and missing values follow v0_1; Empty and denied reads never upload or report success; Successful partial-grant sync uploads only authorized populated datasets (①/⑤, existing JVM regression suites; reuse PrivateDriveUploaderTest and reader suite too); Home actions remain reachable in both themes and enlarged text (①/②, Compose instrumentation; manual TalkBack checklist required); Synthetic state gallery captures all required history and execution states (①, Compose instrumentation and artifact review); Interrupted reset reopens committed old or empty state without claiming deletion completed (①/④, SQLite integration fixtures plus actual Galaxy process-loss checklist); Reopen after abandoned authorization or failed deletion never restores credentials (①/③/④, JVM session and Activity instrumentation)

### Format and commit policy

- Internal database schema version 1; fixed single-row table with key 1 and separate payload format version 1. ISO instants and local dates, enum-valued phases/coverage, nonnegative confirmed-file count or explicit unknown, fixed allowlisted failure category; no free-form provider text.
- Maximum two logical records (attempt/success), five unique known categories, and 16 KiB encoded envelope as a defensive storage/parser cap for this metadata-only format. Test cap and cap-plus-one; this is a resource bound, not a performance SLA. Invalid fields/unknown enum/oversized payload are corrupt; unknown format version is unsupported, not absent.
- Missing file is absent; inspect existence before opening so a read does not create/reset a missing or unreadable store. Do not install a corruption handler that deletes the database. Unknown DB `user_version` blocks ordinary writes. Explicit reset is permitted only when a supported transaction can safely commit empty state; otherwise deletion fails visibly.
- Run SQLite operations on IO under one session gate. Parameter-bind payloads; replace envelope in a transaction; acknowledge only after `endTransaction` succeeds. Default production rollback journal with `synchronous=FULL`; verify effective pragmas in the harness. Never use destructive fallback on an exception.
- Reset atomically writes a supported empty envelope, removing attempt and success together; it does not unlink/recreate the database, promise forensic erasure or touch external services. Acknowledged reset must reopen empty. Failure before commit reopens the previous committed envelope; interruption at commit reopens either valid old or valid new state, never claims completion solely from a prior UI intention.
- A transaction error leaves the session blocked/unknown until a successful reload/reset. Retain last readable committed success in memory with an error label, never imply a known rollback when commit outcome cannot be determined. Real reopen determines persisted state.
- Injectable **internal** precommit fault boundary permits actual transaction rollback tests. Production default has no injected failure. No exported test Activity, production secret/debug endpoint or provider-permission bypass is added.

### 관련된 암묵적 동작

- SQLite's standard begin/success/end transaction idiom commits only successful transactions; unfinished work rolls back. Verify adapter behavior with real reopen tests, not mocked commit calls. (Confirmed: [Android SQLiteDatabase](https://developer.android.com/reference/android/database/sqlite/SQLiteDatabase), transaction method documentation retrieved during planning.)
- SQLite transactions present atomic old/new state across interruption; this is not remote Drive transactionality or a claim of fault-proof hardware. (Confirmed: [SQLite transaction](https://www.sqlite.org/lang_transaction.html) and [atomic commit](https://www.sqlite.org/atomiccommit.html) documentation.)
- App lifecycle currently calls `resume`/`pause`; pause cancels active work and Google consent can itself pause the Activity. (Confirmed: `MainActivity.kt`, `SyncViewModel.kt`.) Preserve this distinction; cancelling execution must not cancel the independent bounded local terminal write before recording interruption.
- ViewModel coroutine scope is cancelled on destruction; process death provides no guaranteed final callback. Recovery must derive from committed state, not depend on `finally` running. (Framework lifetime behavior to reconfirm against the installed lifecycle sources before T5; assumption, task tier standard.)
- Compose Activity recreation is not proof of OS process death. (Confirmed: existing account-free instrumentation uses `ActivityScenario`; Galaxy force-stop remains separate evidence.)

## 3. 추적성

Actual method names below index the approved criteria. Current-source JVM84 and Android40 passed with zero failures/errors/skips;56 PNG artifacts were downloaded and representative images reviewed. Actual Galaxy process-loss and TalkBack observations remain pending; semantic tests do not substitute for those observations.

| Specification criterion (verbatim) | Actual test name / remaining observation | Fulfilled in |
| --- | --- | --- |
| After confirmed manual upload and process recreation, the UI restores its reporting date, confirmation time, granted-category coverage and confirmed-file count as historical local success. | committedManualSuccessSurvivesFreshStoreReopenWithCoherentFields; persistedSuccessSurvivesFreshActivityAndViewModelWhileConsentAndPreparedContinuationReset | T3 completion |
| The history stores only the latest attempt and latest confirmed success, with a format version and bounded metadata, and contains no token, account/file/record identifier, raw measurement, CSV body or provider error text. | History codec rejects forbidden fields and oversized or malformed metadata | T2 completion |
| An attempt records authorization-request time separately from actual execution-start time, so cancelling authorization does not create a fictitious started upload. | 승인 시작 저장 실패는 SDK 실행을 막고 취소 결과는 요청 시각만 기록한다 | T5 completion |
| A failed local start-state write prevents health reading and remote upload, and an uncommitted result is never presented as a durably recorded success. | 실행 시작 저장 실패는 실제 효과 경계의 읽기와 업로드를 모두 막는다; 원격 확인 뒤 결과 저장 실패는 일시 증거와 이전 영속 성공을 분리한다 | T5 completion |
| Failure or interruption while persisting a result preserves the previous committed record and reports an explicit local-history failure without asserting that already-issued remote writes were rolled back. | failedResultTransactionReopensPriorCommittedSuccess; 원격 확인 뒤 결과 저장 실패는 일시 증거와 이전 영속 성공을 분리한다 | T3, T5 completion |
| A persisted started execution without a committed terminal outcome is shown as interrupted or indeterminate after process recreation, with no automatic retry and no fabricated success. | 미완료 실행을 복원하면 재시도 없이 결과 미확정 중단을 저장한다; persistedRunningReopensInterruptedWithoutFinishOrCountAndPreservesPriorSuccess | T4 completion |
| After process recreation, upload consent is unchecked, no token or prepared continuation is restored, and no health read or network write occurs until fresh manual consent/authorization/continuation. | 재열기와 지연 승인 결과는 토큰이나 명시적 계속 및 외부 실행을 복원하지 않는다; persistedSuccessSurvivesFreshActivityAndViewModelWhileConsentAndPreparedContinuationReset | T5, T7 completion |
| A later failed, empty, permission-required or interrupted attempt updates latest-attempt status while retaining the previous independently confirmed success. | Later non-success outcomes retain independently committed prior success | T2 completion |
| Duplicate UI actions cannot run overlapping manual executions or let a stale completion overwrite a newer local forget operation. | 중복 시작과 지연 완료는 확인된 삭제를 덮어쓰지 못한다 | T4 completion |
| Missing history starts as no local history, while unsupported or corrupt stored history produces a recoverable explicit state and never fabricated zero measurements or success. | absentUnsupportedCorruptAndUnavailableStoresHaveDistinctReopenResults | T3 completion |
| The UI distinguishes authorization time, actual execution start, latest outcome and previous confirmed reporting date, and indicates partial authorized scope rather than complete five-category coverage. | detailsDistinguishAuthorizationExecutionAndResultTimesWithPartialScope | T6 completion |
| Restored history is labelled as local historical evidence whose current Google-account association and upstream completeness are unverified, and is never used to skip a fresh read/upload or advance a backfill cursor. | restoredSuccessIsLabelledHistoricalAndNeverBypassesManualExecution | T6, T5 completion |
| A confirmed successful local forget clears persisted history and in-memory consent/token/status without deleting Drive files or claiming to revoke Health Connect or Google grants; failure and interruption follow the deletion criteria below. | 확인된 로컬 삭제는 인증을 무효화하고 다시 열면 빈 기록이다; actualUiCancelPreservesHistoryAndConfirmedLocalDeletionReopensDurablyEmpty | T4, T3 completion |
| The same reporting-day paths, CSV payload rules, explicit EMPTY semantics, granted-type filtering and private Drive target checks remain unchanged under the existing meaningful regressions. | CSV headers ordering quoting and missing values follow v0_1; Empty and denied reads never upload or report success; Successful partial-grant sync uploads only authorized populated datasets; Ambiguous or shared targets are rejected before upload | T5, T7 completion |
| The collapsed home shows the target reporting date, current attempt/error state, independently retained last confirmed success and the next permitted action without opening details. | collapsedHomeShowsCurrentErrorPriorSuccessTargetDayAndNextAction | T6 completion |
| Last-success date, timestamp, coverage and file count all come from the same committed success record; a later failed attempt's count is never displayed as that success's count. | successCardCountDateTimeAndCoverageComeFromOneCommittedSuccess | T6 completion |
| Expanded details distinguish authorization-request time, actual execution-start time and terminal time, displaying absent times as “미시작” or “확인 불가” as appropriate rather than inventing timestamps. | detailsRenderMissingExecutionAndTerminalTimesWithoutInventedTimestamps; unreadableAndLoadingExecutionStartRemainsUnknownWhileKnownUnstartedHistoryShowsNotStarted | T6 completion |
| A failed or interrupted latest attempt remains visible alongside an earlier success; EMPTY is shown as no records to export, not successful upload. | latestFailureAndEmptyOutcomeRemainDistinctFromPriorSuccessfulUpload | T6 completion |
| Authorization and explicit upload continuation remain separate actions, with no automatic read/upload caused by expanding details, restoring the screen or loading history. | expandingDetailsOrRestoringUiNeverAuthorizesReadsOrUploads | T6 completion |
| The screen remains readable and operable with system light/dark themes, enlarged system text and TalkBack; critical state, confirmation controls and next actions must not be clipped, overlap or rely on color alone. | bothThemesAtDoubleTextScaleKeepManualAndRecoveryControlsNamedAndReachable; 실제 TalkBack·Galaxy 검증 대기 | T7 completion |
| Synthetic UI previews or screenshots cover no history, committed success, authorization pending, running, interrupted, history-read failure and deletion failure; these are review artifacts, not evidence of real account/device success. | syntheticGalleryCapturesSevenStatesInBothThemesAndDoubleTextScale | T7 completion |
| Cancelling the deletion dialog changes neither durable history nor current consent/token state. | 삭제 확인을 취소하면 인증과 영속 기록을 보존한다; actualUiCancelPreservesHistoryAndConfirmedLocalDeletionReopensDurablyEmpty | T4, T6 completion |
| Deletion is unavailable while permission/authorization is pending, a sync is running, or a history read/write/delete is in progress; the reason is visible and the operation is rejected even if a stale UI event arrives. | 삭제 진입 검사는 권한 대기와 새로고침 및 실행과 저장소 작업 중 오래된 이벤트를 거부한다; busySessionsRejectDeleteRequestsAndExplainDisabledDeletion | T4, T6 completion |
| Confirming deletion immediately clears in-memory consent, token and prepared continuation before durable deletion is attempted. | 삭제 확인은 첫 저장소 중단점 전에 인증을 무효화한다 | T4 completion |
| A durable empty-state commit clears both latest attempt and latest success together; a fresh store reopen after acknowledged completion finds no local history. | acknowledgedEmptyStateTransactionReopensWithoutAttemptOrSuccess; actualUiCancelPreservesHistoryAndConfirmedLocalDeletionReopensDurablyEmpty | T3 completion |
| If deletion fails, show “이력 삭제에 실패했습니다. 저장된 이력이 남아 있을 수 있습니다.” and provide “삭제 다시 시도”; do not show “삭제 완료” or restore consent/token. | 삭제 실패는 완료로 주장하지 않고 인증 복원 없이 재시도를 허용한다; failedDeletionOffersRetryWithoutClaimingCompletionOrRestoringCredentials | T4, T6 completion |
| After deletion failure, any retained readable record is marked “삭제되지 않은 기존 이력”; unreadable storage remains explicitly unknown rather than appearing empty. | 삭제 실패는 읽을 수 있는 기록만 유지로 표시하고 읽기 불가는 미확정으로 남긴다; failedDeletionLabelsRetainedHistoryWhileUnreadableHistoryRemainsUnknown | T4, T6 completion |
| After deletion failure, new authorization/upload stays disabled for the current session until deletion succeeds or the user explicitly chooses “삭제 취소·기존 이력 유지” and the store is successfully reloaded; neither action restores consent/token. | 삭제 실패는 명시적 유지와 성공한 재읽기 전까지 새 승인을 차단한다 | T4 completion |
| If the process ends during deletion, reopening shows the actual committed state: empty after committed clearing, otherwise the retained prior history or an explicit read error; it never claims an interrupted deletion completed. | interruptedResetBeforeCommitReopensOldStateAndAfterCommitReopensEmpty; 실제 기기 프로세스 종료 경계 대기 | T3, T7 completion |
| An uncommitted deletion intention need not survive process loss; persisted history may reappear after an interrupted/failed deletion, but a durably acknowledged deletion must not be undone by stale callbacks, result writes or recovery. | 복구 후 이전 세대 콜백은 확인된 초기화를 다시 쓰지 못한다; acknowledgedEmptyStateTransactionReopensWithoutAttemptOrSuccess | T4, T3 completion |
| Deletion is idempotent: deleting already-empty history succeeds without remote calls, and any deletion failure remains a storage error rather than a fake success. | 빈 기록의 반복 삭제는 로컬이며 영속화 실패를 성공으로 바꾸지 않는다; repeatedEmptyDeletionIsLocalAndPersistenceFailureNeverBecomesSuccess | T3, T4 completion |
| Local deletion does not delete Drive exports, revoke external grants, clear Health Connect records or promise forensic secure erasure. | deleteConfirmationStatesLocalOnlyScopeAndTriggersNoExternalDeletion | T6, T4 completion |
| A persisted pending authorization without execution start restores as “인증 단계에서 종료됨 · 업로드 미시작”; persisted authorization success awaiting continuation restores as “업로드 시작 전 종료됨 · 다시 인증해 주세요.” | Recovery distinguishes abandoned authorization from awaiting-upload interruption | T2 completion |
| A persisted running attempt without a committed terminal result restores as interrupted/indeterminate, without fabricated completion time or guaranteed uploaded-file count; remote files may already exist. | Running recovery never invents terminal time or confirmed upload count; persistedRunningReopensInterruptedWithoutFinishOrCountAndPreservesPriorSuccess | T2 completion |
| A committed terminal result restores its actual outcome, while failed/empty/interrupted attempts retain the independently committed previous success. | Terminal restoration preserves actual outcome and prior independent success; terminalRestorationPreservesActualOutcomeAndPriorIndependentSuccess | T2, T3 completion |
| Unsupported-format, corrupt and unavailable history are distinguished from an absent history store; no automatic reset or overwrite occurs merely because reading failed. | readingIncompatibleOrCorruptPayloadNeverOverwritesItAndExplicitResetCommitsEmpty; unsupportedSchemaAndCorruptDatabasePreserveReadsAndWritesUntilExplicitReset; unavailableDatabaseReadsAndFailedResetLeaveTheStoreUnavailable | T3 completion |
| While history is unreadable or unsupported, new authorization/upload is blocked until an explicit reload succeeds or a user-confirmed local reset durably succeeds; reset failure follows the deletion-failure rules. | 읽기 불가 기록은 명시적 재읽기 또는 초기화 성공 전까지 업로드를 차단한다 | T4 completion |
| Persisting a recovered status uses the same atomic failure rules as ordinary results; if recovery persistence fails, show the derived interruption together with a local-history error and do not claim durable recovery. | 복구 저장 실패는 파생 중단과 로컬 저장 오류를 표시한다; recoverySaveFailureLeavesPersistedRunningAttemptAndDerivableInterruption; recoveryWriteFailureShowsDerivedOutcomeAndUnknownResultWhilePreservingPriorSuccessAndBlockingNewUpload | T4, T3 completion |
| App reopen never restores tokens, upload consent or a prepared continuation, even after failed deletion or abandoned authorization. | 재열기와 지연 승인 결과는 토큰이나 명시적 계속 및 외부 실행을 복원하지 않는다; persistedSuccessSurvivesFreshActivityAndViewModelWhileConsentAndPreparedContinuationReset | T5, T7 completion |

Additional boundary baseline: envelope exactly 16 KiB/one byte over, zero/nonzero/unknown file counts, all five coverage categories/duplicates/unknown values, missing/invalid/incompatible stores, repeated reset, double clicks, delayed callbacks, midnight date selection and read-only/unavailable storage. Reuse existing source/CSV/Drive tests for previous regressions rather than mirror their implementation.

## 4. 작업

### T1: Establish real SQLite reopen test harness

- **변경 파일**

  | File | New/edit | Change |
  | --- | --- | --- |
  | `app/src/main/java/com/ny/my/health/HistoryDatabase.kt` | New | Private path, version-1 single-row schema, transaction helper |
  | `app/src/androidTest/java/com/ny/my/health/HistoryDatabaseInstrumentedTest.kt` | New | Real Android DB context/reopen smoke |

- **인터페이스**: 2절 `HistoryDatabase` 참조. Internal DB open/close and transaction helper only; typed history behavior comes in T3.
- **결정 로직**: Absent file is not silently created by a read; unsupported schema is rejected without destructive fallback.
- **호출 순서**:
  1. Open test-only private DB, inspect version/pragmas, commit synthetic probe, close and reopen.
  2. Any open/schema failure stays a storage error; no logs containing payloads.
- **작성할 테스트**: New `Private history database reopens a committed synthetic row` (integration harness smoke; ①/④). This task establishes the absent persistence-test infrastructure; production codec tests are not combined with it.
- **완료 기준**: Minimum instrumentation context starts and a real reopened row matches; new store remains disconnected from app execution.
- **검증 방법**:
  ```bash
  cd android
  ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.ny.my.health.HistoryDatabaseInstrumentedTest
  ```
- **주의사항**: Recovery 2/2: retain a typed unsupported-schema exception and platform corruption exception with fixed non-sensitive messages; no schema/configuration change. Local emulator is absent; use approved CI execution or report blocked. Confirm platform DB path/pragma behavior before coding; if unsupported, stop for plan correction. Do not install a destructive corruption handler or add a persistence dependency.

### T2: Define bounded history transitions

T1 완료 후 시작.

- **변경 파일**

  | File | New/edit | Change |
  | --- | --- | --- |
  | `core/src/main/kotlin/com/ny/my/health/core/SyncHistory.kt` | New | Values, port, strict codec and pure recovery |
  | `core/src/test/kotlin/com/ny/my/health/core/SyncHistoryTest.kt` | New | Deterministic transition/codec tests |

- **인터페이스**: Shared `SyncHistory` and `HistoryStore`.
- **결정 로직**:
  ```
  Pending authorization / awaiting continuation / running → distinct recovered outcome, no invented finish time
  Failed / empty / permission-required / interrupted → prior success retained
  Successful terminal result → coherent success fields copied from that attempt
  Unknown version / invalid payload → unsupported / corrupt, never implicit empty
  ```
- **작성할 테스트**: 2절 `T2Cases` 참조.  New core tests assigned to T2 in the traceability table, plus exact parser-cap boundary, invalid enums/duplicates, negative counts and date/time validation (②/③).
- **완료 기준**: Every T2 traceability test and codec boundary passes with fixed clock/synthetic values; encoded whitelist excludes all forbidden fields.
- **검증 방법**:
  ```bash
  cd android
  ./gradlew -PcoreOnly :core:test --tests '*SyncHistoryTest'
  ```
- **주의사항**: Reuse installed JSON library; no new plugin/dependency. Do not serialize token/session/generation or coerce invalid input to defaults. Unexpected contract conflict is blocked, not silently repaired.

### T3: Commit history atomically

T2 완료 후 시작.

- **변경 파일**

  | File | New/edit | Change |
  | --- | --- | --- |
  | `app/src/main/java/com/ny/my/health/SqliteHistoryStore.kt` | New | Bound reads/transactional envelope replacement/reset |
  | `app/src/androidTest/java/com/ny/my/health/SqliteHistoryStoreInstrumentedTest.kt` | New | Real rollback, empty commit, old/new reopen |

- **인터페이스**: Shared `HistoryStore`, `SyncHistory`, `SyncHistoryCodec` and `HistoryDatabase` from T1/T2.
- **결정 로직**: Absent, unsupported, corrupt and unavailable are distinct results; unknown storage never silently initializes or overwrites history.
- **호출 순서**:
  1. Validate encoded envelope before starting a write transaction.
  2. Commit one bound row update; acknowledge only after transaction completion.
  3. Error or cancellation → preserve uncertainty, close safely, reopen to determine committed state.
- **작성할 테스트**: 2절 `T3Cases` 참조.  T3 traceability cases use actual SQLite close/reopen; inject precommit failure for result/reset rollback and repeat with committed empty state. Test unreadable database separately from invalid JSON and unsupported version.
- **완료 기준**: Real reopened state is old after rollback, new after commit, and valid on interrupted-state fixtures; errors are categorized without provider/SQL/payload details.
- **검증 방법**:
  ```bash
  cd android
  ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.ny.my.health.SqliteHistoryStoreInstrumentedTest
  ```
- **주의사항**: No mock call-count substitute for durability. Do not claim definite rollback after an ambiguous commit error. Storage corruption can prevent reset too; surface failure rather than deleting the DB file.

Checkpoint after T3: inspect actual instrumentation XML and reopen evidence; no ViewModel/UI integration until persistence behavior is established.

### T4: Serialize history session recovery

T3 완료 후 시작.

- **변경 파일**

  | File | New/edit | Change |
  | --- | --- | --- |
  | `app/src/main/java/com/ny/my/health/HistorySession.kt` | New | Serialized operations, local recovery, generation tickets |
  | `app/src/test/java/com/ny/my/health/HistorySessionTest.kt` | New | Controlled store failure and event ordering |

- **인터페이스**: Shared `HistorySession`, `HistoryStore`, `SyncHistory`.
- **결정 로직**:
  ```
  Busy / unreadable / delete-failed state → reject disallowed new authorization and stale events
  Confirm deletion → invalidate credentials/prepared work before requesting durable reset
  Reset failed → block new upload until reset success or explicit keep-existing plus successful reload
  Completion generation differs → no state/store write from stale callback
  ```
- **호출 순서**:
  1. Serialize local load, derive recovery and persist it; persistence failure remains visibly non-durable.
  2. Serialize authorization/execution-start metadata before permitting external effects.
  3. Serialize delete/reload under the same guard; do not acquire a lock recursively around suspended callbacks.
- **작성할 테스트**: 2절 `T4Cases` 참조.  All T4 mapped cases; deferred completions prove busy/generation behavior. Use small handwritten boundary fakes and deterministic coroutine gates, not timing sleeps.
- **완료 기준**: No local recovery triggers reader/network effects; delete failure and stale callback rules hold; pure decisions stay outside SQL/callback chains.
- **검증 방법**:
  ```bash
  cd android
  ./gradlew :app:testDebugUnitTest --tests '*HistorySessionTest'
  ```
- **주의사항**: This class has no Application/SDK imports. Test doubles model failures/order only; actual storage guarantees remain T3 integration evidence. Never restore credentials during load or keep-existing.

### T5: Connect manual execution to durable history

T4 완료 후 시작.

- **변경 파일**

  | File | New/edit | Change |
  | --- | --- | --- |
  | `app/src/main/java/com/ny/my/health/SyncViewModel.kt` | Edit | Wire session/store and preserve explicit manual flow |
  | `app/src/main/java/com/ny/my/health/MainActivity.kt` | Edit | Defer existing SDK launch until durable authorization callback |
  | `app/src/main/java/com/ny/my/health/HistorySession.kt` | Edit | Testable execution/authorization effect boundary |
  | `app/src/test/java/com/ny/my/health/HistorySessionTest.kt` | Edit | Ordered integration of fake read/upload/credential effects |

- **인터페이스**: Shared `HistorySession`; retain Google SDK request/response handling, with `beginAuthorization(onReady: () -> Unit)` for asynchronous durable-start acknowledgment. The Activity callback launches the unchanged AuthorizationClient flow only after commit; no blocking IO on UI thread. Root confirmed the old Boolean handshake cannot provide that boundary.
- **결정 로직**: History restore never satisfies authorization/upload opt-in; only successful terminal results replace committed success; recoverable local-history error differs from remote outcome.
- **호출 순서**:
  1. Durable authorization-attempt write → permit SDK launch; failure blocks launch.
  2. Persist awaiting-continuation phase → show explicit continuation; stale SDK callbacks are rejected.
  3. Durable running write → start existing coordinator; failure means zero read/upload calls.
  4. Coordinator terminal/cancellation → serialized terminal record; result-save failure retains old committed success and discloses transient remote evidence.
- **작성할 테스트**: 2절 `T5Cases` 참조.  T5 mapped cases: zero effects after start-write failure, remote confirmation/local-save failure, preserved prior success, pause cancellation, late callbacks and duplicate continuation. Reuse existing reader/coordinator/transport tests.
- **완료 기준**: Existing SDK launches remain Activity-owned; no persisted token; independently scoped terminal-history write cannot be accidentally cancelled with the sync job, and never attempts remote replay.
- **검증 방법**:
  ```bash
  cd android
  ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug
  ```
- **주의사항**: Recovery 2/2 records the existing Activity call-site dependency; change only the handoff until T6 owns visual changes. Reconfirm installed lifecycle cancellation behavior before implementation. Process death can prevent any final write; recovery must still work. Unknown assumption or incompatible existing API is blocked rather than bypassing foreground checks.

Checkpoint after T5: inspect actual ordering tests and credential handling; no visual completion claim before screen and lifecycle checks.

### T6: Render status-first home and deletion recovery

T5 완료 후 시작.

- **변경 파일**

  | File | New/edit | Change |
  | --- | --- | --- |
  | `app/src/main/java/com/ny/my/health/HealthHomeScreen.kt` | New | Approved home layout and explicit dialog/error actions |
  | `app/src/main/java/com/ny/my/health/MainActivity.kt` | Edit | Pass state/actions; keep permission/Google launchers |
  | `app/src/androidTest/java/com/ny/my/health/HealthHomeScreenInstrumentedTest.kt` | New | Synthetic state semantics and dialog behaviors |
  | `app/src/androidTest/java/com/ny/my/health/HealthAppInstrumentedTest.kt` | Edit | Align old P0 captions with approved new copy, retaining all safety assertions |

- **인터페이스**: Shared `HealthHomeScreen`, `HistoryUiActions`.
- **결정 로직**: Current error and committed success are independent; missing start/finish time is explicit; success card fields originate from the same committed success.
- **작성할 테스트**: 2절 `T6Cases` 참조.  T6 traceability cases plus delete dialog cancel/confirm semantics, busy-state rejection and local reload with no external effects.
- **완료 기준**: Approved section-4 arrangement/copy is visible; latest failure survives earlier success; token is never part of the render state; details expansion invokes no read/upload.
- **검증 방법**:
  ```bash
  cd android
  ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.ny.my.health.HealthHomeScreenInstrumentedTest
  ```
- **주의사항**: Recovery 2/2: old Activity UI tests still assert P0 captions, so update only obsolete copy/necessary UI navigation in this same slice; retain zero-grant/consent/no-fake-success assertions before full CI. Reuse Material 3 and existing scrolling layout; no navigation/chart dependency. Distinguish unavailable actions with text as well as disabled controls; no real health data in captures.

### T7: Validate lifecycle and accessible UI states

T6 완료 후 시작.

- **변경 파일**

  | File | New/edit | Change |
  | --- | --- | --- |
  | `app/src/androidTest/java/com/ny/my/health/HealthHomeScreenInstrumentedTest.kt` | Edit | Synthetic states/themes/font-scale evidence |
  | `app/src/androidTest/java/com/ny/my/health/HealthAppInstrumentedTest.kt` | Edit | Preserve first-launch/zero-grant/recreation guarantees |
  | `../docs/V1/device-acceptance.md` | New | Explicit Galaxy/TalkBack/process-death steps |
  | `../docs/V1/execution-report.md` | New | Actual tests, screenshots, pending evidence |
  | `../.github/workflows/android-emulator-tests.yml` | Edit | Add synthetic PNG-only glob to existing report upload |

- **인터페이스**: Existing instrumentation runner and shared `HealthHomeScreen`; installed androidx.test:monitor 1.7.2 exposes PlatformTestStorageRegistry.getInstance().openOutputFile(name). Confirmed by installed class signatures/bytecode; FileTestStorage uses runner additionalTestOutputDir. Use fixed v1-synthetic-ui/*.png names and synthetic-only screen states; no new dependency. Add android/app/build/outputs/**/v1-synthetic-ui/*.png to existing artifact paths, never upload all outputs/APKs/private app state. Actual AGP collection must be verified by downloading PNGs before claiming visual review.
- **결정 로직**: None; test actual rendering and evidence boundaries.
- **작성할 테스트**: 2절 `T7Cases` 참조.  T7 mapped cases; synthetic captures for no-history/success/authorization/running/interrupted/read-failure/delete-failure, light/dark themes and enlarged text. Retain existing consent and zero-grant paths using approved new UI copy.
- **완료 기준**: Instrumentation results/captures reviewed, Galaxy force-stop and TalkBack results recorded only when actually performed; pending physical checks prevent full conformance PASS.
- **검증 방법**:
  ```bash
  cd android
  ./gradlew :app:connectedDebugAndroidTest
  ```
- **주의사항**: Recovery 2/2 records the missing host evidence collection path only; no runner/version/permissions/signing change. Emulator recreation is not force-stop proof. Do not skip or weaken old safety assertions to match the new UI; update obsolete wording only against the approved spec.

Checkpoint after T7: spec-only independent conformance check, code review and security/privacy review; unresolved device results remain explicit. Reviewers receive the approved spec and implementation, not this plan as the source of truth.

## 5. 전체 검증

1. Recheck `spec_rev` and approved plan/P0 disposition before execution; re-read current source instead of assuming PR base stability.
2. Execute core/app suites, debug APK and lint using configured JDK/SDK/proxy tooling:

   ```bash
   cd android
   python /workspace/tooling/run-health-command.py ./gradlew --no-daemon :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
   ```

3. Run all instrumentation on the existing API-35 CI workflow; inspect XML failures/skips, not only job status. Retain actual SQLite reopen artifacts and synthetic screenshot evidence. Never claim skipped tests passed.
4. Inspect changed sources/manifest/dependency graph for token/account/measurement persistence, accidental permissions/dependencies, changed CSV/Drive behavior and backup exposure. Run Gitleaks plus staged diff inspection and Markdown checks.
5. Galaxy validation: fixed-signature update, successful manual sync then force-stop/reopen, interrupted running recovery, no implicit authorization/upload, local delete success/failure guidance and TalkBack/enlarged-text operation. Record categorical results privately/sanitized; no measurements or key material.
6. Compare actual test names/evidence with every traceability row; carry genuinely unobserved device/accessibility outcomes as pending. Require spec-conformance and bounded code/security reviews before declaring the slice implemented.

## 6. 위험과 롤백

- New durable history intentionally changes P0's process-memory-only **status** behavior. Consent/token still reset. Update device expectations for V1 explicitly; do not rewrite historical P0 results.
- Older P0 app ignores the new private database. Reverting code does not imply erasing private history or remote exports; do not introduce automatic destructive downgrade handling. A newer unsupported store blocks history-dependent actions until explicit supported recovery.
- Keep schema changes separate from UI wiring so failures can be isolated; no release publication or signing rotation is part of rollback. Transaction errors are not evidence of remote rollback.
- Commit boundary ambiguity is a storage-error state resolved by reopening; never emit a false success or destructive reset to hide it. Real abrupt-process tests and Galaxy evidence remain distinct from controlled precommit failures.

## 복구 기록

| 작업 | 회차/2 | 실패 증거·원인 | 새 근거·수정 가설 | 계획 보정 전→후·유지 기준 | 수정 파일·보존 대상 | 검증 명령·결과 | 실행자·다음 조치 |
|---|---|---|---|---|---|---|---|
| T7 | Owner-authorized extra fixture correction; third total, no reset | Independent verifier found synthetic SUCCESS fixture mismatches latest successful attempt and success record, so codec rejects it | Production codec requires coherent success date/time/coverage/count | Make SUCCESS screenshots and pending-state fixture coherent, validate gallery readable envelopes through production codec before rendering | Existing HealthHomeScreenInstrumentedTest.kt only; preserve all20 screen tests and56 fixed PNG paths | Scoped compile then current-source Android run | v1_t7; same approved evidence scope |
| T6 | Owner-authorized continued correction; fifth total, no reset | Independent spec inspection found RECOVERY_WRITE hides derived interruption and keep-existing action label differs from approved literal | Existing state regression proves derived INTERRUPTED and RECOVERY_WRITE coexist; renderer must expose both | Preserve explicit local-history error plus derived recovery outcome, unknown remote count/time, blocked authorization and prior success; use approved 삭제 취소·기존 이력 유지 action label | HealthHomeScreen.kt and its instrumentation test only; preserve gallery/lifecycle tests | Scoped compile then full Android runtime required | v1_t6; within latest explicit owner approval for additional corrections |
| T6 | Continued scoped UI-contract correction under owner approval; fourth total, no reset | Read-only review found unreadable history still labels a missing start as 미시작; consent checkbox lacks its own accessible name | Approved spec distinguishes unknown from unstarted and requires TalkBack-operable critical controls | Use 확인 불가 for unknown storage metadata; keep 미시작 only for readable known no-start state; give existing consent checkbox a semantic label and add focused tests | Existing HealthHomeScreen.kt and its UI test only; no data/SDK/API/dependency change | One compile pass and full Android CI required | v1_t6 / within approved UI correctness/accessibility scope |
| T6 | Additional scoped correction under owner continuation approval; third total, no reset | Initial Android test compile used ComposeUiTest StateRestorationTester with ComposeContentTestRule; source review also found UI copy/time/theme mismatches | Installed JUnit4 class signature confirmed; approved spec requires missing-time labels, exact deletion warning and both themes | Use existing junit4.StateRestorationTester; fix null labels to 미시작/확인 불가, exact delete warning/retained label, loading-before-error and system theme | Existing four T6 files only; preserve behavior assertions and existing SDK flow | Single scoped compile then Android CI; no new dependency or API | v1_t6 / owner approved additional corrections and continued workflow |
| T5 | Owner-authorized extra correction (3rd total; budget not reset) | Tests pass but source violates generation memory-only and pending-permission deletion guard | MainActivity savedInstanceState/onSaveInstanceState and ViewModel action entry points inspected | Proposed: retain correlation in same-process ViewModel memory only; reject busy/stale deletion and rejected reauthorization without stuck pending state; add behavioral regressions | Existing T5 four files only; preserve all earlier tests/working code | Owner explicitly approved the additional correction; rerun exact T5 suites/APK and later Android recreation checks | v1_t5 resuming scoped repair; dependent T6/T7 wait for verification |
| T6 | 2/2 | Preflight: existing instrumented tests assert P0 title/captions that approved UI replaces | Existing HealthAppInstrumentedTest inspected | Include minimal old-copy/navigation update in T6 so its CI remains meaningful; T7 stays dependent | Add existing Activity test file, preserve its safety assertions | Full instrumentation after T6 required | Codex / no weaker assertions |
| T7 | 2/2 | Planned synthetic gallery has no host PNG artifact path | Installed monitor 1.7.2 API and output-dir calculation inspected; current workflow uploads XML/reports only | Use existing platform test output and a synthetic PNG-only upload glob; no new dependency | Add existing emulator workflow as fifth T7 file; preserve runtime and signing settings | Confirm downloaded images exist and inspect before visual-review claim | Codex / evidence collection only |
| T4 | 2/2 | Executor brief omitted HistorySessionState: assembler split at secondary header comment | Approved plan already defines exact state/enums; no interface redesign needed | Keep state and enums in HistorySession code fragment and resend exact existing shape; clarify explicit restore and live-state admission | plan.md / brief only; implementation absent | Regenerate brief and confirm state fields included before first code verify | v1_t4 / no new persisted fields or scope |
| T5 | 2/2 | Read-only preflight: MainActivity immediately calls SDK after Boolean beginAuthorization | Existing call site and suspend HistorySession API cannot await durable commit via Boolean | Include minimal Activity handoff edit and callback-based acknowledgment; preserve existing scopes, SDK flow, consent and explicit continuation | Add MainActivity.kt to T5; T6 remains dependent and owns later UI extraction | Compile and ordering tests required at T5; no implementation in this correction | Codex / scope-preserving interface correction |
| T1 | 2/2 | T3 adapter blocked: helper generic SQLiteException conflates unknown schema and corruption | Source inspected: fixed schema guard and corruption handler erase type distinctions | Use internal UnsupportedHistorySchemaException and platform SQLiteDatabaseCorruptException, preserving no-destructive-fallback and schema v1 | HistoryDatabase.kt plus existing smoke test; preserve T2 and all payload rules | Compile and existing harness; T3 must assert actual reopen statuses | v1_t1 scoped recovery / T3 waits for typed API |
| T1–T7 | 1/2 each | Initial T1 brief assembled with 0 Shared design references; later task text referred to traceability not included in briefs | Existing policies and test descriptions were already approved but not mechanically delivered | Tag existing DB/codec policies and test lists; add explicit references, preserving approved schema and criteria | plan.md only; implementation absent | Reassemble each task before dispatch; stop on unresolved references | Codex / no code changes in this correction |

## 환경 준비 기록

| 원인·근거 | 준비 조치 | 조치 사용/1 | 확인 명령·결과 | 확인 사용/1 | 다음 조치 |
|---|---|---|---|---|---|
| Offline Gradle in executor lacked wildcard-IP socket access | Reran identical command with existing additional network/socket permission; no tool/config/version change | 1/1 | T2 exact command passed, 8 tests / 0 failures/errors/skips | 1/1 | Environment issue resolved; T2 done |
| Existing JDK/SDK/proxy configuration required for baseline compilation | Used existing `/workspace/tooling/run-health-command.py`; no tool version or project configuration changed | 1/1 | `./gradlew --offline --no-daemon :core:compileKotlin :app:compileDebugKotlin` via wrapper: BUILD SUCCESSFUL, 18 tasks up-to-date | 1/1 | Baseline compile only; no new implementation or runtime test claimed. Owner disposition recorded; T1 actual CI subsequently passed. |

## Verified execution checkpoints

- T1 source `5f92bee`: app/androidTest compilation passed locally. Android emulator run [41367673147](https://github.com/nyeonu/health-app/actions/runs/41367673147) succeeded; downloaded XML contains 4 executed tests, including `privateHistoryDatabaseReopensACommittedSyntheticRow`, with 0 failures/errors/skips. JVM/APK/lint run [41367673167](https://github.com/nyeonu/health-app/actions/runs/41367673167) succeeded; fresh artifact XML confirms 47 JVM tests with 0 failures/errors/skips. This is synthetic Android persistence evidence, not physical Galaxy/account validation.

- T2: root reran the exact approved unit command with existing socket/network permission; BUILD SUCCESSFUL, 8 new history tests, 0 failures/errors/skips. The first executor attempt failed before compilation on wildcard-IP socket restrictions, not a code defect; no source repair occurred. Public codec/recovery APIs above are actual implementation facts.

- T1 recovery and T3 source `2b46f34`: root compilation passed. [Emulator run 41373372078](https://github.com/nyeonu/health-app/actions/runs/41373372078) succeeded; downloaded XML confirms 17 tests, 0 failures/errors/skips, including all 3 DB harness and all 11 typed-store cases. [JVM/APK/lint run 41373372032](https://github.com/nyeonu/health-app/actions/runs/41373372032) succeeded. T1 recovery 2/2 is verified, not reset.

- T4: first implementation and root verification passed, actual XML contains 16 tests with 0 failures/errors/skips; root rerun was up-to-date on unchanged code. Tests exercise arrival rejection, generation invalidation, delete/reload failures and guarded starts with deterministic store doubles; actual durability remains separately established by T3.

- T5 candidate: first implementation and root repeated verification passed, core 35 + app 44 = 79 JVM tests, 0 failures/errors/skips; debug APK assembled. Root review nevertheless blocks completion: Activity serializes the consent generation in savedInstanceState against the memory-only rule; ViewModel history deletion entry points do not reject Health Connect permission/refresh busy states, and rejected reauthorization can retain an old ticket. Candidate files are preserved; T6/T7 have not started. An additional scoped repair was requested because T5 plan-correction budget is already 2/2; no repair has been performed or counted as approved.

- Owner authorization (2026-11-02): explicitly approved the additional T5 correction after the two integration issues were disclosed. Existing correction history is retained (third total, not a reset). Remove Bundle generation persistence, preserve correlation only in same-process ViewModel memory, enforce history-action busy guards and clean rejected authorization state; no new scope, dependency or external authorization policy.

- T5 owner-authorized correction validation: first scoped correction passed 35 core + 48 app = 83 JVM tests and APK build, root rerun unchanged/up-to-date. Source inspection confirms no generation Bundle/SavedState restoration and production busy gates. Acceptance follow-up within the same owner-approved memory/status correction clears stale transient confirmation/count/message on invalidation while retaining independently committed history until acknowledgment. This extra scoped pass is recorded, not hidden or treated as a reset of previous corrections; no new feature or permission policy is introduced.

- T5 owner-authorized memory/status correction completed: 35 core + 49 app = 84 JVM tests, 0 failures/errors/skips, APK assembled. Bundle persistence removed; same-process ViewModel holder correlates SDK result once and is empty in a new process. Production busy/rejected-start guards and transient-status clearing are covered by 5 added regressions (29 total session tests). Committed historical success is retained until the reset commits. The initial correction and disclosed acceptance follow-up each verified once; no hidden count reset.

- T6 expected-copy correction evidence: initial unexecuted tests asserted “실행 시작 시각: 기록 없음” / “종료 시각: 기록 없음” and a shorter delete-failure text. Approved spec explicitly requires absent starts as “미시작”, absent terminal times as “확인 불가”, and “이력 삭제에 실패했습니다. 저장된 이력이 남아 있을 수 있습니다.” with “삭제되지 않은 기존 이력”. Correct assertions to that contract, not to make existing output pass; no safety assertion is removed.

- T6 final source `79ffd0f`: root compilation passed. [Android CI 41434453899](https://github.com/nyeonu/health-app/actions/runs/41434453899) succeeded; downloaded XML confirms 34 tests (17 screen + 3 Activity + 3 DB harness + 11 store), 0 failures/errors/skips. [JVM/APK/lint CI 41434453978](https://github.com/nyeonu/health-app/actions/runs/41434453978) succeeded. T7 remains pending runtime gallery/lifecycle evidence.

- T7 fixture-check verification launcher initially ran from repository root and failed with FileNotFoundError for ./gradlew before Gradle started. Correcting cwd to android and rerunning the identical compile is environment launch recovery, not a source retry; no code edit or budget reset.

- Final verification target `b97ff632b12cfdc935303c2167d840d16c894e7e`: root reran core/app JVM, APK, lint and Android-test compilation; BUILD SUCCESSFUL in 49s. JVM XML remains84 (35+49), failures/errors/skips0. Android CI41438677211 and synthetic artifacts pending. T6 returned to in_progress while its new recovered-outcome rendering awaits Android runtime evidence; T7 correction is test-fixture-only and does not imply T6 completion.

- Final source `b97ff632b12cfdc935303c2167d840d16c894e7e`: [Android CI41438677211](https://github.com/nyeonu/health-app/actions/runs/41438677211) succeeded. Downloaded raw XML confirms40 tests (screen20, Activity3, fresh Activity/ViewModel3, DB3, store11), failures/errors/skips0. Artifact13137259532 contains exactly56 expected fixed synthetic PNGs, all readable1080×2400; root reviewed7 representative latest-source images covering all7 required states including corrected keep-existing caption and light/dark/enlarged states. [JVM/APK/lint CI41438677210](https://github.com/nyeonu/health-app/actions/runs/41438677210) succeeded. T6 corrected rendering is verified done. T7 automated portion passes; actual Galaxy/process-loss/system-settings/TalkBack remains pending and prevents full completion.

- Verification isolation recovery: a verifier called collaboration.list_agents while waiting and the tool unexpectedly exposed prior reports. Its observations were not used as final independent conformance. A fresh spec/code/artifact-only verifier was dispatched with explicit prohibition on agent-status tools; implementation remained unchanged. No implementation recovery budget reset or new software scope.

- Final isolated spec conformance:34/39 PASS;5 criteria FAIL for absent actual OS process-loss and system-settings/TalkBack evidence, not observed implementation defects. See `conformance-report.md`. Root independently matched all39 verbatim criteria and every mapped function against actual executed XML (no missing/skipped mapping). Per gate, keep approved spec, Proposed ADR, in_progress plan/T7 and draft PR; final code/security reviews and merge remain blocked. Final verifier did not read plans/prior reports or call agent-status tools.


## PR #8 review correction — owner-authorized continuation

Owner instruction: address PR comments, reflect fixes, spawn another session to verify, then merge if no issues and continue. The owner's review comment separates automated software verification from actual Galaxy/TalkBack acceptance. Development merge may proceed after Important findings and independent code/security review are clear; remaining P0/Galaxy/TalkBack stays pending and is not release/full-device acceptance. This explicit disposition supersedes the earlier blanket development-merge hold without changing any of the39 success-criterion sentences or marking unobserved work PASS.

- T3 additional correction (third total; prior2/2 retained): explicit-confirmation reset must recover unsupported schema, structurally invalid schema-v1 and physical corruption. Normal load/replace preserve files and never auto reset. Supported DB reset retains existing transaction. Fallback uses same-path shared locking, persistent private recovery-directory marker, validated empty candidate, synchronized snapshots including sidecars, checked atomic rename and directory sync, empty reopen validation, complete old-data/staging cleanup before acknowledgment. Marker on read/write is explicit unavailable; no read-time mutation. Failed/cancelled preparation preserves originals; installed-but-not-cleaned remains explicit error, never restores old history. See ADR-005. Scope: HistoryDatabase.kt, SqliteHistoryStore.kt, new internal recovery helper if needed, actual store/harness tests. No dependency/permission/format/auth changes.
- T7 additional correction (fourth total; prior history retained): host adb force-stop between distinct instrumentation processes, PID exit/new-process checks, synthetic success/running/auth/delete-boundary restoration and no implicit consent/continuation. Actual system night/font_scale transitions and resource/semantic verification. New host-only instrumented test and bounded CI shell script, existing emulator workflow only. Preserve old40 tests and56 PNGs, no skipped tests or production test hooks. Reset system settings and clean only owned synthetic files. Actual account/Galaxy/TalkBack remains pending.
- Test acceptance: real unsupported-schema/garbage/missing-table DB → explicit reset → fresh empty reopen → normal write/manual admission; interrupted replacement and cleanup error remain errors with no acknowledged data resurrection; sidecar preservation and multiple instances; host process-death scenario results checked beyond adb command exit status; system settings read back and target resource values asserted.
- Verification: local core/app suites/APK/lint/androidTest compile, latest-head Android CI raw XML plus host scenario evidence/PNGs; independent fresh verifier receives approved spec, source and artifacts only, not this plan/prior reports or collaboration status outputs. Final review includes security and local concurrency reasoning.

- T7 fifth total scoped correction (latest owner review-fix authorization; no reset): first added host-test compile rejected cross-module smart cast of expected.attempt.confirmedFiles. Use one local non-null binding/existing non-null assertion at that expected-value access; preserve all assertions. bash -n already passed; rerun scoped compile once.

- Shared-worktree verification dependency: T7 scoped compile reached in-progress T3 helper and failed on Android OsConstants.O_DIRECTORY before Android-test compilation. T7 assertions were not compiled/run; pause it until T3 source is stable. Use Android-supported O_RDONLY directory open with checked descriptor/type and fsync, preserving protocol. This is a platform-API technical correction in T3 (fourth total under latest owner correction authorization), not an OS-test assertion failure or a reset of previous budgets. Subsequent shared compilation is serialized.

- T3 hot-journal policy: readonly preservation can leave a genuine uncommitted hot rollback journal as explicit UNAVAILABLE; no read-time copying/parser/recovery protocol is added. The approved interrupted-delete contract permits an explicit read error, and confirmed reset now safely recovers this family. Small beforeCommit force-stop fixtures may be non-hot and must not be described as proving hot-journal rollback. Add real hot-family regression if feasible, keep existing old-state assertions for non-hot fixtures, never silently return empty.

- Review-fix compilation complete: root reran :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:compileDebugAndroidTestKotlin with existing wrapper/socket permission, BUILD SUCCESSFUL in58s; shell syntax and diff checks pass. Store actual-file tests increase11→17; expected normal Android suite40→46, host8 recovery+4 settings+8 cleanup successful methods tracked separately, deliberately killed holds not successes. Runtime results still pending.

- T3 fifth total correction under current owner review-fix approval: Android run41523078006 raw XML46 tests,1 failure,0errors/skips. Failure is WAL fixture setup at line183: PRAGMA wal_autocheckpoint returns rows and Android rejects execSQL (SQLITE_OK Queries can be performed using query/rawQuery only). Use rawQuery and assert returned setting0; retain WAL preservation assertions. Other45 tests, including genuine hot/reset/cleanup cases, passed. Host phase did not run because connected suite failed; no host PASS claim. Scoped test compile then full latest-head CI required.

- T3 sixth total correction under current owner review-fix authorization: independent session reproduced malformed schema-v1 with id INTEGER PRIMARY KEY but missing CHECK(id=1); id2 survives INSERT OR REPLACE id1, allowing false reset acknowledgment and corrupt reopen. Missing PK also breaks future upserts. Validate the supported history table definition/constraints (including unexpected history triggers) before ordinary access; malformed-but-integrity-ok schema is unsupported and explicit reset recreates it. Centralize canonical CREATE statement; compare platform sqlite_master definition with harmless whitespace/case normalization, preserving schema-v1 own historical DDL. Retain missing-table explicit error classification. Add actual-file fixtures missing CHECK and missing PK, ordinary-access nonmutation, confirmed reset empty + repeated fresh writes with one coherent row; retain all old assertions. After reset insertion validate decoded empty before ack as defense. Independent current-source verdict REQUEST CHANGES; new corrected-source verification required, no scope or budget reset.

- T7 sixth total review-authorized correction: acd2621 Android run41525077361 completed46/46 normal tests with0failure/error/skip, then host script failed before fixtures. Runner lacks rg, and connectedDebugAndroidTest teardown removed instrumentation APK (Unable to find instrumentation info). Use portable grep -E for fixed regex checks, explicitly install the already built debug/test APKs and verify runner registration before host scenarios. No new dependency/runner/signing/permission changes. Host results remain unverified; latest current-code CI may reach same setup issue until correction is pushed.
