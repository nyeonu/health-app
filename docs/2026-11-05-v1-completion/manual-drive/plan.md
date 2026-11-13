---
plan_id: manual-drive
title: 계정에 연결된 전경 수동 복구
status: in_progress
author: Codex task-breakdown
created: 2026-11-12
parent: ../plan.md
parent_rev: 10b8b7c60f14d2e23accebcb3823be0d60a02c7b
spec: ../spec.md
spec_rev: 97c01b1992ad7935e6fa8f1f8b371a3963457daa
branch: feat/v1-manual-drive
tasks:
  - id: T1
    title: 실제 요청 범위만 읽는 수동 HC adapter
    depends_on: []
    status: done
    tier: standard
  - id: T2
    title: 계정과 동일 token의 Drive 사용자 증명
    depends_on: []
    status: done
    tier: standard
  - id: T3
    title: journal 이후 쓰기와 읽기 전용 Drive 재조정
    depends_on: [T1]
    status: done
    tier: standard
  - id: T4
    title: 현재 세대의 target 증거와 명시 복구 원자성
    depends_on: []
    status: done
    tier: standard
  - id: T5
    title: Drive destination의 typed 확인 연결
    depends_on: [T3, T4]
    status: done
    tier: standard
  - id: T6
    title: gate 소유자만 실행 credential을 준비
    depends_on: [T4]
    status: done
    tier: standard
  - id: T7
    title: 명시 계속으로만 전경 수동 batch 실행
    depends_on: [T2, T5, T6]
    status: done
    tier: standard
  - id: T8
    title: 수동 구성과 복구 화면 합성
    depends_on: [T7]
    status: in_progress
    tier: standard
  - id: T9
    title: 실제 Activity의 bound 경로와 통합 회귀
    depends_on: [T8]
    status: in_progress
    tier: standard
  - id: T10
    title: 기존 강제종료 host 검증의 bound UI 호환
    depends_on: [T9]
    status: pending
    tier: light
  - id: T11
    title: 복원 시 실제 권한 범위 coverage 투영
    depends_on: [T9]
    status: done
    tier: standard
  - id: T12
    title: 마지막 업로드와 최근 대상 날짜 표시
    depends_on: [T8]
    status: in_progress
    tier: standard
  - id: T13
    title: 실제 core 처리 단계 성능 계측 복원
    depends_on: [T9]
    status: done
    tier: standard
  - id: T14
    title: bound 실행의 휴대폰 진단 수명 연결
    depends_on: [T11, T13]
    status: pending
    tier: standard
  - id: T15
    title: 양 API 에뮬레이터 userdata 디스크 확보
    depends_on: []
    status: pending
    tier: light
  - {id: T16, title: 실제 제목을 유일하게 지정하는 UI 회귀 선택자, depends_on: [T12], status: pending, tier: light}
  - {id: T17, title: 동일 전체 검증의 핵심 화면 fail-fast 분할, depends_on: [], status: pending, tier: standard}
---

# 계정에 연결된 전경 수동 복구

## 1. 맡는 기준

상위 추적성 행을 그대로 인용한다. core-store/automation-ui/saf-local 공동 소유 기준은 이번 PR 관련 경계만 충족하며 전체 에픽 완료로 승격하지 않는다. 각 이름의 작업 배정과 테스트 층은 3절에 명시한다. parent_rev는 현재 커밋이며 **root 검토→parent 먼저 커밋→그 파일 최신 commit으로 repin 후 승인**하기 전에는 실행 금지다. 소유자 V1 자동승인 위임에 따른 root 검토이며 사용자 반복 승인 질문은 없다.

| 스펙 기준 | 테스트 이름 (출처/층) | 충족 위치 |
|---|---|---|
| 매 실행 한국 시간 오늘에서 30일을 뺀 보수적 HC read floor 이전 또는 가용성/권한이 불명확한 날짜를 HISTORY_ACCESS_REQUIRED와 미확인 gap으로 유지하고 추정 최초 grant 시각을 저장하거나 원본 제한·오류·page/sample 초과를 빈 데이터로 처리하지 않는다. | `Dates below the conservative history floor and unknown access remain blocked instead of empty` (①·②·③/유닛 및 HC adapter 통합)  <br>`Scoped manual read queries only requested current grants and revoked access never becomes empty` (①·②·③·④/아래 manual-drive 작업 층)<br>`Scoped source limits remain blocked and legacy pagination overlap and CSV remain unchanged` (①·②·③·④/아래 manual-drive 작업 층)| core-store; manual-drive 완료 기준 |
| 부분 실패·취소·결과불명 후 대상 reconciliation을 거쳐 pending 파일만 다시 처리하고 이미 확인된 동일 revision의 건강한 파일을 중복 create하거나 prior success를 지우지 않는다. | `After partial failure cancellation or unknown create reconciliation retries only pending files` (①·③·④/유닛 및 local TLS·provider 통합) <br>`Reread journal preserves unknown create uncertainty before reconciliation is interrupted again`<br>`Unknown null handle remains unresolved across changed revision and conservative phases`<br>`Unknown create survives empty roundtrip while fresh pending and proven targets remain usable`<br>`New scope preserves unknown create from another scope for the same physical dataset`<br>`Reduced grants adopt a freshly proven target despite another scope unknown create`<br>`Uncertainty queries exclude other dates and generations and fail closed on stale or unavailable reads`<br>`unknownCreateSurvivesRereadCancellationAndSqliteReopenWithoutBlindCreate`<br>`crossScopeUnknownCreatesSurviveNewJournalCancellationAndSqliteReopen`<br>`physicalUncertaintyReadRejectsMalformedOtherProfilesOrphansAndUnavailableDatabases` <br>`Drive reconciliation never writes and unknown folder or file creation never blind posts` (①·②·③·④/아래 manual-drive 작업 층)<br>`All active scope known and prior handles survive reopen and conflicting targets block` (①·②·③·④/아래 manual-drive 작업 층)| core-store; manual-drive; saf-local 완료 기준 |
| 전경·주기·catch-up·중복 UI가 동시에 진입해도 공용 gate로 단일 실행만 시작하고 프로세스 손실 후 durable journal을 복구하며 설정 generation 변경 뒤 지연 결과가 새 coverage를 덮지 못한다. | `Concurrent manual periodic and catch up requests admit once and reject old generation callbacks after restart` (①·③·④/유닛 및 SQLite·worker 통합)  <br>`Prepared session admits one credential factory and busy callers only merge triggers` (①·②·③·④/아래 manual-drive 작업 층)| core-store; manual-drive; automation-ui 완료 기준 |
| Drive 계정은 전경 AccountPicker로 명시적으로 선택하고 모든 authorization을 저장된 Account로 제한하며 계정 변경/제거 시 새 generation·동의를 요구하고 다른 계정으로 조용히 fallback하지 않는다. | `Account picker selection binds all authorization and account removal never falls back` (①·③·④/adapter 통합 및 Compose E2E)  <br>`Same issued token about proof must exactly match selected account before any raw health read` (①·②·③·④/아래 manual-drive 작업 층)<br>`Picker cancellation malformed values and stale ticket never configure or authorize` (①·②·③·④/아래 manual-drive 작업 층)| manual-drive 완료 기준 |
| 일시 네트워크/허용된 service 실패만 exponential backoff로 최초 실행 외 최대 3회 재시도하고 영구 실패·불명확 create는 맹목 재전송하지 않으며 retry 소진은 다음 명시적 trigger까지 visible pending/blocked로 남긴다. | `Only transient batch failures retry at most three times and uncertain creates reconcile first` (①·②·③·④/유닛 및 worker·TLS 통합)  <br>`Explicit manual recovery reopens eligible blockers without deleting file uncertainty or remote stale` (①·②·③·④/아래 manual-drive 작업 층)| automation-ui; manual-drive 완료 기준 |
| 자동 opt-out·설정 변경·reset은 queued work와 현재 generation의 admission을 무효화하고 이미 발행된 원격 쓰기를 취소로 rollback했다고 표시하지 않으며 기존 이력 삭제와 설정/coverage reset 범위를 각각 정확히 안내한다. | `Opt out configuration change and confirmed reset invalidate old admission while preserving history reset scope` (①·③·④/SQLite·worker 통합 및 Compose E2E)  <br>`Manual configuration and reset use generation CAS and never erase foundation history` (①·②·③·④/아래 manual-drive 작업 층)| manual-drive; automation-ui 완료 기준 |
| Drive destination은 기존 drive.file·marked private owned My Drive·ambiguous target 거부·deterministic same-file 갱신·확인 응답 계약과 CSV v0.1을 유지한다. | `Drive tracked batches preserve private target validation deterministic same file and CSV schema` (①·③·⑤/기존 core 회귀 및 local TLS 통합)  <br>`Fresh Drive ACK permits absent checksum but rejects wrong checksum and changed target identity` (①·②·③·④/아래 manual-drive 작업 층)| manual-drive 완료 기준 |
| light/dark·큰 글자·Compose semantics 검증에서 동의·계정/폴더 선택·자동 설정·수동 실행·재시도·reset 확인에 도달할 수 있고 실제 TalkBack·Galaxy 관찰은 별도 미확인으로 남긴다. | `Consent account tree automatic manual retry and reset remain reachable in dark light and large text semantics` (①·②·⑤/Compose E2E)  <br>`Manual consent picker authorization and explicit continue remain reachable in light dark and large text` (①·②·③·④/아래 manual-drive 작업 층)| manual-drive; automation-ui 완료 기준 |
| app-private 상태/backup 제외와 로그/진단 allowlist를 검증하여 raw 건강 데이터·CSV·token·계정/기기/문서 식별자·URI·provider 본문을 공개 로그·fixture·CI artifact에 포함하지 않는다. | `Private state and allowlisted diagnostics exclude health payload credentials identifiers and provider bodies` (①·③/저장소·TLS·provider 통합 및 정적 검증)  <br>`Manual credentials never enter saved state store diagnostics or public fixtures` (①·②·③·④/아래 manual-drive 작업 층)| 전체 검증 3 완료 기준 |
| 실제 store 재열기·손상/미지원 상태·실행 취소·동시 admission·local TLS·합성 provider·worker 제약/권한·UI 회귀와 APK/lint 검증을 수행하고 각 PR의 독립 최신 head 검토를 마친 개발 완료와 Galaxy/계정/provider/Doze/배터리/TalkBack 수용 대기를 별도로 보고한다. | `Real store cancellation admission TLS provider worker and UI suites pass with device acceptance separately pending` (①·③·④·⑤/통합·E2E; review와 실제 기기는 비자동 항목)  <br>`Manual flow rejects stale proof pause reset and double continue while retaining legacy layout and history regressions` (①·②·③·④/아래 manual-drive 작업 층)| 전체 검증 1·2·4·5 완료 기준 |

## 2. 실행에 필요한 현재 상태

- 기준 source: master PR16 merge `4b98aee1119ded1f1c6553e92198ac004d30fa87`; 이 worktree의 branch `feat/v1-manual-drive`, 작성 시작시 clean. 승인 spec revision은 frontmatter와 같다.
- 상위 2절 `ManualDriveContracts`를 인터페이스·결정 로직 위치·확인 근거의 단일 원본으로 쓴다. 실제 없는 API는 위 계약대로 새로 도입하며 기존 Ports에는 upsertTracked가 없다. 기존 31개 metadata 필드를 재설계하지 않는다.
- HealthConnectReader.read(date)는 SDK/client/current grants→category conditional IO이며 HealthReader만 구현한다. private readPages는 PAGE_SIZE=1000/MAX_PAGES=100이고 HR sample100000을 검사한다. automatic adapter는 아직 없다.
- SqliteSyncStateStore.configure/readBinding/invalidate/reset와 loadUnresolvedCreates가 있다. known targets/reopen admin은 없다. 기존 store decode/validation/write CAS 및 noBackup DB를 그대로 사용한다.
- SyncExecutionSession.run은 path gate tryLock 실패시 merge하고 constructor engine을 사용한다. registry는 process shared이며 Activity 소유로 바꾸지 않는다. DestinationSync는 RUNNING journal→writePending→file confirmation의 경계를 이미 가진다.
- PrivateDriveUploader.upsertBatch는 root once/동시2/직렬 callback/structured cancellation 구현이다. appFolder는 create하므로 read-only reconcile에 쓸 수 없다. GoogleDriveTransport는 files path만 allowlist하며 HTTPS/JSON/1MiB/no redirect/no retry를 보존한다.
- 실제 MainActivity/SyncViewModel은 기존 선택 없는 auth+memory token 흐름이며 새 bound flow로 연결해야 한다. 기존 foundation history API와 CSV v0.1을 보존한다. 새 UI는 ManualDriveUi/ManualDriveSession으로 분리하고 HealthHomeScreen optional params를 사용한다.
- 신규 테스트 기반/Gradle 의존성은 필요 없다. 기존 OkHttp local TLS/JUnit, 실제 SQLite/Session instrumentation/host harness와 Compose 기반을 재사용한다. 기존 UI/test 명령은 `.github/workflows/android-emulator-tests.yml`에서 직접 확인했다.
- DAG: T1→T3→T5→T7→T8→T9, T2→T7, T4→T5/T6→T7. 병렬 authoring 후보 T1/T2/T4는 파일이 겹치지 않는다. 다른 조합은 depends_on 이후만 진행한다. 모든 로컬 Gradle은 root가 독점 직렬 실행한다. Native compile은 actual PASS가 아니며 각 focused Native는 최종 동일 head CI의 실제 결과로 확정할 수 있다; disjoint authoring을 그 대기 때문에 막지 않는다.

```text
// ManualDriveContracts — 상위 승인 계약 전문, 조립기 전달용 표기
현재 source 기준은 PR #16 merge `4b98aee1119ded1f1c6553e92198ac004d30fa87`이다. core-store 완료는 에픽 전체 완료가 아니다. 아래 설계는 기존 승인 범위의 실행 구체화이며 하위 plan은 root 검토 전 draft다.


    Ports.kt: P0 API 보존, additive typed callback (default는 순차 upsert).
interface DriveUploader {
    suspend fun upsertTracked(files: List<CsvFile>, onConfirmed: suspend (CsvFile, String) -> Unit)
    // 기존 upsert/upsertBatch 유지
}
    PrivateDriveUploader의 실제 member API로 구현; extension-only dispatch 금지.
    V1 전용 읽기 reconciliation과 journal 이후 쓰기 API; legacy upsert와 경계 공유.
suspend fun PrivateDriveUploader.reconcileTracked(files: List<PlannedFile>, knownTargets: Map<Dataset, Set<String>>): ReconciledFiles
suspend fun PrivateDriveUploader.writeTrackedPending(files: List<PlannedFile>, onConfirmed: suspend (CsvFile, String) -> Unit)
    app-only, current generation/date의 모든 profile targetHandle + priorConfirmation target.
suspend fun SqliteSyncStateStore.loadKnownDriveTargets(expected: DestinationGeneration, date: LocalDate): Map<Dataset, Set<String>>
    app-only admin: fresh proof + 명시 continue 후 gate owner만 호출; 기존 schema 유지.
suspend fun SqliteSyncStateStore.reopenManualBlockers(expected: DestinationGeneration): SyncCommit
    engine 인자 nullable; 기존 run 유지. production은 null, prepare 누락 시 fail closed.
suspend fun SyncExecutionSession.runPrepared(trigger: SyncTrigger, generation: DestinationGeneration, prepare: suspend () -> DestinationSync): BatchOutcome
    BoundDriveAuthorization.kt: Account 객체 대신 plain validated name/type 사용.
    credential은 private 비-data class 메모리, toString/StateFlow/SavedState/DB로 전달 금지.
    ManualDriveSession.kt: binding/ticket/generation/foreground를 before/after proof에 재확인.


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
```

```text
// ProfilingRepairContracts — 기존 안전 진단의 additive 복원
DestinationSync(store,reader,destination,clock,profiling:SyncProfiling?=null)
SyncRevision.identify(snapshot,previous,observedAt,profiling:SyncProfiling?=null)
ManualDriveSession의 기존 primary/production 인자 뒤 추가: newProfiling:()->ManualSyncProfiling?={null}, onProfileStarted:(ManualSyncProfiling)->Unit={}, onProfileFinished:(ManualSyncProfiling,ProfileOutcome)->Unit={_,_->}
ManualDrivePorts.profiledDestination(transport,generation,profiling):StorageDestination 기본은 기존 destination 위임, production은 같은 collector를 PrivateDriveUploader에 전달.
HEALTH_READ는 실제 readScoped, NORMALIZE/CSV_EXPORT는 identify 내부 두 실제 canonical/final projection 각각 계측(count2/day), DRIVE_TOTAL은 reconcile/write 실제 호출 각각 누적. about은 EXECUTION에만 포함하고 파일 요청으로 위장하지 않는다.
유효 ticket만 profile 생성, authStarted→현재 authReturned→continuewait→gate owner prepare 안 executionStarted. stale/busy는 owner profile을 변경/종결하지 않는다. expected external pause는 authwait 유지, unexpected pause/withdraw/reset은 취소. RUNNING은 structured cleanup 뒤 종료한다.
COMPLETED→측정성공, BLOCKED/STALE/RETRYABLE/PENDING/null/실패→실패, CancellationException→취소. MERGED는 새profile 생성/owner종결하지 않는다. factory/observer 오류는 업무 결과를 바꾸지 않는다.
기존 foundation AUTHORIZATION_HISTORY/START_HISTORY/TERMINAL_HISTORY는 실제 호출하지 않으므로 미측정0으로 두고 V1 journal을 재명명하지 않는다. 기존 debug optin/고정24행/숫자enum만/메모리만/명시copy/release미노출 보존.
```

## 3. 작업

### T1: 실제 요청 범위만 읽는 수동 HC adapter

- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `android/app/src/main/java/com/ny/my/health/HealthConnectReader.kt` | 수정 | HealthReader 보존·ScopedHealthReader·private filterable read |
  | `android/core/src/main/kotlin/com/ny/my/health/core/Ports.kt` | 수정 | typed SourceReadException 고정 사유 |
  | `android/core/src/main/kotlin/com/ny/my/health/core/DestinationSync.kt` | 수정 | typed source failure만 정확 사유로 저장 |
  | `android/app/src/test/java/com/ny/my/health/HealthConnectReaderTest.kt` | 수정 | category별 실제 query·revocation·기존 budget/overlap 회귀 |
  | `android/core/src/test/kotlin/com/ny/my/health/core/DestinationSyncTest.kt` | 수정 | source limit/access reason·미확인 보존 |

- **인터페이스**: 상위 2절 `ManualDriveContracts` 참조.
- **결정 로직**:

  ```
  요청 category와 현재 grant 교집합만 read 허용 → 나머지는 BLOCKED
  MANUAL/current grant → EXPLICIT_DAY; automatic trigger → BLOCKED
  page/sample 상한 초과 → SOURCE_LIMIT_EXCEEDED; revoked grant → SOURCE_ACCESS_REQUIRED
  generic source failure → SOURCE_READ_FAILED; 어느 오류도 EMPTY/부분성공으로 치환하지 않음
  ```
- **작성할 테스트**:
  - 신규/수정: `Scoped manual read queries only requested current grants and revoked access never becomes empty`.
  - 신규/수정: `Scoped source limits remain blocked and legacy pagination overlap and CSV remain unchanged`.
  - 유닛/HC adapter 통합: 실제 HealthConnectClient 경계 test double에 전달된 recordType/aggregate metric/query를 검사한다. STEPS-only에 weight/HR/sleep/exercise query 0, HR-only·혼합·grant 철회·unknown SDK·요청 BLOCKED를 포함한다. 기존 pagination/repeated token/oversize/page100×1000/sample100000 및 interval overlap tests를 재사용한다.
- **완료 기준**:
  - 배정된 조건·기대 결과를 단언한 focused tests 통과. instrumentation은 실제 실행 또는 최종 동일 head CI 대기 상태를 명확히 기록하며 compile만으로 done 처리하지 않는다.
- **검증 방법** (android/ 기준):

  ```bash
  ./gradlew :core:test --tests com.ny.my.health.core.DestinationSyncTest :app:testDebugUnitTest --tests com.ny.my.health.HealthConnectReaderTest
  ```
- **주의사항**: 기존 read(date)·daily CSV·aggregate priority·start-time overlap 의미를 바꾸지 않는다. 전체 read 후 결과를 필터링하는 구현은 실패다. HC 실제 provider completeness는 미확인이다. 추정이 틀리거나 인터페이스가 충돌하면 우회/skip/단언 완화하지 말고 blocked와 증거를 root에 보고한다.

### T2: 계정과 동일 token의 Drive 사용자 증명

- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `android/app/src/main/java/com/ny/my/health/BoundDriveAuthorization.kt` | 신규 | plain account/ticket 판정·memory credential·about proof |
  | `android/core/src/main/kotlin/com/ny/my/health/core/DrivePrincipalProof.kt` | 신규 | 기존 JSON 의존성으로 SDK-free exact principal proof predicate |
  | `android/app/src/main/java/com/ny/my/health/GoogleDriveTransport.kt` | 수정 | exact GET about fields만 추가 허용 |
  | `android/app/src/test/java/com/ny/my/health/BoundDriveAuthorizationTest.kt` | 신규 | synthetic strings로 picker/result/proof 판정 |
  | `android/app/src/test/java/com/ny/my/health/GoogleDriveTransportTest.kt` | 수정 | local TLS about request·host/path/method/query 거부 |

- **인터페이스**: 상위 2절 `ManualDriveContracts` 참조.
- **결정 로직**:

  ```
  RESULT_OK+current ticket+유효 name/type → 선택 후보; 다른 결과 → 현재 binding 보존/차단
  name/type 각각 UTF8 1..1024bytes, encode 가능, ISOcontrol 없음; type=com.google
  resolution/token/scope 불충분 또는 stale generation/ticket → 미승인
  about user.emailAddress exact selected name 및 me=true → 그 ticket/token만 승인
  missing/mismatch/HTTP/parse failure → 승인 없음; 계정 fallback 없음
  ```
- **작성할 테스트**:
  - 신규/수정: `Same issued token about proof must exactly match selected account before any raw health read`.
  - 신규/수정: `Picker cancellation malformed values and stale ticket never configure or authorize`.
  - 유닛: byte1024/1025·멀티바이트·blank/control/invalid UTF16·wrong type·cancel/null extras·late ticket·missing scope/token·resolution 분할. local TLS 통합: 동일 bearer+exact encoded fields·GET body 없음, malformed/missing user/me/email·401/403/429/5xx·redirect/cancel/1MiB 초과. Android Account 객체는 JVM 판정 fixture에서 생성하지 않는다.
- **완료 기준**:
  - 배정된 조건·기대 결과를 단언한 focused tests 통과. instrumentation은 실제 실행 또는 최종 동일 head CI 대기 상태를 명확히 기록하며 compile만으로 done 처리하지 않는다.
- **검증 방법** (android/ 기준):

  ```bash
  ./gradlew :app:testDebugUnitTest --tests com.ny.my.health.BoundDriveAuthorizationTest --tests com.ny.my.health.GoogleDriveTransportTest
  ```
- **주의사항**: 확인: auth21.4 setAccount 사용 가능, AuthorizationResult에 account identity 보장 없음, about는 drive.file 허용(상위 확인 근거). token의 toString/원인/로그 누출 금지. endpoint를 임의 Drive path로 넓히지 않는다. 실제 로그인은 수행하지 않는다. 추정이 틀리거나 인터페이스가 충돌하면 우회/skip/단언 완화하지 말고 blocked와 증거를 root에 보고한다.

체크포인트 2: root가 누적 source diff/컴파일/배정된 의미 검증을 확인한다. actual Native는 동일 최종 head gate까지 pending을 보존하고 disjoint T의 authoring은 계속할 수 있다. 실패와 계획 보정은 복구 기록에 누적한다.

### T3: journal 이후 쓰기와 읽기 전용 Drive 재조정

- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `android/core/src/main/kotlin/com/ny/my/health/core/Ports.kt` | 수정 | DriveUploader member upsertTracked 기본 구현 |
  | `android/core/src/main/kotlin/com/ny/my/health/core/PrivateDriveUploader.kt` | 수정 | legacy batch 공용경계·reconcileTracked/writeTrackedPending |
  | `android/core/src/test/kotlin/com/ny/my/health/core/PrivateDriveBatchTest.kt` | 수정 | typed callback/root once/2 writes/cancel 회귀 |
  | `android/core/src/test/kotlin/com/ny/my/health/core/PrivateDriveReconciliationTest.kt` | 신규 | current metadata·known targets·checksum·unknown create |

- **인터페이스**: 상위 2절 `ManualDriveContracts` 참조.
- **결정 로직**:

  ```
  current root/dataset/file exact safe identity+fresh exact checksum → 재사용/adoption
  checksum absent/invalid/mismatch+safe existing ID → pending 같은-ID PATCH
  known ID 부재/변경/충돌·unsafe·ambiguous/incomplete lookup → blocked
  no existing safe target+createMayHaveSucceeded → reconciliation required, POST 금지
  fresh no-known/no-uncertainty 부재 → pending; create는 writePending에서만
  fresh ACK 2xx+전체 safe metadata+same ID → 확인; present wrong checksum → 확인 금지
  LOOKUP/PATCH IOException/429/500/502/503/504 → transient
  401/403 → account blocked; ambiguous POST/bad mutation response → reconciliation required
  ```
- **작성할 테스트**:
  - 신규/수정: `Drive reconciliation never writes and unknown folder or file creation never blind posts`.
  - 신규/수정: `Fresh Drive ACK permits absent checksum but rejects wrong checksum and changed target identity`.
  - 유닛/transport boundary: reconcile의 POST/PATCH 0, root/dataset/file 각각 unknown POST 실패 뒤 다음 lookup 부재시 create 0, known ID missing/moved/deleted/shared/duplicate/incomplete, exact checksum reuse·absence PATCH·wrong digest ACK 거부. 기존 core privacy·deterministic CSV 및 batch callback 순서/최대2/cancellation 재사용.
- **완료 기준**:
  - 배정된 조건·기대 결과를 단언한 focused tests 통과. instrumentation은 실제 실행 또는 최종 동일 head CI 대기 상태를 명확히 기록하며 compile만으로 done 처리하지 않는다.
- **검증 방법** (android/ 기준):

  ```bash
  ./gradlew -PcoreOnly :core:test --tests com.ny.my.health.core.PrivateDriveBatchTest --tests com.ny.my.health.core.PrivateDriveReconciliationTest --tests com.ny.my.health.core.PrivateDriveUploaderTest
  ```
- **주의사항**: T1 완료 후 시작(Ports 파일 겹침). reconcile pending 입력은 값 그대로 반환하고 exact FileKey/digest/known IDs의 단일 single-use prepared batch context로 target을 유지한다. 같은 batch의 fresh root/parents/file 검증을 재사용하며 성공/실패/새 reconcile에 clear한다. write전 exact 입력 일치를 검사하고 reservation 누락/변경이면 fail closed다. 완전 canonical path의 unique ID로 모든 known ID 일치가 증명되면 추가 GET은 강제하지 않는다. read-only reconcile에서 appFolder() 호출 금지. root/dataset creation도 RUNNING 이후이며 failure uncertainty를 파일 journal에 보존한다. PR15 root-once-perbatch/동시2/직렬 callback/자식 종료를 회귀시킨다. remote deletion/media GET/새 retry loop 없음. 추정이 틀리거나 인터페이스가 충돌하면 우회/skip/단언 완화하지 말고 blocked와 증거를 root에 보고한다.

### T4: 현재 세대의 target 증거와 명시 복구 원자성

- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `android/app/src/main/java/com/ny/my/health/SqliteSyncStateStore.kt` | 수정 | app-only all-profile known-ID read·eligible blocker reopen |
  | `android/app/src/androidTest/java/com/ny/my/health/ManualDriveStoreInstrumentedTest.kt` | 신규 | 실제 SQLite reopen/CAS/fault/uncertainty 회귀 |

- **인터페이스**: 상위 2절 `ManualDriveContracts` 참조.
- **결정 로직**:

  ```
  현재 generation/date 모든 profile current/prior IDs → dataset별 Set; stale/오류 → 실패
  명시 recovery+finished/no attempt+matching generation → eligible blocker만 제거; BLOCKED day는 PENDING
  RUNNING attempt·stale generation·invalid DB → mutation 거부
  file phase/handles/prior/revision/REMOTE_STALE/unknown intent → 보존
  ```
- **작성할 테스트**:
  - 신규/수정: `All active scope known and prior handles survive reopen and conflicting targets block`.
  - 신규/수정: `Explicit manual recovery reopens eligible blockers without deleting file uncertainty or remote stale`.
  - 실제 SQLite 통합: 두 scope의 current/prior 동일·충돌IDs, 다른 날짜/세대 제외, malformed 다른 profile/orphan/unsupported/unavailable 거부. blocker4종 각각 reopen 후 planner 재선택, SOURCE/HISTORY blocker 보존, finished attempt 불변·RUNNING 거부, beforeCommit failure/reopen 동등 상태·generation CAS·REMOTE_STALE 보존. 전체 SyncState encode validation을 통과한 결과만 commit한다.
- **완료 기준**:
  - 배정된 조건·기대 결과를 단언한 focused tests 통과. instrumentation은 실제 실행 또는 최종 동일 head CI 대기 상태를 명확히 기록하며 compile만으로 done 처리하지 않는다.
- **검증 방법** (android/ 기준):

  ```bash
  ./gradlew :app:assembleDebugAndroidTest
  ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.ny.my.health.ManualDriveStoreInstrumentedTest
  ```
- **주의사항**: 기존 validated write/pathLock/decode를 사용하며 column/schema 변경 없음. 전체 history/archived binding 추정/N+1 query 도입 금지. native compile과 actual SQLite PASS를 구별한다. fresh proof/explicit continue/gate 소유 호출은 T7에서 연결한다. 추정이 틀리거나 인터페이스가 충돌하면 우회/skip/단언 완화하지 말고 blocked와 증거를 root에 보고한다.

체크포인트 4: root가 누적 source diff/컴파일/배정된 의미 검증을 확인한다. actual Native는 동일 최종 head gate까지 pending을 보존하고 disjoint T의 authoring은 계속할 수 있다. 실패와 계획 보정은 복구 기록에 누적한다.

### T5: Drive destination의 typed 확인 연결

- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `android/app/src/main/java/com/ny/my/health/DriveApiDestination.kt` | 신규 | StorageDestination→known IDs·read-only reconcile·typed write |
  | `android/app/src/test/java/com/ny/my/health/DriveApiDestinationTest.kt` | 신규 | local TLS로 fresh lookup/ACK/unknown 복구 계약 |
  | `android/app/src/main/java/com/ny/my/health/GoogleDriveTransport.kt` | 수정 | GET 503 server-directed 숨은 재시도 차단 |
  | `android/app/src/test/java/com/ny/my/health/GoogleDriveTransportTest.kt` | 수정 | GET Retry-After:0 응답도 한 번만 전송 회귀 |

- **인터페이스**: 상위 2절 `ManualDriveContracts` 참조.
- **결정 로직**:

  ```
  CsvFile와 planned FileKey exact pair → confirmation
  서로 다른 날짜 batch 또는 mismatched callback → fail closed
  all-profile known IDs 충돌 → blocked; unsafe prior ID를 새 deterministic file로 대체하지 않음
  ```
- **호출 순서**:

  ```
  1. known targets 읽기→fresh read-only reconcile; 오류는 고정 destination reason으로 전파
  2. core가 RUNNING commit한 뒤 writePending 호출; callback마다 exact FileConfirmation 전달
  ```
- **작성할 테스트**:
  - 신규/수정: `Drive tracked batches preserve private target validation deterministic same file and CSV schema`.
  - 신규/수정: `After partial failure cancellation or unknown create reconciliation retries only pending files`.
  - local TLS 통합: fresh metadata checksum reuse write0, absent checksum safe PATCH, partial confirmation 뒤 실패/취소→다음 run pending만 재조정, unknown root/dataset/file creation→부재시 POST0, HTTP 분류와 redacted errors. synthetic known-ID supplier로 source/Drive protocol 검증; DB 자체는 T4 실제 테스트를 근거로 한다.
- **완료 기준**:
  - 배정된 조건·기대 결과를 단언한 focused tests 통과. instrumentation은 실제 실행 또는 최종 동일 head CI 대기 상태를 명확히 기록하며 compile만으로 done 처리하지 않는다.
- **검증 방법** (android/ 기준):

  ```bash
  ./gradlew :app:testDebugUnitTest --tests com.ny.my.health.DriveApiDestinationTest
  ```
- **주의사항**: T3/T4 완료 후 시작. adapter는 generation+date를 store read에 전달하고 토큰을 persist하지 않는다. P0 upsert를 V1 reconcile 경로로 직접 호출하여 blind create하지 않는다. fresh same-ID ACK checksum 부재는 승인 계약대로 허용한다. 추정이 틀리거나 인터페이스가 충돌하면 우회/skip/단언 완화하지 말고 blocked와 증거를 root에 보고한다.

### T6: gate 소유자만 실행 credential을 준비

- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `android/app/src/main/java/com/ny/my/health/SyncExecutionSession.kt` | 수정 | nullable legacy engine·runPrepared owner-only local engine |
  | `android/app/src/androidTest/java/com/ny/my/health/SyncExecutionSessionInstrumentedTest.kt` | 수정 | 실제 SQLite gate·prepare 순서·busy/취소/실패 회귀 |

- **인터페이스**: 상위 2절 `ManualDriveContracts` 참조.
- **결정 로직**:

  ```
  gate 획득 실패 → trigger merge만; prepare 호출/credential 소비 없음
  production engine=null+prepare 누락 → raw read 전 fail closed
  prepare failure/cancel → cleanup 및 gate 반환; engine registry 보관 없음
  ```
- **호출 순서**:

  ```
  1. gate owner 등록→recover; 실패 시 raw read 없이 cleanup
  2. owner-only prepare; 실패 시 원인/credential 로그 없이 cleanup
  3. access/planner→attempt start transaction→local engine.run→기존 cleanup
  ```
- **작성할 테스트**:
  - 신규/수정: `Prepared session admits one credential factory and busy callers only merge triggers`.
  - 실제 SQLite 통합: prepare를 barrier로 멈추고 두번째 runPrepared의 factory0·pending receipt, read0 before proof, prepare 실패/취소 후 재진입, generation 변경 뒤 stale, legacy run non-null engine 회귀. 현재 Session12 및 store/process suites를 유지한다.
- **완료 기준**:
  - 배정된 조건·기대 결과를 단언한 focused tests 통과. instrumentation은 실제 실행 또는 최종 동일 head CI 대기 상태를 명확히 기록하며 compile만으로 done 처리하지 않는다.
- **검증 방법** (android/ 기준):

  ```bash
  ./gradlew :app:assembleDebugAndroidTest
  ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.ny.my.health.SyncExecutionSessionInstrumentedTest
  ```
- **주의사항**: T4 완료 후 시작. prepare는 gate/owner 등록/recover 이후 access·planner·raw health 이전에 실행한다. local engine은 run 종료 후 유지하지 않는다. registry/path gate 구조와 cleanup NonCancellable 경계를 바꾸지 않는다. 추정이 틀리거나 인터페이스가 충돌하면 우회/skip/단언 완화하지 말고 blocked와 증거를 root에 보고한다.

체크포인트 6: root가 누적 source diff/컴파일/배정된 의미 검증을 확인한다. actual Native는 동일 최종 head gate까지 pending을 보존하고 disjoint T의 authoring은 계속할 수 있다. 실패와 계획 보정은 복구 기록에 누적한다.

### T7: 명시 계속으로만 전경 수동 batch 실행

- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `android/app/src/main/java/com/ny/my/health/ManualDriveSession.kt` | 신규 | credential-free UI state·configure/reset·per-run proof·controller |
  | `android/app/src/test/java/com/ny/my/health/ManualDriveSessionTest.kt` | 신규 | synthetic auth/health/destination 경계로 전경 상태 검증 |

- **인터페이스**: 상위 2절 `ManualDriveContracts` 참조.
- **결정 로직**:

  ```
  시작/복원/외부 consent 복귀 → metadata 표시 또는 ready만, raw read/upload 없음
  명시 continue+전경+fresh ticket/binding → runPrepared 허용
  전후 about binding/gen/ticket/foreground 변경 → raw read 전에 차단
  계정 변경 → 새 동의/configure CAS; 같은 목적지 retry → 기존 generation 유지
  reset 성공 → automatic off+fresh consent; 실패 → 성공 표시 금지
  ```
- **호출 순서**:

  ```
  1. 명시 continue→runPrepared 진입; busy는 factory 없이 merge
  2. owner prepare에서 current binding/ticket/gen/foreground 확인→같은 token about 조회
  3. proof 뒤 current binding/ticket/gen/foreground 재확인; 실패 시 raw read 없이 거부
  4. reopenManualBlockers 단일 transaction→같은 reader/store engine 반환
  5. 완료/실패/취소에 private credential 폐기; store/UiState에는 전달하지 않음
  ```
- **작성할 테스트**:
  - 신규/수정: `Account picker selection binds all authorization and account removal never falls back`.
  - 신규/수정: `Manual configuration and reset use generation CAS and never erase foundation history`.
  - 신규/수정: `Manual credentials never enter saved state store diagnostics or public fixtures`.
  - 유닛/합성 경계: configure KST yesterday/categories/grant0 거부, consent return0 reads, explicit continue1 batch, busy double continue credential replace0, pause/reset/change during proof stale, missing/wrong account0 raw reads, retry preserves unknown flags, history deletion/reset 경로 분리, startup refresh metadata-only. pure 판정과 orchestration은 fake ports로 검증하며 실 DB 증거를 T4/T6으로 둔다.
- **완료 기준**:
  - 배정된 조건·기대 결과를 단언한 focused tests 통과. instrumentation은 실제 실행 또는 최종 동일 head CI 대기 상태를 명확히 기록하며 compile만으로 done 처리하지 않는다.
- **검증 방법** (android/ 기준):

  ```bash
  ./gradlew :app:testDebugUnitTest --tests com.ny.my.health.ManualDriveSessionTest
  ```
- **주의사항**: T2/T5/T6 완료 후 시작. private non-data credential을 StateFlow/SavedState에서 제외한다. owner prepare 안 fresh same-token proof→reopenManualBlockers→같은 store/reader의 engine 생성 순서다. grant0에서는 auth 요청도 차단한다. 자동/SAF/실계정 실행은 추가하지 않는다. 추정이 틀리거나 인터페이스가 충돌하면 우회/skip/단언 완화하지 말고 blocked와 증거를 root에 보고한다.

### T8: 수동 구성과 복구 화면 합성

- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `android/app/src/main/java/com/ny/my/health/ManualDriveUi.kt` | 신규 | 작은 수동 상태/actions·pure projection·별도 reset 확인 |
  | `android/app/src/main/java/com/ny/my/health/HealthHomeScreen.kt` | 수정 | optional manual state/actions와 과거 foundation history 표시 |
  | `android/app/src/androidTest/java/com/ny/my/health/ManualDriveUiInstrumentedTest.kt` | 신규 | light/dark/large text 새 수동 흐름 semantics |
  | `android/app/src/androidTest/java/com/ny/my/health/HealthHomeScreenInstrumentedTest.kt` | 수정 | default legacy component와 safe insets 회귀 유지 |

- **인터페이스**: 상위 2절 `ManualDriveContracts` 참조.
- **결정 로직**:

  ```
  EMPTY/partial/blocked/stale/prior success → 서로 다른 상태·다음 허용 동작
  legacy foundation history → 이전 이력, V1 current coverage로 표시 금지
  reset dialog → config/coverage만, remote 삭제/grant 철회 성공 주장 없음
  ```
- **작성할 테스트**:
  - 신규/수정: `Manual consent picker authorization and explicit continue remain reachable in light dark and large text`.
  - Compose E2E 합성 state/actions: 미구성 consent/account 선택, fresh auth/ready/continue, blocked recovery, reset cancel/confirm, zero grants disabled. light/dark/fontScale1.8에서 스크롤·semantics 접근; 기존 top title/date safe insets/scroll tests 유지. 자동 toggle/SAF 기능은 이 PR에 완료로 표시하지 않는다.
- **완료 기준**:
  - 배정된 조건·기대 결과를 단언한 focused tests 통과. instrumentation은 실제 실행 또는 최종 동일 head CI 대기 상태를 명확히 기록하며 compile만으로 done 처리하지 않는다.
- **검증 방법** (android/ 기준):

  ```bash
  ./gradlew :app:assembleDebugAndroidTest
  ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.ny.my.health.ManualDriveUiInstrumentedTest,com.ny.my.health.HealthHomeScreenInstrumentedTest
  ```
- **주의사항**: T7 완료 후 시작. optional defaults로 기존 pure screen test 경로 보존. foundation 이력 삭제는 기존 semantics를 유지한다. 실제 TalkBack/Galaxy pending이며 synthetic screenshot을 실기기로 부르지 않는다. 추정이 틀리거나 인터페이스가 충돌하면 우회/skip/단언 완화하지 말고 blocked와 증거를 root에 보고한다.

### T9: 실제 Activity의 bound 경로와 통합 회귀

- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `android/app/src/main/java/com/ny/my/health/MainActivity.kt` | 수정 | always-show Google picker·setAccount bridge·외부 결과 명시 continue |
  | `android/app/src/main/java/com/ny/my/health/SyncViewModel.kt` | 수정 | ManualDriveSession 소유/위임·production engine=null session·pause 취소 |
  | `android/app/src/androidTest/java/com/ny/my/health/HealthAppInstrumentedTest.kt` | 수정 | 실제 Activity의 bound action 기대와 기존 layout 회귀 |
  | `android/app/src/androidTest/java/com/ny/my/health/DestinationConfigurationInstrumentedTest.kt` | 신규 | 실 DB configure/reset/선택 취소/저장실패 |
  | `android/app/src/androidTest/java/com/ny/my/health/ManualDriveFlowInstrumentedTest.kt` | 신규 | 합성 auth bridge와 실제 store/session lifecycle 흐름 |

- **인터페이스**: 상위 2절 `ManualDriveContracts` 참조.
- **결정 로직**:

  ```
  모든 실제 Activity Drive action → selected bound Account만 사용
  외부 result 현재 ticket/gen 불일치 → 무시; consent 복귀 → continue 대기
  zero HC grants → auth disabled; pause → credential 폐기/진행 취소
  ```
- **호출 순서**:

  ```
  1. 사용자 선택 요청→항상 표시 Google picker→현재 결과만 bridge로 전달
  2. setAccount+drive.file authorize→외부 resolution 결과 확인→ready만 표시
  3. 별도 continue→controller run; lifecycle pause/reset→ticket 폐기 및 실행 취소
  ```
- **작성할 테스트**:
  - 신규/수정: `Manual flow rejects stale proof pause reset and double continue while retaining legacy layout and history regressions`.
  - 신규/수정: `Consent account tree automatic manual retry and reset remain reachable in dark light and large text semantics`.
  - 실제 Android/SQLite 통합: synthetic auth bridge로 picker 취소/config save fail/consent/fresh auth/about proof/explicit continue, pause와 configuration reset 중 지연 결과, duplicate continue 단일 admission, reopen previous state, history reset 분리. 기존 HealthApp 화면 상단/스크롤/프로세스 tests를 broad mock으로 대체하지 않는다. 상위 UI 기준 중 tree/automatic은 후속 에픽 위치에 남긴다.
- **완료 기준**:
  - 배정된 조건·기대 결과를 단언한 focused tests 통과. instrumentation은 실제 실행 또는 최종 동일 head CI 대기 상태를 명확히 기록하며 compile만으로 done 처리하지 않는다.
- **검증 방법** (android/ 기준):

  ```bash
  ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest
  ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.notClass=com.ny.my.health.HistoryProcessRecoveryInstrumentedTest,com.ny.my.health.SyncStateProcessRecoveryInstrumentedTest
  ```
- **주의사항**: T8 완료 후 시작. Activity SDK Account 생성/authorize request bridge는 production에 두고 test는 injectable plain-value bridge로 통제한다. 기존 unbound Drive authorization/upload route를 실제 UI에서 남기지 않는다. 오래된 unbound-action 단언만 승인 spec 근거를 명시하여 바꾸고 레이아웃/history/zero-grant 단언을 약화하지 않는다. root 검토 뒤 세부 보정은 실행 보고에 기록한다. 추정이 틀리거나 인터페이스가 충돌하면 우회/skip/단언 완화하지 말고 blocked와 증거를 root에 보고한다.

### T10: 기존 강제종료 host 검증의 bound UI 호환

- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `android/app/src/androidTest/java/com/ny/my/health/HistoryProcessRecoveryInstrumentedTest.kt` | 수정 | 기존 assertManual의 명시 consent 및 disabled bound continue 검사 |

- **인터페이스**: 2절 `ManualDriveContracts` 참조.
- **결정 로직**: assertManual에서 첫 toggle 위치 대신 `V1 비공개 Drive 업로드 동의` semantics의 off를 검사한다. 항상 렌더되는 `업로드 계속`은 부재 대신 disabled를 검사한다. model.manual.state의 consent=false 및 phase가 READY/RUNNING이 아님을 검사한다. 기존 legacy opt-in/token/ticket/status와 복구/시스템 설정/cleanup 모든 단언은 유지한다.
- **작성할 테스트**: 기존 `reopenedActivityRestoresOnlyCommittedMetadata`, `liveSystemThemeAndFontChangeReachActivityAndControls` 및 host20을 재사용한다(출처: 기존 프로세스 복구·동의 비자동 실행 회귀, 실제 Android/host 층). 신규 동작이나 테스트 삭제·skip·허용적 assertion은 없다.
- **완료 기준**: testAPK compile와 최종 API35/36 기존 host20 및 killed hold PASS0을 유지한다. 컴파일만으로 actual 실행 성공을 주장하지 않는다.
- **검증 방법**: android에서 `./gradlew :app:assembleDebugAndroidTest`; 최종 CI의 `bash android/ci/verify-history-process-recovery.sh` 실제 결과 대조.
- **주의사항**: T9의 다섯 파일 제한을 보존하려고 누락된 host compatibility 한 파일만 별도 분리한다. 외부 grant/login/health 값/원격 쓰기는 없다. root가 T9 source를 통합한 뒤 compile하며 source authoring은 서로 다른 파일로 병렬 가능하다.

### T11: 복원 시 실제 권한 범위 coverage 투영

- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `android/app/src/main/java/com/ny/my/health/ManualDriveSession.kt` | 수정 | metadata-only scoped coverage |
  | `android/app/src/test/java/com/ny/my/health/ManualDriveSessionTest.kt` | 수정 | 복원·권한변경·stale 메타데이터 회귀 |
  | `android/app/src/androidTest/java/com/ny/my/health/ManualDriveFlowInstrumentedTest.kt` | 수정 | 실제SQLite scoped 복원 |

- **인터페이스**: 2절 `ManualDriveContracts` 참조.
- **결정 로직**: refresh/refreshGrants/구성 직후 실제 MANUAL access metadata와 한국 today로 store.loadForScope를 읽어 coverage를 투영한다. raw health/authorize/upload는0. grant 변경은 새 profile로 gap/excluded/blocked를 정확히 보여주고 null scope를 마지막 current scope처럼 표시하지 않는다. metadata await 중 ticket/gen/owner 변경 시 최신 상태를 지우지 않는다. 기존 state 필드를 사용하며 raw health 새 상태/DBschema는 없다.
- **작성할 테스트**: 기존 grouped 회귀와 실제 SQLite에 scoped EMPTY/confirmed/blocked metadata를 저장→새 controller refresh에서 pending/blocked/current checked/uploaded/recent/excluded를 확인한다. grant 변경/0grant·stalegen·DB실패·healthraw0·Drive0. 자동 off 시작 감지/표시 기준(①③④⑤/유닛+Native).
- **완료 기준**: 배정 focused/root 통합 검증 및 최종 실제 API35/36 Native 결과. 컴파일만으로 actual 통과를 주장하지 않는다.
- **검증 방법**: android/에서 `./gradlew :app:testDebugUnitTest --tests com.ny.my.health.ManualDriveSessionTest :app:assembleDebugAndroidTest`.
- **주의사항**: T14와 ManualDriveSession/NativeFlow 파일이 겹치므로 T11 완료 후 T14 authoring한다. VMdraft/UI API는 바꾸지 않는다. 기존 단언·CSV·취소·journal·credential 경계를 약화하지 않는다.

### T12: 마지막 업로드와 최근 대상 날짜 표시

- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `android/app/src/main/java/com/ny/my/health/ManualDriveUi.kt` | 수정 | safe KST timestamp와 최근 대상 날짜 확인/업로드 |
  | `android/app/src/main/java/com/ny/my/health/HealthHomeScreen.kt` | 수정 | 기준 한국 날짜 전달 |
  | `android/app/src/androidTest/java/com/ny/my/health/ManualDriveUiInstrumentedTest.kt` | 수정 | 미확인·오래됨·최근 날짜 구분 |

- **인터페이스**: 2절 `ManualDriveContracts` 참조.
- **결정 로직**: 기존 coverage.lastUploadAt은 active generation 전체 profile 최대라는 한계를 표시하고 현재 scope refresh 시각으로 과장하지 않는다. 주입된 한국 today와 enrolledDay로 최근 eligible {today-2,today-1}의 recentCheckedDays/recentUploadedDays를 각각 표시한다. 이는 최근 대상 날짜 확인/업로드 여부이며 오늘 다시 갱신되었다는 뜻은 아니다. raw health 새 필드 불필요, pure projection 유지.
- **작성할 테스트**: 기존 light/dark/large semantics test를 재사용하며 같은 through값이나 최근 set 다른 fixture, lastUploadAt 있음/없음, partial/EMPTY(읽기확인과 업로드확인 다름), stale/미확인 문구를 단언(①②④/Compose).
- **완료 기준**: 배정 focused/root 통합 검증 및 최종 실제 API35/36 Native 결과. 컴파일만으로 actual 통과를 주장하지 않는다.
- **검증 방법**: android/에서 `./gradlew :app:assembleDebugAndroidTest`.
- **주의사항**: T11/T13과 파일겹침 없어 병렬 가능. 선택·동의·reset·legacyhistory·상단safeinset 보존; 단순문구를 위하여 미실행 성공을 만들지 않는다. 기존 단언·CSV·취소·journal·credential 경계를 약화하지 않는다.

### T13: 실제 core 처리 단계 성능 계측 복원

- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `android/core/src/main/kotlin/com/ny/my/health/core/DestinationSync.kt` | 수정 | optional collector/real read·Drive |
  | `android/core/src/main/kotlin/com/ny/my/health/core/SyncRevision.kt` | 수정 | real normalization/export |
  | `android/core/src/test/kotlin/com/ny/my/health/core/DestinationSyncTest.kt` | 수정 | 실제stage/성공실패취소/null호환 |

- **인터페이스**: 2절 `ProfilingRepairContracts` 참조.
- **결정 로직**: 선언된 optional collector로 실제 수행 함수만 감싼다. 두 normalize/export projection은 숨기지 않는다. 예외를 swallow하거나 Drive 실패를 계측성공으로 바꾸지 않는다.
- **작성할 테스트**: 실제 정상 read1/normalize2/export2, EMPTY Drive0, read/Drive failure·cancel active0, null collector와 결과/CSV/revision 동등. 기존 batch profiling 회귀 재사용(①③④/유닛).
- **완료 기준**: 배정 focused/root 통합 검증 및 최종 실제 API35/36 Native 결과. 컴파일만으로 actual 통과를 주장하지 않는다.
- **검증 방법**: android/에서 `./gradlew -PcoreOnly :core:test --tests com.ny.my.health.core.DestinationSyncTest --tests com.ny.my.health.core.PrivateDriveBatchTest --tests com.ny.my.health.core.SyncProfilingTest`.
- **주의사항**: T11/T12와 파일겹침 없어 병렬 authoring. 새 enum/summary행/journal 계측은 없다. 기존 단언·CSV·취소·journal·credential 경계를 약화하지 않는다.

### T14: bound 실행의 휴대폰 진단 수명 연결

- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `android/app/src/main/java/com/ny/my/health/ManualDriveSession.kt` | 수정 | ticket/owner profile와productioncollector |
  | `android/app/src/main/java/com/ny/my/health/SyncViewModel.kt` | 수정 | debug factory/phone state begin complete clear |
  | `android/app/src/test/java/com/ny/my/health/ManualDriveSessionTest.kt` | 수정 | busy stale failure cancel profile |
  | `android/app/src/androidTest/java/com/ny/my/health/ManualDriveFlowInstrumentedTest.kt` | 수정 | 실제VM bound 경로 report |

- **인터페이스**: 2절 `ProfilingRepairContracts` 참조.
- **결정 로직**: 실제 유효 auth ticket부터 profile 생성하고 owner-only execution/끝난 cleanup 후 finish. VM은 기존 safe 보고·copy 경로를 사용한다. callback/factory 실패가 sync 결과를 바꾸지 않고 stale/busy 요청은 현재profile에 간섭하지 않는다. release factory null.
- **작성할 테스트**: 현재 bound VM 실제경로(realSQLite+session, syntheticHC/transport) 완료·실패·취소시 state.phoneProfiling.report 갱신/새시도이전보고폐기, uploader같은collector request/operation관측. 기존legacy업로드를 대신 호출하지 않는다. grouped 성공EMPTY/proof실패/권한/Drive실패/pause/reset/철회/externalwait/lateA/merged/observerthrow/null 및 기존formatter/redaction/copy/release 회귀(①②③④⑤/유닛+Native).
- **완료 기준**: 배정 focused/root 통합 검증 및 최종 실제 API35/36 Native 결과. 컴파일만으로 actual 통과를 주장하지 않는다.
- **검증 방법**: android/에서 `./gradlew :app:testDebugUnitTest --tests com.ny.my.health.ManualDriveSessionTest --tests com.ny.my.health.ManualSyncProfilingTest --tests com.ny.my.health.PhoneProfilingSessionTest :app:assembleDebugAndroidTest`.
- **주의사항**: T11/T13 통합 후 시작. 기존 VM Application constructor와productionfactory 유지; 필요시 같은4파일내 actualsession factory seam만 추가하며 새로운 raw state/실계정/디스크metric은 금지. 기존 단언·CSV·취소·journal·credential 경계를 약화하지 않는다.

### T15: 양 API 에뮬레이터 userdata 디스크 확보

- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `.github/workflows/android-emulator-tests.yml` | 수정 | 기존 unused toolchain cleanup을 API35/36 모두 적용 |

- **결정 로직**: 기존 Reserve disk step의 API36 if 조건만 제거하고 이름을 두 API 대상으로 변경한다. 기존 Kotlin build에 쓰지 않는 dotnet/NDK 제거 경로·안전한 ANDROID_HOME 검증·df는 보존한다. 테스트/버전/권한/timeout/host 단언을 바꾸지 않는다.
- **작성할 테스트**: 추가 제품 테스트 없음(CI-only). 실제원인 job131873397235의userdata avail7074.67MB<need7372.80MB와 10분 boot timeout을 근거로 기존 성공한API36 환경준비를 API35에도 적용한다.
- **완료 기준**: YAML/static diff 확인, 최종API35/36 CI의부팅및원래전체Native/host성공. 부팅전실패를제품assertion실패로세지않는다.
- **검증 방법**: 기존 YAML파서 또는 정적검사; GitHub최종head의기존전체CI.
- **주의사항**: 한 파일만 수정하며 새 cleanup대상/permission/skip/externalgrant를도입하지않는다. 다른codeauthoring과파일겹침없어병렬가능.

### T16: 실제 제목을 유일하게 지정하는 UI 회귀 선택자

- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `android/app/src/androidTest/java/com/ny/my/health/HealthAppInstrumentedTest.kt` | 수정 | 제목 exact selector 두곳 |

- **결정 로직**: firstLaunch와backgroundAndReturn에서 onNodeWithText("마지막 업로드 확인",substring=true)를정확한제목매칭으로바꾼다. assertExists/disabled/노업로드/노인증/geometry모든단언은그대로다.
- **작성할 테스트**: 기존두Native를재사용. c334 actualAPI35 120중2실패/0error/0skip 원본XML이두노드매칭(설명+실제제목)을증명한다. production문구나검증기대는바꾸지않는다.
- **완료 기준**: testAPK컴파일/최종양API120전체와host실행, exactselector가유일한제목을검증함.
- **검증 방법**: ./gradlew :app:assembleDebugAndroidTest 및양API기존전체CI.
- **주의사항**: 한파일두selector만. 테스트삭제/skip/firstnode선택/허용적카운트금지.

### T17: 동일 전체 검증의 핵심 화면 fail-fast 분할

- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `.github/workflows/android-emulator-tests.yml` | 수정 | 핵심4class를먼저실행하고나머지전체를뒤에실행 |
  | `android/ci/run-emulator-validation.sh` | 신규 | 단일 bash 프로세스에서 준비·핵심·나머지·host 순서 실행 |

- **결정 로직**: 같은API35/36/동일APK로 HealthAppInstrumentedTest,ManualDriveFlowInstrumentedTest,ManualDriveUiInstrumentedTest,DestinationConfigurationInstrumentedTest를첫connected class인자로실행한다. 성공XML/추가출력을별도critical디렉터리로보존한뒤 두번째connected는기존hold2개와이미실행한4개class만notClass로제외해나머지전체를각각한번실행한다. 그뒤기존host2개그대로. failure는즉시중단/alwaysartifact로실패증거보존. APK/Gradle/SDK/version/timeout/permissions/디스크준비불변. 추가한critical증거path를artifact에포함한다.
- **작성할 테스트**: CIonly. 이전전체에서실행되던각class가critical또는remainder정확히한곳에있고기존hold만일반발견에서제외되는정적검사. 스크립트bash -n/기존host명령보존. root최종evidence는두XML의유일class+method합으로수집해중복PASS금지.
- **완료 기준**: 최종양API critical+remainder총Native/host모두PASS. focused성공만전체성공으로표시금지.
- **검증 방법**: YAML/bashstatic 및GitHub실제전체실행.
- **주의사항**: testordering만조정하며테스트범위/단언/재시도는바꾸지않는다. provider별도branch optionaldispatch모드는후속통합시이full분기를유지한다.

## 4. 검증

최종 체크포인트(T9 이후): source 동결 head에서 root가 다음 순서로 실행하고 실제 XML/CI 원본 결과와 source head를 실행 보고에 기록한다. 계획 문서 작성 중 테스트/로그인/원격 쓰기는 실행하지 않았다.

```bash
cd android
./gradlew -PcoreOnly :core:test
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.notClass=com.ny.my.health.HistoryProcessRecoveryInstrumentedTest,com.ny.my.health.SyncStateProcessRecoveryInstrumentedTest
cd ..
bash android/ci/verify-history-process-recovery.sh
bash android/ci/verify-sync-state-process-recovery.sh
```

API35/36 기존 CI를 사용한다. 기존 history host20/SyncState host17 verifier+17 cleanup/killed hold PASS0과 기존 Native106을 보존하고 실제 새 전체 test 수를 보고한다. Native106은 baseline이며 신규 suite가 추가된 총수를 미리 주장하지 않는다. 실제 SQLite/current native gate/host를 mocks나 JVM으로 대체하지 않는다. 배치 privacy/root once/bounded2/cancellation, CSV v0.1/overlap/page/sample, PR14 상단 title/date와 foundation history 회귀를 함께 확인한다. fixture에는 합성 값만 쓰고 공개 로그/artifact에 raw health/CSV/token/account/device/document/URI/provider 본문을 넣지 않는다.

새 dependency 없음: Gradle 변경/새 audit 도입은 이 PR 범위가 아니다. 기존 보안 checklist의 해당 Android 항목(credential 메모리·minimum scope·HTTPS allowlist·backup 제외·표준 SDK consent·DB parameter binding·redacted error)을 최신 diff에서 점검한다. OWASP password 기록의 30일 freshness는 이번 비밀번호 없는 흐름의 암호 기본값 근거로 사용하지 않는다. 의존성의 새 취약성 신호가 생기면 기존 resolved audit/도달성 기준으로 별도 보고한다.

최신 전체 diff/head를 author/fixer와 다른 fresh-context read-only 세션이 독립 적합성·코드·보안 검토한다. spec conformance에는 approved spec+현재 구현만 주고 plan/이전 verdict를 주지 않는다. 한국어 PR 제목/설명/댓글에 reviewed head·findings/severity·검증/한계·최종 병합 적합성을 기록하고 차단 수정 뒤 새 head 재검토한다. owner 자동승인에 따른 개발 병합과 실제 수용/출시 승인을 구분한다. ADR007 Proposed는 actual conformance 전 바꾸지 않는다.

실계정 AccountPicker/consent/drive.file-only about population·별칭/계정 제거·Google 실제 응답 checksum, Galaxy/TalkBack/Doze/배터리/실제 provider는 pending이다. 기존 P0 acceptance와 recoverable-key backup 불확실성을 보존한다. 자동 worker·SAF·통합 full coverage 화면은 후속 계획이며 이번 PR의 합성 증거로 완료 처리하지 않는다.

## 5. 롤백

개발 변경은 PR revert로 되돌린다. noBackup state/unknown intent/confirmed metadata와 foundation history를 임의 삭제하지 않는다. 사용자 명시 config reset은 별도 동의·durable CAS 경계이며 원격 파일/외부 grant를 삭제하지 않는다. 이미 발행한 remote writes는 취소/revert로 rollback됐다고 주장하지 않는다. schema/format 변경은 없다.

## 복구 기록

| 작업 | 회차/2 | 실패 증거·원인 | 새 근거·수정 가설 | 계획 보정 전→후·유지 기준 | 수정 파일·보존 대상 | 검증 명령·결과 | 실행자·다음 조치 |
|---|---|---|---|---|---|---|---|

## 환경 준비 기록

| 원인·근거 | 준비 조치 | 조치 사용/1 | 확인 명령·결과 | 확인 사용/1 | 다음 조치 |
|---|---|---|---|---|---|

## 실행 승인 기록

2026-11-12 소유자의 V1 자동승인 위임에 따라 root가 승인했다. 독립 계획 검토는 base `4b98aee1119ded1f1c6553e92198ac004d30fa87`에서 차단 지적 없이 APPROVE였다. parent 계약을 먼저 커밋하고 parent_rev를 고정했다. 실행 전 baseline 표기 Session10을 실제 Session12로 교정했으며 기능·기준 변경은 없다. 최초 실행은 T1/T2/T4의 분리 worktree authoring으로 시작하며 로컬 Gradle은 하나씩만 실행한다. 실제 Native 검증은 최종 CI까지 pending이다.

### PLAN 복구 1/2 — 실행 전 브리프 계약 누락

조립 결과 Shared design이 표 한 줄뿐이어서 상세 계약이 전달되지 않았다. parent의 ManualDriveContracts 원문을 child 2절의 단일 명명 코드 조각으로 복제했다. 인자·반환·동작 변경은 없고 parent_rev도 유지한다. 재조립한 T1/T2/T4가 상세 계약을 포함하며 해소 실패 0인지 확인 후 위임한다.

### T2 복구 1/2 — 기존 JSON parser의 모듈 경계

실행 전 조사에서 app에는 Android org.json stub만 있고 core의 기존 kotlinx JSON은 implementation-only라 app compile API에서 보이지 않았다. 새 의존성/Gradle 수정 대신 다섯 번째 배정 파일 C/DrivePrincipalProof.kt에 SDK-free Boolean proof predicate를 둔다. app의 판정은 이를 위임하며 exact email/me 조건·malformed 거부·redacted 오류를 유지한다. 기존 JSON 좌표와 runtime graph는 그대로다. T2 최초 구현 및 local TLS 검증에 포함하며 다른 작업 파일은 보존한다.

### T1/T4 체크포인트

T1 root focused verify `/workspace/tooling/pr17-t1-root-final.log` BUILD SUCCESSFUL 23초: core DestinationSync9 + app HealthConnectReader16, failure/error/skip0. 마지막 fixture는 100페이지×1000레코드 실제 전달/조회 횟수를 포함하며 기존 CSV/overlap/uncertainty 회귀를 보존한다. T1 done. T4 root testAPK verify `/workspace/tooling/pr17-t4-root.log` BUILD SUCCESSFUL 12초; 실제 SQLite 두 테스트는 아직 CI pending이므로 T4 in_progress를 유지한다. disjoint T6 authoring은 앞서 승인한 Native 최종 gate 대기 예외로 진행할 수 있으나 actual 완료 주장은 보류한다.

### T2 검증·환경 준비

최초 명령은 실행자가 network 추가 권한을 누락하여 Gradle FileLockContentionHandler의 wildcard IP 초기화에서 실패했다(컴파일·테스트 시작 전). root가 기존 승인 network socket 권한을 적용한 동일 명령을 1회 실행했고 BUILD SUCCESSFUL54초, BoundDriveAuthorization2+GoogleDriveTransport9=11/0/0/0을 실제 XML로 확인했다. 코드/환경설정 변경은 없고 T2 기능 보정 회차는 여전히1이다. root log `/workspace/tooling/pr17-t2-root.log`. duplicate JSON key 거부는 승인 기준에 없어 기존 parser 의미를 유지하며 새 보장으로 주장하지 않는다.

### PLAN 복구 2/2 — root 문서 명령 경로

root가 android/에서 상대 docs 경로로 문서 기록을 시도해 FileNotFoundError로 멈췄다. cherry-pick은 성공했고 문서·제품 파일 손실이나 Gradle 실행은 없었다. 이후 문서 편집은 절대 경로를 쓰고 빌드는 별도 명령으로 분리한다. 기존 자동승인 범위의 운영 보정이며 기능·검증 기준은 유지한다.

### T3 복구 1/2 — 재사용 digest 계약

root의 실행 전 source 검토에서 requirePresent checksum 판정이 MD5만 있는 metadata도 재사용하는 것을 발견했다. 승인 parent의 SHA-256 metadata 재사용 계약에 따라 reuse는 유효한 exact sha256Checksum을 필수로 한다. MD5만 있거나 SHA가 없으면 safe existing same-ID PATCH pending이며 이전 확인으로 채택하지 않는다. fresh write ACK는 checksum 부재를 허용하되 존재하는 SHA/MD5는 일치해야 한다. T3 assigned uploader와 reconciliation test만 보정하며 MD5-only same-ID PATCH 및 SHA exact adoption을 단언한다. 아직 Gradle 미실행이며 baseline·동시2·callback·single-use·read-only 계약은 보존한다.

### T4 복구 1/2 — 독립 검토의 orphan fixture 분리성

독립 quality review는 `34147fdffea4d623db0690568a1c6ea6b5878893`에서 차단0/Suggestion1을 보고했다. orphan fixture의 raw open만 NO_LOCALIZED_COLLATORS가 없어 locale metadata 생성이 broad rejection의 다른 원인이 될 가능성이 있다(실제 생성 관찰은 아직 없음). 배정 test 파일만 raw open flag를 기존 DB와 동일하게 하고 schema 이름이 fixture 전후 동일함을 단언한다. day를 지우기 전에 FK off, 지운 뒤 dataset row가 orphan임을 확인하며 기존 거부·bytes 보존 기대를 유지한다. production 파일/기능/거부 정책 변경은 없다. 다음 Native 실행에서 실제 검증하고 최신 head 재검토한다.

### T6 source 체크포인트 및 T3 분류 해석

T6 prepared-session3개+기존12개를 보존한 testAPK 컴파일을 author1분18초/root13초로 확인했다. 준비 factory는 gate owner 등록·복구 뒤, source access/planner 전에만 호출되고 busy factory는 소비하지 않는다. credential-bound engine은 지역 변수이며 native15개 실제 실행은 pending이다. T6 in_progress 유지. T3의 POST 분류는 401/403만 account blocked이고 나머지 실패는 보수적으로 reconciliation required를 유지한다. 명시적4xx도 원격 mutation 부재를 새 보장으로 가정하지 않으며 blind create/자동 재시도는 금지다. lookup/PATCH의 명시 transient 분류는 그대로다.

### T6 복구 1/2 — 취소 테스트의 예외 상속

fresh quality 재검토가 `4500044529f073e8252893b60cb561717ed50779`의 신규 Native 테스트에서 Important1을 발견했다. JVM CancellationException은 IllegalStateException 하위 타입이므로 cancelled=true 경로가 broad catch에 잡혀 assertFalse(cancelled)로 실패한다. 배정 Session instrumented test의 owner launch에서 CancellationException을 먼저 rethrow하고 일반 합성 실패만 IllegalStateException 단언으로 처리한다. production gate/cancel 코드는 변경하지 않는다. 기존 취소 표시·busy receipt 해결·raw health0·재진입 단언을 유지하고 compile/actual Native/독립 재검토로 확인한다. compile 통과를 실제 테스트 성공으로 주장하지 않았으며 현재 완료 판정은 보류다.

### T3 검증 체크포인트

root focused verify `/workspace/tooling/pr17-t3-root.log` BUILD SUCCESSFUL8초(동일 source 캐시), author actual 실행36초: Batch14+Reconciliation7+Uploader10=31/0/0/0 XML 대조. 기존23 회귀와 신규8개를 보존했다. SHA-256 exact reuse/MD5-only PATCH, no mutation reconcile, root/dataset/file unknown-create no blind POST, strict identity/single-use/preparation 변경 거부, bounded2/serialized callback/joined cancellation을 검증했다. T3 done이며 T4 actual Native pending과 별개로 승인된 disjoint T5 authoring을 시작한다. 실제 계정 결과나 실사용 개선 수치가 아니다.

### CI 환경 관찰 — Native 시작 전 의존성 조회 실패

head34147fd의 run43169661306/API35 job131019650187은 connectedDebugAndroidTest의 UTP 추가-output host tool configuration resolve에서 기존 Maven 좌표10개를 찾지 못해 Native 시작 전에 실패했다. 원본 job log에 protobuf/auto-service/dagger 등 기존 UTP transitive POM의 Google/MavenCentral 조회가 missing으로 나온다. 저장소 settings/의존성 변경은 없었다. 2026-11-12 06:50경 root가 공식 MavenCentral proto-google-common-protos2.17.0 및 auto-service1.1.1 POM을 다시 조회해 각각HTTP200을 확인했다. 제품/테스트 assertion 실패나 Native 통과로 세지 않으며 수정 source의 다음 CI head에서 같은 명령을 검증한다. API36은 당시 진행 중으로 미확정이었다. 새 mirror·버전 교체·검증 생략은 하지 않는다.

### T5 복구 1/2 — app internal API 가시성

root pre-build 검토에서 신규 public DriveApiDestination의 store secondary constructor가 기존 internal SqliteSyncStateStore를 노출하는 Kotlin 가시성 충돌을 확인했다. class를 internal로 한정한다. app-only adapter 계약과 테스트 friend 접근은 유지하며 store 가시성·의존성·외부 API를 넓히지 않는다. 배정 DriveApiDestination.kt 한 줄 보정 뒤 최초 focused compile/test로 확인한다.

### T5 복구 2/2 — 실제 TLS에서 드러난 GET 503 숨은 재시도

최초 focused 실제 실행은 2개 중1개 실패했다. `DriveApiDestinationTest.kt:168`에서 기대1전송/실제2전송이며 fixture는 GET503에도 Retry-After:0을 보낸다. 이는 no-retry parent236/child86 계약과 달리 OkHttp retryOnConnectionFailure(false)만으로 server-directed GET followup이 막히지 않는 결함이다. hostile header를 제거해 테스트를 통과시키지 않는다. T5 배정에 기존 GoogleDriveTransport.kt와 GoogleDriveTransportTest.kt를 추가(총4파일)한다. 설치된5.3.0 followup 소스를 확인하여503 재시도 trigger만 좁게 차단하고 원래 status/body·GET no-body·HTTPS·timeout·no redirect·POST/PATCH one-shot 정책을 보존한다. local TLS exact1전송/503 반환·명시적 다음 호출만 새 요청을 단언하고 기존13개 관련 focused 회귀를 함께 실행한다. 새 라이브러리/버전/프로토콜 downgrade는 없다. 로그 `/workspace/tooling/pr17-t5-initial.log`; 실패 XML은 원본 보존 후 재실행한다.

### T5 검증 체크포인트

복구2 focused 실제 실행40초와 root 재검증14초 통과. XML Bound2+DriveApi2+Transport10=14/0/0/0. initial FAIL 원본을 보존했으며 GET503 Retry-After:0 fixture나 기대1전송 단언을 약화하지 않았다. 설치된 OkHttp5.3.0 RetryAndFollowUpInterceptor bytecode에서503 followup이 retryOnConnectionFailure를 조회하지 않음을 확인했다. productionClient의 network interceptor가503 Retry-After만 제거하여 caller에게 기존503 status/빈 error body를 돌려주고 별도 명시적 호출만 다음 응답을 받는다. bodyless files/about GET 및 기존POST/PATCH one-shot 회귀를 검증했다. 일반 외부 injected/shared client의421 coalescing은 새 보장 대상이 아니며 production은 단일 exact host의 독립 client로 제한한다. T5 done; T7은 producer API 검증 뒤 source authoring에 진입하며 T4/T6 actual Native는 최종 gate pending을 유지한다.

### T123456 통합 체크포인트

root source8b2f04b 전체 명령 BUILD SUCCESSFUL1분51초: core80+app83=163/0/0/0, APK/lint/testAPK 통과. 독립 quality r3는 exact8b2f04b 전체 관련 diff와 T5 local TLS XML14건을 대조해 새 차단 지적 없이 현재 구현 slice APPROVE였다. Native actual·controller/UI/Activity 최종 head 검토는 여전히 pending이다. T7은 검증된 producer API 위에서 별도 worktree에 authoring 중이며 로컬 Gradle slot은 현재 반환했다.

### T7 복구 1/2 — 부분 권한·동일 구성 보존

root 최초 source 검토에서 selectAccount가 configured categories 전부의 HC grant를 요구하여 부분 허용1/5에서 전체 의도5개 설정을 거부함을 발견했다. categories 비어 있음은 거부하되 현재 grant와 교집합이 하나 이상이면 설정하고 ungranted는 기존 planner profile에서 제외한다. 동일 account/categories 재선택은 현재 binding/config를 검증한 뒤 generation/enrolledDay/unknown intent를 보존하며 실제 계정/구성 변경만 configure CAS한다. 배정 controller와 grouped test에 부분1/5→config5 및 동일 재선택/configure0·unknown 보존 단언을 추가한다. 기존 grant0/no fallback 정책은 유지한다.

### T7 복구 2/2 — 외부 동의 ticket과 실행 취소의 수명 분리

현재 setForeground(false)/consent(false)가 credential 참조만 지우고 admitted owner Job을 취소하지 않아 proof 이후 engine IO가 이어질 수 있다. 또한 expected external Google resolution pause에서 pending ticket까지 지워 복귀 수락이 불가능하다. owner Job은 admitted prepare 안에서만 포착해 pause/withdraw/reset 시 취소하고 busy 호출은 소유권을 대체하지 않는다. 명시된 expected-external handoff에서만 token/active execution 없이 pending ticket을 보존하며 실제 foreground 복귀 전 결과를 수락하지 않는다. Activity bridge(T9)는 결과를 resume까지 private 메모리에서 대기시키고 지연 ticket/reset/unexpected pause를 거부한다. 배정 두 파일 내에서 exact API를 보고하고 grouped lifecycle 테스트에 proof 전/후 중단·busy 보호·expected pause/foreground acceptance·unexpected pause 거부를 검증한다. 새 Android API/의존성/자동 continuation은 도입하지 않는다. 아직 Gradle 첫 실행 전이며 원래 범위의 취소·동의·부분 권한 성공 기준을 보존하는 기술 보정이다.

### T7 검증 및 소비자 API

author first focused54초/root 동일 source12초 통과. ManualDriveSessionTest grouped3개 failure/error/skip0이며 부분1/5→의도5개·same account/categories generation/unknown 보존·owner 취소·busy credential 보호·external expected pause와 unexpected pause·reset CAS·credential-free 상태를 단언한다. initial 코드 검토의2회 보정 후 첫 실제 검증이었고 숨은 실패/재시도는 없었다. constructor는 internal ManualDriveSession(sqliteStore, healthReader, execution, clock), state:StateFlow<ManualDriveState>. UI는 setPrivateFolderConsent, setForeground, refresh/refreshGrants, beginAccountSelection/selectAccount, beginAuthorization/acceptAuthorization, continueManual/resetConfiguration을 사용한다. expected external launcher 직전 expectExternalResult(pickerId 또는 authorization Ticket.id)를 호출하고 Activity 결과는 실제 foreground resume 뒤 적용한다. 이 API는 callback에서 자동 업로드를 허용하지 않는다. T7 done이며 실제 DB 결합·Activity 증거는 T9 Native gate에서 확인한다.

### T7 복구 3 (기존 소유자 V1 자동승인 위임) — busy·stale 요청의 상태 비간섭

fresh quality r4가 source16d3ce9에서 Important2건을 발견했다. busy beginAuthorization/beginAccountSelection/selectAccount의 check 실패가 공통 safelyValue cleanup으로 owner ticket/auth를 지우고, 늦은 acceptAuthorization/selectAccount 결과도 최신 READY credential 또는 pending picker를 지운다. 초기2회 보정과 같은 회차로 숨기지 않고3회로 누적한다. 소유자가 기존 V1 workflow 전체 수정·검토를 자동 승인한 범위에서 새 근거가 있는 제한 교정을 계속하며 반복 승인 질문을 하지 않는다. 배정 controller/test 두 파일만 busy/stale 요청을 correlated current-request cleanup 이전에 무상태 거부하도록 분리한다. owner prepare/about barrier 중 모든 중복 begin/select 요청·A→B READY 후 lateA·새 picker에 lateold result를 보내도 현재 credential/ticket/phase가 보존되고 B 실행만 허용됨을 회귀 단언한다. 현재 요청의 실제 취소/오류 및 pause/reset/withdraw 정책은 유지하고 owner factory/busy continue/실제health0 보장을 약화하지 않는다. 최초 새 회귀 red 증거를 가능하면 저장하고 수정 후 focused/root/독립 재검토한다. T8 disjoint authoring은 source API 변경 없이 계속할 수 있지만 최종 Native/통합 검증 전 완료 판정은 없다.

T7 복구3 의도된 수정 전 회귀 실행: 기존 grouped3 중2실패/0error/0skip. busy begin/select는 RUNNING→BLOCKED, lateA는 최신 B READY→AUTHORIZATION_REQUIRED를 재현했다. 원본 log/XML은 tooling의 pr17-t7-recovery3-red에 보존했다. T7 in_progress로 되돌리고 T8의 이미 작성된 분리 산출물은 보존하되 T7 수정 검증·재검토 전 T9 통합을 시작하지 않는다.

### T4/T6 actual Native gate 확인

7ca68e78c9cae1b5d4e5d8c3cf20bcaf8e5d8b86/run43171091357의 원본 artifact ZIP digest와 XML/host 결과를 root가 양API에서 확인했다. API35/36 각각Native111/0/0/0(ManualDriveStore2, Session15 포함), foundation host8+4+8=20, SyncState host17 verifier+17cleanup 및 killed hold PASS0이다. source7ca와 현재의 T4/T6 제품/테스트4파일 diff가0임을 확인했다. orphan fixture isolation과 CE-first test 교정도 포함한 실제 실행이므로 T4/T6 done으로 갱신한다. 근거는 checkpoint-native.json이며 T7/T8/최종head나실계정수용을 대신하지 않는다.

### T7 복구3 및 T8 체크포인트

T7 수정 후 author 실제3/0/0/0·root 동일source13초 통과이며 red3/2/0/0 원본과 대조했다. busy/stale early rejection과 metadata await 후 sequence/epoch/generation 검증을 독립 quality r5가 source3b5b84c에서 재검토하여 r4 Important2건 정적 해결·추가 차단0으로 확인했다. root 같은source 전체검증2분19초, core80+app86=166/0/0/0, APK/lint/testAPK 성공. T7 done으로 복귀한다. T8 네 파일의 최초testAPK author성공과 root 전체성공이 있으나 실제 UI Native는 최종동일head CI pending이다.

### T8 복구1/2 — 이전/현재 표시 문구

root는 bound mode의 이전 이력 카드가 현재 상태로 보이는 혼동과 사용자 문구의 구현 용어 foundation을 찾아 배정했다. 이전 기록의 상태로 conditional label을 바꾸고 아래 이전 동기화 이력으로 표시한다. optional legacy path 현재 상태는 보존하고 기존 bound test가 이전 label/현재 label 부재를 단언한다. 별도2파일 수정이며 root통합컴파일/최종Native에서 확인한다.

### T9 환경 준비 및 복구1/2 — SDK 결과의 launch 상관관계

installed JDK javap이 PATH에 없어 첫 읽기 전용 조회가 실패했다. 기존 /workspace/tooling/jdk-21.0.13+9/bin/javap 절대 경로로 조회하도록 승인했으며 도구/버전/설정 변경은 없다. product 복구 회차와 별도 환경 준비1회다.

최초 Activity source 검토에서 단일 queued closure 및 pickerResult/authorizationResult 슬롯이 overlap된 이전 Android result/늦은 SDK callback으로 새 요청 값을 덮을 수 있는 경계를 발견했다. 각 launch는 정확한 request identity와 대응시키고 pending launcher의 callback을 새 launch로 대체하지 않는다. 실제foreground/현재ticket·generation·expected external 상태에 해당하는 결과만 private transient queue에 두고 stale/reset/unexpected pause는 token을 폐기한다. 기존 controller 최종 거부만으로 queue overwrite를 허용하지 않는다. 배정5파일 범위에서 launcher/request correlation·resume 이전 결과·겹침/늦은응답 회귀를 검증한다. 아직 최초 Gradle 전이며 authsetAccount/minimumscope/explicitcontinue 및 token 비영속 조건은 보존한다.

### 재개 시 병렬 실행 보정 및 T9 복구2/2

소유자가 진행속도 개선과 가능한 병렬 실행을 명시했다. 동일 source의 author/root 중복 전체 실행은 줄이고 author 실제 XML/log를 대조한 root 최종 통합 한 번을 gate로 사용한다. 파일이 다른 T9 제한 수정·T10 host 호환·후속 SAF read-only 계획 조사를 병렬로 진행한다. 로컬 Gradle은 메모리/잠금 충돌을 피하도록 한 번씩만 실행한다.

T9 최초 전체 실행166/0/0/0+APK/lint/testAPK2분58초 성공 후 정적 조사에서 stale SDK success가 owner slot을 미정리하는 경로와 초기 metadata 복원으로 사용자의 새 draft를 덮는 경로를 확인했다. stale owner를 finish하고 사용자가 수정한 draft를 늦은 복원이 덮지 않도록 private flag/restore helper를 적용한다. 기존 배정5파일만 수정하며 가능한 기존 Native 테스트의 sequencing 단언으로 확인한다. scope 확대나 fake metrics는 없다.

PLAN 복구3은 기존 host test의 첫 toggle/continue 부재 단언이 새 bound UI에 맞지 않는 누락 보정이다. 원래 동의 off/no credential/no automatic execution 의미를 보존한 T10 한 파일 작업으로 분리했다. 새 제품 범위가 아니며 기존 소유자의 V1 자동승인 및 병렬 실행 지시에 따라 승인한다. 최종 CI의 기존20host를 실제 실행한다.

### 독립 적합성 F1/F2/F3 제한 복구 승인 (소유자 자동승인·병렬 실행)

독립 검증 cfb5009는 기존phoneprofiling 상실(P1), 실제 권한scope의 metadata coverage 복원 누락(P1), 마지막 upload·최근 대상 날짜 표시 누락(P2)를 확인했다. T11은 T7의 누락 보정(기존3회와 별도 누적4), T12는 T8의 누락 보정(누적2)이며 원래 scope를 새 task로 숨겨 회차를 초기화하지 않는다. 기존 profiling 복원은 performance reviewer가 실제API로 작성한2개 제한작업을 T13/T14로 승인해 부모 내에 고정했다. 새요구/미측정 성능 주장/스키마·의존성은 없다. T11/T12/T13은 파일이 달라 병렬 authoring하고 T14는 T11/T13 뒤 시작한다. 최종 exact-head 검증과 독립 재검토·PR댓글 없이는 병합하지 않는다.

### 병렬 검증 자원 확인 및 T11 환경 준비

소유자의 병렬화 지시로 환경을 재측정했다: CPU3, 메모리 약10GB/가용약9GB, 각Gradle heap2GB이다. 분리worktree의 읽기전용공유cache/SDK를 쓰는 로컬빌드를 최대2개로 제한해 병렬 진행하고 각source는검증중동결한다. 메모리/잠금 오류 증거가 나오면 해당 검증만 직렬로 복귀하며 기능/검증기준은 바꾸지 않는다. CI/코드/리뷰/계획준비는 계속병렬이다. T11 최초 bare wrapper실행은 /home/agent/.gradle lock 부모생성 전에 실패해 테스트0이다. 기존 run-health-command.py로 installedJDK/sharedcache를선택하는 환경준비1회를 승인했고 소스/설정변경은없다. 기존 metadata access 기대0→1은 승인된 refresh의 실제MANUALpermission조회에 맞춘 교정이며 raw/Drive0단언은보존한다.

### T11 복구1/2 — 테스트의 명시적 전경·동의 준비

첫 실제 focused5개 중1개 실패/0error/0skip. 새 복원 테스트가 기본foreground=false/consent=false인 controller에서 사용자 설정을 호출해 beginAccountSelection=null로 정상 거부됐다. 해당 configure 직전에 setForeground(true), setPrivateFolderConsent(true)를 명시하여 실제 사용자 준비를 재현한다. production·assertion·권한게이트는 바꾸지 않는다. 원본실패XML/log 보존 뒤 동일5개unit을 재검증하고 Native APK는최종통합에서확인한다. 기존3grouped+새지연메타데이터group은첫실행PASS였다.

T13 actual focused31/0/0/0, T11 actualfocused5/0/0/0(환경준비1·testfixture복구1 별도원본보존), T12testAPK컴파일PASS. 기존source의중복focused전체실행은생략하고root가XML/log·diff를대조했다. T11Nativeactual/T12UIactual는최종CI에서확인하며 T14는완성된actualAPI를기준으로authoring한다.

### CI 실제 환경 원인 및 T15 승인

cfb/run43421817901/API35 job131873397235는제품테스트전 FATAL userdata 디스크부족(available7074.67MB/need7372.80MB)으로12:05:54중단된뒤12:15:56부팅timeout이었다. 기존unused toolchain cleanup이API36에만적용돼API35에누락됐다. 동일한기존안전환경준비를양API에적용하는T15를승인한다. 테스트/권한/버전/성공기준은보존한다. 코드수정/SAF리뷰와병렬로처리한다.

### 최종 후보 통합 전 확인

T14 actualfocused24/0/0/0(ManualDriveSession9+ManualSyncProfiling6+PhoneProfilingSession9), testAPK컴파일PASS1분19초. 기존4파일만변경하며실제boundVM+realSQLite/syntheticHC/transport Native진단수명테스트를추가했다. T13/T11/T12/T14/T15를통합한현재후보에root전체검증1회와양API전체CI·4독립리뷰를병렬수행한다. rawHealth/credential/date는진단입력에추가하지않았다. Nativeactual·최신head리뷰전완료/병합주장은없다.

### T14 복구1/2 — 진단 무효화·terminal callback·Native scoped 단언

210ad8a 독립 적합성은 reset/동의철회 뒤 phone report 폐기/지연재게시금지(P1), 최종hasResolution=true 실패의 collecting 미종결(P2)을 발견했다. 실제 bridge는 중간resolution에는callback하지않으므로 acceptAuthorization으로전달된true는terminal실패로종결한다. 외부동의대기는expectExternalResult/pause로유지하며진단으로우회하지않는다. reset/철회는보고를즉시비우고늦은ownerfinish가다시게시하지않도록 profile identity/generation 무효화를분리한다; 필요시같은4파일내 default-noop invalidationobserver를additive로추가한다. 단순pause는취소요약허용. 신규잘못된테스트의reset/withdraw report-nonnull 기대는원래승인phone spec에따라null/latecallback무재게시로정정하고 terminal실패active0을단언한다.

독립quality는신규Native테스트가unscoped store.load().days(의도적으로empty)에single()을호출하는결함도확인했다. loadForScope(currentgen,today,actualMANUALaccess)로scope를명시해실제EMPTY day를검증한다. production의rawread/쓰기정책을바꾸지않는다. 관련4파일만교정하고 focused단언·Native실제실행·최신head독립재검토까지병합보류한다. 이전24JVM통과와Nativecompile은이누락동작통과증거가아니다.

T14복구1 actualfocused24/0/0/0와testAPK컴파일41초PASS. reset/withdraw는즉시clear하고늦은finish가무효identity를게시못하며단순pause취소보고는보존한다. terminalresolution실패는FAILED/active0, 실제외부pause는callback없이대기유지. NativeEMPTY는actualscopedstate조회로교정했다. root는수정후새source전체검증1회와같은head양API/4독립리뷰를진행하며이전210ad8a의실패예정Native단언을성공증거로쓰지않는다.

### 실제 Native 회귀 T16/T17 승인

c334/API35 artifact14037199934 SHA256cafa66bccc634b32ac6b68b9e673868f061073f8b759c485e4808be694576e56 원본XML Native120/2fail/0error/0skip. 두실패는제목substring선택자가새설명문구도찾아expected1/actual2인정확한오류다. T16은T12텍스트추가의테스트호환보정(누적1)이며제품오류로과장하지않는다. T17은소유자속도지시로같은전체테스트를초기핵심/나머지로분할해다음실패를13분뒤대신초기에확인한다. 모든Native/host gate를유지한다.

### T17 복구1 — emulator-runner 줄별 shell 계약

SAF focused run43428796098 API35 job131896773570 실제로그는 `/usr/bin/sh: 1: Syntax error: end of file unexpected (expecting "fi")`이며 Native 시작 전 중단이다. runner가 script 각 줄을 별도 shell로 실행하므로 T17 최초 여러 줄 if/변수도 같은 위험이 있다. 기존 전체 gate와 명령을 단일 신규 bash helper로 옮기고 workflow script는 bash helper 호출 한 줄로 고정한다. 변경2파일, 테스트·권한·버전·재시도 불변. bash -n뿐 아니라 runner처럼 줄별 실행하는 stub smoke로 실제 명령 순서·실패 즉시 종료를 검증한다. 같은 원인의 SAF T2도 별도 branch에서 동일 제한 교정한다.
