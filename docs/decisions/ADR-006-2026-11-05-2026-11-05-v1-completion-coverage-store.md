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
noBackupFilesDir의 별도 sync-state.db versioned SQLite에 config/day/dataset/attempt를 보관하고 generation/attempt CAS transaction으로 confirmation을 적용한다. category별 EXPLICIT_DAY/SUMMARY/WORKOUT_SCOPED/BLOCKED 범위를 revision과 확인 metadata에 보관하여 자동 상세 심박과 전경 대상 조회 coverage를 합치지 않는다. single-process 공용 execution gate는 이 journal과 함께 사용하며 기존 history DB를 migration하지 않는다.

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
별도 DB lifecycle·codec·명시 reset/failure injection을 관리해야 한다. unknown/corrupt/미완료 reset은 fail closed하며 자동 새 DB로 덮지 않는다. hash와 시각·확인 metadata만 저장하고 raw/CSV/token을 staging하지 않는다. remote write는 DB rollback과 함께 취소되지 않는다. multi-process 또는 remote delete를 도입하면 새 결정이 필요하다.

## 참고 자료
- [승인 스펙](../2026-11-05-v1-completion/spec.md)
- [에픽 계획](../2026-11-05-v1-completion/plan.md)
