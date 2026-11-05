# Drive 배치 한정 최적화 실행 증거

## 범위와 현재 판정

승인된 [스펙](spec.md)·[계획](plan.md)의 배치 내 앱 루트 재사용과 최대 2개 파일 작업만 다룬다. 영속 ID 캐시·자동 재시도·추가 권한·인증 흐름·CSV/읽기 계약 변경은 없다. 로컬 자동 검증과 아래 합성 지연 비교는 통과했다. [PR #15](https://github.com/nyeonu/health-app/pull/15)의 fresh-context 독립 적합성·품질·보안·성능 검토와 API35/36 CI는 아래 기록 시점에 pending이며, 개발 병합·V1 완료·실기기/실계정 수용 완료를 선언하지 않는다.

## 검증 소스와 실행

- 실제 검증 소스: `fdda2399304e578340f14af4d208d42e96549730`, 측정 당시 `dirty=false`.
- 실행 명령(Android 프로젝트): `python /workspace/tooling/run-health-command.py ./gradlew --offline --no-daemon :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest`.
- 전체 결과: `BUILD SUCCESSFUL in 1m 29s`, 84 actionable tasks(12 executed, 72 up-to-date). 테스트 XML 16개에서 core 55 + app 74 = 129개, 실패 0·오류 0·skip 0을 확인했다. APK·lint·테스트 APK 빌드 통과. Gradle 작업의 UP-TO-DATE/SKIPPED 표시는 테스트 XML의 skip 수와 다르다.
- 실행 환경 내부 원본: `/workspace/tooling/v1-drive-whole-root.log`, `/workspace/tooling/v1-drive-fdda-evidence/{core,app}/` 및 `performance-summary.csv`. 이 경로는 로컬 증거 보관 위치이며 저장소에 원본 덤프를 추가하지 않는다.
- [JVM/APK/lint CI 41953996190](https://github.com/nyeonu/health-app/actions/runs/41953996190): 성공. artifact `13381514892`의 ZIP SHA256 `25771b599d7f2a4b07853c0f484bde7534c47ef7d1731e6f709284ad7697a8cf`를 확인했고 XML은 129개·실패/오류/skip 0이다. 로컬 보관 위치는 `/workspace/tooling/v1-drive-fdda-ci-jvm`이다.
- [API35/36 instrumentation CI 41953996200](https://github.com/nyeonu/health-app/actions/runs/41953996200): 진행 중, PASS 미확인. 로컬 테스트 APK 빌드는 emulator 실행 증거가 아니다.

T1은 중복 대상 및 실제 transport 계약에 맞는 루트 fixture의 한정 교정을 거쳤다. T2 첫 실행은 14개 중 6개가 실패했다. 빈 응답의 `onResponseStart CloseSocket`이 의도한 NETWORK 대신 RESPONSE_INVALID를 만들었고, 승인된 한정 복구에서 비어 있지 않은 body와 `onResponseBody CloseStream(2)`를 사용했다. 기대 오류 분류와 단언을 유지한 재실행은 14개 전부 통과(43초)했다. 최종 판단은 이 중간 결과나 dirty 측정값이 아닌 위 clean 소스의 전체 129개 결과와 아래 clean 요약에 따른다.

## 합성 지연 측정 방법

`DriveBatchPerformanceTest`는 같은 합성 warm private 3CSV tree에 실제 `GoogleDriveTransport`/OkHttp와 local TLS를 사용한다. production client 설정에서 local TLS 신뢰와 routing만 대체했다. endpoint 응답 headers 지연 40ms, 경로별 별도 유지 연결 풀, 각 경로 전체 배치 예열 3회 후 성공 10회씩 수집했다. serial/optimized 순서는 paired 교대이며 예열 표본은 제외한다. `System.nanoTime`과 정렬 nearest-rank를 사용한다. Java `21.0.13+9-LTS`, Linux/amd64, harness SHA256 `f0013ca647741e25069d8fd100f44a7af36e1d09b9a73e38a5548714e6f8364b`다.

이 harness의 `EXECUTION`은 `DRIVE_TOTAL`을 감싸며 두 stage 모두 `uploader.upsertBatch` 호출과 확인 콜백만 측정한다. coordinator의 Health Connect 읽기·정규화·CSV 생성·이력·인증/사용자 대기·UI는 포함하지 않는다. 두 stage의 작은 차이는 wrapper 경계 차이다. 과거 무지연 `SyncProfilingBaselineTest`의 coordinator 전체 실행과 범위·지연이 다르므로 harness 간 시간은 직접 비교하지 않는다. Drive 벽시계 시간과 서로 겹치는 operation/request 누적 시간을 합산하거나 동일 값으로 보지 않는다.

## Clean 소스의 성공 분포

단위 ns. 경로별 n=10이며 실패는 모두 0이다. 아래 값은 최종 clean `performance-summary.csv`와 같다.

| 경로 | stage | n | p50(ns) | p95(ns) | 표본당 요청 | 최대 활성 요청 | 실패 |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| serial | EXECUTION | 10 | 511129315 | 526807311 | 12 | 1 | 0 |
| optimized | EXECUTION | 10 | 387688905 | 439237075 | 10 | 2 | 0 |
| serial | DRIVE_TOTAL | 10 | 511118418 | 526796394 | 12 | 1 | 0 |
| optimized | DRIVE_TOTAL | 10 | 387678780 | 439227180 | 10 | 2 | 0 |

이 조건에서 두 stage의 p50/p95가 모두 감소하고 성공 확인 파일 수는 각 3개다. n=10 nearest-rank p95는 열 번째 값, 즉 관측 최댓값이다. 실제 사용자 p95·초 단위 SLA·실제 개선율·Google/Galaxy 성능을 뜻하지 않는다. 소유자가 제공한 과거 실제 업데이트/생성 관찰은 각각 n=1이며 이 합성 분포와 섞지 않는다.

## 실패·취소를 분리한 결과

성공 분포에 포함하지 않은 각 시나리오는 요청 8개, 두 write가 겹친 상태에서 실패/취소를 주입한다. 확인 콜백은 0개이며 세 번째 파일 작업은 시작하지 않는다.

| 시나리오 | 분류 | EXECUTION 실패 | EXECUTION 취소 | write 완료 | write 실패 | write 취소 |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| HTTP | HTTP | 1 | 0 | 0 | 1 | 1 |
| network | NETWORK | 1 | 0 | 0 | 1 | 1 |
| body | RESPONSE_INVALID | 1 | 0 | 0 | 1 | 1 |
| metadata | TARGET_REJECTED | 1 | 0 | 0 | 1 | 1 |
| cancel | 해당 없음 | 0 | 1 | 0 | 0 | 2 |

각 stage/operation/request의 started=completed+failed+cancelled와 종료 후 active=false를 검사했다. 실패 시 sibling 종료를 기다리며, 이후 같은 uploader의 명시적 재호출은 요청 10개로 3개를 확인한다. 자동 재시도는 추가하지 않았다. 클라이언트 취소가 이미 전송된 원격 write를 되돌리거나 서버 처리를 중단한다고 주장하지 않는다.

## 독립 검토와 남은 수용

PR #15의 현재 head·전체 diff에 대한 별도 fresh-context 세션의 적합성/품질/보안/성능 판정과 공개 PR 댓글은 pending이다. 이 문서 작성자는 이를 승인으로 대체하지 않는다. 차단 지적이 있으면 수정·정확한 수정 head 검증·독립 재검토 후 결과를 갱신한다.

- 실제 Google 계정에서 변경 후 latency·private sharing·same-ID/no-duplicate 확인: pending.
- 변경 후 Galaxy의 실제 성능과 기기 수용: pending. merge 후 고정 서명 APK의 package/certificate를 확인하고 전달해야 하며 아직 확인하지 않았다.
- 다중 uploader instance/다중 기기 생성 경쟁은 보장하지 않는다. 배치 mutex는 같은 instance에만 적용된다.
- 루트/대상 검증 직후 외부 공유 설정이 바뀌는 경쟁을 원자적으로 차단하지 못한다. 지속 private 상태나 실제 SLA를 보장하지 않는다.
- 실제 계정·회전/navigation·TalkBack·기존 P0 미확인은 그대로 유지한다. PR14의 최초 진입/상단 복귀 표시만 [소유자 user-reported PASS](../2026-11-04-top-content-layout/report.md)로 별도 기록한다.

공개 기록은 합성 집계와 비식별 결과만 포함한다. 실제 건강값·CSV 본문·계정/원격 ID/링크·credential·예외 원문은 포함하지 않는다.
