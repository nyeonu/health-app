# ADR-009: 원본 pair를 보존하고 검증한 뒤 SQLite crash rollback 허용

## 상태
Proposed

## 날짜
2026-11-12

## 티켓
2026-11-05-v1-completion

## 배경
PR #16의 별도 코드 검토에서, 대량 reset이 cache spill한 뒤 프로세스가 종료되면 읽기 전용 admission이 SQLite의 정상 rollback까지 막을 수 있음을 발견했다. 데스크톱 SQLite의 DELETE/FULL·FK RESTRICT·20,000행 프로세스 종료로 재현했으며 Android 실제 spill/수정 전후 검증은 아직 대기다. 모든 read를 쓰기 가능하게 바꾸면 미지원/손상 원본을 먼저 변경할 수 있어 기존 보존 계약에 맞지 않는다.

소유자의 기존 V1 자동승인 범위에서 원자적 복구 요구를 충족하기 위한 기술 보정이다. 실제 기기 수용이나 새로운 계정·권한 승인을 뜻하지 않는다.

## 결정
ADR-006의 별도 noBackup SQLite·exact scope 모델·generation CAS·기존 reset 결정을 승계하고, 정상 READONLY fast path를 유지한다. 실제 typed readonly 실패와 엄격한 단일 DELETE pair 조건에서만 raw pair를 보관하고 별도 work pair를 SQLite로 rollback·전체 검증한 뒤, 원본 identity/hash가 그대로일 때 원래 경로의 native rollback을 허용한다.

지원 schema/metadata로의 애플리케이션 repair·upgrade·재생성·empty reset은 하지 않는다. 원본을 SQLite가 변경하기 전에 durable raw 생바이트 pair를 남기며, 실패·unknown 입력의 원본은 변경하지 않는다. 성공한 active DB는 rollback 결과이므로 변경 전과 생바이트가 같다고 주장하지 않는다.

## 검토한 대안
### 모든 read를 READWRITE로 열기
- 장점: native rollback에 바로 도달한다.
- 단점: schema와 canonical metadata를 확인하기 전에 미지원/손상 원본을 바꿀 수 있다.
- 기각 이유: 원본 보존과 fail-closed admission을 유지할 수 없다.

### journal 삭제 또는 빈 DB로 자동 초기화
- 장점: 사용 불가 상태를 빨리 해소한다.
- 단점: 원래 committed 상태와 복구 증거를 잃거나 확인되지 않은 결과를 꾸밀 수 있다.
- 기각 이유: SQLite 원자성과 명시적 reset 경계를 위반한다.

### 일반 read는 계속 막고 새 수동 복구 기능 추가
- 장점: 일반 조회의 비변경 규칙을 절대적으로 유지한다.
- 단점: 정상 crash rollback에도 새 UI/호출 흐름이 필요하고 기존 자동 catch-up의 복구 도달성이 남는다.
- 기각 이유: 현재 범위에서는 검증된 SQLite rollback만 허용하는 내부 경계가 더 작다.

## 결과와 비용
복구는 canonical path lock에서 수행하고 no-follow FD의 inode/device/size/hash를 재확인한다. WAL/SHM·ATTACH/super-journal·symlink/hardlink·불명 header는 대상이 아니다. SQLite 공식 footer 규칙상 EOF magic이 같으면 외부 master/super-journal 참조 가능성이 있으므로, 길이/checksum과 무관하게 전부 거부한다. private work directory 자체를 filesystem sandbox라고 간주하지 않는다.

schema/FK/quick_check뿐 아니라 Store의 private binding·attempt·모든 active profile/leaf 검증을 work와 original 양쪽에서 수행한다. source 검증 전에는 원본을 writable로 열지 않는다. native pager replay는 SQLite가 담당하며 앱이 journal을 편집·삭제하지 않는다. 정상 경로에는 recovery copy나 writable open이 없다.

raw 보관본은 noBackup 안에 남기며 동일 pair fingerprint의 완전한 보관본은 엄격히 재검증해 재사용한다. 불완전 준비물은 active로 채택하지 않는다. work는 자기 소유와 모든 entry를 입증한 경우만 정리한다. raw 보관과 두 번의 검증은 비정상 복구 시 I/O·저장공간 비용이며, 공간 부족이면 원본을 보존하고 실패한다. 새로운 자동 보존기간/정리 UI는 도입하지 않는다.

실제 Android 20,000행 force-stop, 지원/미지원·canonical 손상·외부 footer·파일 종류·취소/재시작 회귀와 최종 독립 스펙/코드/보안/성능 검토를 통과하기 전에는 Accepted로 바꾸지 않는다. upstream footer 규칙의 확인과 실제 Galaxy/vendor 수용은 구별한다.

## 참고 자료
- [승인 스펙](../2026-11-05-v1-completion/spec.md), [에픽 계획](../2026-11-05-v1-completion/plan.md), [작업 계획](../2026-11-05-v1-completion/core-store/plan.md)
- [PR #16 Q1 검토](https://github.com/nyeonu/health-app/pull/16#issuecomment-6812410205)
- [SQLite locking: hot journal rollback](https://www.sqlite.org/lockingv3.html#dealing_with_hot_journals)
- [SQLite 파일 복사와 journal 보존](https://www.sqlite.org/howtocorrupt.html)
- [SQLite 3.22.0 readMasterJournal](https://github.com/sqlite/sqlite/blob/version-3.22.0/src/pager.c#L1307), [3.46.0 readSuperJournal](https://github.com/sqlite/sqlite/blob/version-3.46.0/src/pager.c#L1300)
