---
plan_id: core-store
title: 날짜별 복구와 내구성 상태
status: in_progress
author: Codex
created: 2026-11-05
parent: ../plan.md
parent_rev: 222bdc4baa7d68e31ba52f2321520b9b0e5e3d62
spec: ../spec.md
spec_rev: 97c01b1992ad7935e6fa8f1f8b371a3963457daa
branch: feat/v1-completion
tasks:
  - {id: T1, title: 등록 이후 bounded 날짜 계획, depends_on: [], status: done, tier: standard}
  - {id: T2, title: 재읽기 revision과 scope별 확인, depends_on: [T1], status: done, tier: standard}
  - {id: T3, title: pending 기반 destination 실행, depends_on: [T2], status: done, tier: standard}
  - {id: T4, title: 실제 SQLite의 원자적 상태 저장, depends_on: [T3], status: done, tier: standard}
  - {id: T5, title: process 공용 admission과 중단 복구, depends_on: [T4], status: done, tier: standard}
  - {id: T6, title: OS 강제종료의 실제 commit 검증, depends_on: [T4, T7, T9], status: done, tier: standard}
  - {id: T7, title: 누적 메타데이터 비용 측정, depends_on: [T3], status: done, tier: standard}
  - {id: T8, title: 측정된 metadata 반복 조회 축소, depends_on: [T4, T7], status: done, tier: standard}
  - {id: T9, title: 계정없는 emulator bootstrap 안정화, depends_on: [T7], status: done, tier: light}
  - {id: T10, title: 실제 hot journal Android 재현, depends_on: [T4, T7, T9], status: done, tier: standard}
  - {id: T11, title: 검증된 SQLite crash rollback, depends_on: [T4, T7, T9], status: in_progress, tier: standard}
---

# 날짜별 복구와 내구성 상태

## 1. 맡는 기준

상위 [에픽 3절](../plan.md)의 core-store 담당 행만 인용한다. 기준·테스트 이름·출처/층은 원문 그대로이며 충족 위치만 아래 T에 연결한다. 복합 행의 실제 HC/Drive/provider/worker/UI 증명은 표시한 후속 plan 소유다. 소유자의 V1 자동승인 위임에 따라 approved이며 구현은 별도 실행 세션에서 시작한다.

| 스펙 기준 | 테스트 이름 (출처/층) | 충족 위치 |
|---|---|---|
| 새 목적지 구성은 한국 시간 어제를 등록일로 저장하고 기존 foundation 이력을 coverage로 사용하지 않으며 등록 전·오늘·미래 날짜를 읽거나 enqueue하지 않는다. | `Enrollment at KST midnight excludes old history today future and pre-enrollment days` (①·②/유닛 및 SQLite 통합) | T1/T4 완료 기준 |
| planner는 등록 이후 recent 두 날짜를 포함해 가장 오래된 eligible 누락으로 채운 최대 7개 고유 날짜를 오름차순으로 만들고 남은 gap·history blocker를 내구성 pending으로 유지한다. | `Recent two days reserve slots before oldest eligible gaps and retain blocked pending days` (①·②·④/유닛 및 SQLite 통합) | T1/T4 완료 기준 |
| EMPTY·제한된 category 확인·upload confirmation·연속 checked-through·uploaded-through를 구분하고 일부 허용 범위나 worker 실행 성공만으로 5종 전체 완료·현재성·gap 없는 coverage를 주장하지 않는다. | `Empty and partial grants never claim five categories upload success or continuous uploaded coverage` (①·②·④/유닛 및 Compose E2E) | T1/T2/T5 부분; automation-ui 후속 |
| 매 실행 한국 시간 오늘에서 30일을 뺀 보수적 HC read floor 이전 또는 가용성/권한이 불명확한 날짜를 HISTORY_ACCESS_REQUIRED와 미확인 gap으로 유지하고 추정 최초 grant 시각을 저장하거나 원본 제한·오류·page/sample 초과를 빈 데이터로 처리하지 않는다. | `Dates below the conservative history floor and unknown access remain blocked instead of empty` (①·②·③/유닛 및 HC adapter 통합) | T1/T3 부분; manual-drive 후속 |
| destination generation/date/dataset/revision과 시도·시작·확인 시각을 원자적으로 저장하고 저장 실패·강제 종료 전후 재열기에서 확인되지 않은 파일/날짜의 성공을 만들지 않는다. | `Reopening around atomic commits never confirms an unacknowledged file or date` (①·③·④/실제 SQLite·host process 통합) | T4/T6 완료 기준 |
| canonical content가 같으면 generated_at 차이로 새 revision을 만들지 않고 원본 재읽기로 revision을 확인한 뒤에만 이전 확인을 재사용하며 달라진 snapshot은 새 revision으로 전환한다. | `Source reread reuses canonical revision despite generated time and advances changed snapshots` (①·②·④/유닛) | T2/T3 완료 기준 |
| 부분 실패·취소·결과불명 후 대상 reconciliation을 거쳐 pending 파일만 다시 처리하고 이미 확인된 동일 revision의 건강한 파일을 중복 create하거나 prior success를 지우지 않는다. | `After partial failure cancellation or unknown create reconciliation retries only pending files` (①·③·④/유닛 및 local TLS·provider 통합) | T3 부분; manual-drive/saf-local 후속 |
| 이전 populated dataset이 비게 되면 잔존 원격 파일을 REMOTE_STALE로 표시하여 새 revision의 full-current를 막고 원격 삭제·완료를 꾸며내지 않는다. | `Disappearing populated datasets retain remote stale and prevent full current without deletion` (①·④/유닛 및 Compose E2E) | T1/T2/T5 부분; automation-ui 후속 |
| 전경·주기·catch-up·중복 UI가 동시에 진입해도 공용 gate로 단일 실행만 시작하고 프로세스 손실 후 durable journal을 복구하며 설정 generation 변경 뒤 지연 결과가 새 coverage를 덮지 못한다. | `Concurrent manual periodic and catch up requests admit once and reject old generation callbacks after restart` (①·③·④/유닛 및 SQLite·worker 통합) | T1/T2/T5 부분; automation-ui 후속 |

## 2. 실행에 필요한 현재 상태

### 조사와 실행 경계

- 기준 checkout의 제품 코드는 `20baaa4` 계열이며 `Ports.kt`의 DriveUploader에는 upsert만 있다. `/workspace/health-app-top-layout`의 `de2852a1ebdd5ffbfe686c3fab84d24230b6f8dd`에서 prospective upsertBatch를 읽기 전용으로 확인했다. **신규 SDK-free 모델·planner·revision T1/T2는 현재 포트/API를 사용하지 않아 별도 worktree에서 먼저 구현·유닛 검증한다. T3의 기존 포트/Android/CI 연결과 이 PR 병합은 PR #15 독립 검토·master 병합 후 actual merge head 반영 및 Ports/PrivateDriveUploader/SyncCoordinator/배치 회귀 재조사 전에는 시작하지 않는다.** 예상 API가 달라지면 실행자가 우회하지 않고 plan/상위 공통 계약을 보정한다.
- AGENTS.md와 README, implementation-plan, architecture, data-contract, codex-first-task, 승인 spec/epic을 읽었다. C/CT/A/AT/AI 접두어는 상위 2절과 동일하다. 아래 신규 경로는 기존 package 디렉터리 안에 만든다.
- `Model.kt`: DataType 5종, HealthSnapshot, CsvFile(relativePath, content). `Normalization.kt`: DayWindow.zone/start/end/yesterday, DefaultNormalizer. `VersionedCsvExporter.kt`: schema 0.1, daily/workouts/heart-rate/sleep, 정렬·CRLF. 이 구현·기존 coordinator 동작은 보존한다.
- `HistoryDatabase.kt`의 noBackupFilesDir, no-delete corruption handler, version/schema 확인, DELETE/FULL commit, beforeCommit 테스트 경계와 `SqliteHistoryStoreInstrumentedTest`의 실제 SQLite fixture를 참고한다. **기존 history 파일/스키마/클래스는 수정하거나 새 store에서 열지 않는다.** SQLite instrumentation 기반은 이미 있으므로 신규 framework/starter 도입 T가 필요 없다.
- 기존 CI API35/36과 verify-history-process-recovery.sh가 host-only hold를 제외하고 별도 force-stop 검증한다. 새 host harness는 독립 class/flag/합성 marker를 사용한다. UI·실제 계정·SAF·WorkManager·자동 실행 연결은 이번 PR에서 하지 않는다.

### SyncState: scope별 구체 모델

31개 metadata 타입의 선언과 모든 필드는 상위 2절 `SyncState` 코드 조각을 단일 원본으로 사용한다. 이 문서는 선언을 복제하지 않고 이 작업의 불변식·SQL·app-private binding만 보완한다. nullable도 상위 선언 그대로다. 모델은 모두 android/core/src/main/kotlin/com/ny/my/health/core/SyncState.kt에 둔다. scope 계약과 ADR-006의 보정은 parent_rev의 커밋에 반영됐다.

```kotlin
// SyncState — local invariants; declarations are canonical in parent section 2
// Exact-scope invariants: exact current-scope rows, never union manual and automatic scopes.
// READY with configuration=null is a durable reset tombstone: fresh generation, automatic off,
// no attempt, scopeKey=null, requestedScopes={}, days={}, oldestPendingDays=[], null through dates.
// ABSENT has state=null; error statuses have state=null and are never a synthetic empty state.
// Scoped load has days only for its exact scopeKey; load() has no selected scope or candidate days.
// Mutation carries exactly one of start/day/confirmation/pendingTriggers/finishAt.
// Generation + attempt id + scopeKey + date + dataset + revision must match before confirmation.
// A new day revision updates only its scope row; other scope rows and prior confirmation survive.
// Core-store retriesUsed starts at zero; worker retry policy is connected only in a later plan.
```

- `SyncState`：tombstone coverage의 scopePendingCount/scopeBlockedCount는 0, excludedTypes/recent sets는 empty다. 구성은 있으나 load()에서 scope를 선택하지 않았으면 scope counters=null이며 0 gaps로 표시하지 않는다. configuration=null READY도 generation을 가지므로 새 configure는 이 tombstone generation CAS를 요구한다.
- `SyncMutation`：start는 새로운 id와 같은 attemptId가 필요하고 나머지는 현재 id가 필요하다. finish 뒤 callback은 INVALID이며, 현재 pendingTriggers의 union만 예외로 허용한다. confirmation은 현재 scope row의 revision·dataset·RUNNING/INDETERMINATE journal과 일치해야 한다. 같은 확인 재커밋만 멱등이며 서로 다른 target/시각으로 덮지 않는다. pendingTriggers 병합은 현재 generation/attempt만 바꾼다.
- `SyncState`：retryBudgetKey는 generation + scopeKey + 오름차순 dates + 현재 해당 scope revision digest(미조회 literal UNREAD)를 canonical 길이 구분하여 SHA-256한다. 실제 자동 retry는 이번 PR에서 실행하지 않으며 0..3 저장/재열기·동일 key 보존을 검증한다.

### SyncPlanner: scope를 먼저 고르는 조회 계약

함수 선언은 상위 2절 `SyncPlanner`를 그대로 사용한다.

```kotlin
// SyncPlanner — local planning rules; signatures are canonical in parent section 2
// Rules: access before candidate filtering and before the SQL LIMIT.
// Active profile = configured categories intersect granted, non-BLOCKED scopeByType entries.
// ScopeKey = SHA256(length-prefixed UTF8 "sync-scope/1", sorted DataType name + ReadScope name).
// Empty/unknown active profile has scopeKey=null; never create a complete empty-profile day.
// Denied/BLOCKED configured types remain excludedTypes, never silently claim five-type completeness.
// A day is checked for its exact profile only when all requested scopes were read successfully.
// It is uploaded only when nonempty intended datasets are confirmed and none is stale/unknown.
// EMPTY completes scoped checked progress; it never counts as upload confirmation.
// Catch-up excludes completed checked days for this profile; it does not use full-five coverage.
// A checked day with pending/indeterminate/stale files is still a gap until reconciliation resolves it.
// Recent two eligible dates are reserved even when complete, then oldest eligible gaps fill max seven.
// Old floor blockers and source-blocked rows remain counted; not all missing days consume the budget.
// Full-five EXPLICIT_DAY aggregates use only that exact profile, never unions of other profiles.
// Prior confirmations are historical; source reread + current reconciliation precede any reuse.
```

- `SyncPlanner`：ReadAccess.scopeByType는 현재 adapter가 요청할 scope를 나타낸다. configuration.categories 중 granted이며 non-BLOCKED로 알려진 항목만 active profile에 들어간다. excludedTypes는 configured-active다. 일부 grant면 active subset으로 날짜별 복구가 진행되며 UI는 그 subset과 제외 타입을 함께 표시해야 한다. available=false 또는 active empty이면 읽지 않고 SOURCE_ACCESS_REQUIRED로 유지한다. 이 경우 loadForScope는 선택 profile 없는 상태(scopeKey=null)를 반환하고 planner는 dates=[]와 recent dates의 SOURCE_ACCESS_REQUIRED, remainingPending=등록일~어제 전체 날짜 수를 반환한다. 범위 미선택 load()의 scope counter null을 완료/0 gaps로 사용하지 않는다. readScoped가 실행 중 특정 active scope를 BLOCKED/실패로 반환하면 **기존 요청 scopeKey 아래 미확인**으로 저장하고 더 작은 성공 scope로 바꿔치기하지 않는다.
- `SyncPlanner`：store는 요청 scope fingerprint를 먼저 계산한 뒤 정확히 그 profile에서 missing/미완료 day를 찾는다. completed=true는 checkedAt!=null, 모든 requestedScopes와 실제 scopes 일치, 파일 없음이면 EMPTY, 파일 있으면 모든 intended dataset CONFIRMED라는 뜻이다. history floor 미만·명시 복구가 필요한 destination blocker/REMOTE_STALE는 읽을 후보에서 제외하되 별도 counts에 남긴다. 이전 blocker는 access 회복 조건을 우선 재평가하며 설정/명시 복구 필요 blocker는 계속 제외한다. 같은 profile에서 앞선 완료 날짜가 oldest7을 계속 차지하지 않는다.
- `SyncPlanner`：SyncState.days는 해당 scope의 oldest7+recent2(최대9일)만 갖는다. store는 등록일~어제 metadata를 행별 streaming/query로 검사하며 모든 날짜를 JSON/list로 적재하지 않는다. floor 미만 gap은 enrollment/day metadata와 scopePendingCount/scopeBlockedCount로 유지한다. 아직 행이 없는 날짜도 gap이다. recent=어제/그제 중 등록 이후, floor=KST오늘-30일 포함. recent eligible 예약 후 oldest eligible gaps로 최대7을 채워 unique ascending으로 반환한다. 오늘/미래/등록 전 read 금지.
- `SyncPlanner`：scopeCheckedThrough는 등록일부터 현재 exact active profile에서 gap 없이 checked한 끝날(EMPTY 포함), scopeUploadedThrough는 등록일부터 EMPTY/unknown/stale 없이 실제 upload-confirmed한 끝날이다. checkedThrough/uploadedThrough는 별도로 **5종 EXPLICIT_DAY 단일 profile**만 집계한다. 부분 scope 연속 확인이 전부 끝나도 full-five 날짜는 null일 수 있으며 이것은 현재 scope catch-up pending 판정에 사용하지 않는다. 최근 날짜 sets와 scope counters는 선택 profile에만 해당한다. lastUploadAt은 어느 profile이든 실제 confirmation의 마지막 시각이며 전체 완료 증거가 아니다.

### SyncStateStore: 상위 scope 조회와 로컬 engine 포트

상위 2절 `SyncStateStore`와 `StorageDestination` 선언을 사용하고 아래 두 로컬 runtime 계약만 추가한다.

```kotlin
// ScopedHealthReader — access chooses requested profile before querying a date
interface ScopedHealthReader {
    suspend fun access(trigger: SyncTrigger): ReadAccess
    suspend fun readScoped(date: LocalDate, requestedScopes: Map<DataType, ReadScope>): ScopedSnapshot
}
// DestinationSync — attempt is created by the session with the selected scope already bound
class DestinationSync(store: SyncStateStore, reader: ScopedHealthReader,
    destination: StorageDestination, clock: java.time.Clock) {
    suspend fun run(trigger: SyncTrigger, generation: DestinationGeneration,
        attemptId: String): BatchOutcome
}

```

- `SyncStateStore`：loadForScope는 expected generation/current config/access를 같은 읽기 snapshot에서 확인하고 scope filtering→현재 profile 완료일 제외→eligible floor와 blocker 판단→oldest 최대7 LIMIT 순서로 선택한다. 이전 EXPLICIT_DAY 완료와 자동 SUMMARY/WORKOUT_SCOPED 완료는 각각 별도 row로 남는다. loadDay null은 정확한 generation/date/scope row 부재만 뜻하며 stale generation/DB 오류는 예외다. planner는 state.scopeKey와 access에서 다시 계산한 key가 다르면 빈 실행으로 넘기지 않고 admission을 중단한다.
- `DestinationSync`：session이 access→loadForScope→scopeKey가 고정된 attempt start를 commit한다. engine는 access 재확인 후 attempt scopeKey와 다르면 source read/write 없이 STALE로 반환한다. 동일 key이면 selected day의 exact-scope 이전 state를 loadDay하고 readScoped→identify한다. 당일 조회 중 scope가 BLOCKED로 변하면 해당 row를 BLOCKED로 남긴다.

### DestinationException: 안전한 실패 분류

```kotlin
// DestinationException — safe allowlisted boundary, lives in Ports.kt
class DestinationException(val reason: BlockReason) : IllegalStateException(reason.name)
// Accept only DESTINATION_TRANSIENT_FAILURE, DESTINATION_RECONCILIATION_REQUIRED,
// ACCOUNT_AUTHORIZATION_BLOCKED, DESTINATION_BLOCKED. No provider message/cause parameter.
```

- `DestinationException`：reconcile/writePending 호출만을 분류 경계로 삼는다. typed transient는 current day 최신 상태를 다시 읽고 RUNNING 파일을 INDETERMINATE로 보존한 후 blocker=DESTINATION_TRANSIENT_FAILURE·phase=PENDING 또는 INDETERMINATE로 커밋한다. 영속화 성공 때만 RETRYABLE이며 store/stale 오류를 transient로 오인하지 않는다. 원격 확인·priorConfirmation·성공한 source checkedAt을 보존한다.
- `DestinationException`：불명확 create는 DESTINATION_RECONCILIATION_REQUIRED, 계정 resolution은 ACCOUNT_AUTHORIZATION_BLOCKED, unknown 일반 목적지 예외는 DESTINATION_BLOCKED로 day BLOCKED journal을 남긴다. 명시된 고정 reason 외 constructor 입력은 거부한다. 모두 자동 후보에서 제외하고 null/error를 EMPTY/confirmed로 만들지 않는다. cancellation은 별도 전파하며 transient 사유를 부여하지 않는다. 일시 실패라도 outstanding unknown-create 파일이 있으면 adapter가 RECONCILIATION_REQUIRED로 우선 분류해야 한다.
- `SyncState`：새3종 reason은 source checkedAt을 무효화하지 않는다. transient reason은 PENDING/INDETERMINATE에서만, 새 영구2종은 BLOCKED에서만 유효하다. 기존 DESTINATION_BLOCKED의 transient 변환 금지. `SyncPlanner`는 RECONCILIATION_REQUIRED/ACCOUNT_AUTHORIZATION_BLOCKED를 기존 영구 사유와 함께 제외하되 pending counts와 prior 이력은 보존한다. RETRYABLE은 이번 batch에 known transient가 있고 영구 blocker가 없을 때만 반환한다. 일반 PENDING·미분류 interruption은 자동 retry 허가가 아니다. worker retry budget은 후속 plan에서 소비하며 이번에는 변경하지 않는다.

### CurrentAttemptRead: 반복 identity 조회의 최소 증명

```kotlin
// CurrentAttemptRead — additive member of SyncStateStore in Ports.kt, no new persisted DTO
suspend fun loadCurrentAttempt(expected: DestinationGeneration): AttemptState?
// Default: load(); non-READY/error throws fixed exception; differing generation/no attempt returns null.
// Android override validates schema + active metadata + exact attempt, returns no coverage/private binding.
// Null is stale/no current attempt only; DB/canonical header errors are not silently null/empty.
```

- `CurrentAttemptRead`：header는 전체coverage/다른day의건강함을 주장하지 않는다. engine 최초전체load와 각journal/confirmation transaction의 all-active integrity는 유지한다. 현재 header의 id/scope/requestedScopes/dates/finishedAt이 admitted attempt와 다르면 source/remote IO전에 STALE다. opaque generation·attempt 검사를 메모리cache로 대체하지 않는다.

### SyncRevision: canonical projection과 공유 원격 파일

identify 선언은 상위 2절 `SyncRevision`를 그대로 사용한다.

```kotlin
// SyncRevision — local canonical projection rules; signature is in parent section 2
// Rules: requestedScopes come from the admitted profile; snapshot scopes must match.
// Canonical components: "sync-revision/1", schema "0.1", date, scopeKey, sorted type/scope, dataset bytes.
// Each UTF8 component has a big-endian 4-byte length; digest is lowercase SHA256 hex64.
// Normalize/export semantic projection at Instant.EPOCH; never replace generated_at strings.
// Identical same-scope digest reuses that scope's first generatedAt; changed content fixes observedAt.
// Actual CSV byteSha256 is separate from semantic digest and generated with fixed revision time.
// Only a complete same-scope populated-to-empty read proves REMOTE_STALE; no remote deletion.
// Narrower grants/scopes create another profile; other profile targets/confirmations remain historical.
```

- `SyncRevision`：DefaultNormalizer/VersionedCsvExporter의 기존 정렬·CRLF·schema0.1을 재사용한다. revision.previous는 같은 scopeKey만 허용하며 다른 profile이면 INVALID다. previous=null은 blocked 없는 clean snapshot에서 positive granted scopes로 최초 profile을 산출하는 경우만 허용한다. engine의 해당 profile row 없는 최초 읽기는 admitted requestedScopes/scopeKey를 담은 revision=null·phase=PENDING·scopes={}·checkedAt=null·datasets={}·blocker=null DayState를 previous로 제공한다. blockedTypes와 BLOCKED scope entries가 일치해야 하며 scope 불명은 성공 empty로 만들지 않는다. DAILY source는 STEPS/WEIGHT, 나머지는 EXERCISE/HEART_RATE/SLEEP다.
- `SyncRevision`：원격 경로는 기존 dataset/date.csv이므로 서로 다른 scope가 **같은 target을 공유할 수 있다**. 새 scope row를 보존하는 것이 다른 scope의 현재 remote bytes도 보존한다는 뜻은 아니다. writePending 직전 durable RUNNING journal transaction은 같은 generation/date/dataset의 다른 scope dataset을 INDETERMINATE로 전환하고 priorConfirmation에 이전 확인을 보존한다. 그 다른 scope의 requestedScopes/revision/checkedAt은 보존한다. 이 보수적 무효화는 remote write 실패/결과불명에도 유지하며 삭제하지 않는다. 다른 profile의 uploadedThrough/current 판정은 멈추고 이후 그 profile 재실행의 source reread+reconciliation으로만 복원한다. 처음 profile pending journal commit은 해당 profile만 바꾸며 이 cross-scope 무효화는 실제 writePending 대상에만 적용한다.

### SyncStateCodec: JSON leaf 계약과 상한

```kotlin
// SyncStateCodec — only actual JSON leaves in scalar SQLite rows; no row/envelope codecs
object SyncStateCodec {
    fun encodeCategories(value: Set<DataType>): String
    fun decodeCategories(json: String, formatVersion: Int): Set<DataType>
    fun encodeScopes(value: Map<DataType, ReadScope>): String
    fun decodeScopes(json: String, formatVersion: Int): Map<DataType, ReadScope>
    fun encodeDates(value: List<LocalDate>): String
    fun decodeDates(json: String, formatVersion: Int): List<LocalDate>
    fun encodeTriggers(value: Set<SyncTrigger>): String
    fun decodeTriggers(json: String, formatVersion: Int): Set<SyncTrigger>
    fun encodeConfirmation(value: FileConfirmation): String
    fun decodeConfirmation(json: String, formatVersion: Int): FileConfirmation
}
// SyncStateCodec — writer format=1; decoder version comes from scalar config.format_version.
// Unknown formatVersion throws UnsupportedOperationException; invalid JSON/value throws IllegalArgumentException.
// Categories/triggers: JSON arrays of enum names, sorted ascending, unique, max5/max3; empty allowed.
// Scopes: JSON object DataType-name -> ReadScope-name, sorted keys, max5; empty and BLOCKED allowed here.
// Admission separately rejects empty/BLOCKED requestedScopes; actual scopes may contain BLOCKED.
// Dates: ISO LocalDate JSON string array, unique ascending, max7; empty allowed at leaf level.
// Confirmation: flat object with exact keys confirmedAt,dataset,date,generation,revision,scopeKey,targetHandle.
// Confirmation values are strings; keys lexicographic ascending; no extra/nested version or key object.
// All methods enforce encoded UTF8 <=16KiB and depth<=8; sets/maps sort deterministically.
// Decode -> validate type/allowlist/bounds -> re-encode must equal original UTF8 bytes exactly.
// Equality rejects duplicate keys and noncanonical whitespace/order/escaping without a new JSON parser.
// No configuration/day/attempt/SyncState/HealthSnapshot/CsvFile/DayRevision JSON serialization exists.
```

- `SyncStateCodec`：JSON leaf는 generation.categories_json, day.requested_scopes_json/scopes_json, attempt.requested_scopes_json/dates_json/pending_triggers_json, dataset.prior_confirmation_json뿐이다. SQL scalar date/generation/scope/revision/phase/timestamp와 JSON 봉투를 중복 저장하지 않는다. private binding은 app adapter 자체 canonical codec이며 core에 올리지 않는다. null priorConfirmation은 SQL NULL이고 문자열 null을 encode/decode하지 않는다. decode version은 SQL config.format_version에서 검증해 전달한다. version1 JSON leaf 안에 version 필드를 만들지 않는다.
- `SyncStateCodec`：날짜는 ISO LocalDate(1970-01-01~9999-12-31), instant는 ISO Instant(동일 연도 범위), digest/scope key는 lowercase hex64, generation/attempt id는 canonical UUID36, opaque handle은 공백/제어문자 없는 UTF-8 1~4096 bytes다. decode unknown version은 store에서 UNSUPPORTED, malformed/noncanonical leaf는 CORRUPT로 구분한다. own-store 입력만 지원하므로 의미가 같아도 다른 공백·key순서·escape·숫자 표기는 거부한다. 파싱 후 구조를 검사해 depth를 제한하며 16KiB byte 제한은 파싱 전에 적용한다. enum alias/ordinal/대소문자 alias는 허용하지 않는다.
- `SyncStateCodec`：최대5 scopes/categories·4 datasets·7 unique ascending dates·3 pendingTriggers·retriesUsed0..3의 허용 경계 fixture를 검증한다. leaf 상한 초과는 encoder/decoder 모두 거부한다. DB 전체(현재+archived) 최대10000 generation행·10000 attempt행·100000 day행·400000 dataset행은 SQL transaction이 검사하며 codec/state snapshot으로 전역 개수를 추정하지 않는다. 개인 payload/CSV/token/raw record 저장은 금지한다.

### SyncStateTransitions: 순수 mutation 검증의 공개 계약

```kotlin
// SyncStateTransitions — functions live in SyncState.kt, no new source file or persistence layer
fun SyncState.isValid(): Boolean // existing T1 public function; retain signature and observable meaning
fun SyncConfiguration.isValid(): Boolean
fun FileConfirmation.isValid(): Boolean
fun AttemptState.isValid(enrolledDay: LocalDate, todayInSeoul: LocalDate): Boolean
fun DayState.isValid(generation: DestinationGeneration,
    enrolledDay: LocalDate, todayInSeoul: LocalDate): Boolean
fun SyncState.validateMutation(expected: DestinationGeneration?, mutation: SyncMutation,
    currentDay: DayState?, todayInSeoul: LocalDate): CommitStatus
// COMMITTED here means a VALID candidate only: pure code never acknowledges durability.
// STALE means active expected generation / current attempt / scope / date revision identity changed.
// INVALID means malformed shape/metadata or a forbidden transition; UNAVAILABLE is never returned here.
// Only SqliteSyncStateStore.transact may construct durable SyncCommit(COMMITTED) after endTransaction succeeds.
```

- `SyncStateTransitions`：isValid overloads는 상위 DTO와 codec의 필드 범위·관계를 검증한다. Configuration은 nonempty categories와 bounded handle/enrollment, confirmation은 canonical FileKey/target/time를 검사한다. Attempt는 id/key/retry/dates/요청 scope와 scheduled≤started≤finished 관계, Day는 same requested fingerprint·등록일≤date<today·dataset key/revision 일치·현재/과거 confirmation의 generation/date/scope/dataset 관계를 검사한다. 요청 scopes는 nonempty/non-BLOCKED, 실제 scopes는 요청 key의 부분집합이며 BLOCKED를 허용하되 성공으로 승격하지 않는다. priorConfirmation은 과거 revision이 가능하나 동일 generation/date/scope/dataset이어야 한다. 공개 함수 추가 외 private helper/extension 분해는 실행자 구현 판단이며 별도 승인·질문을 요구하지 않는다.
- `SyncStateTransitions`：validateMutation 입력 state는 현재 active generation/config/attempt와 최대9일 snapshot이다. currentDay는 SQL adapter가 **같은 transaction에서 mutation의 exact date/scope를 별도 조회한 권위 행**이며 state.days의 누락으로 부재를 추정하지 않는다. currentDay=null은 실제 row 부재다. 날짜 행 변경이 아닌 start/pendingTriggers/finish에는 null을 전달한다. 전역 coverage/counter/archived rows를 이 함수가 재계산·저장하지 않는다.
- `SyncStateTransitions`：분류 순서는 malformed state/mutation(정확히 한 payload·필수 attemptId·필드 범위)→INVALID, expected!=state.generation→STALE, configuration=null→INVALID다. start는 id==attemptId·현재 unfinished attempt 없음·state의 선택 scope/profile과 일치·등록 이후 어제까지 unique ascending dates여야 한다. 기존 finished attempt를 새 id로 대체할 수 있다. 나머지는 현재 unfinished attempt id가 일치해야 하고 아니면 STALE/finished면 INVALID다. pendingTriggers는 현재 집합과 union으로만 늘리고 finishAt은 startedAt 이후여야 한다.
- `SyncStateTransitions`：day mutation은 attempt.dates에 포함된 exact scope/profile만 허용하며 currentDay와 무관한 scope/date는 STALE다. 새 revision은 기존 확인을 priorConfirmation으로 보존한 PENDING 또는 RUNNING journal로 시작한다. 같은 revision 변경은 기존 CONFIRMED를 근거 없이 지우지 않으며 EMPTY/CONFIRMED는 요청 scope read 완료와 intended dataset별 확인이 모두 필요하다. canonical reread가 입증한 populated→empty는 REMOTE_STALE이며 prior 확인/target을 보존한다. CONFIRMED 외 phase로의 write uncertainty는 current confirmedAt을 지우되 priorConfirmation을 보존한다. 실제 source 비교·revision 산출은 SyncRevision, 상태 검증은 이 함수다.
- `SyncStateTransitions`：confirmation은 currentDay 존재·현재 attempt 날짜/scope·revision·dataset의 RUNNING/INDETERMINATE journal이 모두 일치해야 유효하다. identity mismatch는 STALE, target/시간/phase 불가능은 INVALID다. 이미 CONFIRMED인 동일 FileKey/targetHandle/confirmedAt의 정확한 재커밋만 멱등 유효하며 다른 확인으로 덮지 않는다. SQL adapter가 이 검증 후 해당 dataset 확인과 day outcome을 같은 transaction에서 변경한다. 다른 scope current 무효화·archived 차단·전역 capacity/coverage는 SQL query/transaction 책임이며 bounded state의 순수 reducer로 전체 DB를 대체하지 않는다.

### SqliteSyncStateStore: schema와 private 설정

- `SqliteSyncStateStore`：DB=`context.noBackupFilesDir/sync-state.db`, schema `PRAGMA user_version=1`, metadata format=1. SQLiteDatabase 직접 사용; no-delete corruption handler, DELETE journal/FULL sync, canonical path별 lock, 정상 read 시 생성/수리 없음. load는 ABSENT만 파일 없음으로 반환한다. schema SQL/column/index/trigger allowlist 확인 후 read하며 unexpected trigger/table/view/column은 UNSUPPORTED. nullable은 아래 `?`뿐이다. 모든 FK는 foreign_keys=ON, ON UPDATE RESTRICT/ON DELETE RESTRICT(즉시 검사, deferred/cascade 없음)로 강제하며 행 decode 때도 관계를 검증한다. generation.id는 갱신하지 않는다. schema allowlist는 config/generation/day/dataset/attempt 다섯 table만 허용한다. 상위/ADR-006의 table 목록 보정·commit과 parent_rev 갱신 전에는 제품 실행하지 않는다.

| table | columns / constraints |
|---|---|
| `SqliteSyncStateStore` generation | id TEXT PK UUID36; destination_handle TEXT?; enrolled_day TEXT? ISO; categories_json TEXT allowlist5. configured row는 destination/enrolled nonnull·categories nonempty, tombstone은 destination/enrolled null·categories=[]다. generation id와 구성 metadata는 생성 후 불변이다. private account/URI/binding/token/automatic 필드는 두지 않는다 |
| `SqliteSyncStateStore` config | id INTEGER PK CHECK=1; format_version INTEGER=1; active_generation TEXT NOT NULL FK generation(id); automatic INTEGER 0/1 기본0; binding_kind TEXT? DRIVE/LOCAL/SYNTHETIC; binding_payload TEXT?; reset_epoch INTEGER >=0. active row가 tombstone이면 binding null·automatic=0이다. config와 active generation metadata를 join하여 core SyncState/SyncConfiguration을 만든다 |
| `SqliteSyncStateStore` day | generation TEXT FK generation(id); date TEXT; scope_key TEXT hex64; requested_scopes_json TEXT; revision TEXT? hex64; generated_at TEXT?; phase TEXT DayPhase; scopes_json TEXT; checked_at TEXT?; blocker TEXT? BlockReason; PK(generation,date,scope_key). revision/generated_at은 함께 null/nonnull |
| `SqliteSyncStateStore` dataset | generation TEXT; date TEXT ISO; scope_key TEXT hex64; FOREIGN KEY(generation,date,scope_key) REFERENCES day(generation,date,scope_key); dataset TEXT Dataset; revision TEXT hex64; generated_at TEXT; phase TEXT FilePhase; populated INTEGER 0/1; target_handle TEXT?; started_at TEXT?; confirmed_at TEXT?; prior_confirmation_json TEXT?; PK(generation,date,scope_key,dataset). CONFIRMED는 current target+confirmedAt 필수 |
| `SqliteSyncStateStore` attempt | id TEXT PK UUID36; generation TEXT FK generation(id); scope_key TEXT hex64; requested_scopes_json TEXT; trigger TEXT SyncTrigger; scheduled_at TEXT; started_at TEXT?; finished_at TEXT?; dates_json TEXT; retry_budget_key TEXT hex64; retries_used INTEGER CHECK 0..3; pending_triggers_json TEXT. generation UNIQUE(각 generation의 마지막 attempt 하나; archived도 보존) |

- `SqliteSyncStateStore`：index는 day(generation,scope_key,phase,date), dataset(generation,scope_key,phase,date), dataset(generation,date,dataset)만 추가한다. requested_scopes_json에서 재계산한 digest가 scope_key와 일치해야 하며 BLOCKED/빈 요청 profile은 저장하지 않는다. scope key는 SHA256 hex64이며 JSON 요청 범위와 실제 scopes를 별도 열로 저장한다. 서로 다른 profile의 같은 날짜·dataset rows는 co-exist하며 동일 profile 새 revision만 그 row를 갱신한다. TEXT byte 상한은 codec+SQL `length(CAST(... AS BLOB))`로 강제한다(행 JSON16KiB, binding8KiB, 나머지는 위 한계). SQLite integer timestamp/enum ordinal은 사용하지 않는다. day/attempt의 필드 일부를 JSON 봉투로 중복 저장하지 않는다.

- `SqliteSyncStateStore`：private binding은 **A/SqliteSyncStateStore.kt 내부** `PrivateDestinationBinding(kind, accountName?, accountType?, treeUri?)`로 한정한다. DRIVE는 name/type만 각1~1024 UTF-8, LOCAL은 treeUri 1~4096 UTF-8, SYNTHETIC은 나머지 null. JSON allowlist는 kind/accountName/accountType/treeUri다. private binding도 이 store 자체 canonical writer/decode→re-encode byte equality를 쓰며 외부 JSON/공백 입력을 허용하는 예외를 두지 않는다. token/authCode/record/CSV는 허용하지 않는다. core state/config에는 binding payload/account name/type/URI를 반환하지 않고 opaque destinationHandle만 준다. app 전용 `configure(expected, configuration, binding): SyncCommit` 및 `readBinding(expected): PrivateDestinationBinding?`은 같은 store에 둔다. configure는 fresh UUID generation+enrolled 어제+binding+automatic off+빈 coverage를 **한 transaction**으로 교체한다. 기존 구성 변경은 expected generation 일치 때만 가능, ABSENT에서만 expected=null 초기 구성 허용. READY tombstone에서 새 구성도 tombstone expected generation을 요구한다. 교체 transaction은 새 generation row를 먼저 insert하고 config.active_generation과 active binding/automatic=0을 CAS update한다. 이전 generation/day/dataset/attempt 행은 삭제·재연결하지 않으며 active pointer가 가리키지 않는 이전 세대의 이력으로 남긴다. active binding은 새 선택의 최소 name/type 또는 URI로 교체하며 이전 binding을 archive에 복제하지 않는다. foundation history는 그대로 둔다. SDK-free core에 private 필드를 전달하지 않는다. app adapter의 transact는 canonical private binding을 무결성 검사만 하고 폐기하며 private 열을 덮어쓰지 않고 SQL UPDATE 대상을 필요한 열로 한정한다. 이번 PR 실제 계정/선택 UI는 없고 합성 binding만 검사한다.

- `SqliteSyncStateStore`：모든 transaction은 generation CAS와 attempt/scope/revision/current journal 검증 → SQL 변경 → beforeCommit test hook → endTransaction 성공 → COMMITTED 반환 순서다. load/transact/reset은 계정/HC/destination IO를 하지 않는다. confirmation callback의 store commit 실패는 callback 실패로 전파되며 성공 count를 올리지 않는다. reset 정상DB는 사용자 확인 범위에 archived coverage도 포함한다. 한 transaction에서 새 tombstone generation insert→config.active_generation을 새 id로 전환하며 binding=null/automatic=0/reset_epoch+1→dataset 전체 삭제→day 전체 삭제→attempt 전체 삭제→새 tombstone을 제외한 generation 삭제 순서로 진행한다. 따라서 현재/archived 동기화 metadata만 모두 비우고 fresh generation/configuration=null을 반환한다. foundation history DB는 보존한다. unknown/corrupt 복구 reset은 explicit 호출에서만 `.reset` marker 생성→새 empty DB stage→old DB 보존 교체→marker 존재 중 reopen 검증→marker 제거 순서; 실패하면 marker로 admission을 차단하고 UNAVAILABLE, old/stage를 보존한다. symlink/directory/미확인 marker는 임의 삭제하지 않는다. 정상 DB reset은 expected generation CAS가 필수다. corrupt/unknown schema의 명시 복구 reset만 expected=null을 허용하며, 이 경로에서는 정상 READY DB로 판정되면 INVALID로 거부한다. reset marker가 남은 재시도는 자신이 만든 stage/old ownership metadata만 검증 후 이어가고 알 수 없는 파일은 UNAVAILABLE로 보존한다. reset 후 새 configure에도 fresh consent가 필요하며 history/remote/grant는 건드리지 않는다.

### SqliteSyncStateStore: FK와 generation 전환 분석

- `SqliteSyncStateStore`：관계는 config.active_generation→generation.id, day.generation→generation.id, attempt.generation→generation.id, dataset.(generation,date,scope_key)→day 동일 PK다. 단일 config 행은 현재 admission pointer일 뿐 day/attempt FK의 parent가 아니다. generation id는 불변이므로 active 전환에 이전 day/attempt FK를 UPDATE하거나 DELETE할 이유가 없다. active generation만 store load/loadForScope/loadDay/coverage와 cross-scope 무효화의 조회 조건으로 사용한다. 모든 mutation은 **transaction 안에서 config.active_generation=expected를 먼저 CAS 검사**한 뒤 해당 id 범위만 변경하며 archived generation row의 존재로 권한을 판단하지 않는다. delayed old callback은 old row가 존재해도 STALE다.
- `SqliteSyncStateStore`：archived=현재 config.active_generation이 아닌 generation row이며 별도 archived boolean/추가 account binding은 없다. archived day/file의 기존 revision/checkedAt/confirmedAt/priorConfirmation과 마지막 attempt는 보존하지만 현재 coverage/lastUploadAt/pending/최근 상태에 합치지 않는다. archived unfinished journal은 결과불명 과거 이력이며 process-first recovery가 재실행/자동 admission하지 않는다. 이번 PR에는 archive 전용 UI/API를 추가하지 않고 실제 DB reopen integration에서 보존·격리를 검사한다.

| 전환 | 즉시 FK를 지키는 순서 | 실패/원자성 결과 |
|---|---|---|
| `SqliteSyncStateStore` ABSENT 구성 | 새 generation insert → config insert(active_generation=new) | config는 이미 존재하는 parent만 참조한다. commit 실패는 둘 다 rollback이며 ABSENT를 READY 성공으로 꾸미지 않는다 |
| `SqliteSyncStateStore` 목적지/계정 변경 | 현재 active CAS 검사 → 새 generation insert → config pointer/binding/automatic update | 옛 generation과 모든 child는 그대로 유효하다. beforeCommit 실패는 기존 pointer/binding/history, commit 후에는 새 빈 coverage와 옛 archived history만 보인다 |
| `SqliteSyncStateStore` invalidate | 현재 active CAS 검사 → 동일 opaque metadata의 새 generation insert → config pointer 전환/automatic off | binding은 active config에만 남는다. old callback은 active CAS 실패하며 archived row를 수정하지 못한다 |
| `SqliteSyncStateStore` 명시 reset | 새 tombstone insert → config pointer 이동/binding 제거 → dataset→day→attempt 삭제 → 이전 generation 삭제 | config는 지우지 않을 tombstone을 참조한다. dataset을 day보다 먼저 지우며 이전 generation을 마지막에 지우므로 FK 위반이 없다. failure는 전체 rollback 또는 별도 corrupt-reset marker blocked다 |

- `SqliteSyncStateStore`：위 증명은 SQLite FK 관계와 transaction 순서를 대조한 **계획 단계 schema 분석**이다. SQLite 실행/Android 실제 store 통과 증거가 아니며 T4의 actual SQLite reopen/CAS/foreign_key_check 통과와 T6 OS kill 검증이 별도로 필요하다. 임의 PRAGMA foreign_keys=OFF, deferred FK, DELETE cascade로 오류를 숨기지 않는다. 새 generation 추가로 DTO/포트/schema version 번호를 추가하지 않으며 최초 미구현 version1의 SQL만 확정한다.

### SyncExecutionSession: 현재 PR의 runtime 경계

```kotlin
// SyncExecutionSession — app runtime constructor and registry, no UI/worker hookup in this PR
class SyncExecutionSession(store: SqliteSyncStateStore, reader: ScopedHealthReader,
    engine: DestinationSync, clock: java.time.Clock, newId: () -> String) {
    suspend fun detectPending(): CoverageSummary
    suspend fun run(trigger: SyncTrigger, generation: DestinationGeneration): BatchOutcome
    suspend fun invalidate(generation: DestinationGeneration): SyncCommit
    companion object {
        fun get(context: android.content.Context, factory: () -> SyncExecutionSession): SyncExecutionSession
    }
}
```

- `SyncExecutionSession`：A/SyncExecutionSession.kt에 상위 detectPending/run/invalidate signature를 두고 store+DestinationSync+Clock+ID supplier를 주입한다. applicationContext 기준 registry에서 단일 session을 얻는 `get(context, factory)`를 제공한다. Activity/worker 연결은 후속이다. `Mutex.tryLock` 실패는 generation+현재 attempt를 확인한 pendingTriggers 집합 병합으로 MERGED를 반환한다. 현재 소유자의 gate를 lease/timeout으로 탈취하지 않는다. gate 획득 후 load/recheck generation→reader.access(trigger)→loadForScope→scope 고정된 새 attempt durable start→engine 실행→finish/finally gate 반환이다. detectPending은 source health records를 읽지 않으며 automatic이면 CATCH_UP/아니면 MANUAL trigger의 access metadata와 scope query만 수행한다. registry가 보유한 process-first recovery flag를 공유하여 최초 detectPending 또는 최초 run(둘 중 먼저 호출)에서 미종결 RUNNING만 INDETERMINATE로 commit하며 checked/confirmedAt을 추정하지 않는다. 이 복구는 read/upload를 실행하지 않는다. cancellation은 durable pending/indeterminate를 best-effort로 기록하고 그대로 전파, 실패는 store unavailable로 남긴다. invalidate는 reset과 달리 같은 destination/enrollment/categories를 가진 새 generation row를 insert하고 config.active_generation을 CAS 전환하며 active binding은 보존·automatic=0으로 만든다. 새 세대 coverage/journal은 비어 있고 이전 generation/day/dataset/attempt는 archived 이력으로 그대로 남긴다. archived 세대에는 read/write/admission을 허용하지 않는다. delayed old generation callback은 STALE이며 새 상태를 건드리지 않는다. 실제 발생했을 수 있는 remote write rollback을 주장하지 않는다.

결정 로직은 상위 2절 SyncPlanner/SyncRevision/SyncState 함수에 IO 없이 둔다. orchestration은 DestinationSync/session, SQL은 app store만 맡는다. 암묵적 동작은 상위의 **HistoryDatabase pathLock** 항목을 적용한다: store lock은 전체 실행 gate가 아니다(확인: HistoryDatabase.kt/SyncViewModel.kt). Android coroutine cancellation은 commit을 원격 rollback하지 않는다. 새로운 WorkManager/SDK 암묵 동작을 도입하지 않는다.

### T5 실행 전 확정 공용 gate 계약

- `SyncExecutionSession`：get(context,factory)는 applicationContext 편의 registry이며 생성자도 store.database.databaseFile.canonicalPath별 shared entry를 반드시 사용한다. entry는 Mutex gate, live owner/phase, recovery-needed, 최대3개 trigger별 pending receipt를 공유한다. 다른 DB path는 독립이며 Activity/context 객체별 gate로 분리하지 않는다. 사용자 설정 generation은 이 memory key와 다르고 값변경이 liveowner탈취 권한이 아니다.
- `SyncExecutionSession`：run/detectPending은 gate를 먼저 획득한 owner만 복구한다. busy detectPending은 load()의 실제 unscoped coverage(선택scope값unknown/null)를 반환하고 HC/reader.access/health/remote/recovery를 실행하지 않는다. store error는 fixed 오류로 전파하고 빈coverage/완료로 만들지 않는다. gate owner는 unfinished attempt의 exact scope/date rows에서 RUNNING file만 INDETERMINATE로 바꾸며 확인/prior/checked/기존blocker·stale를 보존한다. 모든 필요한 journal 후 해당 attempt를 finish해서 새 start가 가능해야 한다. 본session start.startedAt은 항상 nonnull이다.
- `SyncExecutionSession`：처음복구 성공시 flag를 완료하지만 cleanup실패나 gate반환뒤 unresolved가 남으면 recovery-needed를 유지하여 동일process 다음 owner가 재시도한다. 복구실패는 source/remote 실행전 차단한다. liveowner가 있으면 경과시간/다른instance/최초detect 여부와 무관하게 복구하거나 gate를 탈취하지 않는다.
- `SyncExecutionSession`：busy run의 MERGED는 내구성 receipt다. start전이면 same-generation trigger별 공유 CompletableDeferred receipt(키최대3개)에 모아 owner가 start와 union을 실제 커밋한 뒤 MERGED/count0/coverage=null로 완료한다. 새attempt 생성실패·취소·stale면 대기자도 non-success로 완료하고 영원히대기시키지 않는다. owner/세대가 다른 closing 창에는 PENDING/STALE 등 non-success를 반환하며 저장되지 않은 요청에 MERGED를 붙이지 않는다. 새batch 무제한연속실행은 하지 않는다.
- `SyncState`：pendingTriggers의 현재집합을 포함하는 union만 현재 attemptId/generation 일치 시 finished attempt에도 허용한다. 이 payload검사는 finished거부보다 먼저하며 start/day/confirmation/finish의 기존finished거부는 유지한다. late request의 intent만 기록할 뿐 완료 health/file/retry metadata를 덮지 않는다. Session은 실제 liveowner의 same-gen attempt에만 이를 사용하고 병합commit실패는 MERGED가 아니다.
- `SyncExecutionSession`：취소 중 writer의 구조화된 반환과 NonCancellable journal/attempt cleanup이 끝날 때까지 gate를 보유한다. engine normalreturn이 이미 attempt를 finish했으면 중복finish하지 않는다. callback/DB failure 후 cleanup도 실패하면 결과를 성공으로 바꾸지 않고 다음복구필요를 남긴다. owner 종료 시 사전대기 receipt를 모두 definite non-success 또는 실제저장된MERGED로 정리한 뒤 gate를 반환한다. observer는 필요한 test phase enum만 internal/defaultno-op으로 주입할 수 있고 경로/계정/payload를 노출하지 않는다.
- `SyncExecutionSession`：gate owner detectPending은 config.automatic on이면 CATCH_UP/off이면 MANUAL access metadata만 조회한다. ABSENT/error는 fixed 미설정/저장소오류로 구분해전파하고 synthetic SyncState/coverage를 만들지 않는다. READY tombstone은 실제 emptycoverage를 반환할 수 있다. busy 다른profile UNAVAILABLE를 permanentstore failure로 기록하지 않는다. UI/worker 호출 연결과 WorkManager 정책은 후속plan이다.

위 T5 보정은 구현전 source/계획 대조이며 recovery1/2에 집계했다. T4의 실제SQLite/독립지적/후속process gate를 우회하지 않는다. 동시성 시험은 beforeStart/access 대기와 afterEngine/beforeGateRelease를 명시latch로 분리하고 임의sleep/timeout lease를 쓰지 않는다.



### ProcessRecoveryCases: 실제 프로세스 중단 경계

- `ProcessRecoveryCases`：기존7개 pending-before,pending-after,confirm-before,confirm-after,running,reset-before,reset-after를 유지하고 initial-schema-ready,initial-before-publish,initial-published,reset-owner-before,reset-owner-writing,reset-owner-written,reset-stage-schema-ready,reset-prepared,reset-marker-published를 추가하여 정확히16개 verifier를 실행한다. 기존 SyncStatePhase enum observer의 initial3/reset6에서 latch로 hold하며 arbitrary delay로 경계를 추정하지 않는다.
- `ProcessRecoveryCases`：initial-schema-ready/before-publish는 activeABSENT/null이며 일반read가 candidate를승격/삭제하지 않는다. 새 explicit configure는 새candidate로COMMITTED/READY·automaticoff·attempt/day/dataset/확인시각없음. initial-published는 반환전죽었어도 activeREADY·정확한합성구성/binding·automaticoff·빈metadata이며 죽인configure 반환성공은집계하지 않는다. configure(null) 재시도는STALE이고 reset(null)은INVALID다.
- `ProcessRecoveryCases`：reset6개는 deterministic UNSUPPORTED active(user_version불일치)와 privatebaseline bytes를 seed한다. owner-before/writing/written/stage-schema-ready/prepared의5개는 inactive준비중단으로 일반load가정확히UNSUPPORTED/null·원본bytes동일·active .reset부재를유지한다. owner-writing은파일open뒤본문쓰기전이며 schema-ready는schema transaction후config전이다. 새explicit reset(null)은새prepare를사용하여COMMITTED/READYtombstone, automaticoff·config/binding/attempt/day/dataset/확인시각없음, 원본.old byte동일보존이다. 이전inactiveprepare/candidate자동정리를기대하지 않는다.
- `ProcessRecoveryCases`：reset-marker-published는 .reset게시와parent sync후원본이동전이다. 일반load는UNAVAILABLE/null·원본bytes동일이며 스스로복구하지 않는다. explicit reset(null)은기존같은owner/stage를재사용해COMMITTED/READYtombstone, 원본.old-같은owner byte동일보존·active.reset부재·completed-같은owner의owner/installed증명유지를검증한다. marker를먼저삭제하여성공을만들지 않는다.
- `ProcessRecoveryCases`：reopen은변경없는read/파일기대값을먼저검증한뒤명시retry한다. phase도달marker→livePID확인→실제am force-stop→기존PID종료→다른PIDreopen, 죽인holdPASS0, 정확히16verifier+각cleanup실제성공이필수다. 공개결과는고정fixture enum/count/boolean만, rawDB/계정/URI/CSV/UUID/PIDdump업로드없음. cleanup은소유권과canonicalpath/entry종류검증후자기active/init/prepare/old/completed/sidecar만; unknownentry/symlink는광범위삭제없이실패. 이검증은고정phase process죽음이며전원손실/모든instruction경계보장이아니다.


### HotJournalSpillProbe: 정상 rollback 경로의 실제 Android 재현

- `HotJournalSpillProbe`：기존16사례를보존하고 reset-spill-before 한사례를추가하여17verifier/17cleanup·죽인holdPASS0으로확장한다. 현재작업은미수정source에서실제실패를입증하는test-only probe이며정상reopen READY를기대한다. readonly오류를기대PASS로변경/skip하지않는다.
- `HotJournalSpillProbe`：고정합성today와4,096개연속eligible과거date(enrolled=today-4096, 모든date>=1970/오늘미만), canonical요청scope와유효CONFIRMED day/최소한참조FK구조·각dataset의정확4096 UTF8byte ASCII syntheticopaquehandle을rawSQL단일seed transaction으로넣어16MiB이상을확보한다. 실제spill증명없이는크기만으로통과못한다. scope문자열/statement등상수는loop밖에서재사용하며최초전체READY/canonical/fullcoverage/row검증은유지하고중복preseedscoped검증만생략가능하다. reopen검증은그대로다. store전체load READY와rowcounts/구성/binding/최초coverage를seed전용privateowner에증명한뒤정상reset(expectedGeneration)을호출한다. metadatacap과scope/field validation을우회하는잘못된행을넣지않는다. 기존performancefixture는변경하지않고같은SQLseed패턴을참조할수있다.
- `HotJournalSpillProbe`：실제beforeCommit hook에서mainDB digest가reset직전baseline과달라졌고 -journal이regular/NOFOLLOW이며SQLite rollback journal첫8byte magic d9d505f920a163d7·충분한크기가존재함을확인한뒤hold한다. 단순nonempty/임의sleep으로spill을가정하지않는다. seed가spill을만들지못하면진단후실패로보고한다. no-newtimeout/PRAGMAcache/spill/durability변경, 충분한boundedrows로실제cache넘김을만든다.
- `HotJournalSpillProbe`：host 실제kill/newPID후 최초store.load READY/이전generation·구성/binding/rowcounts/checked·uploadcoverage를확인하여원래committed상태로SQLite rollback이가능해야한다. 이어 store.invalidate(expectedGeneration):SyncCommit으로정상mutation COMMITTED/새generation을확인한다. destination/enrollment/categories/binding동일·automaticoff이며old day는archived로남아있고새coverage는비어있음을검증한다. 원격/health IO0, 자동reset/삭제로성공을만들지않는다. probe수정전현재source에서는이expectation실패가예상되며Android실패증거확인전productionfix를공개하지않는다.


### SyncStateHotJournalRecovery: 검증된 rollback 예외

```kotlin
// SyncStateHotJournalRecovery — new app-private helper; no core/public port or schema change
internal class SyncStateHotJournalRecovery(private val database: SyncStateDatabase) {
    fun recover(validateContent: (android.database.sqlite.SQLiteDatabase) -> Unit,
        checkCancelled: () -> Unit)
    companion object {
        fun guardSidecarPaths(databaseFile: java.io.File)
    }
}
// SyncStateDatabase — additive methods, existing constructor/lock/schema/reset remain
internal fun openAdmittedRead(validateContent: (android.database.sqlite.SQLiteDatabase) -> Unit,
    checkCancelled: () -> Unit): android.database.sqlite.SQLiteDatabase?
internal fun openExistingForRollback(): android.database.sqlite.SQLiteDatabase
// SyncStatePhase — retain all existing values; add four payload-free fault phases
internal enum class SyncStatePhase {
    INITIAL_SCHEMA_READY, INITIAL_BEFORE_PUBLISH, INITIAL_PUBLISHED,
    RESET_OWNER_BEFORE, RESET_OWNER_WRITING, RESET_OWNER_WRITTEN,
    RESET_STAGE_SCHEMA_READY, RESET_PREPARED, RESET_MARKER_PUBLISHED,
    HOT_RAW_SAVED, HOT_CANARY_VALIDATED, HOT_BEFORE_ORIGINAL, HOT_ORIGINAL_ROLLED_BACK
}
```

- `SyncStateHotJournalRecovery`：recover는canonical pathLock을소유한호출에서만동작하며IO worker를새로만들지않는다. 평상시readonlyopen성공은scratch/RW없이그대로반환한다. openAdmittedRead는실제SQLiteReadOnlyDatabaseException에서만helper를호출하며message/추정code776으로분기하지않는다. 일반BUSY/IO/CORRUPT/UNSUPPORTED/ABSENT·resetmarker는복구승격대상이아니다. reset allowMarker=true(stage/installed)에는이일반fallback을적용하지않는다.
- `SyncStateHotJournalRecovery`：guardSidecarPaths는첫nativereadonlyopen전에도정확한DB/-journal/-wal/-shm의existingentry종류/NOFOLLOW를확인한다. symlink/directory/special/hardlink(nlink!=1)는거부한다. regular-journal의EOF8byte가magic d9d505f920a163d7이면지원하지않는master/super참조후보로nativeopen전거부한다. helper대상은WAL/SHM부재·표준SQLite header(format1/read/writeversion1)·journal첫magic과page/sector크기·충분한record길이가입증된단일DELETE pair뿐이다. header/EOF shortread·IO·파일변화·모호함은failclosed,검증불가를불일치/정상으로해석하지않는다. footerchecksum/길이를완화하여외부path를허용하지않는다.
- `SyncStateHotJournalRecovery`：sourceFD는Os.open O_NOFOLLOW/fstat로regular·nlink1·privateparent내부를확인하고64KiB등boundedbuffer로copy/hash한다. device/inode/size/전체SHA256를처음과원본RW직전에비교하며sourceDB/journal/sidecar/marker집합도동일해야한다. JavaSQLite pathopen의NOFOLLOW보장을꾸며내지않고same-app privateparent+singleprocess pathLock과직전identity재검사가전제다.
- `SyncStateHotJournalRecovery`：원본raw DB/journal을noBackup parent의독립prepareUUIDdir에보관·file/dir fsync·프로토콜증명후동일pair fingerprint의complete dir로atomicpublish한다. 예: basename.hot-journal.prepare-UUID → basename.hot-journal-SHA256. complete dir는manifest와raw/state.db,raw/state.db-journal만허용한다. manifest는 protocol=sync-hot-journal/1·ownerUUID·두digest·두size만canonical고정형식으로저장(최대512bytes 자체writer상한),raw를SQLite로열지않는다. 동일complete pair는모든owner/layout/hash를재검증해재사용하여동일실패마다raw중복증가를막는다. 기존manifest도NOFOLLOW FD/regular/nlink·512byte사전상한·정확필드/순서/정규형/re-encode byte동일·owner/dir fingerprint/실제raw size/hash일치를검증하며초과/unknown/모호한입력은원본RW없이거부한다. incompleteprepare는active로채택하지않고unknown/충돌/symlink를읽거나삭제하지않는다.
- `SyncStateHotJournalRecovery`：완전raw에서별도workUUIDdir의state.db/state.db-journal을만들어기존파일전용RW로native rollback한다. source와work는동일flags/NO_LOCALIZED_COLLATORS/no-delete handler/DELETE·FULL·foreignkeys조건이다. recovery opener는CREATE/upgrade/reset/schema생성없음; strict rollback-header/read-writeversion1·WAL/SHM부재·외부footer거부후에만candidate/original에동일하게명시DELETE/FULL을선택하고open후DELETE/FULL을검사한다. Android15/16은journalmode생략시SQLiteGlobal property/resource 기본TRUNCATE를택하므로생략을preserve-mode로오해하지않는다. original에대한선택은candidate전체검증·원본불변뒤이며unknownWAL을DELETE로변환하지않는다. schema/version/FK/quick_check와Store callback전체검증성공후닫고원본불변재검사한다. 그다음만original existing-only native rollback→동일전체검증→닫기→원래readonlyadmission재진입이다. 앱이journal삭제/편집/replay/원본자동restore를하지않는다.
- `SyncStateHotJournalRecovery`：validateContent는Store의같은validateActive(고정KST date)+capacity검사를사용하여binding정규형·정확attempt·비선택profile포함모든active day/dataset를검사한다. schema만정상인malformedmetadata는통과못한다. scratch Admission/핸들을original READY/CAS proof로재사용하지않는다. load/loadForScope/loadDay/loadCurrentAttempt/readBinding와write pre-admission이공통openAdmittedRead를사용한다. 원래메서드의stale/null/error계약및정상narrowheader의coverage비주장은유지한다.
- `SyncStateHotJournalRecovery`：checkCancelled는source/native전및copychunk/검증사이에서호출하고원본RW전취소면원본bytes불변이다. native rollback중에는실제동기호출/close가끝날때까지pathLock을보유하고그뒤취소를전파한다. 실패/취소/디스크부족때raw와원본을보존하고성공/빈state/reset으로바꾸지않는다. work는자기owner/entry모두NOFOLLOW입증후만정리하며unknown entry가있으면어느것도선삭제하지않는다. raw는성공후에도보존하고retention정책/UI를새로추가하지않는다. 단계enum seam은defaultnoop이며경로/계정/내용을노출하지않는다.

### HotJournalRecoveryChecks: 요구 기반 검증

- `HotJournalRecoveryChecks`：기존SQLite32·Session9·성능72/API·host17의기대/단언을유지한다. auxiliary Native fault fixture는한정된작은syntheticDB에서test-only 작은cache를사용해확실한dirty pair를복제할수있으나production설정/실제20kOSprobe조건은절대바꾸지않는다. livewriter를beforeCommit hook에정지해DB/journal을함께복사하고원래writer는throw로정리한다. 이복제는실제OSkill의대체증거가아니며faultpartition실행용이다. schema/metadata유효성과실제header/main변화를증명한다.
- `HotJournalRecoveryChecks`：정상pair의최초load/scopedload/day/header/binding/write(invalidate) 각entry가복원된committed metadata를반환하고raw원본쌍보존·정상mutation/이전archive격리·동일pair재시도rawdedupe를확인한다. 건강/remote IO0이며새confirmation시각을꾸미지않는다. 정상no-journal fastpath는helper artifact/RW 없음이다.
- `HotJournalRecoveryChecks`：지원하지않는user_version/extra schema와지원schema의malformedbinding/비선택scopeleaf/attempt관계인dirty pair는work검증실패·original DB/journal byte동일·originalRW0이다. regularfooter참조후보·WAL/SHM·symlink/hardlink/unknownentry·원본hash변화도원본RW0/외부sentinel불변/모든unknown파일보존이다. footer실패를정상으로우회하지않는다.
- `HotJournalRecoveryChecks`：HOT_RAW_SAVED/HOT_CANARY_VALIDATED/HOT_BEFORE_ORIGINAL에서CE/failure를주입해원본불변·raw보존·다음동일process/새Store재시도가가능함을검증하고,HOT_ORIGINAL_ROLLED_BACK후주입은확인성공을꾸미지않으면서다음readonlyload에복원값이있음을확인한다. rawcopy완료전부분실패도inactive준비물재채택없이재시도가능해야한다. 공개artifact에raw/filemanifest/account/URI/UUID/예외원문을추가하지않는다. helperbackup이추가된실제hostcleanup은정확한protocol/owner/layout/전체entry검증후만지운다.

## 3. 작업

### T1: 등록 이후 bounded 날짜 계획

- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `android/core/src/main/kotlin/com/ny/my/health/core/SyncState.kt` | 신규 | 2절 metadata DTO와 순수 검증 |
  | `android/core/src/main/kotlin/com/ny/my/health/core/SyncPlanner.kt` | 신규 | bounded 날짜 선택·coverage 계산 |
  | `android/core/src/test/kotlin/com/ny/my/health/core/SyncStateTest.kt` | 신규 | 초기상태·범위·불가능한 전이 거부 |
  | `android/core/src/test/kotlin/com/ny/my/health/core/SyncPlannerTest.kt` | 신규 | 등록/날짜/floor/최근/연속성 |

- **인터페이스**: 2절 `SyncState` 참조. 2절 `SyncPlanner` 참조. metadata 타입 전체와 scope 불변식을 함께 수집한다.
- **결정 로직**:
  ```
  구성 없음 → 빈 계획/자동 off
  어제보다 뒤 또는 등록 전 → 제외
  today-30 미만/가용성 불명 → blocked gap
  eligible recent2 예약 → oldest eligible gap으로 7개 채움 → 고유 오름차순
  현재 profile 완료일 → oldest gaps에서 제외; EMPTY는 checked 진행만 허용
  full-five EXPLICIT와 현재 scope through는 따로 계산
  ```
- **작성할 테스트**:
  - "Enrollment at KST midnight excludes old history today future and pre-enrollment days" — 신규; 아래 fixture 조건으로 검증.
  - "Recent two days reserve slots before oldest eligible gaps and retain blocked pending days" — 신규; 아래 fixture 조건으로 검증.
  - "Empty and partial grants never claim five categories upload success or continuous uploaded coverage" — 신규; 아래 fixture 조건으로 검증.
  - "Dates below the conservative history floor and unknown access remain blocked instead of empty" — 신규; 아래 fixture 조건으로 검증.
  - 케이스·재사용 근거: 신규 — 1절 Enrollment, Recent two days, Empty and partial grants, Dates below... 이름 그대로. KST 23:59:59/00:00, 등록 당일/단일일, floor-1/floor/floor+1, 0/1/7/8 gaps, recent와 oldest 중복, 중간 EMPTY/미확인 gap 후 성공, SUMMARY/WORKOUT_SCOPED/BLOCKED를 대표 fixture로 검증한다. 14일 이상 partial/automatic profile에서 2회 이상 batch 실행 시 oldest 미확인 날짜가 앞으로 진행하고, full-five through=null이어도 현재 scope 완료일은 후보를 차지하지 않음을 검증한다. EXPLICIT_DAY→SUMMARY/WORKOUT→EXPLICIT_DAY 순서에서도 첫 scope row/revision/checked 확인을 잃지 않는다. scope 동등분할(②), invalid metadata(③), 연속성 순서(④). 기존 NormalizationTest/CsvExporterTest의 day boundary·null·정렬 회귀는 재사용한다(⑤).
- **완료 기준**: 배정 유닛 통과, foundation history import 없음, 행 없는 날짜도 pending으로 계산.
- **검증 방법**:
  ```bash
  cd android
  ./gradlew -PcoreOnly :core:test --tests '*SyncStateTest' --tests '*SyncPlannerTest'
  ```
- **주의사항**: T1/T2 독립 실행을 허용하며 기존 포트 변경은 T3 선행 gate까지 금지한다. pathLock이 sync gate가 아니라는 상위 확인을 적용한다. 실제 SDK/계정 데이터를 fixture에 넣지 않는다. 불명확 scope를 EXPLICIT_DAY로 추정하지 않는다. 인터페이스 충돌/추정 불일치면 blocked로 보고한다.

### T2: 재읽기 revision과 scope별 확인

- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `android/core/src/main/kotlin/com/ny/my/health/core/SyncRevision.kt` | 신규 | canonical revision·안정 generatedAt |
  | `android/core/src/main/kotlin/com/ny/my/health/core/SyncStateCodec.kt` | 신규 | strict categories/scopes/dates/triggers/confirmation leaf codec |
  | `android/core/src/main/kotlin/com/ny/my/health/core/SyncState.kt` | 수정 | 공개 isValid overloads와 validateMutation 분류 |
  | `android/core/src/test/kotlin/com/ny/my/health/core/SyncRevisionTest.kt` | 신규 | 시간 독립 revision·scope narrowing |
  | `android/core/src/test/kotlin/com/ny/my/health/core/SyncStateTest.kt` | 수정 | codec/metadata 오류·prior 확인 보존 |

- **인터페이스**: 2절 `SyncState` 참조. 2절 `SyncRevision` 참조. 2절 `SyncStateCodec` 참조. 2절 `SyncStateTransitions` 참조.
- **결정 로직**:
  ```
  canonical 같음 → 이전 generatedAt/digest 유지
  content/schema/category/scope 변경 → 새 digest와 observedAt
  완전한 동일 범위에서 populated→empty → REMOTE_STALE
  범위 축소/BLOCKED → 이전 target 보존, 삭제 추론 금지
  unknown field/version/oversize → fail closed
  ```
- **작성할 테스트**:
  - "Source reread reuses canonical revision despite generated time and advances changed snapshots" — 신규; 아래 fixture 조건으로 검증.
  - "Disappearing populated datasets retain remote stale and prevent full current without deletion" — 신규; 아래 fixture 조건으로 검증.
  - 케이스·재사용 근거: 신규 — 1절 Source reread..., Disappearing populated...; generatedAt만 다름/순서만 다름/실제 content변경, scope변경(새 profile와 기존 profile 공존), 완전 empty와 grant축소의 차이, CSV actual byte digest와 semantic digest 구별을 검증한다(①②④). codec unknown key/enum/version, 중복key 및 canonical과 다른 공백/key순서/escape 입력 거부, canonical roundtrip·최대4096 UTF-8 byte handle 허용과16KiB 초과 입력의 파싱 전 거부(16KiB 정확히인 유효 leaf를 억지로 만들지 않음), invalid phase/confirmation 조합은 SyncStateTest의 관찰 가능한 leaf decode/encode 및 mutation 검증 거부 fixture로 검증한다(③). generation/attempt/scope/revision stale, journal 없는 확인 거부, 동일 확인 멱등, finished callback 거부, prior 확인 보존은 기존 SyncStateTest fixture에 추가한다. bounded snapshot 밖 currentDay를 명시 전달하여 검증 가능함과 전역 coverage를 덮지 않음을 확인한다. 기존 합성 fixture의 임의 generation 문자열은 canonical 합성 UUID로 갱신할 수 있으나 T1 동작 단언은 유지한다. 기존 exporter 정렬·escaping·weight tie regression 재사용(⑤).
- **완료 기준**: 재생성 bytes 안정, raw/CSV/whole-row JSON 저장 필드 없음, 공개 leaf API와 transition 분류의 유닛 검증 통과; T1+T2 통합 체크포인트로 core 전체 회귀 통과.
- **검증 방법**:
  ```bash
  cd android
  ./gradlew -PcoreOnly :core:test
  ```
- **주의사항**: JSON leaf helper와 검증 helper의 private 분해는 구현 재량이며 별도 질문하지 않는다. COMMITTED 검증 결과를 durable SyncCommit/원격 성공으로 반환하지 않는다.  T1 후 시작. generated_at 문자열 치환/원본 list hash 금지. 코덱 helper마다 새 테스트를 만들지 않고 invalid input이 확인 상태에 진입하지 못하는 대표 사례를 검증한다. 불명확 계약이면 blocked.

### T3: pending 기반 destination 실행

- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `android/core/src/main/kotlin/com/ny/my/health/core/Ports.kt` | 수정 | additive store/scoped-reader/destination 포트 |
  | `android/core/src/main/kotlin/com/ny/my/health/core/DestinationSync.kt` | 신규 | source→pending→reconcile→개별확인 |
  | `android/core/src/main/kotlin/com/ny/my/health/core/SyncState.kt` | 수정 | mutation 유효성·attempt/budget metadata |
  | `android/core/src/main/kotlin/com/ny/my/health/core/SyncPlanner.kt` | 수정 | 영구 목적지 blocker와 transient 후보 분리 |
  | `android/core/src/test/kotlin/com/ny/my/health/core/DestinationSyncTest.kt` | 신규 | 합성 reader/store/destination 실패 경로 |

- **인터페이스**: 2절 `SyncState` 참조. 2절 `SyncPlanner` 참조. 2절 `SyncRevision` 참조. 2절 `SyncStateStore` 참조. 2절 `StorageDestination` 참조. 2절 `ScopedHealthReader` 참조. 2절 `DestinationSync` 참조. 2절 `DestinationException` 참조. DriveUploader의 upsert/upsertBatch는 그대로 보존한다. upsertTracked default 추가는 manual-drive 소유다.
- **결정 로직**: T1/T2 판정 재사용과 2절 `DestinationException`의 고정 reason 분류. provider 오류 문자열은 판정에 사용하지 않는다.
- **호출 순서**:
  ```
  1. access/loadForScope/plan → 고정 attempt scope와 다르면 중단; read 전 generation 재검사
  2. selected date loadDay(scopeKey)/readScoped/identify → read 실패는 EMPTY로 변환하지 않음
  3. 현재 attempt/scope/day/revision pending journal commit → 실패하면 remote IO 금지; 이전 확인은 priorConfirmation으로 보존
  4. reconcile → 현재 검증된 확인만 callback commit, 불명확 target은 blocked
  5. pending만 RUNNING commit하며 같은 target의 다른 scope current를 INDETERMINATE로 보존 → writePending/파일별 matching commit
  6. day/attempt 결과 commit → 실패 시 성공으로 반환하지 않음
  ```
- **작성할 테스트**:
  - "After partial failure cancellation or unknown create reconciliation retries only pending files" — 신규; 아래 fixture 조건으로 검증.
  - 케이스·재사용 근거: 신규 — 1절 After partial failure...; Source reread...의 read 후 revision 확인 순서; 합성 4파일 중2확인 후 실패, callback commit실패, 다른 scope 쓰기 전 기존 scope 파일 INDETERMINATE+prior 확인 보존, cancel 전/중/후, unknown create→reconcile 기존 target 채택, 건강한 동일 revision 무쓰기, stale generation/revision/attempt callback거부, narrowed scope 보존. 실패/cancel 시 prior 성공 보존과 다음 실행의 source 재읽기를 결과로 단언한다(①③④). 합성 store는 실제 SQLite 검증이 아님을 구분한다. known transient와 unknown 예외 구별, 첫 조회 실패시 write0,2개 확인 후 transient·불명확 create·계정 block의 확인/prior 보존, 영구 사유 planner 제외, cancellation transient 아님, failure journal commit실패시 RETRYABLE 금지, reason/phase validation을 같은 테스트에서 검증한다(①③④).
- **완료 기준**: 모든 remote write보다 journal이 앞서며 파일별 commit만 성공 count에 포함; 기존 P0 coordinator/ports callsites 컴파일·회귀 유지.
- **검증 방법**:
  ```bash
  cd android
  ./gradlew -PcoreOnly :core:test --tests '*DestinationSyncTest' --tests '*SyncCoordinatorTest' --tests '*PrivateDrive*'
  ```
- **주의사항**: T2 후 시작. 현재 파일의 확정 metadata를 확인하지 않은 과거 SUCCESS만으로 skip하지 않는다. 실제 Drive private/root/checksum 검증은 후속이며 합성 목적지 결과를 실제 계정 증거로 쓰지 않는다. cancellation을 일반 실패로 삼키지 않는다.

### T4: 실제 SQLite의 원자적 상태 저장

- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `android/app/src/main/java/com/ny/my/health/SyncStateDatabase.kt` | 신규 | 별도 DB schema/open/transaction/reset marker |
  | `android/app/src/main/java/com/ny/my/health/SqliteSyncStateStore.kt` | 신규 | bounded queries/CAS/private binding/명시 reset |
  | `android/app/src/androidTest/java/com/ny/my/health/SqliteSyncStateStoreInstrumentedTest.kt` | 신규 | 실제 DB reopen·손상·저장 실패 |
  | `android/core/src/main/kotlin/com/ny/my/health/core/SyncStateCodec.kt` | 수정 | SQL row decode validation 연결 |

- **인터페이스**: 2절 `SyncState` 참조. 2절 `SyncPlanner` 참조. 2절 `SyncStateStore` 참조. 2절 `SyncStateCodec` 참조. 2절 `SyncStateTransitions` 참조. 2절 `SqliteSyncStateStore`의 schema/configure/readBinding. app 내부 invalidate(expected)도 같은 transaction으로 구현한다.
- **결정 로직**: 없음(순수 상태 판정 T1/T2 재사용).
- **호출 순서**:
  ```
  1. marker/path/schema 검사 → 실패는 상태 분류 후 원본 보존
  2. transaction 내부 active CAS와 exact currentDay query→validateMutation(COMMITTED 후보)→SQL leaf/scalar 변경 → 실패 rollback
  3. endTransaction 성공 → 새 connection reopen 검증
  4. 명시 reset만 stage/marker 교체 경계 진입 → 실패 marker 유지
  ```
- **작성할 테스트**:
  - "Reopening around atomic commits never confirms an unacknowledged file or date" — 신규; 아래 fixture 조건으로 검증.
  - 케이스·재사용 근거: 신규 — 1절 Reopening around atomic commits... 및 Enrollment/Recent의 SQLite 부분. absent read 무생성, reopen config/일별·파일별 coherence, beforeCommit 예외/cancel→이전 상태, confirmation 없는 pending→성공불가, unknown schema/수정 schema/trigger/손상/경로 directory/marker→distinct blocked, 재열기 private binding 보존·core에서 식별자 미노출, 자동off reset/reopen, 두 generation에 scope day/file/attempt를 넣은 뒤 실제 configure/invalidate 전환→old rows 보존·새 active coverage 비어 있음·old binding이 archive에 없음·old callback STALE, beforeCommit 실패→old active pointer+binding+rows 그대로, 명시 reset→두 세대 metadata 모두 제거+새 tombstone만+foundation history 보존, 각 reopen의 PRAGMA foreign_key_check 결과0행, scope 필터 전 LIMIT 금지와 partial/automatic 2배치 이상 진행·수동/자동 scope rows 공존·공유target 무효화의 재열기, generation CAS경쟁 한쪽만 commit, old attempt/revision callback거부, failed reset old state/marker 보존, settings+coverage 혼합 commit불가(①③④). synthetic binding만 사용한다.
- **완료 기준**: 실제 SQLite 통과, FULL/DELETE 확인, noBackup 경로 확인, history 불변; T3~T4 체크포인트로 core+앱 JVM/APK/lint와 실제 store suite 통과.
- **검증 방법**:
  ```bash
  cd android
  ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
  ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.ny.my.health.SqliteSyncStateStoreInstrumentedTest
  ```
- **주의사항**: T3 후 시작. 기존 history fixture 구조를 재사용하되 파일명 충돌 금지. 새 DB/schema에 destructive onUpgrade/automatic reset 금지. emulator 부재면 CI 실제 class 결과가 필요하며 JVM mock으로 대체하지 않는다. readConnection close와 schema 검사 근거는 실제 HistoryDatabase 패턴; 불일치면 blocked.

### T5: process 공용 admission과 중단 복구

- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `android/app/src/main/java/com/ny/my/health/SyncExecutionSession.kt` | 신규 | 단일 registry/gate·pending merge·restart recovery |
  | `android/app/src/androidTest/java/com/ny/my/health/SyncExecutionSessionInstrumentedTest.kt` | 신규 | 실제 store+합성 engine 동시/cancel 검증 |
  | `android/core/src/main/kotlin/com/ny/my/health/core/SyncState.kt` | 수정 | generation/attempt recovery와 finished pending union 검증 |
  | `android/core/src/test/kotlin/com/ny/my/health/core/SyncStateTest.kt` | 수정 | finished pending union 외 callback 거부 유지 |

- **인터페이스**: 2절 `SyncState` 참조. 2절 `SyncStateStore` 참조. 2절 `SyncExecutionSession` constructor/registry. engine는 ScopedHealthReader/StorageDestination 합성 구현으로 실행한다. start attempt는 session만 소유하며 engine는 시작된 attemptId를 검증한다. T3 독립 테스트는 start mutation을 먼저 commit해 fixture를 만든다.
- **결정 로직**: SyncState.kt에서 미종결 journal→INDETERMINATE, 이전 확정 보존, stale generation/attempt→거부를 판정한다.
- **호출 순서**:
  ```
  1. canonical-path 공용 gate를 먼저 시도; busy detect는 unscoped summary만 읽고 recovery 금지
  2. gate 소유자만 owner없는 unfinished attempt의 exact journal 복구→attempt 종료; 실패시 새 admission 차단
  3. access(trigger)/loadForScope→scope 고정 start와 pre-start pending union durable commit; 그 뒤에만 대기자MERGED
  4. busy run은 same-gen 현재 attempt에 pending union 저장 성공시에만 MERGED; finished union만 허용
  5. engine 실행→이미 finished면 중복finish 금지; 취소/실패cleanup까지 gate 유지 후 반환
  6. invalidate transaction→새generation/자동off; old result CAS거부
  ```
- **작성할 테스트**:
  - "Concurrent manual periodic and catch up requests admit once and reject old generation callbacks after restart" — 신규; 아래 fixture 조건으로 검증.
  - 케이스·재사용 근거: 신규 — 1절 Concurrent manual periodic...의 이번 PR 부분. CountDownLatch로 세 trigger 동시 진입→reader/destination 실행 하나·pendingTriggers 합집합, 살아있는 owner는 시간 경과로 탈취불가, cancel 중 writer 종료 전 gate 재입장불가, store 실패 시 admission차단, restart unresolved→INDETERMINATE(확인 시각 없음), invalidate 후 archived 행이 남아 있어도 늦은 callback/finish 거부와 새 상태보존(①③④). 직접 생성한 두 session의 same-path gate공유/다른path 독립, 최초detect와run의 경합, start전 대기/finish후 release전 요청의 durable pending union, cleanup저장실패 후 같은process에서 recovery재시도, autooff/on 감지의 trigger 및 health/remote호출0도 추가한다. finished pending union만허용하고 confirmation/finish/day callback은 계속거부하는 core 회귀를 보강한다. WorkManager 실제 integration은 후속이다.
- **완료 기준**: 실제 store를 사용한 admission/cancel/recovery suite 통과; UI lifecycle과 독립이며 현재 coordinator/UI 연결 변경 없음.
- **검증 방법**:
  ```bash
  cd android
  ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.ny.my.health.SyncExecutionSessionInstrumentedTest
  ```
- **주의사항**: T4 후 시작. HistoryDatabase pathLock은 store 호출 보호이며 전체 sync gate가 아니라는 상위 확인을 그대로 적용한다. timeout 소유권 탈취 금지. 임의 delay 대신 latch와 bounded timeout을 써 재현한다. 취소 commit best-effort 실패를 성공으로 쓰지 않는다.

### T6: OS 강제종료의 실제 commit 검증

- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `android/app/src/androidTest/java/com/ny/my/health/SyncStateProcessRecoveryInstrumentedTest.kt` | 신규 | 합성 seed/hold/reopen/cleanup |
  | `android/ci/verify-sync-state-process-recovery.sh` | 신규 | host force-stop 경계와 새 PID 검증 |
  | `.github/workflows/android-emulator-tests.yml` | 수정 | normal discovery 제외·별도 harness·합성 결과 |

- **인터페이스**: 2절 `SyncState` 참조. 2절 `SqliteSyncStateStore` schema/reset 계약. 2절 `SyncExecutionSession` 참조: running verifier는 read-first RUNNING 원본을 관찰한 뒤 새process session.detectPending으로 owner없는시도를복구하여 INDETERMINATE/finish와health·remote IO0을 확인한다. host flag `syncStateProcessHost=true`, markers `SYNC_STATE_READY pid=...`, `SYNC_STATE_REOPENED pid=...`, 2절 `ProcessRecoveryCases`의 16개 fixture allowlist. 별도 sync-state-process-fixture.db와 ownership marker만 사용한다.
- **결정 로직**: 없음.
- **호출 순서**:
  ```
  1. 합성 fixture 소유권 검증 후 seed/hold 준비 marker → 실패 즉시 종료
  2. host가 live target PID 확인 후 am force-stop → 죽은 hold는 PASS 집계 제외
  3. 새 instrumentation process reopen → 다른 PID와 commit 경계 상태 검증
  4. 자기 소유 fixture만 cleanup → 실패도 suite 실패
  ```
- **작성할 테스트**:
  - "Reopening around atomic commits never confirms an unacknowledged file or date" — 신규; 아래 fixture 조건으로 검증.
  - 케이스·재사용 근거: 신규 — 1절 Reopening around atomic commits...의 host process 부분. pending-before는 이전 상태, pending-after는 journal만, confirm-before는 미확정, confirm-after는 해당 파일만 확정, running은 INDETERMINATE·reconcile 전 성공불가, reset-before는 archived/current를 포함한 이전 상태/reset-after는 archived/current metadata 없는 자동off 새 tombstone generation. 실제 process 사망을 증명하며 단순 store 객체 재생성과 구분한다(①③④). 기존 history process harness/일반 UI 회귀는 수정 없이 재사용(⑤). 2절 `ProcessRecoveryCases`의 추가 initial3/reset6도 같은 실제 OS force-stop/read-first/retry 검증을 수행한다.
- **완료 기준**: normal suite에 hold class가 들어가지 않고 skip 없이 완료; API35/36의 별도 harness16개 verifier 성공·죽인 hold 성공집계0. T5~T6 통합 체크포인트로 아래 PR 검증 완료.
- **검증 방법**:
  ```bash
  bash -n android/ci/verify-sync-state-process-recovery.sh
  bash android/ci/verify-sync-state-process-recovery.sh
  ```
- **주의사항**: T5/T8 source 통합 compile 체크포인트 확인 후 시작하며 T5/T8 actual Native/성능 gate는 최종 통합 CI에서 함께 충족해야 한다. 기존 host shell의 bounded install/poll/PID/reap 구조를 재사용하되 system settings 전환은 추가하지 않는다. API flags 미확인은 read-only 확인 후 지시 범위만 구현하며 기동/컴파일/검증 실패시 blocked로 보고한다. 일반 discovery 제외는 ignore/Assume skip으로 대체하지 않는다. 공개 evidence는 fixture enum·count·boolean만, DB/CSV/계정/URI/원본 instrumentation dump 업로드 금지.

### T7: 누적 메타데이터 비용 측정

- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `android/app/src/androidTest/java/com/ny/my/health/SyncStateStorePerformanceInstrumentedTest.kt` | 신규 | 실제 SQLite synthetic metadata 반복 측정 |
  | `.github/workflows/android-emulator-tests.yml` | 수정 | 안전한 합성 숫자JSON 산출물 수집 경로 추가 |

- **인터페이스**: 2절 `SqliteSyncStateStore` 참조. 2절 `SyncState` 참조. 2절 `SyncStateCodec` 참조. 제품 API 변경은 없다. 기존 AndroidX PlatformTestStorageRegistry.openOutputFile과 AndroidJUnit4 계측 기반을 재사용한다.
- **결정 로직**: timing threshold/SLA 없이 올바른 결과와 숫자 산출물 존재를 확인한다. 부정확한 CPU/heap 추정치를 만들지 않는다.
- **호출 순서**:
  ```
  1. N=30/365/3650별 FULL_EXPLICIT(5종)/PARTIAL_SUMMARY(STEPS) profile의 독립 synthetic DB6개를 만든다.
  2. 최초 등록은 당시 어제로 configure하고 injected today를 고정 측정일로 이동; 실제 SQLite transaction으로 N개 day/DAILY confirmation metadata를 정상 canonical 형식으로 seed한다.
  3. 각 시나리오 load()/loadForScope() 및 matching confirmation transact를 각각 최초1+반복3회 측정한다.
  4. confirmation 준비는 측정 밖에서 동일revision INDETERMINATE+prior journal로 만들고 실제 ack commit만 측정하며 결과를 확인한다.
  5. monotonic elapsedRealtimeNanos와 Process.getElapsedCpuTime(ms)의 실제 delta를 safe JSON으로 기록하고 자기fixture만 정리한다.
  ```
- **작성할 테스트**:
  - "Accumulated metadata measurements report load scoped query and confirmation costs without weakening integrity" — 신규. 실제 함수는 같은 의미의 lowerCamelCase를 허용한다.
  - 케이스·재사용 근거: 기타(별도 성능검토 P2가 확인한 N+1/full validation 반복)·실제 Android SQLite 계측. 6개 시나리오×3동작×4sample=72개 관측, 모든 commit/read 결과와 null/partial full-coverage 구별을 검증한다. 실제 데이터를 쓰지 않고 SDK-free 숫자를 Android latency로 바꾸지 않는다. 기존 기능·손상·CAS/취소 테스트는 재사용·유지한다.
- **완료 기준**: 실제양API에서 test PASS/skip0과 JSON72개 표본을 root가 내려받아 검사. source baseline의 metadata 비용만 측정하며 임의최적화/새SLA/기기배터리·Google latency 주장은 없음. 심각한 병목이면 그 실측 근거로 별도 제한 교정 task를 계획한다.
- **검증 방법**:
  ```bash
  cd android
  ./gradlew :app:compileDebugAndroidTestKotlin
  ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.ny.my.health.SyncStateStorePerformanceInstrumentedTest
  ```
- **주의사항**: T4 source checkpoint c2b49a2에서 공개된 store API를 진단하는 독립 작업이며 T4완료를 가정하지 않는다. 첫 native실행은 T4회귀 probe와 함께 실행할 수 있고 전체실패와 개별측정PASS를 구분한다. T4가 같은 app compile tree를 편집하므로 source 준비까지만 병렬하고 Gradle은 root단일slot 허가 후 실행한다. test storage는 기존 HealthHomeScreenInstrumentedTest의 PlatformTestStorageRegistry를 그대로 사용해 v1-synthetic-store-performance/metrics.json을 쓴다. workflow artifact에 outputs/**/v1-synthetic-store-performance/*.json 경로만 추가하고 실행/assertion/timeout/skip/이미지설정을 바꾸지 않는다. JSON은 profile enum, N, operation, sample index, elapsedNs, processCpuMs만 허용하며 날짜/계정/URI/UUID/handle/raw/CSV/token/DB dump를 내보내지 않는다. 첫 측정을 cold filesystem이라고 부르지 않는다(첫 reopen/반복 reopen); GC/할당량은 기반 없으면 생략한다.

### T8: 측정된 metadata 반복 조회 축소

- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `android/app/src/main/java/com/ny/my/health/SqliteSyncStateStore.kt` | 수정 | 모든active row streaming bulk 검증/집계·narrow attempt 읽기 |
  | `android/core/src/main/kotlin/com/ny/my/health/core/Ports.kt` | 수정 | 기존 store의 호환 default narrow attempt 포트 |
  | `android/core/src/main/kotlin/com/ny/my/health/core/DestinationSync.kt` | 수정 | 반복 current identity 확인에 narrow 포트 사용 |
  | `android/core/src/test/kotlin/com/ny/my/health/core/DestinationSyncTest.kt` | 수정 | 파일수와 무관한 전체coverage 읽기·stale guard 회귀 |
  | `android/app/src/androidTest/java/com/ny/my/health/SqliteSyncStateStoreInstrumentedTest.kt` | 수정 | 비선택scope손상/pointheader/집계 보존 |

- **인터페이스**: 2절 `SyncState` 참조. 2절 `SyncStateStore` 참조. 2절 `SqliteSyncStateStore` 참조. 추가 포트는 아래 공통 설계의 `CurrentAttemptRead` 참조. 기존 기본구현은 load 기반으로 호환되며 production은 narrow proof만 반환한다.
- **결정 로직**: source/generation/attempt/scope/date/revision·privatebinding·손상·capacity·FULL/DELETE 검증을 보존하고 SQL형태/같은lockedoperation의 중복처리만 줄인다. integrity를 across-call cache로 생략하지 않는다.
- **호출 순서**:
  ```
  1. actual metadata/binding/attempt와 all-active day+dataset를 bounded cursor LEFT JOIN 등으로 streaming 검증한다.
  2. 동일 pass의 full/selected scope 집계·last current/prior 확인·최대9 후보를 만들고 비선택scope도 검증한다.
  3. transaction preflight와 mutation은 같은 path lock의 검사결과만 재사용; exactcurrentDay는 transaction에서 읽는다.
  4. engine 최초 load는 전체READY 검증, 반복current는 expected generation/attempt identity만 확인한다.
  5. 모든원격쓰기 전 journal transact의 전체integrity와 CAS는 그대로 유지한다.
  ```
- **작성할 테스트**:
  - "Current attempt checks reject changed identities without repeating full coverage reads for every file" — 신규/기존 fake 경계에 추가, source읽기/목적지IO 전에 stale 거부와 파일수1/4의 expensive load수 비례증가없음.
  - "Bulk snapshots reject corrupt unselected profiles and preserve scoped coverage and prior timestamps" — 실제SQLite 회귀. nonselected row corrupt가 healthy selected view/ordinarywrite에 숨겨지지 않음, row없는EMPTY·부분/full·여러dataset/공유target·capacity·binding·reset 기존31개 유지.
- **완료 기준**: N+1 broad day/dataset query 제거의 실제source구조 확인, 같은72표본(30/365/3650·2profile) actual양API 재측정·개선방향 확인과 기능Native/core 회귀PASS/skip0. 새SLA/임의성능숫자 없음. 원래측정fixture/크기/샘플/검증은 바꾸지 않는다.
- **검증 방법**:
  ```bash
  cd android
  ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest
  ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.ny.my.health.SqliteSyncStateStoreInstrumentedTest,com.ny.my.health.SyncStateStorePerformanceInstrumentedTest
  ```
- **주의사항**: T4 실제fixed NativePASS 뒤 실행한다. source는 별도worktree로 분리할 수 있고 T5의 session/state/newSessionTest와 겹치지 않는다. 측정 baseline d158981/CI41970834390은 SQLStore16개기대실패와 분리된 performance test의72관측PASS이다. 현재성·scope·프라이버시·과거확인 손상검출을 완화하여 속도를 만들지 않는다. 요약용 SQL MIN/MAX문자정렬로 Instant 순서를 바꾸지 않는다. 상태/범위별전체행을 list/wholeJSON으로 메모리에 적재하지 않는다.

### T9: 계정없는 emulator bootstrap 안정화

- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `.github/workflows/android-emulator-tests.yml` | 수정 | 기존account-free 실행전 setup package 간섭 제거 |
- **인터페이스**: 없음. 기존 emulator-runner script에서 launcher force-stop 직전에 Google SDK setup package가 실제 있을 때만 `adb shell am force-stop com.google.android.googlesdksetup`를 실행한다. package presence는 고정된 pm 명령으로 확인하고 값/경로를 로그로 내보내지 않는다.
- **결정 로직**: package있음→한번force-stop, 없으면 정상진행. 실제ANR 증거는 d158/API35 focus-failure-initial.png의 해당package다.
- **호출 순서**: 기존이미지/KVM/SDK/APK부팅전build→emulator부팅→위 setupforce-stop→기존launcherstop→기존full Native/host 그대로.
- **작성할 테스트**: 새 unit 없음(정적CI설정). 추출script의 bash -n과 실제 양API의 기존strict focus/clipboard·전체Native/host 실행으로 확인한다.
- **완료 기준**: scope/permissions/image/resource/timeout/classfilter/assertion/ANR전역설정/skip/자동재시도 불변; diff1파일과 script문법PASS, 실제fullCI 결과수집. 실제 기기/계정 provisioning을 대신하지 않으며 account-free CI에만 적용.
- **검증 방법**: YAML 구조파싱과 script bash -n; 이후PR 양API full workflow 실제결과.
- **주의사항**: global ANR suppress flag, SetupWizard 전체 disable, device_provisioned/user_setup_complete 설정, PlayServices/HealthConnect disable, forceclick/sleep/timeout증가를 도입하지 않는다. 고정된 emulator boot setup package만 대상으로 하고 production코드/매니페스트를 수정하지 않는다. T7workflow artifact 경로는 보존하며 T6와 파일이 겹쳐 T6의 선행 gate다.


### T10: 실제 hot journal Android 재현

- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `android/app/src/androidTest/java/com/ny/my/health/SyncStateProcessRecoveryInstrumentedTest.kt` | 수정 | 실제spill 4096행×4096byte handle reset-before hold/reopen 회귀 |
  | `android/ci/verify-sync-state-process-recovery.sh` | 수정 | 기존16보존+새1사례/17count/고정진단allowlist |

- **인터페이스**: 2절 `HotJournalSpillProbe`와 2절 `SqliteSyncStateStore`, 2절 `SyncState` 참조. 기존fixture/store/host 메서드만활용하고제품API/DB/engine/기타test는수정하지않는다.
- **결정 로직**: source수정없음. 유효큰DB의정상SQLite rollback 후이전committed상태복원이기대값이다.
- **호출 순서**: seed유효전체load→정상reset→beforeCommit실제spill증명→holdmarker→hostkill/새PID→첫load/이전state검증→정상mutation→소유cleanup.
- **작성할 테스트**:
  - "Reopening around atomic commits never confirms an unacknowledged file or date" — 기존 `reopeningAroundAtomicCommitsNeverConfirmsAnUnacknowledgedFileOrDate`의 reset-spill-before fixture; 과거ImportantQ1 및cache boundary(③④⑤). 원래16case/단언전부보존.
- **완료 기준**: shell/YAML/각runnerline문법·APK/testAPK compile, 실제API35/36 source미수정probe에서spill증명후read-only실패또는정확한반증확인. 실패를별도probe로기록하고제품통과를의미하지않음. finalmerge는수정후같은17사례/기능/후측정이모두PASS필수다.
- **검증 방법**: `bash -n android/ci/verify-sync-state-process-recovery.sh`; android cwd `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest`; 실제PR양API workflow.
- **주의사항**: root의독점Gradlegrant필수, 최초compile실패시중단보고. 개별seed loop별store.transact를20,000번호출하지않는다. 임의cache/durability조정·timeout확대·원본read-only오류를기대값으로수용·raw출력공개금지. source현head에고정한후CI실행중다른inactive설계작업만가능하며productionfix공개는Android실패증거확인뒤다.


### T11: 검증된 SQLite crash rollback

- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `android/app/src/main/java/com/ny/my/health/SyncStateHotJournalRecovery.kt` | 신규 | private raw/work pair·NOFOLLOW·native rollback 검증 |
  | `android/app/src/main/java/com/ny/my/health/SyncStateDatabase.kt` | 수정 | typed recovery 경계·기존파일전용open·enum seam |
  | `android/app/src/main/java/com/ny/my/health/SqliteSyncStateStore.kt` | 수정 | 모든최초entry의fullvalidator/cancellation 연결 |
  | `android/app/src/androidTest/java/com/ny/my/health/SqliteSyncStateStoreInstrumentedTest.kt` | 수정 | 요구기반fault/보존/retry/cancel/entry회귀 |
  | `android/app/src/androidTest/java/com/ny/my/health/SyncStateProcessRecoveryInstrumentedTest.kt` | 수정 | raw/work artifact의소유cleanup만보완 |

- **인터페이스**: 2절 `SyncStateHotJournalRecovery`, 2절 `SyncStateDatabase`, 2절 `SyncStatePhase`, 2절 `HotJournalRecoveryChecks` 참조. Store/core public포트/schema/기존history는변경하지않는다.
- **결정 로직**: header/footer·marker/sidecar/metadata부적합은원본RW거부, 실제typedreadonly+정상단일DELETE pair+work전체검증+원본불변때만native rollback허용. 판정은helper의순수bytepredicate/validation 함수, 파일protocol은helper, SQLiteopen검사는Database, privatebinding/active무결성은기존Storevalidator가소유한다.
- **호출 순서**: readonlyfastpath→typedfailure→FD/pair/footer분류→durable raw보존→별도worknative rollback/fullvalidate→원본identity/hash재확인→원래경로native rollback/fullvalidate→기존readonlyadmission.
- **작성할 테스트**:
  - "Validated hot journals restore committed state through every store entry without fabricating confirmations" — 신규, 정상/entry/원본backup/dedupe(①③④⑤).
  - "Unsupported or malformed recovered pairs preserve original bytes without a writable original open" — 신규, schema/binding/비선택leaf/attempt(③④⑤).
  - "Unsafe rollback sidecars and changed source pairs never follow external paths or delete unknown files" — 신규, footer/파일종류/identity/unknownentry(③④). hardlink는 실제생성이가능하면sameinode/nlink2와Store거부를검증한다. enforcing앱정책의정확AccessDenied에서는무생성/원본bytes와동일production stat validator의nlink1수락/2거부를분리검증하며실제hardlinked admission은미검증으로남긴다.
  - "Interrupted rollback preparation retains raw evidence and safely retries without inventing success" — 신규, 단계CE/failure/다음Store(③④⑤).
- **완료 기준**: 기존모든회귀보존/새Native 실제양API PASS·17OS실제spill verifier/cleanup PASS·같은72/API성능재측정·원본미지원/손상보존/모든entry검증·최종독립Q1재검토통과. 고정probe CI시작뒤별도worktree에서local authoring가능하나productionfix공개/완료판정전에는미수정Android 실제실패증거를root가확인해야한다. sourcefix완료를컴파일로대신하지않는다.
- **검증 방법**: android cwd `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest`; 최종PR API35/36 workflow 및safe artifact 원본대조.
- **주의사항**: 최초compile앞root독점grant필수. 실패시중단보고/숨은교정루프금지. unknownWAL/ATTACH나rawcopy자체를sandbox로간주하지않는다. 원격/계정/권한/의존성추가없음. rootADR009 Proposed에원본비변경문구의정확한예외를기록했고최종독립적합성후만Accepted다.

## 4. 검증

문서 조립 검증은 실제 `assemble_brief.py`에 `--parent docs/2026-11-05-v1-completion/plan.md`를 주어 pinned parent revision에서 수행했다. T1~T6 모두 exit0/해소실패0이며 T1의 31개 metadata 선언은 parent에서 각각 정확히 한 번 수집됨을 확인했다(자식 DTO 선언0). 실제 변경 파일 집계는 T1~T6=4/5/4/4/3/3, 테스트 집계=4/2/1/1/1/1이다. 9개 담당 기준/테스트 열도 상위와 정확히 일치한다. Shared design의 80줄 권장 상한 경고는 남으며 T1의 최종 크기는 88줄이다. 계약을 생략해 크기를 줄이지 않으며 작업블록은 모두80줄 미만이다. 검증용 브리프는 /tmp에만 있고 구현 실행/위임은 하지 않았다. PR15 병합·실제 API 재확인 gate는 유지한다.

실행 전 parent/spec revision을 확인한다. T3 착수 전 PR15 merged API를 확인한다. core-store만의 개발 검증이며 상위 전체 23행/실제계정/SAF/worker/UI acceptance 완료를 의미하지 않는다. 명령은 계획이며 작성 중 실행하지 않았다.

```bash
cd android
./gradlew -PcoreOnly :core:test
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
./gradlew :app:assembleDebugAndroidTest
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.notClass=com.ny.my.health.HistoryProcessRecoveryInstrumentedTest,com.ny.my.health.SyncStateProcessRecoveryInstrumentedTest
cd ..
bash android/ci/verify-history-process-recovery.sh
bash android/ci/verify-sync-state-process-recovery.sh
```

실제 SQLite/host 검증은 SDK-free 로컬 테스트와 별개이며 emulator/KVM 부재 시 API35/36 CI의 해당 head URL/결과가 필요하다. 기존 P0 reader pagination/overlap, CSV normalization/escaping/null, Drive privacy/root/idempotence, foundation history/UI/profiling 회귀를 재사용하고 각 helper별 중복 tests를 추가하지 않는다. 이 PR의 새 타입·SQL·fixture에 raw health/CSV/token/account/URI 공개 로그가 없는지 diff/업로드 경로를 정적 확인한다. app-private synthetic binding 검사 외 실제 account check는 하지 않는다. 기존 backup/history contract를 바꾸지 않는다.

실행 보고 `docs/2026-11-05-v1-completion/execution-report.md`에는 source head·배정 테스트의 실제 함수/결과·미실행 이유·CI URL·PR15 선행 merge head를 기록한다(각 T 제품파일 한도와 별도 문서 증거). AGENTS의 fresh-context read-only 별도 세션 검토와 한국어 PR comment가 필요하다. 최신 full diff/CI head를 맞추고 blocking 수정 후 재검토한다. ADR-006 Proposed는 별도 adr-writer 단계에서 작성하며 이번 문서는 ADR/구현/테스트 통과 증거가 아니다.

## 5. 롤백

새 session은 UI/worker에 아직 연결하지 않아 자동 실행되지 않는다. archived generation은 이전 목적지 이력만 보존하며 재활성화 권한/active private binding을 보존하지 않는다. 명시 설정/coverage reset만 archived/current metadata를 모두 지운다. PR revert는 새 metadata DB를 삭제하지 않으며 foundation history DB는 보존한다. 실제 remote write가 발생한 것처럼/rollback된 것처럼 표시하지 않는다. SQLite 손상/미지원/marker가 있으면 blocked로 보존하고 사용자 명시 reset 경계 외 자동 덮어쓰기하지 않는다. 새 DB를 구버전이 무시하는 것은 원격 복구가 아니다.

## 복구 기록

추가 초기 계획 검토: config singleton generation을 day/attempt parent로 쓰면 변경 시 이전 결과를 삭제해야 하는 FK 충돌을 확인했다. spec 4절의 이전 목적지 이력 보존을 지키도록 immutable generation parent와 config active pointer로 보정했다. 제품 실행/구현 실패 전 검토이며 구현 복구 회차에 해당하지 않는다. 상위/ADR table 목록 보정과 새 parent_rev 고정 후 실행한다.

작성 전 root 검토 보정: full-five 기준으로 현재 scope의 oldest 후보를 제한하여 starvation이 생기는 설계와 scope row 덮어쓰기를 발견했다. 구현 착수/실패 전 계획 수정이며 실행 복구 회차는 아직 사용하지 않았다. scope PK·조회·coverage 분리를 적용했고 상위/ADR 보정 커밋 8df94e65f677f6ba297ea76cc69f264710efe66a에 parent_rev를 고정했다. PR15 병합·실제 API 재확인 전 실행 금지는 유지한다.

| 작업 | 회차/2 | 실패 증거·원인 | 새 근거·수정 가설 | 계획 보정 전→후·유지 기준 | 수정 파일·보존 대상 | 검증 명령·결과 | 실행자·다음 조치 |
|---|---|---|---|---|---|---|---|
| T5 | 4 (소유자자동승인 compiler한정추가교정;누적유지) | recovery3 productioncompilePASS후Native fixture의inlinevalueclass DestinationGeneration에lateinit불가, testkt309 | nullableprivatebacking+requireNotNull getter, configure가실제committed generation만저장하도록유지 | 가짜default세대/기대변경/API변경없음; 기존9사례유지 | SyncExecutionSessionInstrumentedTest.kt fixture만 | core4PASS/appcompilePASS/AndroidTestcompileFAIL, /workspace/tooling/v1-t5-recovery3-verify.log | resume_v1_session_t5 한정교정후같은명령 |
| T5 | 3 (소유자 자동승인 추가교정; 계획교정2회유지) | 최초verify core SyncStateTest4/4PASS 후 appcompile 실패: 다른module public read.state smartcast불가, SyncExecutionSession.kt193 | requireReady에서read.state를local val로잡아동일null/status검증후반환 | API/오류/동작/기대검증불변; 기본2회한도소진을숨기지않고V1자동승인에따라compiler정합성만한정수정 | T5session1파일만;core/9Native테스트유지 | 최초명령exit1 /workspace/tooling/v1-t5-resume-verify.log; 수정후동일명령예정 | resume_v1_session_t5 한정교정, Gradle직렬 |
| T8 | 1/2 | firstverify core65중새identity fixture1실패: malformed UUID changed로기대STALE전에INVALID/BLOCKED. production narrow다른generation은null대신throw. 사전편집cwd오류후verify계속도보고됨 | 다른generation header=null계약복원·valid replacement fixture·actualSQLite stale-null회귀, full aggregate는scalar만보존하여전체candidate최대9유지 | 기존31회귀/성능72fixture/검증강도불변, malformed DB는계속오류이며fake malformed를정상identity교체로표현하지않음 | T8기존5파일중store/coretest/nativeTest한정; exact3load숫자보다파일수1/4비례증가없음과최초검증존재확인 | 최초전체명령 core65/1실패에서중단·native미실행; 교정후동일전체verify예정 | resume_v1_store_perf_t8 부분교정, shell set-e/절대경로 |
| T2 | 3 (소유자 자동승인에 따른 추가 교정; 기존2회 보존) | root 및 독립 사전 계약 검토: finished 수동 attempt가 새 자동 scope selected snapshot을 영구 invalid로 만들어 새 start 불가 | selected-scope equality는 unfinished attempt에만 적용하고 finished 자체 fingerprint/categories 검증은 유지. fresh id의 새 profile start만 허용 | 승인된 manual/automatic 분리 및 previous finished 교체 계약 불변. 기본한도 초과를 숨기지 않고 현재 자동승인 범위의 제한 교정 | SyncState.kt/SyncStateTest.kt만; 기존unfinished mismatch/start/confirmation 거부 유지 | core64/실패·오류·skip0; 실행자21초·최종 root9초 PASS | v1_core_t2 한정 교정 |
| T2 | 2/2 | root source 대조: load()는 선택 scope 없이 현재 attempt를 반환하지만 isValid가 attempt.scopeKey와 null selected scope를 무조건 비교해 정상 journal을 거부 | unscoped snapshot은 attempt 자체의 canonical/profile/config 관계만 검사하고 selected scope가 있을 때만 equality 검사; 날짜행/선택 coverage를 unscoped에 혼입하지 않음 | load() 무선택 계약과 엄격한 selected-scope CAS 불변 | SyncState.kt/SyncStateTest.kt만; 기존 mutation·hash·codec 유지 | core64개/실패·오류·skip0; 실행자9초/root7초 PASS(입력동일 UP-TO-DATE XML대조) | v1_core_t2 부분 교정 |
| T9 | 3 (소유자 자동승인에 따른 실행기호환 교정; 기존2회보존) | 실제79ffd/CI41975679263 API35: runner가각script줄을개별sh -c로실행하여 multiline if 첫줄에서 fi기대EOF, Native0개/산출물없음 | 동일presence조건/force-stop을한줄의완결if로표현. 실행기실제line단위문법검증추가 | 이미지/권한/대상패키지/테스트/timeout/skip불변; staticwhole-scriptPASS와runtime구분 | workflow1파일만 | 줄별syntax·조건제어·양API Native 재실행대기 | v1_emulator_bootstrap_t9 한정교정 |
| T9 | 2/2 | root 추가 package-match 검증의 Python 인용문 syntax오류, source수정/commit 전중단 | 검증driver 인용문만 교정해 exact/other/absent 제어입력 재검사 | workflow내용/기대·권한불변 | 제품수정없음 | 제어3입력PASS·YAML/bash구조PASS; actualCI대기 | root 검증driver 한정교정 |
| T9 | 1/2 | 최초조립의 inline 파일목록이통계0으로나옴 | 기존실제파일1개를템플릿표로옮겨 조립통계1 보정 | 실행명령/계약/제품변경없음 | childplan표만 | 재조립 해소실패0/파일1 확인 | 실행자위임전 표기교정 |
| T5 | 1/2 | 실행 전 별도 계획 대조: recovery-before-gate가 liveowner를 복구할 수 있고 instance별gate/finished pending거부/pre-start merge의 내구성 시점이 미정 | canonical-path shared entry·gate선점후 복구/attempt종료·최대3trigger 사전대기·실제union커밋후MERGED·finished pending-only 예외로 명확화 | 단일process/no lease/구조적취소/성공미조작 유지; T4Native gate불변 | 배정session/state/coretest/nativeTest4파일 | 구현착수전 계약보정; T4실제PASS 후 실행 | 별도read-only 계획 검토 근거·원복횟수 보존 |
| T7 | 1/2 | 첫compile 호출이 network sandbox 옵션 없이 실행돼 Gradle local lock socket usable wildcard IP 오류, task전 중단·source변경없음 | 이미검증된 with_additional_permissions network.enabled=true로 같은wrapper명령1회. 환경/테스트/코드불변 | 새로운 외부서비스/credential없음, localGradle socket권한만복원 | 제품파일 수정없음 | 동일focusedcompile network옵션 교정 후21초 PASS; source2파일 고정 | v1_store_measurement_t7 호출옵션 교정 |
| T4 | 10 (소유자 자동승인에 따른 추가 fixture 교정; 기존회차 보존) | 실제API35 CI41967950562:69/1failure/0error/0skip; row-malformed fixture가 CORRUPT 대신UNSUPPORTED. AOSP15 SQLiteConnection의 기본 locale open이 android_metadata table을 추가함을 공식소스로 확인 | raw fault-injection connection에도 production과 같은 NO_LOCALIZED_COLLATORS 사용·단일의도 fault만 주입. schema allowlist/기대CORRUPT 불변 | 제품schema허용범위 확대/테스트skip/기대완화 없음 | 배정SQLiteTest rawopen4곳만 | row fixture 실제재검증·양API CI 대기 | v1_core_t4 fixture 한정 교정 |
| T4 | 9 (소유자 자동승인에 따른 독립 검토 교정; 기존회차 보존) | c2b49a2 별도 specP2 reset초기 marker/stage crash교착, qualityImportant2 최초구성 schema-only 중단·일반쓰기의 CORRUPT 우회 | 회귀/seam-only probe로 native실패증거 수집→inactive stage 원자적 게시·일관adapter무결성검증·inactive reset준비로 교정→실제SQLite/process 재검증 및독립재검토 | 초기화원자성·unknown원본보존·privatecore비노출·기존history 유지. 새product범위없음 | DB/store/배정SQLiteTest 총3파일 이내 | native probeFAIL→fixPASS·최신fullverify·양API/host 대기 | v1_core_t4 단계분리 교정 |
| T4 | 8 (소유자 자동승인에 따른 추가 교정; 기존회차 보존) | batch7 동결검증 중 static 대조: quarantine sidecar의 exists()는 broken symlink를 부재로 받아 rename 덮어쓰기 가능 | saved sidecar도 NOFOLLOW_LINKS 존재검사로 unknown symlink 보존·차단 | 원본/unknownartifact 보존 경계불변; 기본회차 리셋없음 | SqliteSyncStateStore.kt/배정SQLite test만 | executor 영향compile28초 PASS; root 전체 JVM138/0/0/0·APK/lint/testAPK1분37초 PASS; native12개CI대기 | v1_core_t4 한정 교정 |
| T4 | 7 (소유자 자동승인에 따른 추가 교정; 기존회차 보존) | 실행자 종합 static 대조: JSON syntax오류 분류, ABSENT stale CAS의 부수생성, reset cleanup crash 재시도교착, stage-empty 사전검증 누락, SQLite 동적정수type 허용. generationcap/자동on→resetoff fixture 미검증 | 실패상태·원본보존·CAS/정규타입·정확empty·원자적marker퇴역을 일괄보정. 동일 기준의 native회귀 추가 | 권한/범위/원본보존·코어JSON/DTO/기존history 불변; 새framework없음 | 배정store/database/test 내, 필요scalarcodec만4파일이내 | 일괄fix 전체verify1분53초 PASS; 최종guard 포함root1분37초 PASS; native12개CI대기 | v1_core_t4 한정 교정 |
| T4 | 6 (소유자 자동승인에 따른 추가 교정; 기존회차 보존) | 실행자 static uncovered: lastUploadAt이 current confirmed_at만 집계하여 새revision/공유target invalidation 후 prior만 남으면 실제 과거확인시각을 잃음 | active generation의 current 및 canonical priorConfirmation.confirmedAt 최대값 집계; 다른generation은 제외 | 마지막확인은 전체완료와 별개·이전목적지현재화금지·prior이력보존 유지 | SqliteSyncStateStore.kt/배정SQLite test만 | prior-only lastUploadAt 보존과 reset/config-switch 분리 검증 대기 | v1_core_t4 한정 교정 |
| T4 | 5 (소유자 자동승인에 따른 추가 교정; 기존회차 보존) | 실행자 static uncovered: 다른 profile 공유파일 무효화가 day를 무조건 INDETERMINATE로 바꿔 영구BLOCKED reason/REMOTE_STALE sibling 상태를 잃거나 invalid row 생성 | 영향받는 file만 INDETERMINATE/prior로 바꾸고 기존 BLOCKED/REMOTE_STALE day는 유지, 파생 day 검증 후 저장 | 다른scope 파일쓰기에 따른 uploaded현재성 무효화와 기존차단·stale 사실 보존 | SqliteSyncStateStore.kt/배정SQLite test만 | crossprofile permanent-block/stale sibling fixture 및 전체verify 대기 | v1_core_t4 한정 교정 |
| T4 | 4 (소유자 자동승인에 따른 추가 경로 교정; 기존회차 보존) | 실행자 교정3 편집 cwd가 android인데 android/app 상대경로 사용으로 FileNotFoundError·편집0; 후속Gradle가 잘못 시작돼 compile전 식별PID만 중단 | 절대 파일경로로 같은 smartcast 교정 적용→exit0/실제diff확인 후에만 동일 verify 시작 | source/test 기대값 불변; 중단 run은 PASS로 집계하지 않음·cache/보안설정 변경없음 | SqliteSyncStateStore.kt만 | correction3-applied 전체 JVM/APK/lint/testAPK1분51초 PASS(실제SQLite는CI대기) | v1_core_t4 경로 한정 교정 |
| T4 | 3 (소유자 자동승인에 따른 추가 교정; 기존2회 보존) | 최초 전체 build25초 FAIL: core의 public nullable mutation property는 app 모듈에서 smart cast 불가(start/day/confirmation/pendingTriggers) | 일치 분기에서 검증된 payload를 local val/checkNotNull로 고정해 Kotlin 모듈 경계 반영 | 스키마·상태/테스트·권한·fixture 기대 불변, 도구/환경변경 없음 | SqliteSyncStateStore.kt만 | 동일 전체 명령1회 재검증 대기 | v1_core_t4 compiler 한정 교정 |
| T4 | 2/2 | root source 대조: confirmation 적용이 day.blocker를 무조건 null로 지워 같은 날짜 다른 파일의 reconciliation 차단을 건강한 파일 ack로 숨김 | 파일 ack는 해당 파일만 확인하며 기존 blocker/REMOTE_STALE 보존. 모든 파일확인 및 blocker없음일 때만 CONFIRMED | 파일별 확인·부분차단 보존·실패를 성공으로 승격금지 유지 | SqliteSyncStateStore.kt/배정SQLite test만; core/history 유지 | mixed blocked+running→healthy ack/reopen 차단보존 fixture 대기 | v1_core_t4 한정 교정 |
| T4 | 1/2 | 실행자 uncovered: 새 selected profile 조회가 다른 profile의 unfinished attempt와 공존하면 정상 busy journal을 core validator가 거부하여 CORRUPT 오분류 | loadForScope에서 이 conflict만 UNAVAILABLE/state=null로 반환하고 load() READY/attempt·기존 rows는 유지; recovery/session gate 소유권 변경 없음 | 새 admission 차단·오류상태 무empty·미종결scope 강제교체 금지 유지 | SqliteSyncStateStore.kt/배정SQLite test만; core/history 보존 | conflicting scoped read UNAVAILABLE·unscoped attempt 보존 fixture 대기 | v1_core_t4 한정 보정 |
| T3 | 4 (소유자 자동승인에 따른 추가 교정; 기존회차 보존) | 독립 사전 계약 검토: 고정 reader의 access()는 trigger 입력 없이 한 scope map만 반환해 공용 session에서 수동 EXPLICIT_DAY와 자동 WORKOUT_SCOPED 선택을 구분할 수 없음 | access(trigger) 필수 인자·engine와 admission이 실제 trigger 전달. readScoped는 명시요청scope 그대로 | 단일gate·수동/자동 scope분리·자동 whole-day HR금지 보존. 별도gate/가변globalmode 도입 없음 | Ports/DestinationSync/DestinationSyncTest 총3파일; T4 파일 불변 | target33/실패·오류·skip0, 실행자22초; root 전체 core64/0/0/0,10초 PASS | v1_core_t3 한정 교정 |
| T3 | 3 (소유자 자동승인에 따른 추가 교정; 기존2회 보존) | 독립 사전 계약 검토: KST 자정 전 승인한 floor 날짜가 자정 뒤 실행될 때 scope만 확인하고 현재 floor 이전 원본을 읽을 수 있음 | admitted 날짜는 늘리지 않고 각 read 직전 current KST floor 재검사. 범위외는 STALE 중단, floor 이전은 HISTORY_ACCESS_REQUIRED journal 후 읽기 없이 유지 | 매실행 conservative floor·admitted 날짜 고정 계약 보존; 기본한도 초과를 자동승인 제한 교정으로 명시 | DestinationSync.kt/DestinationSyncTest.kt만; typed failure·reconcile·기존midnight fixture 보존 | target33/실패·오류·skip0, 실행자21초; 최종 root core64/0/0/0,9초 PASS | v1_core_t3 한정 교정 |
| T3 | 2/2 | 실행자 uncovered: 목적지 모든 예외를 generic PENDING으로 반환해 worker가 안전한 transient와 불명확 create를 구분할 durable 근거 없음 | typed allowlist failure + 고정 reason/RETRYABLE, 실제 provider 매핑은 후속 유지 | 최초외3회 제한·불명확create 맹목재송신금지 보존. parent980a38b 재검토·핀 | Ports/SyncState/SyncPlanner/DestinationSync/DestinationSyncTest 총5파일; 기존 regression 보존 | target 실행자29초 PASS; root JVM138/0/0/0·APK/lint/testAPK1분31초 PASS | v1_core_t3 한정 교정 |
| T3 | 1/2 | root source 대조: 동일 revision의 기존 CONFIRMED 파일을 planned에서 제외해 현재 remote metadata/checksum 재검증 없이 성공 재사용 | 모든 intended 파일을 새 source read 뒤 reconcile하고 이전 확인은 prior로 보존. 무쓰기 재사용도 fresh remote proof 필요 | 현재성 재검증·same-ID·privacy 계약 불변. PENDING 확인 거부와 INDETERMINATE 중간 journal 유지 | DestinationSync.kt/DestinationSyncTest.kt만; 기존포트/DTO/Drive유지 | prior-confirmed unsafe/moved/shared/absent/reconcile 실패·healthy 무쓰기 target 실행자/root 각20초 PASS | v1_core_t3 부분 교정 |
| T2 | 1/2 | root source 대조: revision hash가 승인된 schema literal0.1 누락, scope JSON 묶음/enum ordinal dataset 순서로 canonical component 계약과 다름. 기존49테스트는 시간불변만 확인 | 명시적 component별 protocol + 고정 EMPTY hash vector/명시 dataset 이름 순서 fixture로 확인 | SHA256·4byte BE 길이·기존CSV/scope/시각 계약 불변, 새 persisted format 배포 전 교정 | SyncRevision.kt/SyncRevisionTest.kt만; T1/codec/reducer 보존 | literal vector·4dataset 이름순 검증과 core49개/0실패·오류·skip,17초 PASS | v1_core_t2 한정 교정 |
| T1 | 3 (소유자 자동승인에 따른 추가 교정; 기본 한도2회 유지 기록) | root 코드 대조: scopeKey가 ordinal 정렬/문자 length-colon 형식으로 승인된 domain+이름정렬+4byte big-endian 계약 위반. isChecked가 source read와 remote 확인을 합쳐 구분 불충분 | 고정 외부 hash vector로 scope protocol 검증; checked는 읽기 확인, uploaded는 파일 원격 확인으로 분리하고 planner는 업로드 pending을 계속 gap으로 유지 | 새 업무규칙/권한 없음. 기본 복구한도 소진 후 원인을 숨기거나 회차 리셋하지 않고 현재 사용자 V1 자동승인을 추가 정합성 교정 권한으로 적용 | SyncPlanner.kt/SyncState.kt와 배정2test파일만; DTO/기존회귀 보존 | core45개/0실패·오류·skip, 실행자18초/root7초 PASS | v1_core_t1 한정 보정 |
| T1 | 2/2 | test 파일 작성 명령의 cwd가 android인데 android/core 상대경로를 사용해 쓰기 실패. source compile PASS 후 지정 test class 없음으로 verify FAIL | 승인된 repository root에서 미작성 배정2개 test 파일 작성, 같은 verify 실행 | 구현/기대값/대상4파일 불변. DTO/planner source 보존 | SyncStateTest.kt/SyncPlannerTest.kt만 새로 작성 | 지정4개/실패·오류·skip0,15초 PASS | v1_core_t1 경로 한정 복구 |
| T1 | 1/2 | 실행자 사전 확인: validation API·bounded days와 SQL 전기간 summary의 권위·remainingPending 의미가 모호해 DTO 선언만 작성 후 중단 | parent에 isValid extension/SQL summary authoritative/selected 포함 pending 명시. 범위/성공기준 불변 | private helper는 구현 선택; 현재 profile mismatch는 재조회 필요로 선택0. 전체범위 순회 금지 | SyncState.kt/SyncPlanner.kt와 배정4tests 보존 | 사전 계약 반영; 후속 경로실패와 교정 결과를 같은 T1에 누적 기록 | v1_core_t1 부분 보정 후 구현/검증 |

## 환경 준비 기록

| 원인·근거 | 준비 조치 | 조치 사용/1 | 확인 명령·결과 | 확인 사용/1 | 다음 조치 |
|---|---|---|---|---|---|

최신 위임 경계: 자동승인된 상위 계획에 따라 SDK-free 신규 모델/planner/revision(T1/T2)만 별도 worktree에서 먼저 실행한다. 기존 Ports/Android/CI 연결(T3 이후)과 PR 병합은 PR15 reviewed merge/API 재확인 뒤 진행한다. 이는 최초 코드 실행 전 실제 파일 의존성에 맞춘 순서 보정이며 성능 PR 코드/기능/병합 gate를 우회하지 않는다.

Core T1 추가 정합성: canonical scope key는 domain 포함·타입 이름 사전순·각 UTF-8 component 앞4byte big-endian 길이로 고정한다. checked는 해당 revision의 source read와 요청 scope를 확인한 사실이며 remote pending/REMOTE_STALE만으로 읽기 사실을 지우지 않는다. uploaded는 nonempty intended files의 확인 완료를 별도 요구한다. planner의 오래된 gap 제외는 checked만으로 판단하지 않고 EMPTY 또는 실제 uploaded 현재성일 때만 제외한다. source blocked/실패는 새 checked를 만들지 않는다.

T2 canonical revision component 명확화: 각 component는 UTF-8 앞4byte big-endian 길이이며 순서가 sync-revision/1 → 0.1 → ISO date → admitted scopeKey hex → actual scope map의 DataType.name 사전순(type.name,scope.name 각기) → Dataset.name 사전순(dataset.name,relativePath,canonical CSV content 각기)이다. positive grant는 actual non-BLOCKED scope keys와 정확히 같아 별도 JSON component를 중복 추가하지 않는다. EMPTY 고정 vector도 이 형식을 사용한다.

T3 실행 선행 증거: PR15는 master bf071e19e4e52c285e075b3dfe3db09e1bda8135로 병합됐고 이 worktree에 1cbf860c6c431edd437a5d039fe1d36eafade2f6로 반영했다. 실제 Ports.upsertBatch 기본 순차와 PrivateDriveUploader의 최대2/고유입력/콜백 계약을 다시 확인했다. 병합 후 root 전체 JVM137개(core63/app74)·APK/lint/testAPK가2분21초에 통과했다. ScopedHealthReader.readScoped에는 admission의 requestedScopes를 명시 전달하여 구성 밖 읽기와 일중 권한 변경 때 profile 축소 바꿔치기를 막는다. 응답 scopeByType은 요청 key를 그대로 가지며 읽기 불가 타입은 BLOCKED, HealthSnapshot.grantedTypes는 실제 positive read scope keys여야 한다. 현재 전체 grant는 별도 access() 사실로 유지한다.

T3 현재성 보정: snapshot/revision이 같아도 기존 CONFIRMED 파일을 reconcile 입력에서 제외하지 않는다. 이전 확인은 priorConfirmation으로 남기고 현재 journal을 준비해 모든 intended file의 현재 metadata/identity/checksum을 재검증한다. 원격 검증이 건강하면 write 없이 재확인하며 privacy/identity/조회 실패를 과거 성공으로 숨기지 않는다.

T2 unscoped snapshot 명확화: load()의 scopeKey=null/requestedScopes={}는 attempt 부재를 뜻하지 않는다. attempt는 자체 scope fingerprint·요청 categories가 configuration subset인지 검증하고, 선택 scope가 존재할 때만 selected key와 equality를 요구한다. 무선택 snapshot의 days/oldestPendingDays/selected counters는 비어/null이어야 한다. start mutation은 선택 scope 없이 허용하지 않으며 기존 confirmation은 exact attempt/currentDay 관계로 검증한다.

상위 변경 재검토: c55220f→980a38b는 위 T3 실패 분류의 enum/outcome와 책임 분리만 보정한다. 승인 spec 변경 없음, 사용자 V1 자동승인에 따라 이 child를 재검토하고 parent_rev를 새 revision으로 고정했다. 현재성 recovery1과 unscoped T2 recovery2는 보존한다.

T2 finished profile 전환 보정: selected scope와 attempt의 equality는 unfinished attempt에만 요구한다. finished attempt는 자체 fingerprint와 현재 구성 category subset 등 기존 metadata 검증을 유지하며, 다른 profile의 새 선택 snapshot에서도 과거 마지막 journal로 남을 수 있다. start는 새 UUID와 선택 profile 일치/기존 finished 조건을 계속 요구한다. 현재 진행 중 다른 profile attempt의 탈취·confirmation 우회는 허용하지 않는다.

T3 지연 실행 경계: 각 날짜의 readScoped 직전 현재 KST 날짜를 다시 구하고 date<enrolled 또는 date>=현재 today이면 STALE로 원본/원격 IO를 중단한다. date<현재today-30일이면 해당 exact-profile day를 BLOCKED/HISTORY_ACCESS_REQUIRED·checkedAt=null로 journal하고 읽기/업로드 없이 다음 admitted 날짜로 간다. 이전 파일 확인/target metadata는 과거 사실로 보존한다. 새 날짜를 추가하거나 과거 first-grant timestamp를 추정하지 않는다. 자정 전 floor 날짜를 승인하고 자정 뒤 실행한 fixture에서 reader/write 호출0과 durable blocker를 확인한다.

같은 T3 자정 경계 교정에서 최종 coverage 조회의 today도 종료 시점 KST로 다시 산출한다. 진행 중 자정이 지난 경우 새 날짜를 실행 목록에 추가하지 않지만 최신 pending gap을 이전 today 기준으로 숨기지 않는다.

T3 최종 확인: root source/테스트 대조 후 전체core64/실패·오류·skip0을 최종 working snapshot에서 직접 실행했다(9초). 바로 앞 typed failure 단계의 통합 JVM138/APK/lint/testAPK1분31초 증거와 구분하며 이후 상태교정의 Android 재통합은 T4 체크포인트에서 수행한다. T4의 actual SQLite suite는 로컬 emulator 부재로 API35/36 CI가 필요하다.

T4 busy scope 경계: 현재 generation의 unfinished attempt가 존재하고 loadForScope가 선택한 nonnull profile과 다르면 loadForScope는 UNAVAILABLE/state=null을 반환한다. 정상 DB를 CORRUPT로 분류하거나 attempt를 버린 가짜 선택 상태를 반환하지 않는다. load()는 READY unscoped + 정확한 unfinished attempt로 계속 열려 recovery가 가능하다. same-profile scoped read와 finished different-profile 선택은 기존 정상 계약이다. 어떤 조회도 busy를 이유로 journal을 mutate하거나 owner를 탈취하지 않는다.

T3 trigger별 읽기 policy: ScopedHealthReader.access(trigger: SyncTrigger)는 default 인자 없이 호출한다. MANUAL은 명시적 전경 읽기 범위, SCHEDULED/CATCH_UP은 자동 summary/workout 및 background capability/permission policy다. 명시적 전경 backfill 버튼도 trigger=MANUAL이며 CATCH_UP은 자동 catch-up 경로에만 사용한다. 동일 reader는 immutable policy 선택을 trigger로 수행하며 가변 전역 mode나 별도 실행 gate를 도입하지 않는다. engine와 session admission은 실제 run trigger를 전달하고 detectPending은 configuration.automatic이면 CATCH_UP, 아니면 MANUAL로 metadata만 조회한다. readScoped의 admitted requestedScopes 인자는 유지한다. 이번 합성 경계는 동일 reader/engine의 수동→자동→수동 선택과 자동 whole-day HR 요청없음/자동 background불가에도 수동가능을 확인하며 실제 HC automatic reader는 후속 plan에서 구현한다.

T4 파일확인 독립성: confirmation transaction은 해당 dataset의 확인 metadata만 갱신하며 다른 dataset이 만든 day.blocker와 REMOTE_STALE를 지우지 않는다. blocker가 남으면 BLOCKED, 잔존 remote-stale가 있으면 stale 상태를 보존하고, blocker없음·모든 intended file CONFIRMED·요청범위 checked일 때만 day CONFIRMED를 계산한다. blocker 해제는 다음 명시적 reread/reconcile day journal 등 그 원인을 검증한 경계가 담당한다. mixed BLOCKED dataset와 RUNNING dataset의 healthy confirmation/reopen 뒤 blocker·실패파일·pending/blocked coverage가 유지되는 actual SQLite fixture를 추가한다. 변환 후 day의 공개 isValid도 검사하여 confirmation이 잘못된 파생 상태를 만들지 않게 한다.

T4 SQL scalar decode 확인: SyncStateCodec에 decodeDate/decodeInstant/decodeDigest/decodeUuid/decodeHandle의 공개 scalar 검증을 연결한다. JSON leaf10종·whole-row 봉투금지는 유지하며 이 함수들은 SQL 문자열의 canonical boundary 공유용이다. cross-module nullable payload는 분기 안 local val로 고정한다.

T4 cross-scope 무효화 보정: 일치 dataset CONFIRMED→INDETERMINATE와 priorConfirmation 보존만 적용하고 기존 day.phase가 BLOCKED/REMOTE_STALE이면 그대로 유지한다. 그 외 건강한 current day는 INDETERMINATE로 전환한다. 다른 dataset의 blocker/stale·source checked/scopes·revision은 보존하며 변환 후 공개 DayState.isValid를 검사한다. permanent reason의 BLOCKED 관계 및 REMOTE_STALE sibling이 재열기에서도 보존되는 actual fixture를 추가한다.

T4 마지막 확인시각: lastUploadAt은 현재 active generation에 저장된 모든 profile dataset의 current confirmed_at 및 prior_confirmation_json의 canonical FileConfirmation.confirmedAt 중 최대값이다. 현재 remote-current가 무효화돼 prior만 남아도 과거 실제확인시각은 유지하되 uploadedThrough/isUploaded를 올리지 않는다. old generation의 확인은 포함하지 않으며 새 구성/reset은 여전히 null이다. prior-only fixture로 current null/prior nonnull 경계를 실제 reopen 검증한다.

T4 correction7 확정 기술 경계:
- malformed private binding JSONObject syntax의 JSONException은 CORRUPT로 분류한다. UNSUPPORTED/UNAVAILABLE 및 cancellation 경계는 유지한다.
- configure는 expected가 nonnull인데 실제 DB가 ABSENT이면 canonical path lock 안에서 생성 전에 STALE/null을 반환한다. 원본 파일·marker를 만들지 않는다. private write helper에 expected precondition 전달은 내부 구현 선택이며 기존 공개 API는 유지한다.
- SQLite scalar는 선언된 정수값을 Cursor.FIELD_TYPE_INTEGER로 확인한 후 읽는다(config id/format_version/automatic/reset_epoch, attempt retries_used, dataset populated). 허용 범위는 그대로다. nullable/TEXT scalar도 기존 row helper를 통해 실제 STRING/null type을 확인하여 BLOB 등 암묵변환을 성공 상태로 받지 않는다. reset_epoch Long.MAX_VALUE의 +1도 트랜잭션 이전/안에서 거부하여 REAL overflow를 커밋하지 않는다.
- 원본을 옮기기 전에 stage의 정상 schema/FK, exact READY tombstone, 자동off/binding없음, generation 정확1개, day/dataset/attempt0을 검증한다. 알려진 schema라도 구성/coverage가 있는 stage는 설치하지 않고 원본·marker·stage를 보존한 UNAVAILABLE다. 설치 뒤에도 같은 empty proof를 재확인한다.
- 완료한 owned reset marker는 owner/installed를 차례로 지우지 않는다. stage없음·current exact empty 재검증·old원본의 owned quarantine 보존·sidecars완료를 확인한 뒤 marker directory를 같은 parent의 `.reset.completed-<ownerUUID>`로 원자적으로 퇴역하고 parent fsync 뒤에만 응답한다. 퇴역 경로가 이미 있거나 알 수 없는 artifact/형식/ownership이면 덮지 않고 UNAVAILABLE다. 퇴역 directory는 비민감 owner/완료 marker만 남을 수 있으며 admission marker가 아니다. 새 lifecycle에서는 cleanup 중간에 active marker의 ownership을 잃는 창이 없어야 한다. 재시도는 정상 owned 완료 직전 상태를 안전하게 재검증·퇴역하며, unknown marker는 계속 보존·차단한다. 옛 아직 미배포 cleanup 방식 상태를 추정하여 낯선 파일을 삭제하지 않는다.
- 배정 실제SQLite fixture에 JSON구문 오류, absent+nonnull CAS 파일무생성, INTEGER REAL/TEXT 오류, unsafe configured stage 원본보존, owned completion 재시도 및 unknown retirement 경로 보존, generation10000 경계의 새 구성 rollback(기존이력/active보존), synthetic automatic=1에서 reset→off/reopen을 추가한다. 기존 테스트/expectation/손상파일보존은 약화하지 않는다. source snapshot을 안정시킨 후 같은 전체 verify1회, 연결SQLite는 여전히CI gate다.

T4 fixture 구성: 서로 state를 공유하지 않는 JSON/schema/reset/prior-only/capacity 보조 시나리오는 같은 파일의 별도 의미 있는 @Test로 노출해 한 시나리오 실패가 뒤 시나리오 실행을 숨기지 않게 한다. 서로 의존하는 chronological archive/scope/CAS 흐름은 보존하며 중복 실행·기대완화·새 framework 없이 기존/배정 조건을 모두 유지한다. Native 실제 test 개수는 XML에서 집계하고 예전1개 fixture 숫자를 강제하지 않는다.

같은 owned marker 검증에서 owner/installed UUID 파일은 readText 전에 정확36 UTF-8 bytes와 regular/non-symlink 조건을 확인한다. 자체 canonical UUID writer 형식의 기존 상한이며 큰 unknown 파일을 읽어 소유권을 추정하지 않는다.

T4 마지막 NOFOLLOW 보정: owned quarantine sidecar 목적지도 기존 base/completed 목적지와 동일하게 Files.exists(...NOFOLLOW_LINKS)로 검사하며 broken symlink를 덮지 않는다. 해당 synthetic symlink 회귀를 보존하고 executor는 동결된 batch7 결과 후 수정파일/AndroidTest 영향 compile1회, root는 최종 전체 JVM/APK/lint/testAPK 명령을 직접 실행한다. 검증 기대/개별 시나리오를 삭제하거나 재시도 회차를 초기화하지 않는다.

T4 source checkpoint: c637b195035b317bc4897c300e9fbefff3c6bb5c의 실제 source를 root가 최종 전체 명령으로1분37초 검증했다. core64+app74=138/실패·오류·skip0, APK/lint/testAPK PASS이며 native test12개는 compile만 통과했다. 로컬 emulator/KVM이 없어 실제SQLite 실행은 API35/36 CI까지 in_progress로 유지한다. T5/T6 실행 gate는 native 증거 확인 전 넘기지 않는다.

T4 독립 검토 correction9 — 먼저 회귀 재현, 이후 구현:
1. 첫 단계는 기존 동작을 유지한 internal phase observer(default no-op, enum만, payload없음)와 의미 있는 native 회귀를 추가한다. 최초 configure beforeCommit 예외/취소 후 ABSENT·재시도 가능, 초기 schema후 중단, malformed binding/day/attempt에서 configure/invalidate/transact/정상reset 거부·원본byte보존, reset owner 전/도중·stage schema후 중단 뒤 explicit reset 재시도를 각각 검증한다. default observer는 동작을 바꾸지 않고 단계별 native/후속host fault seam이다. source 및 테스트기대는 그대로 두고 실제 CI 실패→교정 증거를 보존한다.
2. 같은 probe에서 correction10의 fixture open flag만 고친다. raw4곳 모두 NO_LOCALIZED_COLLATORS를 추가하여 의도한 schema/row fault 외 android_metadata table을 만들지 않는다. production strict schema를 완화하지 않는다.
3. 실패증거 확보 후 최초 configure의 fully validated DB를 app-private inactive `.init-<UUID>` 위치에 준비하고 schema/config commit·닫기·fsync·정상READY/바인딩/empty coverage 증명 후, canonical path lock 안에서 main DB의 실제부재(NOFOLLOW)를 재확인해 원자적으로 게시하고 parent fsync 뒤 응답한다. 초기 중단이 active schema-only 파일을 남겨선 안 된다. crash 전 inactive artifact는 자동 승인/원본삭제 근거가 아니고 보존하며 다음 명시 configure는 독립 fresh staging으로 정상 재시도 가능하다. 게시 후 응답전 손실은 READY 결과 재조회/CAS로 처리한다. stale nonnull expected의 ABSENT 무생성 계약은 유지한다.
4. 일반 configure/invalidate/transact/정상reset은 기존 active rows와 canonical private binding을 같은 transaction/path lock에서 검증하여 CORRUPT 상태를 바꾸거나 archive로 숨기거나 격리 없이 지우지 않는다. SDK-free core는 private 값에 접근하지 않으며 app adapter 내부 decode/discard 검증만 허용한다. 모든 UPDATE는 필요한 열만 바꾼다. 같은 transaction 내 이미검증한 rows의 불필요한 중복 scan은 피하되 across-call cache로 검증을 생략하지 않는다.
5. corrupt reset도 ownership과 exact-empty stage를 비활성 `.reset.prepare-<UUID>` directory에서 준비·fsync·검증한 뒤에만 active `.reset` marker로 원자적 게시한다. owner write/schema-config 사이 중단은 active admission marker를 남기지 않는다. 검증된 marker 게시 후 원본 이동·설치·기존 atomic완료퇴역 protocol을 수행한다. 미완성 비활성 준비물은 임의 기존 active marker/stage를 repair/delete할 권한으로 해석하지 않는다. unknown active marker·unsafe configured stage·symlink/retirement conflict 보존 회귀를 유지한다. 실제 phase별 process kill은 T6 host로 확장한다.
6. internal observer는 예를 들어 INITIAL_SCHEMA_READY/INITIAL_BEFORE_PUBLISH/INITIAL_PUBLISHED, RESET_OWNER_BEFORE/RESET_OWNER_WRITTEN/RESET_STAGE_SCHEMA_READY/RESET_PREPARED/RESET_MARKER_PUBLISHED 등 실제 작업단계를 enum으로만 노출하고 날짜/경로/binding/CSV/token을 넘기지 않는다. 기존 beforeCommit hook은 유지한다. 테스트는 실제 지점을 사용하고 지원되지 않는 OS 원자성/자동복구를 가정하지 않는다.

독립 성능검토 P2(댓글6649794657)로 T7 진단을 추가했다. 이는 소유자의 병목개선→V1 자동승인 범위에서 계정없는 실제Android metadata 측정이며, 기존기능성공기준/실제SQLite/host gate를 대체하지 않는다. T6와 workflow파일을 공유하여 T6 depends_on에 T7을 추가했고 나머지 제품작업을 병렬화하지 않는다. T4 CI의 기존12개 native source는 API35에서11PASS/1fixture실패이며 T7은 미확인소스의 완료를 주장하지 않는다.

T7 assembly 확인: 해소실패0, Shared design115줄 권장경고와 SqliteSyncStateStore 코드조각 미수집 경고는 schema/메서드가 table·불렛에 충분히 수집된 원문을 대조해 경고①로 판정했다. 새계약을 추정하지 않았다. T7 첫 Gradle은 per-call network옵션 누락으로 task시작 전 local socket 오류이며 기존 환경을 변경하지 않고 권한옵션만 교정한다.

PR16 review probe checkpoint: source 7625f9728331f29be9356fcc784057b5bde646a9는 기본 publication/integrity 동작을 아직 고치지 않고 enum-only seam/회귀19개와 raw fixture flag를 반영했다. 기존12+새19개SQLite + 독립performance1개를 compile했고 root 전체 JVM138/실패·오류·skip0·APK/lint/testAPK1분49초 PASS. 실제Native 회귀 실패와 metadata72개 관측은 다음CI에서 수집한다. c2b49a2의 이전 양API결과는69/1실패/0오류/0skip이며 모두 row-case CORRUPT대신UNSUPPORTED이고 host는미실행이다. artifact digest/XML을 root가 내려받아 확인했다.

교정9 병렬 순서 명확화: probe source를 immutable commit으로 고정하고 CI가 그head에서 시작된 뒤에는 로컬 PhaseB 구현을 계속할 수 있다. PhaseB fix commit 공개/push 및 통과 판정 전에는 이전 immutable probe의 실제Native 실패 증거를 root가 확인한다. 테스트전후 source 분리/실패이력·실제process gate를 약화하지 않으며 CI대기 중 로컬구현만 겹친다. T5실행 gate(T4실제통과)는 그대로다.


상위82aa56c 변경 재검토: 새session nativeclass로 테스트배치만 분리하고 승인스펙/31 metadata DTO·외부기능은 그대로다. parent_rev를 새 commit에 고정했다. T5와 T8은 source파일이 분리되므로 T4완료 뒤 병렬 가능하며 같은Gradle cache는 직렬이다. T8과T9는 실제측정/PNG에 근거한 기존V1 성능/검증 보정이며 새product범위를 추가하지 않는다.

T7 실제완료: d158 NativeCI41970834390 양API에서 performance testcase각1개PASS·JSON72관측을 ZIP digest/XML과함께 root가검사했다. 전체suite는API35=89/19fail, API36=89/16fail로 여전히실패이며 SQLite기대재현실패16개는양API동일, API35추가UI3개는 GoogleSdkSetup ANR PNG로확인했다. host는미실행이다. FULL_EXPLICIT 확인커밋 반복3median은365행 약722/1003ms(API35/36),3650행 약6854/9632ms이다. 이것은 합성Android 저장소 측정이며 실제휴대전화/네트워크/배터리가 아니다.

T9 source4a65f1a: 실행자YAML구조/추출bash/PATH검사와 root직접구조·bash -n·고정package exact/other/absent 제어3입력 모두PASS. 한 번의 root검증driver syntax오류는 source수정전중단으로 복구2/2에 기록했다. 이미지/자원/permission/classfilter/timeout/assertion/host/metrics경로는그대로이며 실제다음양API결과전까지 in_progress다.

T4 교정9 실제실패증거: d158/CI41970834390 양API SQLite31개중16개가새회귀에서실패했고 기존12개는모두PASS(이전rawlocale fixture오류해소). API35UI3개는같은패키지setup ANR PNG, API36UI는PASS. root가양ZIP digest/XML/metric72개씩을검사했다. 고정probe 후 localfix4d691f4는SDK-private admission·inactive initial/reset publication을반영했고 실행자full2분1초/rootfull14초 JVM138/0/0/0·APK/lint/testAPK PASS. 실제fixed Native/process와독립재검토는아직대기다.

T9 실제실행기 계약 보정: emulator-runner@v2는 script의각줄을 별도 /usr/bin/sh -c로실행했다(79ffd API35 actuallog). 따라서 shell if/then/fi는한줄안에서완결한다. root의whole-script bash -n/조건semantic검사는필요하지만충분하지않았으며 새검증은runner와같은 각nonempty line의sh -n도확인한다. 79ffd해당job은setup shell에서Native전중단으로actual0/미실행이며그실행을SQLstoreFAIL/PASS로집계하지않는다.

T9 correction3 source7a6c954: root가 실제runner와같이 각nonempty line의 sh -n 및package exact/other/absent mock에서 stop1/0/0을검증했다. 최신79ffd CI41975679263은 양API 모두multiline if 첫줄EOF로 Native전중단, 실제test0·산출물0이며T4고정소스검증으로집계하지않는다. 다음head에서는같은guard를한줄로완결하여재실행한다. 앱Androidsource는4d691f4에서변경없고14초root/JVM138/0/0/0·APK/lint/testAPK 증거를유지한다.

T5 recovery2/2 — 조립 브리프 직접 대조에서 recovery1의 상세 공용 gate 계약이 2절 밖 복구 기록에 있어 Shared design에 누락됨을 확인했다. 동일 문구를 2절로 이동하고 finished callback 일반 규칙에 기존 pendingTriggers union 예외를 명시했다. 새 API/정책 변경 없이 canonical-path/shared receipts/NonCancellable/recovery-needed/오류 계약이 실제 브리프에 포함되도록 보정한다. T4 실제 Native 선행 gate는 그대로다.

T6 recovery1/2 — 독립 읽기전용 소스점검으로 T4 교정9가 추가한 initial/reset publication 경계가 원래7개 host계획에 빠져 있음을 확인했다. 승인된 원자성/중단복구 기준에 따라 기존7개를 유지하고 기존enum seam9개를 추가한16개로 보정했다. initial게시전ABSENT·게시후READY, resetmarker전원본UNSUPPORTED·후UNAVAILABLE와명시retry 차이를2절에고정했다. 제품API/권한/시스템설정/timeout확대없음. 각phase process죽음만 증명하며전원손실일반화금지.

2026-11-12 재개 확인: immutable head cb89da7783eb8420fca0440cf7b29e61955bca95의 CI41977482571이 양API35/36 SUCCESS다. root가 ZIP digest와 XML을 직접 확인: 각89/실패0/오류0/skip0, SQLite31개 모두PASS, performance1개PASS/72관측, 기존history host8recovery+4settings+8cleanup=20/죽인holdPASS0. ZIP API35 SHA2566a554f674da83fd532138cba0210d189bbadd303e324d2b9a38333c430fee948/API36 0cdb685adbb4c4a9f07aae82f81b995d9b07ce5339b56b8e7aaa31dd61ba0f8e. T4/T9 done; 새session/T6 process와T8性能후측정은별도미완료. T5/T8 파일불겹침/별도worktree로진행, sharedGradle직렬.

T6 recovery2/2 — 파일/API 의존성을 재대조하여 불필요한 runtime 직렬대기를 보정했다. T6의3파일은T5/T8 제품·테스트파일과겹치지않고기존T4enum/store 및T5constructor/API만사용한다. 이미실제통과한T4/T7/T9를depends_on으로유지하고T5/T8 source통합compile체크포인트뒤host작성에착수한다. T5/T8는actualNative/성능전까지in_progress이며done을꾸미지않는다. 최종head의T5/T6/T8전체Native·같은성능fixture·모든16force-stop과독립review gate는그대로다. CI대기중독립authoring만병렬화하며test/timeout/성공기준변경없음.

T5/T8 통합 source checkpoint: root가모든제품source동결후전체명령1분51초PASS(/workspace/tooling/v1-combined-t5-t8-root.log). 실제XMLcore65/app74=139/실패0·오류0·skip0, APK/lint/testAPK PASS. T5 nineNative/T8store32 및성능72/API실행은아직pending이므로두T는in_progress유지. compile통합checkpoint에따라T6작성시작, 최종새head전체Native/16host/후측정과독립리뷰게이트불변.

T6 recovery3 (소유자자동승인·앞선계획교정2회유지) — 최초compile30초PASS뒤실행자가host실패진단한계보고: genericfail후privateoutput삭제로실패fixture/step/위치판정불가. host1파일만고정allowlist fixture/stage와pass=false, 정확한test파일의숫자line만추출한안전진단을추가한다. raw메시지/DB/UUID/PID/경로/계정/CSV출력·업로드는금지하며새진단도고정enum/숫자/boolean만이다. 성공조건16verifier/16cleanup/죽인holdPASS0·timeout·단언·Native불변. shellsyntax/가짜실패출력에개인식별자문자열을넣어고정field만출력됨을staticdriver로검증한다.

T6 recovery4 (root검증driver cwd교정,owner-auto추가/앞선회차보존) — root의safe진단6입력/YAML/discovery/runner-line sh문법은PASS했으나같은명령의Gradle cwd를repo root로두어 wrapper ./gradlew파일부재로task시작전실패했다. source/기대/환경설정은불변, 로그 v1-core-store-final-root-wrong-cwd.log 보존. android cwd로동일full명령1회실행하여검증한다. 제품실패나NativePASS로집계하지않는다.

T6 source동결checkpoint: 실행자APK/testAPKcompile30초PASS, root직접safe진단6입력과YAML/discovery/runner각line sh문법PASS·전체명령43초PASS(대부분입력동일UP-TO-DATE), 기존JVM139/0/0/0 XML확인. v1-core-store-final-root.log. T6 class일반discovery제외와새16verifier/16cleanup host를workflow에연결했다. 실제Native99/양API72성능후측정/16process는CI대기이며T5/T6/T8 in_progress유지.

Important Q1 추가회귀: finalquality exacte62 별도세션이데스크톱SQLite20,000행actualkill로hotjournal+readonly rollback실패를재현했다. Android반증/재현이아직없어T10 test-only를추가, 원래16의작은resetfixture가놓친cache-spill boundary를검증한다. source수정/정상read를무조건RW로전환/journal삭제/자동emptyreset은금지하며안전한복구설계는별도검토중이다. 기존스펙의atomicprocessrecovery 보완이고새제품범위가아니다.

T10 recovery1/2 — 조립통계test0으로인라인작성할테스트항목을기존동일행동명의들여쓴bullet로표기보정했다. 테스트기대/API/source무변경, 조립test1/참조0실패확인후위임한다.

T10 recovery2/2 — 실행자가현재configure의등록일=yesterday강제와20,000일이전등록fixture의동일config재configure기대충돌을발견했다. 정상mutation재개증명은기존invalidate(expectedGeneration) CAS로바꿔같은구성/등록일/binding·자동off·새빈coverage/oldarchive보존을확인한다. sourceAPI/정상reopen기대/기존16case변경없음, 잘못된configure기대를고집하지않는다.

parent_rev 8beb945 재검토: 기존source/스펙/metadata스키마/권한범위는유지하고ImportantQ1을닫을검증된rollback helper 파일과ADR009 Proposed를추가했다. 일반read의절대비변경문구는정상fastpath에유지하며알려진crash복구예외만구체화했다. 새서버/권한/건강payload저장은없고기존raw metadata pair를noBackup에보존한다. 실제Androidfailure와후속독립검증전sourcefix완료가아니다.

T11 실행전계획: Q1 요구를기존원자성/원본보존의범위에서구체화했다. T10 immutable Android actual실패확인이선행의존성이며아직source구현지시가아니다. fullvalidator없이schema만보거나allreadRW/journal삭제로수정하지않는다. ADR009 Proposed/parent8beb를승인범위의결정기록으로삼고기존006결정은승계한다.

T11 recovery1/2 — 브리프검토에서추가phase선언이주석조각이라Shared에누락됨을발견했다. 기존9값과새HOT접두4값의동일enum계약을실제선언블록으로표기하고명시참조를추가, 체크항목의축약phase표기도정확한기존계약명으로맞췄다. source/API/기대정책변경없는브리프전달교정이며재조립후모든4상수포함을확인한다.

T11 recovery2/2 — 공식AOSP15/16 사전조사로journalmode생략이preserve가아니며SQLiteGlobal debug.sqlite.journalmode/resource의기본TRUNCATE를선택함을확인했다. recoveryopener를strictrollbackpair분류후candidate/original동일명시DELETE/FULL로보정하되original은canary전체검증/원본불변후에만열고unknownWAL전환은여전히금지한다. 또한별도보안preflight의manifest reader사전512byte/정규형/owner·dirhash·rawhash검증실패거부를기존프로토콜에명시했다. core/publicAPI/스키마/원본보존/회차를유지한다.

T10 recovery3 (owner-auto 추가교정;앞선2회보존) — e8a93/CI43141798089 API35는Native99완료뒤새case seed-ready60초에서marker전실패하여Q1을입증하지못했다. API36은UTP Maven기존좌표resolve실패로Native0/산출물0이며두POM의현재공식repo200은외부환경근거일뿐CI성공은아니다. 실행중rerun API는403(alreadyrunning)으로재실행을만들지않았다. 품질독립세션의동등조건검토에따라4096행×유효상한4096byte합성handle로실제pager bytes를넘기고main변경+journalmagic/NOFOLLOW증명을유지,seed상수/statement재사용과중복preseedscoped관찰만제거한다. timeout/첫READY·복원전체행·coverage/generation/binding/후속invalidate기대와기존16case는그대로다. Android20kPASS로표현하지않고데스크톱20k와구분한다.

T11 recovery3 (owner-auto 일정한정추가교정;계획교정2회보존) — Q1은실제데스크톱SQLite로확인됐고새동등Androidprobe를immutable commit에고정해CI를시작한다. T11 의존성은기존T4/T7/T9 actualgate로두고, test-only T10source compile/pin뒤별도worktree에서localfix작성은겹칠수있다. T10 actual실패확인전fix공개/완료판정금지및최종양API17host/모든회귀/동일성능/독립Q1재검토gate는그대로다. T10과겹치는ProcessTest는authoring동결snapshot에서분기하고추가test보정과충돌시둘모두보존해재통합한다.

실제e62/CI43139507838 원본대조완료: 양API각99/0/0/0, 새16recovery+16cleanup/holdPASS0, 기존historyhost20. ZIP SHA256 API35 c7958e6ce0f49f34c9c92dd1d9fd2fc230a599104177d37c7c0346cce115ff85/API36 01af04d4119a6c9d319535537fa2d7f685b742eae4c683994dc6a46b51782b34. T5/T6/T8 지정범위done이며새Q1은T10/T11로전체병합을계속차단한다. Q1수정뒤전체회귀를다시검증한다.

T11 local authoring 착수: immutable test-only f12ac9e9c13f569edb95e14c525f2cc051334f4a가CI43145722146에서시작된것을root가확인했다. /workspace/health-app-v1-hot-recovery의별도branch로분리하여5파일만작성한다. Android미수정실제실패확인전productionfix공개금지이며Native0/seed-timeout을Q1재현으로오인하지않는다.

T11 recovery4 (owner-auto compiler정합성추가교정;기존3회유지) — 최초fullverify53초FAIL: private read에coroutine인자를추가한뒤recoverReset의272행이옛3인자호출로남았다. 이미captured coroutine을그호출에도전달하는1행과Database의일반RO/한정rollback예외주석만교정한다. status/reset/원본보존/4새test·기존32기대불변, Native실행아님. 로그v1-t11-first-verify.log 보존,같은full명령재검증.

T11 recovery5 (owner-auto 호출경로/기존compiler교정누적) — recovery4 실행자는android cwd에서android/상대경로편집을시도해FileNotFound, set-e가편집뒤라미수정Gradle이시작되어자기wrapper/daemon만종료(exit130)했다. source추가변경0·추가테스트0, v1-t11-recovery4-verify.log보존. 새회차는절대Path/명시rootcwd의별도편집호출(첫줄set-e)과별도빌드호출로분리한다. 최초미해결coroutine인자와root가확정한inlinevalueclass lateinit generation/key를nullablebacking/getter로교정,Database주석만정확화한다. dummy세대/기대변경/기존32단언변경없음, 앞선회차를초기화하지않는다.

T11 recovery6 (owner-auto 정상canonical alias호환교정;앞선5회유지) — root가현재기존Session첫Native의335/336행 ./filename alias를확인했다. literalparent/absolute==canonical 강제는동일canonical path를공유하는지원alias까지거부하여이미합격한기존case를깨뜨린다. helper의private parent만canonical/normalize해UID/GID/noworldwrite를확인하고finalDB/journal leaf는lstat/O_NOFOLLOW/nlink로검증하여따라가지않는다. 기존Sessionalias단언을유지하고새hotentry그룹에도alias-load대표를추가한다. source전체canonicalize로leafsymlink를지우거나unsafe외부경로를허용하지않는다. recovery5 localfull2분10초/JVM139/0/0/0·APK/lint/testAPK PASS는보존, Native는아직미실행이다.

T11 recovery7 (owner-auto 독립재검토한정추가교정;앞선6회유지) — local후보d475의별도quality ImportantQ2: main부재+regularorphan sidecar를ABSENT로취급해새main게시와오래된journal결합을허용할수있다. helper첫guard에서main부재+정확한journal/wal/shm 중하나라도존재시UNAVAILABLE·무생성·원본sidecar bytes보존으로거부한다. 별도security Low: cleanup finally예외가원래CE를덮을수있음;원래Throwable을우선보존하고cleanup오류는suppressed/내부실패로남기며unknown work는지우지않는다. helper+기존4grouped NativeTest2파일만, 각orphan종류/load/유효새configure경계와unknownwork+CE조합을추가,기존32/Session9/host17/성능fixture/4test함수기대유지. sourceproof는데스크톱orphan대조+실제분기검토이며Android의oldcoverage자동채택을확인했다고과장하지않는다.

T11 recovery8 (owner-auto 기존상태분류회귀한정교정;앞선7회유지) — root source대조: guardSidecarPaths의파일종류require가IllegalArgumentException을던져Store.classify에서CORRUPT로오분류된다. 기존schemaCorruptionAndUnknownPaths...의directory/unknownpath UNAVAILABLE 기대가깨지는확정반례다. filesystem guard 내부의IllegalArgumentException만고정redacted SQLiteException(UNAVAILABLE)으로변환하고Store의metadata/canonical IllegalArgumentException→CORRUPT 분류는유지한다. helper+기존4grouped NativeTest파일만,기존32기대변경금지. normalABSENT/orphan거부/Q1pair/cancel 보존은그대로다.

T10 actual 완료: immutablef12/CI43145722146 양API XML각99/0/0/0·기존16recovery/16cleanup PASS, 새4096×4096bytecase의main-digestchanged/regularjournalmagic입증후새PID첫일반read READY assert(line181)가양API실패했다. root ZIP digest·원본safe결과대조 및hot-journal-probe.json 보존. 실제status문자열은safe진단에기록하지않아임의추정하지않는다. 이는이전20kseed-ready timeout/UTP문제와다른actual회귀실패다. T11 fix공개전실패gate를충족했다.
T11 final-local bd6e26faaba4127f6cccd7c4a81182146995e56e: rootfull1분59초PASS/JVM139/0/0/0·APK/lint/testAPK, 원래32/Session9 유지·새4group(총store36)compile. 별도4세션source재검토에서Q2/cleanupLow/unknownpath분류를source상해소했다고확인받았다. actualfixedNative103/17host·cleanup/성능72/API는미실행이며T11는in_progress·ADR009 Proposed다.

T11 b7 actual 미완료: Native43151273714 양API각103/1/0/0, 새unsafe grouped fixture의Files.createLink(line900) AccessDenied로하드링크주입전실패했다. API35 AVC는enforcing SELinux의link거부를증명한다. 실제하드링크admission/뒤분기/17host는아직증명되지않았다. 원본XML/ZIPdigest는execution-report에보존, JVM/APK/lintPASS와성능72/API는분리한다. 복구9의기술보정은작성자·독립품질/보안진단뒤기록하며기존8회는초기화하지않는다.

T11 recovery9 (owner-auto enforcing Android fixture전제교정;앞선8회유지) — b7 실제103/1/0/0 및API35 SELinux link AVC를근거로앱이자기파일hardlink를생성할수있다는전제를철회한다. 새호스트권한/보안설정/의존성은도입하지않고helper+현재NativeTest2파일만변경한다. 동일regular(StructStat)의internal가시성만열고본문/모든production호출을유지한다. journal/db각실제createLink시도는보존하며성공하면same dev/inode/nlink2·Store UNAVAILABLE/noRW/bytes보존을기존대로검증한다. 정확AccessDenied만별도플랫폼분할로확인하여alias부재·실제소스nlink1·DB/journal/sentinelbytes보존과동일production validator의실제stat기반nlink1수락/2거부를단언한다. 다른예외는실패,Assume/skip/무단continue금지이며이분할은실제hardlinked파일admission PASS가아니다. 뒤unsafe모든분할을실행한다. 독립quality진단은순수stat경계와OS생성거부의분리및명시적실제hardlink미검증을요구했다. 기존스펙원본보존/불안전경로거부/4group·기존32/Session9/host17/성능72gate는그대로다. 새source전체검증/별도검토후CI확인하며실제hardlinkOSadmission한계를완료보고에도남긴다.
