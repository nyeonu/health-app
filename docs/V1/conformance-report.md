# 스펙 적합성 검증

- 대상 스펙: `docs/V1/spec.md`
- 검토·실행 소스: `cc30964a882f7798e71aca7e5c5f73138a1abf72`
- 개발 PR 리뷰: **APPROVE** — 독립 세션의 Critical/Important/Suggestion 없음.
- 소프트웨어 자동 검증 범위: **PASS**.
- 전체 인수 판정: **FAIL —38/39 PASS, 실제 TalkBack/Galaxy 수용 증거1개 미완료**. 관찰된 코드 결함이 아니라 증거 부족이다.

소유자는 코멘트 수정·별도 세션 검증 후 이슈 없는 개발 PR의 병합을 요청했다. 실제 기기/P0 수용은 별도로 남긴다는 검토 방침에 따라 개발 머지와 전체 인수/출시 승인을 구분한다. 승인된39개 기준 문장은 바꾸지 않았다. 스펙 approved·ADR Proposed·수동 수용 대기를 유지한다.

fresh-context 검증자는 스펙·코드·테스트 assertion과 현재 소스의 원시 XML/host summary/대표 PNG를 직접 확인했다. 계획·이전 보고·PR 코멘트를 읽거나 agent-status 도구를 사용하지 않았다. 아래는 그 독립 판정을 root가 보관하고 추적성 확인을 추가한 기록이다.

## 기준별 결과

현재 소스 실행 증거: JVM84(core35+app49), [Android CI41529578551](https://github.com/nyeonu/health-app/actions/runs/41529578551) normal48 테스트 모두 실패·오류·skip0. host summary complete=true: 실제 OS 프로세스 종료·새 PID 복구8, 실제 시스템 설정 변경4, 소유 fixture 정리8. 의도적으로 종료한 hold는0PASS. PNG60개(합성갤러리56+실제시스템4). [JVM/APK/lint CI41529578661](https://github.com/nyeonu/health-app/actions/runs/41529578661) 성공. Root와 검증자가 실제 XML·host summary·source assertion을 대조하고 대표 이미지를 직접 확인했다.

| 성공 기준 | 판정 | 실행 증거 |
|---|---|---|
| After confirmed manual upload and process recreation, the UI restores its reporting date, confirmation time, granted-category coverage and confirmed-file count as historical local success. | PASS | 현재 소스 원시 실행 결과/실제 단언 통과: committedManualSuccessSurvivesFreshStoreReopenWithCoherentFields; persistedSuccessSurvivesFreshActivityAndViewModelWhileConsentAndPreparedContinuationReset; reopenedActivityRestoresOnlyCommittedMetadata (host success) |
| The history stores only the latest attempt and latest confirmed success, with a format version and bounded metadata, and contains no token, account/file/record identifier, raw measurement, CSV body or provider error text. | PASS | 현재 소스 원시 실행 결과/실제 단언 통과: History codec rejects forbidden fields and oversized or malformed metadata |
| An attempt records authorization-request time separately from actual execution-start time, so cancelling authorization does not create a fictitious started upload. | PASS | 현재 소스 원시 실행 결과/실제 단언 통과: 승인 시작 저장 실패는 SDK 실행을 막고 취소 결과는 요청 시각만 기록한다 |
| A failed local start-state write prevents health reading and remote upload, and an uncommitted result is never presented as a durably recorded success. | PASS | 현재 소스 원시 실행 결과/실제 단언 통과: 실행 시작 저장 실패는 실제 효과 경계의 읽기와 업로드를 모두 막는다; 원격 확인 뒤 결과 저장 실패는 일시 증거와 이전 영속 성공을 분리한다 |
| Failure or interruption while persisting a result preserves the previous committed record and reports an explicit local-history failure without asserting that already-issued remote writes were rolled back. | PASS | 현재 소스 원시 실행 결과/실제 단언 통과: failedResultTransactionReopensPriorCommittedSuccess; 원격 확인 뒤 결과 저장 실패는 일시 증거와 이전 영속 성공을 분리한다 |
| A persisted started execution without a committed terminal outcome is shown as interrupted or indeterminate after process recreation, with no automatic retry and no fabricated success. | PASS | 현재 소스 원시 실행 결과/실제 단언 통과: 미완료 실행을 복원하면 재시도 없이 결과 미확정 중단을 저장한다; persistedRunningReopensInterruptedWithoutFinishOrCountAndPreservesPriorSuccess; reopenedActivityRestoresOnlyCommittedMetadata (host running) |
| After process recreation, upload consent is unchecked, no token or prepared continuation is restored, and no health read or network write occurs until fresh manual consent/authorization/continuation. | PASS | 현재 소스 원시 실행 결과/실제 단언 통과: 재열기와 지연 승인 결과는 토큰이나 명시적 계속 및 외부 실행을 복원하지 않는다; persistedSuccessSurvivesFreshActivityAndViewModelWhileConsentAndPreparedContinuationReset |
| A later failed, empty, permission-required or interrupted attempt updates latest-attempt status while retaining the previous independently confirmed success. | PASS | 현재 소스 원시 실행 결과/실제 단언 통과: Later non-success outcomes retain independently committed prior success |
| Duplicate UI actions cannot run overlapping manual executions or let a stale completion overwrite a newer local forget operation. | PASS | 현재 소스 원시 실행 결과/실제 단언 통과: 중복 시작과 지연 완료는 확인된 삭제를 덮어쓰지 못한다 |
| Missing history starts as no local history, while unsupported or corrupt stored history produces a recoverable explicit state and never fabricated zero measurements or success. | PASS | 현재 소스 원시 실행 결과/실제 단언 통과: absentUnsupportedCorruptAndUnavailableStoresHaveDistinctReopenResults |
| The UI distinguishes authorization time, actual execution start, latest outcome and previous confirmed reporting date, and indicates partial authorized scope rather than complete five-category coverage. | PASS | 현재 소스 원시 실행 결과/실제 단언 통과: detailsDistinguishAuthorizationExecutionAndResultTimesWithPartialScope |
| Restored history is labelled as local historical evidence whose current Google-account association and upstream completeness are unverified, and is never used to skip a fresh read/upload or advance a backfill cursor. | PASS | 현재 소스 원시 실행 결과/실제 단언 통과: restoredSuccessIsLabelledHistoricalAndNeverBypassesManualExecution |
| A confirmed successful local forget clears persisted history and in-memory consent/token/status without deleting Drive files or claiming to revoke Health Connect or Google grants; failure and interruption follow the deletion criteria below. | PASS | 현재 소스 원시 실행 결과/실제 단언 통과: 확인된 로컬 삭제는 인증을 무효화하고 다시 열면 빈 기록이다; actualUiCancelPreservesHistoryAndConfirmedLocalDeletionReopensDurablyEmpty |
| The same reporting-day paths, CSV payload rules, explicit EMPTY semantics, granted-type filtering and private Drive target checks remain unchanged under the existing meaningful regressions. | PASS | 현재 소스 원시 실행 결과/실제 단언 통과: CSV headers ordering quoting and missing values follow v0_1; Empty and denied reads never upload or report success; Successful partial-grant sync uploads only authorized populated datasets; Ambiguous or shared targets are rejected before upload |
| The collapsed home shows the target reporting date, current attempt/error state, independently retained last confirmed success and the next permitted action without opening details. | PASS | 현재 소스 원시 실행 결과/실제 단언 통과: collapsedHomeShowsCurrentErrorPriorSuccessTargetDayAndNextAction |
| Last-success date, timestamp, coverage and file count all come from the same committed success record; a later failed attempt's count is never displayed as that success's count. | PASS | 현재 소스 원시 실행 결과/실제 단언 통과: successCardCountDateTimeAndCoverageComeFromOneCommittedSuccess |
| Expanded details distinguish authorization-request time, actual execution-start time and terminal time, displaying absent times as “미시작” or “확인 불가” as appropriate rather than inventing timestamps. | PASS | 현재 소스 원시 실행 결과/실제 단언 통과: detailsRenderMissingExecutionAndTerminalTimesWithoutInventedTimestamps; unreadableAndLoadingExecutionStartRemainsUnknownWhileKnownUnstartedHistoryShowsNotStarted |
| A failed or interrupted latest attempt remains visible alongside an earlier success; EMPTY is shown as no records to export, not successful upload. | PASS | 현재 소스 원시 실행 결과/실제 단언 통과: latestFailureAndEmptyOutcomeRemainDistinctFromPriorSuccessfulUpload |
| Authorization and explicit upload continuation remain separate actions, with no automatic read/upload caused by expanding details, restoring the screen or loading history. | PASS | 현재 소스 원시 실행 결과/실제 단언 통과: expandingDetailsOrRestoringUiNeverAuthorizesReadsOrUploads |
| The screen remains readable and operable with system light/dark themes, enlarged system text and TalkBack; critical state, confirmation controls and next actions must not be clipped, overlap or rely on color alone. | FAIL | 자동 semantics/도달성, 실제 시스템 테마·font_scale4조합 및 캡처는 통과. 실제 TalkBack/Galaxy 관찰 미수행. bothThemesAtDoubleTextScaleKeepManualAndRecoveryControlsNamedAndReachable; liveSystemThemeAndFontChangeReachActivityAndControls (host system settings); 실제 TalkBack·Galaxy 검증 대기 |
| Synthetic UI previews or screenshots cover no history, committed success, authorization pending, running, interrupted, history-read failure and deletion failure; these are review artifacts, not evidence of real account/device success. | PASS | 현재 소스 원시 실행 결과/실제 단언 통과: syntheticGalleryCapturesSevenStatesInBothThemesAndDoubleTextScale |
| Cancelling the deletion dialog changes neither durable history nor current consent/token state. | PASS | 현재 소스 원시 실행 결과/실제 단언 통과: 삭제 확인을 취소하면 인증과 영속 기록을 보존한다; actualUiCancelPreservesHistoryAndConfirmedLocalDeletionReopensDurablyEmpty |
| Deletion is unavailable while permission/authorization is pending, a sync is running, or a history read/write/delete is in progress; the reason is visible and the operation is rejected even if a stale UI event arrives. | PASS | 현재 소스 원시 실행 결과/실제 단언 통과: 삭제 진입 검사는 권한 대기와 새로고침 및 실행과 저장소 작업 중 오래된 이벤트를 거부한다; busySessionsRejectDeleteRequestsAndExplainDisabledDeletion |
| Confirming deletion immediately clears in-memory consent, token and prepared continuation before durable deletion is attempted. | PASS | 현재 소스 원시 실행 결과/실제 단언 통과: 삭제 확인은 첫 저장소 중단점 전에 인증을 무효화한다 |
| A durable empty-state commit clears both latest attempt and latest success together; a fresh store reopen after acknowledged completion finds no local history. | PASS | 현재 소스 원시 실행 결과/실제 단언 통과: acknowledgedEmptyStateTransactionReopensWithoutAttemptOrSuccess; actualUiCancelPreservesHistoryAndConfirmedLocalDeletionReopensDurablyEmpty |
| If deletion fails, show “이력 삭제에 실패했습니다. 저장된 이력이 남아 있을 수 있습니다.” and provide “삭제 다시 시도”; do not show “삭제 완료” or restore consent/token. | PASS | 현재 소스 원시 실행 결과/실제 단언 통과: 삭제 실패는 완료로 주장하지 않고 인증 복원 없이 재시도를 허용한다; failedDeletionOffersRetryWithoutClaimingCompletionOrRestoringCredentials |
| After deletion failure, any retained readable record is marked “삭제되지 않은 기존 이력”; unreadable storage remains explicitly unknown rather than appearing empty. | PASS | 현재 소스 원시 실행 결과/실제 단언 통과: 삭제 실패는 읽을 수 있는 기록만 유지로 표시하고 읽기 불가는 미확정으로 남긴다; failedDeletionLabelsRetainedHistoryWhileUnreadableHistoryRemainsUnknown |
| After deletion failure, new authorization/upload stays disabled for the current session until deletion succeeds or the user explicitly chooses “삭제 취소·기존 이력 유지” and the store is successfully reloaded; neither action restores consent/token. | PASS | 현재 소스 원시 실행 결과/실제 단언 통과: 삭제 실패는 명시적 유지와 성공한 재읽기 전까지 새 승인을 차단한다 |
| If the process ends during deletion, reopening shows the actual committed state: empty after committed clearing, otherwise the retained prior history or an explicit read error; it never claims an interrupted deletion completed. | PASS | 현재 소스 원시 실행 결과/실제 단언 통과: interruptedResetBeforeCommitReopensOldStateAndAfterCommitReopensEmpty; reopenedActivityRestoresOnlyCommittedMetadata (host reset-before/reset-after/reset-empty-before); Galaxy 관찰 대기 |
| An uncommitted deletion intention need not survive process loss; persisted history may reappear after an interrupted/failed deletion, but a durably acknowledged deletion must not be undone by stale callbacks, result writes or recovery. | PASS | 현재 소스 원시 실행 결과/실제 단언 통과: 복구 후 이전 세대 콜백은 확인된 초기화를 다시 쓰지 못한다; acknowledgedEmptyStateTransactionReopensWithoutAttemptOrSuccess |
| Deletion is idempotent: deleting already-empty history succeeds without remote calls, and any deletion failure remains a storage error rather than a fake success. | PASS | 현재 소스 원시 실행 결과/실제 단언 통과: 빈 기록의 반복 삭제는 로컬이며 영속화 실패를 성공으로 바꾸지 않는다; repeatedEmptyDeletionIsLocalAndPersistenceFailureNeverBecomesSuccess |
| Local deletion does not delete Drive exports, revoke external grants, clear Health Connect records or promise forensic secure erasure. | PASS | 현재 소스 원시 실행 결과/실제 단언 통과: deleteConfirmationStatesLocalOnlyScopeAndTriggersNoExternalDeletion |
| A persisted pending authorization without execution start restores as “인증 단계에서 종료됨 · 업로드 미시작”; persisted authorization success awaiting continuation restores as “업로드 시작 전 종료됨 · 다시 인증해 주세요.” | PASS | 현재 소스 원시 실행 결과/실제 단언 통과: Recovery distinguishes abandoned authorization from awaiting-upload interruption |
| A persisted running attempt without a committed terminal result restores as interrupted/indeterminate, without fabricated completion time or guaranteed uploaded-file count; remote files may already exist. | PASS | 현재 소스 원시 실행 결과/실제 단언 통과: Running recovery never invents terminal time or confirmed upload count; persistedRunningReopensInterruptedWithoutFinishOrCountAndPreservesPriorSuccess |
| A committed terminal result restores its actual outcome, while failed/empty/interrupted attempts retain the independently committed previous success. | PASS | 현재 소스 원시 실행 결과/실제 단언 통과: Terminal restoration preserves actual outcome and prior independent success; terminalRestorationPreservesActualOutcomeAndPriorIndependentSuccess |
| Unsupported-format, corrupt and unavailable history are distinguished from an absent history store; no automatic reset or overwrite occurs merely because reading failed. | PASS | 현재 소스 원시 실행 결과/실제 단언 통과: readingIncompatibleOrCorruptPayloadNeverOverwritesItAndExplicitResetCommitsEmpty; unsupportedSchemaAndCorruptDatabasePreserveReadsAndWritesUntilExplicitReset; malformedVersionOneConstraintsAndHistoryTriggersRequireExplicitReplacement; canonicalSchemaWithPreviouslyIgnoredCheckRequiresConfirmedResetToClearEveryRow; unknownWalSchemaReadsAndOrdinaryWritesDoNotConvertOrOverwriteItsDatabaseFamily; genuineHotRollbackJournalStaysExplicitlyUnavailableUntilConfirmedReset; unavailableDatabaseReadsAndFailedResetLeaveTheStoreUnavailable |
| While history is unreadable or unsupported, new authorization/upload is blocked until an explicit reload succeeds or a user-confirmed local reset durably succeeds; reset failure follows the deletion-failure rules. | PASS | 현재 소스 원시 실행 결과/실제 단언 통과: 읽기 불가 기록은 명시적 재읽기 또는 초기화 성공 전까지 업로드를 차단한다 |
| Persisting a recovered status uses the same atomic failure rules as ordinary results; if recovery persistence fails, show the derived interruption together with a local-history error and do not claim durable recovery. | PASS | 현재 소스 원시 실행 결과/실제 단언 통과: 복구 저장 실패는 파생 중단과 로컬 저장 오류를 표시한다; recoverySaveFailureLeavesPersistedRunningAttemptAndDerivableInterruption; recoveryWriteFailureShowsDerivedOutcomeAndUnknownResultWhilePreservingPriorSuccessAndBlockingNewUpload |
| App reopen never restores tokens, upload consent or a prepared continuation, even after failed deletion or abandoned authorization. | PASS | 현재 소스 원시 실행 결과/실제 단언 통과: 재열기와 지연 승인 결과는 토큰이나 명시적 계속 및 외부 실행을 복원하지 않는다; persistedSuccessSurvivesFreshActivityAndViewModelWhileConsentAndPreparedContinuationReset |

## 계약 준수

CSV schema·날짜 경로·허용 항목 필터·EMPTY·drive.file·비공개 Drive 대상 계약 변경 없음. 기존 의미 있는 reader/normalizer/exporter/coordinator/uploader/transport 회귀가 통과했다. 로컬 복구에 자동 remote retry가 추가되지 않았다. 새 서버 API·외부 계약/배포 순서 변경은 해당 없음.

## 추적성

오케스트레이터가 별도 수행: 스펙 원문39개 문장과 계획3절의 순서·내용 일치, 모든 실제 함수명 존재 확인. JVM/normal Android 원시 XML과 host 스크립트의 별도 실행 매핑을 대조했다. host-only hold는 normal discovery에서 제외하고 별도 외부 강제종료로 검증하므로 skip나가짜PASS로 세지 않는다. 테스트 의미/상태/조건과 assertion을 독립 검증자가 직접 읽었다. 번호형 테스트 식별자나 누락된 기준 없음.

## 경계 위반

없음. 새 권한·runtime dependency·OAuth scope·WorkManager·자동 동기화·backfill·SAF·백엔드·영속 토큰·계정 식별자·성능 최적화는 도입하지 않았다. 실제 손상/미지원/제약 손상 SQLite는 일반 읽기/쓰기로 변경하지 않고 확인된 reset만 검증된 빈 DB로 복구한다.

## 실패 상세

실제 TalkBack 서비스와 Galaxy 조작을 실행하지 않아 접근성 기준 전체 인수를 완료하지 못했다. 실제 시스템 테마·글자 변경4건 및 자동 이름/역할/스크롤·확인 조작 통과를 TalkBack PASS로 바꾸지 않는다. 재현된 구현 실패 없음. 소유자의 명시적 개발 머지 방침에 따라 이 검증 공백은 코드 리뷰 APPROVE와 분리한다.

## 남은 불확실성

Galaxy·실계정·실제 TalkBack 인수와 기존 P0 미관측 항목은 유지한다. 원본 비교는 기존 소유자 확인을 보존한다. 이번 APPROVE는 개발 머지 적합성으로, P0 완료·전체 스펙 implemented·실계정 업로드의 신규 성공·출시 승인을 뜻하지 않는다.
