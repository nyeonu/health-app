---
plan_id: saf-local
title: 검증된 local tree 전경 내보내기
status: in_progress
author: Codex
created: 2026-11-13
parent: ../plan.md
parent_rev: 7538e87a1797f3079525dbb6121f50624688ba44
spec: ../spec.md
spec_rev: 97c01b1992ad7935e6fa8f1f8b371a3963457daa
branch: feature/v1-saf-local
source_base: 57da82514e8483f5f32f2ad34ae0111c7f1dc431
execution_gate: PR17-merged-and-parent-integration-repinned
tasks:
  - {id: T1, title: 실제 합성 DocumentsProvider와 grant starter, depends_on: [], status: in_progress, tier: standard}
  - {id: T2, title: 목적지 공통 known-target 조회와 재열기, depends_on: [], status: in_progress, tier: standard}
  - {id: T3, title: 전경 local tree 동의와 실제 capability probe, depends_on: [T1], status: pending, tier: standard}
  - {id: T4, title: journal 이후 동일 대상 SAF upsert와 reconciliation, depends_on: [T2, T3], status: pending, tier: standard}
  - {id: T5, title: 명시적 전경 SAF controller와 화면 조각, depends_on: [T4], status: pending, tier: standard}
  - {id: T6, title: 목적지 선택과 실제 Activity 수동 경로 연결, depends_on: [T5], status: pending, tier: standard}
  - {id: T7, title: 실패 복구와 접근성 통합 회귀, depends_on: [T6], status: pending, tier: standard}
---

# 검증된 local tree 전경 내보내기

## 1. 맡는 기준

상위 추적성 중 SAF 소유 두 행과 공유 복구 행을 그대로 인용한다. 공유 행의 기존 core/Drive 테스트는 재사용하고 이 PR은 provider 연결을 보완한다. 전체 에픽 성공을 이 하위 PR만으로 선언하지 않는다.

| 스펙 기준 | 테스트 이름 (출처/층) | 충족 위치 |
|---|---|---|
| SAF local 구성은 별도 privacy/tree 동의·실제 granted flags·persisted permission·합성 probe가 확인된 capability만 활성화하고 미검증 provider/background capability는 disabled 또는 BLOCKED로 유지한다. | `SAF configuration enables only consented persisted granted and successfully probed local capabilities` (①·②·③/합성 DocumentsProvider 통합 및 Compose E2E) | T1·T3·T5·T6·T7 |
| SAF 동일 날짜의 더 짧은 재내보내기는 같은 대상에 정확히 truncation/readback되고 duplicate·moved/deleted·grant 철회·write interruption은 suffix 재생성이나 false success 없이 reconciliation/명시적 복구로 연결된다. | `SAF shorter overwrite reads back exact bytes and ambiguous revoked moved deleted interrupted targets require recovery` (①·②·③·④/합성 DocumentsProvider 통합) | T2·T4·T7 |
| 부분 실패·취소·결과불명 후 대상 reconciliation을 거쳐 pending 파일만 다시 처리하고 이미 확인된 동일 revision의 건강한 파일을 중복 create하거나 prior success를 지우지 않는다. | `After partial failure cancellation or unknown create reconciliation retries only pending files` (①·③·④/유닛 및 local TLS·provider 통합) | T2·T4·T7; 기존 DestinationSyncTest·Drive 회귀 재사용 |

보완 테스트는 아래 T의 명확한 동작 이름을 그대로 사용한다. 아래 표가 추가 기준선 ↔ 테스트 ↔ 작업의 대응을 고정한다. 부모의 보안·접근성·개발 증거 전체 행은 §4에서 검증하며 이를 SAF 단독 소유 행으로 바꾸지 않는다.

| 스펙 기준(위 원문) | 추가 테스트 이름 | 작업 |
|---|---|---|
| SAF local 구성은 별도 privacy/tree 동의·실제 granted flags·persisted permission·합성 probe가 확인된 capability만 활성화하고 미검증 provider/background capability는 disabled 또는 BLOCKED로 유지한다. | `Synthetic provider grants target UID tree access and resolver reads the seeded document`; `SAF selection and restore never export without explicit foreground continue`; `SAF intended category snapshots reject edited picker or stored scope mismatches and zero grants without shrinking partial grants`; `SAF actual activity selection configures without exporting and explicit continue confirms only selected local target`; `SAF actual activity rejects stale tree results and paused revoked grants without Drive fallback`; `SAF privacy tree continue retry and reset remain reachable in light dark and large text without URI disclosure` | T1·T5·T6·T7 |
| SAF 동일 날짜의 더 짧은 재내보내기는 같은 대상에 정확히 truncation/readback되고 duplicate·moved/deleted·grant 철회·write interruption은 suffix 재생성이나 false success 없이 reconciliation/명시적 복구로 연결된다. | `SAF reconciliation performs no writes and reservations reject changed or reused inputs`; `SAF encoded opaque document URIs retain target resolver identity after SQLite reopen` | T4 |
| 부분 실패·취소·결과불명 후 대상 reconciliation을 거쳐 pending 파일만 다시 처리하고 이미 확인된 동일 revision의 건강한 파일을 중복 create하거나 prior success를 지우지 않는다. | `All active scope generic URI and prior handles survive reopen while stale corrupt and orphan reads fail closed`; `SAF pause reset stale picker and double continue preserve journal and reject delayed results`; `SAF recovery reopens blockers without clearing unknown creation or remote stale`; `SAF explicit recovery preserves confirmed files unknown creates and old generation isolation after reopen` | T2·T5·T7 |

## 2. 실행에 필요한 현재 상태

### 실행 전 고정점

- **실제 선행 병합·부모 통합 준비 완료, root 계획 검토 전 draft**: PR #17은 실제 master `57da82514e8483f5f32f2ad34ae0111c7f1dc431`로 병합됐고 이 worktree는 그 head로 rebase됐다. `android/`·`.github/`가 검증 대상 `b35c11c8b4651cf9b12b004a7fc4ae3d52a4d3ee`와 diff 없이 동일함을 읽기 전용 확인했다(production `c33467141db12d3953f5c77e5e1e22730d93d424`). 기존 V1 자동승인 위임을 적용하므로 새 사용자 승인 질문은 필요하지 않다. root의 fresh-context 계획 검토·부모 commit 뒤 parent_rev 고정/승인 상태 확인 전 구현하지 않는다. parent_rev는 root가 실제 부모 문서 commit ec8698ad062cc683211613adf0ab9d2df40782fd로 고정했다. spec_rev는 변경하지 않는다.
- 부모 §2의 파일 목록·SafLocalContracts·결정 위치와 §3 추적성을 통합했고 SAF 결정은 ADR-011 Proposed에 기록했다. 브리프에는 부모 해당 계약과 이 §2 전체/해당 T를 함께 전달한다. 이 문서는 SAF 구현·Native 통과를 뜻하지 않는다.
- starter 조사 source는 `25739c54de7efb37607bf5cb6af70d9441a367f4`다. early run `43433735126` API36 actual 1 testcase PASS, API35 두 번째 REVOKE 제어 응답 timeout으로 양API 기동 gate는 미완료다(root 전달 실제 결과). T1은 pending, T3는 T1 양API 완료 뒤다. T2는 provider 없이 독립이므로 구체 계획 검토/승인·revision 고정 뒤 시작할 수 있다. 실제 Galaxy/외부 provider 수용을 앞당겨 주장하지 않는다.
- 이 세션은 부모 계획·이 child·신규 ADR-011 세 문서만 갱신한다. 제품/CI/빌드/계정/picker/grant/네트워크/GitHub/commit을 수행하지 않는다.

### 확인한 API와 공통 계약

접두어는 상위와 동일하다: A=`android/app/src/main/java/com/ny/my/health/`, AT=`android/app/src/test/java/com/ny/my/health/`, AI=`android/app/src/androidTest/java/com/ny/my/health/`, C=`android/core/src/main/kotlin/com/ny/my/health/core/`. 표의 신규 이름은 이 계획의 제안 API이며 현재 존재한다고 주장하지 않는다. **브리프 조립기는 이 §2 전체와 해당 T를 함께 전달**한다. 상위만 검색하는 조립기를 쓸 때는 먼저 아래 이름을 부모 §2에 통합한다. 이름이 누락되면 실행을 멈춘다.

| 이름 | 현재 확인/고정할 인터페이스 |
|---|---|
| `StorageDestination` | 기존 C/Ports.kt: `reconcile(List<PlannedFile>): ReconciledFiles`, `writePending(List<PlannedFile>, suspend (FileConfirmation) -> Unit)`; CSV content는 메모리 전용 |
| `SyncExecutionSession` | 기존 `runPrepared(SyncTrigger.MANUAL, generation, suspend () -> DestinationSync)`가 동일 DB 경로 gate를 소유한 뒤 factory를 호출한다. busy 진입은 merge만 한다. 별도 SAF gate를 만들지 않는다. |
| `ExistingManualUiCompatibility` | 현재 `ManualDriveSession(ports, clock, newProfiling = { null }, onProfileStarted = {}, onProfileFinished = { _, _ -> }, onProfileInvalidated = {})`와 sqlite/reader/execution secondary constructor의 profiling 기본값을 유지한다. `ManualDrivePorts.profiledDestination(transport, generation, profiling: SyncProfiling?)`는 기존 destination delegate다. `SyncViewModel`의 sessionFactory는 네 profiling callback을 받아 ManualDriveSession을 반환하며 public Application constructor를 보존한다. `HealthHomeScreen` 기존 optional 순서 `manualState`, `manualActions`, `manualCategories = manualState?.categories.orEmpty().ifEmpty { DataType.entries.toSet() }`, `todayInSeoul = state.date.plusDays(1)` 뒤에 SAF optional 인자를 추가한다. SAF에 Drive profiling/credential API를 복사하지 않는다. |
| `ExistingStoreBinding` | 현재 `PrivateDestinationBinding(kind, accountName = null, accountType = null, treeUri = null)`, `configure(expected: DestinationGeneration?, configuration: SyncConfiguration, binding: PrivateDestinationBinding): SyncCommit`, `readBinding(expected): PrivateDestinationBinding?`, `reopenManualBlockers(expected): SyncCommit`, `reset(expected: DestinationGeneration?): SyncCommit`를 재사용한다. LOCAL은 accountName/accountType=null, private treeUri(UTF-8 1..4096bytes), configure는 KST 어제 및 valid 전체 categories를 요구하고 새 generation·automatic=false를 만든다. readBinding stale는 throw이며 null은 binding 부재다. 새 schema/format을 추가하지 않는다. |
| `SafKnownTargets` | 신규 store 메서드 `suspend fun loadKnownTargets(expected: DestinationGeneration, date: LocalDate): Map<Dataset, Set<String>>`; 기존 `loadKnownDriveTargets`를 호환 delegate로 보존. 모든 current-generation/date profile의 현재/prior handle을 기존 bounded validated 두 index scan으로 읽는다. stale/read failure는 throw, 빈 결과로 숨기지 않는다. T2는 URI 문자열의 lossless DB roundtrip만 검증하고 provider/grant/resolver identity 확인은 T1 이후 T4가 맡는다. |
| `SafTreeProof` | 신규 A/SafTreeConfiguration.kt의 internal 실행 메모리 값(URI를 노출하는 data-class toString 대신 redacted 표현): `treeUri: Uri`, `writeMode: String`; 생성은 성공한 설정 adapter만. UiState·saved state·진단에 URI/proof를 넣지 않는다. background capability는 항상 false. |
| `SafTreeConfiguration` | 신규 `suspend fun select(uri: Uri?, returnedFlags: Int, privacyConsent: Boolean): SafTreeProof`, `suspend fun restore(treeUri: String, privacyConsent: Boolean): SafTreeProof`; Android IO 경계는 이 클래스에 두고 companion의 IO 없는 flags/provider/probe 판정 함수를 유닛 또는 provider 통합에서 검증한다. 취소는 재던지고 나머지 오류는 `DestinationException` allowlist로 치환한다. |
| `SafDestination` | 신규 `SafDestination(resolver: ContentResolver, proof: SafTreeProof, generation: DestinationGeneration, knownTargets: suspend (DestinationGeneration, LocalDate) -> Map<Dataset, Set<String>>, clock: Clock)`; `StorageDestination` 구현. production 생성에는 store::loadKnownTargets를 전달한다. |
| `ManualSafSession` | 신규 A/ManualSafSession.kt: `state: StateFlow<ManualSafState>`, `setForeground(Boolean)`, `setPrivacyConsent(Boolean)`, `setCategories(categories: Set<DataType>)`, `refresh()`, `beginTreeSelection(): Long?`, `acceptTree(requestId: Long, uri: Uri?, returnedFlags: Int)`, `continueExport()`, `retry()`, `reset(expected: DestinationGeneration)`; suspend IO 메서드는 테스트용 ports를 경유한다. setCategories는 방어적 copy로 현재 intendedCategories를 보관한다. beginTreeSelection은 generation/epoch/intendedCategories snapshot을 request에 캡처하며 acceptTree는 현재 입력과 그 snapshot이 같을 때만 받는다. |
| `ManualSafState` | 신규 같은 파일: phase(UNCONFIGURED/SELECTION_REQUIRED/READY/RUNNING/BLOCKED), generation/configured/foreground/privacyConsent/intendedCategories/categories/grantedCategories/enrolledDay/coverage/outcome만 공개. intendedCategories는 UI의 전체 선택, categories는 저장된 전체 구성 범위, grantedCategories는 현재 허용 집합으로 구분한다. tree URI/provider 원문/credential 없음. |
| `SafManualUi` | 신규 A/SafManualUi.kt: pure `SafManualUi(state: ManualSafState, actions: SafManualActions)`; actions는 consent/tree/continue/retry/reset 확인. folder 표시에는 URI 대신 고정 local 목적지 설명을 사용한다. HealthHomeScreen의 새 optional 인자는 기존 호출부 기본값을 유지한다. |
| `SafGrantProtocol` | production authority는 com.android.externalstorage.documents만; EXTRA_LOCAL_ONLY는 hint. actual READ/WRITE/PERSISTABLE 결과를 요구하고 READ\|WRITE 교집합만 persist, 같은 tree persisted read/write와 실제 접근 재확인. 명시 privacy consent 뒤 health read 전 고유 synthetic probe create→긴 bytes→짧은 wt bytes→close→expected-length+1 exact readback→직접 만든 probe만 cleanup. 오류·cleanup 실패는 BLOCKED, cloud/test authority 생산 허용 금지, restore UI는 metadata-only, background=false, real reboot/Galaxy acceptance pending. |
| `SafTargetProtocol` | selected tree가 root, fixed dataset/date relativePath와 FileKey 대조. targetHandle은 buildDocumentUriUsingTree(treeUri, opaqueDocumentId).toString() encoded URI이며 raw ID 저장/수동 인코딩·형식 파싱 금지; T4가 공백/Unicode/reserved opaque ID의 encoded URI를 SQLite reopen 뒤 실제 target resolver로 다시 확인; complete fresh tree/dataset/file enumeration(loading/partial/null/duplicate/malformed/한도 초과는 BLOCKED), 모든 current/prior known URI identity/membership 일치. reconcile는 read-only; safe existing exact bytes는 adopt, mismatch는 same-ID pending, known missing/moved/ambiguous 또는 unknown-create+absence는 create 금지. pending 입력 불변; exact FileKey/bytes와 묶인 single-use reservation만 writePending에서 소비. 모든 directory/file create/write는 durable RUNNING 이후. create 응답불명·중단은 reconciliation required, suffix/append/fallback/health 삭제 없음. close+fresh identity+expected-length+1 exact readback 후 serial callback; callback store 실패/취소는 원격 rollback으로 표시하지 않음. raw URI/body/provider cause 로그 금지, cancel 재던짐, fixed DestinationException reason만. |
| `SafUiProtocol` | LOCAL binding 기존 strict JSON/DB 사용. setCategories는 전체 intendedCategories를 방어적 copy하고 변경 시 picker/proof 무효(현재 export 취소), 자동 configure 금지. beginTreeSelection이 generation/epoch/intendedCategories snapshot을 캡처; acceptTree는 현재 입력이 snapshot과 같아야 하며 configure.categories에는 snapshot 전체를 저장(granted 교집합으로 축소 금지). continue는 intendedCategories==저장 categories==proof 범위를 gate 전후 재확인. 선택/복귀/계속에서 current granted∩intended가 비면 차단하며 일부 grant는 전체 intended를 보존하고 기존 scope/coverage로 처리. restore는 저장 categories로 입력을 초기화하되 진행 중 UI 편집을 refresh로 덮지 않음. configure(destinationHandle="local-tree", KST 어제, automatic=false) + 새 generation; URI는 private binding에만. selection/probe 복귀는 설정만 저장하고 explicit continue가 공용 runPrepared gate 안에서 fresh grant/probe 뒤 engine 실행. stale generation/ticket/동의 철회/pause/reset은 proof 무효. refresh는 metadata-only, privacy consent는 local visibility/백업 위험 설명. retry는 reopenManualBlockers만 호출하여 unknown create/REMOTE_STALE 보존; recovery를 reset/new generation으로 우회하지 않음. UI에는 URI 없는 고정 local 이름과 coverage/blocked/next action, background=false. Drive·history·profiling·insets 보존. |


기존 확인: `PrivateDestinationBinding("LOCAL", treeUri=...)`, configure/readBinding/reset·generation CAS가 이미 있다. `configure`는 automatic=false와 새 generation을 저장한다. `SyncStateCodec.validHandle`은 UTF-8 1..4096 byte, 공백/제어문자 없는 generic handle이다. Drive ID regex나 새 DB column/format을 도입하지 않는다. `FileConfirmation.targetHandle`은 **raw document ID가 아니라 `DocumentsContract.buildDocumentUriUsingTree(treeUri, opaqueDocumentId).toString()`으로 만든 encoded document URI**다. 예를 들어 opaque ID의 공백은 URI path segment 인코딩에 의해 `%20`으로 저장되어 generic handle의 공백 금지와 충돌하지 않는다. ID를 수동 encode/decode하거나 형식을 파싱하지 않는다. 저장/재열기 비교도 DocumentsContract로 만든 URI의 authority/tree/document identity를 기준으로 하고 raw ID를 handle에 직접 넣지 않는다. encoded URI가 기존 길이/공백·제어문자 계약에 맞지 않으면 명시적으로 BLOCKED다.

### SAF 해석과 실행 불변식

- 부모/승인 spec의 local-only를 **system external-storage DocumentsProvider authority `com.android.externalstorage.documents`와 실제 선택 tree의 전경 probe**로 한정한다. picker에는 `Intent.EXTRA_LOCAL_ONLY=true`를 요청하되 그 hint만으로 안전 판정하지 않는다. test authority 허용은 명시적인 test constructor 주입만 가능하고 production allowlist에 들어가지 않는다. cloud/알 수 없는 authority는 probe로도 활성화하지 않는다. OEM의 다른 local provider는 미검증 BLOCKED다.
- `ACTION_OPEN_DOCUMENT_TREE` + READ/WRITE/PERSISTABLE/PREFIX 요청을 사용한다. 결과의 READ|WRITE 교집합만 `takePersistableUriPermission`에 전달하며 결과에 양쪽과 PERSISTABLE가 실제 있어야 한다. 다시 `persistedUriPermissions`에서 같은 tree의 read/write와 실제 접근을 확인한다. cancel/null URI/read-only/persist 거부는 구성 성공이 아니다. restricted root를 우회하지 않는다.
- 별도 privacy 설명은 local 폴더도 파일 관리자/백업/다른 앱에 노출될 수 있음을 알리고 명시 동의를 요구한다. 저장된 LOCAL binding은 승인된 목적지 구성 기록이며 신규 privacy/capability schema를 만들지 않는다. 재시작 복원은 UI 표시만 하고 실행은 재동의/명시 계속 및 fresh grant/probe 후 가능하다. tree 변경은 새 동의/configure generation이며 recovery 편의로 generation/reset을 바꿔 uncertainty를 버리지 않는다.
- probe는 health 읽기 전에 명시 consent 후에만, 충돌 없는 무작위 synthetic 임시 파일 한 개를 create→긴 ASCII payload→짧은 payload→close→정확한 길이/bytes readback한다. 확인된 `wt`만 사용하며 실패 시 append/`w` fallback 금지. probe 삭제는 **직접 생성한 합성 URI 하나**만; 건강 export/기존 파일 삭제 금지. 중단되어 남은 probe를 기존 파일로 추정해 재사용/자동 삭제하지 않는다. cleanup 실패는 성공 capability로 숨기지 않는다.
- `reconcile`는 read-only: tree→dataset directory→정확한 `YYYY-MM-DD.csv` 경로를 매번 fresh 완전 열거하고 directory MIME/flags, 파일 MIME/write flag, `FLAG_PARTIAL` 부재, authority/tree membership, 모든 known/current/prior ID와 unique canonical identity를 검증한다. null cursor/잘못된 열/중복 ID/중복 이름/EXTRA_LOADING/조회 실패/계획 밖 경로는 부재 증거가 아니다. `getTreeDocumentId`/`getDocumentId`는 opaque 값을 그대로 API에 전달하며 파일 경로나 colon/slash ID 규칙으로 해석하지 않는다. known URI가 이동·삭제되었거나 여러 ID가 충돌하면 BLOCKED다.
- 선택한 dedicated tree 자체가 export root이며 dataset directory를 그 아래 둔다. 별도 HealthApp 중첩 root를 가정하지 않는다.
- 승인 CSV relativePath `daily|workouts|heart-rate|sleep/YYYY-MM-DD.csv`를 FileKey date/dataset과 대조한다. 다섯 HC category와 기존 네 CSV dataset의 header/meaning/units/generated_at/EMPTY 규칙은 그대로다. 다섯 category를 다섯 CSV 파일로 새로 나누지 않는다.
- exact content는 메모리 SHA-256 및 byte length/EOF로 확인한다. readback은 예상 bytes+1 상한으로 읽어 trailing bytes/대형 파일을 거절하며 본문을 디스크/진단에 저장하지 않는다. 건강 데이터 expected content는 새 source reread/revision에서만 나온다. exact match면 confirmation 재사용/adoption, 안전한 기존 대상의 mismatch면 같은 ID overwrite pending이다.
- reconcile의 pending은 입력 PlannedFile과 **값이 같아야** 한다. 안전한 발견 URI를 pending.copy로 주입하지 않는다. adapter 안에 exact FileKey/bytes/proof와 묶인 **단 하나의 single-use batch reservation**을 두고 writePending이 소비한다. 새 reconcile/성공/실패/취소에 clear한다. 다른 실행이나 DB에 캐시하지 않는다.
- 모든 건강 export directory/file create와 overwrite는 DestinationSync가 durable RUNNING을 commit한 **뒤 writePending에서만** 한다. null handle unknown create가 있으면 safe existing target을 fresh로 찾지 못한 경우 directory가 없어도 create하지 않는다. directory/file create 호출 후 실패/응답불명도 결과불명이며 suffix 재생성 금지다. 반환 create URI도 같은 authority/tree/정확한 name/MIME/unique path를 재조회하여 검증하고 틀리면 확인하지 않는다.
- close+같은 identity fresh 검증+exact readback 후 serial onConfirmed를 호출한다. local commit 실패/취소면 원격 write를 rollback했다고 말하지 않고 durable journal을 재열어 reconcile한다. SecurityException/grant 상실은 DESTINATION_BLOCKED, ambiguous/create/open/close/readback/write interruption은 DESTINATION_RECONCILIATION_REQUIRED; 명백히 write 전인 일시 read 실패만 DESTINATION_TRANSIENT_FAILURE 가능. raw provider cause/message를 감싸 보존하지 않는다. CancellationException은 그대로 재던진다.
- 자동/background SAF=false를 고정한다. 전경 runtime probe는 reboot/Doze/background/provider 호환 수용 PASS가 아니다. 실제 Galaxy·grant/reboot·외부 provider·TalkBack acceptance는 pending이며 unsupported cloud는 blocked로 유지한다.

결정 로직 위치: SafTreeConfiguration.kt companion에 consent/flags/local capability 판정, SafDestination.kt의 IO 없는 companion 함수에 relativePath/identity/known handle/중복/byte 확인 판정, SafManualUi.kt의 pure projection에 표시·활성 동작을 둔다. grant/persist/probe IO는 SafTreeConfiguration, export/reconciliation IO는 SafDestination, 호출 수명은 controller가 소유한다. 기존 sealed enum 관례를 유지하고 새 의존성/validator framework를 도입하지 않는다. 최소 LOCAL binding과 generic target URI는 ADR-006을 승계한 Accepted ADR-009의 기존 모델/schema/generation 계약을 재사용한다. persistable grant·local allowlist·foreground proof·encoded target URI·single-use reservation 정책은 ADR-011 Proposed로 기록했다. 기존 ADR-009를 대체하지 않으며 SAF 적합성/Android 검증 전 Accepted가 아니다.

## 3. 작업

### T1: 실제 합성 DocumentsProvider와 grant starter

- starter 재사용 기준: 별도 worktree `52f7f97f2835e89e15b795883a540fa4b61c2259`의 아래 세 test 파일을 실제 merge/rebase 뒤 비교하여 도입한다(전체 branch의 CI/문서 변경을 가져오지 않는다). 소스가 있다는 사실은 T1 완료가 아니다. API35/36 target UID runtime은 아직 pending이며 T3는 T1 양API 완료 전 시작하지 않는다.
- 실제 fixture API는 nested `SyntheticDocumentsProvider.GrantActivity`, `Control`, `EXTRA_CONTROL`/`EXTRA_RECEIVER`/`EXTRA_UID`, `AUTHORITY`/`TARGET_PACKAGE`/`TREE_ID`/`SEEDED_ID`/`ACCESS_FLAGS`, `seededBytes()`다. GrantActivity는 현재 ResultReceiver로 결과·test UID만 반환하고 raw activity result URI/flags를 반환하지 않는다. T6에 직접 picker replacement라고 가정하지 않는다. T1에서 같은 세 파일 안에 grant 명령의 실제 URI+granted flags를 `setResult`로 전달하는 test-only launcher 경계를 보완하고 기존 ResultReceiver/고정 target/enum-only 제어를 보존한다. provider는 Java이며 `queryChildDocuments`는 null을 반환할 수 있는 `Cursor`다(Kotlin 호출부에서는 nullable로 처리). FQCN·nested static GrantActivity/Control·상수·seededBytes() 계약은 유지된다.
- 후속 T의 fixture 요구(opaque 공백/Unicode/reserved ID, 작업 횟수 관찰, null descriptor/짧은 write/cleanup·readback 실패)는 현재 starter에 모두 구현되어 있지 않다. 동일 T1 provider 파일의 합성 enum/control과 redacted 관찰 경계로 준비하고 starter 최소 grant/read 테스트와 분리한다. fixture가 조용히 일부 행을 누락한 결과만으로 production이 불완전을 탐지한다고 주장하지 않는다. loading/null/명시 불완전 신호를 검증한다. T3 이후 provider 파일 수정이 필요하면 파일 소유·의존 계획을 먼저 보정한다.

- 별도 provider UID/프로세스는 tested APK의 Kotlin 런타임을 공유하지 않는다. 양API 이전 실행에서 `NoClassDefFoundError: kotlin/io/FilesKt`가 발생하여 starter provider를 framework Java로 전환했다. provider 및 모든 nested Activity/fixture 확장은 Android framework·Java 표준 API·자체 Java 타입만 사용하고 Kotlin/AndroidX 런타임 의존을 다시 도입하지 않는다. compile/DEX 의존성 검사는 Native PASS가 아니다. 후속 run `43433735126`은 API36 actual 1 testcase PASS/API35 두 번째 REVOKE 제어 응답 timeout으로 양API 완료는 pending이다(root 전달 실제 결과). 이 문서 세션에서는 runtime을 실행하지 않았다.

- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `AI/SyntheticDocumentsProvider.java` | 신규 | test APK provider·중첩 GrantActivity·제어된 실패/차단점 |
  | `AI/SyntheticProviderStarterTest.kt` | 신규 | target ContentResolver로 실제 grant·query·read 한 번 |
  | `android/app/src/androidTest/AndroidManifest.xml` | 신규 | test APK에만 provider와 grant Activity 등록 |

- **인터페이스**:

  ```kotlin
  // DocumentsProvider 실제 SDK36 추상 메서드; nullable SDK 인자는 override에서 맞춘다.
  queryRoots(projection: Array<out String>?): Cursor
  queryDocument(documentId: String, projection: Array<out String>?): Cursor
  queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor?
  openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor
  createDocument(parentDocumentId: String, mimeType: String, displayName: String): String
  isChildDocument(parentDocumentId: String, documentId: String): Boolean
  // fixture control: reset, grant/revoke, duplicate, move/delete, partial/loading/null,
  // no-truncate, wrong-return-identity, create-after-effect-failure, write/close interruption.
  ```

- **결정 로직**:

  ```
  fixture child ancestry → exact parent chain → tree membership
  failure checkpoint before/after effect → configured synthetic outcome only
  ```

- **호출 순서**:

  ```
  1. test-only GrantActivity가 test APK UID에서 target package에 tree read/write/persistable/prefix grant 발행.
  2. target ContentResolver가 실제 takePersistableUriPermission과 persisted grant 재조회 → 실패 시 starter 실패.
  3. target resolver query/read/close → 실패 시 starter 실패, provider 원문 공개 로그 금지.
  ```

- **작성할 테스트**:
  - "Synthetic provider grants target UID tree access and resolver reads the seeded document"
- **테스트 조건/층**: 실제 binder/OS grant와 exact synthetic bytes, read-only grant 및 revoke 거부도 확인한다(②·③/Android 통합).
- **완료 기준**: provider는 target production manifest/APK에 없고 API35/36에서 starter가 실제 target UID ContentResolver 연결·grant·읽기를 증명한다. test APK의 자체 resolver 접근만으로 완료하지 않는다.
- **검증 방법**:

  ```bash
  cd android
  ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
  ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.ny.my.health.SyntheticProviderStarterTest
  ```

- **주의사항**: §2 확인: SDK DocumentsProvider.isChildDocument 기본값은 false이므로 fixture가 ancestry를 구현한다. provider는 test APK permission MANAGE_DOCUMENTS/grantUriPermissions와 DOCUMENTS_PROVIDER filter를 갖고 grant Activity만 실제 target URI grant를 발급한다. control payload는 enum/합성 값만; 측정값·URI를 assertion 메시지/출력에 담지 않는다. 신규 기반 최초 구현에 한해 manifest/test UID grant 기동 형태만 이 세 파일 안에서 보정할 수 있다. 구현 전 읽기 전용으로 UID/grant 동작을 확인하고 위임 범위 안에서 구현·보고한다. 기동/컴파일 또는 구현 후 검증 실패 뒤에는 blocked로 보고하고 집계된 복구 지시를 기다린다. 테스트 skip·단언 완화 금지.

### T2: 목적지 공통 known-target 조회와 재열기

- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `A/SqliteSyncStateStore.kt` | 수정 | 기존 validated scan을 generic 이름으로 제공하고 Drive 호환 delegate 보존 |
  | `AI/ManualDriveStoreInstrumentedTest.kt` | 수정 | generic URI 현재/prior·다른 scope 재열기와 기존 Drive query 회귀 |

- **인터페이스**: §2 `SafKnownTargets` 참조.
- **결정 로직**: 없음; 기존 admission/decode/transaction 판정을 재사용한다.
- **호출 순서**:

  ```
  1. existing admittedRead·generation 검사 → stale/unavailable/corrupt 시 throw.
  2. current generation/date의 day+dataset 두 index scan과 기존 strict decode → orphan/잘못된 profile 시 throw.
  3. current/prior handle의 dataset별 set 반환 → 다른 날짜/보관 generation 제외.
  ```

- **작성할 테스트**:
  - "All active scope generic URI and prior handles survive reopen while stale corrupt and orphan reads fail closed"
- **테스트 조건/층**: (②·③·④/실제 SQLite); 기존 `allActiveScopeKnownAndPriorHandlesSurviveReopenAndConflictingTargetsBlock` 재사용. handle은 공백·Unicode·reserved 문자가 포함된 합성 opaque document ID에서 DocumentsContract로 만든 encoded URI이며, 실제 SQLite close/reopen 후 문자열이 byte-for-byte 동일한지만 확인한다. T2는 provider/grant/resolver 호출 없이 실행하며 실제 resolver identity 확인은 T1·T3에 의존하는 T4로 배정한다. raw ID 직접 저장은 하지 않는다. encoded URI 4096-byte 경계·초과 및 raw whitespace handle 거부는 기존 validator와 대조한다.
- **완료 기준**: 실제 store close/reopen 뒤 URI 문자열이 손실 없이 반환되고 기존 Drive method와 결과가 동일하다. provider fixture·실제 grant·resolver identity는 T2 완료 조건이 아니다. schema/format/column/Drive ID 검증 변경이 없다.
- **검증 방법**:

  ```bash
  cd android
  ./gradlew :core:test :app:testDebugUnitTest
  ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.ny.my.health.ManualDriveStoreInstrumentedTest
  ```

- **주의사항**: §2 확인: 현재 loadKnownDriveTargets는 이미 generic handle을 읽고 이름만 Drive 전용이다. regex나 새 mapping table을 더하지 않는다. 이 T는 provider 호출 없이 DocumentsContract URI 생성과 SQLite만 사용하고 T1과 파일이 겹치지 않아 병렬 가능하다. 추정이 틀리면 우회하지 말고 blocked. 기존 bounded 조회·성능 및 오류 경계를 약화하지 않는다.

체크포인트 1(T1·T2 뒤): T1은 actual target UID grant/read API35·36 starter 기동, T2는 provider 없는 실제 SQLite generic/current/prior reopen과 Drive 호환을 각각 확인한다. T1 미완료는 T3를 차단하지만 독립 T2 결과를 무효화하지 않는다. 컴파일/DEX 검사를 runtime PASS로 대신하지 않는다.

### T3: 전경 local tree 동의와 실제 capability probe

- T1 완료 후 시작.
- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `A/SafTreeConfiguration.kt` | 신규 | actual flags·persisted grants·local allowlist·synthetic probe와 메모리 proof |
  | `AI/SafTreeConfigurationInstrumentedTest.kt` | 신규 | real resolver grant/짧은 overwrite probe·restore 실패 검증 |

- **인터페이스**: §2 `SafTreeConfiguration`, `SafTreeProof`, `SafGrantProtocol` 참조.
- **결정 로직**:

  ```
  consent 없음 / null·non-tree·non-content URI / 비허용 authority → BLOCKED
  returned read/write/persistable 부족 → BLOCKED
  동일 tree persisted read/write·실제 접근·probe 중 하나 실패 → BLOCKED
  모두 성공 → foreground proof; background false
  ```

- **호출 순서**:

  ```
  1. 명시 consent·현재 picker ticket 확인 후 select → 받은 read/write 교집합만 persist; 오류는 allowlist.
  2. persistedUriPermissions와 tree directory/create flag·실제 열거 확인 → 불완전 조회면 BLOCKED.
  3. 고유 synthetic probe create→긴 write/close→짧은 wt write/close→exact readback→직접 만든 probe만 cleanup.
  4. restore는 persisted read/write 재확인 후 같은 probe → UI 복원 부수효과로 호출 금지.
  ```

- **작성할 테스트**:
  - "SAF configuration enables only consented persisted granted and successfully probed local capabilities"
- **테스트 조건/층**: fixture partitions는 cancel/null/read-only/missing persistable/permission revoked/unsupported cloud/loading/null descriptor/nontruncating/short write/cleanup failure/restore다. fake grant collection만으로 persist를 증명하지 않고 T1 실제 grant를 사용한다(①·②·③/Android 통합).
- **완료 기준**: health reader를 호출하지 않은 상태에서 검증된 foreground proof만 반환한다. cloud/test provider 생산 허용·background enable·새 persistent capability 필드가 없다.
- **검증 방법**:

  ```bash
  cd android
  ./gradlew :app:assembleDebugAndroidTest
  ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.ny.my.health.SyntheticProviderStarterTest,com.ny.my.health.SafTreeConfigurationInstrumentedTest
  ```

- **주의사항**: §2 확인: tree 문자열 저장은 grant가 아니다. ContentResolver null/partial/loading을 지원 성공으로 해석하지 않는다. runtime probe 성공을 Galaxy/reboot/background PASS로 쓰지 않는다. provider API는 §2와 로컬 research의 SDK36 실제 signatures를 대조한다. 추정이 틀리면 우회하지 말고 blocked.

### T4: journal 이후 동일 대상 SAF upsert와 reconciliation

- T2·T3 완료 후 시작.
- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `A/SafDestination.kt` | 신규 | read-only exact-target reconcile와 one-batch wt/readback confirmation |
  | `AI/SafDestinationInstrumentedTest.kt` | 신규 | target identity/짧은 write/불완전 조회/중단/오류 통합 |
  | `AI/SafJournalInstrumentedTest.kt` | 신규 | 실제 DestinationSync+SQLite 재열기·다른 scope unknown create·callback failure |

- **인터페이스**: §2 `StorageDestination`, `SafDestination`, `SafKnownTargets`, `SafTargetProtocol` 참조.
- **결정 로직**:

  ```
  계획 밖 path / unsafe·duplicate·moved·deleted known identity / incomplete lookup → blocked
  unique safe existing + exact bytes → confirmed
  unique safe existing + changed bytes → identical input pending; same-URI reservation
  없음 + unknown create → reconciliation required; create 금지
  완전 absence + no known/unknown target → identical input pending; create reservation
  reservation 입력 불일치 / 재사용 → fail closed
  ```

- **호출 순서**:

  ```
  1. reconcile가 current-generation/date known targets와 fresh tree 경로를 read-only 조회 → unsafe/미확인 결과는 차단.
  2. engine이 pending/RUNNING을 기존 store transaction으로 commit → 실패 시 writePending 호출 없음.
  3. writePending이 단일 reservation 소비 → grant/identity 변경 시 차단; 필요한 directory/file create는 여기만.
  4. same target wt write/close→fresh identity·정확한 readback → 실패/취소 시 확인 콜백 없음.
  5. serial onConfirmed→기존 원자 commit → callback 실패는 provider transient로 바꾸지 않고 전파.
  6. 재열기 후 fresh reconcile→동일 bytes는 adopt; changed bytes는 safe same ID; absence/ambiguity는 차단.
  ```

- **작성할 테스트**:
  - "SAF shorter overwrite reads back exact bytes and ambiguous revoked moved deleted interrupted targets require recovery"
  - "SAF reconciliation performs no writes and reservations reject changed or reused inputs"
  - "SAF encoded opaque document URIs retain target resolver identity after SQLite reopen"
  - "After partial failure cancellation or unknown create reconciliation retries only pending files"
- **테스트 조건/층**: T1의 실제 target UID grant/provider를 사용하여 공백·Unicode·reserved opaque ID로 DocumentsContract가 만든 encoded URI를 SQLite에 저장·재열기하고, 같은 URI의 실제 target resolver 접근/정확한 document identity·bytes를 확인한다. 이어 실제 SQLite+provider로 create-after-effect 응답불명, directory create interruption, readback interruption, local confirmation commit 실패, source revision 변경·EMPTY 왕복·scope 축소/다른 scope null handle, 재열기와 두 번째 취소를 검증한다. safe known target adoption과 unknown+없음 no-create를 구분한다. 다른 날짜/generation 오염은 T2 재사용(①·②·③·④/Android 통합).
- **완료 기준**: same document identity/count·short exact bytes·healthy file write0·unknown absent create0·reconcile create/write/delete0을 실제 provider 관찰과 journal 행으로 단언한다. provider close/readback와 durable confirmation 둘 다 있어야 성공이다.
- **검증 방법**:

  ```bash
  cd android
  ./gradlew -PcoreOnly :core:test
  ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.ny.my.health.SafDestinationInstrumentedTest,com.ny.my.health.SafJournalInstrumentedTest
  ```

- **주의사항**: §2 확인: DestinationSync는 reconcile.pending이 원래 PlannedFile과 같은지 검사하고 RUNNING commit을 writePending보다 먼저 한다. null target의 createMayHaveSucceeded는 current generation/date 모든 profile에서 전파된다. engine/core 동작을 SAF 편의로 바꾸지 않는다. complete enumeration에 상한을 둘 때 초과는 BLOCKED이며 잘린 목록을 absence로 보지 않는다. provider raw cause나 CSV·URI를 예외 텍스트로 만들지 않는다. 추정이 틀리면 우회하지 말고 blocked.

체크포인트 2(T3·T4 뒤): 실제 grant/probe와 shorter overwrite/readback·journal reopen·unknown absent create0을 합성 provider 양API 통합으로 확인한다. scope별 prior/unknown/REMOTE_STALE 보존 및 encoded URI resolver identity가 통과한 뒤 controller 통합으로 진행한다.

### T5: 명시적 전경 SAF controller와 화면 조각

- T4 완료 후 시작.
- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `A/ManualSafSession.kt` | 신규 | generation·picker ticket·동의·명시 계속·공용 gate controller |
  | `A/SafManualUi.kt` | 신규 | local privacy/선택·계속·차단·재시도/reset 화면 조각 |
  | `AT/ManualSafSessionTest.kt` | 신규 | stale result·취소·double continue·gate·reset 단위 경계 |

- **인터페이스**: §2 `ManualSafSession`, `ManualSafState`, `SafManualUi`, `SyncExecutionSession`, `SafTreeConfiguration`, `SafGrantProtocol`, `SafUiProtocol` 참조. test ports는 기존 ManualDrivePorts와 같은 store/reader/configure/reopen/runPrepared 경계를 사용하며 token/auth API가 없다.
- **결정 로직**:

  ```
  setCategories 변경 → picker/proof 무효; 시작 시 전체 intended snapshot 고정
  consent·foreground·generation·ticket·snapshot/현재 입력 불일치 → 선택 결과 폐기
  current granted∩intended 없음 → configure/continue 차단; partial grant면 전체 intended 보존
  selection/probe 성공 → explicit configure; READY 표시; export 자동 시작 금지
  pause/consent 철회/reset/generation 변경 → ticket/proof 무효; 실행 취소; prior journal 보존
  retry → eligible blockers만 reopen; unknown/REMOTE_STALE 보존
  configured·foreground·consent·grant/probe 충족 + intended==stored==proof 범위 → explicit continue만 공용 gate 실행
  ```

- **호출 순서**:

  ```
  1. refresh는 load/readBinding/coverage만 → 자동 health read/probe/write 없음.
  2. setCategories→beginTreeSelection이 generation/epoch/전체 intended snapshot 고정→현재 access metadata와 입력 재확인→tree grant/probe→configure CAS(snapshot 전체 categories, automatic=false, KST 어제, LOCAL binding) → mismatch/실패면 설정 성공 금지.
  3. explicit continue→intended==stored categories/current grant 확인→runPrepared gate 획득→fresh binding/categories/grant/probe 범위 재확인→reopenManualBlockers→DestinationSync → mismatch/zero grant/stale/probe 실패는 health read 전 차단.
  4. reset 확인→기존 store.reset CAS→현재 실행/지연 결과 무효 → remote/probe/health 파일 삭제 없음.
  ```

- **작성할 테스트**:
  - "SAF selection and restore never export without explicit foreground continue"
  - "SAF intended category snapshots reject edited picker or stored scope mismatches and zero grants without shrinking partial grants"
  - "SAF pause reset stale picker and double continue preserve journal and reject delayed results"
  - "SAF recovery reopens blockers without clearing unknown creation or remote stale"
- **테스트 조건/층**: (①·②·③·④/JVM ports 단위). 선택 전 비어 있는 categories, picker 도중 입력 변경/동일집합 재입력, stale generation, 저장 categories 불일치, grant0, intended 5종 중 일부 grant를 검증한다. partial grant는 configure.categories 5종을 보존하고 기존 partial scope/coverage를 사용하며 grant0는 configure/read/write0이다. exact actual grant는 T3, durable gate는 기존 session instrumentation 및 T6에서 검증한다.
- **완료 기준**: 새 controller가 Drive authorization/계정 API를 호출하지 않고 기존 DB 공용 gate를 사용한다. UiState 및 saved state에 URI/proof가 없으며 기본 automatic=false다.
- **검증 방법**:

  ```bash
  cd android
  ./gradlew :app:testDebugUnitTest --tests com.ny.my.health.ManualSafSessionTest
  ./gradlew :app:assembleDebug
  ```

- **주의사항**: §2 확인: ManualDriveSession이 현재 single selected config를 표시하며 SAF binding을 계정 불명으로 오표시하지 않도록 실제 화면 routing은 T6에서 한 곳에 둔다. both mode/설정 전환으로 기존 uncertainty 우회 금지. External picker 왕복 한 번만 허용하고 일반 전경 이탈은 proof/실행 무효다. 추정이 틀리면 우회하지 말고 blocked.

### T6: 목적지 선택과 실제 Activity 수동 경로 연결

- T5 완료 후 시작.
- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `A/MainActivity.kt` | 수정 | raw Intent flags를 보존한 tree launcher·현재 request/foreground delivery |
  | `A/SyncViewModel.kt` | 수정 | SAF session·단일 목적지 routing·permission/lifecycle/reset 연결 |
  | `A/HealthHomeScreen.kt` | 수정 | local/Drive 명시 선택과 SafManualUi 합성·기존 optional 기본값 |
  | `AI/SafManualFlowInstrumentedTest.kt` | 신규 | 실제 Activity와 target resolver·SQLite·합성 provider 전경 실행 |

- **인터페이스**: §2 `ManualSafSession`, `ManualSafState`, `SafManualUi`, `SafUiProtocol`, `SafGrantProtocol` 참조. tree bridge는 StartActivityForResult의 resultCode/data.uri/**data.flags**를 그대로 전달한다. OpenDocumentTree contract의 Uri-only 반환으로 실제 flags를 꾸며내지 않는다. test launcher는 T1 GrantActivity로 주입하며 production은 ACTION_OPEN_DOCUMENT_TREE다.
- **결정 로직**:

  ```
  current binding LOCAL → SAF current status/actions
  current binding DRIVE → 기존 bound-account status/actions
  새 선택·동의·configure CAS 전 → 이전 active destination 결과 보존
  stale launcher result / 일반 background 복귀 → request 폐기; 자동 continue 금지
  ```

- **호출 순서**:

  ```
  1. UI categories 입력을 session.setCategories로 전달→explicit privacy consent→snapshot을 가진 tree request slot 발급→system picker launch → 취소/실패는 allowlist 상태.
  2. 현재 result+실제 flags→foreground delivery→controller acceptTree가 captured/current intended 일치 확인 → 전체 snapshot 구성만 저장.
  3. explicit continue→공용 gate→fresh grant/probe→기존 source pipeline→SAF confirmation commit.
  4. lifecycle pause/consent 철회/reset→두 destination request 무효화 → 이미 발행된 write는 rollback 성공으로 표시하지 않음.
  ```

- **작성할 테스트**:
  - "SAF actual activity selection configures without exporting and explicit continue confirms only selected local target"
  - "SAF actual activity rejects stale tree results and paused revoked grants without Drive fallback"
- **테스트 조건/층**: UI categories를 실제 session setter에 전달하고 picker 도중 입력 변경/partial grant 전체 intended 유지·grant0 차단을 확인한다. target resolver·실제 SQLite reopen·합성 HC input을 사용하고 실제 AccountPicker/계정/HC provider는 열지 않는다(①·②·③·④/Android E2E).
- **완료 기준**: picker 복귀/앱 복원이 health export를 시작하지 않으며 explicit continue 후 selected generation만 확인된다. 기존 bound Drive와 foundation history/phone profiling/PR14 inset 동작을 보존한다.
- **검증 방법**:

  ```bash
  cd android
  ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
  ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.ny.my.health.SafManualFlowInstrumentedTest,com.ny.my.health.ManualDriveFlowInstrumentedTest
  ```

- **주의사항**: §2 확인: MainActivity의 기존 bridgeBusy/request epoch는 계정/authorization용이다. SAF launcher도 같은 외부 요청 busy 경계에 참여하되 Drive current()의 privateFolderConsent/Account ticket 조건을 SAF로 잘못 복사하지 않는다. 실제 결과 flags 확보를 위해 SDK result contract를 바꾸지 않고 별도 raw Intent launcher를 사용한다. 추정이 틀리면 우회하지 말고 blocked.

체크포인트 3(T5·T6 뒤): metadata-only 복원/명시 continue·stale picker/pause/reset/double continue·실제 Activity 단일 목적지 routing과 기존 Drive/history/profiling/insets 회귀를 대조한 뒤 T7 전체 회복·접근성 검증으로 진행한다.

### T7: 실패 복구와 접근성 통합 회귀

- T6 완료 후 시작.
- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `AI/SafManualUiInstrumentedTest.kt` | 신규 | light/dark/큰 글자와 URI 없는 privacy·tree·continue·retry/reset semantics |
  | `AI/DestinationConfigurationInstrumentedTest.kt` | 수정 | LOCAL binding·새 generation·설정 실패/복원/reset과 Drive 보존 |
  | `AI/SafJournalInstrumentedTest.kt` | 수정 | 실제 UI recovery 후 journal 재열기·미확인 상태 보존 통합 |

- **인터페이스**: §2 `ManualSafState`, `SafManualUi`, `SafKnownTargets`, `SafTargetProtocol`, `SafUiProtocol` 참조.
- **결정 로직**: 없음; 제품 판정을 테스트 안에 복제하지 않는다.
- **작성할 테스트**:
  - "SAF privacy tree continue retry and reset remain reachable in light dark and large text without URI disclosure"
  - "SAF explicit recovery preserves confirmed files unknown creates and old generation isolation after reopen"
- **테스트 조건/층**: T3/T4의 부모 이름 시나리오를 actual Activity/SQLite 경계까지 보완하고 existing history/Drive UI 회귀 재사용(①·②·③·④·⑤/Compose/Android 통합).
- **완료 기준**: API35/36 양쪽에서 starter·grant·short overwrite·actual Activity·SQLite reopen 및 모든 affected suite 통과. 실제 TalkBack/기기/외부 provider/reboot/background는 pending이다. URI/provider 원문/CSV가 test name/assertion message/screenshot/result artifact에 나오지 않는다.
- **검증 방법**:

  ```bash
  cd android
  ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.ny.my.health.SafManualUiInstrumentedTest,com.ny.my.health.DestinationConfigurationInstrumentedTest,com.ny.my.health.SafJournalInstrumentedTest
  ```

- **주의사항**: §2 확인: instrumentation은 JUnit4/Compose 기존 의존성을 재사용하고 별도 Gradle dependency/CI 변경을 요구하지 않는다. 실패 후 테스트 skip·단언 완화·test authority production 등록 금지. 추정이 틀리면 우회하지 말고 blocked.

## 4. 검증

1. 실행 전 실제 merge `57da82514e8483f5f32f2ad34ae0111c7f1dc431`와 모든 API/파일·승인 문서 revision, 부모 commit 뒤 parent_rev 고정·root 계획 검토/승인 상태를 확인한다. T1/T2만 서로 독립이며 나머지는 frontmatter 의존 순서를 지킨다. T4/T7가 같은 journal test를 수정하므로 순차다.
2. android/에서 `./gradlew -PcoreOnly :core:test`, `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest`를 실행한다. 새 dependency가 필요해지면 임의 추가하지 말고 부모 dependency/audit 절차로 보정한다.
3. API35 **및** API36 각각에서 기존 CI와 같은 전체 instrumentation 명령 `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.notClass=com.ny.my.health.HistoryProcessRecoveryInstrumentedTest,com.ny.my.health.SyncStateProcessRecoveryInstrumentedTest`와 `bash android/ci/verify-history-process-recovery.sh`, `bash android/ci/verify-sync-state-process-recovery.sh`를 수행한다. 새 provider starter/SAF class는 제외하지 않는다. host hold class만 기존 정책대로 제외한다. 단일 API나 mock-only 통과는 양API provider 검증 완료가 아니다.
4. production/test APK merged manifests를 대조하여 provider/grant Activity가 test APK에만 있고 새 storage 광역 permission/OAuth/worker/background/계정 의존성이 없음을 확인한다. 기존 noBackup DB/binding 위치와 백업 제외를 실제 manifest/rules로 점검한다. 신규 LOCAL URI가 public logs/diagnostics/saved state/clipboard/screenshot/assertion 출력에 들어가지 않는지 source와 실제 산출물 allowlist를 검토한다. SDK/provider 원문 cause·본문은 오류에 보존하지 않는다. CSV serializer와 다섯 category coverage/네 dataset 회귀를 기존 테스트로 재확인한다.
5. fresh-context 별도 세션이 승인 spec+구현(계획/이전 검토 결론 제외)으로 적합성을 검증한다. 최신 head 전체 diff 품질·보안 검토, 지적 수정·재검토, 한국어 PR 댓글과 최종 reviewed head 기록은 AGENTS의 기존 gate다. 준비 세션은 GitHub를 수정하지 않는다. 부모 통합 담당자가 최종 실행 증거와 storage design 상태를 실제 검증에 맞게 갱신한다.
6. 실제 Galaxy/local provider selection/reboot/짧은 overwrite/offline/권한 철회/TalkBack과 기존 P0·키 백업은 수행한 증거만 반영하고 나머지는 PENDING을 유지한다. synthetic provider는 OS API/권한/앱 계약의 개발 증거이고 외부 provider 수용 증거가 아니다. cloud SAF와 background SAF는 계속 disabled/BLOCKED다.

## 5. 롤백

새 설정을 중지하고 기존 generation admission을 무효화한 뒤 PR을 되돌린다. 사용자 export나 기존 health target을 삭제하지 않는다. 새 LOCAL binding/generic URI를 모르는 구버전은 자동 DB 초기화/Drive fallback 대신 명시적 unsupported/reset 경계를 사용한다. 기존 LOCAL uncertainty/journal을 자동 지우지 않는다. 사용자 확인 reset은 기존 app-private 설정/coverage 범위만 다루고 foundation history/원격 파일을 보존한다. provider proof 실패는 비활성화이며 capability를 낙관적으로 복원하지 않는다.

## 복구 기록

| 작업 | 회차/2 | 실패 증거·원인 | 새 근거·수정 가설 | 계획 보정 전→후·유지 기준 | 수정 파일·보존 대상 | 검증 명령·결과 | 실행자·다음 조치 |
|---|---|---|---|---|---|---|---|

## 환경 준비 기록

| 원인·근거 | 준비 조치 | 조치 사용/1 | 확인 명령·결과 | 확인 사용/1 | 다음 조치 |
|---|---|---|---|---|---|

## 실행 승인 — 2026-11-13

기존V1자동승인/병렬지시에따라root는실제PR17merge57da, 부모인터페이스/추적성통합ec8698, 독립fresh-context 계획검토a43788e APPROVE/차단0을대조해이구체7작업을승인했다. 부모SAF상태in_progress commit7538e87로rev를재고정했다. 계약·파일·검증기준은독립검토대상과같고frontmatter상태/승인근거만갱신했다. T1/T2는파일이달라병렬실행한다. T1의초기3파일은standalone provider 최신52f7f97를토대로재사용하되추가fixture/rawpicker경계와실제양API통과를확인전done아님. T3는T1뒤, T4는T2/T3뒤의gate를유지한다. 실제환경명령은설치된wrapper/JDK/sharedcache를사용하며로컬Gradle최대2개만동시에실행한다.
