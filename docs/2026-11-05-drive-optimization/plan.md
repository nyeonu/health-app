---
plan_id: 2026-11-05-drive-optimization
title: 하루 Drive 배치 최적화 실행 계획
kind: single
status: in_progress
author: Codex
created: 2026-11-05
target: /workspace/health-app-top-layout
spec: spec.md
spec_rev: c5620668ad80c521b66741f291753eb8fe4091d9
branch: feat/v1-drive-optimization
authorization: owner-delegated-automatic-approval
tasks:
  - id: T1
    title: 배치 업로드의 확인과 종료 경계
    depends_on: []
    status: done
    tier: standard
  - id: T2
    title: 실제 TLS 전후 분포와 오류 검증
    depends_on: [T1]
    status: done
    tier: standard
  - id: T3
    title: 실행 증거와 남은 수용 조건 기록
    depends_on: [T2]
    status: pending
    tier: light
---

# 하루 Drive 배치 최적화 실행 계획

## 1. 선택한 방식과 이유

기존 DriveUploader에 기본 순차 배치 메서드를 추가하고 PrivateDriveUploader만 최적화한다. 기존 fake와 단일 호출자는 유지되며 coordinator가 대상 구현을 구별할 필요가 없다. 전체 배치와 단일 호출이 같은 instance mutex를 사용하고, 루트 한 번 확인 뒤 데이터셋 폴더를 입력 순서대로 준비한다. 준비 단계의 일부 직렬 대기를 감수해 폴더 생성 경쟁과 별도 cache를 피한다. 이후 파일 조회와 쓰기를 두 슬롯 안에서 겹친다. 병렬 폴더 생성이나 영속 ID cache는 이 한정 개선보다 배제·무효화 조건이 커 선택하지 않았다.

확인 콜백은 검증된 파일마다 정확히 한 번 직렬 호출한다. 첫 실패와 외부 취소 모두 실행 중 자식을 정리한 뒤 mutex를 해제한다. 원격 쓰기는 rollback할 수 없으므로 확인된 수만 보고한다. 구조화된 자식 취소·작업 예약 경쟁의 구현 판단은 T1 실행자에게 남긴다.

새 의존성·schema·인증·외부 API·영속 모델 도입이 없고 기존 내부 port의 기본 메서드만 추가하므로 되돌리기 비싼 ADR 후보는 없다. 소유자의 자동 승인 위임으로 이 계획은 approved이며 스펙 6절 밖의 변경 권한은 추가하지 않는다.

## 2. 공통 설계

### 변경 파일

| 파일 | 신규/수정 | 변경 요지 | 작업 |
|---|---|---|---|
| `android/core/src/main/kotlin/com/ny/my/health/core/Ports.kt` | 수정 | 기존 단일 호출과 호환되는 배치 기본 계약 | T1 |
| `android/core/src/main/kotlin/com/ny/my/health/core/PrivateDriveUploader.kt` | 수정 | 배치 범위 루트와 폴더 준비, 최대 두 파일 작업 | T1 |
| `android/core/src/main/kotlin/com/ny/my/health/core/SyncCoordinator.kt` | 수정 | 배치 확인 콜백 기반 결과와 종료 경계 | T1 |
| `android/core/src/test/kotlin/com/ny/my/health/core/PrivateDriveBatchTest.kt` | 신규 | 배치·동시 진입·보안·실패 회귀 | T1 |
| `android/core/src/test/kotlin/com/ny/my/health/core/SyncCoordinatorTest.kt` | 수정 | 배치 결과·취소·프로파일링 회귀 | T1 |
| `android/app/src/test/java/com/ny/my/health/SyncProfilingBaselineTest.kt` | 수정 | 기존 순차 단일 upsert adapter로 12회 기준선 보존 | T2 |
| `android/app/src/test/java/com/ny/my/health/DriveBatchPerformanceTest.kt` | 신규 | 경로 기반 실제 TLS 비교·오류·취소 | T2 |
| `docs/2026-11-05-drive-optimization/execution-report.md` | 신규 | 실행·측정·독립 검토와 미확인 항목 | T3 |
| `docs/implementation-plan.md` | 수정 | 검증된 한정 최적화 진행 상태 연결 | T3 |
| `docs/2026-11-04-top-content-layout/report.md` | 수정 | PR14 상단 표시의 소유자 실기기 확인만 반영 | T3 |
| `docs/backlog/layout-refinement.md` | 수정 | 상단 표시 user-reported PASS와 나머지 기기 미확인 분리 | T3 |
| `AGENTS.md` | 수정 | 2026-11-05 소유자 자동 승인 위임과 한계 기록 | T3 |

### 인터페이스

```kotlin
// DriveUploader — Ports.kt; 기존 메서드 보존, 기본 구현은 순차
interface DriveUploader {
    suspend fun upsert(file: CsvFile): String
    suspend fun upsertBatch(files: List<CsvFile>, onConfirmed: suspend (String) -> Unit)
}
// PrivateDriveUploader — 기존 생성자와 단일 메서드 유지
class PrivateDriveUploader(transport: DriveTransport, profiling: SyncProfiling? = null) : DriveUploader {
    override suspend fun upsert(file: CsvFile): String
    override suspend fun upsertBatch(files: List<CsvFile>, onConfirmed: suspend (String) -> Unit)
}
// CsvFile — 기존 Model.kt 모델, 변경 없음
data class CsvFile(val relativePath: String, val content: String)
// DriveTransport — 기존 transport 계약, 변경 없음
interface DriveTransport {
    suspend fun execute(method: String, url: String, contentType: String? = null, body: ByteArray? = null): DriveResponse
}
// SyncCoordinator — 생성자와 public sync API 유지
class SyncCoordinator {
    suspend fun sync(date: LocalDate): SyncResult
    suspend fun syncYesterday(onDateSelected: (LocalDate) -> Unit = {}): SyncResult
}
// SyncProfiling — 기존 집계 API 유지
class SyncProfiling {
    fun snapshot(): ProfileSummary
    suspend fun <T> measure(stage: ProfileStage, block: suspend () -> T): T
}
```

- `DriveUploader`·`PrivateDriveUploader` 배치 계약: 반환값 없음; 전체 성공 때만 정상 반환한다. 기본 구현도 각 upsert 뒤 공백 ID를 거절하고 확인 콜백을 호출한다. ID는 로그에 넣지 않는다. callback 오류도 배치 실패이며 더 진행하지 않는다.
- `DriveUploader`·`PrivateDriveUploader` 배치 계약: PrivateDriveUploader는 원격 metadata와 ID 검증이 끝난 파일만 콜백한다. callback은 직렬이며 suspend가 끝나기 전 다음 콜백을 시작하지 않는다. 정상·실패·취소 반환 뒤 callback 또는 자식 작업이 남지 않는다. callback 안에서 같은 uploader를 재진입하지 않는다. 원격 확인은 받았으나 취소 때문에 callback이 완료되지 못한 쓰기는 indeterminate이며 확인 수에서 제외한다. 완료한 callback은 취소에도 유실·중복시키지 않는다.
- `DriveUploader`·`PrivateDriveUploader` 배치 계약: 현재 exporter가 만드는 입력은 같은 local date의 최대 네 CSV이고 relativePath가 서로 다르다. 이 전제 밖의 신규 업무 규칙은 추가하지 않는다. 모든 파일의 입력 경로·날짜·크기 검증 후 원격 준비를 시작한다. 빈 입력은 원격 요청과 callback 없이 반환한다. 같은 데이터셋은 한 번만 준비하고 파일 입력 순서는 예약 순서로 유지한다.
- `DriveUploader`·`PrivateDriveUploader` 배치 계약: 한 mutex가 root/폴더 준비부터 파일 자식의 완료·취소 정리까지 보호한다. 단일 upsert도 이 mutex를 쓰되 배치 안에서 잠금을 재진입하지 않는다. 루트와 폴더 map은 호출 지역값이다.
- `DriveUploader`·`PrivateDriveUploader` 배치 계약: 두 슬롯 semaphore는 파일 조회부터 검증·callback까지 한 파일 작업 전체를 보호한다. 첫 실패를 관찰하면 대기 파일을 시작하지 않으며 각 슬롯 진입 뒤 취소·실패 상태를 확인하고, 첫 실패 flag를 permit 해제 전에 설정해 대기자가 먼저 시작하는 경쟁을 막는다. 이미 시작한 형제는 취소하고 join한다. 첫 안전한 업로드 오류를 형제 취소로 덮어쓰지 않는다. 외부 취소는 CancellationException으로 전파한다.
- `SyncCoordinator` 집계 계약: coordinator의 uploadedFiles는 직렬 확인 콜백에서만 증가한다. 모든 확인과 batch 정상 반환 뒤 SUCCESS; 오류는 현재 확인 수로 FAILED. DRIVE_TOTAL 종료는 기존 finally 경계이며 각 sync의 확인 수는 지역값이다.
- `DriveUploader`·`PrivateDriveUploader` TLS 비교 계약: serial은 실제 PrivateDriveUploader의 단일 upsert만 노출하는 test adapter가 기본 batch를 사용한다. 기존 baseline도 이 adapter로 기존 12회/순서를 보존한다. optimized는 실제 override를 호출한다. transport production 설정은 변경하지 않는다.

### 결정 로직 위치

- 입력 검증은 PrivateDriveUploader.kt의 IO 없는 private prepare(file: CsvFile)에 둔다(단일·배치 재사용). metadata 검증은 기존 validate/id를 유지한다.
- 확인 수·실패/취소 상태는 별도 정책 클래스를 만들지 않고 SyncCoordinator.sync와 배치의 coroutine 상태 경계에 둔다. 이는 IO orchestration이며 인위적인 pure helper를 추가하지 않는다.
- nearest-rank 계산은 DriveBatchPerformanceTest.kt 안의 IO 없는 private helper로 둔다. 정렬 후 ceil(n*p)-1번째를 선택하며 작은 고정 표본으로 검증한다.

### 관련된 암묵적 동작

- profiling의 operation/request active는 boolean 저장값이 아닌 synchronized 누적 active count에서 계산한다. 동일 operation 중첩을 이미 지원하며 production profiling 수정은 계획하지 않는다. (확인: android/core/src/main/kotlin/com/ny/my/health/core/SyncProfiling.kt:22)
- 실제 OkHttp 취소는 suspendCancellableCoroutine의 invokeOnCancellation에서 call.cancel로 연결된다. (확인: android/app/src/main/java/com/ny/my/health/GoogleDriveTransport.kt:71)
- production client는 retryOnConnectionFailure(false)이며 새 자동 재시도를 추가하지 않는다. (확인: android/app/src/main/java/com/ny/my/health/GoogleDriveTransport.kt:113)
- coroutineScope/async의 형제 실패·취소 전달과 semaphore 대기 취소의 정확한 구현 조합은 아직 실행 검증하지 않았다. (추정) T1은 기존 coroutines 1.10.2에서 작은 결정적 gate 테스트로 확인하며 새 라이브러리 없이 위 배치 계약 안에서 구현한다.

## 3. 추적성

테스트 이름은 아래 영어 문장을 함수 이름으로 그대로 사용한다. ⑤ 과거 버그는 새 결함 신고가 없어 독립 신규 케이스를 만들지 않고 기존 루트·안전성 회귀를 재사용한다.

| 스펙 기준 | 테스트 이름 (출처/층) | 충족 위치 |
|---|---|---|
| 단일 페이지의 기존 private tree에서 서로 다른 데이터셋 3CSV를 업데이트하면 앱 루트 조회 1회·데이터셋 조회 3회·파일 조회 3회·업데이트 3회로 총 10회 요청하며, 기존 순차 기준 12회와 비교할 수 있다. | "Warm batch of three datasets confirms three files with ten requests", "Duplicate relative paths are rejected before any remote request or confirmation" (①·②·③·④/유닛) | T1 완료 기준 |
| warm 단일 CSV는 총 4회, cold 단일 CSV는 새 루트 생성 후 재확인을 포함해 총 7회 요청으로 기존 보호 절차를 유지한다. | "Single warm and cold uploads retain four and seven requests" (①·②·③·④/유닛) | T1 완료 기준 |
| 지연이 있는 서로 다른 파일 작업은 실제로 겹쳐 실행되고 동일 instance의 배치 내 활성 Drive 요청은 언제나 2개 이하이다. | "Delayed file requests overlap with a maximum of two active requests" (①·②·③·④/유닛) | T1 완료 기준 |
| 같은 uploader instance의 배치와 다른 배치 또는 단일 upsert가 겹치지 않으며 cold 동시 진입에서도 그 instance 때문에 앱·동일 데이터셋 폴더가 중복 생성되지 않는다. | "Concurrent cold batches and single calls serialize without duplicate folders" (①·②·③·④/유닛) | T1 완료 기준 |
| 다음 배치는 루트를 다시 조회하므로 앞선 배치 후 공유·이동·삭제·중복 등 안전하지 않은 변경을 감지하고 해당 대상 쓰기를 거부한다. | "Each later batch rejects changed unsafe root before child writes", "Later batches replace filtered moved or trashed roots without reusing prior target IDs" (①·②·③·④/유닛) | T1 완료 기준 |
| 기존 단일 upsert 호출자는 API 변경 없이 동작하고 배치 최적화를 제공하지 않는 기존 fake uploader는 파일을 원래 순서로 하나씩 처리한다. | "Default batch uploader preserves single upsert API and file order" (①·②·③·④/유닛) | T1 완료 기준 |
| 하루 묶음의 모든 파일이 검증된 원격 확인을 반환한 경우에만 SUCCESS와 전체 확인 수를 반환하며 빈 응답 ID나 검증 실패는 성공으로 세지 않는다. | "Coordinator succeeds only after every nonblank verified confirmation" (①·②·③·④/유닛) | T1 완료 기준 |
| 한 파일이 실패하면 새로운 파일 작업을 더 시작하지 않고 이미 시작한 작업의 종료를 정리한 뒤 실제로 확인된 파일만 uploadedFiles에 반영하여 FAILED를 반환한다. | "Batch failure stops queued files and reports only confirmed files after children finish" (①·②·③·④/유닛) | T1 완료 기준 |
| 실패 시 이미 전송된 원격 쓰기를 롤백했다고 주장하지 않고, 확인되지 않은 in-flight 쓰기는 성공 수에 넣지 않으며 안전한 Drive operation/kind/HTTP 분류를 유지한다. | "In flight unconfirmed writes are excluded and safe Drive failure details survive" (①·②·③·④/유닛) | T1 완료 기준 |
| 취소는 CancellationException으로 전파되고 새로운 작업 시작과 실행 중 transport를 취소하며 모든 자식 작업을 종료한 뒤 같은 instance의 다음 호출을 허용하고, 종료 후 늦은 확인 콜백이 결과나 다음 시도의 확인 수를 바꾸지 않는다. | "Cancellation joins children cancels transport and isolates the next invocation" (①·②·③·④/유닛) | T1 완료 기준 |
| 루트·데이터셋·날짜 파일의 소유권·비공유·My Drive·marker·정확한 부모·이름·MIME·ID 검증과 새 루트 멤버십 재확인을 유지하고 불완전 조회·페이지 간 중복·잘못된 pagination을 fail closed로 처리한다. | "Batch preserves private target validation and rejects incomplete duplicate or malformed pages" (①·②·③·⑤/유닛) | T1 완료 기준 및 기존 PrivateDriveUploaderTest 전체 재사용 |
| HTTP 오류·네트워크 실패·취소·비정상 응답에서 자동 POST/PATCH 재전송을 추가하지 않고 토큰·CSV·건강값·원격 ID·공급자 본문 및 예외 원인을 로그·공개 보고에 노출하지 않는다. | "TLS HTTP network malformed and cancelled writes do not replay or expose sensitive errors" (①·③/통합(local TLS)) | T2 완료 기준 및 기존 GoogleDriveTransportTest 재사용 |
| 겹친 요청의 성공·실패·취소 수가 실제 관찰과 일치하고 종료 후 모든 profiling active가 false이며 DRIVE_TOTAL 경과 시간과 누적 operation/request 시간을 구분한다. | "Overlapping completed failed and cancelled requests close all profiling counters" (①·③·④/유닛 및 통합(local TLS)) | T1·T2 완료 기준 |
| EMPTY·무권한·부분 허용·기존 CSV 내용·하루 날짜·수동 동의·별도 계속·로컬 이력 회귀가 기존 의미를 유지한다. | "Existing permission CSV date consent continue and history regressions remain unchanged" (①·②·④/유닛 및 CI instrumentation) | 전체 검증 항목 1·2 |
| 동일 합성 CSV·동일 private tree·동일 주입 지연·동일 TLS transport/연결 예열 조건에서 serial/optimized를 각각 예열 3회 후 최소 10회 성공 측정하여 EXECUTION·DRIVE_TOTAL의 n·p50·p95·요청 수·최대 동시 요청 수를 기록한다. | "Paired delayed TLS samples report execution and Drive distributions with request concurrency" (①·②·④/통합(local TLS)) | T2 완료 기준 |
| 비교용 serial 경로는 기존 단일 upsert의 순차 호출임을 명시하고 병렬 TLS 응답은 요청 경로·대상에 맞게 반환하여 전역 응답 큐의 우연한 순서에 의존하지 않는다. | "Serial adapter uses ordered single upserts and parallel TLS routes by request target" (①·②·④/통합(local TLS)) | T2 완료 기준 |
| 지연 포함 warm 3CSV 비교에서 optimized의 EXECUTION·DRIVE_TOTAL p50과 p95가 serial보다 작고 오류 증가가 없으며, 측정 불안정 또는 역전 시 개선 완료로 판정하지 않고 원인을 보고한다. | "Delayed optimized warm batch lowers both execution and Drive p50 and p95 without failures" (①·②·④/통합(local TLS)) | T2 완료 기준 |
| 실패·취소는 성공 분포와 분리하고 revision·harness·실행 환경·지연·표본 수·백분위 계산법을 기록하여 합성 개선을 재현할 수 있다. | "Failure and cancellation evidence stays separate from successful measured samples" (①·②·④/통합(local TLS)) | T2 완료 기준 |
| 실제 업데이트/생성의 n=1 관찰이나 local TLS 결과로 실제 사용자 p95·초 단위 SLA·개선율·계정 또는 기기 수용 통과를 주장하지 않고 미실행 항목을 명시한다. | — (증거 문서·독립 PR 댓글 조건으로 자동 테스트 불가) | T3 완료 기준 및 전체 검증 항목 3 |
| 별도 세션의 읽기 전용 검토자가 최신 head와 관련 전체 diff를 검토하고 한국어 PR 댓글에 검토 commit·범위·심각도·실제 검증·한계·병합 적합성을 남기며 차단 결함 수정 후 최신 head 재검토를 완료한다. | — (증거 문서·독립 PR 댓글 조건으로 자동 테스트 불가) | 전체 검증 항목 4 |

## 4. 작업

### T1: 배치 업로드의 확인과 종료 경계

- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `android/core/src/main/kotlin/com/ny/my/health/core/Ports.kt` | 수정 | 기존 단일 호출과 호환되는 배치 기본 계약 |
  | `android/core/src/main/kotlin/com/ny/my/health/core/PrivateDriveUploader.kt` | 수정 | 배치 범위 루트와 폴더 준비, 최대 두 파일 작업 |
  | `android/core/src/main/kotlin/com/ny/my/health/core/SyncCoordinator.kt` | 수정 | 배치 확인 콜백 기반 결과와 종료 경계 |
  | `android/core/src/test/kotlin/com/ny/my/health/core/PrivateDriveBatchTest.kt` | 신규 | 배치·동시 진입·보안·실패 회귀 |
  | `android/core/src/test/kotlin/com/ny/my/health/core/SyncCoordinatorTest.kt` | 수정 | 배치 결과·취소·프로파일링 회귀 |

- **인터페이스**: 2절 `DriveUploader`, `PrivateDriveUploader`, `DriveTransport`, `SyncCoordinator`, `SyncProfiling` 참조. 2절 배치·집계 계약 전부 적용.
- **결정 로직**:
  ```
  허용되지 않은 경로·날짜 또는 5MB 초과 → 원격 요청 없이 거절
  공백 확인 또는 원격 검증 실패 → 확인 콜백 없음
  모든 파일 확인 후 정상 종료 → SUCCESS와 전체 확인 수
  일반 실패 → 자식 정리 후 FAILED와 실제 확인 수
  외부 취소 → 자식 정리 후 CancellationException
  ```
- **호출 순서**:
  ```
  1. instance mutex 획득과 입력 검증 → 취소·검증 오류 전파
  2. 앱 루트 조회·검증·필요 시 생성/재확인 → 기존 안전 분류로 실패
  3. 데이터셋별 직렬 조회·검증·필요 시 생성 → 실패 시 파일 시작 없음
  4. 두 슬롯에서 파일 조회와 기존 update/create·확인 callback → 첫 실패 시 신규 시작 금지·형제 취소/join
  5. 모든 자식 종료 뒤 mutex 해제 → 확인된 수로 coordinator 결과, DRIVE_TOTAL finally 종료
  ```
- **작성할 테스트**:
  - "Warm batch of three datasets confirms three files with ten requests" — 신규; 결정적 gate와 합성 metadata로 3절의 조건·결과 검증.
  - "Single warm and cold uploads retain four and seven requests" — 신규; 결정적 gate와 합성 metadata로 3절의 조건·결과 검증.
  - "Delayed file requests overlap with a maximum of two active requests" — 신규; 결정적 gate와 합성 metadata로 3절의 조건·결과 검증.
  - "Concurrent cold batches and single calls serialize without duplicate folders" — 신규; 결정적 gate와 합성 metadata로 3절의 조건·결과 검증.
  - "Each later batch rejects changed unsafe root before child writes" — 신규; 결정적 gate와 합성 metadata로 3절의 조건·결과 검증.
  - "Default batch uploader preserves single upsert API and file order" — 신규; 결정적 gate와 합성 metadata로 3절의 조건·결과 검증.
  - "Coordinator succeeds only after every nonblank verified confirmation" — 신규; 결정적 gate와 합성 metadata로 3절의 조건·결과 검증.
  - "Batch failure stops queued files and reports only confirmed files after children finish" — 신규; 결정적 gate와 합성 metadata로 3절의 조건·결과 검증.
  - "In flight unconfirmed writes are excluded and safe Drive failure details survive" — 신규; 결정적 gate와 합성 metadata로 3절의 조건·결과 검증.
  - "Cancellation joins children cancels transport and isolates the next invocation" — 신규; 결정적 gate와 합성 metadata로 3절의 조건·결과 검증.
  - "Batch preserves private target validation and rejects incomplete duplicate or malformed pages" — 기존 회귀 재사용 및 배치 경로로 동일 조건 보강.
  - "Overlapping completed failed and cancelled requests close all profiling counters" — 신규; 결정적 gate와 합성 metadata로 3절의 조건·결과 검증.
- **완료 기준**:
  - 동일 relativePath 중복 입력은 병렬 원격 생성 경합을 막기 위해 요청 전 거부하고 빈 입력은 요청 없이 종료한다. 다음 배치 root 검증은 malformed 응답 거부와 서버 조회에서 사라진 대상의 ID 미재사용을 구분한다. 기존 cold 생성/재확인 계약을 유지하며 삭제/이동의 독립 실서버 탐지를 주장하지 않는다.
  - 위 테스트와 기존 core 전체 통과. 실패를 앞/뒤 슬롯에서 각각 유발하고 미확인 in-flight·확인 완료·대기 파일을 구별한다. 종료 뒤 callback 수 불변과 다음 호출의 0부터 시작을 검사한다.
  - cold 배치/배치와 배치/단일 동시 진입, 같은 데이터셋 여러 파일, 빈 배치, warm/cold 1CSV, 다음 배치 root 변경(공유·이동·삭제·중복), 중복 페이지·불완전 조회를 검증한다.
  - profiler 성공/실패/취소 합계와 모든 active=false를 검사하며 DRIVE_TOTAL 벽시계와 operation/request 누적 시간을 동일 값으로 강제하지 않는다.
- **검증 방법**:
  ```bash
  cd android
  ./gradlew -PcoreOnly :core:test
  ```
- **주의사항**: 기존 PrivateDriveUploaderTest는 수정하지 않고 전부 재사용한다. 확인: profiling은 synchronized active count를 지원한다(SyncProfiling.kt:22). 추정인 coroutine 조합은 구현 전 읽기 전용으로 확인하고 위임 범위 안에서 구현·보고한다. 최초 컴파일/구현 후 검증 실패는 blocked로 보고하고 복구 지시를 기다린다. 테스트 skip·단언 완화 금지. 실제 remote rollback·다른 instance 배제는 보장하지 않는다. 새 의존성/인터페이스 충돌은 멈춰 보고한다. 빈 ID 실패의 예외 문구는 public 계약이 아니며 기존 FAILED/확인 수/안전한 분류만 보존한다. legacy 테스트의 기대값은 이 근거 밖에서 변경하지 않는다.

### T2: 실제 TLS 전후 분포와 오류 검증

T1 완료 후 시작.

- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `android/app/src/test/java/com/ny/my/health/SyncProfilingBaselineTest.kt` | 수정 | 기존 순차 단일 upsert adapter로 12회 기준선 보존 |
  | `android/app/src/test/java/com/ny/my/health/DriveBatchPerformanceTest.kt` | 신규 | 경로 기반 실제 TLS 비교·오류·취소 |

- **인터페이스**: 2절 `DriveUploader`, `PrivateDriveUploader`, `DriveTransport`, `SyncProfiling` 참조. 2절 TLS 비교 계약 적용.
- **결정 로직**:
  ```
  성공 표본 → 예열 제외 후 경로별 n·정렬 nearest-rank p50·p95 산출
  실패·취소 → 별도 결과, 성공 분포에 포함 금지
  optimized의 두 stage p50·p95 모두 serial 미만이고 오류 증가 없음 → 합성 개선 근거
  역전·불안정 → 원인 보고, 개선 완료 아님
  ```
- **작성할 테스트**:
  - "TLS HTTP network malformed and cancelled writes do not replay or expose sensitive errors" — 신규; 실제 GoogleDriveTransport와 MockWebServer TLS로 3절 조건 검증.
  - "Overlapping completed failed and cancelled requests close all profiling counters" — 신규; 실제 GoogleDriveTransport와 MockWebServer TLS로 3절 조건 검증.
  - "Paired delayed TLS samples report execution and Drive distributions with request concurrency" — 신규; 실제 GoogleDriveTransport와 MockWebServer TLS로 3절 조건 검증.
  - "Serial adapter uses ordered single upserts and parallel TLS routes by request target" — 신규; 실제 GoogleDriveTransport와 MockWebServer TLS로 3절 조건 검증.
  - "Delayed optimized warm batch lowers both execution and Drive p50 and p95 without failures" — 신규; 실제 GoogleDriveTransport와 MockWebServer TLS로 3절 조건 검증.
  - "Failure and cancellation evidence stays separate from successful measured samples" — 신규; 실제 GoogleDriveTransport와 MockWebServer TLS로 3절 조건 검증.
- **완료 기준**:
  - 기존 baseline의 coordinator에는 순차 adapter를 넣어 4/7/12회와 과거 순서 단언을 유지한다. 새 비교는 전역 enqueue 큐를 쓰지 않고 method/path/decoded q/합성 file ID별 Dispatcher 응답을 돌려준다. 병렬 요청 순서는 단언하지 않는다.
  - 합성 warm 3CSV에 각 endpoint 40ms 응답 지연을 동일 주입한다. 두 경로는 동일 fixture·private tree·production client 설정·동등한 연결 예열을 사용한다. 경로별 연결 풀을 유지하고 각각 3회 예열 뒤 10회 이상 성공 측정, 측정 순서를 paired 교대로 진행한다.
  - System.nanoTime 기반 EXECUTION/DRIVE_TOTAL의 n,p50,p95, 요청 수(12/10), 최대 활성(1/2), 실패 수를 보고한다. 겹침은 gate와 thread-safe active 카운터로 확인한다. strict ms·개선율 임계값을 단언하지 않는다. 큰 주입 지연에서 상대 p50/p95 기준은 검사하고 불안정 시 숨기거나 재시도 표본을 선택하지 않는다.
  - 새 harness에 실제 두 in-flight 상태에서 HTTP 503·네트워크 단절·비정상 metadata/본문·취소를 주고, 새 파일 시작 없음·자식 정리·다음 호출 가능·재전송 없음·분류/프로파일 집계를 검증한다. transport 기존 오류/쓰기 재전송 회귀도 전부 재사용한다.
  - 보고서는 android/app/build/reports/drive-batch-performance 아래 합성 집계만 출력한다. revision·dirty·harness SHA256·JDK/OS·지연·warmup/표본·nearest-rank·분리된 실패/취소·연결 조건을 포함한다. 무지연 과거 baseline과 새 지연 분포를 혼합하지 않는다.
- **검증 방법**:
  ```bash
  cd android
  ./gradlew :app:testDebugUnitTest --tests com.ny.my.health.SyncProfilingBaselineTest --tests com.ny.my.health.DriveBatchPerformanceTest --tests com.ny.my.health.GoogleDriveTransportTest
  ```
- **주의사항**: 확인: 실제 transport 취소는 call.cancel로 연결되며 자동 연결 재시도가 꺼져 있다(GoogleDriveTransport.kt:71,113). production transport 수정·의존성 추가는 하지 않는다. 동일한 root/CSV metadata를 경로별 반환하고 합성 body/token을 출력하지 않는다. 실제 계정·기기 지연을 입증했다고 쓰지 않는다. 추정이 틀리거나 검증 실패 시 우회하지 말고 blocked로 보고한다. 단언 완화·skip 금지.

체크포인트: T2 완료 후 전체 JVM·APK·lint와 계정 없는 CI instrumentation을 확인하고 합성 분포 및 실패/취소 결과를 검토한다. 기준 미달이면 T3에 완료라고 기록하지 않는다.

### T3: 실행 증거와 남은 수용 조건 기록

T2 완료 후 시작.

- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `docs/2026-11-05-drive-optimization/execution-report.md` | 신규 | 실행·측정·독립 검토와 미확인 항목 |
  | `docs/implementation-plan.md` | 수정 | 검증된 한정 최적화 진행 상태 연결 |
  | `docs/2026-11-04-top-content-layout/report.md` | 수정 | PR14 상단 표시의 소유자 실기기 확인만 반영 |
  | `docs/backlog/layout-refinement.md` | 수정 | 상단 표시 user-reported PASS와 나머지 기기 미확인 분리 |
  | `AGENTS.md` | 수정 | 2026-11-05 소유자 자동 승인 위임과 한계 기록 |

- **인터페이스**: 없음.
- **결정 로직**: 없음.
- **작성할 테스트**: 신규 없음. 문서 일치 조건은 전체 검증 항목 3·4로 직접 확인한다.
- **완료 기준**:
  - 소유자는 PR14 APK 업데이트 후 최초 진입/상단 복귀에서 제목·날짜 문구가 정상으로 보인다고 답했다. 이 범위만 user-reported PASS로 layout 보고서/백로그에 반영하고 회전/navigation/TalkBack/P0는 미확인으로 유지한다. 원본 화면·건강 값·개인 시각은 복사하지 않는다. 근거 PR14 코멘트6647681407.
  - 실행 보고서에 명령·revision·실제 테스트 수/실패/skip·APK/lint·CI URL/결과·serial/optimized 비교표·분리된 실패/취소·측정 방법과 한계를 기록한다. 미실행 항목은 pending/미확인으로 표시한다.
  - docs/implementation-plan.md는 이 한정 개선의 실제 근거 링크만 추가한다. P0/Galaxy/TalkBack/실계정/더 넓은 V1 체크는 기존 상태를 유지한다.
  - AGENTS.md에 2026-11-05 소유자 위임을 기록한다: V1 범위 spec/plan/implementation/되돌릴 수 있는 수정/독립 검토/검토·검증된 PR 개발 병합 자동 승인. 실제 기기·계정 수용 통과, 실제 background toggle/login, secret 노출 승인은 포함하지 않으며 한국어 PR·fresh-context 독립 검토 의무를 유지한다. 다음 세션은 승인된 범위에 반복 승인 질문을 하지 않는다.
  - 실제 계정의 latency/privacy/same-ID, 다중 instance/기기 생성 경쟁, 검증 직후 공유 변경 한계와 실제 SLA 부재를 기록한다. n=1 관찰과 합성 분포를 구분한다.
- **검증 방법**:
  ```bash
  git diff --check
  git diff -- docs/2026-11-05-drive-optimization/execution-report.md docs/implementation-plan.md AGENTS.md
  ```
- **주의사항**: 실행 안 한 결과를 작성하지 않는다. 공개 보고서에 건강값·원격 ID·계정 링크·토큰·CSV·예외 원인을 넣지 않는다. 독립 리뷰는 아래 전체 검증의 별도 읽기 전용 세션에서 수행한다.

## 5. 전체 검증

1. 저장소 android/에서 아래를 실행하고 결과를 기록한다. 기존 core CSV/날짜/permission, app 수동 동의/별도 계속/로컬 이력 회귀는 전체 suite로 재사용한다. 오래된 증거는 새 실행과 구별한다.
   ```bash
   cd android
   ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
   ```
2. `.github/workflows/android-emulator-tests.yml`의 기존 계정 없는 API35/36 CI를 해당 변경 head에서 실행/확인하고 테스트 결과와 URL을 기록한다. 로컬 emulator/KVM 없음은 CI 결과와 구분하며 기기/계정 검증으로 확대 해석하지 않는다. 기존 UI/일자 회귀는 기존 테스트를 재사용하고 3절의 해당 이름은 보고상의 묶음으로 실제 재사용 클래스/함수 이름을 실행 보고서에 기록한다.
3. T2 생성 집계와 execution-report.md의 모든 수치를 대조하고 3절 각 테스트 이름/실제 함수가 일치하는지 확인한다. 변경 전 `/workspace/tooling/v1-drive-before-baseline-results`는 별도 무지연 과거 증거로만 인용한다. 실패/취소·실계정·미실행 한계가 포함됐는지 확인한다. manifest/dependency/auth/schema/CSV diff에 범위 밖 변경이 없는지도 검토한다.
4. 최신 head와 전체 관련 diff를 작성자/수정자와 별개인 fresh-context 읽기 전용 검토자에게 제공한다. 적합성 검토에는 승인 spec과 구현만 제공하고 plan/과거 결론을 주지 않는다. 한국어 PR 댓글에 검토 commit·범위·심각도·실제 검증·한계·merge 적합성을 남긴다. 차단 결함 수정 후 최신 head 재검토 댓글을 남기고 CI가 통과한 reviewed head에만 위임 범위 내 개발 병합을 진행한다. 별도 세션/댓글은 automated unit test로 대체하지 않는다.
5. 오케스트레이터는 plan task 상태·실제 테스트 이름 추적성·복구/환경 기록을 갱신한다. 이 실행 메타데이터 갱신은 작업별 source 파일 수에 포함하지 않는다. 계획 문서 PR 자체도 AGENTS.md의 별도 세션 독립 검토를 받는다.

## 6. 위험과 롤백

병렬 확인과 실패가 경합하면 확인 수를 잃거나 늦은 callback이 다음 실행에 섞일 수 있어 종료·다음 호출 gate 테스트를 앞 작업에 둔다. TLS 비교는 호스트 부하에 영향을 받으므로 실제 표본과 환경을 남기고 역전 시 완료 판정을 보류한다. 원격 생성/업데이트는 부분 성공 뒤 자동 rollback하지 않는다.

배포 데이터/schema migration은 없다. 개발 커밋 또는 PR을 revert하면 기존 순차 경로로 돌아간다. 이미 확인된 원격 쓰기는 유지하고 사용자가 시작한 다음 시도에서 기존 검증을 거쳐 upsert한다. 앱 설치/실계정 수용은 별도 기존 gate를 유지한다.

## 복구 기록

| 작업 | 회차/2 | 실패 증거·원인 | 새 근거·수정 가설 | 계획 보정 전→후·유지 기준 | 수정 파일·보존 대상 | 검증 명령·결과 | 실행자·다음 조치 |
|---|---|---|---|---|---|---|---|
| T2 | 1/2 | target TLS14개 중 새6개 실패: NETWORK 기대가 RESPONSE_INVALID, empty response+CloseSocket fixture. 성공만 memoize하여 실패 때 campaign 반복 | 실제 nonempty body 중단+단일 HTTP2 stream reset으로 IO 오류를 유발; 설치5.3.0 javap에서 SocketEffect.CloseStream(int)·onResponseBody 확인 | NETWORK 기대값/HTTP·취소·privacy·요청/분포 단언 유지; 실패 Result도 memoize하여 같은 표본 재수집 금지 | DriveBatchPerformanceTest.kt만; production·기존 baseline/transport 전부 보존 | target14개/0실패·오류·skip,43초 PASS; root 전체 검증 대기 | drive_batch_t2 부분 교정 후 root 확인 |
| T1 | 1/2 | root 읽기 검토: batch의 중복 relativePath 입력이 같은 날짜의 병렬 POST를 허용할 수 있고 root 변화 fixture는 malformed 응답과 검색 미노출을 구분하지 않았다. 기존53개 PASS는 이 입력 경계를 검증하지 않음 | 승인된 unique-file 전제와 같은-instance 중복 방지 경계를 요청 전 검증하고 실제 검색 의미의 fixture 추가 | 기존 입력 경로·보안 검사·cold 생성 보존; duplicate-input 거부/filtered-old-ID 미재사용을 보강 | PrivateDriveUploader.kt, PrivateDriveBatchTest.kt; 기존 모든 단언/파일 보존 | prefix 미실행; core55개/0실패·오류·skip, 실행자22초/root7초 PASS | drive_batch_t1 부분 보완 후 root 재검증 |

## 환경 준비 기록

| 원인·근거 | 준비 조치 | 조치 사용/1 | 확인 명령·결과 | 확인 사용/1 | 다음 조치 |
|---|---|---|---|---|---|
