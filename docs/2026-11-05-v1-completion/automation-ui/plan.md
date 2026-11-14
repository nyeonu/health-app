---
plan_id: automation-ui
title: 제한된 자동 동기화와 현재성 화면
status: in_progress
author: Codex
created: 2026-11-13
parent: ../plan.md
parent_rev: 3e713e6c0c87a3f10b8e2800a934708b047ea68c
spec: ../spec.md
spec_rev: 97c01b1992ad7935e6fa8f1f8b371a3963457daa
branch: feature/v1-auto-retry
parallel_branches: [feature/v1-auto-retry, feature/v1-auto-summary, feature/v1-auto-diagnostics]
source_base: 75cc443702a3467c87268d49ce52d4d75b2ed8a5
execution_gate: actual-PR19-merge-contract-parent-ADR-repin-independent-readiness
tasks:
  - {id: T19, title: WorkManager helper process 격리 기반, depends_on: [], status: done, tier: standard}
  - {id: T1, title: 실제 WorkManager starter 기반, depends_on: [T19], status: done, tier: standard}
  - {id: T2, title: durable retry 모델과 검증 경계, depends_on: [T1], status: done, tier: standard}
  - {id: T3, title: 검증된 v1 상태의 v2 migration, depends_on: [T2], status: in_progress, tier: standard}
  - {id: T20, title: v2 도입의 future schema fixture 호환, depends_on: [T1], status: in_progress, tier: light}
  - {id: T21, title: 전체 gate를 보존하는 고정 retry 진단 실행, depends_on: [T1], status: done, tier: light}
  - {id: T4, title: retry claim의 실제 SQLite 원자성, depends_on: [T3, T20], status: pending, tier: standard}
  - {id: T5, title: 공용 session의 자동 attempt 연결, depends_on: [T4], status: pending, tier: standard}
  - {id: T6, title: 누락과 출처를 보존하는 aggregate 값, depends_on: [T1], status: in_progress, tier: standard}
  - {id: T7, title: reserved CSV 요약과 의미 revision, depends_on: [T6], status: pending, tier: standard}
  - {id: T8, title: 실제 HC background category admission, depends_on: [T1, T7], status: pending, tier: standard}
  - {id: T9, title: 불명확한 운동 심박의 안전 차단, depends_on: [T8], status: pending, tier: standard}
  - {id: T10, title: worker용 fresh 계정 authorization, depends_on: [T1, T5], status: pending, tier: standard}
  - {id: T11, title: 약일일 periodic와 bounded catch-up 등록, depends_on: [T1, T5, T9, T10], status: pending, tier: standard}
  - {id: T12, title: worker retry와 프로세스 재시작 회귀, depends_on: [T11], status: pending, tier: standard}
  - {id: T13, title: 동의와 자동 설정의 generation 전이, depends_on: [T12], status: pending, tier: standard}
  - {id: T14, title: 현재성 화면의 IO 없는 투영, depends_on: [T13], status: pending, tier: standard}
  - {id: T15, title: 실제 Activity에서 자동 설정과 gap 표시, depends_on: [T14], status: pending, tier: standard}
  - {id: T16, title: 취소와 접근성 통합 회귀, depends_on: [T15], status: pending, tier: standard}
  - {id: T17, title: 해결 의존성 최종 재감사와 개발 증거, depends_on: [T16], status: pending, tier: standard}
  - {id: T18, title: roadmap과 공개 안내의 실제 상태 반영, depends_on: [T17], status: pending, tier: standard}
---

# 제한된 자동 동기화와 현재성 화면

## 1. 맡는 기준

상위 추적성의 automation-ui 및 전체 검증 행을 그대로 인용한다. 다른 slice와 공유하는 기준은 기존 검증을 재사용하며 이 계획만으로 전체 에픽 완료를 선언하지 않는다.

| 스펙 기준 | 테스트 이름 (출처/층) | 충족 위치 |
|---|---|---|
| 앱 시작은 자동 opt-in이 없으면 감지·표시만 수행하고 자동 opt-in이 있으면 같은 planner의 unique catch-up을 enqueue하며 읽기/업로드를 UI 복원 부수효과로 직접 실행하지 않는다. | `Opening with automatic off only displays gaps and automatic on enqueues one catch up` (①·④/통합 및 Compose E2E) | automation-ui 완료 기준 |
| EMPTY·제한된 category 확인·upload confirmation·연속 checked-through·uploaded-through를 구분하고 일부 허용 범위나 worker 실행 성공만으로 5종 전체 완료·현재성·gap 없는 coverage를 주장하지 않는다. | `Empty and partial grants never claim five categories upload success or continuous uploaded coverage` (①·②·④/유닛 및 Compose E2E) | core-store; automation-ui 완료 기준 |
| 자동 실행은 하루 전체 원시 심박 스캔을 재사용하지 않고 운동 구간의 bounded 상세 조회와 지원되는 요약 Aggregate를 사용하며 권한·세션·조회 의미가 불명확한 심박 범위는 BLOCKED/미확인으로 표시하고 수동 대상 조회와 coverage를 혼합하지 않는다. | `Automatic workout heart queries stay bounded and uncertain scopes block without mixing manual coverage` (①·②·③·④/HC adapter 통합 및 유닛) | automation-ui 완료 기준 |
| 이전 populated dataset이 비게 되면 잔존 원격 파일을 REMOTE_STALE로 표시하여 새 revision의 full-current를 막고 원격 삭제·완료를 꾸며내지 않는다. | `Disappearing populated datasets retain remote stale and prevent full current without deletion` (①·④/유닛 및 Compose E2E) | core-store; automation-ui 완료 기준 |
| 전경·주기·catch-up·중복 UI가 동시에 진입해도 공용 gate로 단일 실행만 시작하고 프로세스 손실 후 durable journal을 복구하며 설정 generation 변경 뒤 지연 결과가 새 coverage를 덮지 못한다. | `Concurrent manual periodic and catch up requests admit once and reject old generation callbacks after restart` (①·③·④/유닛 및 SQLite·worker 통합)  <br>`Prepared session admits one credential factory and busy callers only merge triggers` (①·②·③·④/아래 manual-drive 작업 층)| core-store; manual-drive; automation-ui 완료 기준 |
| 자동 기능은 기본 off이고 전경에서 목적지·범위·지연 가능성 설명과 별도 opt-in 후에만 약 24시간 unique periodic CoroutineWorker를 connected/battery-not-low 제약으로 구성하며 exact-time·당일 완료를 보장하지 않는다. | `Explicit automatic opt in creates one constrained daily CoroutineWorker without exact time promises` (①·②·④/worker 통합 및 Compose E2E) | automation-ui 완료 기준 |
| worker는 background HC feature/permission과 현재 category grant를 재확인하고 authorize resolution·누락 token/scope·계정 오류는 BLOCKED로 남기며 interactive UI를 시작하거나 token·auth code를 저장하지 않는다. | `Worker missing background health grant token scope or account records blocked without interactive launches` (①·③/worker 통합) | automation-ui 완료 기준 |
| 일시 네트워크/허용된 service 실패만 exponential backoff로 최초 실행 외 최대 3회 재시도하고 영구 실패·불명확 create는 맹목 재전송하지 않으며 retry 소진은 다음 명시적 trigger까지 visible pending/blocked로 남긴다. | `Only transient batch failures retry at most three times and uncertain creates reconcile first` (①·②·③·④/유닛 및 worker·TLS 통합)  <br>`Explicit manual recovery reopens eligible blockers without deleting file uncertainty or remote stale` (①·②·③·④/아래 manual-drive 작업 층)| automation-ui; manual-drive 완료 기준 |
| 자동 opt-out·설정 변경·reset은 queued work와 현재 generation의 admission을 무효화하고 이미 발행된 원격 쓰기를 취소로 rollback했다고 표시하지 않으며 기존 이력 삭제와 설정/coverage reset 범위를 각각 정확히 안내한다. | `Opt out configuration change and confirmed reset invalidate old admission while preserving history reset scope` (①·③·④/SQLite·worker 통합 및 Compose E2E)  <br>`Manual configuration and reset use generation CAS and never erase foundation history` (①·②·③·④/아래 manual-drive 작업 층)| manual-drive; automation-ui 완료 기준 |
| home은 현재 목적지·등록 이후 gap·연속 확인 범위·마지막 업로드·recent freshness·queued/blocked 사유와 다음 허용 동작을 구분하고 stale/영구 실패가 과거 성공에 가려지지 않으며 EMPTY를 업로드 완료로 쓰지 않는다. | `Home shows gaps coverage freshness last upload blockers and stale separately from prior success and empty` (①·③·④/유닛 및 Compose E2E) | automation-ui 완료 기준 |
| light/dark·큰 글자·Compose semantics 검증에서 동의·계정/폴더 선택·자동 설정·수동 실행·재시도·reset 확인에 도달할 수 있고 실제 TalkBack·Galaxy 관찰은 별도 미확인으로 남긴다. | `Consent account tree automatic manual retry and reset remain reachable in dark light and large text semantics` (①·②·⑤/Compose E2E)  <br>`Manual consent picker authorization and explicit continue remain reachable in light dark and large text` (①·②·③·④/아래 manual-drive 작업 층)| manual-drive; automation-ui 완료 기준 |
| app-private 상태/backup 제외와 로그/진단 allowlist를 검증하여 raw 건강 데이터·CSV·token·계정/기기/문서 식별자·URI·provider 본문을 공개 로그·fixture·CI artifact에 포함하지 않는다. | `Private state and allowlisted diagnostics exclude health payload credentials identifiers and provider bodies` (①·③/저장소·TLS·provider 통합 및 정적 검증)  <br>`Manual credentials never enter saved state store diagnostics or public fixtures` (①·②·③·④/아래 manual-drive 작업 층)| 전체 검증 3 완료 기준 |
| 실제 store 재열기·손상/미지원 상태·실행 취소·동시 admission·local TLS·합성 provider·worker 제약/권한·UI 회귀와 APK/lint 검증을 수행하고 각 PR의 독립 최신 head 검토를 마친 개발 완료와 Galaxy/계정/provider/Doze/배터리/TalkBack 수용 대기를 별도로 보고한다. | `Real store cancellation admission TLS provider worker and UI suites pass with device acceptance separately pending` (①·③·④·⑤/통합·E2E; review와 실제 기기는 비자동 항목)  <br>`Manual flow rejects stale proof pause reset and double continue while retaining legacy layout and history regressions` (①·②·③·④/아래 manual-drive 작업 층)| 전체 검증 1·2·4·5 완료 기준 |

## 2. 실행에 필요한 현재 상태

### 실행 전 고정점

- 기존 V1 자동승인을 적용한다. 실제 SAF merge `9ab774beac85bf7a96953bb0a0aa1b8098c76c69`와 완료된 WM6파일 통합 `6c5f5b0412029054cc992adc09a2296bcf0c696c` 뒤 부모 공통계약/ADR010 Proposed를 통합했다. 현재는 독립 계획 검토 전 draft이며 parent/spec revision 검증과 검토 통과 후 approved/in_progress로 갱신해 T19부터 실행한다. 실제 계정·기기·background 활성화 승인은 포함하지 않는다.
- 초기 조사 `c334671`와 잠정 SAF 계약은 당시 이력이다. 현재 source_base에는 실제 ManualSafSession/SAF UI/loadKnownTargets와 WM runtime/testing2.11.0·starter·검증된 test window/hot-journal 보완이 통합되어 있다. 현재 source와 계약이 다르면 먼저 기록·보정하고 추측으로 구현하지 않는다.
- 각 실행자는 해당 task의 명시 파일만 수정한다. 작성/검증/독립 검토는 분리하고 모든 PR은 최신 head의 한국어 finding/fix/re-review 댓글과 필수 전체 체크 완료 뒤 정상 병합한다. 실제 계정/키/health upload/grant 조작은 이 실행 범위에 포함하지 않는다.
- A=`android/app/src/main/java/com/ny/my/health/`, AT=`android/app/src/test/java/com/ny/my/health/`, AI=`android/app/src/androidTest/java/com/ny/my/health/`, C=`android/core/src/main/kotlin/com/ny/my/health/core/`, CT=`android/core/src/test/kotlin/com/ny/my/health/core/`. 신규 이름은 제안이며 현존 API로 표현하지 않는다. 브리프는 이 §2와 해당 T를 함께 전달한다. 상위만 읽는 assembler를 쓴다면 먼저 상위 §2에 이름을 통합한다.

### 확인한 사실

| 실제 source | 확인한 경계 |
|---|---|
| `SyncExecutionSession.runPrepared` | canonical DB path 공용 gate; busy caller는 merge; prepare는 owner만 호출. 현재는 새 attempt마다 retriesUsed=0. retryKey는 source reread 전 old revision/UNREAD 해시. `get(app)`은 applicationContext당 최초 session(생성자 reader 포함)을 캐시하고 현재 SyncViewModel은 manual reader로 이를 만든다. `detectPending()`도 config.automatic에 따라 CATCH_UP/MANUAL을 선택해 그 reader.access를 호출한다. 자동 worker가 이 캐시를 재사용하면 SCHEDULED가 계속 BLOCKED이며 reader 구현 추가만으로 해결되지 않는다. UI restore는 record read 없는 metadata 경계만 사용한다. |
| `SqliteSyncStateStore`/`SyncStateDatabase` | generation당 attempt 한 행; start가 이전 row를 delete/insert. `transact`는 한 payload이고 retry count update/lineage 보관 API 없음. exact schema+user_version=1만 허용. config/domain version도1이나 SQLite schema와 의미가 다르다. noBackupFilesDir/path lock/검증된 hot-journal canary/reset staging 경계가 있다. |
| `ManualDriveSession`/`BoundDriveAuthorization` | metadata refresh와 명시 continue 분리; fresh exact selected-account token의 about proof 후 같은 transport 사용. pause/동의/ticket/generation 경계와 profiling optional 인자가 있다. worker에서 UI session을 가장해 호출하지 않는다. |
| `HealthConnectReader` | MANUAL EXPLICIT_DAY만, 다른 trigger BLOCKED. steps aggregate; weight raw latest; HR/sleep/exercise before(dayEnd) 광범위 조회. 자동 reader에 재사용하면 bounded 조건 위반이다. |
| `Model`/`Normalization`/`VersionedCsvExporter`/`SyncRevision` | aggregate DTO 없음; reserved sleep_min/exercise_min은 null 고정; daily 생성은 steps/weight 존재만. revision은 generatedAt=EPOCH CSV를 canonical hashing하며 scope+CSV만 반영한다. |
| `MainActivity`/`SyncViewModel`/`ManualSyncProfiling` | 전경 bridge ticket과 late result 방어; debug 수동 stage/count 계측. 새 기능은 기존 manual optional/default 인자와 history/layout을 보존해야 한다. |
| cached HC1.1.0 source | SleepSessionRecord.SLEEP_DURATION_TOTAL / ExerciseSessionRecord.EXERCISE_DURATION_TOTAL의 Duration metric 존재. FEATURE_READ_HEALTH_DATA_IN_BACKGROUND/HealthPermission background 상수와 feature status/grants API는 선행 inventory와 대조한다. API 존재는 실 provider completeness 증거가 아니다. |

### 공통 계약 위치

실행 계약은 실제 통합한 부모 plan.md의 2절에 원문 그대로 이관했다. executor brief는 반드시 부모를 함께 입력해 해당 contract를 수집한다. 상위/spec revision 고정과 독립 계획 검토 전에는 제품 작업을 시작하지 않는다.

### checkpoint와 실행 단위

각 T는 2~5파일이고 depends_on 없는 작업의 파일은 겹치지 않는다. CP1=T1~T3 기반/모델/migration, CP2=T4~T5 budget/session, CP3=T6~T9 summary/HC, CP4=T10~T12 worker/재시작, CP5=T13~T16 설정/화면, CP6=T17~T18 증거/문서다. 18개 T를 한 executor 세션에 던지지 않는다. parent 통합 시 CP별 reviewable 하위 PR/실행 브리프와 ADR 파일 작업을 분리하고 충돌하는 parent 파일은 순차 통합한다. 지금은 최종 slice 전체의 준비 문서 한 파일이다.


## 3. 작업

### T19: WorkManager helper process 격리 기반

- **depends_on**: 실제 SAF reviewed merge 및 early WorkManager source/evidence 통합 gate; **tier**: standard; **status**: done. 숫자는 기존 T1~T18 추적성을 유지하기 위한 새 ID이며 실행순서는 T19→T1이다.
- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `android/ci/run-emulator-validation.sh` | 수정 | 기존 full critical 뒤 process격리·registry 기반 remainder 제외; 진단분기 불변 |
  | `android/ci/verify-workmanager-isolation.sh` | 신규 | normal/helper/normal 별도 instrumentation과 bounded 실제결과 parse/보존 |
  | `AI/WorkManagerIsolationInstrumentedTest.kt` | 신규 | 실제 public manager/noTestDriver/liveDB normal probe2개 |

- **인터페이스**: 2절 `WorkManagerIsolationContracts`, 2절 `WorkManagerIsolationRegistry`, 2절 `WorkManagerIsolationProbe` 참조.
- **결정 로직**: helper 설치class→isolated invocation만; 기타 normal/host→기존소유회귀; 중복/누락/실패/skip→full실패.
- **작성할 테스트**:
  - "Normal process before and after helper has a live production WorkManager without a test driver" (①·④·⑤/실제 instrumentation 및host프로세스).
- **완료 기준**: production Application 도입 전 normal-before→starter→normal-after 실제양API증거와 기존모든critical/remainder/host case보존, 신규probe2개의 uniqueidentity 확인. source/stub만으로 완료하지 않는다.
- **검증 방법**: 기존 full API35/36 workflow; 설치된 동일APK에 `adb shell am force-stop com.ny.my.health` 뒤 각 `adb shell am instrument -w -r -e class com.ny.my.health.WorkManagerIsolationInstrumentedTest#normalRuntimeBeforeHelperHasALiveProductionManager`, starter고정class, after고정method를 기존runner `com.ny.my.health.test/androidx.test.runner.AndroidJUnitRunner`로 각각실행하고 각종료를검증한다. 실제script에서 host timeout/termination/expectedcount를확인한다.
- **주의사항**: testdriver는 실제 publicAPI nullable 동작을 source와대조한다. publicinitialize/getInstance를 restore라고 쓰지 않는다. 전체기존startup provider를지우거나 appdata clear/임의sleep으로격리를추정하지않는다.

### T1: 실제 WorkManager starter 기반

- **depends_on**: T19; **tier**: standard; **status**: done.
- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `A/HealthApplication.kt` | 신규 | Configuration.Provider와 nested WorkerFactory/공유 runtime |
  | `android/app/src/main/AndroidManifest.xml` | 수정 | Application 등록·Startup의 WorkManagerInitializer metadata만 제거·DiagnosticsReceiver 별도 제거/비활성화 감사 |
  | `AI/WorkManagerStarterInstrumentedTest.kt` | 수정 | 통합된 starter와 production bootstrap의 test factory 경계 |
  | `AI/WorkManagerIsolationInstrumentedTest.kt` | 수정 | stable manager/publiclookup/liveDB·Activity off/recreate 확인 |
  | `docs/V1/dependency-audit.json` | 수정 | 도입 시 네 resolved graph OSV·manifest/AAR metadata·호환성 근거 |

- **인터페이스**: 2절 `WorkManagerStarter`, 2절 `WorkManagerIsolationContracts`, 2절 `WorkManagerIsolationProbe` 참조. 기존 port를 바꾸는 추가 계약은 부모에 먼저 통합한다.
- **결정 로직**: 없음.
- **작성할 테스트**: `Real WorkManager test context initializes and completes a CoroutineWorker once` (①성공기준·②경계/동등분할·③오류·④순서; 실제 SQLite/WorkManager/프로세스 통합). 위 상태/입력과 기대 결과를 assertion으로 검증한다. 신규 테스트이며 기존 회귀는 삭제하지 않는다.
- **완료 기준**: starter + 아래 네 resolved configuration/호환성/manifest/AAR metadata/OSV 초기 audit가 실제 통과하고 head/time/commands/results/errors/disposition을 다섯 번째 파일에 기록해야 T1 PASS다. 미해결 high/critical의 도달성 차단·조회 오류 미확인은 기능 구현 진입을 막는다. 미수행은 pending이다.
- **검증 방법** (실행 단계, `android/`에서):

  ```bash
  ./gradlew :app:dependencies --configuration releaseRuntimeClasspath
  ./gradlew :app:dependencies --configuration debugRuntimeClasspath
  ./gradlew :app:dependencies --configuration debugUnitTestRuntimeClasspath
  ./gradlew :app:dependencies --configuration debugAndroidTestRuntimeClasspath
  ./gradlew :app:dependencyInsight --configuration releaseRuntimeClasspath --dependency androidx.work
  ./gradlew :app:dependencyInsight --configuration releaseRuntimeClasspath --dependency kotlinx-coroutines
  ./gradlew :app:dependencyInsight --configuration releaseRuntimeClasspath --dependency kotlin-stdlib
  ./gradlew :app:dependencyInsight --configuration releaseRuntimeClasspath --dependency guava
  ./gradlew :app:checkDebugAarMetadata :app:checkReleaseAarMetadata :app:processDebugMainManifest :app:processReleaseMainManifest :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest
  (cd "$(git rev-parse --show-toplevel)" && GITHUB_EVENT_NAME=workflow_dispatch TEST_SCOPE=full bash android/ci/run-emulator-validation.sh)
  ```

- **주의사항**: early starter의 두 dependency와 actual Native/audit를 통합·대조해 재사용하며 중복 신규작성/버전변경을 하지 않는다. Production lookup은 app-owned stable reference를 주입하고 framework globals는 helper가 없는 freshprocess에서 검증한다. helper test는 별도process에서만 publicTestInitHelper를 사용한다.  Configuration.Provider/WorkerFactory와 androidx.startup provider에서 WorkManagerInitializer만 제거한다. 전체 Startup provider를 지우지 않는다. 테스트 전용 worker는 test factory로 주입한다. 네 configuration의 실제 resolved Maven ModuleComponentIdentifier 전체(BOM/variant/testing 포함)를 OSV querybatch로 감사하고 응답 오류·취약점·reachability/disposition을 기록한다. Kotlin/compiler/coroutines/runtime/AAR metadata와 debug/release merged manifest의 single process/initializer/diagnostics receiver/backup을 확인한다. 필요한 dependencyInsight는 네 configuration에 반복한다. T1 전체 PASS 전 T2 이후 기능 구현을 시작하지 않는다; 공식 호환 조사나 starter 단독 PASS로 대체하지 않는다. 암묵 초기화/새 migration의 추정이 틀리면 우회하지 말고 blocked로 보고한다. 단언 완화·skip 금지.

### T2: durable retry 모델과 검증 경계

- **depends_on**: T1; **tier**: standard; **status**: done.
- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `C/SyncRetryBudget.kt` | 신규 | IO 없는 lineage/claim/finalization validation |
  | `C/SyncState.kt` | 수정 | optional owner와 memory-only bound context validator |
  | `CT/SyncRetryBudgetTest.kt` | 신규 | 초기+3회·due 경계·immutable proposal/lineage 검증 |

- **인터페이스**: 2절 `AutomaticRetryContracts` 참조. 기존 port를 바꾸는 추가 계약은 부모에 먼저 통합한다.
- **결정 로직**: 같은 unfinished lineage → 새 attempt/periodic/revision 관찰만으로 사용량 초기화 금지; completed lineage → 새 정상 batch 허용; retry claim 3회 뒤 → exhausted; now<due → DEFERRED/사용량 불변; 최초 proposal 누락·scope/date 교체 → 거부.
- **작성할 테스트**: `Retry lineage permits one initial run and three claimed retries despite new attempt ids and changed observed revisions` (①성공기준·②경계/동등분할·③오류·④순서; JVM 유닛/adapter 경계). 위 상태/입력과 기대 결과를 assertion으로 검증한다. 신규 테스트이며 기존 회귀는 삭제하지 않는다.
- **계약 보정3 검증**: SyncState validator의 bound-context pending unfinished·모든 legacy trigger의 finished pending 및 generic null-owner 의미 보존 및 정확히 한 payload, immutable input/output, 초기 version 0/reclaim만 +1/observe·finish 불변, invalid 고정 예외, due 경계와 세 hash golden을 기존 T2 테스트 파일에서 검증한다.
- **교정6 검증**: 기존 세 파일만 유지한다. SyncState.validateMutation에 automaticOwnerRequired: Boolean = false memory-only 인자를 추가하고 새 bound 사례는 명시적으로 true를 전달한다. supplied owner의 intrinsic/id/key 검증, bound start/finish 거부 및 day/confirmation/union, 수동과 모든 legacy trigger의 default semantics를 기존 신규 SyncRetryBudgetTest에서 검증한다. 기존 SyncStateTest와 DestinationSyncTest 및 기대값은 수정하지 않고 전체 core suite를 실행한다.
- **교정7 검증**: 기존 신규 SyncRetryBudgetTest의 bound 성공 confirmation/missing-owner 거부, manual owner 거부 및 bound context true에서 manual generic start/finish 보존을 직접 검증한다. 제품 파일과 legacy tests는 변경하지 않는다.
- **완료 기준**: 위 검증이 실제 통과하고 개인정보 없는 evidence가 있다. 미수행은 pending이다.
- **검증 방법** (실행 단계, `android/`에서):

  ```bash
  ./gradlew -PcoreOnly :core:test
  ```

- **주의사항**: 기존 AttemptState는 마지막 실행의 mirror다. 새 lineage는 frozen 날짜 cohort이며 revisions는 UNREAD sentinel이 아닌 nullable 관찰 metadata다. 순수 validation에는 DB/SDK 호출을 넣지 않는다. 암묵 초기화/새 migration의 추정이 틀리면 우회하지 말고 blocked로 보고한다. 단언 완화·skip 금지.

- **실제 검증**: source dcc5738의 R7 actor 및 root 전체core86개 통과, 공식 PR19 base84 identity 전부 보존/실패·오류·skip·중복0. root log auto-t2-root-recovery7-verify.log 및 sourceSHA별 auto-t2-root-proof.json. 기존 generic/수동 테스트는 변경하지 않았다. owner pure 검증만 완료이며 persisted ledger/SQLite/CAS는 T3/T4 대기다.

### T3: 검증된 v1 상태의 v2 migration

- **depends_on**: T2; **tier**: standard; **status**: in_progress.
- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `A/SqliteSyncStateStore.kt` | 수정 | v2 ledger 전체 검증 호출·capacity·FK ON reset 연결만; T4 원자 claim 구현은 제외 |
  | `A/SyncStateDatabase.kt` | 수정 | v1/v2 strict schema admission |
  | `A/SyncRetryBudgetMigration.kt` | 신규 | owned staged migration/marker 복구 |
  | `A/SyncStateHotJournalRecovery.kt` | 수정 | 버전별 canary schema 검증 |
  | `AI/SyncRetryBudgetMigrationInstrumentedTest.kt` | 신규 | 실제 v1/v2 reopen·fault 경계 |

- **인터페이스**: 2절 `AutomaticRetryContracts` 참조. 기존 port를 바꾸는 추가 계약은 부모에 먼저 통합한다.
- **결정 로직**: 정확한 supported v1 + validated content → additive v2; unknown schema/future version/corruption → 원본 보존 BLOCKED.
- **작성할 테스트**: `Validated v1 migration retains bindings unfinished journals uncertainty prior confirmations and gaps through interrupted v2 publish` (①성공기준·②경계/동등분할·③오류·④순서; 실제 SQLite/WorkManager/프로세스 통합). 위 상태/입력과 기대 결과를 assertion으로 검증한다. 신규 테스트이며 기존 회귀는 삭제하지 않는다.
- **계약 보정3 검증**: 한 신규 table에 requestedScopes를 검증된 column/JSON으로 저장하고 dates/scopes/observed JSON 합계 <=16KiB. 기존 ACTIVE partial unique index의 generation scan을 EXPLAIN으로 검증해 충분하면 추가 index 금지. schema 검증은 전체 row를 검증한다.
- **교정6 검증**: 고정 DDL은 retry_budget parent, 기존 attempt의 nullable automatic_lineage_id FK, 기존 ACTIVE partial index다. 기존 계획의 SyncRetryBudgetMigrationInstrumentedTest에서 v1 legacy NULL, 원본 보존, FK ON reset 순서, migrated/fresh v2 실제 sqlite_master 및 foreign_key_list parity, reopen/FK/quick checks를 검증한다. v1/v2 exact schema map을 구분한다. 기존 hot-journal fixture의 의미를 보존하며 새 test 파일이나 기존 hot-journal 테스트 수정은 요구하지 않는다. API28은 source 호환성 판단이며 실제 migration 미수행, 기존 API35/36 gate는 유지한다.
- **준비 교정1**: 원래4파일에 기존 store 연결1파일을 더한5파일이며 T4와 순차 인계한다. 기존 session future-version fixture는 별도 T20 한파일이 소유한다.
- **준비 교정2·증거 층**: 위 Android 통합 층의 SQLite/WorkManager/프로세스 병기는 테스트 층 분류이며 T3에 새 worker/host를 도입하라는 요구가 아니다. T3 새 테스트는 실제 SQLite v1/v2·재열기·injected interruption 단계와 원본 보존을 실제 검증한다. 기존 full runner의 실제 process recovery/cleanup host gate는 그대로 모두 수행한다. 새 migration 단계의 실제 kill/restart를 수행했다고 주장하지 않으며 exception fault와 명확히 구분한다.
- **검토 교정3~6**: Os static class 직접 호출(3), copy 이전 durable exclusive owner 및 준비중 중단의 known-state resume/unknown preserve BLOCKED(4), source/stage inode·device·size·digest를 manifest와 모든 rename 경계에서 검증(5), linked finished attempt의 created/started/finished/updated/due 일관성(6)을 같은 기존파일에서 보완한다. 새 migration test의 실제 DB reopen/fault/corruption으로 확인한다.
- **검토 교정7**: 유효한 owner+원본proof와 stage 미생성 상태는 copy부터 재개한다. 변환 완료 checkpoint 전 변환된 stage의 device/inode/size/hash를 별도 durable proof로 기록해 재개 시 검증·ready 승격하며 DDL을 재실행하지 않는다. 증명 기록 전 partial/unproved/mismatched stage는 계속 원본과 owner 보존 BLOCKED다.
- **교정8·9 검증**: 실제compile의 core 모듈 public nullable finishedAt은 local val snapshot으로 비교한다(8). actor가 보고한 FK ON retained-budget reset·linked finish corruption/valid retirement·changed-content/legacy spill·명시gap·실제v1 dirty journal 회귀를 같은신규fixture에 보완한다(9). 기존테스트·assertion은변경하지않는다.
- **완료 기준**: 위 검증이 실제 통과하고 개인정보 없는 evidence가 있다. 미수행은 pending이다.
- **검증 방법** (실행 단계, `android/`에서):

  ```bash
  ./gradlew :app:assembleDebugAndroidTest
  (cd "$(git rev-parse --show-toplevel)" && GITHUB_EVENT_NAME=workflow_dispatch TEST_SCOPE=full bash android/ci/run-emulator-validation.sh)
  ```

- **주의사항**: §2 migration 계약을 따른다. version>2를 future unsupported fixture로 사용하며 malformed v1는 계속 거부한다. native v1 hot-journal forensic fixture를 v2라는 이유로 제거하지 않는다. staged attempt nullable FK를 추가하되 old row/bytes의 의미를 재작성하지 않는다. 암묵 초기화/새 migration의 추정이 틀리면 우회하지 말고 blocked로 보고한다. 단언 완화·skip 금지.

**Checkpoint**: 해당 구간의 실제 source/계약/회귀와 parent pin을 root가 확인한 후 후속 구간으로 진행한다.

### T20: v2 도입의 future schema fixture 호환

- **depends_on**: T1; **tier**: light; **status**: in_progress.
- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `AI/SyncExecutionSessionInstrumentedTest.kt` | 수정 | unsupported fixture version 2를 future 3으로 교정 |
  | `AI/SqliteSyncStateStoreInstrumentedTest.kt` | 수정 | 기존 attempt 손상 INSERT의 12열을 명시해 v2 nullable 추가와 호환 |

- **인터페이스**: 신규 API는 없으며 손상·unsupported 시 raw read/remote write 0과 원본 보존 assertion을 유지한다.
- **결정 로직**: 기존 unsupported fixture의 version 2는 이제 supported v2가 되므로 3을 넣는다. 기존 테스트 이름·구조·assertion·다른 damage 입력은 변경하지 않는다. 기존 attempt 손상 fixture INSERT는 기존12열명만명시하고12값/손상조건을그대로유지한다.
- **작성할 테스트**: 기존 unsupported damage 사례를 그대로 재사용한다. 신규 테스트 파일이나 expectation 변경은 없다.
- **완료 기준**: T3와 통합된 source에서 기존 full Native API35/36 및 host protocol을 실제 통과해야 done이다. 컴파일만으로 완료하지 않는다.
- **검증 방법**: T3 source frozen 뒤 root가 `./gradlew :app:assembleDebugAndroidTest` 및 repository root에서 `GITHUB_EVENT_NAME=workflow_dispatch TEST_SCOPE=full bash android/ci/run-emulator-validation.sh`를 수행한다. 로컬 emulator 부재는 pending이고 root GitHub CI 양API로 실제 검증한다. T3와 같은 명령을 중복 실행하지 않고 source head/evidence를 공유한다.
- **주의사항**: 이 두 파일은 T3 제품·새fixture와 disjoint다. 이후 T5의 기존 session fixture 인계 전 T20 완료 source를 보존한다. 회귀 삭제·skip·assertion 완화·v2 corruption 허용 금지.

### T21: 전체 gate를 보존하는 고정 retry 진단 실행

- **depends_on**: T1; **tier**: light; **status**: done.
- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `.github/workflows/android-emulator-tests.yml` | 수정 | 기존 dispatch choice에 정적 retry 진단3개만 추가 |
  | `android/ci/run-emulator-validation.sh` | 수정 | source 존재 확인 뒤 고정 class만 실행·진단 종료 |

- **인터페이스**: 기존 dispatch allowlist를 확장하며 임의 class/command 입력은 받지 않는다. 기본값 full, PR full, 기존 진단6개와 양API/host/registry 순서·artifact/timeout/permissions는 그대로다.
- **결정 로직**: workflow_dispatch의 retry-migration은 정확한 class com.ny.my.health.SyncRetryBudgetMigrationInstrumentedTest, retry-store는 com.ny.my.health.SyncRetryBudgetStoreInstrumentedTest, retry-session은 com.ny.my.health.SyncExecutionSessionInstrumentedTest를 실행한다. 각 고정 source path가 없으면 실패하며 unknown은 기존대로 실패한다. 선택 진단은 full PASS가 아니다.
- **작성할 테스트**: shell syntax와 임시 synthetic adb/gradle stub으로3개scope의 exact1class 선택, source누락/unknown 실패 및 기존full tail bytes 보존을 검증한다. 저장소에 새test파일/의존성은 추가하지 않는다. 실제현재migration8개를양API에서선택실행하는CI증거는root가확인한다.
- **완료 기준**: staticroute/checks와실제선택진단8개양API를통과하고 fullgate불변을fresh독립review로확인한다. 진단결과로T3/전체V1완료를대신하지않는다.
- **검증 방법**: `bash -n android/ci/run-emulator-validation.sh` 및 source0cf03a9와fulltail/기타workflow설정동일대조, 위stubroute검증; root가 branch의workflow_dispatch test_scope=retry-migration과정확한XML8개/digest를검증한다.
- **주의사항**: 제품/manifest/dependency/원시로그공개/sharding/fixture/timeout 변경 금지. 기존진단과fullrunner표현을재작성하지말고최소case만추가한다. 첫의미있는검증실패시보고/중단한다.


- **실제 검증**: CI route source4bb92f3 fresh독립APPROVE 및rootstatic exactroute/누락/unknown/fulltail byte동일검증후, fixtureR11포함2b8ce11의고정진단43553148135 양API정확8개/source동일identity/실패·오류·skip·중복0을공식ZIPdigest와XML로root대조했다(auto-t3-diagnostic-r11-root-proof.json). 실제선택진단은전체143Native/host결과가아니며T3/T20완료를대신하지않는다. 최초진단43552194517의7PASS/1FAIL과R11수정이력은보존한다.
### T4: retry claim의 실제 SQLite 원자성

- **depends_on**: T3, T20; **tier**: standard; **status**: pending.
- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `A/SqliteSyncStateStore.kt` | 수정 | 기존 path lock/validated write에 atomic claim/finalize |
  | `A/SqliteSyncRetryBudget.kt` | 신규 | bounded row codec/query helper; 독립 DB 아님 |
  | `AI/SyncRetryBudgetStoreInstrumentedTest.kt` | 신규 | claim CAS/fault/reopen/exhaustion |

- **인터페이스**: 2절 `AutomaticRetryContracts` 참조. 기존 port를 바꾸는 추가 계약은 부모에 먼저 통합한다.
- **결정 로직**: expected generation+lineage+version+owner 일치 → claim 1회 commit; stale/동일 claim replay → 증가 없음; exhausted → health/remote admission 거부.
- **작성할 테스트**: `SQLite retry claims survive reopen and racing attempts cannot exceed three retries or erase old metadata` (①성공기준·②경계/동등분할·③오류·④순서; 실제 SQLite/WorkManager/프로세스 통합). 위 상태/입력과 기대 결과를 assertion으로 검증한다. 신규 테스트이며 기존 회귀는 삭제하지 않는다.
- **계약 보정3 검증**: 2절 preflight/load API 및 exact scope CAS를 구현한다. indexed ACTIVE owner/due streaming은 cap 100000, 목록/JSON per-row/N+1 금지. owner recovery 우선, max future due/정각/다른 scope terminal, stale/손상 외부 호출 0을 검증한다. busy union 동시성, 실제 observation/day atomicity, eligible manual retirement 및 rollback/reopen을 검증한다.
- **교정6 검증**: 기존 계획의 SyncRetryBudgetStoreInstrumentedTest에서 긍정 persisted link를 private boundary가 도출하는 것과 claim/finish/recovery/opt-out 뒤 reopen, orphan·역방향 owner/link 불일치·generation/scope/count/version/date/revision 불일치의 fail closed, ownerless union, legacy config false, finished manual replacement와 보존 budget, rollback/CAS를 검증한다. generic caller는 bool 또는 link를 지정하지 못한다. T3 schema/fixture 소유를 건드리지 않는다.
- **완료 기준**: 위 검증이 실제 통과하고 개인정보 없는 evidence가 있다. 미수행은 pending이다.
- **검증 방법** (실행 단계, `android/`에서):

  ```bash
  ./gradlew :app:assembleDebugAndroidTest
  (cd "$(git rev-parse --show-toplevel)" && GITHUB_EVENT_NAME=workflow_dispatch TEST_SCOPE=full bash android/ci/run-emulator-validation.sh)
  ```

- **주의사항**: 하나의 기존 DB transaction으로 proposal 검증/frozen scope·날짜 저장/attempt 시작/budget claim/due CAS를 묶는다. initial/manual 이전 row/partial grants/계획 후 scope·grant 변경/재claim cohort 교체 거부와 due 직전·정각/SQLite reopen/CAS race에서 count·attempt 원자성을 검증한다. finalize는 종료와 RETRY_EXHAUSTED blocker를 함께 commit한다. read/store 오류를 empty/new budget으로 바꾸지 않는다. config.format_version/domain SyncState.version=1과 DB user_version=2는 별개 계약이다. 암묵 초기화/새 migration의 추정이 틀리면 우회하지 말고 blocked로 보고한다. 단언 완화·skip 금지.

### T5: 공용 session의 자동 attempt 연결

- **depends_on**: T4; **tier**: standard; **status**: pending.
- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `A/SyncExecutionSession.kt` | 수정 | runAutomaticPrepared/explicit detectPending trigger/ledger claim·cleanup |
  | `C/DestinationSync.kt` | 수정 | 자동 day preparation과 실제 revision ledger의 같은 commit |
  | `AI/SyncExecutionSessionInstrumentedTest.kt` | 수정 | busy merge/cancellation/CAS/reopen |
  | `CT/DestinationSyncTest.kt` | 수정 | pending revision 변화와 uncertainty 보존 |

- **인터페이스**: 2절 `AutomaticRetryContracts`, `AutomaticSessionRoutingContracts` 참조. 기존 port를 바꾸는 추가 계약은 부모에 먼저 통합한다.
- **결정 로직**: busy → pending trigger merge만; owner → 복구 후 atomic claim; cancellation → journal interruption 보존, retry reset 없음.
- **작성할 테스트**: `Automatic session claims once under the shared gate and cancellation never resets budget or clears uncertain creates` (①성공기준·②경계/동등분할·③오류·④순서; 실제 SQLite/WorkManager/프로세스 통합). ⑤기존 관련 회귀 재사용. 위 상태/입력과 기대 결과를 assertion으로 검증한다. 신규 테스트이며 기존 회귀는 삭제하지 않는다.
- **계약 보정3 검증**: 모든 자동 day/confirmation에 owner와 실제 read revision을 전달한다. bound ownerless busy pending union은 현재 미완료 attempt superset만 허용하며 ledgerless legacy generic 동작은 보존한다. readScoped와 pure identify만 source catch, 취소 전파/두 SourceReadException reason 보존/나머지 SOURCE_READ_FAILED; store 및 destination IO는 제외. 기존 DestinationSyncTest에서 해당 날짜 revision 실패 write 0, 이전 완료 write 유지 및 분리 회귀를 검증한다.
- **교정6 검증**: 실제 enabled automatic entry는 prepare/access 이전 전용 claim/finish로 라우팅하고 default-off 및 due 이전 auth/metadata/records/remote 0을 기존 session 테스트에서 검증한다. legacy synthetic generic 경로는 보존한다.
- **완료 기준**: 위 검증이 실제 통과하고 개인정보 없는 evidence가 있다. 미수행은 pending이다.
- **검증 방법** (실행 단계, `android/`에서):

  ```bash
  ./gradlew :app:assembleDebugAndroidTest
  (cd "$(git rev-parse --show-toplevel)" && GITHUB_EVENT_NAME=workflow_dispatch TEST_SCOPE=full bash android/ci/run-emulator-validation.sh)
  ```

- **주의사항**: manual runPrepared 계약과 profiling optional 인자를 보존한다. detectPending 기본 MANUAL을 유지하여 기존 호출부를 깨지 않되 config.automatic 암묵 trigger를 제거한다. manual/automatic 별도 session이 같은 canonical gate에서 상호 배제되는지 검증한다. initial proposal과 기존 manual attempt 불일치/partial grants/계획 후 grant 변화는 BLOCKED·STALE이며 새 cohort로 우회하지 않는다. automatic factory/auth는 gate/claim 이후 호출한다. current generation/automatic 재검사 후 Health read. source 새 revision은 ledger 관찰값만 바꾸며 미종결 lineage claim count는 바꾸지 않는다. 암묵 초기화/새 migration의 추정이 틀리면 우회하지 말고 blocked로 보고한다. 단언 완화·skip 금지.

**Checkpoint**: 해당 구간의 실제 source/계약/회귀와 parent pin을 root가 확인한 후 후속 구간으로 진행한다.

### T6: 누락과 출처를 보존하는 aggregate 값

- **depends_on**: T1; **tier**: standard; **status**: in_progress.
- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `C/Model.kt` | 수정 | nullable aggregate duration DTO/defaults |
  | `C/Normalization.kt` | 수정 | SUMMARY duration만 정상 분 값으로 투영 |
  | `CT/NormalizationTest.kt` | 수정 | null/zero/분수/권한/기존 latest weight |

- **인터페이스**: 2절 `AutomaticSummaryContracts` 참조. 기존 port를 바꾸는 추가 계약은 부모에 먼저 통합한다.
- **결정 로직**: 미조회/null metric → blank; 실제 Duration.ZERO → 0.0; negative → source failure; 최대 Duration의 분 변환은 finite; manual defaults → 기존 결과.
- **작성할 테스트**: `Aggregate duration summaries preserve missing zero fractional minutes and provenance without inventing sessions` (①성공기준·②경계/동등분할·③오류·④순서; JVM 유닛/adapter 경계). ⑤기존 관련 회귀 재사용. 위 상태/입력과 기대 결과를 assertion으로 검증한다. 신규 테스트이며 기존 회귀는 삭제하지 않는다.
- **계약 보정3 검증**: 단일 immutable AggregateDuration DTO를 snapshot/day 둘 다 끝에 default null로 추가한다. defensive copy/unmodifiable origins, 값 동등성, 음수 거부, 최대 Duration finite, ZERO만 0.0, missing blank, 1440 상한 없음을 기존 테스트에서 검증한다.
- **완료 기준**: 위 검증이 실제 통과하고 개인정보 없는 evidence가 있다. 미수행은 pending이다.
- **검증 방법** (실행 단계, `android/`에서):

  ```bash
  ./gradlew -PcoreOnly :core:test
  ```

- **주의사항**: manual HealthSnapshot positional constructor 뒤 default 인자를 추가한다. 집계를 가짜 SleepSession/ExerciseSession/WeightMeasurement로 만들지 않는다. 암묵 초기화/새 migration의 추정이 틀리면 우회하지 말고 blocked로 보고한다. 단언 완화·skip 금지.

### T7: reserved CSV 요약과 의미 revision

- **depends_on**: T6; **tier**: standard; **status**: pending.
- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `C/VersionedCsvExporter.kt` | 수정 | sleep_min/exercise_min과 summary-only daily |
  | `C/SyncRevision.kt` | 수정 | aggregate 의미/출처를 선택적 canonical 입력에 포함 |
  | `CT/CsvExporterTest.kt` | 수정 | manual v0.1 golden byte 회귀 |
  | `CT/SyncRevisionTest.kt` | 수정 | summary revision/generatedAt/remote stale |

- **인터페이스**: 2절 `AutomaticSummaryContracts` 참조. 기존 port를 바꾸는 추가 계약은 부모에 먼저 통합한다.
- **결정 로직**: summary metric 실제 존재 → daily; 모든 metric missing/다른 record 없음 → CSV 없음; manual aggregate 없음 → 동일 bytes와 기존 revision.
- **작성할 테스트**: `Summary only daily files retain v0.1 header while changed aggregates advance revision and manual exports stay byte identical` (①성공기준·②경계/동등분할·③오류·④순서; JVM 유닛/adapter 경계). ⑤기존 관련 회귀 재사용. 위 상태/입력과 기대 결과를 assertion으로 검증한다. 신규 테스트이며 기존 회귀는 삭제하지 않는다.
- **계약 보정3 검증**: SUMMARY scope 검증과 sorted origins, manual 기본 null의 기존 CSV bytes/hash golden을 유지한다.
- **완료 기준**: 위 검증이 실제 통과하고 개인정보 없는 evidence가 있다. 미수행은 pending이다.
- **검증 방법** (실행 단계, `android/`에서):

  ```bash
  ./gradlew -PcoreOnly :core:test
  ```

- **주의사항**: resting_hr_bpm은 계속 blank다. canonical marker는 aggregate envelope가 있을 때만 추가하여 기존 manual digest를 바꾸지 않는다. 출처 정렬/중복 제거는 SDK metric별 origins에만 적용하고 raw source record를 합치지 않는다. 암묵 초기화/새 migration의 추정이 틀리면 우회하지 말고 blocked로 보고한다. 단언 완화·skip 금지.

### T8: 실제 HC background category admission

- **depends_on**: T1, T7; **tier**: standard; **status**: pending.
- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `A/AutomaticHealthReader.kt` | 신규 | background access 및 일별 summary/latest raw weight |
  | `A/HealthConnectReader.kt` | 수정 | MANUAL 전용 reader의 nonmanual BLOCKED 보존 |
  | `android/app/src/main/AndroidManifest.xml` | 수정 | READ_HEALTH_DATA_IN_BACKGROUND만 추가 |
  | `AT/AutomaticHealthReaderTest.kt` | 신규 | feature/grant/aggregate/date별 adapter 검증 |

- **인터페이스**: 2절 `AutomaticHealthContracts` 참조. 기존 port를 바꾸는 추가 계약은 부모에 먼저 통합한다.
- **결정 로직**: background feature+permission+해당 category grant → 가능한 scope; exercise/HR overlap proof 미확인 → HR BLOCKED; 다른 supported category → 독립 scope.
- **작성할 테스트**: `Automatic background grants gate each category and aggregates preserve nullable metrics while weight stays latest actual record` (①성공기준·②경계/동등분할·③오류·④순서; JVM 유닛/adapter 경계). 위 상태/입력과 기대 결과를 assertion으로 검증한다. 신규 테스트이며 기존 회귀는 삭제하지 않는다.
- **완료 기준**: 위 검증이 실제 통과하고 개인정보 없는 evidence가 있다. 미수행은 pending이다.
- **검증 방법** (실행 단계, `android/`에서):

  ```bash
  ./gradlew :core:test :app:testDebugUnitTest
  ```

- **주의사항**: SDK1.1.0의 features/getGrantedPermissions/aggregate 실제 API만 쓴다. steps COUNT_TOTAL, sleep/exercise DURATION_TOTAL은 metric별 aggregate request로 출처를 분리한다. weight는 당일 bounded raw read+latest이며 AVG를 쓰지 않는다. history permission은 추가하지 않는다. grant 철회는 EMPTY로 변환하지 않는다. 암묵 초기화/새 migration의 추정이 틀리면 우회하지 말고 blocked로 보고한다. 단언 완화·skip 금지.

### T9: 불명확한 운동 심박의 안전 차단

- **depends_on**: T8; **tier**: standard; **status**: pending.
- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `A/AutomaticHealthReader.kt` | 수정 | workout overlap proof와 날짜 공용 budgets |
  | `AT/AutomaticHealthReaderTest.kt` | 수정 | earlier-start/cross-midnight/동일 sample/limit |

- **인터페이스**: 2절 `AutomaticHealthContracts` 참조. 기존 port를 바꾸는 추가 계약은 부모에 먼저 통합한다.
- **결정 로직**: production overlap completeness proof 없음 → affected HR WORKOUT_SCOPED read 제외/BLOCKED; proof 있는 test adapter+완전 empty sessions → HR query 0회; limit 초과 → 미확인.
- **작성할 테스트**: `Unproven interval discovery blocks workout heart scope while independent summary categories still complete` (①성공기준·②경계/동등분할·③오류·④순서; JVM 유닛/adapter 경계). ⑤기존 관련 회귀 재사용. 위 상태/입력과 기대 결과를 assertion으로 검증한다. 신규 테스트이며 기존 회귀는 삭제하지 않는다.
- **완료 기준**: 위 검증이 실제 통과하고 개인정보 없는 evidence가 있다. 미수행은 pending이다.
- **검증 방법** (실행 단계, `android/`에서):

  ```bash
  ./gradlew :core:test :app:testDebugUnitTest
  ```

- **주의사항**: 현재 production proof=false다. 합성 fixture PASS만으로 true로 바꾸지 않는다. bounded exercise discovery와 earlier-start HR series 포함 보장이 둘 다 필요하다. fixed lookback/before(dayEnd)/whole-day raw fallback 금지. 실제 session bounds∩day union을 사용하고 page100/sample100000/query100의 일일 공용 budget을 적용한다. query100은 페이지100에서 유도한 추가 작업량 한도이며 제품 SLA가 아니다. 암묵 초기화/새 migration의 추정이 틀리면 우회하지 말고 blocked로 보고한다. 단언 완화·skip 금지.

**Checkpoint**: 해당 구간의 실제 source/계약/회귀와 parent pin을 root가 확인한 후 후속 구간으로 진행한다.

### T10: worker용 fresh 계정 authorization

- **depends_on**: T1, T5; **tier**: standard; **status**: pending.
- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `A/BackgroundDriveAuthorization.kt` | 신규 | application-context SDK silent authorize adapter |
  | `AT/BackgroundDriveAuthorizationTest.kt` | 신규 | resolution/account/token/scope/redaction |
  | `AI/BackgroundAuthorizationInstrumentedTest.kt` | 신규 | 실제 SDK request adapter 경계; 계정없는 실패 |

- **인터페이스**: 2절 `BackgroundAuthorizationContracts` 참조. 기존 port를 바꾸는 추가 계약은 부모에 먼저 통합한다.
- **결정 로직**: bound Account+drive.file+token+no resolution+same-token about proof → memory transport; 나머지 → ACCOUNT_AUTHORIZATION_BLOCKED.
- **작성할 테스트**: `Background authorization pins the selected account and proves the same fresh token before any health read without launching resolution` (①성공기준·②경계/동등분할·③오류·④순서; JVM 유닛/adapter 경계). 위 상태/입력과 기대 결과를 assertion으로 검증한다. 신규 테스트이며 기존 회귀는 삭제하지 않는다.
- **완료 기준**: 위 검증이 실제 통과하고 개인정보 없는 evidence가 있다. 미수행은 pending이다.
- **검증 방법** (실행 단계, `android/`에서):

  ```bash
  ./gradlew :app:assembleDebugAndroidTest
  (cd "$(git rev-parse --show-toplevel)" && GITHUB_EVENT_NAME=workflow_dispatch TEST_SCOPE=full bash android/ci/run-emulator-validation.sh)
  ```

- **주의사항**: BoundDriveAuthorization.acceptResult/withVerifiedTransport의 기존 exact principal proof를 재사용한다. production Identity.getAuthorizationClient(app).authorize(setAccount(bound)) Task 취소/late result를 generation ticket으로 거부한다. 테스트 대역은 silent real-account 성공 증거가 아니다. 암묵 초기화/새 migration의 추정이 틀리면 우회하지 말고 blocked로 보고한다. 단언 완화·skip 금지.

### T11: 약일일 periodic와 bounded catch-up 등록

- **depends_on**: T1, T5, T9, T10; **tier**: standard; **status**: pending.
- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `A/SyncWorkScheduler.kt` | 신규 | unique names/constraints/backoff/restore |
  | `A/SyncWorker.kt` | 신규 | CoroutineWorker budget/session 결과 매핑 |
  | `A/HealthApplication.kt` | 수정 | per-worker auto reader/session/engine 및 canonical gate 배선 |
  | `AI/SyncWorkSchedulerInstrumentedTest.kt` | 신규 | 실제 WorkManager DB/requests |

  | `android/ci/verify-workmanager-isolation.sh` | 수정 | SyncWorkSchedulerInstrumentedTest 고정helper registry·remainder 제외 등록 |

- **인터페이스**: 2절 `AutomaticWorkContracts`, `AutomaticSessionRoutingContracts`, `WorkManagerIsolationRegistry` 참조. 기존 port를 바꾸는 추가 계약은 부모에 먼저 통합한다.
- **결정 로직**: automatic=false → enqueue 없음; true+current generation+Drive → unique periodic와 catch-up; stale/local → no health admission.
- **작성할 테스트**: `Explicit automatic opt in creates one constrained daily CoroutineWorker without exact time promises` (①성공기준·②경계/동등분할·③오류·④순서; 실제 SQLite/WorkManager/프로세스 통합). 위 상태/입력과 기대 결과를 assertion으로 검증한다. 신규 테스트이며 기존 회귀는 삭제하지 않는다.
- **교정6 검증**: worker의 실제 enabled entry는 전용 claim/finish로 라우팅하고 default off 또는 due 이전 auth/metadata/records/remote 호출 0을 기존 worker fixture에서 검증한다. generic store start 거부만으로 IO 보장을 주장하지 않는다.
- **완료 기준**: 위 검증이 실제 통과하고 개인정보 없는 evidence가 있다. 미수행은 pending이다.
- **검증 방법** (실행 단계, `android/`에서):

  ```bash
  ./gradlew :app:assembleDebugAndroidTest
  (cd "$(git rev-parse --show-toplevel)" && GITHUB_EVENT_NAME=workflow_dispatch TEST_SCOPE=full bash android/ci/run-emulator-validation.sh)
  ```

- **주의사항**: production factory에서 auto reader를 session/engine에 함께 주입하며 manual 캐시를 재사용하지 않는다. manual-first/worker-first 생성 순서 모두 실제 reader 호출을 검증한다. periodic 24h, CONNECTED, batteryNotLow, exponential backoff30s를 앱 선택으로 사용한다. periodic/one-time 이름 분리, generation만 입력. unique KEEP로 중복 catch-up 병합; 하나의 worker invocation은 최대7일 한 batch만 처리하고 내부 drain loop 없음. 남은 gap은 durable 유지하여 다음 trigger/catch-up이 처리한다. runAttemptCount는 판정 원본이 아니다. 암묵 초기화/새 migration의 추정이 틀리면 우회하지 말고 blocked로 보고한다. 단언 완화·skip 금지.

### T12: worker retry와 프로세스 재시작 회귀

- **depends_on**: T11; **tier**: standard; **status**: pending.
- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `AI/SyncWorkerInstrumentedTest.kt` | 신규 | TestDriver/실제 SQLite outcome/stop |
  | `AI/SyncRetryProcessRecoveryInstrumentedTest.kt` | 신규 | host 단계별 합성 seed/assert |
  | `android/ci/verify-sync-retry-process-recovery.sh` | 신규 | 별도 process force-stop/restart 검증 |
  | `.github/workflows/android-emulator-tests.yml` | 수정 | 기존 recovery와 함께 합성 검증 |

  | `android/ci/verify-workmanager-isolation.sh` | 수정 | SyncWorkerInstrumentedTest 고정helper registry·remainder 제외 등록 |

- **인터페이스**: 2절 `AutomaticRetryContracts`, `WorkManagerIsolationRegistry` 참조. 기존 port를 바꾸는 추가 계약은 부모에 먼저 통합한다.
- **결정 로직**: RETRYABLE+남은 durable claim 또는 DEFERRED(due 미도달) → Result.retry; blocked/exhausted/merged/stale → retry loop 없이 Result.success+정확한 durable state; success → coverage 자체로 판정.
- **작성할 테스트**: `Only transient batch failures retry at most three times and uncertain creates reconcile first` (①성공기준·②경계/동등분할·③오류·④순서; 실제 SQLite/WorkManager/프로세스 통합). 위 상태/입력과 기대 결과를 assertion으로 검증한다. 신규 테스트이며 기존 회귀는 삭제하지 않는다.
- **계약 보정3 검증**: preflight와 ownerless busy union의 process 경합 및 stale/손상 외부 호출 0, explicit eligible retirement의 불확실성 보존을 기존 통합 테스트에서 검증한다.
- **완료 기준**: 위 검증이 실제 통과하고 개인정보 없는 evidence가 있다. 미수행은 pending이다.
- **검증 방법** (실행 단계, `android/`에서):

  ```bash
  ./gradlew :app:assembleDebugAndroidTest
  (cd "$(git rev-parse --show-toplevel)" && GITHUB_EVENT_NAME=workflow_dispatch TEST_SCOPE=full bash android/ci/run-emulator-validation.sh)
  bash ci/verify-sync-retry-process-recovery.sh
  ```

- **주의사항**: 신규 retry host-only class를 고정host-exclusion에 넣고 기존workflow의 full mode에서만 기존fullhelper 뒤 신규host를 실행한다. optional diagnostic를 full로확대하지않는다.  실제 SQLite+WorkManager에서 periodic transient→backoff 중 app-open catch-up 및 역순, process restart, due 직전/정각, CAS 경합을 제어된 clock으로 검증한다. due 이전 auth/HC access·record/remote/count 증가는 모두0이다. factory가 manual-first/worker-first일 때 SCHEDULED/CATCH_UP은 auto reader의 feature/grant/aggregate 경로, MANUAL은 기존 raw reader 경로에만 도달하고 detectPending trigger도 일치하며 gate 경합 credential factory는1회다. 실제 initial+3 retry 뒤 process restart/new periodic/new attempt로 다섯 번째 같은 lineage 실행을 허용하지 않는다. claim 직후/발행 뒤/소진 commit 직전 kill을 각각 검증한다. claim 후 kill은 보수적으로 1회 소비된 상태이며 자동 refund하지 않는다. WorkManager stop cancellation은 다시 던지고 remote rollback을 주장하지 않는다. 암묵 초기화/새 migration의 추정이 틀리면 우회하지 말고 blocked로 보고한다. 단언 완화·skip 금지.

**Checkpoint**: 해당 구간의 실제 source/계약/회귀와 parent pin을 root가 확인한 후 후속 구간으로 진행한다.

### T13: 동의와 자동 설정의 generation 전이

- **depends_on**: T12; **tier**: standard; **status**: pending.
- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `A/AutomaticSyncSession.kt` | 신규 | metadata-only restore/explicit opt-in/off |
  | `A/SqliteSyncStateStore.kt` | 수정 | setAutomaticCAS/invalidate lineage 보존 |
  | `AT/AutomaticSyncSessionTest.kt` | 신규 | consent/epoch/lifecycle |
  | `AI/AutomaticSettingsInstrumentedTest.kt` | 신규 | 실제 store+WorkManager off/reset |

  | `android/ci/verify-workmanager-isolation.sh` | 수정 | AutomaticSettingsInstrumentedTest 고정helper registry·remainder 제외 등록 |

- **인터페이스**: 2절 `AutomaticSettingsContracts`, `WorkManagerIsolationRegistry` 참조. 기존 port를 바꾸는 추가 계약은 부모에 먼저 통합한다.
- **결정 로직**: 전경 설명+구성된 Drive+유효 category+feature/grant+별도 opt-in → enable; opt-out/settings/reset → 먼저 generation 무효 후 queued cancel.
- **작성할 테스트**: `Opt out configuration change and confirmed reset invalidate old admission while preserving history reset scope` (①성공기준·②경계/동등분할·③오류·④순서; 실제 SQLite/WorkManager/프로세스 통합). 위 상태/입력과 기대 결과를 assertion으로 검증한다. 신규 테스트이며 기존 회귀는 삭제하지 않는다.
- **완료 기준**: 위 검증이 실제 통과하고 개인정보 없는 evidence가 있다. 미수행은 pending이다.
- **검증 방법** (실행 단계, `android/`에서):

  ```bash
  ./gradlew :app:assembleDebugAndroidTest
  (cd "$(git rev-parse --show-toplevel)" && GITHUB_EVENT_NAME=workflow_dispatch TEST_SCOPE=full bash android/ci/run-emulator-validation.sh)
  ```

- **주의사항**: enable는 기존 namespace/coverage를 유지한 config 자동 bit CAS만 허용하고 새 권한을 자동 요청하지 않는다. disable는 기존 invalidate 의미(새 generation, auto off, 기존 metadata archive)를 사용하고 lineage metadata를 삭제하지 않는다. binder 계정은 private 유지. 재enable은 새 generation/명시 동의와 fresh 검증이며 archived coverage 합치기 금지. disable/invalidate 뒤 same physical namespace known-target 안전성은 선행 manual/SAF 계약을 따른다. 암묵 초기화/새 migration의 추정이 틀리면 우회하지 말고 blocked로 보고한다. 단언 완화·skip 금지.

### T14: 현재성 화면의 IO 없는 투영

- **depends_on**: T13; **tier**: standard; **status**: pending.
- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `A/SyncCoverageUi.kt` | 신규 | coverage 모델/다음 행동 순수 판정 |
  | `AT/SyncCoverageUiTest.kt` | 신규 | gap/EMPTY/partial/recent/old/error |

- **인터페이스**: 2절 `CoverageUiContracts` 참조. 기존 port를 바꾸는 추가 계약은 부모에 먼저 통합한다.
- **결정 로직**: 최근2일/연속 coverage/lastUploadAt 각각 표시; EMPTY → checked만; partial/excluded/stale/blocker → full current 금지.
- **작성할 테스트**: `Home shows gaps coverage freshness last upload blockers and stale separately from prior success and empty` (①성공기준·②경계/동등분할·③오류·④순서; JVM 유닛/adapter 경계). 위 상태/입력과 기대 결과를 assertion으로 검증한다. 신규 테스트이며 기존 회귀는 삭제하지 않는다.
- **완료 기준**: 위 검증이 실제 통과하고 개인정보 없는 evidence가 있다. 미수행은 pending이다.
- **검증 방법** (실행 단계, `android/`에서):

  ```bash
  ./gradlew :core:test :app:testDebugUnitTest
  ```

- **주의사항**: scopeKey별 manual/auto 결과를 합산해 전체 완료로 만들지 않는다. 더 최근 manual upload가 global lastUploadAt만 갱신하고 auto SUMMARY freshness/blocker는 유지되는 사례와 ENQUEUED(unmet constraint/backoff)/BLOCKED(dependency)/durable auth blocker를 각각 검증한다. null pending count는 unknown 표시이며 0이 아니다. scheduledAt/startedAt/lastSuccessAt/coverage dates를 구분하고 WorkInfo SUCCEEDED를 last upload로 쓰지 않는다. 암묵 초기화/새 migration의 추정이 틀리면 우회하지 말고 blocked로 보고한다. 단언 완화·skip 금지.

### T15: 실제 Activity에서 자동 설정과 gap 표시

- **depends_on**: T14; **tier**: standard; **status**: pending.
- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `A/SyncViewModel.kt` | 수정 | explicit MANUAL refresh/metadata-only restore/opt-in catch-up enqueue |
  | `A/MainActivity.kt` | 수정 | 전경 background permission launcher와 ticket |
  | `A/HealthHomeScreen.kt` | 수정 | 자동 설명/상태/actions 조각 연결 |
  | `AI/AutomaticUiInstrumentedTest.kt` | 신규 | 실제 activity 복원/선택/동의/large text |

- **인터페이스**: 2절 `AutomaticSettingsContracts` 참조. 기존 port를 바꾸는 추가 계약은 부모에 먼저 통합한다.
- **결정 로직**: 앱 열기 off → metadata/gap만; on → unique catch-up enqueue; picker/permission 반환 → 상태만, 명시 동의 없이 sync 없음.
- **작성할 테스트**: `Opening with automatic off only displays gaps and automatic on enqueues one catch up` (①성공기준·②경계/동등분할·③오류·④순서; Compose/Activity E2E). 위 상태/입력과 기대 결과를 assertion으로 검증한다. 신규 테스트이며 기존 회귀는 삭제하지 않는다.
- **완료 기준**: 위 검증이 실제 통과하고 개인정보 없는 evidence가 있다. 미수행은 pending이다.
- **검증 방법** (실행 단계, `android/`에서):

  ```bash
  ./gradlew :app:assembleDebugAndroidTest
  (cd "$(git rev-parse --show-toplevel)" && GITHUB_EVENT_NAME=workflow_dispatch TEST_SCOPE=full bash android/ci/run-emulator-validation.sh)
  ```

- **주의사항**: SAF UI/ManualDriveUi/default parameters·legacy history·insets·phone profiling를 보존한다. 신규 자동 permission 요청은 별도 전경 action에서 feature 확인 후 실행하며 broad history 권한 없음. LOCAL 자동 toggle은 이유와 함께 disabled다. restore가 authorize/HC record read/upload를 직접 호출하지 않는다. 암묵 초기화/새 migration의 추정이 틀리면 우회하지 말고 blocked로 보고한다. 단언 완화·skip 금지.

### T16: 취소와 접근성 통합 회귀

- **depends_on**: T15; **tier**: standard; **status**: pending.
- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `AI/AutomaticUiInstrumentedTest.kt` | 수정 | light/dark/font2.0·scroll semantics |
  | `AI/ManualDriveFlowInstrumentedTest.kt` | 수정 | worker 경합/late result |
  | `AI/HealthHomeScreenInstrumentedTest.kt` | 수정 | layout/history/원격 stale |
  | `AI/PhoneProfilingScreenInstrumentedTest.kt` | 수정 | 디버그 수동 계측 보존/release 없음 |

- **인터페이스**: 2절 `CoverageUiContracts` 참조. 기존 port를 바꾸는 추가 계약은 부모에 먼저 통합한다.
- **결정 로직**: 동의 철회/pause/계정변경/reset 뒤 late callback → 새 상태 변경 없음; large text → 모든 허용 action scroll로 도달.
- **작성할 테스트**: `Consent account tree automatic manual retry and reset remain reachable in dark light and large text semantics` (①성공기준·②경계/동등분할·③오류·④순서; Compose/Activity E2E). ⑤기존 관련 회귀 재사용. 위 상태/입력과 기대 결과를 assertion으로 검증한다. 신규 테스트이며 기존 회귀는 삭제하지 않는다.
- **완료 기준**: 위 검증이 실제 통과하고 개인정보 없는 evidence가 있다. 미수행은 pending이다.
- **검증 방법** (실행 단계, `android/`에서):

  ```bash
  ./gradlew :app:assembleDebugAndroidTest
  (cd "$(git rev-parse --show-toplevel)" && GITHUB_EVENT_NAME=workflow_dispatch TEST_SCOPE=full bash android/ci/run-emulator-validation.sh)
  ```

- **주의사항**: permission/account/tree 결과가 stale이면 무시하고 credential 재사용 금지. profile은 foreground manual only·debug allowlist를 유지한다. worker raw exceptions/IDs/CSV/URI/health/account/WorkRequest UUID는 진단 금지. 제3자 library DEBUG 로그를 앱 safe diagnostic으로 취급하지 않으며 release manifest/logging에서 SDK/WorkManager verbose 출력을 활성화하지 않는다. 실제 TalkBack PASS를 합성 semantics에서 추론하지 않는다. 암묵 초기화/새 migration의 추정이 틀리면 우회하지 말고 blocked로 보고한다. 단언 완화·skip 금지.

**Checkpoint**: 해당 구간의 실제 source/계약/회귀와 parent pin을 root가 확인한 후 후속 구간으로 진행한다.

### T17: 해결 의존성 최종 재감사와 개발 증거

- **depends_on**: T16; **tier**: standard; **status**: pending.
- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `docs/V1/dependency-audit.json` | 수정 | T1 도입 audit 보존 + 최종 네 resolved configuration OSV 재감사 |
  | `docs/V1/automated-testing.md` | 신규 | 실제 명령/합성/기기 구분 |
  | `docs/V1/device-acceptance.md` | 수정 | Doze/reboot/grants/accounts/TalkBack pending |
  | `docs/2026-11-05-v1-completion/automation-ui/report.md` | 신규 | reviewed head/실행 evidence/남은 gate |

- **인터페이스**: 2절 `ReleaseEvidence` 참조. 기존 port를 바꾸는 추가 계약은 부모에 먼저 통합한다.
- **결정 로직**: 조회 오류 → 미확인; 도달 가능한 high/critical → 차단/수정; mock 성공 → 실제 계정·기기 수용 미승격.
- **작성할 테스트**: `Real store cancellation admission TLS provider worker and UI suites pass with device acceptance separately pending` (①성공기준·②경계/동등분할·③오류·④순서; 문서/정적/전체회귀). 위 상태/입력과 기대 결과를 assertion으로 검증한다. 신규 테스트이며 기존 회귀는 삭제하지 않는다.
- **완료 기준**: 위 검증이 실제 통과하고 개인정보 없는 evidence가 있다. 미수행은 pending이다.
- **검증 방법** (실행 단계, `android/`에서):

  ```bash
  ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintDebug :app:lintRelease :app:assembleDebugAndroidTest
  ```

- **주의사항**: resolved Maven ModuleComponentIdentifier 전체를 releaseRuntimeClasspath/debugRuntimeClasspath/debugUnitTestRuntimeClasspath/debugAndroidTestRuntimeClasspath에서 수집한다. BOM/variant/testing 포함 OSV querybatch, source head/time/scopes/errors/disposition 기록. known Guava 기존 결과를 무조건 복사하지 않는다. private key backup은 계속 pending. 암묵 초기화/새 migration의 추정이 틀리면 우회하지 말고 blocked로 보고한다. 단언 완화·skip 금지.

### T18: roadmap과 공개 안내의 실제 상태 반영

- **depends_on**: T17; **tier**: standard; **status**: pending.
- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `README.md` | 수정 | 구현된 V1 동작과 미확인 수용 |
  | `docs/implementation-plan.md` | 수정 | 실제 완료 checkbox만 |
  | `docs/V1/storage-destinations.md` | 수정 | SAF foreground/background 구분 |
  | `docs/architecture.md` | 수정 | 실제 WorkManager/runtime/summary/retry 경계 |
  | `docs/data-contract.md` | 수정 | P0 manual 불변과 자동 derived reserved summary 설명 |

- **인터페이스**: 2절 `ReleaseEvidence` 참조. 기존 port를 바꾸는 추가 계약은 부모에 먼저 통합한다.
- **결정 로직**: 실제 개발 증거 있음 → 해당 항목만 done; 계정/기기/출시 미수행 → pending.
- **작성할 테스트**: `Documentation records implemented automatic behavior without claiming device or production provider acceptance` (①성공기준·②경계/동등분할·③오류·④순서; 문서/정적/전체회귀). 위 상태/입력과 기대 결과를 assertion으로 검증한다. 신규 테스트이며 기존 회귀는 삭제하지 않는다.
- **완료 기준**: 위 검증이 실제 통과하고 개인정보 없는 evidence가 있다. 미수행은 pending이다.
- **검증 방법** (실행 단계, `android/`에서):

  ```bash
  ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintDebug :app:lintRelease :app:assembleDebugAndroidTest
  ```

- **주의사항**: 개발 merge는 출시 승인이 아니다. 기존 승인 spec/과거 implemented spec을 소급 수정하지 않는다. 새 계약의 data-contract 설명은 parent integration에서 확정된 additive summary 의미만 반영한다. 암묵 초기화/새 migration의 추정이 틀리면 우회하지 말고 blocked로 보고한다. 단언 완화·skip 금지.

## 4. 검증

준비 단계에는 실행하지 않는다. 아래는 구현 후 evidence가 필요한 항목이다.

1. T1은 네 실제 resolved graph/OSV·merged manifest·AAR metadata/호환성 초기 audit와 starter를 모두 통과해야 하며 T17은 최종 graph를 재감사한다. T1 starter는 `WorkManagerTestInitHelper.initializeTestWorkManager`의 실제 context/worker completion을 증명한다. 이후 TestDriver constraints와 worker permission/account/failure 검증은 실제 WorkManager DB+실제 private SQLite store를 사용한다. fixture permission/SDK fake는 실제 Galaxy grant 증거가 아니다. debug Application 중복 초기화·잘못된 factory·unknown worker construction도 fail-closed로 검증한다.
2. retry baseline은 immutable initial proposal과 prior manual row/partial grants/planning 후 grant·scope 변화/cohort 교체 거부, periodic↔catch-up transient backoff 우회 거부, restart/due 직전·정각/CAS에서 health/auth/remote/count 변화0, 그리고 `(initial, retry1, retry2, retry3)`의 정확히4회 입장, 다섯 번째 fresh attempt/process/periodic 입장 거부, claim CAS race, day vector source 변화, source 미조회 상태, retry3 뒤 finish failure, active journal recovery, 명시 retry만 reopen, stale generation/old callback, 다른 completed batch의 정상 refresh 허용이다. 실제 SQLite reopen·rollback fault·host process kill을 쓴다. remote unknown/prior/REMOTE_STALE와 원본 binding/gaps가 모든 경계에서 유지되어야 한다. 단순 fake store count는 durable proof가 아니다.
3. migration은 valid actual v1→v2→재열기, v1 unfinished/hot journal→canary 검증→migration, stage 생성/검증/marker/publish/reopen 각 crash, reset owner와 migration owner 경합, corrupt/future schema/symlink/sidecar/source 교체 거부, original byte fingerprint 보존, v2 hot-journal 복구와 unsupported version3를 검증한다. 기존 native v1 forensic fixture도 의미 있는 그대로 유지한다. 자동 reset/원본 삭제/strict schema 생략으로 통과 금지.
4. HC adapter는 실제 설치1.1.0 request 구조를 fake client 경계에서 검증한다. unavailable/background feature denied/grant revoked/partial grants; null vs0 duration·fractional minutes·metric별 provenance; latest weight ties; cross-midnight/earlier-start unproven HR BLOCKED; 완전한 no-session에서0 HR calls; 반복 page token·page/sample/query 공유 한도. steps/sleep/exercise 가능한 범위는 HR 불명에도 진행하고 전체5종 완료가 아니다. manual CSV golden bytes와 raw 세션 bounds·기존 normalization/HR pagination 회귀를 재사용한다.
5. per-worker automatic reader/session/engine과 manual cached session을 생성 순서 양쪽에서 검증하고 detectPending의 명시 trigger, auto grant/aggregate와 manual raw 경로 분리 및 canonical gate 상호 배제를 실제 배선으로 검증한다. UI는 실제 Activity에서 restore(off/on), 설정/동의/권한/ticket 늦은 결과, worker와 manual 동시 admission, EMPTY/partial/gap/REMOTE_STALE/old success/recent failure, reset과 history 삭제 구분을 검증한다. light/dark/fontScale2.0과 작은 viewport에서 scroll을 통해 동의·계정·폴더·자동 설정·수동 계속·retry·reset확인까지 접근하고 interactive semantics가 잘리지 않아야 한다. 실제 TalkBack/기기 관찰은 별도 pending이다.
6. `android/`에서 `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintDebug :app:lintRelease :app:assembleDebugAndroidTest`를 실행한다. 연결된 각 API emulator의 repo root에서 `GITHUB_EVENT_NAME=workflow_dispatch TEST_SCOPE=full bash android/ci/run-emulator-validation.sh`를 실행해 helper registry/process 격리, 기존 전체 Native/history/sync-state 및 등록된 retry host를 함께 검증한다. 필터 없는 단독 전체 Gradle instrumentation으로 대체하지 않는다. 기존 JVM/TLS/store/UI/profiling 회귀를 삭제하지 않으며 SDK-free core 성공을 APK 성공으로 표현하지 않는다.
7. 실제 resolved configurations 네 종류와 work/coroutines/Kotlin/Guava `dependencyInsight`, merged debug/release manifest의 single-process/initializer/diagnostics receiver/backup/background permission, release APK의 debug profiling UI/log 부재를 검사한다. OSV querybatch 오류·취약점·reachability/disposition을 구분하고 테스트 좌표도 포함한다. 심각도만으로 무조건 zero-findings를 만들지 않는다.
8. source·fixtures·CI upload path·APK logs/diagnostics의 allowlist를 검사한다. raw health/CSV/token/auth code/account/device/document/URI/SDK-provider exception/WorkRequest UUID를 출력하지 않는다. synthetic 계정 문자열도 기존 공개 fixture 관례만 사용한다. noBackup DB/migration marker/backup exclusion을 실제 manifest와 경로로 확인한다. worker는 clipboard/manual profiling을 자동으로 호출하지 않는다.
9. reviewed commit별 실제 명령/결과/미실행 이유를 기록하고 독립 latest-head 리뷰/차단 지적 fix+재검토를 PR 댓글에 남긴다. 개발 merge와 출시 수용은 별개다. 실제 Galaxy background feature/grant, silent selected-account authorize/about/upload, local provider persisted grant/reboot, Doze/no network/low battery/reboot/force-stop/missed-day recovery, Samsung delayed source, actual TalkBack, private signing-key recoverable backup은 수행 전까지 pending이다. 이번 준비에는 실제 계정/건강자료/업로드/grant를 사용하지 않는다.

### 보완 추적성

§1의 원문 기준/기존 테스트 이름을 유지하며 아래 추가 케이스를 해당 T에 연결한다. 테스트 출처는①성공기준,②동등/경계,③오류,④상태/순서,⑤기존 회귀다. 각 T의 테스트 이름과 조건·기대 결과가 실행 기준선이다.

| §1 기준의 연결 | 추가 검증 | 작업/층 |
|---|---|---|
| 제한된 retry·원자 저장·공용 admission | initial immutable proposal·prior manual/partial grants/scope·cohort 변경 거부/initial+3회/due 이전 periodic↔catch-up·restart 우회 불가/정각 CAS/명시 reopen/소진 blocker 원자성/정상 completed batch | T2~T5·T12; ①②③④/JVM·실제 SQLite·host process |
| 원자 저장·복구·보안 | 실제 v1 migration·unfinished journal·unknown/prior/binding/gap 보존·future3/corruption 거부·marker ownership | T3~T4; ①②③④⑤/실제 DB·파일·process |
| 자동 bounded 읽기·EMPTY/partial·revision·REMOTE_STALE | supported nullable aggregate provenance/summary-only daily/manual byte·digest 불변/weight latest/HR 미입증 차단·독립 category | T6~T9; ①②③④⑤/JVM·SDK adapter |
| worker 권한/계정·기본 off·약일일 constraints | T1 도입 감사+starter/auto reader session·engine 실제 배선·detectPending trigger·manual-first/worker-first gate/fresh selected-account SDK adapter/same-token about before HC/worker no interactive UI/constraints·bounded batch | T1·T10~T12; ①②③④/WorkManager·SQLite·SDK boundary |
| 앱 열기·home·취소·접근성 | global lastUploadAt와 scope freshness 분리/WorkInfo ENQUEUED constraints vs BLOCKED dependency/metadata-only restore/unique catch-up/EMPTY 및 최근·과거·partial·blocked 안전 표시/late callback 무효/전 actions 도달 | T13~T16; ①②③④⑤/SQLite·Compose Activity |
| 보안·개발 증거와 실제 수용 분리 | 네 resolved graph OSV/manifest/release/공개 artifact allowlist/기존 회귀/정확 roadmap·pending | T1·T17~T18·전체검증; ①③⑤/정적·통합·문서; 실제 수용은 비자동 |

| preflight·scope 독립성과 ownerless busy union | 미래 ACTIVE due 최대값·정각·다른 terminal scope 허용·same-scope terminal 거부·stale/손상 외부 IO 0·미완료 superset 동시 union 무손실 | T2/T3/T4/T5/T12; ①②③④/JVM·실제 SQLite·process |
| summary 및 projection 실패 경계 | 동일 불변 DTO의 missing/zero/max/방어복사·SUMMARY 및 수동 bytes/hash·revision 실패 해당 날짜 write 0·두 source reason/취소/store 분리 | T5/T6/T7; ①②③⑤/JVM |

| durable link와 legacy validation 경계 | 기존 scheduled generic start/engine 및 finished pending 유지·bound owner/day/finish 거부·orphan/reverse mismatch/opt-out·reopen·schema parity·early IO 0 | T2/T3/T4/T5/T11; ①②③④⑤/JVM·SQLite·worker |

### 원문 테스트의 작업 배정

§1의 첫 테스트 이름은 아래 배정에서 같은 이름의 실제 테스트 또는 요구별 fixture로 구현한다. 공유 manual 테스트는 기존 suite를 재사용한다. §3의 보완 테스트가 원문 요구 검증을 대체하거나 누락시키지 않는다.

| 원문 테스트 이름 | 책임 작업 |
|---|---|
| `Opening with automatic off only displays gaps and automatic on enqueues one catch up` | T15 |
| `Empty and partial grants never claim five categories upload success or continuous uploaded coverage` | T14·T16 |
| `Automatic workout heart queries stay bounded and uncertain scopes block without mixing manual coverage` | T9 |
| `Disappearing populated datasets retain remote stale and prevent full current without deletion` | T7·T16 |
| `Concurrent manual periodic and catch up requests admit once and reject old generation callbacks after restart` | T5·T12 |
| `Explicit automatic opt in creates one constrained daily CoroutineWorker without exact time promises` | T11·T15 |
| `Worker missing background health grant token scope or account records blocked without interactive launches` | T10·T12 |
| `Only transient batch failures retry at most three times and uncertain creates reconcile first` | T12 |
| `Opt out configuration change and confirmed reset invalidate old admission while preserving history reset scope` | T13 |
| `Home shows gaps coverage freshness last upload blockers and stale separately from prior success and empty` | T14 |
| `Consent account tree automatic manual retry and reset remain reachable in dark light and large text semantics` | T16 |
| `Private state and allowlisted diagnostics exclude health payload credentials identifiers and provider bodies` | T17·전체검증7~8 |
| `Real store cancellation admission TLS provider worker and UI suites pass with device acceptance separately pending` | T17·전체검증1~9 |

### PR 체크포인트와 병합 순서 — 실행 전 준비

아래는 기존 19개 작업의 검토·병합 경계이며 새 작업이나 실행 승인이 아니다. 각 작업의 ID, 의존성, 상태, 보정 횟수, 계약, 추적성 및 전체 검증을 그대로 유지한다. 실제 reviewed SAF 병합, 완료된 WorkManager foundation 6개 source 통합, parent/spec/ADR 통합 및 실제 revision 재고정은 **어떤 제품 수정이나 작업 실행보다 먼저** 필요하다. 현재 source pin은 그때까지 미확정이다.

| PR 체크포인트 | 기존 작업과 기준 브랜치 | 검토 경계 |
|---|---|---|
| 1 | T19 → T1; 위 선행 gate를 통과하고 실제 통합·재고정한 기준 | helper process 격리를 production bootstrap보다 먼저 완료한다. 기존 native inventory와 진단 경로를 보존한다. |
| 2 | T2 → T3 → T4 → T5 → T10; 병합된 PR 1 기준 | retry 모델, v1→v2 migration/recovery, 실제 SQLite claim/finalization, session/revision 연결 및 worker 계정 adapter를 일관된 경계로 검증한다. |
| 3 | T6 → T7 → T8 → T9; 병합된 PR 1 기준, PR 2와 병렬 가능 | aggregate 모델·summary CSV·의미 revision과 HC background reader/안전 차단을 검증하고 수동 CSV 및 기존 읽기 회귀를 보존한다. |
| 4 | T11 → T12 → T13; PR 2와 PR 3이 모두 실제 병합된 기준 | 두 lane을 합쳐 worker/scheduler, process recovery와 durable generation·동의 전이를 함께 검증한다. 명시적 동의 전 자동 작업을 활성화하지 않는다. |
| 5 | T14 → T15 → T16 → T17 → T18; 병합된 PR 4 기준 | 화면 투영·Activity·접근성·취소 회귀와 최종 의존성 감사, 실제 증거 및 공개 문서 상태를 함께 검토한다. 미수행 기기·provider 수용은 pending으로 남긴다. |

DAG: `foundation → PR1(T19→T1) → {PR2(T2→T3→T4→T5→T10), PR3(T6→T7→T8→T9)} → PR4(T11→T12→T13) → PR5(T14→T15→T16→T17→T18)`.

PR 2와 PR 3은 같은 병합된 PR 1에서 병렬 개발할 수 있다. 파일이 달라도 `DestinationSync`와 revision 직렬화·공유 모델 사이에는 의미상 겹침이 있으므로 병렬 작업 전에 공통 계약을 고정하고 실제 diff와 통합 결과를 재검증한다. 한쪽을 먼저 병합한 뒤 다른 쪽은 실제 병합 head에 rebase하고 최종 head의 필수 검증과 독립 리뷰를 다시 수행한다. PR 4는 양쪽 실제 병합을 포함한 기준에서 시작한다. PR 4의 store, application 및 helper registry 변경도 선행 병합 위에서 순차 통합한다. 각 경계는 승인된 spec slice에 부합하고 독립적으로 빌드·검증 가능하며 기존 runtime과 수동 동작을 보존해야 한다. schema·store·session의 불완전한 조합이나 동의 전 background 활성화를 노출하지 않는다. 경계가 이를 충족하지 못하면 결합된 작업을 함께 검토하도록 경계를 재평가하며 요구사항과 gate는 완화하지 않는다.

각 PR의 최종 head마다 양 API native, host process 및 JVM을 포함한 전체 필수 CI와 기존 inventory를 실행하고, 새로운 독립 품질·보안 리뷰 및 PR 댓글을 남긴다. 차단 지적은 수정하고 변경된 head를 재검토한 뒤 병합한다. flaky 필터·skip은 사용하지 않는다. T1 초기 audit는 T17 최종 재감사를 대체하지 않는다. 실제 계정·기기·provider 및 출시 수용은 수행 전까지 pending이다.


## 5. 롤백

자동 opt-out/generation invalidation을 먼저 내구성 commit하고 해당 generation work를 취소한다. 이미 발행된 원격 write를 rollback했다고 말하지 않는다. v2 DB를 v1 코드로 열면 unsupported로 안전 차단되는 것이 정상이며 schema 버전만 내리거나 retry table/metadata를 삭제해서 downgrade하지 않는다. 구현 전의 앱으로 revert가 필요하면 v2 원본을 noBackup에 보존하고 자동 off/명시 복구 안내를 제공한다. v1 raw/source 백업을 모든 이후 write가 없었던 것처럼 복원하는 것은 금지다. 새 테이블/마이그레이션 없이 해결 가능한 더 작은 안전 계약이 parent 독립 검토에서 입증되면 실행 전 draft/ADR을 보정한다. 이 문서의 준비 작업 자체에는 데이터 rollback이 없다.

## 기술 초안 보정 기록

- 2026-11-13: `/workspace/tooling/v1-auto-retry-plan-review.md`의 Important 2건 및 `/workspace/tooling/v1-auto-hc-ui-plan-review.md`의 P2를 반영했다. retry-not-before의 모든 automatic claim CAS, immutable initial scope/date proposal, T1 도입 감사 선행과 T17 최종 재감사를 명시했다. 실제 source의 cached manual reader/ambient detectPending trigger는 per-worker auto session+동일 canonical gate 배선으로 보정하고 UI의 WorkInfo 상태와 global upload/scope freshness 의미를 분리했다. schema 단일-cohort 대안의 검토 한계도 기록했다.
- **draft 기술 보정만**이며 spec/제품 범위·상위 실행 gate는 바꾸지 않았다. 제품/테스트/Gradle/GitHub/계정·기기·build/commit은 수행하지 않았다. 다음 단계는 수정 초안의 독립 재검토다. PR17+SAF 실제 병합·rebase·parent 계약/파일·추적성 및 Proposed ADR 통합·revision 재고정 전 실행하지 않는다. 복구/환경 표는 실행 증거가 없어 비운다.

## 복구 기록

| 작업 | 회차/2 | 실패 증거·원인 | 새 근거·수정 가설 | 계획 보정 전→후·유지 기준 | 수정 파일·보존 대상 | 검증 명령·결과 | 실행자·다음 조치 |
|---|---|---|---|---|---|---|---|

## 환경 준비 기록

| 원인·근거 | 준비 조치 | 조치 사용/1 | 확인 명령·결과 | 확인 사용/1 | 다음 조치 |
|---|---|---|---|---|---|

### 병렬 준비 — 실제 WorkManager singleton 격리

2026-11-13 read-only 설치2.11.0 source/javap와 early starter를 대조한 결과 publiccloseWorkDatabase는 globaldelegate를복원하지않고 SystemJobService는 appDI를거치지않는것을확인했다. 독립 계획자문을반영해T19격리기반→T1productionbootstrap으로분리하고T11/T12/T13 registry소유를기존5파일이하로추가했다. 이는실행전draft보완이며parent/ADR/actualreviewedmerge/rebase/re-pin gate전 제품수정은하지않는다. T1뒤retry/store lane T2→T3→T4→T5→T10과 summary/HC lane T6→T7→T8→T9를파일충돌없이병렬화하고T11에서합류한다. root는실행시공통계약pin·각lane검증·Gradle최대2·sourcefreeze를유지한다. 기존스펙/최종전체gate/실기기미확인은불변이다.

### 최신 SAF source 호환 준비 — 실행 gate 유지

2026-11-13 별도Terra의읽기전용source대조를반영한다. 실제reviewedSAFmerge/rebase/parent·spec·ADRrepin전제품실행은계속금지다. SyncViewModel.ManualDestinationDependencies의store/ScopedReader/execution/configuration/clock주입과publicapplication/기존testsessionFactory생성자를보존한다. 수동Drive와SAF는동일주입reader·execution을사용하며injectedreader가concreteHealthConnectReader와다르면execution도반드시같은injectedreader로명시한다. worker는자신의freshautomaticreader와새execution/DestinationSync에같은reader를연결하며수동두session/Driveprofiling/SAFproof·loadKnownTargets를대체하지않는다. 양manual목적지와manual-first/worker-first실제배선을검증한다.

현재detectPending()은인자없고config.automatic으로trigger를선택한다. explicitdetectPending(trigger)는T5의실제신규변경이며현재SyncViewModel에기존detectPending호출이있다고가정하지않는다. 기존refreshDestinations의두manualmetadata복원과committedkind/gen투영을유지한다. 실제schema/transact는아직ledger CAS나automaticfinalization을지원하지않으므로이를이미있는API로브리프에기록하지않는다.

T19는earlystarter의옛script가아닌실제mergedSAF의진단earlyexit·critical/remaining/host명령과최종Native목록을기준으로helper격리를추가한다. WorkManagerStarterInstrumentedTest전체를격리하고normal-before/helper/normal-after는각각별도instrumentationprocess여야한다. 예전121/130등숫자를최종inventory로하드코딩하지않는다. T1manifest는전체Startup provider가아닌WorkManagerInitializer항목만제거하며diagnostics receiver처리는실제mergedmanifest/audit결과를기록한다. 기존155dependencygraph초기증거는graph동일성을대조해재사용하되T17최종재감사를생략하지않는다.

### 준비 교정1 — T19/T1 공통 계약의 브리프 표기

실행 전 read-only 브리프 조립에서T19와T1이exit2/WorkManagerIsolationProbe해소실패를보고했다. §2의기존계약3개는존재하지만지원표기인bullet이아닌산문이었고,하나의§2뒤복수backtick참조는마지막이름만포착됐다. 동일원문에bullet접두어와각명시참조의§2를넣어계약을변경하지않고조립가능하게한다. T19/T1공통표기교정1로기록하고실제실행때해당회차를reset하지않는다. statusdraft/실제SAFmerge·WM통합·parent/spec/ADRrepingate와제품수정금지는유지한다. 실제source검토/parent통합뒤최종브리프를다시조립한다.

같은준비교정1에서WorkManagerStarter기존산문도bullet로옮기고명시참조표기를조립기가지원하는2절로정규화했다. 계약원문/작업경계는바꾸지않으며전체19작업의참조해소를실행전에점검한다.

같은 준비 교정1에서 §2의 기존 retry·summary·health 등 공통 계약을 assembler 지원 bullet/code header로 표기하고 관련 bullet에 기존 alias를 반복했다. 원문 계약과 API는 유지하며 교정 횟수를 초기화하지 않는다. status draft와 실제 SAF reviewed merge·완료된 WM 6 source 통합·parent/spec/ADR 재고정 전 제품 수정 금지 gate는 그대로다.

### 실제 통합 고정점 — 실행 승인 준비

현재worktree는실제SAFmerge9ab774beac85bf7a96953bb0a0aa1b8098c76c69에서분기했고completedWMsource6파일은donor와SHA동일하게6c5f5b0412029054cc992adc09a2296bcf0c696c로통합했다. 공통contract원문은부모2절로옮겼고ADR010은Proposed다. 과거draft의c334/미통합가정은당시이력이며현재source_base가실제기준이다. 부모commit후parent_rev를고정하고독립계획검토/기존V1위임의승인상태를확인한뒤T19를시작한다. 원래19task/correctioncount/전체gate는보존한다.

### 준비 교정2 — 독립 계획 검토의 격리·배선 충돌

실제fullrunner없이helper설치class를같이실행할수있던10개task명령/전체검증을registry기반fullrunner로교체했다. source/API/테스트기준은유지하고부모/ADR008의worker세션배선을freshperworker+공유canonicalgate로정합화했다. T19/T1및관련task의기존준비교정이력을초기화하지않으며새명령은실제양API에서검증해야한다.

### 실행 승인·T19 시작

소유자의 기존 V1 자동 승인 위임과 독립 계획 재검토 APPROVE(f5ab721, auto-integrated-plan-readiness-rereview.md)에 따라 실행을 시작한다. 준비 교정1·2는 누적 보존한다. T19는 새로운 Terra 세션에 조립 브리프로 위임하며 실제 양 API 격리/full 증거 전 T1 생산 초기화를 시작하지 않는다. 새로운 근거가 필요한 추가 교정은 기존 V1 범위의 소유자 위임을 적용하되 원래 작업 ID와 회차를 이어 기록한다.

### T19 교정3 — 독립 소스 검토의 네 국소 지적

기준3510bee, 보고 auto-t19-independent-review.md의 APK 재설치/runner 확인·private raw allowlist·다중 class별 inventory·실패/timeout 정리를 기존 verify-workmanager-isolation.sh 안에서 교정한다. 기존 probe와 모든 기존 suite/assertion·source는 보존한다. 교정1·2 및 이번 교정3을 원래T19에 누적하며 AGENTS.md의 같은V1 자동 승인으로 새 근거를 가진 가역 수정을 진행한다. 원문 공개를 검증된 합성 protocol에 한정하는 부모 계약을 재고정했다. bash-n·정상/누락APK/실패/skip/duplicate/timeout을 구별하는 private fake-adb 검증과 fresh 독립 재검토 후 실제 양API full을 수행한다. fake-adb 통과는 실제 기기/process 검증을 대신하지 않는다.

### T19 교정4 — 실제 AndroidJUnitRunner 성공 protocol

1b58950 재검토에서 캐시된 runner1.6.2 InstrumentationResultPrinter의 실제 bytecode는 testStarted에 stream=\n%s:(class header), 성공 testFinished에 stream=.을 내보내지만 교정3 parser는 빈 stream만 허용함을 확인했다. root도 실제 runtimejar javap로 동일 사실을 대조했다(auto-t19-runner-result-printer-bytecode.txt). 잘못 만든 fake 성공 응답을 실제 protocol의 클래스 continuation/성공 dot까지 정확히 반영하며 기대 identity·code·count·privacy·모든 미허용 출력 차단은 보존한다. 종료검증에서 pidof의 정상 부재 exit1과 timeout/adb오류도 구별하여 종료 미확인을 성공으로 처리하지 않는다. 기존 T19 회차1~3을 유지하고 이번4를 소유자 같은V1 가역 기술 교정 위임으로 수행한다. 파일은 기존 hostscript 하나이며 새 의존성/제품동작/검증기준 변경은 없다. 실제 protocol fixture의 교정 전 실패·교정 후 통과와 기존 부정 protocol 회귀·fresh 재검토 후 양API full을 실행한다.

### T19 실제 완료·T1 시작 (2026-11-14 UTC)

source2992452/run43527182302 공식 artifact API35 14088236324/sha256:9548d12b541c816ecbd67b73e74f98ed5118b2a5d5522ead1f46634e6cf51dca, API36 14087950535/sha256:093a97d19d5446fb585faa19aee96631991247027e574960edb0d0f66f5e438f. 각135unique Native/critical15+remaining117+isolated3, 기존132 보존, 실패/오류/skip/중복0; host recovery8+settings4+cleanup8 및 sync-state recovery17+cleanup17, killedholds pass0. root가 officialdigest와 실제XML/세격리raw·JSON/host원문을 직접 대조했다(/workspace/tooling/auto-t19-root-native-proof.json). 독립 소스 재검토2992452 APPROVE. T19누적교정1~4는 보존한다. 다음T1은 same task Terra 세션의 read-only preflight를 이어 진행하며 실제 production 초기화·manifest·네graph 감사·양API 재검증은 새로 수행한다.

### T1 교정3 — 기존 core 타입 import 누락

최초 구현 검증 compileDebugKotlin이 HealthApplication.kt:22 ScopedHealthReader unresolved reference로 중단됐다. core/Ports.kt:46의 기존 com.ny.my.health.core.ScopedHealthReader 선언을 root가 확인했다. 새 계약/의존성/동작을 추가하지 않고 HealthApplication.kt에 정확한 import 한 줄을 추가해 같은 전체 로컬 검증을 다시 수행한다. T1 준비 교정1·2를 보존하고 이번을 원래T1의 교정3으로 누적한다. 새 근거와 한정된 가역 수정을 소유자의 기존V1 자동 승인 위임으로 진행하며 후속 미확인 오류는 통과로 처리하지 않는다. manifest/probe/starter·기존회귀와 감사 gate는 유지한다.

### T1 교정3 결과·현재 head 감사 checkpoint

기존 core.ScopedHealthReader import 한 줄 교정 후 같은 동결 소스의 로컬 결합 검증이 2m42s/133tasks로 통과했다. 최초 결합 빌드 원문은 미보존이며 이를 원문 검증으로 꾸미지 않는다. root가 source를 cb981df로 고정하고 별도 직접 Gradle JVM/APK/lint/testAPK 검증을 15s PASS로 실행·로그 보존했다. 실제 JVM XML core84+app97/실패·오류·skip·중복0과 lint0error 및 debug/release merger를 직접 대조했다. 현재 네graph129/130/135/110(합집합155), 새OSV155결과/HTTP200/오류0/취약점0, 21개 command로그와 query/response/source hash 대조는 docs/V1/dependency-audit.json 및 root의 auto-t1-root-audit-proof.json에 있다. post-bootstrap Native와 최종 현재head 독립 검토는 대기이므로 T1은 in_progress다. 작업별 사전 준비된 T2/T6는 읽기 전용 점검만 수행하며 T1 실제 reviewed merge 뒤 별도worktree에서 구현한다.

### 준비 교정3 — 다음 lane 공개 계약 보정 (미구현)

준비 교정1/2와 기존 작업별 교정 이력·위임을 유지한다. T2/T3/T4/T5/T6/T7/T12의 API, preflight, ownerless pending union, transaction 검증과 summary DTO 공백을 부모 2절 및 Proposed ADR-010으로 보정했다. 이것은 실행 또는 검증 완료 주장이 아니다. T19 done, T1 in_progress, 나머지 pending과 기존 DAG/spec_rev/source pin은 그대로다. parent_rev는 아직 기존 값이며 실제 PR19 reviewed merge와 rebase 후 root가 parent/ADR을 실제 commit하고 child parent_rev를 그 commit으로 재고정해야 한다. SHA를 예측하지 않는다. 실제 PR19 full CI 및 fresh reviewed merge → 실제 source rebase → parent/ADR repin과 child pin → 전체 브리프 재조립 및 fresh 독립 문서 readiness review가 T2/T6 시작의 필수 gate다. PR2 retry와 PR3 summary는 실제 PR1 merge에서 파일 소유를 분리해 병렬 작성할 수 있으나 PR2를 먼저 merge하고 PR3를 실제 PR2 merge head로 rebase, 관련 및 필수 전체 검증과 fresh 독립 head review를 다시 수행한다. 실기기·실계정·활성화는 별도 pending이다.

### 실제 T1 완료·후속 계약 readiness gate

PR19는75cc443702a3467c87268d49ce52d4d75b2ed8a5로 병합됐고 이 worktree를 검증head8b9f38e와 tree동일한 실제master merge로 fast-forward했다. current Native43534668761 양API135 unique/기존132보존/오류·실패·skip·중복0 및기존host20/17+17, JVM43534668764의181개를 원본대조했고 독립검토·PR한글최종댓글6869176673을 기록했다. T1done이며 아래후속T2/T6는 parent/ADR commit→childrepin→19brief 재조립→fresh readiness 승인 전까지 pending을 유지한다. 구현은 실제PR19merge에 공통준비commit을 더한 같은 기준에서 두 별도lane worktree로 시작한다. 준비교정1·2·계약구체화3 및 T1import교정3·T19후속교정4까지의 원래누적이력은 초기화하지 않는다.

### T2/T6 독립 readiness 완료·병렬 실행 시작

공통준비head df5c072404b10ca9bb43bdcac58301eac42dfbec의 실제parent/ADR/child/데이터계약과T2/T4/T5/T6/T7 실제브리프를 작성자와 다른 fresh Terra 세션이 재검토해 READY를 기록했다(auto-contract-doc-final-readiness.md). 두 Important(unknown-scope metadata-only preflight, ownerless busy union)은 해소됐고 해당5브리프는 독립 재조립과 byte동일했다. source_base75cc443 실제reviewedmerge+이공통준비commit을 공유하는 별도retry/summary worktree에서 T2와T6을 시작한다. 각T는기존준비교정1·2 및 계약구체화3을 이어가며 source동작 변경을 실제verify하기 전완료로표시하지않는다.

### T2 교정4·5 위임 범위

첫core실패는기존MANUALfinishedpending을새자동제약으로거부한것이며기존SyncStateTest의COMMITTED기대는변경하지않는다. 자동trigger에한정한미완료pending/ownercontext검증과waitingdue=updatedAt+delay snapshotvalidation을완성한다. 최초85개중새lineage검증은통과했으나원래계획의불변입출력·cohort교체/완료후새lineage·잘못된입력·owner문맥·정확히한payload검증이미완료였으므로기존T2새test파일안에서요구기준별누락을보완한다. 파일은원래3개,단일제한수정과원명령검증후중단/보고하며기존준비1·2·계약3에교정4·5를누적한다. root의T6별도lane는영향없이보존한다.

### T2 교정6 — durable owner link와 legacy validation 경계

기존 준비1/2·계약3·T2 교정4/5는 유지하고 횟수를 초기화하지 않는다. R4/R5 전체 core 86개 중 84 통과·2 실패는 기존 generic SCHEDULED start 및 ledgerless scheduled engine 회귀다. 원인은 trigger를 ledger binding의 증거로 취급한 것이다. 기존 테스트는 변경하지 않는다. 교정6은 memory-only 명시 context와 저장된 attempt→lineage positive link로 경계를 분리하며 ADR-012 Proposed가 ADR-010을 대체한다. 실제 PR19 merge 및 T1 done은 유지한다. T2/T6은 공통 상태 in_progress이고 T6 완료는 별도 summary source lane의 사실이다. 이 문서 수정만으로 구현 또는 SQL migration 검증을 주장하지 않는다. parent/ADR 실제 commit f14b7501602bfe53aa3becb0482c1ebb3cca6116에 child parent_rev를 재고정했다. 19 brief 재조립 및 fresh readiness review 후 제품 교정을 위임한다. 각 T 5파일 상한, T3→T4 순차 ownership과 기존 전체 gate 및 실기기/실계정 pending은 유지한다.

### T2 교정7 — 보고된 pure owner 경계 미커버 보완

R6 actor와 root 전체core86개가 통과했지만 actor가 명시한 confirmation 및 manual owner 직접 검증이 미작성이다. 기존 승인T2의 owner/day/confirmation/manual 보존 기준 누락만 기존 신규test파일에서 보완한다. 새업무규칙이나 제품변경 없이 실제유효fixture의 확인 성공·owner누락거절·manual owner 거절과 bound context의 manual generic 보존을 단일부분수정/전체core검증한다. 원래prep1/2·계약3·R4/5/6에7을누적하고회차를초기화하지않으며 소유자의같은범위가역교정위임으로진행한다.

### T3 준비 교정1과 T20 추가 — 실제 파일 소유 일치

T3 첫실행 전 source로 확인한 v2 store capacity/reset/ledger 전체validation 연결은 기존원자성요구의구현연결이며5번째store파일로한정한다. 상세claim구현은T4에순차인계한다. 기존unsupported version2fixture는별도T20에서같은assertion을유지해version3으로교정한다. 각각5/1파일로병렬편집가능하며검증은T3source동결뒤같은head의전체양API Native/host로한번수행한다. T3/T20을실제통과전done으로표시하지않고T4는둘모두기다린다. 기존회차를보존하고이번T3prep1을누적한다.

### T3 준비 교정2 — 통합 층 표기와 실제 실행 증거 경계

실행자가 공통 Android 통합 층 SQLite/WorkManager/프로세스 병기를 T3의 신규 WorkManager 및 host kill test 의무로 읽어 편집 전 중단했다. T3 작업의 명시 대상과 named test/completion은 실제 DB staged migration·reopen·중단 fault 경계이며 새 worker/host 파일은 지정하지 않았다. 해당 의미를 명확히 하고 실제 SQLite fault 주입과 별도 실제 기존hostprocess 증거를 구분한다. 기존full양API/host 명령·기존process fixture는유지하며 새migrationphase의 kernelkill증거를 수행없이추정하지않는다. 새제품기능·gate축소없이 원래5파일내최초구현을이어가며 prep1뒤이번2를누적한다.

### T3 실행자 인계 — 미구현 산출물 보존

첫 T3 실행자는 준비교정1·2의 파일소유와검증층문제해소후에도 staged migration/recovery를 자신있게 완성하지못했다고 보고하고 재배정을 요청했다. 제품파일편집·빌드·Native수행은0이며 기존T2/T20 source는그대로다. 다른새Terra 실행자에게동일T3와현재조립brief를인계한다. 모델·세션교체로 기존공통prep회차및T3prep1·2를초기화하지않으며상태는in_progress다.

### T3 첫 구현 기여의 크기 제한

새실행자도제품편집없이기존active-only validator를장애로보고했으므로동일T3를재작성하지않고첫기여를기존store의 migration-source 전체generation 검증helper로한정한다. Metadata와이미명시meta를받는 streaming validator를재사용·분리하고 archived generation의id/destination/enrolled/categories를검증한다. archived automatic은저장되지않아검증용문맥값을과거설정사실이나저장값으로주장하지않는다. 실제active config/binding 및v1automatictrue차단은별도권위값으로검증한다. per-generation indexedstreaming과cap을유지하며normalUIread를매번전체generation검증으로바꾸지않는다. 이후같은task의schema/stagedpublish/실제test기여를순차연결한다. 이부분기여는T3완료나별도feature완료가아니며모든원래Nativegate를유지하고기존회차를초기화하지않는다.

### T3 사전 기여 컴파일 확인

전체generation validator·엄격v1/v2 schema maps·모든ledger/양방향link검증·capacity/reset연결을작성후source를동결해root가offline :app:assembleDebugAndroidTest를직접실행했다. auto-t3-preparatory-compile.log에서1m22s BUILD SUCCESSFUL이다. 이는아직v1fresh기본의준비기여컴파일이며stagedmigration/freshv2전환/실제새Native검증이나T3완료가아니다. 이어같은실행자의부분기여로durablepublish/wiring과실제migrationfixture를순차완성한다.

### T3 독립 원문 검토 교정3·4·5·6

원래prep1/2를보존한첫부분기여들을컴파일한뒤새staging기여를동결해독립Terra원문검토를받았다. 새기여는아직build/test전이며검토에서Java Os class를Kotlin값으로사용한컴파일문제(3), 준비중durable owner부재(4), resume manifest에서device/inode누락(5), 종료attempt와budget시각관계누락(6)을확인했다. source snapshot/hash와 auto-t3-migration-safety-review.md를보존했다. 같은5파일범위에서각근거를가진국소교정을한번의제한수정으로적용하며새schema/재시도권리/범위확장은없다. 기본2회이후의새근거가있는기존V1가역교정은소유자자동위임(AGENTS.md)에따르며독립재검토·실제양API전체gate는유지한다. 미완전·불명prepared bytes는버리거나이전source에새초기권리를주지않고owner와원본을보존해BLOCKED한다. 종료중ACTIVE waiting은최초finish updatedAt와finishedAt의관계및due를검증하고terminal수동retirement는후속updatedAt을허용하되건강성공outcome을추론하지않는다.

### T20 보정1 — 기존 손상 fixture의 schema 독립 SQL

실제 source검사에서 기존attempt raw INSERT12값이v2의13열과맞지않는것을확인했다. T20에기존SqliteSyncStateStoreInstrumentedTest한파일을추가해원래12열명만명시하고동일bad attempt값/기존assertion을보존한다. T20누적보정1이며T3회차와독립적으로기록한다. 제품동작·회귀기대값·host를변경하지않고root가통합source의테스트APK와전체양API/host를검증한다.

### T3 교정7 — 알려진 준비 중단의 안전한 재개

R3~6 source교정후독립재검토에서Os static/identity/finish일관성은source수준해소됐으나owner-only와커밋된transformed checkpoint가정상적으로도영구BLOCKED되는것을확인했다(auto-t3-migration-safety-rereview.md). 유효owner·그대로인원본·아직stage없음은known state로copy부터재개한다. 변환완료checkpoint전에는별도durable 변환proof를기록해같은inode/content를확인한뒤v2검증과ready승격만재개한다. durable proof기록전의partial·불명·불일치바이트는기존preserve/BLOCKED기준을유지한다. 원래prep1/2와R3/4/5/6에이번7을누적하며새권리/schema/domain필드없이다음부분교정과실제새SQLitefixture검증을진행한다.

### T3 교정8·9 — 실제 compile 실패와 보고된 검증 누락

첫현재staging/test source APKcompile이SyncRetryBudgetMigration.kt:102에서core다른모듈public nullable finishedAt smartcast불가로실패했다(auto-t3-verify.log). actor는즉시중단했고추가수정/재실행없다. 교정8은해당immutable local val로snapshot후null분기·비교하는Kotlin모듈경계수정이다. 같은보고의미커버인FKON budget reset·link시간corruption/정상terminalretirement·changedbytes/legacyspill·직접gap·실제v1dirtyjournal은원래승인T3기준누락이므로새fixture에서교정9로보완한다. 새SQLschema/권리/기존기대값변경없고T3원래prep1/2·R3~7을보존한누적8/9로기록하며단일제한수정/원명령검증한다. 실제kernelkill미수행은분리한다.

### T3 교정10 — 실제 Android fixture의 locale metadata 오염 차단

root가현재91433b6 전체로컬core/app/APK/lint를2m29s 통과시킨뒤Native실행전새fixture의raw openOrCreateDatabase/openDatabase에NO_LOCALIZED_COLLATORS누락을발견했다. AOSP공식SQLiteConnection.java HTTP200 source476/496줄로기본writableopen이android_metadata table을추가함을확인했다. 이는strictv1/v2 map에없는테이블로처음부터fixture를unsupported로만들며제품schema를완화할이유가아니다. 새MigrationInstrumentedTest의모든rawopen에production과같은NO_LOCALIZED_COLLATORS 및초기DELETE/FULL설정을적용하는test-only교정10이다. 기존테스트/스키마/map/assertion은보존한다. 진행중43550413104는오염fixture의불필요한긴검증을피해취소요청하고PASS증거로쓰지않는다. 새sourcehead로전체양API/host를재실행한다. 원래회차1~9를보존한다. 공식source는공개AOSP참조이며실기기수행주장이아니다.

### T21 준비 — 새기능이아닌기존진단 allowlist 확장

제품T3와독립인별도worktree에서기존정적diagnostic패턴을세개의정적class로확장한다. 현재full0cf03a9run43550859281는유지하며source/gate불변검증뒤새fixture선택실행을병렬화한다. 별도스펙/SLA나fullsharding을도입하지않고기존자동검증경로의가역기술보조만수행한다. 완료/PASS층구분과PR최종currenthead전체검증은유지한다.
