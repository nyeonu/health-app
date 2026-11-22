# 1.0 후보 보안·의존성 검토

검토 입력은 `48398565082262ce33a06e65a45b681058b75641`이다. 후속 T1 교정5는 store의 revision 관측 조건과 실제 SQLite 회귀만 변경하며 의존성·manifest·인증·개인정보 안내 입력은 동일하다. 이 문서는 개발 후보의 제한된 정적 검토이며 실기기·계정 수용 또는 공개 출시 승인이 아니다.

## 확인 결과

- 실제 Gradle resolved Maven 좌표: release 129, debug 130, JVM 135, AndroidTest 110, 중복 제거 155, unresolved 0. 새 OSV querybatch HTTP 200/155개 응답에서 매치 0·오류 0이다. [원본 좌표·입력 해시·응답 해시](dependency-audit.json)를 보존했다. 미보고 취약점·비 Maven 구성요소·실제 도달 가능성까지 입증하지 않는다.
- debug 및 release merged manifest에서 versionName 1.0.0/code 2, backup·cleartext 비활성, 수동 5종+별도 background 읽기 권한, WorkManager initializer/diagnostics 제거, Startup provider 비공개를 확인했다. 건강 쓰기·과거 전체 읽기·exact alarm·배터리 제한 예외 권한을 추가하지 않았다. release 확인 명령은 `:app:processReleaseMainManifest`이며 성공했다.
- 앱 전용 no-backup DB, 고정 계정의 `drive.file`, 동일 token principal proof, 메모리 내 credential, coroutine 취소와 durable generation 재검사를 정적으로 확인했다. 실제 계정 동작·모든 경쟁 상태의 부재를 보장하지 않는다.
- 개인정보 안내는 명시적 opt-in 자동 Drive, 별도 background 권한, OS 지연, 철회·reset·보존 범위를 설명한다. 새 analytics/crash SDK는 없다.
- 도달 가능한 Git patch history에서 private-key header/AWS access-key/Google API-key/GitHub classic-token 네 형식은 매치 0이다. 포괄적 entropy·binary·폐기된 history 검사가 아니며 비밀 값은 기록하지 않았다.
- 보안 스킬의 password-storage 참고 upstream pin은 변경되어 참고자료 유지보수가 필요하다. 이 앱은 비밀번호 저장 결정을 추가하지 않았다.

## 남은 검증

최종 head 전체 CI·격리 host 원본·배포 artifact 검사 및 독립 PR 검토는 별도 gate다. 실제 Galaxy/Health Connect/Google 계정·Doze·재부팅·저전력·TalkBack·서명키 별도 개인 백업의 기존 미확인은 그대로 둔다. CI secret 설정은 개인 백업 확인을 대신하지 않는다.
