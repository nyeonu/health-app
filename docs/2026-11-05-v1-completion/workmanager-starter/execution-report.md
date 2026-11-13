# WorkManager 합성 starter 실행 기록

## 범위와 상태

- 작성 기준: `9cffa854cfa81e8d6ff450dc4efc7cc59956f2cf` 위의 T2 작업 트리. 최종 CI source head는 아직 확정하지 않았다.
- T2는 진행 중이다. 최초 AndroidTest 컴파일 실패에서 중지한 뒤 승인 recovery 1 (`4a4fd45`)의 두 파일 범위로만 수정했다. 로컬 컴파일과 JVM 회귀만으로 완료 처리하지 않는다.
- 원래 automation T1 전체도 완료하지 않는다. 이번 범위는 dependency audit와 test-only starter이며 Application/initializer/diagnostics/factory의 production 연결은 후속 작업이다.
- production Application, manifest, runner와 기존 full CI gate는 변경하지 않았다. fixture는 `androidTest`의 한 파일에만 있다.

## 검증할 실제 동작

- class: `com.ny.my.health.WorkManagerStarterInstrumentedTest`
- method: `realWorkManagerTestContextInitializesAndCompletesACoroutineWorkerOnce`
- 실제 Android context와 WorkManager 2.11.0 helper가 만드는 인메모리 DB를 사용한다. mock TestDriver나 worker 호출 대체를 사용하지 않는다.
- 실제 TestDriver가 있어야 하며 1일 initial delay를 해제하기 전 DB 상태 `ENQUEUED`와 실행 counter `0`을 확인한다. `setInitialDelayMet` 후 실제 `CoroutineWorker`가 `SUCCEEDED`, 고정 합성 output, 실행 counter `1`을 남겨야 한다.
- 서로 다른 두 개의 2-thread executor와 `PRESERVE_EXECUTORS`를 사용한다. instrumentation 스레드에서 `runBlocking`/`withTimeout`/`Flow.first`로 terminal 상태를 관찰하며 Android main thread는 차단하지 않는다. 테스트 전체 제한은 60초다.
- `finally`에서 작업 취소의 bounded await → helper DB 종료 → 두 executor 종료를 모두 시도한다. 최초 실패를 보존하고 정리 실패는 suppressed로 남긴다.
- factory는 합성 worker만 생성한다. 입력·출력은 고정 합성 marker이며 Health Connect·Drive·SAF·계정·세션·권한 API와 네트워크에는 접근하지 않는다.

## 로컬 검증

- 명령 (`android/`): `python /workspace/tooling/run-health-command.py ./gradlew --offline --no-daemon :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`
- 결과: **FAIL** — `:app:compileDebugAndroidTestKotlin FAILED`, `BUILD FAILED in 2m 24s`.
- 최초 결정 오류: `WorkManagerStarterInstrumentedTest.kt:64:42 Cannot access class 'ListenableFuture'. Check your module classpath for missing or conflicting dependencies.` 같은 오류가 65행과 93행에도 발생했다. `enqueue(...).result.get`, `getWorkInfoById(...).get`, `cancelAllWork().result.get`의 public API 타입을 컴파일할 수 없고 `get`/`state` 후속 오류가 발생했다.
- 확인된 부분 결과: `:core:test` 84개, `:app:testDebugUnitTest` 92개, 각 failure/error/skip `0` (JUnit XML 기준). `:app:assembleDebug`는 UP-TO-DATE였으며 AndroidTest APK 조립은 완료하지 못했다. lint 분석 태스크 일부는 수행했으나 최종 lint 성공은 주장하지 않는다.
- 원시 로그: `/workspace/tooling/wm-starter-t2.log` (로컬 실행 산출물, 저장소에 포함하지 않음).
- 원시 JVM XML 사본: `/workspace/tooling/wm-starter-t2-xml/core/`, `/workspace/tooling/wm-starter-t2-xml/app/` (저장소에 포함하지 않음).
- 차단 계약: 승인 brief의 bounded `cancelAllWork().result` await와 실제 WorkManager API를 이용한 구현이 compile classpath에서 `ListenableFuture`를 해석하지 못한다. 별도 recovery 지시 전 의존성·설정·지원 API 배선을 변경하지 않는다. 제안: orchestration에서 runtime 외 AndroidTest compile classpath의 Guava/ListenableFuture 해석을 진단한 뒤 수정 파일과 허용 API를 명시한 recovery를 위임한다.

### 승인 recovery 1

- 지시 기록: `4a4fd45`. 의존성·설정·audit graph는 변경하지 않는다. WorkManager 2.11.0의 실제 `Operation.java`에 있는 public `getState(): LiveData<State>`를 사용하며 inline `Operation.await()`로 future 접근 문제를 우회한다고 가정하지 않는다.
- enqueue/cancel ACK는 `Operation.state` observer를 실제 main 스레드에서 등록하고 instrumentation 스레드의 `CompletableDeferred`/`withTimeout`으로 10초 이내 terminal을 기다린다. 정확히 `Operation.State.SUCCESS` 클래스인지 확인하며 `FAILURE`는 고정된 안전한 실패 메시지만 남긴다.
- observer는 `finally`에서 실제 main 스레드로 제거한다. 원래 오류를 보존하며 제거 오류도 suppressed로 남긴다. delay 해제 전 DB 상태 조회도 10초 제한의 `getWorkInfoByIdFlow(...).first { it != null }`로 바꾼다. worker 결과·counter 기대값과 전체 cleanup 순서는 유지한다.
- 명령 (`android/`): `python /workspace/tooling/run-health-command.py ./gradlew --offline --no-daemon :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`
- 결과: **PASS** — `BUILD SUCCESSFUL in 1m`, `77 actionable tasks: 8 executed, 69 up-to-date`. `:app:lintDebug`, `:app:assembleDebug`, `:app:assembleDebugAndroidTest`가 모두 완료했다. 기존 core/app JVM 소스는 바뀌지 않아 이미 실제 통과한 176개 JVM 테스트는 중복 실행하지 않았다.
- 원시 로그: `/workspace/tooling/wm-starter-t2-recovery1.log`.
- 최종 lint XML: error `0`, warning `2` (`android/app/build/reports/lint-results-debug.xml`).

## 실제 CI 증거

| 실행 환경 | source head | class/method 실행 수 | failure/error/skip | 결과 |
|---|---|---|---|---|
| API 35 기존 full discovery | 미확보 | 미확인 | 미확인 | pending |
| API 36 기존 full discovery | 미확보 | 미확인 | 미확인 | pending |

두 API의 현재 source head에서 위 class/method 실행과 failure/error/skip `0`을 원시 CI XML 및 실행 링크로 확인해야 T2를 완료할 수 있다. 로컬 `connectedDebugAndroidTest`는 실행하지 않았다. CI 실행 및 증거 수집은 orchestration 후속 단계다.

## 한계와 미검증

- `closeWorkDatabase()`는 테스트 scope/DB를 닫지만 global delegate를 기본 인스턴스로 복원하지 않는다. 다음 helper 초기화가 delegate를 교체하며 현재 production에는 WorkManager 호출이 없다. 따라서 후속 production 연결이나 helper를 초기화하지 않는 WorkManager 테스트가 추가되면 이 경계를 다시 검토해야 한다. 지원되지 않는 delegate 복원 API는 사용하지 않았다.
- process death·재부팅·실제 OS scheduling·주기 작업·retry·production factory 연결은 이 합성 starter가 검증하지 않는다. 초기화/작업/정리 실패 경로의 별도 failure injection 테스트는 승인된 T2 테스트 범위에 없다.
- 실제 Galaxy·계정·provider·TalkBack·배터리·기존 P0 수용과 recoverable key backup 미확인은 그대로 남는다. 이 테스트는 실제 건강 데이터 접근 또는 업로드 성공의 증거가 아니다.

## 실제 첫 전체 Native와 fixture 보정

source d07284f/run43435616205는양API121/4fail/0skip이다. 새starter는양API PASS이나기존4HotFixture테스트의실제main-file변경준비가before==dirty로실패해host를실행하지못했다. 복구/원본보존동작에도달전실패이며근본원인은현재PRAGMA관측부재로미확정이다. test-only동일writer transaction안의cache1/spill1설정과안전한실효값관측으로제한교정했다. 기존4test body/96row/payload/실제changedmain·journalmagic·typedreadonly·모든byte보존단언은변경하지않았다. testAPK compile40초PASS는실제새NativePASS가아니며새양API전체gate를기다린다.
