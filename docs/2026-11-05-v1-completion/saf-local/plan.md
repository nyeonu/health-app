---
plan_id: saf-local
title: 검증된 local tree 전경 내보내기
status: in_progress
author: Codex
created: 2026-11-13
parent: ../plan.md
parent_rev: a43e03749231fcfab123549e7bd776e8704ea757
spec: ../spec.md
spec_rev: 97c01b1992ad7935e6fa8f1f8b371a3963457daa
branch: feature/v1-saf-local
source_base: 57da82514e8483f5f32f2ad34ae0111c7f1dc431
execution_gate: PR17-merged-and-parent-integration-repinned
tasks:
  - {id: T1, title: 실제 합성 DocumentsProvider와 grant starter, depends_on: [], status: done, tier: standard}
  - {id: T2, title: 목적지 공통 known-target 조회와 재열기, depends_on: [], status: done, tier: standard}
  - {id: T3, title: 전경 local tree 동의와 실제 capability probe, depends_on: [T1], status: done, tier: standard}
  - {id: T4, title: journal 이후 동일 대상 SAF upsert와 reconciliation, depends_on: [T2, T3], status: done, tier: standard}
  - {id: T5, title: 명시적 전경 SAF controller, depends_on: [T4, T9], status: done, tier: standard}
  - {id: T6, title: 목적지 선택과 실제 Activity 수동 경로 연결, depends_on: [T5, T9, T10], status: done, tier: standard}
  - {id: T7, title: 실패 복구와 접근성 통합 회귀, depends_on: [T11], status: in_progress, tier: standard}
  - {id: T8, title: 기본 전체 gate를 유지하는 명시 component 진단, depends_on: [], status: done, tier: standard}
  - {id: T9, title: URI 없는 SAF 상태와 pure 화면 semantics, depends_on: [], status: done, tier: standard}
  - {id: T10, title: 동일 source provider와 destination 실제 checkpoint, depends_on: [T4], status: done, tier: standard}
  - {id: T11, title: 실제 Activity SAF 선택과 전경 수동 실행 검증, depends_on: [T6, T12], status: done, tier: standard}
  - {id: T12, title: 같은 구성의 metadata refresh 결과 보존, depends_on: [T5], status: done, tier: standard}
---

# 검증된 local tree 전경 내보내기

## 1. 맡는 기준

상위 추적성 중 SAF 소유 두 행과 공유 복구 행을 그대로 인용한다. 공유 행의 기존 core/Drive 테스트는 재사용하고 이 PR은 provider 연결을 보완한다. 전체 에픽 성공을 이 하위 PR만으로 선언하지 않는다.

| 스펙 기준 | 테스트 이름 (출처/층) | 충족 위치 |
|---|---|---|
| SAF local 구성은 별도 privacy/tree 동의·실제 granted flags·persisted permission·합성 probe가 확인된 capability만 활성화하고 미검증 provider/background capability는 disabled 또는 BLOCKED로 유지한다. | `SAF configuration enables only consented persisted granted and successfully probed local capabilities` (①·②·③/합성 DocumentsProvider 통합 및 Compose E2E) | T1·T3·T9·T5·T6·T7 |
| SAF 동일 날짜의 더 짧은 재내보내기는 같은 대상에 정확히 truncation/readback되고 duplicate·moved/deleted·grant 철회·write interruption은 suffix 재생성이나 false success 없이 reconciliation/명시적 복구로 연결된다. | `safShorterOverwriteReadsBackExactBytesAndAmbiguousRevokedMovedDeletedInterruptedTargetsRequireRecovery` (①·②·③·④/합성 DocumentsProvider 통합) | T2·T4·T7 |
| 부분 실패·취소·결과불명 후 대상 reconciliation을 거쳐 pending 파일만 다시 처리하고 이미 확인된 동일 revision의 건강한 파일을 중복 create하거나 prior success를 지우지 않는다. | `afterPartialFailureCancellationOrUnknownCreateReconciliationRetriesOnlyPendingFiles` (①·③·④/유닛 및 local TLS·provider 통합) | T2·T4·T7; 기존 DestinationSyncTest·Drive 회귀 재사용 |

보완 테스트는 아래 T의 명확한 동작 이름을 그대로 사용한다. 아래 표가 추가 기준선 ↔ 테스트 ↔ 작업의 대응을 고정한다. 부모의 보안·접근성·개발 증거 전체 행은 §4에서 검증하며 이를 SAF 단독 소유 행으로 바꾸지 않는다.

| 스펙 기준(위 원문) | 추가 테스트 이름 | 작업 |
|---|---|---|
| SAF local 구성은 별도 privacy/tree 동의·실제 granted flags·persisted permission·합성 probe가 확인된 capability만 활성화하고 미검증 provider/background capability는 disabled 또는 BLOCKED로 유지한다. | `Synthetic provider grants target UID tree access and resolver reads the seeded document`; `SAF selection and restore never export without explicit foreground continue`; `SAF intended category snapshots reject edited picker or stored scope mismatches and zero grants without shrinking partial grants`; `SAF actual activity selection configures without exporting and explicit continue confirms only selected local target`; `SAF actual activity rejects stale tree results and paused revoked grants without Drive fallback` | T1·T5·T6·T7 |
| SAF local 구성은 별도 privacy/tree 동의·실제 granted flags·persisted permission·합성 probe가 확인된 capability만 활성화하고 미검증 provider/background capability는 disabled 또는 BLOCKED로 유지한다. | `SAF privacy tree continue retry and reset remain reachable in light dark and large text without URI disclosure` (①·②·③·④·⑤/Compose 합성 UI 통합) | T9; T7 재실행 |
| SAF 동일 날짜의 더 짧은 재내보내기는 같은 대상에 정확히 truncation/readback되고 duplicate·moved/deleted·grant 철회·write interruption은 suffix 재생성이나 false success 없이 reconciliation/명시적 복구로 연결된다. | `safReconciliationPerformsNoWritesAndReservationsRejectChangedOrReusedInputs`; `safEncodedOpaqueDocumentUrisRetainTargetResolverIdentityAfterSQLiteReopen` | T4 |
| 부분 실패·취소·결과불명 후 대상 reconciliation을 거쳐 pending 파일만 다시 처리하고 이미 확인된 동일 revision의 건강한 파일을 중복 create하거나 prior success를 지우지 않는다. | `All active scope generic URI and prior handles survive reopen while stale corrupt and orphan reads fail closed`; `SAF pause reset stale picker and double continue preserve journal and reject delayed results`; `SAF recovery reopens blockers without clearing unknown creation or remote stale`; `SAF explicit recovery preserves confirmed files unknown creates and old generation isolation after reopen` | T2·T5·T7 |

## 2. 실행에 필요한 현재 상태

### 실행 전 고정점

- **실제 선행 병합·부모 통합 준비 완료, root 계획 검토 전 draft**: PR #17은 실제 master `57da82514e8483f5f32f2ad34ae0111c7f1dc431`로 병합됐고 이 worktree는 그 head로 rebase됐다. `android/`·`.github/`가 검증 대상 `b35c11c8b4651cf9b12b004a7fc4ae3d52a4d3ee`와 diff 없이 동일함을 읽기 전용 확인했다(production `c33467141db12d3953f5c77e5e1e22730d93d424`). 기존 V1 자동승인 위임을 적용하므로 새 사용자 승인 질문은 필요하지 않다. root의 fresh-context 계획 검토·부모 commit 뒤 parent_rev 고정/승인 상태 확인 전 구현하지 않는다. parent_rev는 root가 실제 부모 문서 commit ec8698ad062cc683211613adf0ab9d2df40782fd로 고정했다. spec_rev는 변경하지 않는다.
- 부모 §2의 파일 목록·SafLocalContracts·결정 위치와 §3 추적성을 통합했고 SAF 결정은 ADR-011 Proposed에 기록했다. 브리프에는 부모 해당 계약과 이 §2 전체/해당 T를 함께 전달한다. 이 문서는 SAF 구현·Native 통과를 뜻하지 않는다.
- starter 조사 source는 `25739c54de7efb37607bf5cb6af70d9441a367f4`다. early run `43433735126` API36 actual 1 testcase PASS, API35 두 번째 REVOKE 제어 응답 timeout으로 양API 기동 gate는 미완료다(root 전달 실제 결과). T1은 pending, T3는 T1 양API 완료 뒤다. T2는 provider 없이 독립이므로 구체 계획 검토/승인·revision 고정 뒤 시작할 수 있다. 실제 Galaxy/외부 provider 수용을 앞당겨 주장하지 않는다.
- 이 세션은 부모 계획·이 child·신규 ADR-011 세 문서만 갱신한다. 제품/CI/빌드/계정/picker/grant/네트워크/GitHub/commit을 수행하지 않는다.

### 확인한 API와 공통 계약

접두어는 상위와 동일하다: A=`android/app/src/main/java/com/ny/my/health/`, AT=`android/app/src/test/java/com/ny/my/health/`, AI=`android/app/src/androidTest/java/com/ny/my/health/`, C=`android/core/src/main/kotlin/com/ny/my/health/core/`. 표의 신규 이름은 이 계획의 제안 API이며 현재 존재한다고 주장하지 않는다. **브리프 조립기는 해당 T와 명시 참조된 §2 계약 조각을 원문으로 전달**한다. 상위만 검색하는 조립기를 쓸 때는 먼저 아래 이름을 부모 §2에 통합한다. 이름이 누락되면 실행을 멈춘다.

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
| `ManualSafState` | 신규 A/ManualSafUiState.kt(T9): phase(UNCONFIGURED/SELECTION_REQUIRED/READY/RUNNING/BLOCKED), generation/configured/foreground/privacyConsent/intendedCategories/categories/grantedCategories/enrolledDay/coverage/outcome만 공개. intendedCategories는 UI의 전체 선택, categories는 저장된 전체 구성 범위, grantedCategories는 현재 허용 집합으로 구분한다. tree URI/provider 원문/credential 없음. |
| `SafManualActions` | 신규 A/ManualSafUiState.kt(T9)의 아래 exact callback 계약. T5는 state/phase/actions를 소비하며 중복 선언하지 않는다. resetConfiguration은 확인한 expected generation을 전달한다. |
| `SafManualUi` | 신규 A/SafManualUi.kt: pure `SafManualUi(state: ManualSafState, actions: SafManualActions)`; actions는 consent/tree/continue/retry/reset 확인. folder 표시에는 URI 대신 고정 local 목적지 설명을 사용한다. HealthHomeScreen의 새 optional 인자는 기존 호출부 기본값을 유지한다. |
| `SafGrantProtocol` | production authority는 com.android.externalstorage.documents만; EXTRA_LOCAL_ONLY는 hint. actual READ/WRITE/PERSISTABLE 결과를 요구하고 READ\|WRITE 교집합만 persist, 같은 tree persisted read/write와 실제 접근 재확인. 명시 privacy consent 뒤 health read 전 고유 synthetic probe create→긴 bytes→짧은 wt bytes→close→expected-length+1 exact readback→직접 만든 probe만 cleanup. 오류·cleanup 실패는 BLOCKED, test authority 허용은 명시적 test constructor 주입만 가능하고 production allowlist에 들어가지 않는다. cloud/test authority 생산 허용 금지, restore UI는 metadata-only, background=false, real reboot/Galaxy acceptance pending. |
| `SafTargetProtocol` | selected tree가 root, fixed dataset/date relativePath와 FileKey 대조. targetHandle은 buildDocumentUriUsingTree(treeUri, opaqueDocumentId).toString() encoded URI이며 raw ID 저장/수동 인코딩·형식 파싱 금지; T4가 공백/Unicode/reserved opaque ID의 encoded URI를 SQLite reopen 뒤 실제 target resolver로 다시 확인; complete fresh tree/dataset/file enumeration(loading/partial/null/duplicate/malformed/한도 초과는 BLOCKED), 모든 current/prior known URI identity/membership 일치. reconcile는 read-only; safe existing exact bytes는 adopt, mismatch는 same-ID pending, known missing/moved/ambiguous 또는 unknown-create+absence는 create 금지. pending 입력 불변; exact FileKey/bytes와 묶인 single-use reservation만 writePending에서 소비. 모든 directory/file create/write는 durable RUNNING 이후. create 응답불명·중단은 reconciliation required, suffix/append/fallback/health 삭제 없음. close+fresh identity+expected-length+1 exact readback 후 serial callback; callback store 실패/취소는 원격 rollback으로 표시하지 않음. raw URI/body/provider cause 로그 금지, cancel 재던짐, fixed DestinationException reason만. |
| `SafUiProtocol` | LOCAL binding 기존 strict JSON/DB 사용. setCategories는 전체 intendedCategories를 방어적 copy하고 변경 시 picker/proof 무효(현재 export 취소), 자동 configure 금지. beginTreeSelection이 generation/epoch/intendedCategories snapshot을 캡처; acceptTree는 현재 입력이 snapshot과 같아야 하며 configure.categories에는 snapshot 전체를 저장(granted 교집합으로 축소 금지). continue는 intendedCategories==저장 categories==proof 범위를 gate 전후 재확인. 선택/복귀/계속에서 current granted∩intended가 비면 차단하며 일부 grant는 전체 intended를 보존하고 기존 scope/coverage로 처리. restore는 저장 categories로 입력을 초기화하되 진행 중 UI 편집을 refresh로 덮지 않음. configure(destinationHandle="local-tree", KST 어제, automatic=false) + 새 generation; URI는 private binding에만. selection/probe 복귀는 설정만 저장하고 explicit continue가 공용 runPrepared gate 안에서 fresh grant/probe 뒤 engine 실행. stale generation/ticket/동의 철회/pause/reset은 proof 무효. refresh는 metadata-only, privacy consent는 local visibility/백업 위험 설명. retry는 reopenManualBlockers만 호출하여 unknown create/REMOTE_STALE 보존; recovery를 reset/new generation으로 우회하지 않음. UI에는 URI 없는 고정 local 이름과 coverage/blocked/next action, background=false. Drive·history·profiling·insets 보존. |
| `SafActivityIntegration` | T6의 기존 네 파일만 사용한다. MainActivity.kt 안에 internal ManualSafBridge(isBusy():Boolean, selectTree(requestId:Long, result:(Uri?,Int)->Unit))와 instance viewModelFactory:ViewModelProvider.Factory?/safTreeIntent:()->Intent를 둔다. 생산 Intent는 ACTION_OPEN_DOCUMENT_TREE+READ/WRITE/PERSISTABLE/PREFIX+EXTRA_LOCAL_ONLY이며 StartActivityForResult의 RESULT_OK/data?.data/data?.flags를 그대로 전달한다(nonOK→null, 없는flags→0). 요청flags를결과에OR하지않는다. test picker만 explicit GrantActivity Intent로주입하고result용launch에는NEW_TASK/MULTIPLE_TASK를넣지않는다. Activityinstance seam은onActivityPreCreated에주입해실제onCreate전동일factory를쓴다. syntheticallowlist를exportedIntent/staticglobal로선택하지않는다. Drive/SAF request는같은busy경계를쓰고SAF결과buffer는Drivephase/privateFolderConsent와독립이며ActivityRESUMED+SAFforeground뒤만delivery한다. launchfailure는null로현재ticket을소모하고stale결과는새slot을소모하지않는다. |
| `ManualDestinationDependencies` | T6의 SyncViewModel.kt nested class: store:SqliteSyncStateStore, reader:ScopedHealthReader, execution:SyncExecutionSession, configuration:SafTreeConfiguration, clock:Clock. 새primary `(application:Application, dependencies:ManualDestinationDependencies?, sessionFactory:기존4profilingcallback factory?)`, 기존public1arg와기존internal2arg `(application, sessionFactory)` overload를그대로보존한다. 기존 trailing-lambda호출의마지막인자종류를바꾸지않는다. V1Drive/SAF는동일injectedstore/scopedreader/execution을공유하고default도같은canonicalgate를쓴다. legacyreader의availability/grantedPermissions/read는기존HealthConnectReader를보존하고새ScopedHealthReader로해당API를가정하지않는다. 실제T6테스트는실제targetSQLite/execution/resolver+합성scopedreader/forTestingconfiguration을주입한다. |
| `SafDestinationRouting` | T6 SyncViewModel.kt 안 internal ManualDestination(DRIVE,LOCAL), 편집중선택draft와strictprivatebinding의실제committedkind를구분한다. 모드클릭은configure/reset/export를하지않고현재generation/coverage를보존한다. 실제binding과다른form에는다른목적지coverage/success를구성된결과로표시하지않고expectedgeneration만CAS에보존하며기존활성결과는읽기전용으로남긴다. unknownkind에서Drive자동fallback금지. Home optional인자는기존todayInSeoul뒤 safState:ManualSafState?=null,safActions:SafManualActions?=null,selectedDestination:ManualDestination=DRIVE,changeDestination:((ManualDestination)->Unit)?=null 순으로추가한다. hasV1은Drive또는SAF의완전한state/actions쌍으로판정해LOCAL에서구legacyDriveupload가다시나오지않게한다. Drive/history/profiling/insets·기존인자순서·기본값은보존한다. |


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

### SafCiProtocol

```text
// SafCiProtocol — 고정 class 진단과 기존 전체 gate 보존
workflow_dispatch.test_scope choice default full; choices full,saf-provider-starter,saf-store,saf-capability,saf-destination,saf-ui,saf-flow.
GITHUB_EVENT_NAME=workflow_dispatch일 때만 TEST_SCOPE 선택을 적용; PR/기타/변수없음은 기존 full.
saf-provider-starter → com.ny.my.health.SyntheticProviderStarterTest
saf-store → com.ny.my.health.ManualDriveStoreInstrumentedTest
saf-capability → com.ny.my.health.SafTreeConfigurationInstrumentedTest
saf-destination → com.ny.my.health.SafDestinationInstrumentedTest,com.ny.my.health.SafJournalInstrumentedTest
saf-ui → com.ny.my.health.SafManualUiInstrumentedTest (T9 고정 source가 존재한 뒤 실제 실행)
saf-flow → com.ny.my.health.SafManualFlowInstrumentedTest,com.ny.my.health.ManualDriveFlowInstrumentedTest (T6고정source생성뒤만실제실행)
선택 class는 위 리터럴만허용하고 해당고정 testsource가없으면명시실패. 임의 class 문자열/eval/쉘명령입력금지.
unknown dispatch scope는 명시실패; diagnostic branch는 고정class connected만실행하고host를full통과로표시하지않음.
기본full은현재 critical14+나머지전체+기존host2스크립트 및artifact수집 그대로. 새테스트자동포함/skip없음.
runner script는현재 단일bashhelper호출그대로; API35/36/permissions/버전/timeout/디스크준비불변.
```


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
  | `AI/SyntheticDocumentsProvider.java` | 수정 | BLOCK_BEFORE_EFFECT 진입을 private ResultReceiver로 관측하여 실제 mid-probe 취소를 결정적으로 검증 |

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
- fixture 추가 계약: 기존 MOVE_OUTSIDE_TREE/DELETE_SEEDED/DUPLICATE에 private 합성 target-id extra를 추가할 수 있다. 미지정은 기존 seed 동작을 유지한다. ID는 provider nodes map의 opaque key로만 조회하고 filesystem 경로로 해석하거나 URI/ID/body를 로그·오류에 넣지 않는다. partial은 silent PARTIAL cursor와 다른 고정 DOCUMENT_PARTIAL enum/Column_FLAGS의 명시 bit로 구분한다. 기존 권한/UID/grant/Java-only/binding/schema/10초 단언을 유지한다.
- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `A/SafDestination.kt` | 신규 | read-only exact-target reconcile와 one-batch wt/readback confirmation |
  | `AI/SafDestinationInstrumentedTest.kt` | 신규 | target identity/짧은 write/불완전 조회/중단/오류 통합 |
  | `AI/SafJournalInstrumentedTest.kt` | 신규 | 실제 DestinationSync+SQLite 재열기·다른 scope unknown create·callback failure |
  | `AI/SyntheticDocumentsProvider.java` | 수정 | explicit Document.FLAG_PARTIAL 및 선택된 합성 target의 move/delete/duplicate 제어로 실제 차단 검증 |

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
  - "safShorterOverwriteReadsBackExactBytesAndAmbiguousRevokedMovedDeletedInterruptedTargetsRequireRecovery"
  - "safReconciliationPerformsNoWritesAndReservationsRejectChangedOrReusedInputs"
  - "safEncodedOpaqueDocumentUrisRetainTargetResolverIdentityAfterSQLiteReopen"
  - "afterPartialFailureCancellationOrUnknownCreateReconciliationRetriesOnlyPendingFiles"
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


- **병렬 checkpoint 분리**: T4의 완료는 교정 source의 lint/APK/testAPK·독립 소스 재검토·기존 JVM XML 대조까지다. 아래 actual provider/destination 연결 실행은 동일 source를 고정한 T10에서 빠짐없이 수행한다. T5는 이 안정 API source를 기반으로 별도 두 파일만 구현할 수 있고 T6는 T10 실제 양API PASS 전 시작하지 않는다. component 실패 시 T4 원래 ID/복구 회차를 유지하며 T5의 영향 없는 코드와 증거를 보존한다.

### T9: URI 없는 SAF 상태와 pure 화면 semantics

- depends_on: []; root가 2026-11-13 exact state/actions 계약과 pure UI enablement를 기존 V1 자동승인 범위로 승인했다. 부모/child 문서 고정 뒤 시작. T3/T4 소유 source 파일을 읽기만 하며 수정하지 않는다.
- tier: standard.
- **변경 파일** (총 3개)

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `A/ManualSafUiState.kt` | 신규 | metadata-only phase/state/actions 단일 선언 |
  | `A/SafManualUi.kt` | 신규 | backend 독립 pure SAF 화면 조각 |
  | `AI/SafManualUiInstrumentedTest.kt` | 신규 | 합성 state/callback의 theme/large text/semantics |

- **인터페이스**: §2 `ManualSafState`, `SafManualActions`, `SafManualUi` exact 계약. backend/controller/Activity/store/provider 경계와 무관한 합성 metadata/callback만 사용한다.
- **결정 로직**: SafManualUi.kt private IO 없는 status/enablement 표시 함수. controller의 snapshot/proof/grant/gate 판정을 대체하지 않는다.
- **동작**: metadata-only composition; 전체 intendedCategories 체크박스와 현재 grant 수를 구별; 고정 local 목적지 이름과 local visibility/backup privacy 설명; privacy/tree/명시 continue/retry/reset 확인; configured categories 불일치·grant0·pause/RUNNING 시 잘못된 continue 방지; partial grant는 전체 intended 그대로 표시; scoped/full coverage·pending/blocked·최근 날짜별 읽기/업로드·lastUploadAt/outcome을 구분; EMPTY를 업로드 성공으로 표시하지 않음; background 지원 없음. reset은 캡처한 generation 확인을 사용한다.
- **표시 enablement**: idle = foreground && phase != RUNNING. tree = idle && privacyConsent && intendedCategories.any { it in grantedCategories }. continue = idle && privacyConsent && configured && generation != null && intendedCategories == categories && intendedCategories.any { it in grantedCategories } && phase == READY. retry는 idle/동의/구성/범위 일치/교집합과 BLOCKED 또는 STALE/RETRYABLE/PENDING 또는 scope pending/blocked 표시가 있는 경우에만 요청한다. UI는 retry로 export 성공을 선반영하지 않는다. 실제 허용 판정은 T5가 재검증한다. consent/categories/HC request/grant refresh는 idle에서 도달 가능하고, reset은 idle/configured/non-null generation에서 확인한다.
- **작성할 테스트**: "SAF privacy tree continue retry and reset remain reachable in light dark and large text without URI disclosure" (기존 T7 이름 이동, ①·②·③·④·⑤/Compose 합성 UI 통합).
- **테스트 조건/층**: light/dark × fontScale 1/1.8에서 scroll·role·checked/enabled·callback을 확인. 최초 composition/recomposition/state 교체는 callback0; 빈 intended/grant0/동의 없음/비전경/RUNNING/저장범위 불일치는 continue 불가; partial grant는 intended 전체 유지; READY의 명시 클릭만 continue1. retry 클릭은 retry1이며 continue0. reset 취소0/확인1/세대 변경 뒤 오래된 확인0. 초기·BLOCKED/STALE/RETRYABLE·과거 성공+현재 pending·EMPTY·partial scope·최근 날짜별 증거가 다른 coverage를 합성 주입하고 구별 표시를 확인. URI/provider/proof 필드는 없고 고정 목적지 이름만 렌더링. fixture는 합성 metadata만 사용하고 semantics tree나 raw URI를 assertion message/artifact에 dump하지 않는다.
- **완료 기준**: 3개 파일만으로 app 및 androidTest 컴파일; widget은 controller/backend를 참조하지 않음. 양API의 pure Compose class 결과를 각각 기록하고 실제 provider/gate/Activity/TalkBack 수용이라고 주장하지 않는다.
- **검증 방법**: android/에서 ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug; API35 및 API36에서 ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.ny.my.health.SafManualUiInstrumentedTest. 실행은 root 공용 build/기기 gate로 직렬화한다. 다른 agent와 공유 Gradle/emulator를 동시에 실행하지 않는다. 사용 불가 검증은 pending으로 기록한다.
- **주의사항**: 기존 Compose/JUnit4 의존성만 재사용. HealthHomeScreen/SyncViewModel/MainActivity/ManualSafSession/SafTreeConfiguration/SafDestination/DB/Gradle/manifest/CI 수정 금지. 이 작업은 tree picker 실행, HC read, export, probe, persisted grant 또는 새로운 generation을 생성하지 않는다.

### T5: 명시적 전경 SAF controller

- T4·T9 완료 후 시작.
- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `A/ManualSafSession.kt` | 신규 | generation·picker ticket·동의·명시 계속·공용 gate controller |
  | `AT/ManualSafSessionTest.kt` | 신규 | stale result·취소·double continue·gate·reset 단위 경계 |

- **인터페이스**: §2 `ManualSafSession`, `ManualSafPorts`, `ManualSafState`, `SafManualActions`, `SafManualUi`, `SyncExecutionSession`, `SafTreeConfiguration`, `SafGrantProtocol`, `SafUiProtocol` 참조. T9의 ManualSafUiState.kt state/phase/actions를 소비하며 DTO/widget 파일을 수정하거나 같은 클래스를 재선언하지 않는다. test ports는 기존 ManualDrivePorts와 같은 store/reader/configure/reopen/runPrepared 경계를 사용하며 token/auth API가 없다.
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

- T5·T9 완료 후 시작.
- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `A/MainActivity.kt` | 수정 | raw Intent flags를 보존한 tree launcher·현재 request/foreground delivery |
  | `A/SyncViewModel.kt` | 수정 | SAF session·단일 목적지 routing·permission/lifecycle/reset 연결 |
  | `A/HealthHomeScreen.kt` | 수정 | local/Drive 명시 선택과 SafManualUi 합성·기존 optional 순서/기본값 보존, SAF optional은 todayInSeoul 뒤에 추가 |

- **인터페이스**: §2 `SafActivityIntegration`, `ManualDestinationDependencies`, `SafDestinationRouting`, `ManualSafSession`, `ManualSafState`, `SafManualActions`, `SafManualUi`, `SafUiProtocol`, `SafGrantProtocol` 참조. T9 DTO/widget을 중복 선언하지 않고 actions를 기존 session/Activity 경계에 연결하며 resetConfiguration(expected)는 확인한 generation으로 session.reset(expected)를 요청한다. tree bridge는 StartActivityForResult의 resultCode/data.uri/**data.flags**를 그대로 전달한다. OpenDocumentTree contract의 Uri-only 반환으로 실제 flags를 꾸며내지 않는다. test launcher는 T1 GrantActivity로 주입하며 production은 ACTION_OPEN_DOCUMENT_TREE다.
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

- **작성할 테스트**: 기존 core84/app96 합180 JVM과 기존 Native source 컴파일 회귀를 재사용한다. 새 실제 Activity 두 행동은 동일 승인 기준 그대로 T11로 옮겨 작성·실행하며 이 T6 source gate를 Native 통과로 부르지 않는다.
- **테스트 조건/층**: UI categories를 실제 session setter에 전달하고 picker 도중 입력 변경/partial grant 전체 intended 유지·grant0 차단을 확인한다. target resolver·실제 SQLite reopen·합성 HC input을 사용하고 실제 AccountPicker/계정/HC provider는 열지 않는다(①·②·③·④/Android E2E).
- **완료 기준**: 제품 세 파일 배선이 기존180 JVM/빌드/lint/testAPK 컴파일과 독립 소스 검토를 통과한다. 새 실제 picker/선택/explicit continue/SQL reopen 검증은 T11에서 필수 수행하고 T7·최종병합은 그 뒤다.
- **검증 방법**:

  ```bash
  cd android
  ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest
  ```

- **주의사항**: §2 확인: MainActivity의 기존 bridgeBusy/request epoch는 계정/authorization용이다. SAF launcher도 같은 외부 요청 busy 경계에 참여하되 Drive current()의 privateFolderConsent/Account ticket 조건을 SAF로 잘못 복사하지 않는다. 실제 결과 flags 확보를 위해 SDK result contract를 바꾸지 않고 별도 raw Intent launcher를 사용한다. 추정이 틀리면 우회하지 말고 blocked.

체크포인트 3(T5·T6·T11 뒤): metadata-only 복원/명시 continue·stale picker/pause/reset/double continue·실제 Activity 단일 목적지 routing과 기존 Drive/history/profiling/insets 회귀를 대조한 뒤 T7 전체 회복·접근성 검증으로 진행한다.

### T11: 실제 Activity SAF 선택과 전경 수동 실행 검증

- T6 제품 배선과 T12 controller source gate 완료 후 시작한다. CI saf-flow는 T8 고정 source 후보를 사용하며 전체 gate를 대체하지 않는다.
- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `AI/SafManualFlowInstrumentedTest.kt` | 신규 | 실제 Activity·raw flags·target resolver·SQLite·합성 HC 전경 실행 |

- **인터페이스**: §2 `SafActivityIntegration`, `ManualDestinationDependencies`, `SafDestinationRouting`, `ManualSafSession`, `ManualSafState`, `SafManualActions`, `SafUiProtocol`, `SafGrantProtocol` 참조. 실제 T6 구현 API를 직접 읽고 사용한다. production seam을 테스트 편의로 바꾸거나 static global/invented proof를 추가하지 않는다.
- **결정 로직**: 제품 판정을 테스트에 복제하지 않는다.
- **호출 순서**: Application.onActivityPreCreated에서 instance factory와 test picker Intent를 주입 → 실제 MainActivity 생성 → UI 입력/consent/실제 launcher 결과 flags → 구성만 저장/health export0 → explicit continue → 실제 core/SQLite/provider 확인 → 모든 callback/scenario/grant/파일 정리.
- **작성할 테스트**:
  - "SAF actual activity selection configures without exporting and explicit continue confirms only selected local target"
  - "SAF actual activity rejects stale tree results and paused revoked grants without Drive fallback"
- **테스트 조건/층**: UI categories 실제 setter, picker 도중 입력 변경, partial grant 전체 intended 유지, grant0 configure/read/write0, read-only 결과flags, 일반pause/secondpause/stale ticket/revoke 후 health read0를 검증한다. 실제ViewModel continue/retry 직후와pause/resume·권한refresh 뒤 COMPLETED 결과와 grant/probe BLOCKED 오류가즉시지워지지않는지, 이전성공이현재차단을가리지않는지, LOCAL→Drive 구성/reset 뒤LOCALconfigured·coverage가현재활성으로남지않는지도같은두groupedtests에서검증한다. selected local generation과 실제SQLite reopen/targetresolver readback을 대조한다. 실제 계정/HealthConnect provider는 열지 않고 합성 ScopedHealthReader·기존 test-only authority constructor를 주입한다. Drive bridge는 counting/throwing sentinel이며 fallback0이다. 허용된 synthetic capability probe쓰기를 health export0과 혼동하지 않는다. URI/body/credentials를 assertion message/log/screenshot에 노출하지 않는다.
- **완료 기준**: 새2methods와 기존ManualDriveFlow6methods가 saf-flow 양API에서 실패·오류·skip·중복0이며 root가 원본 XML/정확class+method/SHA를 대조한다. 실제기기/계정/TalkBack 수용은 pending이다.
- **검증 방법**:

  ```bash
  cd android
  ./gradlew :app:testDebugUnitTest :app:assembleDebugAndroidTest
  ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.ny.my.health.SafManualFlowInstrumentedTest,com.ny.my.health.ManualDriveFlowInstrumentedTest
  ```

- **주의사항**: 기존 fixture의 target UID/grant/ResultReceiver를 재사용하고 actual launcher는 NEW_TASK/MULTIPLE_TASK 없이 launch한다. 임의sleep/timeout증가/단언완화/skip/production allowlist확장 금지. 필요하면 미커버를 보고하고 제품코드를 임의수정하지 않는다.

### T12: 같은 구성의 metadata refresh 결과 보존

- T5 완료 후 독립 controller 두 파일만 교정한다. T6 UI 세 파일과 겹치지 않아 병렬 가능하다. 원래 T6 통합에서 발견한 상태 보존 결함이며 작업 분리로 원래 복구 회차를 초기화하지 않는다.
- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `A/ManualSafSession.kt` | 수정 | 같은 generation·유효 범위의 metadata/foreground 왕복에서 현재 결과·실패 보존 |
  | `AT/ManualSafSessionTest.kt` | 수정 | 같은 구성 refresh·foreground·범위/세대 변경의 결과 보존 회귀 |

- **인터페이스**: §2 `ManualSafSession`, `ManualSafPorts`, `ManualSafState`, `SafUiProtocol` 참조. 공개 API·DTO·proof·schema·권한은 그대로다.
- **결정 로직**: 같은 구성/실효읽기범위의 metadata-only refresh·foreground 왕복은 현재 outcome 및 BLOCKED를 지우지 않는다. 명시적 새 선택/실행/동의·입력 변경과 실제 generation/configuration 변경에서는 기존 무효화 규칙을 유지한다. 실효 grant scope가 바뀌면 이전 COMPLETED를 새 범위의 완료로 승격하지 않고 명시 새 확인이 필요함을 표시한다. 새 generation/다른 binding의 과거 outcome을 현재로 복사하지 않는다.
- **작성할 테스트**:
  - "Metadata and foreground refresh preserve current SAF result and blocker but never promote changed scope or generation as completed" (①·③·④·⑤/JVM ports).
- **호출 순서**: 실제 기존 session API로 성공/차단 결과 생성 → 같은 generation refresh/pause/resume/refresh → 결과와 현재 blocker 보존 및 record/probe/write0 확인 → 실효 grant/scope 및 generation 변경 → 새 구성 완료로 복사하지 않음 확인.
- **완료 기준**: 기존4행동을 그대로 유지하고 위5번째 회귀가 통과한다. 테스트에서 Uri 실행/가짜 private proof/Android 런타임을 쓰지 않는다. 실제VM/Activity는T11양API에서 검증한다.
- **검증 방법**:

  ```bash
  cd android
  ./gradlew :app:testDebugUnitTest --tests com.ny.my.health.ManualSafSessionTest :app:assembleDebug
  ```

- **주의사항**: setForeground의 projectPhase도 기존BLOCKED를지울수있어refresh만고치고끝내지않는다. consent철회/reset/명시selection/configure CAS/unknowncreate·REMOTE_STALE/freshownerprobe·sharedgate·동일tree/scope generation재사용은보존한다. 실패후숨은수정loop없이보고한다.

### T7: 실패 복구와 최종 통합 회귀

- T11 완료 후 시작.
- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `AI/DestinationConfigurationInstrumentedTest.kt` | 수정 | LOCAL binding·새 generation·설정 실패/복원/reset과 Drive 보존 |
  | `AI/SafJournalInstrumentedTest.kt` | 수정 | 실제 UI recovery 후 journal 재열기·미확인 상태 보존 통합 |

- **인터페이스**: §2 `ManualSafState`, `SafManualActions`, `SafManualUi`, `SafKnownTargets`, `SafTargetProtocol`, `SafUiProtocol` 참조.
- **결정 로직**: 없음; 제품 판정을 테스트 안에 복제하지 않는다.
- **작성할 테스트**:
  - "localConfigurationFailureRestoreAndResetPreserveDriveBindingAndHistory"
  - "safExplicitRecoveryPreservesConfirmedFilesUnknownCreatesAndOldGenerationIsolationAfterReopen"
- **테스트 조건/층**: T9의 합성 semantics 증거는 actual Activity routing/lifecycle와 durable recovery를 대신하지 않는다. T7에서 기존 검증 명령의 세 class와 전체 affected suite를 다시 실행한다. T3/T4의 부모 이름 시나리오를 actual Activity/SQLite 경계까지 보완하고 existing history/Drive UI 회귀 재사용(①·②·③·④·⑤/Compose/Android 통합).
- **완료 기준**: API35/36 양쪽에서 starter·grant·short overwrite·actual Activity·SQLite reopen 및 모든 affected suite 통과. 실제 TalkBack/기기/외부 provider/reboot/background는 pending이다. URI/provider 원문/CSV가 test name/assertion message/screenshot/result artifact에 나오지 않는다.
- **검증 방법**:

  ```bash
  cd android
  ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.ny.my.health.SafManualUiInstrumentedTest,com.ny.my.health.DestinationConfigurationInstrumentedTest,com.ny.my.health.SafJournalInstrumentedTest
  ```

- **주의사항**: §2 확인: instrumentation은 JUnit4/Compose 기존 의존성을 재사용하고 별도 Gradle dependency/CI 변경을 요구하지 않는다. 실패 후 테스트 skip·단언 완화·test authority production 등록 금지. 추정이 틀리면 우회하지 말고 blocked.

### T8: 기본 전체 gate를 유지하는 명시 component 진단

- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `.github/workflows/android-emulator-tests.yml` | 수정 | dispatch choice와 TEST_SCOPE env만 추가 |
  | `android/ci/run-emulator-validation.sh` | 수정 | 고정 허용목록 diagnostic 분기, 기존 full 본문 보존 |

- **인터페이스**: §2 `SafCiProtocol` 참조. 현재 추가 배정은 고정 saf-flow choice→com.ny.my.health.SafManualFlowInstrumentedTest,com.ny.my.health.ManualDriveFlowInstrumentedTest 두 class뿐이다. T6 source생성 전 class존재guard로실패해야하며실제flow8method는T6 source통합뒤양API에서실행한다. 기존6choice/UI/공통prep/defaultfull/host/API/timeout/권한은불변이다. 동일기존CI두파일만수정하고source없는가짜test를레포에추가하지않는다. 기존6mode실제성공증거는보존한다.
- **결정 로직**: fixed scope→fixed class, dispatch guard; 실제 app/provider 정책 변경 없음.
- **작성할 테스트**: CI-only static/stub. YAML matrix/settings/artifact/host/fullcommands byte parity; PR에scope주입·빈변수·full→full, explicit각진단→고정class만, unknown/missingclass→실패, connected실패→즉시전파. runner처럼script줄별shell실행. Native0개/다른class는root원본XML검증에서PASS아님.
- **완료 기준**: 기본 PR/full은 모든 Native·host를보존한다. 진단결과는해당component에만사용하고최종현재Androidsource의양API전체회귀는별도필수다.
- **검증 방법**: YAML구조·bash -n·실제한줄action stub branch/failure전파와원본full본문동등성검증. GitHubactualcomponent는해당T의원본XML/class/method/count/head로root확인한다.
- **주의사항**: API/settings/dependency/앱기능/테스트단언변경금지. T1/T2소유파일과안겹쳐병렬가능하며작성만먼저하고class실제존재전진단을기동성공으로표시하지않는다.

## 4. 검증

1. 실행 전 실제 merge `57da82514e8483f5f32f2ad34ae0111c7f1dc431`와 모든 API/파일·승인 문서 revision, 부모 commit 뒤 parent_rev 고정·root 계획 검토/승인 상태를 확인한다. T1/T2/T8와 root 승인된 T9는 disjoint 파일을 소유하며 frontmatter 의존 순서를 지킨다. T3/T4 작성과 T9 작성도 disjoint다. 공유 Gradle/emulator gate는 root가 직렬 실행한다. T4/T7가 같은 journal test를 수정하므로 순차다.
2. android/에서 `./gradlew -PcoreOnly :core:test`, `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest`를 실행한다. 새 dependency가 필요해지면 임의 추가하지 말고 부모 dependency/audit 절차로 보정한다.
3. API35 **및** API36 각각에서 기존 CI와 같은 전체 instrumentation 명령 `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.notClass=com.ny.my.health.HistoryProcessRecoveryInstrumentedTest,com.ny.my.health.SyncStateProcessRecoveryInstrumentedTest`와 `bash android/ci/verify-history-process-recovery.sh`, `bash android/ci/verify-sync-state-process-recovery.sh`를 수행한다. 새 provider starter/SAF class는 제외하지 않는다. host hold class만 기존 정책대로 제외한다. 단일 API나 mock-only 통과는 양API provider 검증 완료가 아니다.
4. production/test APK merged manifests를 대조하여 provider/grant Activity가 test APK에만 있고 새 storage 광역 permission/OAuth/worker/background/계정 의존성이 없음을 확인한다. 기존 noBackup DB/binding 위치와 백업 제외를 실제 manifest/rules로 점검한다. 신규 LOCAL URI가 public logs/diagnostics/saved state/clipboard/screenshot/assertion 출력에 들어가지 않는지 source와 실제 산출물 allowlist를 검토한다. SDK/provider 원문 cause·본문은 오류에 보존하지 않는다. CSV serializer와 다섯 category coverage/네 dataset 회귀를 기존 테스트로 재확인한다.
5. fresh-context 별도 세션이 승인 spec+구현(계획/이전 검토 결론 제외)으로 적합성을 검증한다. 최신 head 전체 diff 품질·보안 검토, 지적 수정·재검토, 한국어 PR 댓글과 최종 reviewed head 기록은 AGENTS의 기존 gate다. 준비 세션은 GitHub를 수정하지 않는다. 부모 통합 담당자가 최종 실행 증거와 storage design 상태를 실제 검증에 맞게 갱신한다.
6. 실제 Galaxy/local provider selection/reboot/짧은 overwrite/offline/권한 철회/TalkBack과 기존 P0·키 백업은 수행한 증거만 반영하고 나머지는 PENDING을 유지한다. synthetic provider는 OS API/권한/앱 계약의 개발 증거이고 외부 provider 수용 증거가 아니다. cloud SAF와 background SAF는 계속 disabled/BLOCKED다.

## 5. 롤백

새 설정을 중지하고 기존 generation admission을 무효화한 뒤 PR을 되돌린다. 사용자 export나 기존 health target을 삭제하지 않는다. 새 LOCAL binding/generic URI를 모르는 구버전은 자동 DB 초기화/Drive fallback 대신 명시적 unsupported/reset 경계를 사용한다. 기존 LOCAL uncertainty/journal을 자동 지우지 않는다. 사용자 확인 reset은 기존 app-private 설정/coverage 범위만 다루고 foundation history/원격 파일을 보존한다. provider proof 실패는 비활성화이며 capability를 낙관적으로 복원하지 않는다.

### T10: 동일 source provider와 destination 실제 checkpoint

- **depends_on**: T4; **tier**: standard; **status**: pending.
- **변경 파일**: `docs/2026-11-05-v1-completion/saf-local/checkpoint-native.json` — root가 실제 run/head/artifact digest/XML class+method/count와 한계를 기록한다. 제품·테스트 코드 수정 작업이 아니다.
- **인터페이스**: §2 `SafCiProtocol` 참조.
- **작성할 테스트**: T1 starter와 T4의 기존 네 Native 행동을 재사용한다. 새 동작이나 테스트 삭제/skip이 없다.
- **호출 순서**: T4 source를 한 SHA로 고정 → saf-destination 및 provider fixture 재검증 saf-provider-starter를 API35/36에 독립 dispatch → ZIP hash/원본 XML/expected class+method/failure·error·skip·duplicate0을 root 대조한다.
- **완료 기준**: 두 mode 각각 양API 실제 PASS이며 진단 component 결과를 full gate라고 부르지 않는다. 실패 시 T4 원래 교정 회차로 귀속하고 T6를 시작하지 않는다.
- **검증 방법**: 고정 진단 saf-destination은 `SafDestinationInstrumentedTest,SafJournalInstrumentedTest`, saf-provider-starter는 `SyntheticProviderStarterTest`다. 기존 최종 전체 Native/host는 T7에서 별도로 그대로 수행한다.
- **주의사항**: T5의 두 신규 파일과 소유가 겹치지 않아 actual CI 대기와 controller 구현만 병렬화한다. 실제 기기/계정/외부 provider/TalkBack 수용은 미확인이다.

## 복구 기록

| 작업 | 회차/2 | 실패 증거·원인 | 새 근거·수정 가설 | 계획 보정 전→후·유지 기준 | 수정 파일·보존 대상 | 검증 명령·결과 | 실행자·다음 조치 |
|---|---|---|---|---|---|---|---|

## 환경 준비 기록

| 원인·근거 | 준비 조치 | 조치 사용/1 | 확인 명령·결과 | 확인 사용/1 | 다음 조치 |
|---|---|---|---|---|---|

## 실행 승인 — 2026-11-13

기존V1자동승인/병렬지시에따라root는실제PR17merge57da, 부모인터페이스/추적성통합ec8698, 독립fresh-context 계획검토a43788e APPROVE/차단0을대조해이구체7작업을승인했다. 부모SAF상태in_progress commit7538e87로rev를재고정했다. 계약·파일·검증기준은독립검토대상과같고frontmatter상태/승인근거만갱신했다. T1/T2는파일이달라병렬실행한다. T1의초기3파일은standalone provider 최신52f7f97를토대로재사용하되추가fixture/rawpicker경계와실제양API통과를확인전done아님. T3는T1뒤, T4는T2/T3뒤의gate를유지한다. 실제환경명령은설치된wrapper/JDK/sharedcache를사용하며로컬Gradle최대2개만동시에실행한다.

## 환경 준비 기록

T1첫wrapper는network-disabled sandbox에서Gradle FileLockContentionHandler wildcardIP확정전실패했다(compile0). 기존JDK/cache/버전/명령을유지하고로컬daemon socket을허용하는tool `with_additional_permissions/network.enabled=true`로실행하는환경준비1회를승인했다. source결함/Native실패가아니며T2는처음부터같은args로진행한다. T1의앞선상대경로편집은cwd android에서못찾아미적용이었고완료주장없다. 미적용된배정fixture마무리2개만절대경로로적용하는작업경로교정1회를기록하며전체파일재작성/임의재시도금지다. 실제추가검증실패시중단한다.

### 실행 기술 보정 — component CI 피드백

소유자의진행속도/병렬지시로기존검증을약화하지않는T8두파일작업을승인한다. 실측전체PR17 CI는약25분, 별도starter진단은약8분이며host/기존120Native를매component확인마다중복실행하면직렬대기가늘어난다. component별원본증거를먼저받되최종full은필수로유지한다. 기본full의새테스트자동포함과host/critical증거보존을정적/stub및최종Native로검증한다. 부모는CIownership만추가한bfd6591로재고정했고기존T1/T2계약/파일/성공기준은불변이다. Shared1 table-only SafKnownTargets 경고는root가실제method서명/두scan/current-prior/throw계약이그한줄과T2전체순서에완전히포함됨을확인했으며누락계약추정없이계속한다. §2전체전달이라는옛조립기설명은실제선별추출에맞춰문구만교정했다.

### T2 검토 보정1 — 실제 tree URI 형태와 기존 dedup 회귀 보존

T2첫build176JVM/0fail·error·skip와testAPKcompile은성공했다. rootdiff대조에서testhelper가standalone document URI를만들고있어실제targetHandle의tree/documentURI형태를직접확인하지않았고, 범위별서로다른4handle로확장하면서이전동일handle의집합dedup경계가명시검증에서빠졌다. 기존성공기준에따라testhelper를DocumentsContract.buildTreeDocumentUri→buildDocumentUriUsingTree로고정하고같은2scope동일current/prior의이전dedup+DriveAPI동등성단언을같은테스트에보존한다. production/storescan/기존추가corrupt·경계·URI검증불변. test파일1개만수정/compile확인하며176JVM은같은source로중복실행하지않는다. actualNative는계속pending이다.

### T8 검토 보정1 — 진단도 기존 공통 AVD 준비 유지

정적/stub은모든full명령동등성을통과했지만root통합검토에서새diagnostic분기가기존SDKsetup/launcher1회종료앞에서return하여공통계정없는AVD준비를건너뛰는것을확인했다. 기본full본문의동등성과함께진단도같은기존준비를적용하도록scope분기를공통준비뒤/critical앞으로옮긴다. 권한/버전/기존prep명령/호스트/필터불변. helper1파일만교정하고stub은모든diagnostic에서prep1회뒤고정class명령만실행·실패전파를확인한다. 실제componentNative는원본증거확인전pending이다.

### T1 실제 완료 / T4 fixture 검토 제안 반영

source4c7ed4392ce800c0c81615113e93f78640bbe226/run43439218159 양API각SyntheticProviderStarterTest1/0fail·error·skip을root가ZIPsha/XML대조했다. 실제targetUID·persistedRW/RO·revoke거부·exactbytes·private rawresultdata/flags단언을통과했다. actualActivityResultlauncher delivery는T6에서검증하며이를대체했다고주장하지않는다. testAPKJava-only/productionAPK부재와initialbuild1분20초확인후T1done이다. 이증거는최종SAFfull gate가아니다.

독립체크포인트리뷰4c7ed43은차단0·APPROVE와향후Document.FLAG_PARTIAL fixture추가Suggestion1을기록했다. 현재PARTIAL은silentcursortruncation이고INCOMPLETE는loading/error이므로leafpartialflag검증으로과장하지않는다. 기존SafTargetProtocol의partial-document차단기준을완전히검증하도록T4의파일소유에providerJava1개를추가(총4파일)하고고정enum/Column_FLAGS에명시FLAG_PARTIAL을추가하는제한범위를승인한다. T3는이파일을안쓰고T4는T3뒤라충돌없으며기존전체grant/source프로토콜/Java-only조건불변이다. T4직전브리프에는이추가계약을포함하고실제Native/full에증거를남긴다.

### T3 브리프 표기 보정1

조립된SafGrantProtocol table-only조각을root대조하니같은§2불변식의test-constructor주입허용문장이빠져있었다. 기존원문한문장만명시참조table로옮겨같은producer/consumer계약을전달한다. allowlist/새authority/기기수용/constructor businesspolicy 변경없다. 재조립해명시참조0미해소와해당문장Shared design포함확인후T3배정한다.

### 체크포인트1 실제 완료

같은source4c7ed43에서T1/run43439218159 양API1/0/0/0 및T2/run43439224531 양API2/0/0/0을ZIP해시/원본XML/정확class·method대조했다. genericURI actualtree/current/prior/dedup/4096·4097/오류경계·Drive호환과실제grant/query/read를각각증명해T1/T2done이다. T8의static실제singleline/stub·fullbody동등성과실제두dispatch모드양API기동을확인해done으로표시하며최종전체Native/host검증은전체계획gate로여전히필수다. sourcecheckpoint rawID/digest는checkpoint-native.json에보존했다. T3만실행중이며T4는T3실제capability양API통과뒤시작한다.

### T3 경로 준비 및 미확인 실패 경계 보정2

T3상대경로설정이cwd android와중복되어미적용된상태의build가시작됐다. 실행중source수정도뒤늦게확인되어그성공log를gate에서제외했다. source동결뒤별도절대경로편집/빌드로meaningful35초PASS를얻었고그원본만사용한다. Source실제기능실패로과장하거나미구현test없는compile을PASS로세지않는다.

실행자uncovered인mid-probe취소와create-after-effect 오류는기존취소재전파/ownedprobe-onlycleanup기준에기대결과가명확하다. T3테스트에실제Binder checkpoint진입을관측한뒤cancel→RELEASE_BLOCK→종결/반환proof없음을검증한다(청소성공및청소실패에서도원래CancellationException보존). CREATE_AFTER_EFFECT_FAILURE는BLOCKED/반환proof없음·미반환URI추측삭제0을단언한다. arbitrary sleep/timeout증가/mockgrant를쓰지않도록provider의기존BLOCK_BEFORE_EFFECT에private ResultReceiver 진입알림만추가한다. T3파일소유3개로제한하며T1기존grant/UUID/Java-only/enum관측/10초단언불변, T4는뒤에있어충돌없다. 실제Native는startup1class와capabilityclass를같은source의두진단mode로병렬확인한다. 기존문장추출보정1과누적2번째기술보정이며추가실제실패시원본보존/새근거후제한복구한다.

### 순수 UI 병렬 분리 승인 — T9

소유자의기존V1자동승인·병렬지시에따라root는실제core CoverageSummary/BatchStatus/DestinationGeneration을대조한exactstate/actions및captured-generation reset계약을승인했다. 새DTO1file은기존노출metadata와callback의소유분리이며DB/health/권한/동작요구추가아니다. 기존T5widget과T7pure semantics를T9의3파일로옮겨T3/T4와충돌없이작성한다. T5controller는T4와T9뒤, T6는둘다뒤이며T7실제복구/full gate불변이다. parent65abc08으로rev를고정했다. UI추가진단saf-ui는고정class1개만의optionaldispatch로root별도CI작성하며전체full을대체하지않는다.

### T8 순수 UI 고정 진단 추가 배정

T9병렬분리로기존4진단완료증거를보존하고fixed saf-ui choice1개를선택목록에추가한다. 같은기존두CI파일만대상이며공통prep/defaultfull/host/API/settings/timeout/권한불변이다. T8을추가분기검증기간만in_progress로되돌리고actualUI Native양API는T9완료조건과별도로대조한다. 기존성공을다시실패로바꾸거나source없는class를PASS로표시하지않는다.

### T9 환경 준비 및 T3 API 가용성 복구3

T9첫bareGradle호출은read-onlyHOME lock전에실패하여기존Pythonwrapper/JDK/cache선택환경준비1로수정했다. 다음wrapper는overlay7.9GB가100%에이르러변경없는core클래스출력단계실패했다. root는완료작업의regenerableintermediates만삭제하고옛build5개를/tmp로옮겨symlink경로를유지해소스/XML/reports/APK/원본CI증거를보존했다. free1.4GB와동일출력경로writecheckPASS후T9실패core Kotlin출력만정리한disk준비1을수행했다. 그뒤T9app/APK/testAPKcompile은성공했으나lint가T3의DocumentsContract.isChildDocument API29호출/min28 NewApi를발견했다. 이는UI소스문제가아닌실제T3호환성결함이다.

T3는소유자기존V1자동수정위임에따라누적3으로보정한다. app minSdk28/SDK/의존성은유지하고공개membership검증API가없는28에서는LOCAL capability를명시BLOCKED로중단한다. selected/restored URI의persist/probe전과isChildDocument사용직전에동일Build.VERSION.SDK_INT>=29 가드를적용해실제지원안되는호출/숨은fallback/파일생성을피한다. DRIVE/기존경로불변이며API28LOCAL/reboot/실기기수용을PASS로주장하지않는다. T3file1개guard·표기만교정하고wrapperlint/testAPK로검증한뒤새source양APIcomponent/UI를확인한다. 임의SuppressLint/RequiresApi로호출자책임전가/minSDK상향/새fallback금지.

### T9 리뷰 제안 보정1 — retry guard를 독립 검증

독립UI소스리뷰86270e5는차단0·APPROVE와Suggestion1을기록했다. disabled guard matrix가needsRetry=false인ready를기반으로해retry guard조건을독립검증하지못했다. 같은8개행의UI상태를PENDING outcome으로만강화해needsRetry=true에서도각foreground/consent/grants/scope/config/generation/RUNNING guard가버튼을막는지검증한다. 기존단언/제품소스/기타테스트는불변이며test한줄보정만한다. 이미존재하는enum/사용형태라중복localGradle없이정적diff후최종UI Native실행에서확인한다.

### T3/T9/T8 actual source 체크포인트 완료

source086b5b3의provider1/capability2/pureUI1각양API failure/error/skip/duplicate0을root가6ZIPsha/원본XML exactclass+method로독립대조했다. combinedlint/APK/testAPK2분52초PASS와UI소스리뷰086 APPROVE/제안해결및capability source차단0을대조했다. 이전eac provider36의UTP해석실패(테스트0)는새source 양API실제PASS로해소했다. T3실제Binder취소/cleanupfail suppressed/unknowncreate삭제0과11분기, T9 light/dark/font1/1.8·capturedgen reset/retryguards가실제실행됐다. T3/T9/T8done이며T2와함께T4착수gate충족이다. 전체SAF/실제Activity/실기기/TalkBack/background수용은미완료다. 원본ZIP는watcher추출dir/artifact.zip이며root첫경로오인읽기실패는검증성공으로세지않고정정후실제대조했다.

### T4 fixture 사용 계약 보정 — 실제 선택 target 제어

T4부여파일4개는유지한다. 기존MOVE_OUTSIDE_TREE/DELETE_SEEDED/DUPLICATE control은seed에고정돼실제adapter가만든날짜CSV의변경을검증하기에부족하다. 같은기존control의private 합성target-id extra를추가할수있게하며미지정은기존seed동작을그대로유지한다. ID는provider가만든nodes map의opaque key로만조회하고실제filesystem경로로해석하거나URI/ID/body를로그·오류메시지에넣지않는다. 권한/authority/UID/원래grants·10초단언/Java-only/binding/schema불변. partial은silentPARTIAL cursor와다른고정DOCUMENT_PARTIAL enum/Column_FLAGS의명시bit로구분한다. 테스트필요한합성target에대한제어뿐이고production능력/새UI동작이아니다.

### T5 실행 전 SDK-free ports 계약 구체화

T4 구현과 병렬 read-only 실제 API 조사로 기존 ManualDrivePorts 관례와 sealed private SafTreeProof/JVM 경계를 대조했다. 기존 승인된 suspend test ports를 같은 T5 파일 안의 ManualSafPorts로 구체화하며 생성자·문자열 ingress·실제 proof 생성 위치·즉시 picker 왕복 한 번·복원 READY 의미를 상위/하위 §2에 고정했다. 범위/권한/의존성/schema/DTO/기존 테스트 기준을 바꾸지 않으며 T4 product 계약도 그대로다. T5는 여전히 T4 완료 후 실행한다. 같은 tree·범위 재선택은 generation을 재사용해 불명확 결과를 보존한다. 소유자의 기존 V1 자동승인으로 이 구체화를 승인했으며 새로운 기능이나 승인 질문을 추가하지 않는다.

### T4 복구1 — DEX 이름과 초기 소스 검토 지적

첫 meaningful gate는 production/test Kotlin·기존 JVM·lintDebug·assembleDebug 뒤 dexBuilderDebugAndroidTest에서 `Space characters in SimpleName ... are not allowed prior to DEX version 040`로 실패했다(`/workspace/tooling/saf-t4.log`, 3분7초). 이전 성공 task를 전체 APK 성공으로 세지 않는다. initial 독립 소스 리뷰는 Important3(서로 다른 dataset에 동일 canonical ID, SecurityException 이유, encoded handle 경계)를 확인했고 root는 ViewModel main→runPrepared→SafDestination의 ContentResolver 호출에 IO dispatcher 전환이 없는 점도 확인했다. 같은 T4 네 파일 안의 초기 구현 교정 한 묶음으로 복구1/2를 부여한다. 기존 grant/UID/Java-only/SDK28BLOCKED/상태 schema/동의/10초 단언/건강 파일 무삭제/원래 callback·CancellationException 보존은 그대로다.

- Native method 이름은 동일 행동의 camelCase로 바꾼다. SDK/minSdk/DEX 설정을 올리지 않는다. 원래 네 행동/단언을 보존하고 최종 추적성에 실제 이름을 기록한다.
- provider IO만 기존 SafTreeConfiguration과 같은 Dispatchers.IO 경계로 이동하고 로컬 store/callback 예외는 provider catch 밖에 둔다. SecurityException은 cause 없는 DESTINATION_BLOCKED로 분리한다. snapshot 전체 dataset canonical target 충돌과 기존 core validHandle의 UTF-8 1..4096/whitespace·control 없음/encoder-canEncode 조건을 encoded URI에 적용한다. 기존/adopt/write 전 및 create 반환 직후, 건강 bytes 쓰기와 확인 전 차단한다. opaque ID를 경로로 해석하지 않는다.
- 같은 기존 Java fixture에 고정 `SECURITY_FAILURE`, `SAME_TARGET_ACROSS_DATASETS`, `OVERSIZED_TARGET_ID` 제어를 추가할 수 있다. synthetic target만 대상으로 실제 query SecurityException·두 dataset의 동일 node/정확 membership·encoded URI 길이 4096/4097 경계를 검증한다. private 숫자 byte-size extra는 이 마지막 고정 제어에만 제한하고 범위를 검증한다. 임의 path/URI/명령 실행·로그는 추가하지 않는다.
- Native 회귀는 실제 SecurityException의 정확한 BLOCKED/cause·suppressed 없음, alias의 mutation/callback0, handle 4096 허용/4097 차단 및 새 oversized create 후 health write/callback0을 검증한다. 기존 blocked-create 취소 테스트를 Main dispatcher에서 호출하고 실제 Binder 진입 뒤 main heartbeat가 기존10초 내 전달되는지도 검증하여 화면 스레드 점유를 구별한다. 실패해도 finally에서 provider를 release한다. 기존 네 행동을 삭제/skip하거나 단언을 완화하지 않는다.
- 교정 후 `:app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`를 exact Python wrapper로 한 번 실행한다. JVM production logic 미변경/기존176 성공 XML은 대조하고 중복 full JVM을 돌리지 않는다. source freeze·absolute paths·edit/build 분리·free500MB를 유지한다. 새 실패는 수정 loop 없이 보고한다. 이후 updated source 독립 재검토와 실제 양API component 및 최종 full gate는 여전히 필수다.

### 실제 component 대기와 T5 작성의 병렬 승인

소유자의 재개·병렬 지시에 따라 root는 T4의 안정 source/lint/APK/testAPK/독립 검토와 actual component 대기를 T4/T10으로 분리한다. T10은 원래 T4의 provider/destination 양API 시험을 그대로 소유하고 T6의 추가 선행조건이다. T5는 T4와T9 source 계약 뒤 충돌 없는 두 파일을 작성하므로 CI 대기와 겹칠 수 있다. scope/성공 기준/최종 full gate/원래 복구 ID/실패 중단 규칙은 유지한다. T5 brief의 소유 타입 불렛에 backtick을 넣어 assembler가 원문 전체 계약을 수집하도록 표기만 정정했다.

### T4 안정 source 완료 / actual checkpoint T10 시작

복구1 source의 lintDebug+assembleDebug+assembleDebugAndroidTest가2분1초/exit0으로통과했다. root는이전동일기존JVM core84/app92/합176/실패·오류·skip·중복0의원본XML을직접대조했다. 독립updatedsource APPROVE와네파일SHA-256을복사전후직접대조했으며이름/권한오류분류/canonical충돌/handle4096·4097/mainIO교정을확인했다. Native 실제실행을로컬build로대신하지않고T10에서동일source 양API destination4methods/providerstarter1method를검증한다. T4done은병렬분리한sourcegate만의완료이며T5두파일은동시에작성가능하고T6는actualT10PASS전차단한다. 원래T4복구1/2회차는유지한다. 새create4096성공 직접검증제안은비차단이며동일validator의existing4096채택/overwrite와new4097실패를현재검증한다. opaque ID의canonicalCSVadoption은fixture미지원한계로남기고실제SQLiteURI재열기/resolver동일성/잘못된경로차단을증거로사용한다.

### T10 외부 실행 차단 — runner 배정 전 Actions account 제한

sourcecb274caf의두고정mode dispatch(run43459510333/43459516911)는GitHub Actions계정결제/지출한도annotation으로시작전에모두차단됐다. 네job모두runner_id0/steps빈값/tests0/artifact0이다. 제품테스트실패나Native검증완료가아니며T4코드복구회차를차감하지않는다. 요청mode는saf-destination/saf-provider-starter지만실제runner scope는실행되지않아확인되지않았다. root는소유자에게GitHub Billing확인을요청했으며계정/결제정보는요청·수집하지않고자동billing변경·동일차단무한재시도도하지않는다. T10blocked/T6pending을유지하고독립T5로컬구현·검증은계속한다. 기존실행중WM gate는그대로완료결과를대조한다. 한도해소뒤동일고정source component를실제실행하고기존최종full gate/독립PR검토/댓글/merge의무를유지한다.

### T5 source/JVM 실제 완료

기존두파일만작성한T5의targeted ManualSafSessionTest4개/실패·오류·skip·중복0과assembleDebug가1분26초/exit0으로통과했다. root가원본XML4개method와두source SHA-256을독립updatedreview APPROVE와복사전후대조했다. 실제Uri호출/가짜proof없이JVM수명·순서검증을했고실제adapter는freshselect/restore+privateproof를쓴다. 기존LOCAL아닌binding은generation만CAS기대값으로보존하고LOCAL구성/coverage로표시하지않는다. source리뷰의null-binding직접분기및post-probe변경경계추가제안은비차단이며현재코드의fresh재확인/차단은검토했다. T5done이며실제SQLite/sharedgate/Activity/provider성공으로이JVM결과를확대하지않는다. T6는여전히T10actual양API실행전pending이고T10은runner시작전외부한도로blocked다. 다음component재개시최신통합source를고정하고T4네파일동일성과T5새추가를기록한다.

### T6 외부 gate 대기 중 실제 연결 seam 준비

read-only실제Activity/ViewModel/Home/기존Native호출자조사로필요한instancefactory/rawresultbridge/동일store-reader-gate주입/legacyreader보존/기존trailing-lambda오버로드/LOCALhasV1/draft와active표시차이를상위/하위§2에구체화했다. 기존T6네파일및동의·권한·generation·단일목적지정책만사용하며새dependency/schema/생산testallowlist/staticglobal을추가하지않는다. 이는실행전준비이며T10실제양APIgate가외부한도로blocked인동안T6제품구현은시작하지않는다. root는소유자기존V1자동승인범위로이기술적구체화를승인한다.

### T10 Public 전환 후 실제 실행 재개

소유자가저장소를Public으로변경했으며root가GitHub에서isPrivate=false/visibility=PUBLIC을직접확인했다. latesta1598b22051066ed7066f9a78ddec76b8eb1ef3c에두고정component를새로dispatch(run43478573782/43478579503)했고네API job모두nonzero runner와실제단계실행을확인했다. 이전두run의결제한도/테스트0기록은역사로보존하되현재T10을in_progress로올린다. Native실제결과는아직pending이며완료원본대조전T6를시작하지않는다. T4네파일은cb274caf와동일하고T5두신규파일/d51fde4가추가된통합source임을고정했다. Public전환은소유자가수행했고assistant는visibility/billing설정을변경하지않았다.

### T4 복구2 / T10 최초 실제 실패 — 합성 운동 fixture의 KST 날짜

latest a159 source run43478573782는양API4unique중동일1failure/오류·skip·중복0이며SafJournalInstrumentedTest.kt:65 expected BLOCKED/actualCOMPLETED다. 다른세Native method와provider(run43478579503)각양API1method는성공했고root가네ZIP SHA/원본XML/실패stack을독립대조했다. 실제운동fixture가 saf.time(2026-11-05T00:00Z)-7200/-3600을써KST11월5일07~08시가되지만대상일은11월4일이다. 기존DefaultNormalizer의day-overlap필터가올바르게제외해workouts CSV가없었고,의도한두번째readback실패를실행하지못해COMPLETED가됐다.

T4원래ID누적복구2/2로같은SafJournalInstrumentedTest.kt한파일만교정한다. 합성운동시간을DayWindow.start(saf.date)+3600/+7200으로대상일안에고정하고해당범위및두dataset fixture전제조건을검증한다. 승인된KST일별필터/실제provider/readback실패/기존BLOCKED·부분확인·재열기·unknowncreate·동일URI·원래취소단언은모두그대로다. 기대값을COMPLETED로바꾸거나단언을약화하지않는다. production/원래provider/다른세test·T5완료source를보존한다. 코드교정뒤동일Pythonwrapper :app:assembleDebugAndroidTest를1회실행하고새source독립delta검토및양APIcomponent를다시대조한다. 새실패시자동loop없이원본과새근거를보고한다. T4source교정동안in_progress/T6pending이며actualT10전체PASS전다음구현은시작하지않는다.

### T4 복구2 환경 확인 — AndroidTest incremental visibility

R2첫packaging은20초에기존cross-file internal SafDestinationFixture접근을모두거부했다. 기존선언은internal이며R1에서동일선언이실제컴파일/Native3method에사용됐다. 수정한운동시간/사전조건의syntax오류는아니다. 직전에root가완료build를/tmp로이동하고같은경로symlink를만들었으므로기존AndroidTest incremental metadata가남아있다는환경가설을한정한다(원인으로단정하지않음). source변경없이지정한두생성출력build/kotlin/compileDebugAndroidTestKotlin 및build/tmp/kotlin-classes/debugAndroidTest만제거하고동일assembleDebugAndroidTest를한번재검증한다. test-results/reports/APK/source·sharedGradlecache는보존한다. 환경준비1/확인1이며실패시추가삭제/재시도없이보고한다. 소스변경회차T4 2/2는그대로이고기대값/테스트판정/visibility modifier를바꾸지않는다.

### T4 복구2 소스·환경 검증 완료와 실제 재실행 준비

AndroidTest생성cache/classes두경로만제거한뒤source수정없이동일assembleDebugAndroidTest가41초/exit0으로통과했다. 따라서이전internalvisibility오류는cache환경준비로해소됐고visibility modifier를바꾸지않았다. 실제이동과오류사이정확한compiler원인까지증명한것은아니다. 독립R2delta APPROVE와새fileSHA40960015aac4ed43db809a7129284cc8839dec0c1173ba71f5899ed1f7914c9a를root가대조해현재source에통합했다. 다른세T4파일/T5완료source는불변이다. T4sourcegate done, T10의두고정mode는이새source에서양API재실행한다. 기존Native3PASS와provider1PASS는증거로보존하지만새composite전체통과를대체하지않는다. T4원래복구2/2·T6실제gate는유지한다.

### T8 실제 flow 고정 진단의 병렬 준비

T6 검증명령은새SafManualFlow2method와기존ManualDriveFlow6method의실제Activity검증이다. 전체Native/host는T7에서여전히필수이며T6마다전체120+개를중복실행하지않도록기존optionaldispatch에고정saf-flow1개를추가한다. 동일두CI파일은T6네제품/테스트파일과겹치지않아T10대기중작성/정적검증가능하다. 선택class는리터럴고정이며둘중source가없으면실패하고PR/기타event/빈값은그대로full이다. 새mode실제실행은T6source생성뒤하고최종full gate를대체하지않는다. 기존T8수정회차를리셋하지않고이는소유자기존V1자동승인·병렬지시안의진단범위추가작업이다.

### T10 실제 완료와 T6 재개

sourcef863887001e788a11f31bff563905751830266f7의run43480662382는양API각정확4method, run43480668622는양API각정확provider1method가성공했다. root가네ZIP SHA/원본XML/exactclass+method/실패·오류·skip·중복0을직접대조했다. T4원래fixture실패는교정후실제로통과했고T10done이다. T6의선행조건이모두충족되어실제Activity네파일연결을재개한다. 전체SAF/fullNative/host/최종freshPR검토·댓글·병합은아직미완료다.

### T6 작업 크기 보정1 — Terra 제품 배선과 실제 flow 시험 분리

첫Terra 실행자는네파일전체의배선과새Native2개를한세션에완료할수없다고보고했고파일변경/빌드/테스트는0이었다. 작업실패를성공으로세지않고원래T6를제품배선3파일source gate와의존T11 actualflow1파일로나눈다. 기존두행동·실제SQLite/provider·양API8methods gate를T11에원문유지하고T7선행을T11로옮겼다. T6는기존180 JVM/빌드/lint/testAPK·독립source검토까지이며실제E2E통과라고하지않는다. 원래T6기술보정1/2를기록하며모델교체로횟수를초기화하지않는다. 소유자의Terra작업별새세션·자동승인·병렬지시에따른작업크기보정이고scope/성공기준/권한/데이터/의존성/schema/최종full gate는불변이다.

### T8 saf-flow source 정적 검증 완료

기존부분편집13줄을Terra가보존한채정적/stub검증했고root도같은검증/bashedsyntax/diffcheck를직접실행했다. 별도Terra세션source APPROVE와두SHA를root가복사전대조했다. 선택목록1줄과고정case12줄만추가됐고기존full본문/host/설정은byte동일하다. staticAST의127은Starter이름누락이있어최종Nativecount로쓰지않는다. 실제saf-flow 양API실행은T11신규source뒤이며T8은그때까지in_progress다.

T11은원래T6의Native부분을분리한것이므로T6기술보정1/2이력과잔여복구예산을승계한다. 작업ID분리·Terra새세션으로원래회차를초기화하지않고실제실패의원인제품/테스트작업에귀속한다.

### T6 복구2 — source 검토의 결과 보존·다른 목적지 projection 무효화

제품3파일첫gate는3분6초/exit0이고root가core84/app96 합180unique/실패·오류·skip0의원본XML을대조했다. 별도Terra source review는Important2를발견했다: SyncViewModel continueSaf/retrySaf 직후refreshDestinations→saf.refresh가새outcome/BLOCKED를즉시지우고, Drive selectAccount configure/reset 뒤SAFprojection을갱신하지않아oldLOCALcoverage가활성으로보인다. T6원래크기보정1뒤누적2/2로SyncViewModel.kt한파일의통합callback/metadata 갱신순서만교정한다. SAF실행직후는새결과·phase를보존하면서필요한다른projection만갱신하고Drive구성/reset 뒤는fresh양projection/strictkind로generation변경을반영한다. AuthorizationTicket/새동의/foreground/입력스냅샷/이전완료이력/데이터·schema·권한은유지한다. MainActivity/Home/controller파일은동일하게보존한다.

T11의기존두행동에실제VM액션직후COMPLETED·BLOCKED결과보존과priorSuccess분리, LOCAL→Drive/reset generation뒤LOCAL표시무효화를기대결과명확한회귀로추가했다. 새testfile은여전히T11한개이며T6실행자가작성하지않는다. 교정뒤app96 JVM/APK/lint/testAPK만1회검증하고변경없는core84는중복하지않는다. updatedsource독립재검토와실제T11양API8methodgate·최종full검증은필수다. 원래T6/T11분리예산은공유하며새실패는원본/새근거를보고하고숨은fixloop를하지않는다.

### T6 추가 정합성 교정3 / T12 분리 — 실제 binding·foreground 결과 보존

R2는app96/lint/APK/testAPK 2분8초PASS이고root가app XML96을대조했다. updated독립Terra는확정Important2를추가로한정했다: 기존ManualDriveSession.refresh는LOCAL도Driveconfigured로투영하므로strictreadBinding 없이양방향draft가coverage를오표시하며, resume/권한refresh 및setForeground.projectPhase가samegeneration결과/BLOCKED를지운다. 이전수정소스를보존하고소유자의기존V1전체자동수정승인에따라새근거의추가교정을수행한다. 원래T6회차는누적3으로정직하게유지하며작업ID/세션으로리셋하지않는다. 새범위/성공기준/권한/schema/외부서비스도입이나실계정행동은없다.

T6제품3파일은generation과맞는실제privatebinding의kind를내부state로읽고unknown/invalid는failclosed, draft와별개로양UI의configured/coverage/outcome 및허용행동을현재kind에맞게투영한다. rawbinding/URI는UI에내보내지않는다. 실제모드변경은durableconfigure/reset/export없이두session의지연ticket/실행을메모리동의·입력경계로무효화하고기존generation/coverage를보존한다. 기존profiling sessionFactory생성자·legacy테스트호출이별도주입store를쓰므로defaultstore의새UIprojection을그기존실행API의잘못된guard로쓰지않는다. Home은CoverageSummary.toString을제품문구로내보내지않고사람이읽을날짜/건수만표시한다.

결과보존은T12의기존controller/JVM2파일로분리해T6UI3파일과병렬교정하며T11은둘다완료뒤실제두행동/8method를검증한다. 기존R2의즉시결과보존·Drive변경후양projectionrefresh수정은보존한다. T12도원래T6통합교정의회차를승계한다. 각부분은1회교정/검증후새실패를보고하며동일가설무한재시도하지않는다.

### T6 추가 교정4 / T12 순서 경계 — 실제 source 새 증거

R3 제품3파일은3분23초/exit0으로컴파일·appJVM/lint/APK/testAPK를통과했으나독립source검토가새인가race를확정했다. selectManualAccount가받은ticket뒤refreshDestinations를await하는동안모드/동의가무효화돼도이후bridge.authorize가category만보고Google요청을시작할수있다. 소유자의기존V1자동수정범위에서이새근거를교정하며원래T6누적4를숨기거나리셋하지않는다. 이전세대표시·R2/R3수정은보존한다. MainActivity/SyncViewModel의인가직전live foreground/consent/Drive draft/currentgeneration/category를검사하고유효ticket반환과요청사이에새suspend공백을두지않는다. 새외부왕복allowance를단순검사용으로미리arm하지않는다. 실제UI권한확대/새OAuth흐름이아닌기존동의철회거부보강이다. Home파일은보존한다. T11같은두행동에늦은picker/metadata복귀뒤모드·동의무효화시새인가요청이없음을검증한다.

T12첫gate는targeted5JVM/실패·오류·skip0 및APK1분52초PASS이며root가원본XML을대조했다. 독립Terra는새순서반례를확정했다: COMPLETED→선택grant전부철회→continue preflight의freshAccess가grants만갱신하고실패→refresh가이미갱신된scope를비교해옛COMPLETED를보존한다. 같은controller/test두파일에서freshAccess의실효scope변경과명시새owner실행시작(preflight실패포함)에옛outcome을무효화하고busyMERGED가진행중owner결과를지우지않게한다. 같은5번째회귀에이순서를추가하고기존same-generation/foreground BLOCKED보존·새scope/세대승격금지·4기존행동을유지한다. 이T12부분도원래T6통합교정의누적이력에연결하며새가설없이무한수정하지않는다. 두실행자는각각1회교정/검증후결과를보고하고updated독립재검토·T11실제8method/최종full gate는그대로다.

### T6/T12 source gate 실제 완료와 T11 시작

T6R4 source3파일은app96JVM/lint/APK/testAPK2분19초PASS와독립updatedTerra APPROVE(실제T12/T11조건부)를받았다. T12수정2파일은targeted5JVM/APK44초PASS와독립updatedTerra APPROVE로처음finding을해결했다. root가두원본XML집합(96+5중4공통=app97)·실패/오류/skip0·다섯sourceSHA를직접대조해통합했다. core84는불변기존검증을보존한다. 통합source의app97는T11신규Native파일compile때같이실행하고최종full에서는core84+app97=181및전체Native/host를검증한다. T6/T12done은source/unitgate만이며T11actualVM/Activity/SQLite/provider검증은아직미완료다. T11은두선행gate완료뒤한파일의두실제행동을작성한다. 원래T6교정이력1~4및T12추가순서교정은숨기거나리셋하지않는다.

### T11 실제 반환과 controller 수락 사이의 결정적 제어

실행자는GrantActivity가즉시finish라fixturehold가없다고보고했으며소스변경/빌드0이다. root는기존MainActivity.internal var safBridge와원래productionbridge의selectTree callback을대조했다. Native파일안의test wrapper가원래bridge를그대로호출해실제GrantActivity/StartActivityForResult/실제URI·flags/productionbuffer를통과시키고,그반환후controller로전달할callback만private메모리에보관할수있다. 관측gate뒤실제input편집또는ActivityScenario추가pause/resume를완료하고동일actualURI/flags로소비callback을호출하면capturedticket의stale/secondpause거부를결정적으로검증한다. positive/read-only는보류없이원래bridge를써rawresult와동의·설정만/명시실행을검증한다.

이는GrantActivity자체나framework rawcallback전단계를hold했다고주장하지않고실제반환→appcontroller수락경계를제어한다. 원래한Native파일/기존seam/실제SQL·provider/기존두행동을보존하며flags를발명하거나fixture/manifest/timeout/sleep을추가하지않는다. 소스에이미있는callback연결의clarification이고권한/동작기준을좁히지않는다. 필요하면metadata access gate도가능하지만실행자는간단한기존bridgewrapper를우선한다.

### T11 실제 행위 검증 보강 — source review의 관측·음성 분기

첫신규Native2method는app97 JVM/testAPK2분1초PASS이고root가통합원본XML97unique/실패·오류·skip0을확인했다. 독립Terra source는Important3를확정했다: 무효동의·편집draft에서새인가dispatch거부가없고, LOCAL을reset한뒤Drive로전환해실제committedLOCAL대draftDRIVE투영을검증하지못하며, callback replay뒤Compose idle만기다려IO구성완료전false상태를단언해잘못수락된구성도지나갈수있다. 소유자기존V1자동보완범위의새근거로같은Native1파일을교정하며원래T6/T11이력누적5로정직하게연결한다.

기존두method를유지하며실제기존LOCAL구성/coverage가존재한채draft만Drive로바꿔binding/generation불변·보이는Drive미구성/Local읽기전용증거를먼저단언한다. 그뒤실제Drive구성/reset및늦은credential거부를검증한다. 무효consent/draft/category각명시인가진입에서authorize counter가증가하지않음을검증한다. callback/명시액션의비동기처리는publicviewModelScope Job의전후newchild집합을Main에서포착해JUnitworker에서기존10초내join하는등의결정적완료관측을쓰고,그뒤재열기한SQLite에새구성이없음을확인한다. reflection/새API/임의sleep/timeout증가가아니다. providerbaseline조회는pickerlaunch전, post조회는consumer처리완료뒤로배치해OBSERVEActivity자체의pause가stale거부를만들지않게한다.

실행자uncovered3도같은단위로보완한다: 실제성공retry후COMPLETED와같은건강파일identity유지,grant0의create/write/delete전부불변,입력snapshot+저장revision생성시각으로실제expectedCSVbytes를구해targetresolver와Boolean으로exact비교한다. sharedcore CSV계약검증은재사용하며URI/CSV를assertEquals출력으로노출하지않는다. 허용된syntheticprobe쓰기와health export를구분한다. 교정뒤같은app97/testAPK명령1회·updated독립재검토·양API8methodactual을그대로수행하고실패시숨은loop없이보고한다.

### T11 추가 교정6 — metadata 완료 관측과 독립 CSV oracle

교정1의 app97/testAPK는38초PASS이며 frozen SHA d2a2bef7d48592347a23c75f731c5b21b765ddaa28f0bf8e4d52e584ef744769를독립재검토했다. 원래Important3와grant0/성공retry분기는해결됐으나새Important2가남았다. refreshPermissions의legacy refreshing=false는별도SAF metadata job완료증거가아니므로동일public Job child완료관측을refresh와실제resume주변에도적용한다. ActivityScenario lifecycle호출은JUnitworker에두고Main에서전후job집합만포착하며Compose idle/기존false상태를완료로간주하지않는다. exactCSV기대값은같은production normalizer/exporter로계산하지않고docs/data-contract.md의고정schema/합성steps=1/빈ungranted값/저장generatedAt에따른독립literal UTF-8 bytes로비교한다.

기존한Native파일/두method/권한·생성·쓰기·삭제음성단언은유지한다. 소유자V1자동수정위임에따라새근거의같은범위교정6으로원래누적을이어기록하며reset하지않는다. 교정후compile/unit명령1회와updated별도재검토뒤양API실제8methods를진행한다. 새실패는숨겨재시도하지않는다.

### T11 보강 source gate 완료와 실제 양 API 실행

교정6 frozen Native파일 SHA23993e7063a2bb612062d953c90746e10d0b1ebe983eeee4b3f64761ca279d8c는app97/testAPK36초PASS와updated독립Terra source APPROVE를받았다. root가원본XML97unique/실패·오류·skip0과sourceSHA를대조해통합했다. source승인을Native통과로간주하지않으며saf-flow고정두class/8methods를이source의API35·36에서실행한다. 실제원본ZIP/digest/XML까지대조한뒤에만T11/T8done과T7진입을처리한다. 기존full/host/최종freshPRreview는불변이다.

### T11/T8 actual checkpoint 완료와 T7 진입

run43496000449는source c134515d2266d175174e8b1de45a26e648008f6a의API35·36각각exact SafManualFlow2+ManualDriveFlow6=8unique를실제통과했다. root가두ZIP SHA256/digest와원본XML의class/method/실패·오류·skip·중복0을직접대조했다. root검증기록은saf-flow-root-verification.json이다. 전체로컬gate는동일source에서2분58초PASS(core84+app97=181/lint/APK/testAPK), root원본XML검증0fail/error/skip/dup이다. T11/T8을done으로하고T7의두기존Native파일복구회귀를시작한다. filtered8증거를전체Native/host또는실기기수용으로간주하지않는다.

### T7 교정1 — 실제 Activity recovery fixture 연결

첫실행자는DestinationConfiguration의localConfigurationFailureRestoreAndResetPreserveDriveBindingAndHistory를추가하고testAPK1분26초PASS이나실제Activity retry/journal reopen을미작성으로보고했다. 기존JournalFixture의synthetic-saf와직접attempt setup은ManualSafSession의local-tree/실제admission을바로대신하지못하며T11privatefixture도직접재사용할수없다. 미완료를done으로바꾸지않는다.

같은T7의누적교정1로기존config부분산출물을보존하고SafJournal기존파일에만bounded private Activityfixture를추가한다. 이미존재하는MainActivity.viewModelFactory/SyncViewModel.ManualDestinationDependencies/SafTreeConfiguration.forTesting/separate realSyncExecutionSession및동일reader/실제provider를사용하고T11의필요최소precreatedfactory패턴을참고한다. 새production seam/fixturefile/가짜proof는금지한다. JournalFixture에새테스트만명시적으로local-tree구성을쓰는옵션을둘수있으며기존default synthetic-saf·기존두method기대는유지한다. 실제engine/store로만든확인·미확인상태를복원해실제화면retry를누르고완료를관측한뒤SQLite재열기로확인된파일/unknowncreate/oldgeneration불변을검증한다. unknown을성공으로바꾸거나faultmode를무시하지않으며안전BLOCKED보존도정확한기대다. 기존테스트·권한·최종양API전체/host gate는유지하고같은테스트파일compile1회뒤독립검토와전체실행을진행한다. 별도세션은원래T7의회차를승계한다.

### T7 교정2 — 독립검토의 구체적 반례와 양 fixture 보강

첫Activityfixture compile36초PASS이나updated source검토와작성자uncovered가새반례를확정했다. 구성파일Important2(binding전체미비교/주입callback도달미관측), journalImportant4(복원LOCAL의중복mode/pickertext, providerreset으로확인파일삭제, click후job스냅샷race, rawURI assertEquals출력)이다. 같은T7교정2이며부분산출물/기존회차를보존한다. 파일이겹치지않는두수정은병렬작성하고두source고정뒤compile1회로합류한다.

구성파일은실패전후Drive전체binding과성공후LOCAL전체binding을Boolean비교하고주입commit실패callbackcounter증가+정확UNAVAILABLE를단언한다. reset후binding부재도확인한다. journal은providerreset을제거한다. 먼저실제기존확인파일을수정하고SQLite확인만실패시키는기존failConfirmation을이용해정상provider에서pending을만들고,실제Activity의명시retry로같은파일을adopt/confirm하는경로를검증한다. 그뒤Activity를닫고새세대에서기존daily는남긴채EXERCISE/snapshot을추가해없는workouts경로에실제create-after-effect불명을만든다. 새localcategories옵션은새테스트에서만쓰고기존default를유지한다. 이전/현재daily확인과bytes/identity를보존하고unknown을성공으로승격하지않는실제UIretry/BLOCKED뒤재열기·oldgeneration격리를검증한다. persistentfault에서freshprobe가거부되면이를엔진reconciliation성공으로주장하지않으며앞의정상provider명시retry가실제엔진복구증거다.

복원LOCAL에서는mode전환버튼을불필요하게누르지않고privacy checkbox와실제retry를선택한다. retry는Compose public OnClick semantics action callback안에서Main의job전후집합을원자적으로관측하고JUnitworker에서join한다(이미완료된job이면집합비어도정상이며outcome변화/실제admission관측을별도로단언). 단순click뒤뒤늦은child존재단언이나기존BLOCKED대기만으로완료를판정하지않는다. URI/bytes/전체binding은Boolean비교로출력금지다. 제품/fixture외파일/권한/전체최종gate는불변이다.

### T7 교정 source gate 완료 — 전체 actual gate 대기

최종두파일은root testAPK29초PASS와updated독립Terra source APPROVE를받았다. configuration SHA d9087d56a5a97aedd562ce811f47cc8ca5d969ab7f81ff0d0721b9185532e29d, journal SHA b449cb54023872e260239b76aa5f24b1e4a1aed866854df7bcb6165143dd9e31를root가대조해통합했다. 정상provider의실제UIretry→COMPLETED/samefile채택과persistentcreatefault의실제UIretry→freshprobe BLOCKED/unknown보존을구별한다. 후자를엔진reconciliation성공으로주장하지않는다. 기존actualreconciliation행동은그대로다. 전체API35·36/기존host/JVM/lint/APK와최종freshspec적합성·품질/보안·PR댓글·reviewedmerge 전T7/saf-local완료로표시하지않는다.
