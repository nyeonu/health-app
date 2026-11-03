# 스펙 적합성 검증

- 대상 스펙: docs/2026-11-02-sync-profiling/spec.md
- 판정: PASS
- 검증한 HEAD: `fa552277c815b43f5314b19700c91e883a04eb56`
- 실행: `cd android && python /workspace/tooling/run-health-command.py ./gradlew --offline --no-daemon :core:test :app:testDebugUnitTest --rerun-tasks`
- 결과: `BUILD SUCCESSFUL in 52s`, 28 tasks 실제 실행; JUnit XML 직접 합산 core 41 + app 59 = 100 tests, failures=0/errors=0/skipped=0.

## 기준별 결과

| 성공 기준 | 판정 | 실행 증거 |
|---|---|---|
| 인증·사용자 대기와 실제 동기화 실행이 구분되어 대기 시간을 upload-only latency로 보고하지 않는다. | PASS | 직접 재실행한 ManualSyncProfilingTest.Authorization and continuation waits never contribute to execution elapsed: 인증 9×10¹² ns, 계속 대기 8×10¹² ns를 주어도 EXECUTION=19 ns; 실행 전 EXECUTION 없음. baseline 하네스는 coordinator 경계만 측정하며 baseline.md가 controller·인증·대기 미측정을 명시한다. |
| 실행된 읽기·변환·Drive 작업·로컬 이력 저장의 경과 시간과 요청 작업별 횟수를 구분하고, 실행하지 않은 단계는 측정된0과 구분한다. | PASS | SyncProfilingTest.Unstarted stages are absent while completed zero duration stages remain present 및 PrivateDriveUploaderTest.Drive request counts match warm cold and paginated calls 실행 통과. 실제 stage/operation/request 키·횟수·시간을 단언하고 페이지 조회는 operation 1/request 2로 구분. HistorySessionTest의 시작·종결 커밋 측정 및 실패 단언 통과. |
| 경과 시간은 벽시계 변경에 영향을 받지 않는 단조 시계로 측정하며 오류·취소 시 완료한 구간과 미완료 구간을 구분한다. | PASS | SyncProfilingTest.Monotonic stage timing survives wall clock changes and preserves failed cancelled and active states 통과: 벽시계 1시간 역행에도 10/20/30 ns와 완료/실패/취소/active를 구분. Clock failures preserve block return values and original exception identity 통과. 실제 baseline는 System.nanoTime으로 실행. |
| 진단은 기존 SyncResult, 부분 업로드 수, 취소 전파, 권한/비공개 대상 판정 및 이력의 커밋·실패 보장을 바꾸거나 새 실행·재시도를 만들지 않는다. | PASS | SyncCoordinatorTest의 profiling on/off 결과·순서 비교, EMPTY/권한/부분 업로드/취소 테스트, HistorySessionTest의 시작 실패 시 effects=0·종결 저장 실패·취소 중 성공 커밋 보존·실패한 중단 저장 뒤 동일 취소 객체 보존 테스트 직접 통과. GoogleDriveTransportTest의 local TLS 위험 대상/503/취소 시 요청 수 단언 통과. |
| 진단에는 건강 수치·건강 기록 건수·보고 날짜·CSV·계정/파일/레코드 식별자·토큰·URL/query·공급자 오류 원문을 포함하지 않는다. | PASS | ManualSyncProfilingTest.Safe profiling summary contains only fixed enums and numeric statistics 통과: 전체 출력행이 고정 enum 라벨과 6개 비음수 정수로만 이루어지고 8192자 이하임을 확인. 오류/취소 객체는 summary 입력으로 받지 않는 구조를 확인했고 SyncDiagnostics/AuthorizationDiagnostics/transport의 provider 본문·원인 비노출 테스트도 통과. |
| 공개 산출물은 고정 합성 fixture만 사용하고 실제 기기 진단은 명시적으로 공유한 안전한 결과 외에는 비공개로 유지한다. | PASS | SyncProfilingBaselineTest 직접 실행: 고정 날짜·generatedAt·42 steps·고정 합성 심박/수면·synthetic ID/token만 사용한 실제 local TLS 39회 준비/성공 시도 및 별도 실패/취소. 원본/새 summary의 fixture 경로를 확인. 공개 baseline.md는 합성 결과와 안전한 집계만 담고 실제 기기 자료 없음. 실제 기기 수집을 하지 않았으므로 비공개 실기기 보관 동작을 주장하지 않는다. |
| 계측 상태는 제한된 작업 종류의 요약으로 관리하며 페이지·기록마다 무제한 로그나 새 영속 사용자 이력을 만들지 않는다. | PASS | SyncProfilingTest.Profiling storage remains bounded after repeated requests and terminal snapshots 직접 실행: 2,000회×7종 요청 후 키 집합 고정, 누적값·포화 연산 단언. ManualSyncProfilingTest는 1회 출력·종료 전/비활성 출력 차단·출력기 예외 격리 단언. manifest/의존성/reader/transport 무변경과 production diff에서 새 영속 저장 경로 없음도 확인. |
| 동일한 fixture·원격 트리 상태·측정 환경의 반복 결과에 source revision, 조건, 표본 수와 산출 방법을 기록해 비교 가능한 기준선을 제공한다. | PASS | SyncProfilingBaselineTest 직접 실행으로 3조건 각 3회 준비+10개 성공 표본·고정 원격 응답·실제 TLS·nearest-rank 검증. frozen /workspace/tooling/pr9-baseline-final/summary.csv 430행에서 30표본과 43개 min/p50/p95/max 분포를 Python으로 독립 재계산하여 baseline.md의 모든 분포 및 별도 오류/취소 행과 일치. harness SHA256=baba4dcbde9ebebc000b001ab033c31488e34ba200fafe18410dd2798000f6a9. 기록된 2ccd1d5bbca1a3a2210a39f2f867c7ba03451a53과 현재 android diff 없음. |
| 기존 요청 순서·동일 파일 갱신·권한/EMPTY·위험 대상 거부·취소 회귀가 유지되고, 측정 코드 때문에 추가 원격 요청이나 암묵 재시도가 생기지 않는다. | PASS | PrivateDriveUploaderTest 10개 및 GoogleDriveTransportTest 7개 직접 통과. warm/cold/page 요청 순서, 동일 ID PATCH, 공유/모호/private target 거부, 503 Retry-After:0 이후 POST/PATCH 미재생, active 실제 TLS PATCH 취소 뒤 원격 요청 4개 유지 단언. coordinator EMPTY/권한 회귀와 부분 업로드 확인 수 보존 통과. |
| 보고서는 실제 관찰과 코드상 후보·외삽을 구분하고, 실제 기기 증거 없이 사용자 체감 개선이나 실사용 지배 병목을 확정하지 않는다. | PASS | baseline 하네스를 직접 재실행하고 문서의 frozen raw 분포를 독립 재계산. baseline.md가 local TLS 관찰, 합성 reader와 실제 Health Connect 차이, 인증/이력/Google/기기 미측정, JIT/부하·표본10 tail 한계를 구분한다. 실사용 지배 병목·사용자 개선율·SLA 확정 문구 없음. |
| 최적화 후보와 필요한 추가 검증을 측정 결과에서 도출하되 실제 최적화 구현과 수치 목표는 별도 승인 대상으로 남긴다. | PASS | 실행한 baseline에서 warm1=4/cold1=7/warm3=12 요청을 실제 검증. baseline.md 후속 후보는 실제 기기 단계·요청 분포 확인 및 반복 app folder 조회 조사이고 캐시/병렬화/배치/재시도 선택이나 목표 수치는 정하지 않는다. production diff는 수집기와 계측 연결만 추가하며 기존 mutex 직렬 업로드·원격 요청 경로를 유지한다. |

## 계약 준수

해당 없음. 스펙 4절은 외부 계약 변경 없음을 명시한다. 기존 CSV/날짜/권한/private Drive/취소/이력 계약은 직접 실행한 CSV·정규화·HealthConnectReader·coordinator·uploader·transport·history 회귀로 확인했다. Health Connect reader, manifest, Gradle 의존성, GoogleDriveTransport production 파일은 병합 기준 671d213cd8baf2c386436ae0bec2e0b905ef3da2와 동일하다.

## 추적성

격리 원칙에 따라 plan.md와 추적성 표는 읽지 않았다. 기준 ↔ 테스트 표 대조는 이 보고서 이후 오케스트레이터가 수행해야 한다. 위 증거에 사용한 테스트 내용과 실제 JUnit 실행 결과를 확인했으며 skip된 테스트는 없다. 다만 SyncCoordinatorTest의 `Profiling preserves sync outcomes partial confirmations cancellation and history commit failures`는 실제로 성공 on/off 동등성만 단언한다. 부분 실패·취소·이력 실패는 다른 명시적인 테스트에서 실행 확인했으므로 해당 긴 이름만을 모든 조건의 증거로 사용하면 안 된다.

## 경계 위반

없음. 검토한 Android diff에서 새 OAuth scope/서비스/의존성/WorkManager/SAF/백필/백엔드/MCP/외부 자동 telemetry, 캐시·병렬화·배치·자동 재시도, 읽기 범위 축소 또는 UI 진단 대시보드 변경을 발견하지 않았다. 실행 증거는 고정 합성 데이터와 local TLS에 한정했다.

## 실패 상세

없음.

## 남은 불확실성

- 실제 Galaxy/Google OAuth·실제 Google Drive 네트워크·Health Connect provider/Android UI·SQLite 기기 내구성·TalkBack·배터리/스케줄링은 실행하지 않았다. 기기/계정이 없고 이번 스펙은 실제 기기 수집을 가능할 때만 수행하도록 허용한다. PASS는 이러한 실기기 수용의 통과나 사용자 체감 성능 개선을 뜻하지 않는다.
- SyncViewModel은 JVM test에서 실제 Android lifecycle/UI로 구동하지 않았다. ManualSyncProfiling·HistoryEffects와 실제 transport 경계는 실행했으며 실제 화면의 연결은 source로 대조했다. UI 대시보드 변경이 없어 브라우저 검증 대상은 없다.
- 이번 재실행 summary revision은 검증 HEAD이며 dirty=true이다. 동시에 다른 작업자가 문서를 갱신하는 조건이므로 이 값을 소스 dirty=false의 재현으로 주장하지 않는다. frozen 원본 dirty=false와 Android 소스 동일성은 따로 확인했다. 문서의 보관된 시간 수치와 재실행 시간의 일치는 요구하지 않았고 실제 시간은 달라질 수 있다.
- 이 문서는 read-only 독립 검증 보고서이다. 레포 수정·스펙 status 변경·GitHub 댓글 게시·plan 추적성 확인은 수행하지 않았다.

## 최종 HEAD 추가 검증 (2026-11-03)

- 추가 검증 HEAD: `f154263677bb94b5bc1cc11e7c3945e6ffe890fb`. 최종 11개 성공 기준 판정은 모두 PASS로 유지한다.
- `git diff fa552277c815b43f5314b19700c91e883a04eb56 HEAD -- android`를 독립 확인했다. 유일한 Android 변경은 SyncCoordinatorTest의 테스트 이름을 `Profiling preserves successful sync outcomes call order and separate stage timings`로 교체한 한 줄이다. 이전 파일에서 이 문자열 하나만 바꾸면 현재 파일과 완전히 같음을 프로그램으로 단언했다. 모든 tracked production 코드와 baseline harness는 이전 전체 실행 HEAD와 바이트가 같다. 앞서 100개 전체 테스트의 실행 증거는 그 동일한 구현/검증 바이트에 적용한다.
- 현재 HEAD에서 `cd android && python /workspace/tooling/run-health-command.py ./gradlew --offline --no-daemon :core:test --tests com.ny.my.health.core.SyncCoordinatorTest --rerun-tasks`를 직접 실행했다. `BUILD SUCCESSFUL in 26s`, 4 tasks 모두 실제 실행. JUnit XML에서 tests=8/failures=0/errors=0/skipped=0을 별도 확인했다. 수정한 이름의 성공 동등성/호출 순서/단계 시간 테스트도 실행 통과했다. 추적성 절의 이전 이름이 과도하다는 지적은 이 이름 수정으로 해소됐다. 계획 추적성 표 자체의 검증은 여전히 오케스트레이터의 별도 책임이다.
- 새로 읽은 문서는 `device-profiling.md`뿐이다. plan/brief/execution report/타 검토 보고서는 읽지 않았다. 소스와 대조한 결과 다음 주장이 맞는다: debug 플래그에서만 SyncProfiling tag로 종료 시 최선 노력 1회 출력; 10 stage+7 operation+7 request의 enum 순서 총 24행; 중복 라벨은 행 위치로 scope 구별; 각 행은 라벨과 숫자 6열; request는 실제 uploader의 transport 및 HTTP/JSON 응답 처리 구간; private 대상 검증은 operation 단계에서 실패 가능; 미실행·active·관찰된 0·시계결함 폐기를 구별; 인증/계속/실행 경계는 source와 일치하고 업로드 전용 시간으로 주장하지 않는다. 문서의 비공개 tag 한정 수집·공개 allowlist·미실행 기기 수용 한계도 승인 스펙과 일치한다. 실제 adb 수집 또는 Android lifecycle를 추가 실행했다는 의미는 아니다.
- 실제 Galaxy/계정/Health Connect/SQLite/화면/접근성·P0 수용은 미검증으로 유지한다. 기기 문서는 절차이며 실행 결과가 아니다. 문서 시간 수치나 실사용 성능 개선을 새로 측정한 것으로 주장하지 않는다.
- 레포 파일 편집·GitHub 게시 없음.

## 오케스트레이터 추적성 대조

승인 스펙의 성공 기준11문장이 계획 표의11행과 정확히 같음을 별도 스크립트로 확인했다. 표의 실제 테스트명과 단언을 대조했으며 성공 경로·부분 업로드 실패·취소·이력 실패는 각 실제 테스트로 매핑했다. 구현 범위 밖 기기/계정 검증과 문서·정적 기준을 테스트 PASS로 바꾸지 않았다. 최종 core41+app59=100개, 실패/오류/skip0 및 독립 실행 증거를 확인했다.
