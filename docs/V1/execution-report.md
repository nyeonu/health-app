## 실행 결과

- 계획: `docs/V1/plan.md` / 승인 스펙39개 문장 유지.
- 완료한 작업: T1–T6 완료, T7 자동 검증 완료·실제 TalkBack/Galaxy 수용 대기. 현재 소유자는 코멘트 처리와 독립 검증 후 개발 PR 병합을 허용하고 수동 수용/P0와 분리한다.
- 브리프:
  - brief T1 standard · 159줄 · 12330자 · 추정 토큰 4110 · Shared design 참조 1(명시 1, 해소 실패 0) · 변경 파일 2 · 테스트 0 · 작업 블록 22줄 · Shared design 2줄 · Goal 5줄 · 결과: 완료 · 계획 이탈: 승인 범위 내 기술 보정은 plan.md 복구 기록 참조
  - brief T2 standard · 191줄 · 13902자 · 추정 토큰 4634 · Shared design 참조 3(명시 1, 해소 실패 0) · 변경 파일 2 · 테스트 0 · 작업 블록 27줄 · Shared design 29줄 · Goal 5줄 · 결과: 완료 · 계획 이탈: 승인 범위 내 기술 보정은 plan.md 복구 기록 참조
  - brief T3 standard · 217줄 · 17086자 · 추정 토큰 5695 · Shared design 참조 5(명시 1, 해소 실패 0) · 변경 파일 2 · 테스트 0 · 작업 블록 27줄 · Shared design 55줄 · Goal 5줄 · 결과: 완료 · 계획 이탈: 승인 범위 내 기술 보정은 plan.md 복구 기록 참조
  - brief T4 standard · 228줄 · 18560자 · 추정 토큰 6186 · Shared design 참조 4(명시 1, 해소 실패 0) · 변경 파일 2 · 테스트 0 · 작업 블록 31줄 · Shared design 62줄 · Goal 5줄 · 결과: 완료 · 계획 이탈: 승인 범위 내 기술 보정은 plan.md 복구 기록 참조
  - brief T5 standard · 199줄 · 16473자 · 추정 토큰 5491 · Shared design 참조 2(명시 1, 해소 실패 0) · 변경 파일 4 · 테스트 0 · 작업 블록 30줄 · Shared design 34줄 · Goal 5줄 · 결과: 완료 · 계획 이탈: 승인 범위 내 기술 보정은 plan.md 복구 기록 참조
  - brief T6 standard · 179줄 · 14866자 · 추정 토큰 4955 · Shared design 참조 3(명시 1, 해소 실패 0) · 변경 파일 4 · 테스트 0 · 작업 블록 23줄 · Shared design 21줄 · Goal 5줄 · 결과: 완료 · 계획 이탈: 승인 범위 내 기술 보정은 plan.md 복구 기록 참조
  - brief T7 standard · 171줄 · 14542자 · 추정 토큰 4847 · Shared design 참조 2(명시 1, 해소 실패 0) · 변경 파일 5 · 테스트 0 · 작업 블록 26줄 · Shared design 10줄 · Goal 5줄 · 결과: 자동 검증 PASS·물리 확인 대기 · 계획 이탈: 승인 범위 내 기술 보정은 plan.md 복구 기록 참조
- 바뀐 파일:
- `.github/workflows/android-emulator-tests.yml`
- `android/app/src/androidTest/java/com/ny/my/health/HealthAppInstrumentedTest.kt`
- `android/app/src/androidTest/java/com/ny/my/health/HealthHomeScreenInstrumentedTest.kt`
- `android/app/src/androidTest/java/com/ny/my/health/HistoryDatabaseInstrumentedTest.kt`
- `android/app/src/androidTest/java/com/ny/my/health/HistoryProcessRecoveryInstrumentedTest.kt`
- `android/app/src/androidTest/java/com/ny/my/health/SqliteHistoryStoreInstrumentedTest.kt`
- `android/app/src/main/java/com/ny/my/health/HealthHomeScreen.kt`
- `android/app/src/main/java/com/ny/my/health/HistoryDatabase.kt`
- `android/app/src/main/java/com/ny/my/health/HistoryResetRecovery.kt`
- `android/app/src/main/java/com/ny/my/health/HistorySession.kt`
- `android/app/src/main/java/com/ny/my/health/MainActivity.kt`
- `android/app/src/main/java/com/ny/my/health/SqliteHistoryStore.kt`
- `android/app/src/main/java/com/ny/my/health/SyncViewModel.kt`
- `android/app/src/test/java/com/ny/my/health/HistorySessionTest.kt`
- `android/ci/verify-history-process-recovery.sh`
- `android/core/src/main/kotlin/com/ny/my/health/core/SyncHistory.kt`
- `android/core/src/test/kotlin/com/ny/my/health/core/SyncHistoryTest.kt`
- `docs/P0/device-acceptance.md`
- `docs/V1/conformance-report.md`
- `docs/V1/dependency-audit.json`
- `docs/V1/device-acceptance.md`
- `docs/V1/execution-report.md`
- `docs/V1/plan.md`
- `docs/V1/review-followup.md`
- `docs/decisions/ADR-005-2026-11-02-V1-explicit-database-recovery.md`
- `docs/implementation-plan.md`
- 판정: **개발 머지 APPROVE / 소프트웨어 자동 검증 PASS / 전체 인수38PASS·1FAIL(실제 TalkBack/Galaxy 증거 부족)**. 확인된 구현 결함 없음. [독립 검증](conformance-report.md), [코멘트 처리](review-followup.md).
- 자동 복구: 기존 T1–T7 각각2/2 이력 유지. 이후 보정은 소유자 명시적 계속 실행·추가 교정·이번 코멘트 수정 승인에 따랐다. T3 총6회, T7 총8회까지 기록하며 예산을 초기화하지 않았다. 상세 원인·파일·검증·전달 누락 재발과 환경 실행 오류는 계획 복구/체크포인트에 보존했다. 독립 검증에서 발견한 잘못된 reset acknowledgment도 실제 파일 회귀로 보완했다.

## 검증

| 검증 항목 | 실행한 명령 또는 원시 증거 | 결과 |
|---|---|---|
| 로컬 전체 | tooling wrapper로 `:core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:compileDebugAndroidTestKotlin` | BUILD SUCCESSFUL. JVM84(core35+app49), 실패·오류·skip0. lint0errors/기존7warnings |
| 최신 host-test compile | `:app:compileDebugAndroidTestKotlin`, `bash -n ci/verify-history-process-recovery.sh` | PASS; root의 정확한 재실행11s/up-to-date |
| 최신 JVM/APK/lint CI | [41529578661](https://github.com/nyeonu/health-app/actions/runs/41529578661) / source cc30964 | PASS |
| 최신 계측 원시 XML | [41529578551](https://github.com/nyeonu/health-app/actions/runs/41529578551), artifact13181228619 / source cc30964 | normal48:screen20+Activity3+fresh-VM3+DB3+store19. 실패·오류·skip0 |
| 실제 OS 프로세스 종료 | host `results.txt`, PID 소멸/신규PID 검증 후 실제 Activity 상태/SQLite 재열기 |8recovery PASS:success/running/authorizing/awaiting/empty/reset-after/reset-before/reset-empty-before |
| 실제 시스템 설정 | 살아 있는 Activity의 초기 반대 설정→host night/font 변경→새 리소스·조작 검증 |4조합 PASS(light/dark × font1/2), 캡처4 |
| 정리 및 수집 경계 | host8cleanup PASS, complete=true, killed_holds_counted_as_pass=0 | 준비 hold8개 강제종료는 통과가 아님. 기존 DB 거부·소유 fixture만 정리 |
| 합성 캡처 | 원시 PNG60(gallery56+system4), 파일 개수/디코드 확인 및 대표 시각 검토 | PASS. 계정/실기기 실제 성공 증거로 사용하지 않음 |
| 보안·의존성 | Gitleaks 소스/feature history/수정 diff; fresh OSV Maven121 coordinates | 유출0, query errors0/known matches0. Gradle fingerprint 동일; 비공개·미공개 취약점 부재 보증 아님 |
| 독립 검증 | fresh session에 spec/code/raw evidence만 제공; 이전 보고·plan·agent-status 도구 제외 | APPROVE, Critical/Important/Suggestion 없음. 원본 assertion/XML/host/PNG 직접 확인 |
| 실제 Galaxy·TalkBack | [수용 체크리스트](device-acceptance.md) | 미수행·대기. 자동 의미/프로세스 검증과 분리 |

## 테스트 대조

| 계획한 테스트 (출처/층) | 스펙 기준 | 실제 테스트 (파일·이름) | 결과 |
|---|---|---|---|
| T3 completion (성공기준·경계·실패·순서/해당 JVM·SQLite·Compose·host) | After confirmed manual upload and process recreation, the UI restores its reporting date, confirmation time, granted-category coverage and confirmed-file count as historical local success. | committedManualSuccessSurvivesFreshStoreReopenWithCoherentFields; persistedSuccessSurvivesFreshActivityAndViewModelWhileConsentAndPreparedContinuationReset; reopenedActivityRestoresOnlyCommittedMetadata (host success) | PASS(자동 소프트웨어 검증 범위) |
| T2 completion (성공기준·경계·실패·순서/해당 JVM·SQLite·Compose·host) | The history stores only the latest attempt and latest confirmed success, with a format version and bounded metadata, and contains no token, account/file/record identifier, raw measurement, CSV body or provider error text. | History codec rejects forbidden fields and oversized or malformed metadata | PASS(자동 소프트웨어 검증 범위) |
| T5 completion (성공기준·경계·실패·순서/해당 JVM·SQLite·Compose·host) | An attempt records authorization-request time separately from actual execution-start time, so cancelling authorization does not create a fictitious started upload. | 승인 시작 저장 실패는 SDK 실행을 막고 취소 결과는 요청 시각만 기록한다 | PASS(자동 소프트웨어 검증 범위) |
| T5 completion (성공기준·경계·실패·순서/해당 JVM·SQLite·Compose·host) | A failed local start-state write prevents health reading and remote upload, and an uncommitted result is never presented as a durably recorded success. | 실행 시작 저장 실패는 실제 효과 경계의 읽기와 업로드를 모두 막는다; 원격 확인 뒤 결과 저장 실패는 일시 증거와 이전 영속 성공을 분리한다 | PASS(자동 소프트웨어 검증 범위) |
| T3, T5 completion (성공기준·경계·실패·순서/해당 JVM·SQLite·Compose·host) | Failure or interruption while persisting a result preserves the previous committed record and reports an explicit local-history failure without asserting that already-issued remote writes were rolled back. | failedResultTransactionReopensPriorCommittedSuccess; 원격 확인 뒤 결과 저장 실패는 일시 증거와 이전 영속 성공을 분리한다 | PASS(자동 소프트웨어 검증 범위) |
| T4 completion (성공기준·경계·실패·순서/해당 JVM·SQLite·Compose·host) | A persisted started execution without a committed terminal outcome is shown as interrupted or indeterminate after process recreation, with no automatic retry and no fabricated success. | 미완료 실행을 복원하면 재시도 없이 결과 미확정 중단을 저장한다; persistedRunningReopensInterruptedWithoutFinishOrCountAndPreservesPriorSuccess; reopenedActivityRestoresOnlyCommittedMetadata (host running) | PASS(자동 소프트웨어 검증 범위) |
| T5, T7 completion (성공기준·경계·실패·순서/해당 JVM·SQLite·Compose·host) | After process recreation, upload consent is unchecked, no token or prepared continuation is restored, and no health read or network write occurs until fresh manual consent/authorization/continuation. | 재열기와 지연 승인 결과는 토큰이나 명시적 계속 및 외부 실행을 복원하지 않는다; persistedSuccessSurvivesFreshActivityAndViewModelWhileConsentAndPreparedContinuationReset | PASS(자동 소프트웨어 검증 범위) |
| T2 completion (성공기준·경계·실패·순서/해당 JVM·SQLite·Compose·host) | A later failed, empty, permission-required or interrupted attempt updates latest-attempt status while retaining the previous independently confirmed success. | Later non-success outcomes retain independently committed prior success | PASS(자동 소프트웨어 검증 범위) |
| T4 completion (성공기준·경계·실패·순서/해당 JVM·SQLite·Compose·host) | Duplicate UI actions cannot run overlapping manual executions or let a stale completion overwrite a newer local forget operation. | 중복 시작과 지연 완료는 확인된 삭제를 덮어쓰지 못한다 | PASS(자동 소프트웨어 검증 범위) |
| T3 completion (성공기준·경계·실패·순서/해당 JVM·SQLite·Compose·host) | Missing history starts as no local history, while unsupported or corrupt stored history produces a recoverable explicit state and never fabricated zero measurements or success. | absentUnsupportedCorruptAndUnavailableStoresHaveDistinctReopenResults | PASS(자동 소프트웨어 검증 범위) |
| T6 completion (성공기준·경계·실패·순서/해당 JVM·SQLite·Compose·host) | The UI distinguishes authorization time, actual execution start, latest outcome and previous confirmed reporting date, and indicates partial authorized scope rather than complete five-category coverage. | detailsDistinguishAuthorizationExecutionAndResultTimesWithPartialScope | PASS(자동 소프트웨어 검증 범위) |
| T6, T5 completion (성공기준·경계·실패·순서/해당 JVM·SQLite·Compose·host) | Restored history is labelled as local historical evidence whose current Google-account association and upstream completeness are unverified, and is never used to skip a fresh read/upload or advance a backfill cursor. | restoredSuccessIsLabelledHistoricalAndNeverBypassesManualExecution | PASS(자동 소프트웨어 검증 범위) |
| T4, T3 completion (성공기준·경계·실패·순서/해당 JVM·SQLite·Compose·host) | A confirmed successful local forget clears persisted history and in-memory consent/token/status without deleting Drive files or claiming to revoke Health Connect or Google grants; failure and interruption follow the deletion criteria below. | 확인된 로컬 삭제는 인증을 무효화하고 다시 열면 빈 기록이다; actualUiCancelPreservesHistoryAndConfirmedLocalDeletionReopensDurablyEmpty | PASS(자동 소프트웨어 검증 범위) |
| T5, T7 completion (성공기준·경계·실패·순서/해당 JVM·SQLite·Compose·host) | The same reporting-day paths, CSV payload rules, explicit EMPTY semantics, granted-type filtering and private Drive target checks remain unchanged under the existing meaningful regressions. | CSV headers ordering quoting and missing values follow v0_1; Empty and denied reads never upload or report success; Successful partial-grant sync uploads only authorized populated datasets; Ambiguous or shared targets are rejected before upload | PASS(자동 소프트웨어 검증 범위) |
| T6 completion (성공기준·경계·실패·순서/해당 JVM·SQLite·Compose·host) | The collapsed home shows the target reporting date, current attempt/error state, independently retained last confirmed success and the next permitted action without opening details. | collapsedHomeShowsCurrentErrorPriorSuccessTargetDayAndNextAction | PASS(자동 소프트웨어 검증 범위) |
| T6 completion (성공기준·경계·실패·순서/해당 JVM·SQLite·Compose·host) | Last-success date, timestamp, coverage and file count all come from the same committed success record; a later failed attempt's count is never displayed as that success's count. | successCardCountDateTimeAndCoverageComeFromOneCommittedSuccess | PASS(자동 소프트웨어 검증 범위) |
| T6 completion (성공기준·경계·실패·순서/해당 JVM·SQLite·Compose·host) | Expanded details distinguish authorization-request time, actual execution-start time and terminal time, displaying absent times as “미시작” or “확인 불가” as appropriate rather than inventing timestamps. | detailsRenderMissingExecutionAndTerminalTimesWithoutInventedTimestamps; unreadableAndLoadingExecutionStartRemainsUnknownWhileKnownUnstartedHistoryShowsNotStarted | PASS(자동 소프트웨어 검증 범위) |
| T6 completion (성공기준·경계·실패·순서/해당 JVM·SQLite·Compose·host) | A failed or interrupted latest attempt remains visible alongside an earlier success; EMPTY is shown as no records to export, not successful upload. | latestFailureAndEmptyOutcomeRemainDistinctFromPriorSuccessfulUpload | PASS(자동 소프트웨어 검증 범위) |
| T6 completion (성공기준·경계·실패·순서/해당 JVM·SQLite·Compose·host) | Authorization and explicit upload continuation remain separate actions, with no automatic read/upload caused by expanding details, restoring the screen or loading history. | expandingDetailsOrRestoringUiNeverAuthorizesReadsOrUploads | PASS(자동 소프트웨어 검증 범위) |
| T7 completion (성공기준·경계·실패·순서/해당 JVM·SQLite·Compose·host) | The screen remains readable and operable with system light/dark themes, enlarged system text and TalkBack; critical state, confirmation controls and next actions must not be clipped, overlap or rely on color alone. | bothThemesAtDoubleTextScaleKeepManualAndRecoveryControlsNamedAndReachable; liveSystemThemeAndFontChangeReachActivityAndControls (host system settings); 실제 TalkBack·Galaxy 검증 대기 | 자동 부분 PASS / 실제 TalkBack·Galaxy 수용 대기 |
| T7 completion (성공기준·경계·실패·순서/해당 JVM·SQLite·Compose·host) | Synthetic UI previews or screenshots cover no history, committed success, authorization pending, running, interrupted, history-read failure and deletion failure; these are review artifacts, not evidence of real account/device success. | syntheticGalleryCapturesSevenStatesInBothThemesAndDoubleTextScale | PASS(자동 소프트웨어 검증 범위) |
| T4, T6 completion (성공기준·경계·실패·순서/해당 JVM·SQLite·Compose·host) | Cancelling the deletion dialog changes neither durable history nor current consent/token state. | 삭제 확인을 취소하면 인증과 영속 기록을 보존한다; actualUiCancelPreservesHistoryAndConfirmedLocalDeletionReopensDurablyEmpty | PASS(자동 소프트웨어 검증 범위) |
| T4, T6 completion (성공기준·경계·실패·순서/해당 JVM·SQLite·Compose·host) | Deletion is unavailable while permission/authorization is pending, a sync is running, or a history read/write/delete is in progress; the reason is visible and the operation is rejected even if a stale UI event arrives. | 삭제 진입 검사는 권한 대기와 새로고침 및 실행과 저장소 작업 중 오래된 이벤트를 거부한다; busySessionsRejectDeleteRequestsAndExplainDisabledDeletion | PASS(자동 소프트웨어 검증 범위) |
| T4 completion (성공기준·경계·실패·순서/해당 JVM·SQLite·Compose·host) | Confirming deletion immediately clears in-memory consent, token and prepared continuation before durable deletion is attempted. | 삭제 확인은 첫 저장소 중단점 전에 인증을 무효화한다 | PASS(자동 소프트웨어 검증 범위) |
| T3 completion (성공기준·경계·실패·순서/해당 JVM·SQLite·Compose·host) | A durable empty-state commit clears both latest attempt and latest success together; a fresh store reopen after acknowledged completion finds no local history. | acknowledgedEmptyStateTransactionReopensWithoutAttemptOrSuccess; actualUiCancelPreservesHistoryAndConfirmedLocalDeletionReopensDurablyEmpty | PASS(자동 소프트웨어 검증 범위) |
| T4, T6 completion (성공기준·경계·실패·순서/해당 JVM·SQLite·Compose·host) | If deletion fails, show “이력 삭제에 실패했습니다. 저장된 이력이 남아 있을 수 있습니다.” and provide “삭제 다시 시도”; do not show “삭제 완료” or restore consent/token. | 삭제 실패는 완료로 주장하지 않고 인증 복원 없이 재시도를 허용한다; failedDeletionOffersRetryWithoutClaimingCompletionOrRestoringCredentials | PASS(자동 소프트웨어 검증 범위) |
| T4, T6 completion (성공기준·경계·실패·순서/해당 JVM·SQLite·Compose·host) | After deletion failure, any retained readable record is marked “삭제되지 않은 기존 이력”; unreadable storage remains explicitly unknown rather than appearing empty. | 삭제 실패는 읽을 수 있는 기록만 유지로 표시하고 읽기 불가는 미확정으로 남긴다; failedDeletionLabelsRetainedHistoryWhileUnreadableHistoryRemainsUnknown | PASS(자동 소프트웨어 검증 범위) |
| T4 completion (성공기준·경계·실패·순서/해당 JVM·SQLite·Compose·host) | After deletion failure, new authorization/upload stays disabled for the current session until deletion succeeds or the user explicitly chooses “삭제 취소·기존 이력 유지” and the store is successfully reloaded; neither action restores consent/token. | 삭제 실패는 명시적 유지와 성공한 재읽기 전까지 새 승인을 차단한다 | PASS(자동 소프트웨어 검증 범위) |
| T3, T7 completion (성공기준·경계·실패·순서/해당 JVM·SQLite·Compose·host) | If the process ends during deletion, reopening shows the actual committed state: empty after committed clearing, otherwise the retained prior history or an explicit read error; it never claims an interrupted deletion completed. | interruptedResetBeforeCommitReopensOldStateAndAfterCommitReopensEmpty; reopenedActivityRestoresOnlyCommittedMetadata (host reset-before/reset-after/reset-empty-before); Galaxy 관찰 대기 | PASS(자동 소프트웨어 검증 범위) |
| T4, T3 completion (성공기준·경계·실패·순서/해당 JVM·SQLite·Compose·host) | An uncommitted deletion intention need not survive process loss; persisted history may reappear after an interrupted/failed deletion, but a durably acknowledged deletion must not be undone by stale callbacks, result writes or recovery. | 복구 후 이전 세대 콜백은 확인된 초기화를 다시 쓰지 못한다; acknowledgedEmptyStateTransactionReopensWithoutAttemptOrSuccess | PASS(자동 소프트웨어 검증 범위) |
| T3, T4 completion (성공기준·경계·실패·순서/해당 JVM·SQLite·Compose·host) | Deletion is idempotent: deleting already-empty history succeeds without remote calls, and any deletion failure remains a storage error rather than a fake success. | 빈 기록의 반복 삭제는 로컬이며 영속화 실패를 성공으로 바꾸지 않는다; repeatedEmptyDeletionIsLocalAndPersistenceFailureNeverBecomesSuccess | PASS(자동 소프트웨어 검증 범위) |
| T6, T4 completion (성공기준·경계·실패·순서/해당 JVM·SQLite·Compose·host) | Local deletion does not delete Drive exports, revoke external grants, clear Health Connect records or promise forensic secure erasure. | deleteConfirmationStatesLocalOnlyScopeAndTriggersNoExternalDeletion | PASS(자동 소프트웨어 검증 범위) |
| T2 completion (성공기준·경계·실패·순서/해당 JVM·SQLite·Compose·host) | A persisted pending authorization without execution start restores as “인증 단계에서 종료됨 · 업로드 미시작”; persisted authorization success awaiting continuation restores as “업로드 시작 전 종료됨 · 다시 인증해 주세요.” | Recovery distinguishes abandoned authorization from awaiting-upload interruption | PASS(자동 소프트웨어 검증 범위) |
| T2 completion (성공기준·경계·실패·순서/해당 JVM·SQLite·Compose·host) | A persisted running attempt without a committed terminal result restores as interrupted/indeterminate, without fabricated completion time or guaranteed uploaded-file count; remote files may already exist. | Running recovery never invents terminal time or confirmed upload count; persistedRunningReopensInterruptedWithoutFinishOrCountAndPreservesPriorSuccess | PASS(자동 소프트웨어 검증 범위) |
| T2, T3 completion (성공기준·경계·실패·순서/해당 JVM·SQLite·Compose·host) | A committed terminal result restores its actual outcome, while failed/empty/interrupted attempts retain the independently committed previous success. | Terminal restoration preserves actual outcome and prior independent success; terminalRestorationPreservesActualOutcomeAndPriorIndependentSuccess | PASS(자동 소프트웨어 검증 범위) |
| T3 completion (성공기준·경계·실패·순서/해당 JVM·SQLite·Compose·host) | Unsupported-format, corrupt and unavailable history are distinguished from an absent history store; no automatic reset or overwrite occurs merely because reading failed. | readingIncompatibleOrCorruptPayloadNeverOverwritesItAndExplicitResetCommitsEmpty; unsupportedSchemaAndCorruptDatabasePreserveReadsAndWritesUntilExplicitReset; malformedVersionOneConstraintsAndHistoryTriggersRequireExplicitReplacement; canonicalSchemaWithPreviouslyIgnoredCheckRequiresConfirmedResetToClearEveryRow; unknownWalSchemaReadsAndOrdinaryWritesDoNotConvertOrOverwriteItsDatabaseFamily; genuineHotRollbackJournalStaysExplicitlyUnavailableUntilConfirmedReset; unavailableDatabaseReadsAndFailedResetLeaveTheStoreUnavailable | PASS(자동 소프트웨어 검증 범위) |
| T4 completion (성공기준·경계·실패·순서/해당 JVM·SQLite·Compose·host) | While history is unreadable or unsupported, new authorization/upload is blocked until an explicit reload succeeds or a user-confirmed local reset durably succeeds; reset failure follows the deletion-failure rules. | 읽기 불가 기록은 명시적 재읽기 또는 초기화 성공 전까지 업로드를 차단한다 | PASS(자동 소프트웨어 검증 범위) |
| T4, T3 completion (성공기준·경계·실패·순서/해당 JVM·SQLite·Compose·host) | Persisting a recovered status uses the same atomic failure rules as ordinary results; if recovery persistence fails, show the derived interruption together with a local-history error and do not claim durable recovery. | 복구 저장 실패는 파생 중단과 로컬 저장 오류를 표시한다; recoverySaveFailureLeavesPersistedRunningAttemptAndDerivableInterruption; recoveryWriteFailureShowsDerivedOutcomeAndUnknownResultWhilePreservingPriorSuccessAndBlockingNewUpload | PASS(자동 소프트웨어 검증 범위) |
| T5, T7 completion (성공기준·경계·실패·순서/해당 JVM·SQLite·Compose·host) | App reopen never restores tokens, upload consent or a prepared continuation, even after failed deletion or abandoned authorization. | 재열기와 지연 승인 결과는 토큰이나 명시적 계속 및 외부 실행을 복원하지 않는다; persistedSuccessSurvivesFreshActivityAndViewModelWhileConsentAndPreparedContinuationReset | PASS(자동 소프트웨어 검증 범위) |

- 추적성 표 갱신: 스펙 원문39개와 모든 실제 함수명을 계획3절/이 표에 갱신했다. root가 직접 원문·함수 존재·실행 XML 및 host 호출을 대조했다.
- skip·비활성화된 테스트: JVM/normal Android0. host-only 클래스는 일반 발견에서 제외하되 외부 프로세스 조정으로 별도 실행하며 준비 hold를 성공으로 세지 않는다.
- 신규·변경된 테스트 파일: SyncHistoryTest.kt, HistorySessionTest.kt, HealthAppInstrumentedTest.kt, HealthHomeScreenInstrumentedTest.kt, HistoryDatabaseInstrumentedTest.kt, SqliteHistoryStoreInstrumentedTest.kt, HistoryProcessRecoveryInstrumentedTest.kt 및 host script.
- 커버리지 증감: 라인% 추적 없음. 실제 malformed schema·constraint·WAL/hot journal·교체 중단/정리·실제 OS 종료·실제 시스템 설정 실행을 추가했다.

## 남은 문제

- 실행하지 못한 검증: 실제 TalkBack·Galaxy·실계정 인수. 기존 P0 미확인과 별도 key-backup 문제는 그대로 유지하며 원본 비교 완료라는 소유자 확인은 보존한다.
- 미커버 분기: 현재 독립 검증에서 새 차단 결함 없음. 실제 하드웨어 전원 손실·법의학적 완전 삭제를 보장하지 않는다.
- 다시 판단할 조건: 수용에서 실제 실패가 발견되거나 다중 Android 프로세스/새 스키마/목적지/인증 정책이 도입되면 재검토. 초기 brief 전달 누락의 재발은 계획에 기록했으며 assembler 회귀 검증 확장 필요. 새 실행자의 과거 보고서 노출은 최종 검증자로 사용하지 않고 fresh verifier로 대체했다.

## 다음 단계

소유자의 명시적 요청에 따라 이슈 없는 개발 PR 병합을 진행한다. 실제 Galaxy/TalkBack/P0 수용과 출시 승인은 분리하고 spec approved·ADR Proposed 상태를 유지한다. 기존 후속 요청인 업로드 병목 측정은 별도 업무로 시작하며 이 PR에 최적화·SAF·스케줄·백필·백엔드를 섞지 않는다.
