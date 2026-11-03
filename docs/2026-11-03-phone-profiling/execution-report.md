## 실행 결과

- 계획: [승인된 실행 계획](plan.md). 이 보고는 T1–T5의 범위별 검증 증거를 대조하며 독립 스펙 적합성11/11 PASS 및 f11f988의 별도 코드·보안·성능 리뷰 모두 APPROVE다.
- 완료한 작업: T1–T5 / 5개. T5는 문서 4개만 작성·수정하며 앱 테스트를 새로 만들거나 앱을 재빌드하지 않았다. 자동 복구 사용은 아래에 구분한다.
- 브리프: 다음은 생성 당시 첫 줄 통계 원문과 작업 결과다. `테스트 0`은 조립기 통계이며 실제 테스트 미작성/미실행을 뜻하지 않는다.
  - `<!-- brief T1 inherited · 189줄 · 11550자 · 추정 토큰 3850 · Shared design 참조 2(명시 2, 해소 실패 0) · 변경 파일 3 · 테스트 0 · 작업 블록 23줄 · Shared design 36줄 · Goal 5줄 -->` 결과(재시도 1회 후 검증 PASS) · 계획 이탈(없음)
  - `<!-- brief T2 inherited · 190줄 · 11431자 · 추정 토큰 3810 · Shared design 참조 2(명시 2, 해소 실패 0) · 변경 파일 2 · 테스트 0 · 작업 블록 24줄 · Shared design 36줄 · Goal 5줄 -->` 결과(재시도 2회 후 검증 PASS) · 계획 이탈(Android 테스트 method명 camelCase 보정; SDK·기대값 유지)
  - `<!-- brief T3 inherited · 192줄 · 11570자 · 추정 토큰 3856 · Shared design 참조 2(명시 2, 해소 실패 0) · 변경 파일 2 · 테스트 0 · 작업 블록 22줄 · Shared design 40줄 · Goal 5줄 -->` 결과(재시도 1회 후 검증 PASS) · 계획 이탈(Android 테스트 method명 camelCase 보정; SDK·기대값 유지)
  - `<!-- brief T4 inherited · 230줄 · 13607자 · 추정 토큰 4535 · Shared design 참조 6(명시 6, 해소 실패 0) · 변경 파일 5 · 테스트 0 · 작업 블록 35줄 · Shared design 65줄 · Goal 5줄 -->` 결과(재시도 1회 후 검증 PASS) · 계획 이탈(Android 테스트 method명 camelCase 보정; SDK·기대값 유지)
  - `<!-- brief T5 inherited · 146줄 · 8756자 · 추정 토큰 2918 · Shared design 참조 0(명시 0, 해소 실패 0) · 변경 파일 4 · 테스트 0 · 작업 블록 18줄 · Shared design 0줄 · Goal 5줄 -->` 결과(재시도 1회 문서 한정 보정 후 검증 PASS) · 계획 이탈(없음)
- 바뀐 파일: T1 `PhoneProfilingSession.kt`, `PhoneProfilingSessionTest.kt`, `ManualSyncProfiling.kt`; T2 `PhoneProfilingClipboard.kt`, `PhoneProfilingClipboardInstrumentedTest.kt`; T3 `PhoneProfilingSection.kt`, `PhoneProfilingScreenInstrumentedTest.kt`; T4 `SyncViewModel.kt`, `MainActivity.kt`, `HealthHomeScreen.kt`, `HealthAppInstrumentedTest.kt`, `HealthHomeScreenInstrumentedTest.kt`; T5 본 문서, `device-validation.md`, `../2026-11-02-sync-profiling/device-profiling.md`, `../backlog/sync-performance.md`.
- 판정: 전체 검증1·2·3을 완료했다. 독립 스펙 적합성11/11 PASS, f11f988의 별도 코드·보안·성능 리뷰 모두 APPROVE이며 Critical/Important/Suggestion 지적0이다. 실제 기기 수집·전달은 미확인이다. 최종 문서 head 재검토와 병합 처분은 PR 코멘트로 확인하고 이 결과를 실기기 성능 입증으로 확대하지 않는다.
- 자동 복구: T1 1/2 — 파일 생성 cwd 오류 부분 복구. 신규 테스트 작성 전 기존 6개 통과는 완료 근거에서 제외하고 최종 신규 9개+기존 6개 통과를 사용했다. T2 2/2 — Android 공백 method명 DEX 오류 보정 1회, 클립보드 테스트의 RESUMED/창 포커스 전제 보강 1회. T3 1/2·T4 1/2 — 같은 DEX 제약에 대한 사전 이름 보정. SDK·기대값·권한·의존성·승인 범위는 유지했다. T5 1/2 — 실제 diff와 보고서의 파일 목록 불일치를 문서 한정으로 보정했다. 변경하지 않은 workflow를 T4 목록에서 빼고 실제 변경된 `HealthHomeScreenInstrumentedTest.kt`를 변경 파일·테스트 파일 목록에 포함했다. 이 fixture는 콜백 연결만 바뀌었으며 새 테스트나 개수 추가는 없다. 전체 검증3의 독립 검토 대기도 판정에 명시했다. 실제 diff 목록 대조와 `git diff --check` PASS로 재검증했다. 참조 해소 실패·상한 경고와 참조 처리 보정은 없었다. 조립기 테스트 수 0과 실제 테스트 수의 차이는 위와 같이 명시한다. 위에 기록한 복구 외 추가 복구는 없으며 실패 시 실행자가 임의 수리하지 않는 원칙을 유지한다.

제품 구현 소스는 `fc47a011f23d0a4262b4b23025acbed073a2dcba`, 최종 테스트 보강 소스는 `4a061cb7eb338f86d5df6009847dd7ff08ed8d22`다. 이후 T5는 문서만 바꾼다. 공개 문서에는 집계와 고정 테스트명만 기록하며 원시 로그·화면·개인 시각·건강 값·식별자를 옮기지 않는다.

## 검증

| 검증 항목 | 실행한 명령 또는 확인 대상 | 결과 |
|---|---|---|
| T1 범위 검증 | `PhoneProfilingSessionTest`·`ManualSyncProfilingTest` 실행 XML | 신규 9개+기존 6개=15개 PASS, 실패/오류/skip 0. 최종 전체 XML에서도 정확한 이름 확인 |
| 전체 검증 1: JVM·APK·lint | `cd android` 후 `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest` | T4 실행자 및 오케스트레이터 PASS; T5 뒤 root 전체 명령37초 재검증 PASS. 실제 XML core41/app68=109개, 실패/오류/skip 0. lint 오류 0, 기존 DataExtractionRules/MissingApplicationIcon 경고 2개 |
| T2·T3 선행 Android CI | API 35 CI 41672143507 (`bc044e8`), 41675121121 (`adf71a7`) | 각각 Android49/54개+host20개 PASS. 최종 head 검증과 구분 |
| 이전 통합 CI 실패 | [CI 41678564632](https://github.com/nyeonu/health-app/actions/runs/41678564632), `fc47a01` | Android55개 중 클립보드 고정 label 단언 1개 FAIL, 오류/skip 0. host 단계 미실행. 이 실패 기록을 최종 PASS로 덮지 않음 |
| 전체 검증 2: 최종 Android·실제 복구 | [CI 41681123691](https://github.com/nyeonu/health-app/actions/runs/41681123691), `4a061cb`; `cd android` 후 `./gradlew --no-daemon :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.notClass=com.ny.my.health.HistoryProcessRecoveryInstrumentedTest`; 저장소 루트 `bash android/ci/verify-history-process-recovery.sh` | Android55개 PASS, 실패/오류/skip 0. 실제 프로세스 복구8·시스템 설정4·정리8=host20 PASS. killed hold를 통과로 센 건0, 합성 PNG60개 무결성 확인 |
| 추가 release 패키징 확인 | `cd android` 후 `./gradlew :app:assembleRelease`; `apkanalyzer manifest debuggable` | 오케스트레이터 PASS, unsigned APK debuggable=false. 설치/배포·실제 release UI 검증 아님. 비디버그 세션/UI는 아래 테스트로 구분 |
| 전체 검증 3: 경계·문서 정적 대조 | `PhoneProfilingSession`, `PhoneProfilingSection`, `PhoneProfilingClipboard`, `SyncViewModel`, `MainActivity`와 기기 절차 대조 | 최근1건 메모리·새 시도/무효화·고정 안전 형식·명시적 복사 연결·새 영속/자동 전송 경로 없음 확인. 실제 Galaxy 클립보드 수명 보장 아님 |
| 독립 스펙 적합성 | 별도 세션의 자체 JVM109개·debug/release build 재실행, 동일 Android 소스의 원본 CI55/host20 파싱 | [e8191ef 검증11/11 PASS](https://github.com/nyeonu/health-app/pull/12#issuecomment-6611111570). root 추적성 대조 완료; 기기/계정/복합 OS 실패 검증 한계 유지 |
| 별도 전체 품질·보안·성능 리뷰 | f11f988에서 전체 diff/실제 단언·원본 XML 검토; 코드 리뷰 JVM 강제 재실행56초·빌드/lint71초; 보안 current source/feature history gitleaks8.30.1 및 동일 Maven graph/OSV 원본 대조 | [코드](https://github.com/nyeonu/health-app/pull/12#issuecomment-6611232088)·[보안](https://github.com/nyeonu/health-app/pull/12#issuecomment-6611203474)·[성능](https://github.com/nyeonu/health-app/pull/12#issuecomment-6611206093) 모두 APPROVE, 지적0. 실제 UI/OS/기기 성능·계정 한계 유지 |
| T5 문서 검증 | 저장소 루트 `git diff --check` | PASS |

최종 CI 원본은 저장소 밖 `/workspace/tooling/pr12-ci-evidence-final`에 있다. T5는 실제 Android 실행 XML과 호스트 집계 결과를 읽어 55/0/0/0과 복구8·설정4·정리8을 대조했다. 오케스트레이터는 artifact13254362071의 SHA-256도 검증했다. 로컬에는 에뮬레이터가 없으며 CI 검증을 로컬 실행이라고 쓰지 않는다. 클립보드 테스트는 bounded RESUMED/포커스 대기와 쓰기 직전 단언 보강 후 통과했다. 이전 실패의 OS 원인이 확정되었다고 단정하지 않는다. 빈 진단 Activity 경로와 합성 populated Compose/OS 쓰기를 검증했으며 실제 계정 OAuth 뒤 populated 앱 전체 흐름을 자동화했다고 주장하지 않는다.

## 테스트 대조

출처: ①성공 기준, ②경계/동등 분할, ③오류, ④상태/순서, ⑤과거 결함. 실제 이름은 소스와 실행 XML에서 대조했다. 파일명은 `android/app/src/test/java/com/ny/my/health/`(JVM), `android/app/src/androidTest/java/com/ny/my/health/`(Android)를 기준으로 적는다.

| 계획한 테스트 (출처/층) | 스펙 기준 | 실제 테스트 (파일 · 테스트 이름) | 결과 |
|---|---|---|---|
| 진단 없음과 수집 중에는 복사와 과거 측정 표시가 없다 (①④/JVM) | 빈 진단·수집 중 과거 값/복사 금지 | `PhoneProfilingSessionTest.kt` · 진단 없음과 수집 중에는 복사와 과거 측정 표시가 없다 | PASS |
| 미관측 미완료 실제 영과 작은 양수 시간은 서로 다르게 표시한다 (①②/JVM) | 미관측·미완료·실제0·작은 양수 구별 | `PhoneProfilingSessionTest.kt` · 미관측 미완료 실제 영과 작은 양수 시간은 서로 다르게 표시한다 | PASS |
| 단계별 표시와 복사 요약에는 고정 안전 정보만 있다 (①②③/JVM) | 안전 고정 단계·24행 요약 | `PhoneProfilingSessionTest.kt` · 단계별 표시와 복사 요약에는 고정 안전 정보만 있다 | PASS |
| 최근 한 건의 불변 요약만 유지하고 새 세션은 비어 있다 (①②④/JVM) | 최근1건 불변·새 세션 비어 있음 | `PhoneProfilingSessionTest.kt` · 최근 한 건의 불변 요약만 유지하고 새 세션은 비어 있다 | PASS |
| 새 시도와 무효화는 이전 요약과 늦은 완료를 폐기한다 (①④/JVM) | 새 시도/무효화 뒤 늦은 완료 폐기 | `PhoneProfilingSessionTest.kt` · 새 시도와 무효화는 이전 요약과 늦은 완료를 폐기한다 | PASS |
| 복사 실패는 안전한 안내만 남기고 명시적 재시도만 허용한다 (①③④/JVM) | 복사 실패 안내·명시적 재시도 | `PhoneProfilingSessionTest.kt` · 복사 실패는 안전한 안내만 남기고 명시적 재시도만 허용한다 | PASS |
| 복사 중 무효화 뒤 늦은 결과는 새 상태를 덮지 않는다 (①④/JVM) | 무효화 후 늦은 복사 결과 격리 | `PhoneProfilingSessionTest.kt` · 복사 중 무효화 뒤 늦은 결과는 새 상태를 덮지 않는다 | PASS |
| 비디버그에서는 진단과 복사 경로가 없다 (①④/JVM) | 비디버그 세션 차단 | `PhoneProfilingSessionTest.kt` · 비디버그에서는 진단과 복사 경로가 없다 | PASS |
| 종결 정상 실패 취소와 미완료를 업무 결과와 혼동하지 않는다 (①②③④/JVM) | 종결 상태와 업무 결과 분리 | `PhoneProfilingSessionTest.kt` · 종결 정상 실패 취소와 미완료를 업무 결과와 혼동하지 않는다 | PASS |
| opensDiagnosticsExplicitlyAndCallsCopyOnlyOnClick (①④/Compose) | 명시적으로 펼침·복사만 호출 | `PhoneProfilingScreenInstrumentedTest.kt` · `opensDiagnosticsExplicitlyAndCallsCopyOnlyOnClick` | PASS (API 35 합성) |
| missingAndCollectingDiagnosticsNeverShowPreviousMeasurementsOrEnableCopy (①④/Compose) | 빈 진단·수집 중 복사 비활성 | `PhoneProfilingScreenInstrumentedTest.kt` · `missingAndCollectingDiagnosticsNeverShowPreviousMeasurementsOrEnableCopy` | PASS (API 35 합성) |
| nonDebugDiagnosticsExposeNoViewOrCopyControls (①④/Compose) | 비디버그 UI 숨김 | `PhoneProfilingScreenInstrumentedTest.kt` · `nonDebugDiagnosticsExposeNoViewOrCopyControls` | PASS (API 35 합성) |
| restorationDoesNotRestoreDiagnosticPayload (①②④/Compose) | 복원 번들에 진단 내용 없음 | `PhoneProfilingScreenInstrumentedTest.kt` · `restorationDoesNotRestoreDiagnosticPayload` | PASS (API 35 합성) |
| diagnosticsExplainSafeSharingAndSeparateClipboardLifetime (①/Compose) | 공유 안내·별도 클립보드 수명·테마/글자 접근 | `PhoneProfilingScreenInstrumentedTest.kt` · `diagnosticsExplainSafeSharingAndSeparateClipboardLifetime` | PASS (API 35 합성) |
| explicitForegroundWritePlacesTheSameSafeSummaryOnSystemClipboard (①④/OS 통합) | 실제 전경 OS 클립보드 동일 안전 요약 쓰기 | `PhoneProfilingClipboardInstrumentedTest.kt` · `explicitForegroundWritePlacesTheSameSafeSummaryOnSystemClipboard` | PASS (API 35 합성) |
| openingEmptyDiagnosticsAndRecreationNeverStartConsentOrUpload (①③④⑤/Activity) | 빈 진단/재생성이 동의·업로드를 시작하지 않음 | `HealthAppInstrumentedTest.kt` · `openingEmptyDiagnosticsAndRecreationNeverStartConsentOrUpload` | PASS (API 35 합성) |
| 인증/계속 대기 분리 (①②/JVM, 기존 재사용) | 실행 구간과 대기 분리·중첩 합산 금지 | `ManualSyncProfilingTest.kt` · `Authorization and continuation waits never contribute to execution elapsed` | PASS |
| 고정 안전 요약 (①②③/JVM, 기존 재사용) | 민감 입력 없이 enum/숫자만 | `ManualSyncProfilingTest.kt` · `Safe profiling summary contains only fixed enums and numeric statistics` | PASS |
| 기존 coordinator/uploader/transport/reader/history 회귀 (①③④⑤/JVM·통합) | 순차 요청·동일 파일·private 검증·취소·부분 결과·이력 유지 | `SyncCoordinatorTest`, `PrivateDriveUploaderTest`, `GoogleDriveTransportTest`, `HealthConnectReaderTest`, `HistorySessionTest`, `SyncHistoryTest`와 나머지 기존 회귀. 전체109개 중 신규9개 제외100개 | PASS, 실제 계정/기기 검증과 별도 |
| 신규 영속/전송 경로 없음·안전 전달 절차 (①/정적·문서) | 메모리 수명·24행 확인 뒤 사용자 직접 전달 | T5 기기 절차와 소스 대조, 앱 신규 테스트 불필요 | 확인, 실제 전달 미실행 |

- 추적성 표 갱신: root는 독립 검증에서 사용한 기존 대기 분리·coordinator/uploader 테스트 이름과 명시적 복사/안내 관련 기존 신규 테스트 대응을 계획 표에 보완했고 실제 XML과 대조했다. Android method명은 minSdk28 DEX에서 공백을 허용하지 않아 위 camelCase 이름으로 대조했다. 한국어 JVM 이름은 유지했다. 계획 표의 갱신·최종 상태 정리는 오케스트레이터가 담당하며 T5는 계획 문서를 수정하지 않았다.
- skip·비활성화된 테스트: 실제 JVM/최종 Android XML 모두 0건. host의 의도적 killed hold는 성공 테스트로 집계하지 않았다.
- 신규·변경된 테스트 파일: T1 `PhoneProfilingSessionTest.kt`; T2 `PhoneProfilingClipboardInstrumentedTest.kt`; T3 `PhoneProfilingScreenInstrumentedTest.kt`; T4 `HealthAppInstrumentedTest.kt`, `HealthHomeScreenInstrumentedTest.kt`(기존 fixture의 콜백 연결만 변경, 신규 테스트·개수 추가 없음). T5 없음.
- 커버리지 증감: 저장소의 커버리지 수치 추적 근거 없음. 테스트 개수를 커버리지 비율로 쓰지 않는다.

## 남은 문제

- 실행하지 못한 검증: 실제 Galaxy 수집·복사·사용자 전달, 실제 기기/다른 OS의 클립보드 동작과 보관, TalkBack, 실제 Health Connect·OAuth·Google 네트워크 조건별 시간과 p50/p95. 기존 P0/실기기/계정 미확인은 유지한다. 원본 비교/D08의 기존 확인을 다시 요구하지 않는다. 상단 가림은 별도 backlog다. 독립 스펙 적합성과 전체 코드/보안/성능 검토는 완료했다. 최종 문서 head의 재검토·병합 처분은 PR 코멘트에 남긴다.
- 미커버 분기: 계획 밖 신규 분기 없음. 에뮬레이터 합성 테스트를 실제 OAuth 이후 전체 populated 흐름이나 모든 클립보드 플랫폼 정책의 입증으로 확대하지 않는다.
- 다시 판단할 조건: 제품 코드/고정 요약 계약·권한·의존성·저장/전송 경로 변경 또는 새 CI 실패 시 관련 검증·독립 검토를 갱신한다. 기기 측정이 도착해야 병목/최적화 판단이 가능하다. 단계·operation·request 중첩 시간은 합산하지 않는다. 이전 PR #9의 독립 PASS/APPROVE는 이번 변경에 승계하지 않는다.

## 다음 단계

별도 스펙·코드·보안·성능 검토를 완료했다. 최종 문서 상태만 반영한 head의 재검토·범위·지적과 심각도·실제 검증·한계·판정 및 병합 처분은 [PR #12 코멘트](https://github.com/nyeonu/health-app/pull/12)에 한국어로 기록한다. 이 문서의 완료 표기를 실제 기기 수용이나 성능 개선 주장으로 확대하지 않는다.

고정 서명의 새 디버그 APK를 받은 뒤 [기기 수집 절차](device-validation.md)를 수행하고 확인한 안전 요약만 사용자가 직접 전달한다. 실제 기기 자료가 도착해야 동일 조건 반복 측정과 병목·최적화 방식을 다음 범위로 판단할 수 있다.
