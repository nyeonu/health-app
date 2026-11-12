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

## 2026-11-12 수동 경로 구체화 (Proposed 유지)

PR16 merge `4b98aee1119ded1f1c6553e92198ac004d30fa87` 이후 manual-drive 초안은 기존 승인 V1/소유자 자동승인 위임 아래 root의 구체 검토 대기다. picker는 항상 표시하고 com.google 타입만 허용하며 current ticket/RESULT_OK/AccountManager의 authAccount·accountType 값을 UTF-8 1..1024bytes와 control/encode 검증 후 사용한다. SDK Account 생성은 production bridge로 제한한다.

AuthorizationResult에는 사용자 계정 정보 보장이 없으므로 setAccount만으로 응답을 신뢰하지 않는다. 추가 scope 없이 동일 access token으로 Drive about.get `fields=user(emailAddress,me)`를 조회하여 me=true와 선택 name의 정확 문자열 일치를 확인한다. 누락/불일치/오류/지연 generation 또는 ticket는 raw Health Connect read 전에 차단한다. 별칭 정규화는 하지 않는다. drive.file이 공식 about.get 허용 scope라는 API 근거와 실제 계정에서 필드가 항상 존재한다는 보장은 구별한다.

credential은 controller의 private 메모리에만 두고 StateFlow/SavedState/DB/toString/log에 전달하지 않는다. 공용 gate owner가 per-run prepare로 fresh proof를 확인한 뒤 local engine을 만들며 registry에 token-bound engine을 보관하지 않는다. busy 호출은 trigger만 병합하고 두 번째 credential을 소비/교체하지 않는다. 외부 consent 복귀는 자동 upload를 실행하지 않고 별도 continue를 요구한다.

선택 이유는 기존 drive.file과 승인 account binding을 유지하면서 raw read 이전 현재 Drive principal을 확인하기 위해서다. 추가 identity scope/새 로그인/backend/offline token 또는 cached SDK account를 신원으로 사용하는 대안은 승인 범위·최소 권한/증거 조건에 맞지 않아 채택하지 않는다. 실제 계정의 about population, 계정 제거/별칭/consent와 Galaxy lifecycle 수용은 pending이다. 구현·독립 적합성 검증 전이므로 Accepted가 아니다.

공식 근거: [about.get scopes/fields](https://developers.google.com/workspace/drive/api/reference/rest/v3/about/get), [About.user](https://developers.google.com/workspace/drive/api/reference/rest/v3/about), [User.emailAddress/me](https://developers.google.com/workspace/drive/api/reference/rest/v3/User), [authorization 사용자 정보 제한](https://developer.android.com/identity/authorization).
