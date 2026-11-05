---
plan_id: core-store
title: 날짜별 복구와 내구성 상태
status: in_progress
author: Codex
created: 2026-11-05
parent: ../plan.md
parent_rev: 980a38bc98279d020c557600d5ed586d2fc83b13
spec: ../spec.md
spec_rev: 97c01b1992ad7935e6fa8f1f8b371a3963457daa
branch: feat/v1-completion
tasks:
  - {id: T1, title: 등록 이후 bounded 날짜 계획, depends_on: [], status: done, tier: standard}
  - {id: T2, title: 재읽기 revision과 scope별 확인, depends_on: [T1], status: done, tier: standard}
  - {id: T3, title: pending 기반 destination 실행, depends_on: [T2], status: done, tier: standard}
  - {id: T4, title: 실제 SQLite의 원자적 상태 저장, depends_on: [T3], status: in_progress, tier: standard}
  - {id: T5, title: process 공용 admission과 중단 복구, depends_on: [T4], status: pending, tier: standard}
  - {id: T6, title: OS 강제종료의 실제 commit 검증, depends_on: [T5], status: pending, tier: standard}
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
- `SyncMutation`：start는 새로운 id와 같은 attemptId가 필요하고 나머지는 현재 id가 필요하다. finish 뒤 callback은 INVALID다. confirmation은 현재 scope row의 revision·dataset·RUNNING/INDETERMINATE journal과 일치해야 한다. 같은 확인 재커밋만 멱등이며 서로 다른 target/시각으로 덮지 않는다. pendingTriggers 병합은 현재 generation/attempt만 바꾼다.
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

- `SqliteSyncStateStore`：private binding은 **A/SqliteSyncStateStore.kt 내부** `PrivateDestinationBinding(kind, accountName?, accountType?, treeUri?)`로 한정한다. DRIVE는 name/type만 각1~1024 UTF-8, LOCAL은 treeUri 1~4096 UTF-8, SYNTHETIC은 나머지 null. JSON allowlist는 kind/accountName/accountType/treeUri다. private binding도 이 store 자체 canonical writer/decode→re-encode byte equality를 쓰며 외부 JSON/공백 입력을 허용하는 예외를 두지 않는다. token/authCode/record/CSV는 허용하지 않는다. core state/config에는 binding payload/account name/type/URI를 반환하지 않고 opaque destinationHandle만 준다. app 전용 `configure(expected, configuration, binding): SyncCommit` 및 `readBinding(expected): PrivateDestinationBinding?`은 같은 store에 둔다. configure는 fresh UUID generation+enrolled 어제+binding+automatic off+빈 coverage를 **한 transaction**으로 교체한다. 기존 구성 변경은 expected generation 일치 때만 가능, ABSENT에서만 expected=null 초기 구성 허용. READY tombstone에서 새 구성도 tombstone expected generation을 요구한다. 교체 transaction은 새 generation row를 먼저 insert하고 config.active_generation과 active binding/automatic=0을 CAS update한다. 이전 generation/day/dataset/attempt 행은 삭제·재연결하지 않으며 active pointer가 가리키지 않는 이전 세대의 이력으로 남긴다. active binding은 새 선택의 최소 name/type 또는 URI로 교체하며 이전 binding을 archive에 복제하지 않는다. foundation history는 그대로 둔다. core transact는 private 필드를 읽어 전달하거나 덮어쓰지 않으며 SQL UPDATE 대상을 필요한 열로 한정한다. 이번 PR 실제 계정/선택 UI는 없고 합성 binding만 검사한다.

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
  | `android/app/src/androidTest/java/com/ny/my/health/SqliteSyncStateStoreInstrumentedTest.kt` | 수정 | 실제 store+합성 engine 동시/cancel 검증 |
  | `android/core/src/main/kotlin/com/ny/my/health/core/SyncState.kt` | 수정 | generation/attempt recovery 검증 보완 |

- **인터페이스**: 2절 `SyncState` 참조. 2절 `SyncStateStore` 참조. 2절 `SyncExecutionSession` constructor/registry. engine는 ScopedHealthReader/StorageDestination 합성 구현으로 실행한다. start attempt는 session만 소유하며 engine는 시작된 attemptId를 검증한다. T3 독립 테스트는 start mutation을 먼저 commit해 fixture를 만든다.
- **결정 로직**: SyncState.kt에서 미종결 journal→INDETERMINATE, 이전 확정 보존, stale generation/attempt→거부를 판정한다.
- **호출 순서**:
  ```
  1. 최초 detectPending → unresolved attempt recovery commit; 실패시 admission 차단
  2. gate 시도 → busy면 현재 generation pending trigger 병합 후 MERGED
  3. gate 소유자 access/loadForScope와 scope 고정 start commit → 실패시 engine 시작 금지
  4. engine 실행/finish commit → cancellation 전파, finally gate 반환
  5. invalidate transaction → 새 generation과 자동off; 늦은 callback CAS 거부
  ```
- **작성할 테스트**:
  - "Concurrent manual periodic and catch up requests admit once and reject old generation callbacks after restart" — 신규; 아래 fixture 조건으로 검증.
  - 케이스·재사용 근거: 신규 — 1절 Concurrent manual periodic...의 이번 PR 부분. CountDownLatch로 세 trigger 동시 진입→reader/destination 실행 하나·pendingTriggers 합집합, 살아있는 owner는 시간 경과로 탈취불가, cancel 중 writer 종료 전 gate 재입장불가, store 실패 시 admission차단, restart unresolved→INDETERMINATE(확인 시각 없음), invalidate 후 archived 행이 남아 있어도 늦은 callback/finish 거부와 새 상태보존(①③④). WorkManager 실제 integration은 후속이다.
- **완료 기준**: 실제 store를 사용한 admission/cancel/recovery suite 통과; UI lifecycle과 독립이며 현재 coordinator/UI 연결 변경 없음.
- **검증 방법**:
  ```bash
  cd android
  ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.ny.my.health.SqliteSyncStateStoreInstrumentedTest
  ```
- **주의사항**: T4 후 시작. HistoryDatabase pathLock은 store 호출 보호이며 전체 sync gate가 아니라는 상위 확인을 그대로 적용한다. timeout 소유권 탈취 금지. 임의 delay 대신 latch와 bounded timeout을 써 재현한다. 취소 commit best-effort 실패를 성공으로 쓰지 않는다.

### T6: OS 강제종료의 실제 commit 검증

- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `android/app/src/androidTest/java/com/ny/my/health/SyncStateProcessRecoveryInstrumentedTest.kt` | 신규 | 합성 seed/hold/reopen/cleanup |
  | `android/ci/verify-sync-state-process-recovery.sh` | 신규 | host force-stop 경계와 새 PID 검증 |
  | `.github/workflows/android-emulator-tests.yml` | 수정 | normal discovery 제외·별도 harness·합성 결과 |

- **인터페이스**: 2절 `SyncState` 참조. 2절 `SqliteSyncStateStore` schema/reset 계약. host flag `syncStateProcessHost=true`, markers `SYNC_STATE_READY pid=...`, `SYNC_STATE_REOPENED pid=...`, fixture allowlist `pending-before,pending-after,confirm-before,confirm-after,running,reset-before,reset-after`. 별도 sync-state-process-fixture.db와 ownership marker만 사용한다.
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
  - 케이스·재사용 근거: 신규 — 1절 Reopening around atomic commits...의 host process 부분. pending-before는 이전 상태, pending-after는 journal만, confirm-before는 미확정, confirm-after는 해당 파일만 확정, running은 INDETERMINATE·reconcile 전 성공불가, reset-before는 archived/current를 포함한 이전 상태/reset-after는 archived/current metadata 없는 자동off 새 tombstone generation. 실제 process 사망을 증명하며 단순 store 객체 재생성과 구분한다(①③④). 기존 history process harness/일반 UI 회귀는 수정 없이 재사용(⑤).
- **완료 기준**: normal suite에 hold class가 들어가지 않고 skip 없이 완료; API35/36의 별도 harness7개 verifier 성공·죽인 hold 성공집계0. T5~T6 통합 체크포인트로 아래 PR 검증 완료.
- **검증 방법**:
  ```bash
  bash -n android/ci/verify-sync-state-process-recovery.sh
  bash android/ci/verify-sync-state-process-recovery.sh
  ```
- **주의사항**: T5 후 시작. 기존 host shell의 bounded install/poll/PID/reap 구조를 재사용하되 system settings 전환은 추가하지 않는다. API flags 미확인은 read-only 확인 후 지시 범위만 구현하며 기동/컴파일/검증 실패시 blocked로 보고한다. 일반 discovery 제외는 ignore/Assume skip으로 대체하지 않는다. 공개 evidence는 fixture enum·count·boolean만, DB/CSV/계정/URI/원본 instrumentation dump 업로드 금지.

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
| T2 | 3 (소유자 자동승인에 따른 추가 교정; 기존2회 보존) | root 및 독립 사전 계약 검토: finished 수동 attempt가 새 자동 scope selected snapshot을 영구 invalid로 만들어 새 start 불가 | selected-scope equality는 unfinished attempt에만 적용하고 finished 자체 fingerprint/categories 검증은 유지. fresh id의 새 profile start만 허용 | 승인된 manual/automatic 분리 및 previous finished 교체 계약 불변. 기본한도 초과를 숨기지 않고 현재 자동승인 범위의 제한 교정 | SyncState.kt/SyncStateTest.kt만; 기존unfinished mismatch/start/confirmation 거부 유지 | core64/실패·오류·skip0; 실행자21초·최종 root9초 PASS | v1_core_t2 한정 교정 |
| T2 | 2/2 | root source 대조: load()는 선택 scope 없이 현재 attempt를 반환하지만 isValid가 attempt.scopeKey와 null selected scope를 무조건 비교해 정상 journal을 거부 | unscoped snapshot은 attempt 자체의 canonical/profile/config 관계만 검사하고 selected scope가 있을 때만 equality 검사; 날짜행/선택 coverage를 unscoped에 혼입하지 않음 | load() 무선택 계약과 엄격한 selected-scope CAS 불변 | SyncState.kt/SyncStateTest.kt만; 기존 mutation·hash·codec 유지 | core64개/실패·오류·skip0; 실행자9초/root7초 PASS(입력동일 UP-TO-DATE XML대조) | v1_core_t2 부분 교정 |
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
