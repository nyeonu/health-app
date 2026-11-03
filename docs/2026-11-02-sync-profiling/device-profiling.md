# 실제 기기 수동 동기화 측정 절차

이 절차는 아직 실행하지 않았다. Galaxy 실제 기기·계정·Google OAuth·Health Connect·TalkBack·기존 P0 수용 결과는 합성 JVM/local TLS 결과로 대체하지 않는다. 공개 저장소에는 실제 건강 데이터, CSV, 화면 덤프, 계정/기기/Drive 식별자, 원시 로그를 넣지 않는다.

## PC가 없을 때의 새 수집 경로

새 디버그 APK에서는 수동 동기화 후 **성능 진단 보기 → 안전한 진단 요약 복사 → 고정 24행/6개 숫자 열 확인 → 사용자가 직접 전달**할 수 있다. [휴대폰 수집 절차](../2026-11-03-phone-profiling/device-validation.md)에 복사 실패 안내와 최근 1건·화면 모델/프로세스·시스템 클립보드의 서로 다른 수명 한계를 기록했다. 앱 내 진단 삭제가 이미 복사한 클립보드까지 지우지는 않는다. 새 기능의 합성 검증은 [실행 보고](../2026-11-03-phone-profiling/execution-report.md)에 있으며 실제 Galaxy 수집/전달·기기 클립보드·실측 p50/p95는 여전히 미확인이다. 아래 기존 ADB 절차와 해석은 대안으로 유지한다.

## 준비와 반복 조건

1. 검증할 소스 revision, debug APK 여부, 설치 버전과 서명 일치 여부를 비공개 작업 기록에 확인한다. 측정 revision은 합성 기준선의 `2ccd1d5bbca1a3a2210a39f2f867c7ba03451a53`와 구별해 적는다. release에서 진단을 활성화하거나 새 권한·scope·외부 수집기를 추가하지 않는다.
2. 같은 기기, 앱/OS 버전, 전경 유지, 권한 집합, 보고 날짜, 데이터량 범주, 네트워크 조건과 폴더/파일의 기존 존재 여부를 고정한다. OAuth 재동의/이미 승인됨, 첫 생성/동일 파일 갱신, 성공/실패/취소는 서로 다른 조건으로 기록한다. 날짜가 바뀌거나 권한·데이터가 변하면 별도 조건으로 분리한다.
3. 반복 횟수와 준비 실행 제외 수를 수집 전에 정한다. 동일 조건의 모든 시도를 기록하고 성공만 골라 실패/취소를 숨기지 않는다. 합성 기준선은 조건별 준비 3회 제외 후 10회이며 기기 실측과 동일한 모집단은 아니다.

## 비공개 수집

앱은 debuggable일 때만 `SyncProfiling` 단일 tag에 시도별 고정 범주의 요약을 한 번 출력한다. 전경에서 Google 권한 확인 후 명시적으로 ‘업로드 계속’을 누르고 종결까지 관찰한다. 인증 거부·취소·화면 이탈은 각 조건의 결과로 남긴다. 로그 출력은 최선 노력이며 진단 출력 실패는 업무 결과를 바꾸지 않는다. 종료/프로세스 사망 시 요약 자체가 없을 수 있으므로 누락을 0시간 또는 성공으로 간주하지 않는다.

필요하면 개인 PC의 접근 제한된 비공개 임시 디렉터리에서 `adb logcat -v raw -s SyncProfiling:D '*:S'`로 해당 tag만 관찰한다. 전체 logcat, bugreport, 네트워크 본문, CSV, 스크린샷을 수집하거나 저장소에 첨부하지 않는다. 다른 시도의 로그와 섞이지 않도록 한 번에 한 시도를 수행하고 로컬 수집 범위를 확인한다. 앱은 진단을 영속 보관하거나 자동 외부 전송하지 않는다. 비공개 작업 파일도 목적 달성 후 삭제한다.

## 현재 요약 형식과 해석

`ManualSyncProfiling.safeSummary()`는 헤더 없이 총 24행을 enum 선언 순서로 출력한다. 각 행은 `라벨 started completed failed cancelled elapsed_ns active`이며 숫자는 음이 아닌 정수, `active`는 `0` 또는 `1`이다.

| 행(1부터) | 범위 | 라벨 순서 |
|---|---|---|
| 1–10 | ProfileStage | AUTHORIZATION_HISTORY, AUTHORIZATION_WAIT, CONTINUE_WAIT, EXECUTION, START_HISTORY, HEALTH_READ, NORMALIZE, CSV_EXPORT, DRIVE_TOTAL, TERMINAL_HISTORY |
| 11–17 | DriveOperation 업무 operation | APP_FOLDER_LOOKUP, APP_FOLDER_CREATE, DATASET_FOLDER_LOOKUP, DATASET_FOLDER_CREATE, FILE_LOOKUP, FILE_CREATE, FILE_UPDATE |
| 18–24 | 같은 DriveOperation의 transport request | 11–17행과 동일 순서 |

operation과 request의 라벨은 같으므로 행 위치로 구별한다. 실제 앱의 uploader request 계측은 transport 호출과 HTTP 상태·JSON 응답 검증을 포함한다. HTTP 오류 또는 JSON 검증 실패이면 request와 operation 모두 failed다. HTTP·JSON 검증 성공 뒤 private 대상 검증에서 거부되면 request는 completed, operation은 failed일 수 있다. 일부 저수준 테스트에서 transport가 503 응답을 정상 반환하는 경우를 실제 앱의 request 완료 의미와 혼동하지 않는다. 두 범위의 수와 시간을 합쳐 요청 수로 세지 않는다. 중첩된 단계 시간도 서로 더해 총시간으로 만들지 않는다.

`started=0`은 관찰된 시작이 없다는 뜻이다. 보통 미실행 단계이지만 계측 시계 실패로 측정이 폐기된 경우에도 이 값이 나올 수 있다. 업무 블록이 실행되었어도 측정 map에 남지 않을 수 있으므로 업무 단계가 실행되지 않았다고 단정하지 않는다. `started=1`, `completed+failed+cancelled=1`, `active=0`, `elapsed_ns=0`이면 관측이 종결된 0시간 값으로, 미실행과 다르다. `started=1, active=1, elapsed_ns=0`은 아직 종결되지 않은 관측이며 0시간 완료 측정으로 분류하지 않는다. 종결 수와 `active`를 함께 확인한다. `active=1`이면 시작했으나 요약 시점에 종료되지 않은 관측이며 완료 시간으로 해석할 수 없다. 특히 인증 이력 커밋이 진행 중인 화면 폐기에서는 AUTHORIZATION_HISTORY가 active=1인 미완료 요약이 가능하다. 나중의 커밋 완료가 이미 출력한 요약을 갱신한다고 가정하지 않는다.

AUTHORIZATION_HISTORY는 인증 시작을 위한 로컬 이력 처리 경계다. AUTHORIZATION_WAIT는 앱이 인증을 시작한 뒤 콜백 검증/처리에 도달하는 경계이고 Google 서버 시간만 뜻하지 않는다. CONTINUE_WAIT는 승인 콜백 수락 뒤 인증 수락 이력 커밋과 UI 처리, 사용자의 명시적 계속 대기를 포함할 수 있다. EXECUTION은 계속 실행 진입부터 읽기·정규화·CSV·Drive·시작/종결 이력 처리를 포함하는 앱 경계다. 이 값들을 순수 업로드 지연으로 부르지 않는다. 건강 읽기/CSV/Drive/로컬 이력을 각각 비교하고 인증·계속 대기는 실행 시간과 분리한다.

## 공유 전 allowlist 확인

1. 24행, 고정 enum 라벨, 범위별 순서와 6개 숫자 열만 있는지 비공개로 검사한다. 임의 문자열, 날짜/시각, 토큰, 계정/기기 ID, Drive 경로/URL/파일명, 건강 값, 원시 레코드 수/본문이 있으면 공유를 중단한다.
2. 원시 로그 접두사, 다른 tag, 앱/계정 화면과 개인 식별 자료를 모두 제외한다. 공개용 보고에는 검증한 집계와 재현 조건의 비식별 범주만 수동으로 옮기고 수집 파일을 첨부하지 않는다.
3. 조건별 표본 수·성공/실패/취소·미실행·미완료를 구분한다. p50/p95는 동일 조건의 완료 표본만으로 계산하고 분모와 제외 사유를 적는다. 작은 표본의 p95는 최대값일 수 있으며 SLA가 아니다.
4. 계정의 private Drive·동일 파일 갱신·읽기 권한/취소·이력 내구성은 별도 기존 수용 절차로 확인한다. TalkBack과 실제 기기의 P0 미확인은 그대로 남긴다. 측정 후 최적화 방식·목표는 별도 승인을 받는다.
