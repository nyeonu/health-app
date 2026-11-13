---
plan_id: workmanager-starter
title: 독립 WorkManager 의존성 감사와 합성 starter 준비
kind: single
status: in_progress
author: Codex
created: 2026-11-13
spec: ../spec.md
spec_rev: 97c01b1992ad7935e6fa8f1f8b371a3963457daa
branch: feat/v1-workmanager-starter
baseline: b35c11c8b4651cf9b12b004a7fc4ae3d52a4d3ee
tasks:
  - {id: T1, title: WorkManager 의존성 초기 감사, depends_on: [], status: done, tier: standard}
  - {id: T2, title: 실제 test WorkManager 합성 starter, depends_on: [T1], status: in_progress, tier: standard}
---

# 독립 WorkManager 기반 준비

## 1. 선택한 방식과 이유

승인된 [V1 스펙](../spec.md)과 [상위 계획](../plan.md)의 automation-ui 최초 기반 작업만 분리하는 **검토용 draft**다. 소유자의 기존 V1 자동승인·병렬화 지시 안에서 root가 이 구체 계획을 검토한 뒤 실행한다. 이번 작성은 문서만 변경하며 제품/테스트 편집, 빌드, 네트워크, GitHub 작업을 수행하지 않는다.

`/workspace/health-app-automation-preparation/docs/2026-11-05-v1-completion/automation-ui/plan.md`의 T1 전체를 먼저 실행하지 않는다. 해당 draft에는 production `HealthApplication`, runtime 소유권과 main manifest 수정이 섞여 있다. 여기서는 의존성 도입/감사를 T1, 실제 test-only starter를 T2로 나누어 각각 최대 5파일로 제한한다. PR17 Native와 SAF 작업의 API·소유 파일을 사용하지 않아 별도 worktree에서 준비할 수 있다. merge 전에는 선행 reviewed merge head를 대조해 pin과 충돌을 다시 확인한다.

기존 [ADR-008](../../decisions/ADR-008-2026-11-05-2026-11-05-v1-completion-workmanager-runtime.md)은 **Proposed**로 유지한다. WorkManager 2.11.0 선택은 그대로이며 새 ADR을 만들거나 Accepted로 바꾸지 않는다. Application/initializer/diagnostics 최종 배선은 automation-ui 후속 기능 단계다. 이 기반 PR만으로 ADR의 전체 배선을 완료했다고 보고하지 않는다.

## 2. 공통 설계

### 실제 확인한 현재 상태

| 경로 | b35c11c에서 확인한 내용 |
|---|---|
| `android/app/build.gradle.kts` | WorkManager 의존성 없음. minSdk28/compileSdk36/targetSdk36, JVM17, coroutines1.10.2, AndroidJUnitRunner, ext:junit1.2.1/runner1.6.2 |
| `android/settings.gradle.kts` | AGP8.13.2/Kotlin2.2.20; coreOnly 외 app 포함 |
| `android/app/src/main/AndroidManifest.xml` | application android:name 없음, allowBackup=false, cleartext=false. WorkManager/Startup 명시 선언 없음 |
| `android/app/src/main/java/com/ny/my/health/SyncViewModel.kt` | platform Application을 받으며 WorkManager 호출 없음; custom Application 클래스 없음 |
| `.github/workflows/android-emulator-tests.yml` | 실제 emulator matrix API35/36, debug와 androidTest APK build, 기존 full validation helper 호출 |
| `android/ci/run-emulator-validation.sh` | critical 4개 class 뒤 나머지 normal discovery와 history/sync-state host 검증. 새 starter는 notClass 제외 목록에 없으므로 normal discovery 대상 |
| 상위 계획/ADR-008 | 공식 2.11.0 POM/AAR 조사상 minSdk23/minCompileSdk35/minAGP8.6.0/Kotlin2.1.20/coroutines1.9.0. 이는 실제 앱 resolved graph 검증을 대신하지 않음 |

T1 실제 공식2.11.0 source/resolvedAAR javap로 아래helper/driver/executor/cleanup계약을확인했다. raw inventory는 dependency-audit.json 및 tooling/wm-starter-t1/javap.txt에있다. actualstarter 기동은T2 API35/36에서확인하며아직pending이다.

### EarlyStarterContracts

```text
// EarlyStarterContracts — 기존 독립 기반 계약
dependencies: implementation androidx.work:work-runtime:2.11.0
              androidTestImplementation androidx.work:work-testing:2.11.0
production Application/main manifest/initialization 코드 변경 없음.
테스트 파일 하나 안에 synthetic CoroutineWorker + test-only WorkerFactory + fixture를 둔다.
worker의 입력/출력은 고정 합성 marker만; injected in-memory counter로 정확히 1회 실행 확인.
WorkManagerTestInitHelper.initializeTestWorkManager의 실제 2.11.0 지원 경로로 test manager 설치.
WorkManagerTestInitHelper.getTestDriver로 실제 TestDriver를 얻고 null이면 실패.
실제 WorkManager DB/상태를 조회하고 test driver로 initial delay를 해제하여 SUCCEEDED까지 확인.
실제 네트워크 상태를 가정하거나 health/Drive/SAF/session/store/account/permission API 호출 금지.
CoroutineWorker에는 지원되는 비동기 executor 배선을 사용하며 SynchronousExecutor와
runBlocking의 main-thread 교착을 피한다. 정확한 배선은 실제 source 확인 후 선택·기록.
test helper DB 종료/worker cancel/executor 종료는 지원 API로 finally에서 모두 시도.
확인된 실제 API: Configuration.Builder.setWorkerFactory(factory).setExecutor(workerExecutor).setTaskExecutor(taskExecutor).
initializeTestWorkManager(context, configuration, WorkManagerTestInitHelper.ExecutorsMode.PRESERVE_EXECUTORS).
getTestDriver(context)는 nullable이므로 non-null 단언; driver.setInitialDelayMet(request.id).
서로다른worker/task ExecutorService를쓰며task는Roomqueryexecutor이기도하므로2threads이상pool필수.
Instrumentation thread의 runBlocking/withTimeout에서getWorkInfoByIdFlow(id).first{terminal}로관찰하며mainthread차단금지.
finally는cancelAllWork().result boundedawait→closeWorkDatabase()→두executor shutdown 모두시도/원래실패보존.
closeWorkDatabase는testscope/DB를닫지만defaultdelegate를복원하지않는다. 다음testhelper초기화가delegate교체하며현재production에는WM호출이없다.
생산Application/manifest초기화코드변경없이test-only WorkerFactory가합성worker만생성한다.
테스트 전체에 timeout; arbitrary sleep/polling 성공 판정, skip, mock TestDriver 금지.
```

**의존성 부수효과 경계:** work-runtime 자체의 manifest가 default Startup initializer, scheduler service/receiver/permission을 가져올 수 있다. 따라서 dependency 추가를 “앱 초기화 영향 0”이라고 부르지 않는다. T1은 실제 debug/release merger diff로 모두 기록한다. 초기화는 library 기본값 그대로 두며 앱은 work를 enqueue하지 않는다. ADR-008의 custom Configuration.Provider/initializer 제거/diagnostics 비활성화는 후속 단계로 남는다. 이 기본 initializer의 존재 자체가 승인된 조기 추출 제약과 양립하지 않거나 test helper를 설치하려면 production Application/manifest 변경이 필요하면 **blocked**로 보고한다. 앱 초기화를 먼저 만들거나 숨은 test runner 변경으로 우회하지 않는다.

## 3. 추적성

| 스펙 기준/전제 | 테스트·검증 (출처/층) | 작업 및 잔여 기준 |
|---|---|---|
| 전제 환경: WorkManager dependency·worker·test 기반은 없음, 도입 필요; SAF fake/test provider 기반은 없음, 도입 필요. | `Real WorkManager test context initializes and completes a CoroutineWorker once` (기타: 새 기반 기동 전제/Android 통합) | T2; SAF는 별도 starter 소유 |
| 구현 전 공식 WorkManager release notes와 minSdk28/compile36/Kotlin·coroutines 호환성을 확인하고 resolved dependency audit를 수행한다. | 네 resolved graph OSV, AAR metadata, debug/release manifest diff (기타: 스펙6절 도입 제약/정적·실제 build) | T1 |
| 실제 store 재열기·손상/미지원 상태·실행 취소·동시 admission·local TLS·합성 provider·worker 제약/권한·UI 회귀와 APK/lint 검증을 수행하고 각 PR의 독립 최신 head 검토를 마친 개발 완료와 Galaxy/계정/provider/Doze/배터리/TalkBack 수용 대기를 별도로 보고한다. | 위 starter의 실제 TestDriver delay 해제 전 미실행/해제 후 정확히1회 SUCCEEDED, 기존 전체 회귀 (②경계·③실패·④상태/Android 통합) | T2는 기반만 증명; 제품 worker/권한/제약 및 전체 V1 수용은 후속 automation-ui pending |
| app-private 상태/backup 제외와 로그/진단 allowlist를 검증하여 raw 건강 데이터·CSV·token·계정/기기/문서 식별자·URI·provider 본문을 공개 로그·fixture·CI artifact에 포함하지 않는다. | test-only worker의 합성 marker/counter와 생산 APK 부재, allowBackup=false 및 merger diff 검토 (①·③/정적·APK 검사) | T1/T2의 관련 부분만. 최종 전체 privacy 검증은 후속 단계 |

기존 과거 결함을 꾸며내지 않는다(⑤). 오류는 helper/driver 부재·worker timeout·unexpected construction·중복 실행이 starter 실패로 나타나는 경계만 검증한다. 실제 OS network/battery/Doze나 production retry/permission/account 분기를 이번 starter에서 검증했다고 주장하지 않는다.

## 4. 작업

### T1: WorkManager 의존성 초기 감사

- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `android/app/build.gradle.kts` | 수정 | runtime/testing 2.11.0 두 좌표만 추가 |
  | `docs/2026-11-05-v1-completion/workmanager-starter/dependency-audit.json` | 신규 | 네 실제 graph 전체 좌표·OSV·metadata·manifest diff·호환성·한계 |

- **인터페이스**: §2 `EarlyStarterContracts` 참조.
- **결정 로직**: 없음.
- **작성할 테스트**: 새 제품 테스트 없음. 실제 resolved graph/build/metadata/manifest 검사로 dependency 통합 계약 검증. 최소 기동 테스트는 별도 T2.
- **완료 기준**:
  - 아래 네 configuration의 실제 selected Maven ModuleComponentIdentifier 전체를 BOM·variant·test transitive 포함 추출하여 OSV querybatch 감사. requested dependency 목록이나 dependency tree 문자열의 일부만 감사하지 않는다.
  - 감사 JSON에 source head/time/commands/configuration별 좌표/OSV 응답·오류/취약점 severity·도달성·disposition·merged manifest 및 AAR metadata 결과를 기록한다. 취약점 조회 오류/미해결 high·critical은 기능 진행을 차단한다.
  - debug/release merged manifest 및 merger report의 initializer/diagnostics/service/receiver/permission/process/exported/backup 변경을 baseline과 비교한다. SDK·compiler·coroutines upgrade, ktx/multiprocess 추가 없음.
  - 2.11.0 실제 source/javap로 helper/driver/cleanup/worker factory 계약과 기존 default initializer와 test helper의 교체 경계를 확인·기록한다. production 초기화 변경 필요 시 blocked.
- **검증 방법** (`android/`에서; 실행 단계만):

  ```bash
  ./gradlew :app:dependencies --configuration releaseRuntimeClasspath
  ./gradlew :app:dependencies --configuration debugRuntimeClasspath
  ./gradlew :app:dependencies --configuration debugUnitTestRuntimeClasspath
  ./gradlew :app:dependencies --configuration debugAndroidTestRuntimeClasspath
  ./gradlew :app:dependencyInsight --configuration releaseRuntimeClasspath --dependency androidx.work
  ./gradlew :app:checkDebugAarMetadata :app:checkReleaseAarMetadata :app:processDebugMainManifest :app:processReleaseMainManifest :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest
  ```

- **주의사항**: §2의 default initializer/graph는 추정이며 실제 source/manifest로 먼저 확인한다. T1은 의존성 도입만 수행하고 production initialization 코드를 변경하지 않는다. 네 graph의 Kotlin/coroutines/guava/work selected version이 다르면 각 configuration의 dependencyInsight로 기록한다. 공식 Maven 조사 성공을 actual app audit 성공으로 대체하지 않는다. 컴파일/감사 실패 후 blocked로 보고하고 집계된 복구 지시를 기다린다. test skip·assertion 완화 금지.

### T2: 실제 test WorkManager 합성 starter

- **depends_on**: T1 완료 후 시작.
- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `android/app/src/androidTest/java/com/ny/my/health/WorkManagerStarterInstrumentedTest.kt` | 신규 | nested synthetic worker/factory, 실제 helper/driver와 bounded 기동 검증 |
  | `docs/2026-11-05-v1-completion/workmanager-starter/execution-report.md` | 신규 | local build와 실제 API35/36 CI class/method/count/head 결과 및 한계 |

- **인터페이스**: §2 `EarlyStarterContracts` 참조. T1에서 확인한 실제 지원 API만 사용한다.
- **결정 로직**: 없음.
- **호출 순서**:

  ```text
  1. 실제 test helper로 test factory를 가진 WorkManager 설치 → 실패하면 테스트 실패.
  2. 실제 TestDriver와 delayed one-time synthetic request 생성 → driver 부재면 실패.
  3. delay 해제 전 nonterminal 상태와 counter0 확인 → 실제 TestDriver로 delay 해제.
  4. bounded terminal await 후 SUCCEEDED/고정 output marker/counter1 확인 → 실패나 timeout은 실패.
  5. finally에서 test work 취소·DB/fixture executor cleanup 모두 시도 → 원래 실패 보존, cleanup 실패도 보고.
  ```

- **작성할 테스트**:
  - 신규 `Real WorkManager test context initializes and completes a CoroutineWorker once`: 실제 Android context/DB/helper/driver/factory/CoroutineWorker 사용. delay 해제 전 실행0, 해제 후 성공1과 marker 확인. fake health/계정 IO도 넣지 않는다.
  - 기존 JVM·UI·history/sync-state host 회귀 재사용. starter를 제외/skip하지 않는다.
- **완료 기준**:
  - 실제 CI의 현재 source head에서 API35와 API36 둘 다 starter class/method 실행 및 failure/error/skip0 증거를 확보한다. local compile만으로 T2/원래 automation T1을 done으로 두지 않는다.
  - 기존 full CI 기본 gate는 그대로 실행되며 테스트 fixture는 test APK에만 있다. production Application/manifest/runtime initializer 설정은 변경되지 않는다.
  - 원래 automation T1 전체를 완료 표시하지 않는다. 이 PR로 완료한 것은 dependency audit와 test-only starter뿐이며 Application/initializer/diagnostics/factory production 연결은 후속 작업으로 재분해한다.
- **검증 방법** (`android/`에서):

  ```bash
  ./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
  ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.ny.my.health.WorkManagerStarterInstrumentedTest
  ```

- **주의사항**: 현재 workflow의 API35/36 full discovery가 starter를 포함하므로 이번 계획에서 workflow/helper를 고치지 않는다(SAF 병렬 파일과 충돌 방지). focused CI가 필요하면 별도 범위가 확인된 orchestration 작업으로 분리하며 full gate 증거로 쓰지 않는다. test-only factory는 합성 worker만 인식하고 unexpected class construction을 성공으로 바꾸지 않는다. 최초 구현 보정은 이 test 파일 내부의 확인된 helper/executor/factory/cleanup 배선만 허용한다. production Application 변경이 필요하거나 기동/컴파일/실행 실패 시 우회하지 말고 blocked로 보고한다. global singleton 교체가 다른 test class에 영향을 주지 않도록 지원 cleanup 계약을 T1에서 확인한다.

## 5. 전체 검증과 통합

T1 초기 audit PASS → T2 local build + actual CI API35/36 PASS → current-head 독립 검토와 차단 지적 수정/재검토 순서다. 전체 제품 기능 실행은 이 초기 audit와 실제 starter 통과 전 시작하지 않는다. 초기 감사 후 최종 automation 통합 head에서 네 graph/OSV/manifest/AAR 재감사는 별도로 유지한다.

PR17/SAF와 동시에 준비해도 early branch를 최종 제품 기반이라고 간주하지 않는다. 선행 reviewed merge head에서 충돌·pin·manifest·dependency graph·test discovery를 재확인하고 필요한 감사를 반복한다. parent 통합 시 work_plans/추적성에 이 독립 starter를 연결하고 automation-ui T1을 production initialization 후속 작업과 중복 없이 재분해한다. 지금 parent를 바꿔 실행 범위를 조용히 넓히지 않는다. root의 concrete-plan 검토 전 실행하지 않는다.

Galaxy/실계정/실제 HC background grant/Doze/reboot/network/battery/TalkBack/provider 수용은 pending이며 이 starter가 대신하지 않는다.

## 6. 롤백

기반 PR을 revert한다. dependency 두 줄과 test-only 파일만 제거하며 사용자 설정·coverage·account·health data migration은 없다. generated WorkManager private DB가 생길 수 있으므로 이를 기존 health/history DB로 간주해 삭제하거나 성공 증거로 사용하지 않는다.

## 복구 기록

| 작업 | 회차/2 | 실패 증거·원인 | 새 근거·수정 가설 | 계획 보정 전→후·유지 기준 | 수정 파일·보존 대상 | 검증 명령·결과 | 실행자·다음 조치 |
|---|---|---|---|---|---|---|---|

## 환경 준비 기록

| 원인·근거 | 준비 조치 | 조치 사용/1 | 확인 명령·결과 | 확인 사용/1 | 다음 조치 |
|---|---|---|---|---|---|

## 구체 계획 자동승인 및 조기 분리 근거

2026-11-13 root는 위2개작업/각2파일과초기감사·실제starter gate를직접검토했다. 소유자의기존V1전체자동승인과새병렬지시에따라독립single계획으로승인한다. 같은spec_rev/ADR008선택을유지하며production worker/Application/permission/자동설정변경은제외한다. 부모단계의실행순서를몰래완료처리하지않고자동화도입전검증기반만앞당긴다. 기본initializer의실제manifest부수효과를감사하여공개하고generation/privatehealth이력과무관한합성test만실행한다. root현재검토head b35c11c, 실제API35/36·최종통합·PR독립검토전완료주장금지.

### 계획 표기 복구1/2

assemble_brief의명시참조 EarlyStarterContracts가heading만으로는해소되지않았다. 실제존재하는동일코드블록에조립기지원header주석만추가했다. 계약/API/파일/검증내용불변. 재조립에서해소실패0및Shared design원문포함확인후T1배정한다.

### T1 실제 완료와 검증기 보정1

root는4실제graph/155OSV응답/0vulnerability·error와13Gradle rawlog SHA256을직접대조했다. baseline19초/integration3분8초119tasks PASS, 실제AAR source/javap21 signatures확인. 기본initializer/component/normalpermissions 및DUMP-protected DiagnosticsReceiver의privileged enqueue부수효과를감사JSON에기록했고backup/process변경없다. OSV초기자동검토거부는공개Maven좌표만전송한다는allowlist증거로한정한후승인된조회로완료됐다. 읽기전용마지막검증기의임의commands길이16예상이실제15(13Gradle+OSV+javap)와달랐다. 임의길이대신필수명령집합/성공증거검증으로교정1회했으며source/build재시도없다. T1done, T2actualNative완료전전체done아님.
