# ADR-012: 자동 attempt와 retry lineage의 내구성 연결

## 상태
Proposed

## 날짜
2026-11-14

## 티켓
2026-11-05-v1-completion

## 배경

ADR-010의 실행 중 T2 전체 core 86개 중 기존 generic SCHEDULED start와 ledgerless scheduled engine 두 회귀가 확인됐다. 순수 core의 trigger, 설정 또는 불투명 retry key는 ledger 소유 여부를 증명하지 못한다. 종료 시 budget owner가 비워지고 opt-out 뒤 설정도 달라지므로 owner scan의 부재는 legacy라는 증거가 아니다. 기존 테스트를 유지하면서 실제 자동 retry의 원자성과 미완료 이력을 보존해야 한다.

## 결정

ADR-010의 한 신규 retry_budget table과 ACTIVE partial unique index에 더해 기존 단수 attempt table에 nullable storage-only `automatic_lineage_id TEXT REFERENCES retry_budget(lineage_id) ON UPDATE RESTRICT ON DELETE RESTRICT`를 추가한다. domain AttemptState, JSON format1, 기존 열과 데이터 의미는 바꾸지 않으며 legacy v1 attempt는 NULL이다. claim은 budget과 link를 원자 기록한다. budget claimVersion이 권위이며 별도 attempt version column은 없다.

순수 `SyncState.validateMutation`에 memory-only `automaticOwnerRequired: Boolean = false`를 추가한다. 기본은 모든 legacy trigger의 generic null-owner 의미와 finished pending을 보존한다. 제공된 owner는 언제나 UUID/version, 현재 attempt id, non-manual trigger 및 유도 retry key를 검증하며 bound intent를 뜻한다. bound 자동 day/confirmation은 owner를 요구하고 ownerless pending은 matching unfinished attempt의 sole-payload superset union만 허용한다. generic 자동 start/finish는 bound context에서 금지하고 manual null 의미는 유지한다. 이 Boolean은 authorization이 아니다. 실제 SQLite private boundary는 positive persisted link와 ledger를 검증해 이를 도출하며 public transact caller는 선택할 수 없다. enabled config의 generic non-manual start도 별도로 차단한다. 실제 enabled worker는 prepare/access 전 전용 claim/finish를 사용하며 default off 및 due 전 외부 호출은 0이다.

Link는 같은 generation, derived retry key, scope, requested scopes, frozen dates와 count를 일치시킨다. unfinished는 ACTIVE budget의 owner가 해당 attempt여야 하고 reverse owner도 같은 unfinished linked attempt를 가리켜야 한다. finished는 owner cleared와 적합한 status/due가 필요하다. missing parent/orphan 및 양방향 link, generation/scope/count/version 불일치는 opt-out 후에도 fail closed한다. attempt ID는 fresh하고 reclaim은 atomically 교체 및 version +1한다. finish/recovery/opt-out은 retained attempt link를 유지한다. gate 아래 eligible manual start는 기존 attempt가 finished일 때만 NULL link 새 row로 교체하며 budget/uncertainty를 보존한다. reset은 FK ON에서 attempt → budget → generation 순이다.

Validated closed v1의 staged copy에서 budget parent를 먼저 만들고 attempt nullable FK를 ALTER, 예정 index와 user_version2를 같은 transaction에 적용한다. v1/v2 exact schema maps를 분리하고 migrated 및 fresh v2의 실제 sqlite_master/foreign_key_list parity, FK/quick check와 reopen을 검증한다. 기존 원본·sidecar/hot-journal 정책을 유지한다. FK disable, deferred FK, side DB 또는 미확인 backup API는 사용하지 않는다. API28 nullable REFERENCES ADD COLUMN은 source 수준의 타당성 평가이며 실제 API28 migration은 미수행이다. minSdk28 제약 및 기존 API35/36 실제 gate를 유지하고 별도 API28 hard gate는 추가하지 않는다.

## 검토한 대안

- trigger만 사용: 단순하지만 기존 scheduled generic 테스트가 실패하고 ledger 연결을 증명하지 못해 기각한다.
- config만 사용: opt-out 이후 기존 bound attempt를 구분하지 못해 기각한다.
- opaque hash와 cleared owner를 사용: 끝난 attempt의 누락된 ledger와 legacy를 구별할 수 없어 기각한다.
- 기존 legacy 테스트 기대값 변경: 호환성 회귀를 감추므로 기각한다.
- 외부 Boolean 또는 side DB: 호출자가 검증을 우회하거나 SQLite 원자성을 잃으므로 기각한다.

## 결과와 비용

Schema migration, exact DDL parity, 양방향 관계 검증 및 transaction CAS 책임이 생긴다. counts, observations, uncertainty와 partial scope 및 기존 retry 정책은 유지된다. orphan과 한쪽 link 누락은 검출하지만 권한 있는 private DB 조작으로 참조를 NULL로 만들고 관련 row도 일관되게 삭제한 상태는 정당한 legacy와 구분하지 못한다. MAC이나 하드웨어 기반 변조 증거는 주장하지 않는다. 실제 migration·rollback·reopen·worker 테스트와 독립 spec conformance 전까지 Proposed다.

## 참고 자료

- [승인 스펙](../2026-11-05-v1-completion/spec.md)
- [공통 계획](../2026-11-05-v1-completion/plan.md)
- [자동화 작업 계획](../2026-11-05-v1-completion/automation-ui/plan.md)
- [대체한 ADR-010](ADR-010-2026-11-13-2026-11-05-v1-completion-automatic-retry-ledger.md)
