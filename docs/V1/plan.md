---
plan_id: V1
title: Durable manual history and status-first home
kind: single
status: draft
author: Codex
created: 2026-11-01
target: nyeonu/health-app/android
spec: spec.md
spec_rev: 3e50cb91633d88402b236bdb2d5924bec9103991
tasks:
  - id: T1
    title: Establish real SQLite reopen test harness
    depends_on: []
    status: pending
    tier: standard
  - id: T2
    title: Define bounded history transitions
    depends_on: [T1]
    status: pending
    tier: standard
  - id: T3
    title: Commit history atomically
    depends_on: [T2]
    status: pending
    tier: standard
  - id: T4
    title: Serialize history session recovery
    depends_on: [T3]
    status: pending
    tier: standard
  - id: T5
    title: Connect manual execution to durable history
    depends_on: [T4]
    status: pending
    tier: standard
  - id: T6
    title: Render status-first home and deletion recovery
    depends_on: [T5]
    status: pending
    tier: standard
  - id: T7
    title: Validate lifecycle and accessible UI states
    depends_on: [T6]
    status: pending
    tier: standard
---

# Durable manual history and status-first home

## 1. 선택한 방식과 이유

Use platform SQLite for one bounded history envelope in the app's no-backup private directory. No Room, DataStore, database library or permission is added. The envelope contains only latest attempt and latest confirmed success; it is not a log table. SQLite's explicit transaction boundary makes replacing both records, retaining the prior committed envelope on rollback, and acknowledging empty-state deletion testable with actual close/reopen operations. Store version and payload are committed together. Prefer platform transactions over a custom journal/rename protocol; SharedPreferences does not provide the explicit transaction/test boundary selected here, and a new ORM would add schema/framework cost for one row.

Keep persistence adapters in app, and value/codec/recovery decisions in core. Reuse the existing Kotlin JSON dependency with explicit field parsing, avoiding a serialization compiler plugin. One app-session coordinator serializes local reads/writes on IO, owns busy/generation guards and gates existing manual effects; ViewModel remains the Android lifecycle adapter. Extract a stateless Material 3 screen so every visual state can be tested without real accounts or Health Connect.

**ADR candidate:** persisted format/version policy and SQLite transaction boundary. After plan approval, record a Proposed ADR with adr-writer; do not mark this design Accepted until conformance verification passes.

**Entry gate:** the owner approved the specification and permits this planning work. Execution-plan approval is separate. Before T1 implementation, obtain explicit P0 disposition covering unresolved original-session comparison, recoverable signing-key backup, isolated revocation gaps and dialog cancellation. Planning and automated review do not waive these items. No P0 result is promoted here.

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
| `app/src/main/java/com/ny/my/health/MainActivity.kt` | Edit | Render extracted screen; keep SDK launchers | T6 |
| `app/src/androidTest/java/com/ny/my/health/HealthHomeScreenInstrumentedTest.kt` | New | Synthetic screen states/screenshots/semantics | T6, T7 |
| `app/src/androidTest/java/com/ny/my/health/HealthAppInstrumentedTest.kt` | Edit | Retain consent/lifecycle expectations after UI change | T7 |
| `../docs/V1/device-acceptance.md` | New | Galaxy force-stop/recovery/accessibility evidence template | T7 |
| `../docs/V1/execution-report.md` | New | Actual verification evidence and pending limits | T7 |

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
// HistorySessionState — operation gates plus readable committed data
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
- `HistorySession.kt`: pure eligibility/operation-generation checks remain same-file helpers, separate from suspending store calls. It is the single in-process writer and does not claim cross-process/multi-instance safety.
- Authorization/execution start methods return acceptance only after commit; the in-memory generation ticket is never serialized. Credential invalidation is a callback invoked before deletion's first suspending operation, including failure paths. Session state includes no credential; a rejected/stale authorization callback must discard its token in ViewModel.
- On explicit continuation, compute yesterday once, persist that execution date and call existing `SyncCoordinator.sync(date)` with exactly that date. Do not independently recompute the day between metadata and read across midnight.
- `HealthHomeScreen.kt`: pure display projection combines latest outcome and independently committed success; never derive success count from the latest attempt.

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

All test names below are planned behavior descriptions. None have been implemented or executed by this plan. Sources: ① specification, ② partitions/boundaries, ③ failure paths, ④ state/ordering, ⑤ prior defect. Actual SQLite assertions use instrumentation/integration; pure decisions use JVM tests; UI semantics use instrumentation and human accessibility review.

| Specification criterion (verbatim) | Test name (source / level) | Fulfilled in |
| --- | --- | --- |
| After confirmed manual upload and process recreation, the UI restores its reporting date, confirmation time, granted-category coverage and confirmed-file count as historical local success. | Committed manual success survives a fresh store reopen with coherent fields (①/④, SQLite integration) | T3 completion |
| The history stores only the latest attempt and latest confirmed success, with a format version and bounded metadata, and contains no token, account/file/record identifier, raw measurement, CSV body or provider error text. | History codec rejects forbidden fields and oversized or malformed metadata (①/②, JVM) | T2 completion |
| An attempt records authorization-request time separately from actual execution-start time, so cancelling authorization does not create a fictitious started upload. | Cancelled authorization has a request time and no execution start (①/④, JVM session) | T5 completion |
| A failed local start-state write prevents health reading and remote upload, and an uncommitted result is never presented as a durably recorded success. | Failed running-state commit prevents every reader and uploader effect (①/③, JVM session) | T5 completion |
| Failure or interruption while persisting a result preserves the previous committed record and reports an explicit local-history failure without asserting that already-issued remote writes were rolled back. | Failed result transaction reopens the prior committed success (①/③, SQLite integration; transient error UI in T5) | T3, T5 completion |
| A persisted started execution without a committed terminal outcome is shown as interrupted or indeterminate after process recreation, with no automatic retry and no fabricated success. | Restoring unfinished execution reports unknown outcome without retry (①/④, JVM session) | T4 completion |
| After process recreation, upload consent is unchecked, no token or prepared continuation is restored, and no health read or network write occurs until fresh manual consent/authorization/continuation. | Reopened history grants no consent token continuation or external execution (①/④, JVM session and Activity instrumentation) | T5, T7 completion |
| A later failed, empty, permission-required or interrupted attempt updates latest-attempt status while retaining the previous independently confirmed success. | Later non-success outcomes retain independently committed prior success (①/②/④, JVM) | T2 completion |
| Duplicate UI actions cannot run overlapping manual executions or let a stale completion overwrite a newer local forget operation. | Duplicate starts and delayed completions cannot overwrite acknowledged deletion (①/④, JVM session) | T4 completion |
| Missing history starts as no local history, while unsupported or corrupt stored history produces a recoverable explicit state and never fabricated zero measurements or success. | Absent unsupported corrupt and unavailable stores have distinct reopen results (①/②/③, SQLite integration) | T3 completion |
| The UI distinguishes authorization time, actual execution start, latest outcome and previous confirmed reporting date, and indicates partial authorized scope rather than complete five-category coverage. | Details distinguish authorization execution and result times with partial scope (①/②, Compose instrumentation) | T6 completion |
| Restored history is labelled as local historical evidence whose current Google-account association and upstream completeness are unverified, and is never used to skip a fresh read/upload or advance a backfill cursor. | Restored success is labelled historical and never bypasses manual execution (①/④, Compose instrumentation and session) | T6, T5 completion |
| A confirmed successful local forget clears persisted history and in-memory consent/token/status without deleting Drive files or claiming to revoke Health Connect or Google grants; failure and interruption follow the deletion criteria below. | Confirmed local deletion invalidates credentials and reopens empty without remote effects (①/④, JVM session plus SQLite integration) | T4, T3 completion |
| The same reporting-day paths, CSV payload rules, explicit EMPTY semantics, granted-type filtering and private Drive target checks remain unchanged under the existing meaningful regressions. | CSV headers ordering quoting and missing values follow v0_1; Empty and denied reads never upload or report success; Successful partial-grant sync uploads only authorized populated datasets (①/⑤, existing JVM regression suites; reuse PrivateDriveUploaderTest and reader suite too) | T5, T7 completion |
| The collapsed home shows the target reporting date, current attempt/error state, independently retained last confirmed success and the next permitted action without opening details. | Collapsed home shows current error prior success target day and next action (①, Compose instrumentation) | T6 completion |
| Last-success date, timestamp, coverage and file count all come from the same committed success record; a later failed attempt's count is never displayed as that success's count. | Success card count date time and coverage come from one committed success (①/④, Compose instrumentation) | T6 completion |
| Expanded details distinguish authorization-request time, actual execution-start time and terminal time, displaying absent times as “미시작” or “확인 불가” as appropriate rather than inventing timestamps. | Details render missing execution and terminal times without invented timestamps (①/②, Compose instrumentation) | T6 completion |
| A failed or interrupted latest attempt remains visible alongside an earlier success; EMPTY is shown as no records to export, not successful upload. | Latest failure and empty outcome remain distinct from prior successful upload (①/②, Compose instrumentation) | T6 completion |
| Authorization and explicit upload continuation remain separate actions, with no automatic read/upload caused by expanding details, restoring the screen or loading history. | Expanding details or restoring UI never authorizes reads or uploads (①/④, Compose instrumentation) | T6 completion |
| The screen remains readable and operable with system light/dark themes, enlarged system text and TalkBack; critical state, confirmation controls and next actions must not be clipped, overlap or rely on color alone. | Home actions remain reachable in both themes and enlarged text (①/②, Compose instrumentation; manual TalkBack checklist required) | T7 completion |
| Synthetic UI previews or screenshots cover no history, committed success, authorization pending, running, interrupted, history-read failure and deletion failure; these are review artifacts, not evidence of real account/device success. | Synthetic state gallery captures all required history and execution states (①, Compose instrumentation and artifact review) | T7 completion |
| Cancelling the deletion dialog changes neither durable history nor current consent/token state. | Cancelling delete confirmation preserves credentials and durable history (①/④, JVM session and Compose instrumentation) | T4, T6 completion |
| Deletion is unavailable while permission/authorization is pending, a sync is running, or a history read/write/delete is in progress; the reason is visible and the operation is rejected even if a stale UI event arrives. | Busy sessions reject stale delete requests and explain disabled deletion (①/②/④, JVM session and Compose instrumentation) | T4, T6 completion |
| Confirming deletion immediately clears in-memory consent, token and prepared continuation before durable deletion is attempted. | Confirmed deletion invalidates credentials before the first store suspension (①/④, JVM session) | T4 completion |
| A durable empty-state commit clears both latest attempt and latest success together; a fresh store reopen after acknowledged completion finds no local history. | Acknowledged empty-state transaction reopens without attempt or success (①/④, SQLite integration) | T3 completion |
| If deletion fails, show “이력 삭제에 실패했습니다. 저장된 이력이 남아 있을 수 있습니다.” and provide “삭제 다시 시도”; do not show “삭제 완료” or restore consent/token. | Failed deletion offers retry without claiming completion or restoring credentials (①/③, JVM session and Compose instrumentation) | T4, T6 completion |
| After deletion failure, any retained readable record is marked “삭제되지 않은 기존 이력”; unreadable storage remains explicitly unknown rather than appearing empty. | Failed deletion labels retained history while unreadable history remains unknown (①/②/③, JVM session and Compose instrumentation) | T4, T6 completion |
| After deletion failure, new authorization/upload stays disabled for the current session until deletion succeeds or the user explicitly chooses “삭제 취소·기존 이력 유지” and the store is successfully reloaded; neither action restores consent/token. | Delete failure blocks new authorization until reset or explicit keep and reload succeeds (①/③/④, JVM session) | T4 completion |
| If the process ends during deletion, reopening shows the actual committed state: empty after committed clearing, otherwise the retained prior history or an explicit read error; it never claims an interrupted deletion completed. | Interrupted reset reopens committed old or empty state without claiming deletion completed (①/④, SQLite integration fixtures plus actual Galaxy process-loss checklist) | T3, T7 completion |
| An uncommitted deletion intention need not survive process loss; persisted history may reappear after an interrupted/failed deletion, but a durably acknowledged deletion must not be undone by stale callbacks, result writes or recovery. | Stale generation cannot rewrite an acknowledged reset after recovery (①/④, JVM session plus SQLite reopen) | T4, T3 completion |
| Deletion is idempotent: deleting already-empty history succeeds without remote calls, and any deletion failure remains a storage error rather than a fake success. | Repeated empty deletion is local and persistence failure never becomes success (①/②/③, SQLite integration and JVM session) | T3, T4 completion |
| Local deletion does not delete Drive exports, revoke external grants, clear Health Connect records or promise forensic secure erasure. | Delete confirmation states its local-only scope and triggers no external deletion (①, Compose instrumentation and effect assertions) | T6, T4 completion |
| A persisted pending authorization without execution start restores as “인증 단계에서 종료됨 · 업로드 미시작”; persisted authorization success awaiting continuation restores as “업로드 시작 전 종료됨 · 다시 인증해 주세요.” | Recovery distinguishes abandoned authorization from awaiting-upload interruption (①/②/④, JVM) | T2 completion |
| A persisted running attempt without a committed terminal result restores as interrupted/indeterminate, without fabricated completion time or guaranteed uploaded-file count; remote files may already exist. | Running recovery never invents terminal time or confirmed upload count (①/④, JVM) | T2 completion |
| A committed terminal result restores its actual outcome, while failed/empty/interrupted attempts retain the independently committed previous success. | Terminal restoration preserves actual outcome and prior independent success (①/②/④, JVM and SQLite reopen) | T2, T3 completion |
| Unsupported-format, corrupt and unavailable history are distinguished from an absent history store; no automatic reset or overwrite occurs merely because reading failed. | Reading incompatible corrupt or unavailable history never overwrites it (①/②/③, SQLite integration) | T3 completion |
| While history is unreadable or unsupported, new authorization/upload is blocked until an explicit reload succeeds or a user-confirmed local reset durably succeeds; reset failure follows the deletion-failure rules. | Unreadable history blocks upload until explicit successful reload or reset (①/③/④, JVM session) | T4 completion |
| Persisting a recovered status uses the same atomic failure rules as ordinary results; if recovery persistence fails, show the derived interruption together with a local-history error and do not claim durable recovery. | Recovery-save failure shows derived interruption and local storage error (①/③, JVM session plus SQLite rollback integration) | T4, T3 completion |
| App reopen never restores tokens, upload consent or a prepared continuation, even after failed deletion or abandoned authorization. | Reopen after abandoned authorization or failed deletion never restores credentials (①/③/④, JVM session and Activity instrumentation) | T5, T7 completion |

Additional boundary baseline: envelope exactly 16 KiB/one byte over, zero/nonzero/unknown file counts, all five coverage categories/duplicates/unknown values, missing/invalid/incompatible stores, repeated reset, double clicks, delayed callbacks, midnight date selection and read-only/unavailable storage. Reuse existing source/CSV/Drive tests for previous regressions rather than mirror their implementation.

## 4. 작업

### T1: Establish real SQLite reopen test harness

- **변경 파일**

  | File | New/edit | Change |
  | --- | --- | --- |
  | `app/src/main/java/com/ny/my/health/HistoryDatabase.kt` | New | Private path, version-1 single-row schema, transaction helper |
  | `app/src/androidTest/java/com/ny/my/health/HistoryDatabaseInstrumentedTest.kt` | New | Real Android DB context/reopen smoke |

- **인터페이스**: Internal DB open/close and transaction helper only; typed history behavior comes in T3.
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
- **주의사항**: Local emulator is absent; use approved CI execution or report blocked. Confirm platform DB path/pragma behavior before coding; if unsupported, stop for plan correction. Do not install a destructive corruption handler or add a persistence dependency.

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
- **작성할 테스트**: New core tests assigned to T2 in the traceability table, plus exact parser-cap boundary, invalid enums/duplicates, negative counts and date/time validation (②/③).
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

- **인터페이스**: Shared `HistoryStore`, `SyncHistory` and T1 DB helper.
- **결정 로직**: Absent, unsupported, corrupt and unavailable are distinct results; unknown storage never silently initializes or overwrites history.
- **호출 순서**:
  1. Validate encoded envelope before starting a write transaction.
  2. Commit one bound row update; acknowledge only after transaction completion.
  3. Error or cancellation → preserve uncertainty, close safely, reopen to determine committed state.
- **작성할 테스트**: T3 traceability cases use actual SQLite close/reopen; inject precommit failure for result/reset rollback and repeat with committed empty state. Test unreadable database separately from invalid JSON and unsupported version.
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
- **작성할 테스트**: All T4 mapped cases; deferred completions prove busy/generation behavior. Use small handwritten boundary fakes and deterministic coroutine gates, not timing sleeps.
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
  | `app/src/main/java/com/ny/my/health/HistorySession.kt` | Edit | Testable execution/authorization effect boundary |
  | `app/src/test/java/com/ny/my/health/HistorySessionTest.kt` | Edit | Ordered integration of fake read/upload/credential effects |

- **인터페이스**: Shared `HistorySession`; preserve current coordinator and Google SDK integration signatures where possible.
- **결정 로직**: History restore never satisfies authorization/upload opt-in; only successful terminal results replace committed success; recoverable local-history error differs from remote outcome.
- **호출 순서**:
  1. Durable authorization-attempt write → permit SDK launch; failure blocks launch.
  2. Persist awaiting-continuation phase → show explicit continuation; stale SDK callbacks are rejected.
  3. Durable running write → start existing coordinator; failure means zero read/upload calls.
  4. Coordinator terminal/cancellation → serialized terminal record; result-save failure retains old committed success and discloses transient remote evidence.
- **작성할 테스트**: T5 mapped cases: zero effects after start-write failure, remote confirmation/local-save failure, preserved prior success, pause cancellation, late callbacks and duplicate continuation. Reuse existing reader/coordinator/transport tests.
- **완료 기준**: Existing SDK launches remain Activity-owned; no persisted token; independently scoped terminal-history write cannot be accidentally cancelled with the sync job, and never attempts remote replay.
- **검증 방법**:
  ```bash
  cd android
  ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug
  ```
- **주의사항**: Reconfirm installed lifecycle cancellation behavior before implementation. Process death can prevent any final write; recovery must still work. Unknown assumption or incompatible existing API is blocked rather than bypassing foreground checks.

Checkpoint after T5: inspect actual ordering tests and credential handling; no visual completion claim before screen and lifecycle checks.

### T6: Render status-first home and deletion recovery

T5 완료 후 시작.

- **변경 파일**

  | File | New/edit | Change |
  | --- | --- | --- |
  | `app/src/main/java/com/ny/my/health/HealthHomeScreen.kt` | New | Approved home layout and explicit dialog/error actions |
  | `app/src/main/java/com/ny/my/health/MainActivity.kt` | Edit | Pass state/actions; keep permission/Google launchers |
  | `app/src/androidTest/java/com/ny/my/health/HealthHomeScreenInstrumentedTest.kt` | New | Synthetic state semantics and dialog behaviors |

- **인터페이스**: Shared `HealthHomeScreen`, `HistoryUiActions`.
- **결정 로직**: Current error and committed success are independent; missing start/finish time is explicit; success card fields originate from the same committed success.
- **작성할 테스트**: T6 traceability cases plus delete dialog cancel/confirm semantics, busy-state rejection and local reload with no external effects.
- **완료 기준**: Approved section-4 arrangement/copy is visible; latest failure survives earlier success; token is never part of the render state; details expansion invokes no read/upload.
- **검증 방법**:
  ```bash
  cd android
  ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.ny.my.health.HealthHomeScreenInstrumentedTest
  ```
- **주의사항**: Reuse Material 3 and existing scrolling layout; no navigation/chart dependency. Distinguish unavailable actions with text as well as disabled controls; no real health data in captures.

### T7: Validate lifecycle and accessible UI states

T6 완료 후 시작.

- **변경 파일**

  | File | New/edit | Change |
  | --- | --- | --- |
  | `app/src/androidTest/java/com/ny/my/health/HealthHomeScreenInstrumentedTest.kt` | Edit | Synthetic states/themes/font-scale evidence |
  | `app/src/androidTest/java/com/ny/my/health/HealthAppInstrumentedTest.kt` | Edit | Preserve first-launch/zero-grant/recreation guarantees |
  | `../docs/V1/device-acceptance.md` | New | Explicit Galaxy/TalkBack/process-death steps |
  | `../docs/V1/execution-report.md` | New | Actual tests, screenshots, pending evidence |

- **인터페이스**: Existing instrumentation runner and shared `HealthHomeScreen`.
- **결정 로직**: None; test actual rendering and evidence boundaries.
- **작성할 테스트**: T7 mapped cases; synthetic captures for no-history/success/authorization/running/interrupted/read-failure/delete-failure, light/dark themes and enlarged text. Retain existing consent and zero-grant paths using approved new UI copy.
- **완료 기준**: Instrumentation results/captures reviewed, Galaxy force-stop and TalkBack results recorded only when actually performed; pending physical checks prevent full conformance PASS.
- **검증 방법**:
  ```bash
  cd android
  ./gradlew :app:connectedDebugAndroidTest
  ```
- **주의사항**: Emulator recreation is not force-stop proof. Do not skip or weaken old safety assertions to match the new UI; update obsolete wording only against the approved spec.

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

## 환경 준비 기록

| 원인·근거 | 준비 조치 | 조치 사용/1 | 확인 명령·결과 | 확인 사용/1 | 다음 조치 |
|---|---|---|---|---|---|
