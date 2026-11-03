---
status: measurement_implemented_device_pending
requested_by: user
scope: profiling and evidence-based sync optimization
---

# 수동 동기화 지연 조사

2026-11-03: 승인된 측정 범위의 단계별 진단과 합성 local TLS 기준선이 구현되었다. 구현·테스트 소스는 `2ccd1d5bbca1a3a2210a39f2f867c7ba03451a53`다. [실행 보고](../2026-11-02-sync-profiling/execution-report.md), [합성 기준선](../2026-11-02-sync-profiling/baseline.md), [실제 기기 절차](../2026-11-02-sync-profiling/device-profiling.md)를 함께 본다. 최종 독립 리뷰/현 head 판정과 실제 기기 측정은 별도이며 전체 완료나 머지 가능을 이 문서로 선언하지 않는다.

## 확인된 근거와 한계

기존 화면의 약 11초 간격은 인증·사용자 대기·읽기·CSV·Drive·이력을 포함할 수 있는 UI 간격이다. 업로드 단독 지연이나 병목 근거가 아니다. private 시각과 건강 값은 보관하지 않는다.

동결한 합성 기준선은 revision `2ccd1d5`, dirty=false, harness SHA-256 `baba4dcbde9ebebc000b001ab033c31488e34ba200fafe18410dd2798000f6a9`의 결과다. 조건별 준비 3회를 제외한 10회에서 warm 1 CSV는 4요청, cold 1 CSV는 7요청, warm 3 CSV는 12요청이다. EXECUTION p50/p95는 각각 12.209432/30.028564ms, 11.012485/32.668145ms, 14.939481/21.839164ms다. 이는 합성 reader와 루프백 TLS의 앱 실행 경계이며 실제 Health Connect, OAuth, Google 네트워크, 이력 저장을 포함한 기기 종단 측정이 아니다. cold가 더 빠르게 보이는 값으로 실제 생성이 갱신보다 빠르다고 결론 내리지 않는다.

HTTP 503 실패 1건과 reader 취소 1건은 성공 분포에서 분리했다. Drive 업무 operation과 transport request를 구분하고 동일 파일 갱신·private 검증·취소·오류·로컬 이력 보장의 회귀 검증은 실행 보고에 기록한다. 단계들은 중첩되므로 합산하지 않는다. CPU/메모리/배터리 및 실제 기기 p50/p95는 아직 없다. Galaxy/계정/TalkBack과 기존 P0 미확인 항목은 그대로다.

## 다음 판단과 최적화 후보

- 동일 조건의 실제 기기 측정에서 인증/계속 대기와 실행을 분리하고 건강 읽기, 정규화/CSV, Drive 조회/전송, 로컬 이력 중 지배 구간을 확인한다.
- 반복 메타데이터 조회 축소, 변경 없는 파일 재전송 회피, 배치, 제한된 병렬화는 미선택 후보로 유지한다. 합성 4/7/12요청 모양만으로 병목·예상 개선율을 확정하지 않는다.
- 성능 목표·개선 방식·동시 요청 수는 기기 기준선과 별도 승인 후 정한다. 새 의존성·OAuth scope·외부 telemetry·영속 진단도 이번 범위가 아니다.
- 선택한 변경은 같은 조건과 기준선으로 비교하고 정확성 회귀를 유지한다. 실제 건강 데이터, CSV, 원시 trace/log를 공개하지 않는다.

## 유지할 보장

매번 갱신한 권한 확인, private-owner 대상 검증, 같은 날짜 파일 동일성, 명시적 부분 결과, 암묵적 쓰기 재시도 금지, 취소 전파, 페이지/샘플 상한, 계정 분리, 오류 비식별화와 로컬 이력 내구성을 유지한다. 넓은 원본 읽기가 상한에 걸리면 실패를 숨기거나 잘라낸 하루를 성공으로 부르지 않는다. 병렬화 후보도 중복 생성, 성공 오표기, 검증 우회를 허용하지 않는다. 이 backlog는 최적화 구현 승인이나 기존 P0 gate 종료 근거가 아니다.
