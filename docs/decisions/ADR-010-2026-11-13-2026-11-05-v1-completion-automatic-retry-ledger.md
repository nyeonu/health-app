# ADR-010: 자동 retry cohort와 입장 가능 시각을 같은 SQLite journal에 보관

## 상태
Proposed

준비 초안이다. 구현·migration·검증이 수행됐다는 뜻이 아니며 실행 허가를 추가하지 않는다. 기존 ADR의 상태는 변경하지 않는다.

## 날짜
2026-11-13

## 티켓
2026-11-05-v1-completion

## 배경

승인 V1은 허용된 transient 실패만 최초 실행 외 최대 3회 exponential backoff로 재시도하고, 소진은 다음 명시적 trigger까지 visible pending/blocked로 남기도록 한다. periodic과 app-open catch-up은 서로 다른 WorkRequest이므로 WorkManager의 `runAttemptCount`와 각 request의 backoff만으로 공통 실패 batch의 횟수와 다음 실행 가능 시각을 보존할 수 없다.

조사 source `c33467141db12d3953f5c77e5e1e22730d93d424`의 `SqliteSyncStateStore.saveAttempt`는 generation당 마지막 attempt를 교체한다. `SyncExecutionSession`은 새 attempt마다 retriesUsed=0으로 시작하며 source reread 전 revision/UNREAD 값으로 retryKey를 만든다. 현재 SQLite admission은 exact schema `user_version=1`이고 config/domain version도 1이지만 서로 다른 계약이다. 기존 필드 이름만 유지해 새 attempt나 process에서 실패 예산을 초기화하면 횟수 제한을 지킬 수 없다.

manual EXPLICIT_DAY와 automatic SUMMARY/WORKOUT_SCOPED는 별도 scope다. 한 범위의 진행이 다른 범위의 미종결·소진 이력이나 같은 physical target의 unknown create/prior confirmation/REMOTE_STALE를 없애서는 안 된다. 현재 `get(app)` session 캐시는 최초 생성자의 manual reader를 유지하므로 worker의 automatic reader를 추가하는 것만으로 실행 배선이 바뀌지 않는다.

이 ADR은 [automation-ui 준비 계획](../2026-11-05-v1-completion/automation-ui/plan.md)의 persistence 선택을 기록한다. 제품 성공 기준·수용 범위는 확장하지 않는다. ADR-009의 보존·검증된 hot-journal 복구 경계를 유지하면서 지원 schema v2를 명시적으로 도입하는 제안이며, ADR-008의 공용 admission을 reader가 다른 session 객체 사이에서도 유지하도록 구체화한다.

## 결정

기존 `noBackupFilesDir/sync-state.db`에 additive `retry_budget` 테이블과 검증된 v1→v2 staged migration을 제안한다. frozen scope/date cohort의 claim·due 검사·attempt 시작과 finish·소진 blocker를 각각 같은 SQLite transaction으로 묶어, 모든 automatic trigger가 동일한 durable 예산을 소비하게 한다.

### 저장과 입장 계약

- generation+scope+최대 7개 고정 완료 KST 날짜 cohort에 fresh UUID lineage를 부여한다. ledger는 lineage/generation/scope, frozen requested scopes/dates, nullable observed revision digest vector, retriesUsed(0..3), retryNotBefore/retryDelayMillis, claimVersion/ownerAttemptId, 고정 status와 시각만 보관한다. 날짜·scope는 metadata이며 raw 측정값·CSV·token/auth code·account·raw URI/provider body는 ledger에 넣지 않는다. 기존 private binding의 저장 계약을 복제하거나 바꾸지 않는다.
- 최초 claim은 fresh feature/grant/access **metadata만**으로 만든 immutable proposal(expected generation, scopeKey, requestedScopes, frozenDates, attempt metadata/accessFingerprint)을 필수 입력으로 받는다. generation/automatic/config/scope/date bounds를 검증해 lineage와 AttemptState를 한 commit으로 생성한다. 이전 manual attempt나 별도 선행 commit을 암묵 입력으로 쓰지 않는다. 재claim은 저장된 scopes/dates 및 lineage/version CAS가 일치해야 하며 cohort를 교체하지 않는다. 계획 후 grant/scope 변화는 BLOCKED, generation 변화는 STALE이며 새 initial로 우회하지 않는다.
- initial은 retriesUsed=0, 후속 automatic claim은 transaction에서 1씩 소비하며 retry3까지다. 실제 source revision은 관찰값으로 저장하고 미종결 lineage의 count를 초기화하지 않는다. 완료된 cohort의 정상 후속 refresh는 새 batch를 허용한다. WorkRequest UUID/runAttemptCount/process/periodic tick은 예산 정체성이 아니다.
- 허용 transient finish에서 남은 retry가 있으면 다음 retry1/2/3에 각각 30/60/120초 delay와 `retryNotBefore=finishNow+delay`를 attempt 종료와 함께 commit한다. periodic/catch-up 모두 같은 claim transaction에서 `now >= retryNotBefore`와 generation/lineage/version/owner를 확인한다. DB due 사전 확인 후 최종 CAS에서도 재검사한다. due 이전은 DEFERRED이고 attempt/count/claimVersion 변화와 auth/HC metadata·record/remote 호출은 0이다. worker는 `Result.retry()`로 재대기하며 별도 sleep/drain loop나 새 initial은 없다. OS가 더 늦게 실행할 수 있다.
- retry3 실패는 finish와 함께 EXHAUSTED 및 미완료 날짜 RETRY_EXHAUSTED blocker를 기록한다. 미조회 날짜도 revision=null의 visible blocker로 남긴다. 기존 unknown/prior/REMOTE_STALE·확정 파일과 다른 영구 blocker를 보존한다. BLOCKED/EXHAUSTED의 null due는 admission 허가가 아니다. finalize 실패는 fail closed이며 claim 뒤 crash는 소비량을 refund하지 않고 unfinished journal부터 복구한다.
- explicit manual recovery만 eligible ledger 종료와 기존 `reopenManualBlockers`를 같은 transaction으로 묶어 새 initial을 허용할 수 있다. 앱 열기·periodic·catch-up·work 재등록은 reopen이 아니다. 완료 cohort는 due를 해제하지만 새 config/generation을 실패 예산 우회로 쓰지 않는다. 시계 후퇴는 due까지 보수적으로 대기하며 정확한 실행 시각을 약속하지 않는다.
- PK는 lineage UUID, generation FK 및 `(generation,scope_key)`당 ACTIVE 1행의 partial unique index를 제안한다. dates/revision vector JSON은 16KiB 이하, ledger는 기존 DB capacity guard에 포함한 최대 100000행이다. enum/UUID/hash/date/time/count/due·owner 관계를 모든 read/admission에서 검증한다. cap 초과는 STORE_UNAVAILABLE이며 자동 prune/reset은 없다. indexed bounded 조회를 사용하고 날짜별 N+1 또는 전체 JSON rewrite를 추가하지 않는다.

### migration과 원본 보존

1. 원본을 read-only exact v1 schema/content/FK/cap/private binding까지 먼저 검증한다. v1 hot journal은 ADR-009의 raw pair 보존→canary 전체 검증→원본 fingerprint 확인→native rollback 경계를 먼저 거친다. 이 예외를 일반 writable open으로 확대하지 않는다. 정상 fast path에 복구 copy를 추가하지 않는다.
2. canonical path lock 아래 noBackup sibling staging의 exclusive migration owner marker를 획득한다. reset owner와 동시 소유하지 않는다. no-follow descriptor identity/해시·symlink/sidecar 검사를 적용한다. 모든 DB handle을 닫고 validated v1의 미해결 rollback journal/WAL/SHM이 없음을 확인한 뒤 source 전후 fingerprint가 같은 staged copy를 만든다. hot/nonempty journal을 무시한 main-file copy나 Android에 없는 backup API를 가정하지 않는다.
3. stage fsync와 read-only v1 전체 재검증 후 **stage에서만** additive table/index+user_version2를 transaction으로 적용한다. 재열기 v2 전체 검증→durable marker→atomic publish→published v2 재검증을 통과해야 완료다. crash 시 owned marker와 검증된 source/stage fingerprint로만 해당 단계를 재개한다. unknown bytes/owner 불일치는 보존 BLOCKED이며 자동 삭제·reset하지 않는다.
4. 기존 generation/binding/day/dataset/attempt, unknown/prior/gap/시각은 의미 그대로 보존한다. 정상 legacy automatic=false는 empty ledger로 옮긴다. 예기치 않은 validated v1 automatic=true는 과거 budget을 알 수 없어 자동 admission을 차단하고 명시 복구를 요구한다. true를 덮거나 새 initial을 주지 않으며 unfinished manual attempt를 automatic으로 승격하지 않는다.
5. exact v1/v2만 지원하고 unsupported/future schema(3 이상)·malformed v2·손상은 원본 writable open 전에 fail closed한다. v1와 v2 각각의 hot-journal canary가 필요하며 기존 native v1 forensic fixture의 의미를 보존한다. config/domain format1과 SQLite schema2를 혼동하지 않는다.

### 공용 gate와 reader 배선

worker마다 `AutomaticHealthReader`와 새 `SyncExecutionSession`을 만들고 worker engine에도 **같은 automatic reader 인스턴스**를 주입한다. manual ViewModel의 `SyncExecutionSession.get(app)` 캐시는 그대로 manual reader를 사용하며 worker가 이 캐시를 재사용하지 않는다. 두 session은 같은 canonical DB path의 기존 static Entry gate/control/owner/receipts/recovery를 공유한다. unique work만으로 admission을 대신하거나 살아 있는 owner를 timeout으로 탈취하지 않는다. 지원 범위는 single process다.

manual은 MANUAL, automatic은 SCHEDULED/CATCH_UP만 IO 전에 허용한다. `detectPending(trigger = MANUAL)`은 config.automatic에서 trigger를 암묵 선택하지 않는다. manual refresh는 automatic=true여도 MANUAL이고 worker는 명시 자동 trigger를 사용한다. UI restore의 gap 표시는 durable metadata 경계이며 기록 읽기를 시작하지 않는다. gate/claim 뒤 fresh selected-account authorization/about proof와 같은 transport를 사용하고 실제 health records는 그 뒤에 읽는다. 권한/계정 부족은 BLOCKED이고 worker는 interactive UI를 열지 않는다.

## 검토한 대안

### 기존 AttemptState 필드의 atomic carry와 generation당 단일 미종결 cohort

- 장점: 기존 dates/requestedScopes/retryBudgetKey/retriesUsed를 활용하고 table 추가를 줄일 수 있다. 현재 delete/insert 정책 변경으로 구현 가능성 자체를 배제하지 않는다.
- 단점: due/owner/version 및 blocked/exhausted 이력 보존이 추가로 필요하다. 마지막 attempt 한 행에 모든 scope의 미종결 예산을 담을 수 없고, 하나의 cohort가 끝날 때까지 다른 scope도 막으면 scope별 정상 진행을 제한한다. 이전 행을 교체하면 실패 cohort가 새 initial을 얻을 수 있다.
- 기각 이유: 다른 scope의 blocker/복구 이력을 보존하면서 정상 완료 cohort의 후속 refresh를 허용하는 persistence 경계가 필요하다. 기존 행의 hidden ledger JSON은 validation/size 책임을 숨긴다. 단일 cohort 제한이 승인 의미를 모두 유지하는 더 작은 계약으로 입증되면 실행 전 parent/ADR을 재검토한다. 이 대안이 원리적으로 불가능하다고 주장하지 않는다.

### sidecar file/별도 DB/SharedPreferences ledger

- 장점: 기존 DB schema 변경을 줄이고 별도 저장 형식으로 시작하기 쉽다.
- 단점: attempt 시작·day blocker·retry count·due를 기존 SQLite transaction 하나로 commit할 수 없다. crash 중 한쪽만 갱신되면 예산 우회나 소진 표시 누락이 생기며 두 저장소 복구 protocol이 필요하다.
- 기각 이유: 같은 transaction으로 보장할 수 있는 상태를 분산시키는 비용이 더 크다. migration staging marker는 ledger가 아니며 이 대안을 채택하는 근거가 아니다.

### WorkManager runAttemptCount/backoff 또는 unique work만 사용

- 장점: OS scheduler의 기존 재시도 기능을 바로 쓴다.
- 단점: periodic과 one-time catch-up/new request 사이 동일 cohort의 count와 due가 연결되지 않는다. gate는 동시 실행만 막고 순차 조기 재진입은 막지 못한다.
- 기각 이유: 모든 automatic 진입에서 내구성 횟수·지연 계약을 제공하지 못한다. WorkManager backoff는 선택안에서도 OS 지연으로 유지한다.

### 같은 sync-state DB의 최소 retry_budget table과 v2 migration — 선택

- 장점: 기존 attempt/day와 claim·due·소진을 같은 commit으로 묶고 scope별 terminal metadata를 보존한다. 기존 row와 foundation history DB 의미를 바꾸지 않는다.
- 단점: exact schema validator/codec/index/cap/reset FK 순서와 v1/v2 hot-journal·staged publish/crash 검증을 추가해야 한다. v2를 읽지 못하는 구버전으로 단순 downgrade할 수 없다.
- 선택 이유: retry 정책과 visible blocker의 원자성을 한 저장소에서 검증할 수 있고 기존 persistence 경계를 가장 직접적으로 확장한다.

## 결과와 비용

SQLite migration과 복구·reset의 ownership 상호 배제, 모든 입장의 due CAS 및 terminal metadata 검증 책임이 store/session에 생긴다. 제한된 storage와 비정상 migration의 stage/raw 보관 I/O 비용을 감수한다. 원본 보존은 remote write의 rollback을 뜻하지 않는다. opt-out/generation invalidation을 먼저 commit하고 work를 취소하며 이미 발행된 write를 취소로 되돌렸다고 표시하지 않는다.

v2 상태를 v1 코드로 열면 unsupported 차단이 정상이다. schema 번호를 내리거나 ledger를 삭제해서 downgrade하지 않는다. revert가 필요하면 v2 원본을 noBackup에 보존하고 자동 off/명시 복구 안내를 제공한다. v1 백업을 이후 write가 없었던 것처럼 복원하지 않는다.

실행 전 **PR #17 및 SAF의 실제 reviewed merge head 반영→rebase→실제 API 재조사→parent 계약/파일/추적성 및 ADR 관계 통합→parent_rev/spec_rev/source 재고정**이 필요하다. 이후에도 T1의 네 resolved graph OSV·merged debug/release manifest·AAR metadata/compiler/runtime 호환 감사와 실제 WorkManager starter를 모두 통과해야 기능 구현을 시작한다. T17 최종 재감사는 유지한다. 이 ADR 작성은 그 gate 통과가 아니다.

실제 SQLite reopen/rollback fault/process kill, periodic↔catch-up backoff 우회, due 직전/정각/CAS, immutable proposal와 grant/scope 변화, manual-first/worker-first reader 배선과 공용 gate 검증이 필요하다. 구현 및 독립 spec conformance 통과 전 Accepted로 변경하지 않는다. Galaxy/실계정/background grant/Doze/배터리/reboot/force-stop/TalkBack/private signing-key backup은 별도 pending이다. multiprocess·scope 복구 의미 변경·더 작은 안전 저장 계약이 입증되면 후속 ADR로 재결정한다.

## 참고 자료

- [승인 스펙](../2026-11-05-v1-completion/spec.md)
- [에픽 계획 — 실행 전 통합 필요](../2026-11-05-v1-completion/plan.md)
- [automation-ui 준비 계획](../2026-11-05-v1-completion/automation-ui/plan.md)
- [ADR-006: 기존 coverage 모델 기록](ADR-006-2026-11-05-2026-11-05-v1-completion-coverage-store.md)
- [ADR-008: WorkManager와 공용 admission](ADR-008-2026-11-05-2026-11-05-v1-completion-workmanager-runtime.md)
- [ADR-009: 검증된 hot-journal 복구](ADR-009-2026-11-12-2026-11-05-v1-completion-validated-hot-journal-recovery.md)
- [현재 session source](../../android/app/src/main/java/com/ny/my/health/SyncExecutionSession.kt)
- [현재 store source](../../android/app/src/main/java/com/ny/my/health/SqliteSyncStateStore.kt)
- [현재 exact schema admission](../../android/app/src/main/java/com/ny/my/health/SyncStateDatabase.kt)
