# ADR-006: 날짜별 coverage와 generation journal을 별도 SQLite에 보관

## 상태
Proposed

## 날짜
2026-11-05

## 티켓
2026-11-05-v1-completion

## 배경
기존 foundation history는 계정에 연결된 coverage가 아니며 HistoryDatabase의 schema와 reset 계약이 이미 구현되어 있다. 새 기능은 destination generation/date/dataset/revision과 중단·확인 상태의 원자적 변경 및 실제 재열기 증거가 필요하다.

소유자의 “병목 개선부터 V1 끝까지 자동승인” 위임을 근거로 approved 에픽 계획의 결정 시점에 기록한다. 아직 구현/적합성 검증을 마치지 않았으므로 Accepted가 아니다.

## 결정
noBackupFilesDir의 별도 sync-state.db versioned SQLite에 config/generation/day/dataset/attempt를 보관하고 generation/attempt CAS transaction으로 confirmation을 적용한다. category별 요청 scope의 canonical SHA-256 key를 day/attempt/revision/file confirmation에 포함하고 요청 범위와 실제 읽기 범위를 구분한다. day PK=(generation,date,scopeKey), dataset PK=(generation,date,scopeKey,dataset)로 동일 날짜의 profile을 공존시켜 전경 EXPLICIT_DAY와 자동 SUMMARY/WORKOUT_SCOPED coverage를 합치거나 덮어쓰지 않는다. single-process 공용 execution gate는 이 journal과 함께 사용하며 기존 history DB를 migration하지 않는다.

## 검토한 대안
### 기존 history DB 확장
- 장점: DB 수가 줄어든다
- 단점: 기존 strict schema/reset와 과거 증거의 의미를 바꾼다
- 기각 이유: foundation 이력과 새 destination coverage를 분리하기 위해 기각

### 전체 상태 JSON 단일 봉투
- 장점: 한 번의 replace로 구현이 단순하다
- 단점: 날짜가 늘어날수록 전체 rewrite와 크기 상한 비용이 커진다
- 기각 이유: 날짜/파일별 조회와 변경에 맞는 SQLite 행을 선택

## 결과와 비용
이전 generation 결과를 보존하기 위해 immutable generation 부모와 config active pointer를 분리한다. day/attempt FK는 generation, dataset FK는 exact-scope day를 가리킨다. 계정 변경은 새 부모 생성 후 active pointer CAS이며 이전 결과는 보존하고 이전 private binding은 archive하지 않는다. 명시 reset은 새 tombstone pointer로 전환한 뒤 자식 우선 삭제하여 FK를 유지한다. 현재 조회와 callback은 active pointer를 검사하고 이전 기록은 현재 coverage에 사용하지 않는다.

현재 scope로 완료한 날짜를 oldest 후보 LIMIT보다 먼저 제외한다. strict 5종 EXPLICIT_DAY summary와 exact profile의 scopeCheckedThrough/scopeUploadedThrough·pending/blocked counts·excludedTypes를 분리하여 partial/자동 profile이 full-five 미완료 날짜에 계속 막히지 않게 한다. 요청 scope가 실행 중 BLOCKED로 바뀌면 작은 성공 profile로 재명명하지 않고 원래 요청 key 아래 미확인으로 보관한다.

원격 dataset/date.csv은 profile 사이에서 공유된다. 실제 쓰기 직전 transaction에서 같은 generation/date/dataset의 다른 profile 파일 current를 INDETERMINATE로 바꾸고 priorConfirmation과 checked/revision 이력은 보존한다. 이후 해당 profile의 새 source read와 target reconciliation 전에는 업로드 현재성을 복구하지 않는다. 이는 source deletion이나 원격 rollback을 의미하지 않는다.

READY configuration=null은 새 generation·자동 off·빈 coverage를 가진 durable reset tombstone이다. ABSENT/error의 state=null과 구분하고 tombstone 재구성에도 generation CAS를 적용한다. 위 내용은 구현 전 최초 계획 검토의 설계 명확화이며 구현 장애를 수리하거나 실행 복구 회차를 사용한 기록이 아니다.

별도 DB lifecycle·codec·명시 reset/failure injection을 관리해야 한다. unknown/corrupt/미완료 reset은 fail closed하며 자동 새 DB로 덮지 않는다. hash와 시각·확인 metadata만 저장하고 raw/CSV/token을 staging하지 않는다. remote write는 DB rollback과 함께 취소되지 않는다. multi-process 또는 remote delete를 도입하면 새 결정이 필요하다.

## 참고 자료
- [승인 스펙](../2026-11-05-v1-completion/spec.md)
- [에픽 계획](../2026-11-05-v1-completion/plan.md)
