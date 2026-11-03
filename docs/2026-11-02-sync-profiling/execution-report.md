## 실행 결과

- 계획: `docs/2026-11-02-sync-profiling/plan.md`
- 완료한 작업: T1–T7 자동 검증 완료, T8 문서 조립·정적 검증; 8개 작업의 최종 상태 확정은 오케스트레이터가 수행한다. 승격 없음.
- 브리프:
  - <!-- brief T1 inherited · 187줄 · 10342자 · 추정 토큰 3447 · Shared design 참조 1(명시 1, 해소 실패 0) · 변경 파일 2 · 테스트 0 · 작업 블록 27줄 · Shared design 30줄 · Goal 5줄 --> 결과(재시도 1회); 계획 이탈(기존 산문 계약을 선언 주석으로 이동·재조립)
  - <!-- brief T2 inherited · 205줄 · 11004자 · 추정 토큰 3668 · Shared design 참조 2(명시 2, 해소 실패 0) · 변경 파일 2 · 테스트 0 · 작업 블록 34줄 · Shared design 41줄 · Goal 5줄 --> 결과(재시도 1회, 개명 후 최종 확인 대기); 계획 이탈(검증 본문에 맞게 성공 전용 테스트 이름 정정)
  - <!-- brief T3 inherited · 209줄 · 11131자 · 추정 토큰 3710 · Shared design 참조 2(명시 2, 해소 실패 0) · 변경 파일 2 · 테스트 0 · 작업 블록 38줄 · Shared design 41줄 · Goal 5줄 --> 결과(1회차 PASS); 계획 이탈(없음)
  - <!-- brief T4 inherited · 206줄 · 11000자 · 추정 토큰 3666 · Shared design 참조 2(명시 2, 해소 실패 0) · 변경 파일 2 · 테스트 0 · 작업 블록 35줄 · Shared design 41줄 · Goal 5줄 --> 결과(재시도 1회); 계획 이탈(누락된 취소/중단 저장 실패 검증 보완)
  - <!-- brief T5 inherited · 221줄 · 12019자 · 추정 토큰 4006 · Shared design 참조 2(명시 2, 해소 실패 0) · 변경 파일 3 · 테스트 0 · 작업 블록 39줄 · Shared design 52줄 · Goal 5줄 --> 결과(재시도 1회); 계획 이탈(기존 산문 계약을 선언 주석으로 이동·재조립)
  - <!-- brief T6 inherited · 197줄 · 10710자 · 추정 토큰 3570 · Shared design 참조 2(명시 2, 해소 실패 0) · 변경 파일 1 · 테스트 0 · 작업 블록 26줄 · Shared design 41줄 · Goal 5줄 --> 결과(1회차 PASS); 계획 이탈(없음)
  - <!-- brief T7 inherited · 205줄 · 11317자 · 추정 토큰 3772 · Shared design 참조 3(명시 3, 해소 실패 0) · 변경 파일 2 · 테스트 0 · 작업 블록 28줄 · Shared design 47줄 · Goal 5줄 --> 결과(재시도 1회); 계획 이탈(기준선 출력 경로·HTTP 503 FAILED 통계 명시 보정)
  - <!-- brief T8 inherited · 156줄 · 8711자 · 추정 토큰 2903 · Shared design 참조 0(명시 0, 해소 실패 0) · 변경 파일 3 · 테스트 0 · 작업 블록 28줄 · Shared design 0줄 · Goal 5줄 --> 결과(재시도 1회, 문서 검증 완료, 최종 확정 대기); 계획 이탈(request 결과·종결된 0시간 정의·테스트 증거 범위 정정)
- 바뀐 파일: `android/core/src/main/kotlin/com/ny/my/health/core/{SyncProfiling,SyncCoordinator,PrivateDriveUploader}.kt`, `android/core/src/test/kotlin/com/ny/my/health/core/{SyncProfilingTest,SyncCoordinatorTest,PrivateDriveUploaderTest}.kt`, `android/app/src/main/java/com/ny/my/health/{HistorySession,ManualSyncProfiling,SyncViewModel}.kt`, `android/app/src/test/java/com/ny/my/health/{HistorySessionTest,ManualSyncProfilingTest,GoogleDriveTransportTest,SyncProfilingBaselineTest}.kt`, `docs/2026-11-02-sync-profiling/{baseline,device-profiling,execution-report,plan,spec,discovery}.md`, `docs/backlog/sync-performance.md`. 브리프는 무시 경로의 실행 기록이며 커밋 대상이 아니다.
- 판정: 구현·테스트 소스 `2ccd1d5bbca1a3a2210a39f2f867c7ba03451a53`에 대한 로컬 전체 JVM 테스트·debug APK·lint는 PASS. 문서 전용 후속 head는 `fa552277c815b43f5314b19700c91e883a04eb56`이며 소스·테스트는 동일하다. 현재 XML은 core 41 + app 59 = 100건, 실패/오류/skip 0건이다. 독립 스펙 적합성 검증은 `fa55227`에서 11/11 PASS, 실제 100건 재실행도 52초에 PASS로 오케스트레이터가 확인했다. 이후 T2 테스트 이름만 정정한 소스의 최종 재확인은 대기다. hosted CI·독립 코드/보안/성능 리뷰와 최종 문서 head 판정도 별도 미완료다. 머지 가능 판정은 하지 않는다.
- 자동 복구: T1 1/2·T5 1/2는 조립기가 기존 산문 계약을 누락하여 선언 주석으로 옮긴 뒤 재조립한 보정이다. 같은 유형이 재발했으며 제품 오류 복구는 아니다. T4 1/2는 취소와 중단 이력 저장 실패 시 원래 취소 동일성 검증 보완, T7 1/2는 `app/build` 출력 경로와 HTTP 503의 명시적 FAILED 통계 보정이다. 해당 보정 후 아래 검증은 PASS. T2 1/2는 성공 전용 계측 테스트의 과도한 이름을 검증 본문에 맞게 정정한 복구이며 업무 동작 변경은 없다. T8 1/2는 request 결과 의미, 종결된 0시간 정의, 테스트 증거 범위의 문서 정정으로 정적 검사 PASS다. T3/T6 0/2, 승격 없음. 복구 기록의 최종 집계는 계획 문서에서 관리한다.

## 검증

아래 Gradle 명령은 저장소의 `android`에서 공통 접두사 `python /workspace/tooling/run-health-command.py ./gradlew --offline --no-daemon` 뒤에 표의 인자를 붙여 오케스트레이터가 실행했다. 로그는 공개 저장소 밖의 `/workspace/tooling`에 있다. T8은 Gradle을 중복 실행하지 않고 로그·테스트 소스/XML을 읽었다.

| 검증 항목 | 실행한 명령 | 결과 |
|---|---|---|
| T1 | `:core:test --tests com.ny.my.health.core.SyncProfilingTest` | PASS, 4건; `pr9-t1-root.log`, 11초, UP-TO-DATE 포함 |
| T2 | `:core:test --tests com.ny.my.health.core.SyncCoordinatorTest` | PASS, 8건; `pr9-t2-root.log`, 10초, UP-TO-DATE 포함 |
| T3 | `:core:test` | PASS, core 41건; `pr9-t3-root.log`, 10초, UP-TO-DATE 포함 |
| T4 | `:app:testDebugUnitTest --tests com.ny.my.health.HistorySessionTest` | PASS, 31건; `pr9-t4-root.log`, 11초, UP-TO-DATE 포함 |
| T5 root checkpoint | `:core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` | PASS, 당시 checkpoint 98건; `pr9-t5-root-checkpoint.log`, 16초. 현재 최종 100건과 구별 |
| T6 | `:app:testDebugUnitTest --tests com.ny.my.health.GoogleDriveTransportTest` | PASS, local TLS 7건; `pr9-t6-root.log`, 12초, UP-TO-DATE 포함 |
| T7 | `:app:testDebugUnitTest --tests com.ny.my.health.SyncProfilingBaselineTest --rerun-tasks` | PASS, 1건; `pr9-t7-root.log`, 42초, 26 tasks 실행 |
| 전체 검증 | `:core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` | PASS, 100건, 실패/오류/skip 0건; `pr9-full-root.log`, 40초 |
| T8 문서 | 저장소 루트 `git diff --check` | PASS |
| 의존성 재해결 | 외부 `snapshot.init.gradle`의 `:app:reviewDependencySnapshot` | PASS, 11초; 3개 configuration 객체가 공개 121 coordinates 그래프와 정확히 같음. master 대비 build/Gradle 설정 차이 없음 |
| 의존성 취약점 조회 | `pr9-security/audit-summary.json` 확인 | 최신 OSV 121결과, 조회 오류 0, 알려진 매치 0 |
| 비밀정보 검사 | 커밋 소스 git archive 및 `origin/master..HEAD` 이력의 Gitleaks | 각각 exit 0, finding 0; `pr9-security/{source,history}-gitleaks.json` |

합성 기준선은 `/workspace/tooling/pr9-baseline-final/summary.csv`의 동결 결과만 사용했다. revision `2ccd1d5bbca1a3a2210a39f2f867c7ba03451a53`, dirty=false, harness SHA-256 `baba4dcbde9ebebc000b001ab033c31488e34ba200fafe18410dd2798000f6a9`, Java 21.0.13+9-LTS, Linux/amd64다. live build 출력은 기준선으로 쓰지 않는다. 공개용 집계와 재현 조건은 [baseline.md](baseline.md)를 본다. warm1/cold1/warm3은 각각 준비 3회 제외 후 10회이며 4/7/12 transport 요청이다. HTTP 503 1건과 reader 취소 1건은 성공 분포와 분리했다. 실제 Health Connect·Google 계정·Google 서버 지연·로컬 이력 저장·CPU/메모리/배터리의 실측 기준선은 아니다.

## 테스트 대조

아래는 소스와 XML에서 확인한 실제 이름의 증거 색인이다. 전체 승인 계획 추적성 표와의 최종 대조·갱신은 오케스트레이터의 독립 적합성 검증 뒤 확정하며, 이 표만으로 모든 스펙 기준의 충족을 선언하지 않는다. 경로는 core=`android/core/src/test/kotlin/com/ny/my/health/core`, app=`android/app/src/test/java/com/ny/my/health` 기준이다.

| 계획한 테스트 (출처/층) | 스펙 기준 | 실제 테스트 (파일 · 테스트 이름) | 결과 |
|---|---|---|---|
| T1 고정 수집/JVM | 미실행·0시간 구별 | core/SyncProfilingTest.kt · `Unstarted stages are absent while completed zero duration stages remain present` | PASS |
| T1 단조 시간/JVM | 실패·취소·활성 상태 분리 | core/SyncProfilingTest.kt · `Monotonic stage timing survives wall clock changes and preserves failed cancelled and active states` | PASS |
| T1 유계 저장/JVM | 원시 이벤트 무저장 | core/SyncProfilingTest.kt · `Profiling storage remains bounded after repeated requests and terminal snapshots` | PASS |
| T1 계측 실패/JVM | 원래 값·예외 유지 | core/SyncProfilingTest.kt · `Clock failures preserve block return values and original exception identity` | PASS |
| T2 성공 계측/JVM | 성공 결과·호출 순서·단계별 시간 분리 | core/SyncCoordinatorTest.kt · `Profiling preserves successful sync outcomes call order and separate stage timings` | 동일 본문은 PASS; 개명 후 최종 확인 대기. 부분 실패·취소·이력 실패를 이 테스트로 주장하지 않음 |
| T2 부분 실패/JVM | 계측 유무와 무관하게 확인된 파일만 보고 | core/SyncCoordinatorTest.kt · `Partial upload failure reports only confirmed files` | PASS |
| T2 취소/JVM | 읽기·정규화·CSV·업로드 취소의 원래 예외 전파 | core/SyncCoordinatorTest.kt · `Cancellation propagates without reporting success` | PASS |
| T4 시작 커밋 실패/JVM | 계측 유무와 무관하게 읽기·업로드 차단 | app/HistorySessionTest.kt · `실행 시작 저장 실패는 실제 효과 경계의 읽기와 업로드를 모두 막는다` | PASS |
| T4 결과 커밋 실패/JVM | 계측 유무와 무관하게 원격 확인·이전 영속 성공 분리 | app/HistorySessionTest.kt · `원격 확인 뒤 결과 저장 실패는 일시 증거와 이전 영속 성공을 분리한다` | PASS |
| T4 취소 종결 커밋/JVM | 계측 유무와 무관하게 종결 저장·재실행 금지 | app/HistorySessionTest.kt · `전경 작업 취소는 독립 종결 저장을 끝내고 원격 업로드를 재실행하지 않는다` | PASS |
| T4 결과 저장 중 취소/JVM | 계측 유무와 무관하게 원격 확인·성공 커밋 보존 | app/HistorySessionTest.kt · `결과 저장 중 전경 취소는 원격 확인과 성공 커밋을 잃지 않는다` | PASS |
| T3 요청 모양/JVM | warm/cold/페이지 요청 수 분리 | core/PrivateDriveUploaderTest.kt · `Drive request counts match warm cold and paginated calls` | PASS |
| T4 이력 경계/JVM | 중단 저장 실패에도 원래 취소 유지 | app/HistorySessionTest.kt · `중단 이력 저장이 실패해도 원래 취소와 이전 성공을 보존한다` | PASS |
| T5 사용자 대기/JVM | 인증·계속 대기를 실행에서 분리 | app/ManualSyncProfilingTest.kt · `Authorization and continuation waits never contribute to execution elapsed` | PASS |
| T5 안전 출력/JVM | 고정 라벨·숫자만 출력 | app/ManualSyncProfilingTest.kt · `Safe profiling summary contains only fixed enums and numeric statistics` | PASS |
| T5 비활성/출력 실패/JVM | 결과 보존·반복 출력 방지 | app/ManualSyncProfilingTest.kt · `비활성 출력과 실패하는 출력기는 업무 결과를 바꾸거나 반복 출력하지 않는다` | PASS |
| T6 실제 transport/local TLS | 순서·private 검증·취소·재전송 금지 | app/GoogleDriveTransportTest.kt · `Instrumented local TLS keeps request order private validation and cancellation without replay` | PASS |
| T7 합성 기준선/local TLS | 반복 수·요청 수·nearest rank 검증 | app/SyncProfilingBaselineTest.kt · `Local TLS baseline records repeatable scenarios sample counts and nearest rank percentiles` | PASS |
| T8 문서 | 안전한 기기 절차·실제 증거 기록 | 신규 앱 테스트 불필요; 문서 정적 검사 | PASS, 실제 기기 수집은 미실행 |

- 추적성 표 갱신: T8이 계획을 편집하지 않았다. 계획 표의 최종 실제 이름 대조·갱신은 오케스트레이터 대기다.
- skip·비활성화된 테스트: 실행 XML의 skip 0건. 이 수치만으로 미실행 기기/계정 수용을 대체하지 않는다.
- 신규·변경된 테스트 파일: core의 SyncProfilingTest.kt, SyncCoordinatorTest.kt, PrivateDriveUploaderTest.kt; app의 HistorySessionTest.kt, ManualSyncProfilingTest.kt, GoogleDriveTransportTest.kt, SyncProfilingBaselineTest.kt. T8 신규 테스트 없음.
- 커버리지 증감: 레포의 수치 추적 근거 없음. 추정하지 않는다.

## 남은 문제

- 실행하지 못한 검증: 실제 기기·계정·TalkBack·P0 잔여 수용 및 기기 성능 측정. hosted JVM CI `41555758734`/emulator CI `41555758737`는 최종 결과 확인 대기. 독립 스펙 적합성은 `fa55227`에서 11/11 PASS(비공개 실행 보고 경로 `/workspace/tooling/pr9-conformance-report.md`)이나 테스트 이름 정정 뒤 최종 소스 재확인은 대기다. 별도 세션의 코드 품질·보안·성능 리뷰와 현 문서 head의 리뷰 코멘트/최종 판정도 대기다.
- 미커버 분기: 실제 OAuth/Health Connect/Google 네트워크 지연과 기기 CPU·메모리·배터리, 인증 이력 커밋 진행 중 폐기 시 완결 실측은 합성 기준선에 없다. `active=1` 요약은 미완료로 남기며 완료로 치환하지 않는다.
- 다시 판단할 조건: 소스 revision·harness·JDK/OS·표본/데이터량·네트워크·기기 조건 변경 시 새 기준선이 필요하다. T1/T5의 같은 산문 계약 누락 재발은 계획 복구 기록에서 추적하며 `검증 확장 필요`: `evals/assembler-check`에 재발 입력을 추가하고 추출 문제와 전달 문제를 좁히는 후속 작업을 별도로 수행한다. 최적화·목표·새 의존성/인증/목적지/영속 진단/외부 telemetry는 별도 승인 대상이다.

## 다음 단계

스펙 적합성 검증(spec-conformance-check)의 테스트 이름 정정 후 최종 소스 재확인을 진행한다. 별도 세션의 코드·보안·성능 리뷰와 hosted CI를 확인하고 현재 head·지적→수정→검증→verdict를 PR 코멘트에 남긴 뒤 오케스트레이터가 최종 상태를 확정한다. T8은 GitHub에 게시하지 않는다. 이후 [기기 절차](device-profiling.md)에 따라 비공개 실측을 수행하고 최적화 후보와 목표를 별도로 승인받는다.
