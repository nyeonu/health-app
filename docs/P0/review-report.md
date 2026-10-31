# 리뷰 요약

**판정: APPROVE — P0 구현, source HEAD `4828485`**

독립 스펙 재검증 후 읽기 전용 품질·보안 리뷰를 수행했다. 두 리뷰어 모두 소스를 수정하지 않았다. 실제 기기·계정 통합이나 production 준비 완료를 의미하지 않는다.

## Critical

없음.

## Important

남은 항목 없음. 최초 품질 리뷰에서 발견한 전경 유지 중 KST 자정 이후 이전 날짜를 재사용하는 문제는 수정됐다. 실제 수동 실행은 현재 clock으로 전일을 선택하는 `SyncCoordinator.syncYesterday`를 사용하고, 선택 날짜가 UI·HealthReader·CSV 경로·성공 기록에 연결된다. 같은 coordinator에 주입된 clock을 자정 전후로 전진시키는 회귀 테스트가 두 날짜의 실제 읽기와 업로드 경로를 검증한다. 최종 품질 리뷰는 해당 수정 diff를 직접 재확인하고 승인했다.

## Suggestion

- `android/app/src/main/java/dev/nyeonu/healthapp/HealthConnectReader.kt:61`: 심박 sample budget을 페이지 수집 도중 계산하면 초과 입력을 더 일찍 거부할 수 있다. 현재는 page/record 상한 아래에서 모두 수집한 뒤 nested samples를 센다. 실제 메모리 장애를 측정하거나 재현하지 않았으므로 P0 차단 문제로 판정하지 않았다. 대량 입력/실기기 테스트와 함께 후속 개선한다.

## 검증 확인

- 테스트를 먼저 읽고 전체 구현·설정·규범 문서를 검토했다. 최종 JUnit XML은 18개·실패 0·오류 0·skip 0이다.
- 독립 검증자의 전체 52개 작업 재실행으로 APK/Lint 통과, Lint 오류 0·경고 8을 확인했다. 리뷰어는 불필요한 광범위 재빌드를 반복하지 않았다.
- 필수 참고자료: local reasoning, backend/frontend performance, security checklist. 성능 수치 개선은 주장하지 않았다.
- 실제 HC·UI/lifecycle·Google OAuth/Drive·ChatGPT 내용 검증은 미실행이며 별도 handoff에 남아 있다.

# 보안 리뷰

**판정: APPROVE — current P0 source HEAD `4828485`**

Google `AuthorizationClient`의 `drive.file` 단일 scope, 메모리 한정 토큰, 동의 이후의 명시적 전경 실행, 읽기 직전 HC 권한 재확인, HTTPS endpoint allowlist·redirect 거부·응답 크기 제한·오류 redaction을 확인했다. Drive 대상은 parent·소유권·app marker·MIME·unshared/untrashed·shared-drive 부재 조건을 검증한다. 날짜 수정 이후에도 전경·권한·취소 보호가 유지됨을 수정 diff로 재검토했다.

공개 Git tree/history의 제한된 credential-pattern 검사에서 개인 export·credential·서명 키를 발견하지 못했다. 패턴 검사는 모든 종류의 시크릿 부재를 증명하지 않는다.

## 의존성 잔여 위험

OSV는 transitive Guava 31.1-android에서 LOW temporary-directory disclosure, MODERATE FileBackedOutputStream, HIGH native collection deserialization 메모리 고갈 경고를 반환했다. 의존성은 Health Connect 경로로 유입된다. 앱 입력은 typed HC API와 HTTPS JSON이며 공격자 제어 native Java serialization 입력 경로는 발견하지 못했다.

보안 리뷰어의 제한된 bytecode-reference 검사에서 HC client 852 classes, HC generated-proto 146 classes, coroutines-guava 9 classes에는 취약 API/ObjectInputStream 참조가 없었다. Shaded protobuf 523 classes의 ByteString 세 클래스에는 ObjectInputStream 참조가 있었다. **전체 transitive graph가 도달 불가능하다고 입증한 것은 아니다.** 배포 전 호환 패치/도달성 검토와 2026-11-30 재조회 조건을 유지한다. 무취약점 판정이나 production 승인으로 해석하지 않는다.

## 미검증 및 참고자료 한계

- 실제 권한 철회·전경 이탈, 계정 동의/전환, private Drive 갱신·공유 상태 경쟁 및 ChatGPT 내용 접근은 미검증이다.
- 중단 시 원격 파일이 일부 남거나 in-flight 쓰기 결과가 불명확할 수 있다. 문서의 수동 확인/재실행 절차가 필요하다.
- Security checklist의 OWASP metadata는 현재 source SHA와 달라 `needs_review`다. 이 앱은 비밀번호 저장 정책을 도입하거나 해당 기본값을 적용하지 않았다.
