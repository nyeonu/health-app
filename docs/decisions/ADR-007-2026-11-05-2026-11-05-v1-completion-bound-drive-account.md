# ADR-007: 사용자가 선택한 Google 계정에 모든 Drive 권한을 고정

## 상태
Proposed

## 날짜
2026-11-05

## 티켓
2026-11-05-v1-completion

## 배경
기존 전경 authorize는 명시적 account binding이 없고 token/동의는 메모리 전용이다. 날짜별 coverage를 다른 계정에 재사용하면 잘못된 성공과 의도하지 않은 쓰기를 만들 수 있다. 설치 auth21.4.0 setAccount와 basement18.5.0 AccountPicker API를 javap로 확인했다.

소유자의 “병목 개선부터 V1 끝까지 자동승인” 위임을 근거로 approved 에픽 계획의 결정 시점에 기록한다. 아직 구현/적합성 검증을 마치지 않았으므로 Accepted가 아니다.

## 결정
전경 AccountPicker로 고른 com.google Account의 최소 name/type을 private backup-excluded 구성에 보관하고 authorization마다 setAccount를 적용한다. worker는 실행마다 fresh authorize하고 token/scope/resolution/account를 검사하며 interactive UI나 credential persistence를 사용하지 않는다.

## 검토한 대안
### 기본 적격 계정에 authorize
- 장점: 현재 전경 구현 변경이 적다
- 단점: 복수 계정/제거/재선택 때 coverage의 소유 목적지가 불명확하다
- 기각 이유: 다른 계정으로의 silent fallback을 방지하기 위해 기각

### refresh token을 저장하는 offline/backend flow
- 장점: 백그라운드 credential 교환을 별도 제어할 수 있다
- 단점: 새 secret 저장·backend·권한 범위와 복구 비용이 생긴다
- 기각 이유: 승인 범위 밖이며 token 영속 저장 금지에 따라 기각

## 결과와 비용
실제 계정의 silent authorization을 보장하지 않는다. resolution·missing token/scope·account 오류는 BLOCKED이며 전경 명시적 복구가 필요하다. 변경/제거는 새 generation과 동의를 요구하고 늦은 ticket 결과는 거절한다. name/type도 로그·fixture·공개 artifact에서 제외한다. 계정 로그인/시스템 동의 수용은 소유자 수행 대기이다.

## 참고 자료
- [승인 스펙](../2026-11-05-v1-completion/spec.md)
- [에픽 계획](../2026-11-05-v1-completion/plan.md)
- [Google authorization](https://developer.android.com/identity/authorization)
- [AccountPicker](https://developers.google.com/android/reference/com/google/android/gms/common/AccountPicker)
