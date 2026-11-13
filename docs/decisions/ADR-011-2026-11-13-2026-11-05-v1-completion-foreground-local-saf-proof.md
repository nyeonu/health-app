# ADR-011: 전경 local SAF 권한과 동일 대상 확인을 실행마다 검증

## 상태
Accepted

## 날짜
2026-11-13

## 티켓
2026-11-05-v1-completion

## 배경
SAF는 사용자에게 보이는 local 폴더를 쓰므로 private Drive의 계정/소유/공유 검증과 다른 권한 경계가 필요하다. tree URI 문자열 저장만으로 현재 read/write 권한이나 짧은 덮어쓰기의 truncation을 증명할 수 없다. create/write는 SQLite transaction과 함께 rollback되지 않으며 응답불명 뒤 재생성하면 같은 날짜의 중복 파일을 만들 수 있다.

PR #17 실제 merge `57da82514e8483f5f32f2ad34ae0111c7f1dc431`의 Store/공용 gate/unknown-create 계약을 조사했다. 기존 V1 자동승인 위임에 따라 부모 SAF 계약과 7T 계획의 결정을 기록한다. 계획 작성 당시 child는 root 검토·부모 commit/revision 고정 전 draft였다. 현재 소프트웨어 실행·적합성 검증 결과는 아래에 기록하며, 이 결정의 Accepted 상태는 실제 기기 수용을 뜻하지 않는다. ADR-010은 자동화 계획용으로 예약되어 SAF에는 ADR-011을 사용한다.

## 결정
production은 `com.android.externalstorage.documents`의 사용자 선택 tree만 지원하고 `EXTRA_LOCAL_ONLY`는 hint로 취급한다. test authority는 명시 test constructor 주입에만 허용한다. cloud/알 수 없는 provider 및 background SAF는 disabled/BLOCKED다. local 폴더의 파일 관리자·백업·다른 앱 노출 가능성을 별도 설명하고 명시 privacy 동의를 받는다.

raw Activity result의 실제 URI와 READ/WRITE/PERSISTABLE flags를 확인하고 받은 READ|WRITE 교집합만 persist한다. 같은 tree의 persisted read/write와 실제 접근을 재확인한다. 명시 동의 뒤 health read 전 직접 만든 고유 synthetic probe에 긴 bytes→짧은 `wt` bytes→close→expected-length+1 bounded exact readback을 수행한다. 직접 만든 probe 하나만 cleanup하며 실패하면 capability를 활성화하지 않는다. `w`/append fallback, 기존 건강 파일 삭제, 중단 후 알 수 없는 probe 자동 청소는 하지 않는다. proof는 실행 메모리에만 있고 URI/proof/provider 원문은 UiState/SavedState/공개 진단으로 전달하지 않는다.

선택 tree 자체를 export root로 사용한다. 기존 네 dataset/date relativePath와 FileKey를 대조하고 tree/dataset/file 경로를 fresh 완전 조회하여 MIME/flags·partial/loading·중복·tree membership·known/current/prior identity를 검증한다. targetHandle은 raw document ID가 아닌 `DocumentsContract.buildDocumentUriUsingTree(treeUri, opaqueDocumentId).toString()` encoded URI다. opaque ID를 경로나 규칙 있는 문자열로 해석하거나 수동 인코딩하지 않는다. 기존 generic UTF-8 1..4096bytes/whitespace·control 금지 validator를 그대로 사용하며 맞지 않으면 BLOCKED다.

`reconcile`는 read-only이고 pending PlannedFile은 원래 입력과 값이 같아야 한다. 발견한 URI를 pending.copy로 삽입하지 않고 adapter의 실행 메모리에 exact FileKey/bytes/proof를 묶은 단 하나의 single-use batch reservation을 둔다. 새로운 reconcile·소비·성공·실패·취소에 reservation을 폐기하며 다른 batch/run/DB에 캐시하지 않는다. durable RUNNING commit 뒤 `writePending`에서만 directory/file create와 같은 URI `wt` overwrite를 수행한다. close·fresh 동일 identity·exact readback을 확인한 뒤 serial callback으로 durable confirmation을 commit한다.

모든 current-generation/date scope profile의 current/prior handle과 unknown null-handle create를 기존 bounded validated scan으로 읽는다. unknown create에서 safe existing target을 증명하지 못하면 directory/file 부재도 새 create의 근거가 아니다. create/write/close/readback 응답불명·취소·local confirmation 실패는 journal과 prior/REMOTE_STALE를 보존하고 새 source read/reconciliation으로 회복한다. generic retry/reset/new generation으로 uncertainty를 우회하거나 원격 write가 rollback됐다고 표시하지 않는다. 기존 `reopenManualBlockers`는 gate owner가 fresh proof와 명시 계속 뒤 eligible day blocker만 해제한다.

controller는 전체 intended categories를 picker generation/epoch snapshot에 고정한다. 일부 grant는 저장 범위를 줄이지 않고 기존 partial scope/coverage로 처리하며 grant0는 health read 전에 차단한다. selection/복원은 자동 export를 시작하지 않는다. 명시 continue만 공용 `SyncExecutionSession.runPrepared`를 통해 fresh binding/categories/grant/probe를 확인하여 실행한다. 별도 SAF gate나 token/계정 API는 만들지 않는다.

Accepted ADR-009가 ADR-006의 noBackup DB·exact-scope 모델·generation CAS·reset 및 검증된 crash rollback을 승계한다. 이 ADR은 그 계약을 재사용하는 SAF 권한/대상 프로토콜의 보완이며 ADR-009를 supersede하지 않는다. DB schema/column/format, CSV v0.1/다섯 category/네 dataset, foundation history 계약은 바꾸지 않는다.

## 검토한 대안

### Uri-only picker 결과
- 장점: 기존 OpenDocumentTree contract로 연결이 짧다.
- 단점: 실제 returned flags를 보존하지 않는다.
- 기각 이유: 실제 granted flags 검증 계약을 만족하기 위해 raw Intent result를 사용한다.

### raw document ID 저장
- 장점: handle 문자열이 짧다.
- 단점: tree/authority 컨텍스트와 opaque 문자 인코딩을 별도로 관리해야 한다.
- 기각 이유: DocumentsContract가 만든 encoded URI를 그대로 저장·재열어 동일 resolver identity를 검증한다.

### 저장 URI 또는 grant 존재만으로 capability 활성화
- 장점: 실행 전 provider 작업이 적다.
- 단점: 실제 write/truncation/close/readback과 현재 접근을 증명하지 못한다.
- 기각 이유: synthetic probe와 매 실행 fresh 접근 검증이 필요하다.

### reconciliation에서 발견한 URI를 pending 입력에 삽입
- 장점: write 단계의 대상 전달이 직접적이다.
- 단점: engine의 intended PlannedFile equality 계약을 바꾼다.
- 기각 이유: 입력을 보존한 single-use 실행 메모리 reservation을 사용한다.

## 결과와 비용
매 명시 실행의 probe·fresh enumeration/readback에는 IO 비용이 든다. 이 검증은 검증 뒤 다른 앱/사용자가 파일을 변경하지 않음을 보장하지 않으며 local 폴더의 지속 privacy 보장도 아니다. grant 철회/이동/삭제/중복/불완전 조회는 명시 blocked/reconciliation으로 노출한다. 테스트 fixture가 조용히 누락한 행만으로 production이 모든 provider 결함을 탐지한다고 주장하지 않는다.

test APK provider와 nested Activity/fixture는 Android framework·Java 표준 API·자체 Java만 사용한다. tested APK의 Kotlin runtime은 별도 provider UID에서 공유되지 않으며 이전 양API 실행의 `kotlin/io/FilesKt` 누락을 재도입하지 않는다. 이는 starter의 기동 경계이며 production 새 의존성이나 권한을 추가하는 결정이 아니다.

starter `25739c54de7efb37607bf5cb6af70d9441a367f4`의 run `43433735126`은 API36 actual 1 testcase PASS/API35 두 번째 REVOKE 제어 응답 timeout으로 양API 기동 완료가 아니다(root 전달 결과). 이는 당시 실패 이력이며 현재 상태를 뜻하지 않는다. 소스 `bbde0da5efbc6a6363bdd5f033128e5e2f5770b5`의 전체 run `43513509886`은 양 API 각각 Native132와 기존 host20/recovery17/cleanup17을 통과했고, JVM181 및 승인 스펙만 받은 새 세션의 SAF 소프트웨어 범위 적합성 검증도 PASS다. 따라서 이 전경 local 프로토콜 결정을 Accepted로 확정한다. 이는 실제 provider 호환성·출시 승인이 아니다. Galaxy/local provider/reboot/Doze/background/TalkBack/P0 수용은 수행한 증거와 pending을 계속 구분한다.

## 참고 자료
- [승인 스펙](../2026-11-05-v1-completion/spec.md)
- [부모 SafLocalContracts](../2026-11-05-v1-completion/plan.md)
- [SAF 실행 기록](../2026-11-05-v1-completion/saf-local/plan.md)
- [기존 저장·generation·crash rollback ADR-009](ADR-009-2026-11-12-2026-11-05-v1-completion-validated-hot-journal-recovery.md)

- [PR #18](https://github.com/nyeonu/health-app/pull/18) 및 [현재 소프트웨어 검증](../V1/saf-local-verification.md)
