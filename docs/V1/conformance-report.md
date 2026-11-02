# 스펙 적합성 검증

- 대상 스펙: `docs/V1/spec.md`
- 대상 소스: `b97ff632b12cfdc935303c2167d840d16c894e7e`
- 판정: FAIL

fresh-context 검증자가 스펙·구현·원시 실행 XML·대표 합성 이미지만 대조했다. 계획·과거 보고서·실행 대화 및 agent-status 도구를 제공하지 않았다. 아래는 그 독립 판정을 오케스트레이터가 보관하고 추적성 대조를 추가한 기록이다. 확인된 구현 결함은 없으며34개 PASS,5개 FAIL은 실제 기기 실행 증거 부족이다. 실제 오동작을 관찰했다는 의미가 아니다.

실행 증거: 현재 소스 JVM XML84개(core35+app49), Android CI[41438677211](https://github.com/nyeonu/health-app/actions/runs/41438677211) XML40개 모두 실패·오류·skip0. [JVM/APK/lint CI41438677210](https://github.com/nyeonu/health-app/actions/runs/41438677210) 통과. root도 로컬 전체 명령을 실행했다. 검증자는 원시 XML과 테스트 단언을 직접 대조했으며 새 테스트 명령을 실행했다고 주장하지 않았다. 합성 PNG56개 존재,7상태 대표7개를 root와 검증자가 각각 시각 확인했다.

## 기준별 결과

| 성공 기준 | 판정 | 실행 증거 |
|---|---|---|
| After confirmed manual upload and process recreation, the UI restores its reporting date, confirmation time, granted-category coverage and confirmed-file count as historical local success. | FAIL | SQLite 및 새 Activity/ViewModel의 복원은 통과. 실제 수동 업로드 후 OS 프로세스 종료·재생성 관찰 없음. committedManualSuccessSurvivesFreshStoreReopenWithCoherentFields; persistedSuccessSurvivesFreshActivityAndViewModelWhileConsentAndPreparedContinuationReset |
| The history stores only the latest attempt and latest confirmed success, with a format version and bounded metadata, and contains no token, account/file/record identifier, raw measurement, CSV body or provider error text. | PASS | 현재 소스 실행 XML에서 통과 및 실제 단언 확인: History codec rejects forbidden fields and oversized or malformed metadata |
| An attempt records authorization-request time separately from actual execution-start time, so cancelling authorization does not create a fictitious started upload. | PASS | 현재 소스 실행 XML에서 통과 및 실제 단언 확인: 승인 시작 저장 실패는 SDK 실행을 막고 취소 결과는 요청 시각만 기록한다 |
| A failed local start-state write prevents health reading and remote upload, and an uncommitted result is never presented as a durably recorded success. | PASS | 현재 소스 실행 XML에서 통과 및 실제 단언 확인: 실행 시작 저장 실패는 실제 효과 경계의 읽기와 업로드를 모두 막는다; 원격 확인 뒤 결과 저장 실패는 일시 증거와 이전 영속 성공을 분리한다 |
| Failure or interruption while persisting a result preserves the previous committed record and reports an explicit local-history failure without asserting that already-issued remote writes were rolled back. | PASS | 현재 소스 실행 XML에서 통과 및 실제 단언 확인: failedResultTransactionReopensPriorCommittedSuccess; 원격 확인 뒤 결과 저장 실패는 일시 증거와 이전 영속 성공을 분리한다 |
| A persisted started execution without a committed terminal outcome is shown as interrupted or indeterminate after process recreation, with no automatic retry and no fabricated success. | FAIL | RUNNING 복구 및 중단 표시 테스트는 통과. 실제 OS 프로세스 종료 후 재생성 관찰 없음. 미완료 실행을 복원하면 재시도 없이 결과 미확정 중단을 저장한다; persistedRunningReopensInterruptedWithoutFinishOrCountAndPreservesPriorSuccess |
| After process recreation, upload consent is unchecked, no token or prepared continuation is restored, and no health read or network write occurs until fresh manual consent/authorization/continuation. | FAIL | 새 ViewModel의 동의·티켓·준비 작업 초기화 및 외부 효과0 테스트는 통과. 실제 OS 프로세스 재생성 관찰 없음. 재열기와 지연 승인 결과는 토큰이나 명시적 계속 및 외부 실행을 복원하지 않는다; persistedSuccessSurvivesFreshActivityAndViewModelWhileConsentAndPreparedContinuationReset |
| A later failed, empty, permission-required or interrupted attempt updates latest-attempt status while retaining the previous independently confirmed success. | PASS | 현재 소스 실행 XML에서 통과 및 실제 단언 확인: Later non-success outcomes retain independently committed prior success |
| Duplicate UI actions cannot run overlapping manual executions or let a stale completion overwrite a newer local forget operation. | PASS | 현재 소스 실행 XML에서 통과 및 실제 단언 확인: 중복 시작과 지연 완료는 확인된 삭제를 덮어쓰지 못한다 |
| Missing history starts as no local history, while unsupported or corrupt stored history produces a recoverable explicit state and never fabricated zero measurements or success. | PASS | 현재 소스 실행 XML에서 통과 및 실제 단언 확인: absentUnsupportedCorruptAndUnavailableStoresHaveDistinctReopenResults |
| The UI distinguishes authorization time, actual execution start, latest outcome and previous confirmed reporting date, and indicates partial authorized scope rather than complete five-category coverage. | PASS | 현재 소스 실행 XML에서 통과 및 실제 단언 확인: detailsDistinguishAuthorizationExecutionAndResultTimesWithPartialScope |
| Restored history is labelled as local historical evidence whose current Google-account association and upstream completeness are unverified, and is never used to skip a fresh read/upload or advance a backfill cursor. | PASS | 현재 소스 실행 XML에서 통과 및 실제 단언 확인: restoredSuccessIsLabelledHistoricalAndNeverBypassesManualExecution |
| A confirmed successful local forget clears persisted history and in-memory consent/token/status without deleting Drive files or claiming to revoke Health Connect or Google grants; failure and interruption follow the deletion criteria below. | PASS | 현재 소스 실행 XML에서 통과 및 실제 단언 확인: 확인된 로컬 삭제는 인증을 무효화하고 다시 열면 빈 기록이다; actualUiCancelPreservesHistoryAndConfirmedLocalDeletionReopensDurablyEmpty |
| The same reporting-day paths, CSV payload rules, explicit EMPTY semantics, granted-type filtering and private Drive target checks remain unchanged under the existing meaningful regressions. | PASS | 현재 소스 실행 XML에서 통과 및 실제 단언 확인: CSV headers ordering quoting and missing values follow v0_1; Empty and denied reads never upload or report success; Successful partial-grant sync uploads only authorized populated datasets; Ambiguous or shared targets are rejected before upload |
| The collapsed home shows the target reporting date, current attempt/error state, independently retained last confirmed success and the next permitted action without opening details. | PASS | 현재 소스 실행 XML에서 통과 및 실제 단언 확인: collapsedHomeShowsCurrentErrorPriorSuccessTargetDayAndNextAction |
| Last-success date, timestamp, coverage and file count all come from the same committed success record; a later failed attempt's count is never displayed as that success's count. | PASS | 현재 소스 실행 XML에서 통과 및 실제 단언 확인: successCardCountDateTimeAndCoverageComeFromOneCommittedSuccess |
| Expanded details distinguish authorization-request time, actual execution-start time and terminal time, displaying absent times as “미시작” or “확인 불가” as appropriate rather than inventing timestamps. | PASS | 현재 소스 실행 XML에서 통과 및 실제 단언 확인: detailsRenderMissingExecutionAndTerminalTimesWithoutInventedTimestamps; unreadableAndLoadingExecutionStartRemainsUnknownWhileKnownUnstartedHistoryShowsNotStarted |
| A failed or interrupted latest attempt remains visible alongside an earlier success; EMPTY is shown as no records to export, not successful upload. | PASS | 현재 소스 실행 XML에서 통과 및 실제 단언 확인: latestFailureAndEmptyOutcomeRemainDistinctFromPriorSuccessfulUpload |
| Authorization and explicit upload continuation remain separate actions, with no automatic read/upload caused by expanding details, restoring the screen or loading history. | PASS | 현재 소스 실행 XML에서 통과 및 실제 단언 확인: expandingDetailsOrRestoringUiNeverAuthorizesReadsOrUploads |
| The screen remains readable and operable with system light/dark themes, enlarged system text and TalkBack; critical state, confirmation controls and next actions must not be clipped, overlap or rely on color alone. | FAIL | Material 밝음/어두움·LocalDensity2x·이름/역할/스크롤 및 합성 캡처 검증 통과. 실제 시스템 설정 전환·TalkBack 관찰 없음. bothThemesAtDoubleTextScaleKeepManualAndRecoveryControlsNamedAndReachable; 실제 TalkBack·Galaxy 검증 대기 |
| Synthetic UI previews or screenshots cover no history, committed success, authorization pending, running, interrupted, history-read failure and deletion failure; these are review artifacts, not evidence of real account/device success. | PASS | 현재 소스 실행 XML에서 통과 및 실제 단언 확인: syntheticGalleryCapturesSevenStatesInBothThemesAndDoubleTextScale |
| Cancelling the deletion dialog changes neither durable history nor current consent/token state. | PASS | 현재 소스 실행 XML에서 통과 및 실제 단언 확인: 삭제 확인을 취소하면 인증과 영속 기록을 보존한다; actualUiCancelPreservesHistoryAndConfirmedLocalDeletionReopensDurablyEmpty |
| Deletion is unavailable while permission/authorization is pending, a sync is running, or a history read/write/delete is in progress; the reason is visible and the operation is rejected even if a stale UI event arrives. | PASS | 현재 소스 실행 XML에서 통과 및 실제 단언 확인: 삭제 진입 검사는 권한 대기와 새로고침 및 실행과 저장소 작업 중 오래된 이벤트를 거부한다; busySessionsRejectDeleteRequestsAndExplainDisabledDeletion |
| Confirming deletion immediately clears in-memory consent, token and prepared continuation before durable deletion is attempted. | PASS | 현재 소스 실행 XML에서 통과 및 실제 단언 확인: 삭제 확인은 첫 저장소 중단점 전에 인증을 무효화한다 |
| A durable empty-state commit clears both latest attempt and latest success together; a fresh store reopen after acknowledged completion finds no local history. | PASS | 현재 소스 실행 XML에서 통과 및 실제 단언 확인: acknowledgedEmptyStateTransactionReopensWithoutAttemptOrSuccess; actualUiCancelPreservesHistoryAndConfirmedLocalDeletionReopensDurablyEmpty |
| If deletion fails, show “이력 삭제에 실패했습니다. 저장된 이력이 남아 있을 수 있습니다.” and provide “삭제 다시 시도”; do not show “삭제 완료” or restore consent/token. | PASS | 현재 소스 실행 XML에서 통과 및 실제 단언 확인: 삭제 실패는 완료로 주장하지 않고 인증 복원 없이 재시도를 허용한다; failedDeletionOffersRetryWithoutClaimingCompletionOrRestoringCredentials |
| After deletion failure, any retained readable record is marked “삭제되지 않은 기존 이력”; unreadable storage remains explicitly unknown rather than appearing empty. | PASS | 현재 소스 실행 XML에서 통과 및 실제 단언 확인: 삭제 실패는 읽을 수 있는 기록만 유지로 표시하고 읽기 불가는 미확정으로 남긴다; failedDeletionLabelsRetainedHistoryWhileUnreadableHistoryRemainsUnknown |
| After deletion failure, new authorization/upload stays disabled for the current session until deletion succeeds or the user explicitly chooses “삭제 취소·기존 이력 유지” and the store is successfully reloaded; neither action restores consent/token. | PASS | 현재 소스 실행 XML에서 통과 및 실제 단언 확인: 삭제 실패는 명시적 유지와 성공한 재읽기 전까지 새 승인을 차단한다 |
| If the process ends during deletion, reopening shows the actual committed state: empty after committed clearing, otherwise the retained prior history or an explicit read error; it never claims an interrupted deletion completed. | FAIL | 커밋 전 예외 주입과 실제 SQLite 재열기는 통과. 삭제 중 실제 OS 프로세스 종료 관찰 없음. interruptedResetBeforeCommitReopensOldStateAndAfterCommitReopensEmpty; 실제 기기 프로세스 종료 경계 대기 |
| An uncommitted deletion intention need not survive process loss; persisted history may reappear after an interrupted/failed deletion, but a durably acknowledged deletion must not be undone by stale callbacks, result writes or recovery. | PASS | 현재 소스 실행 XML에서 통과 및 실제 단언 확인: 복구 후 이전 세대 콜백은 확인된 초기화를 다시 쓰지 못한다; acknowledgedEmptyStateTransactionReopensWithoutAttemptOrSuccess |
| Deletion is idempotent: deleting already-empty history succeeds without remote calls, and any deletion failure remains a storage error rather than a fake success. | PASS | 현재 소스 실행 XML에서 통과 및 실제 단언 확인: 빈 기록의 반복 삭제는 로컬이며 영속화 실패를 성공으로 바꾸지 않는다; repeatedEmptyDeletionIsLocalAndPersistenceFailureNeverBecomesSuccess |
| Local deletion does not delete Drive exports, revoke external grants, clear Health Connect records or promise forensic secure erasure. | PASS | 현재 소스 실행 XML에서 통과 및 실제 단언 확인: deleteConfirmationStatesLocalOnlyScopeAndTriggersNoExternalDeletion |
| A persisted pending authorization without execution start restores as “인증 단계에서 종료됨 · 업로드 미시작”; persisted authorization success awaiting continuation restores as “업로드 시작 전 종료됨 · 다시 인증해 주세요.” | PASS | 현재 소스 실행 XML에서 통과 및 실제 단언 확인: Recovery distinguishes abandoned authorization from awaiting-upload interruption |
| A persisted running attempt without a committed terminal result restores as interrupted/indeterminate, without fabricated completion time or guaranteed uploaded-file count; remote files may already exist. | PASS | 현재 소스 실행 XML에서 통과 및 실제 단언 확인: Running recovery never invents terminal time or confirmed upload count; persistedRunningReopensInterruptedWithoutFinishOrCountAndPreservesPriorSuccess |
| A committed terminal result restores its actual outcome, while failed/empty/interrupted attempts retain the independently committed previous success. | PASS | 현재 소스 실행 XML에서 통과 및 실제 단언 확인: Terminal restoration preserves actual outcome and prior independent success; terminalRestorationPreservesActualOutcomeAndPriorIndependentSuccess |
| Unsupported-format, corrupt and unavailable history are distinguished from an absent history store; no automatic reset or overwrite occurs merely because reading failed. | PASS | 현재 소스 실행 XML에서 통과 및 실제 단언 확인: readingIncompatibleOrCorruptPayloadNeverOverwritesItAndExplicitResetCommitsEmpty; unsupportedSchemaAndCorruptDatabaseRefuseWritesAndResetWithoutReplacement; unavailableDatabaseReadsAndFailedResetLeaveTheStoreUnavailable |
| While history is unreadable or unsupported, new authorization/upload is blocked until an explicit reload succeeds or a user-confirmed local reset durably succeeds; reset failure follows the deletion-failure rules. | PASS | 현재 소스 실행 XML에서 통과 및 실제 단언 확인: 읽기 불가 기록은 명시적 재읽기 또는 초기화 성공 전까지 업로드를 차단한다 |
| Persisting a recovered status uses the same atomic failure rules as ordinary results; if recovery persistence fails, show the derived interruption together with a local-history error and do not claim durable recovery. | PASS | 현재 소스 실행 XML에서 통과 및 실제 단언 확인: 복구 저장 실패는 파생 중단과 로컬 저장 오류를 표시한다; recoverySaveFailureLeavesPersistedRunningAttemptAndDerivableInterruption; recoveryWriteFailureShowsDerivedOutcomeAndUnknownResultWhilePreservingPriorSuccessAndBlockingNewUpload |
| App reopen never restores tokens, upload consent or a prepared continuation, even after failed deletion or abandoned authorization. | PASS | 현재 소스 실행 XML에서 통과 및 실제 단언 확인: 재열기와 지연 승인 결과는 토큰이나 명시적 계속 및 외부 실행을 복원하지 않는다; persistedSuccessSurvivesFreshActivityAndViewModelWhileConsentAndPreparedContinuationReset |

## 계약 준수

CSV v0.1 헤더·값·CRLF·인용·경로 회귀, reader14개, private Drive 대상9개 및 로컬 TLS transport6개가 통과했다. 인증과 별도 업로드 계속의 효과 경계도 통과했다. 기존 reader/coordinator/CSV/Drive/manifest/Gradle은 기준 소스 대비 동일하다. 실제 계정·원본 데이터 검증을 수행했다는 의미가 아니다. 신규 서버 API·배포 순서 제약은 해당 없음.

## 추적성

오케스트레이터가 별도 수행: 승인 기준39개 문장의 원문 일치, 실제 함수명 존재 및 실제 실행 XML에 포함됨을 모두 확인했다. 코드에 없거나 skip된 매핑 테스트 없음. 계획3절과 실행 보고서의 모든 행을 실제 함수명으로 갱신했다. 기준 번호 접두어 또는 불명확한 번호형 이름 없음. 검증자는 계획을 읽지 않았다.

## 경계 위반

없음. 플랫폼 SQLite의 noBackupFilesDir·단일행 트랜잭션·DELETE journal·FULL synchronous와 손상/지원불가 파일 보존 테스트가 확인됐다. WorkManager·자동 업로드·backfill·다른 목적지·영속 토큰·계정별 영속 coverage·새 권한/의존성/Drive scope를 도입하지 않았다.

## 실패 상세

확인된 구현 결함 없음. 실제 OS 프로세스 종료·재생성을 요구하는4개 기준과 실제 시스템 접근성을 요구하는1개 기준에 실행 증거가 없다. 성공 후·실행 중·삭제 커밋 전후의 실제 프로세스 종료/재실행 및 시스템 테마·글자 설정·TalkBack의 읽기/초점/확인 조작을 관찰해야 한다. Activity/ViewModel 재생성, SQLite 재열기, CancellationException 주입, MaterialTheme/LocalDensity/semantics 검증을 해당 관찰로 확대하지 않았다.

## 남은 불확실성

- Galaxy의 실제 프로세스 종료·재열기·수동 복구 및 삭제 경계.
- 시스템 테마·글자 설정 연동과 실제 TalkBack 초점/음성/대화상자 조작.
- 새 V1 코드의 실제 계정 Google 인증·Drive 업로드 및 실제 건강 데이터 결과. 합성 증거로 대체하지 않았다.
- 기존 P0 미확인 항목은 소유자 지시대로 유지한다. 원본 비교 완료라는 소유자 확인을 다시 미확인으로 바꾸지 않는다.

전체 적합성 FAIL이므로 spec은 approved, ADR-004는 Proposed, 계획은 in_progress, PR은 draft로 유지한다. 최종 품질·보안 리뷰/ready-for-review/머지는 진행하지 않았다.
