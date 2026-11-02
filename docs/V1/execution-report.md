## 실행 결과

- 계획: `docs/V1/plan.md` (승인 스펙 참조 유지)
- 완료한 작업: T1–T6 검증 완료. T7 자동 검증 완료, 실제 Galaxy·프로세스 손실·시스템 설정·TalkBack 관찰 대기.
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
- `android/app/src/androidTest/java/com/ny/my/health/SqliteHistoryStoreInstrumentedTest.kt`
- `android/app/src/main/java/com/ny/my/health/HealthHomeScreen.kt`
- `android/app/src/main/java/com/ny/my/health/HistoryDatabase.kt`
- `android/app/src/main/java/com/ny/my/health/HistorySession.kt`
- `android/app/src/main/java/com/ny/my/health/MainActivity.kt`
- `android/app/src/main/java/com/ny/my/health/SqliteHistoryStore.kt`
- `android/app/src/main/java/com/ny/my/health/SyncViewModel.kt`
- `android/app/src/test/java/com/ny/my/health/HistorySessionTest.kt`
- `android/core/src/main/kotlin/com/ny/my/health/core/SyncHistory.kt`
- `android/core/src/test/kotlin/com/ny/my/health/core/SyncHistoryTest.kt`
- `docs/P0/device-acceptance.md`
- `docs/V1/dependency-audit.json`
- `docs/V1/device-acceptance.md`
- `docs/V1/execution-report.md`
- `docs/V1/conformance-report.md`
- `docs/V1/plan.md`
- `docs/implementation-plan.md`
- 판정: **자동 검증 통과 / 전체 워크플로우 미완료**. 독립 적합성은34/39 PASS,5개는 실제 기기 실행 증거 부족으로 FAIL; 확인된 구현 결함 없음. 상세는 [conformance-report.md](conformance-report.md). 실제 Galaxy 강제 종료·TalkBack 미확인으로 전체 적합성 PASS 및 출시 승인을 주장하지 않는다. 알려진 표시 오류를 수정하고 자동 검증을 끝까지 수행한다.
- 자동 복구: T1–T7 각각 2/2 사용 이력 유지. Shared design 추출·전달 누락은 여러 작업에 재발했으며 첫 보정으로 함께 기록했다. T4 추가 브리프 전달 보완도 기록됨. T5·T6의 추가 정확성 보정은 소유자의 명시적 승인에 따라 진행하며 예산을 초기화하지 않는다. 세부 원인·범위·검증은 계획의 복구 기록 및 체크포인트를 참조한다.

## 검증

| 검증 항목 | 실행한 명령 또는 증거 | 결과 |
|---|---|---|
| T6 선행 계측 | CI run 41434453899, source 79ffd0f | 34개 통과: 화면 17, Activity 3, DB 3, 저장소 11. 실패·오류·skip 없음. T7 증거 아님 |
| T6 선행 JVM/APK/lint | CI run 41434453978 | 통과. T7 증거 아님 |
| 상위 실행자 로컬 전체 검증 | `:core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:compileDebugAndroidTestKotlin` | 최종 source b97ff63에서 BUILD SUCCESSFUL in49s, XML84개(core35+app49), 실패·오류·skip0. lint 오류0 / 기존 경고7 |
| T7 컴파일 | `python /workspace/tooling/run-health-command.py ./gradlew --offline --no-daemon :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin` (`android`에서 실행) | PASS — BUILD SUCCESSFUL in 20s; 30 actionable tasks: 1 executed, 29 up-to-date |
| T7 에뮬레이터 | CI 41436772139 / source c63642f | 통과:39개 실패·오류·skip0. PNG56개 다운로드, root4개 샘플 시각 검토. 최신 교정 전 증거 |
| 최신 Android/PNG | CI41438677211 / source b97ff63 | PASS. XML40(화면20+Activity3+새ViewModel3+DB3+store11), 실패·오류·skip0. PNG56개, 7상태×2테마×2글꼴×2위치,1080×2400. root7상태 대표7개 시각검토. |
| Galaxy 강제 종료·TalkBack | `docs/V1/device-acceptance.md` | 모두 대기 |

## 테스트 대조

| 계획한 테스트 (출처/층) | 스펙 기준 | 실제 테스트 (파일 · 테스트 이름) | 결과 |
|---|---|---|---|
| T3 completion (승인 기준/경계/상태/실패, JVM·SQLite·Compose) | After confirmed manual upload and process recreation, the UI restores its reporting date, confirmation time, granted-category coverage and confirmed-file count as historical local success. | committedManualSuccessSurvivesFreshStoreReopenWithCoherentFields; persistedSuccessSurvivesFreshActivityAndViewModelWhileConsentAndPreparedContinuationReset | JVM84 / Android40 통과, 실패·오류·skip0. 명시된 물리 확인은 대기 |
| T2 completion (승인 기준/경계/상태/실패, JVM·SQLite·Compose) | The history stores only the latest attempt and latest confirmed success, with a format version and bounded metadata, and contains no token, account/file/record identifier, raw measurement, CSV body or provider error text. | History codec rejects forbidden fields and oversized or malformed metadata | JVM84 / Android40 통과, 실패·오류·skip0. 명시된 물리 확인은 대기 |
| T5 completion (승인 기준/경계/상태/실패, JVM·SQLite·Compose) | An attempt records authorization-request time separately from actual execution-start time, so cancelling authorization does not create a fictitious started upload. | 승인 시작 저장 실패는 SDK 실행을 막고 취소 결과는 요청 시각만 기록한다 | JVM84 / Android40 통과, 실패·오류·skip0. 명시된 물리 확인은 대기 |
| T5 completion (승인 기준/경계/상태/실패, JVM·SQLite·Compose) | A failed local start-state write prevents health reading and remote upload, and an uncommitted result is never presented as a durably recorded success. | 실행 시작 저장 실패는 실제 효과 경계의 읽기와 업로드를 모두 막는다; 원격 확인 뒤 결과 저장 실패는 일시 증거와 이전 영속 성공을 분리한다 | JVM84 / Android40 통과, 실패·오류·skip0. 명시된 물리 확인은 대기 |
| T3, T5 completion (승인 기준/경계/상태/실패, JVM·SQLite·Compose) | Failure or interruption while persisting a result preserves the previous committed record and reports an explicit local-history failure without asserting that already-issued remote writes were rolled back. | failedResultTransactionReopensPriorCommittedSuccess; 원격 확인 뒤 결과 저장 실패는 일시 증거와 이전 영속 성공을 분리한다 | JVM84 / Android40 통과, 실패·오류·skip0. 명시된 물리 확인은 대기 |
| T4 completion (승인 기준/경계/상태/실패, JVM·SQLite·Compose) | A persisted started execution without a committed terminal outcome is shown as interrupted or indeterminate after process recreation, with no automatic retry and no fabricated success. | 미완료 실행을 복원하면 재시도 없이 결과 미확정 중단을 저장한다; persistedRunningReopensInterruptedWithoutFinishOrCountAndPreservesPriorSuccess | JVM84 / Android40 통과, 실패·오류·skip0. 명시된 물리 확인은 대기 |
| T5, T7 completion (승인 기준/경계/상태/실패, JVM·SQLite·Compose) | After process recreation, upload consent is unchecked, no token or prepared continuation is restored, and no health read or network write occurs until fresh manual consent/authorization/continuation. | 재열기와 지연 승인 결과는 토큰이나 명시적 계속 및 외부 실행을 복원하지 않는다; persistedSuccessSurvivesFreshActivityAndViewModelWhileConsentAndPreparedContinuationReset | JVM84 / Android40 통과, 실패·오류·skip0. 명시된 물리 확인은 대기 |
| T2 completion (승인 기준/경계/상태/실패, JVM·SQLite·Compose) | A later failed, empty, permission-required or interrupted attempt updates latest-attempt status while retaining the previous independently confirmed success. | Later non-success outcomes retain independently committed prior success | JVM84 / Android40 통과, 실패·오류·skip0. 명시된 물리 확인은 대기 |
| T4 completion (승인 기준/경계/상태/실패, JVM·SQLite·Compose) | Duplicate UI actions cannot run overlapping manual executions or let a stale completion overwrite a newer local forget operation. | 중복 시작과 지연 완료는 확인된 삭제를 덮어쓰지 못한다 | JVM84 / Android40 통과, 실패·오류·skip0. 명시된 물리 확인은 대기 |
| T3 completion (승인 기준/경계/상태/실패, JVM·SQLite·Compose) | Missing history starts as no local history, while unsupported or corrupt stored history produces a recoverable explicit state and never fabricated zero measurements or success. | absentUnsupportedCorruptAndUnavailableStoresHaveDistinctReopenResults | JVM84 / Android40 통과, 실패·오류·skip0. 명시된 물리 확인은 대기 |
| T6 completion (승인 기준/경계/상태/실패, JVM·SQLite·Compose) | The UI distinguishes authorization time, actual execution start, latest outcome and previous confirmed reporting date, and indicates partial authorized scope rather than complete five-category coverage. | detailsDistinguishAuthorizationExecutionAndResultTimesWithPartialScope | JVM84 / Android40 통과, 실패·오류·skip0. 명시된 물리 확인은 대기 |
| T6, T5 completion (승인 기준/경계/상태/실패, JVM·SQLite·Compose) | Restored history is labelled as local historical evidence whose current Google-account association and upstream completeness are unverified, and is never used to skip a fresh read/upload or advance a backfill cursor. | restoredSuccessIsLabelledHistoricalAndNeverBypassesManualExecution | JVM84 / Android40 통과, 실패·오류·skip0. 명시된 물리 확인은 대기 |
| T4, T3 completion (승인 기준/경계/상태/실패, JVM·SQLite·Compose) | A confirmed successful local forget clears persisted history and in-memory consent/token/status without deleting Drive files or claiming to revoke Health Connect or Google grants; failure and interruption follow the deletion criteria below. | 확인된 로컬 삭제는 인증을 무효화하고 다시 열면 빈 기록이다; actualUiCancelPreservesHistoryAndConfirmedLocalDeletionReopensDurablyEmpty | JVM84 / Android40 통과, 실패·오류·skip0. 명시된 물리 확인은 대기 |
| T5, T7 completion (승인 기준/경계/상태/실패, JVM·SQLite·Compose) | The same reporting-day paths, CSV payload rules, explicit EMPTY semantics, granted-type filtering and private Drive target checks remain unchanged under the existing meaningful regressions. | CSV headers ordering quoting and missing values follow v0_1; Empty and denied reads never upload or report success; Successful partial-grant sync uploads only authorized populated datasets; Ambiguous or shared targets are rejected before upload | JVM84 / Android40 통과, 실패·오류·skip0. 명시된 물리 확인은 대기 |
| T6 completion (승인 기준/경계/상태/실패, JVM·SQLite·Compose) | The collapsed home shows the target reporting date, current attempt/error state, independently retained last confirmed success and the next permitted action without opening details. | collapsedHomeShowsCurrentErrorPriorSuccessTargetDayAndNextAction | JVM84 / Android40 통과, 실패·오류·skip0. 명시된 물리 확인은 대기 |
| T6 completion (승인 기준/경계/상태/실패, JVM·SQLite·Compose) | Last-success date, timestamp, coverage and file count all come from the same committed success record; a later failed attempt's count is never displayed as that success's count. | successCardCountDateTimeAndCoverageComeFromOneCommittedSuccess | JVM84 / Android40 통과, 실패·오류·skip0. 명시된 물리 확인은 대기 |
| T6 completion (승인 기준/경계/상태/실패, JVM·SQLite·Compose) | Expanded details distinguish authorization-request time, actual execution-start time and terminal time, displaying absent times as “미시작” or “확인 불가” as appropriate rather than inventing timestamps. | detailsRenderMissingExecutionAndTerminalTimesWithoutInventedTimestamps; unreadableAndLoadingExecutionStartRemainsUnknownWhileKnownUnstartedHistoryShowsNotStarted | JVM84 / Android40 통과, 실패·오류·skip0. 명시된 물리 확인은 대기 |
| T6 completion (승인 기준/경계/상태/실패, JVM·SQLite·Compose) | A failed or interrupted latest attempt remains visible alongside an earlier success; EMPTY is shown as no records to export, not successful upload. | latestFailureAndEmptyOutcomeRemainDistinctFromPriorSuccessfulUpload | JVM84 / Android40 통과, 실패·오류·skip0. 명시된 물리 확인은 대기 |
| T6 completion (승인 기준/경계/상태/실패, JVM·SQLite·Compose) | Authorization and explicit upload continuation remain separate actions, with no automatic read/upload caused by expanding details, restoring the screen or loading history. | expandingDetailsOrRestoringUiNeverAuthorizesReadsOrUploads | JVM84 / Android40 통과, 실패·오류·skip0. 명시된 물리 확인은 대기 |
| T7 completion (승인 기준/경계/상태/실패, JVM·SQLite·Compose) | The screen remains readable and operable with system light/dark themes, enlarged system text and TalkBack; critical state, confirmation controls and next actions must not be clipped, overlap or rely on color alone. | bothThemesAtDoubleTextScaleKeepManualAndRecoveryControlsNamedAndReachable; 실제 TalkBack·Galaxy 검증 대기 | JVM84 / Android40 통과, 실패·오류·skip0. 명시된 물리 확인은 대기 |
| T7 completion (승인 기준/경계/상태/실패, JVM·SQLite·Compose) | Synthetic UI previews or screenshots cover no history, committed success, authorization pending, running, interrupted, history-read failure and deletion failure; these are review artifacts, not evidence of real account/device success. | syntheticGalleryCapturesSevenStatesInBothThemesAndDoubleTextScale | JVM84 / Android40 통과, 실패·오류·skip0. 명시된 물리 확인은 대기 |
| T4, T6 completion (승인 기준/경계/상태/실패, JVM·SQLite·Compose) | Cancelling the deletion dialog changes neither durable history nor current consent/token state. | 삭제 확인을 취소하면 인증과 영속 기록을 보존한다; actualUiCancelPreservesHistoryAndConfirmedLocalDeletionReopensDurablyEmpty | JVM84 / Android40 통과, 실패·오류·skip0. 명시된 물리 확인은 대기 |
| T4, T6 completion (승인 기준/경계/상태/실패, JVM·SQLite·Compose) | Deletion is unavailable while permission/authorization is pending, a sync is running, or a history read/write/delete is in progress; the reason is visible and the operation is rejected even if a stale UI event arrives. | 삭제 진입 검사는 권한 대기와 새로고침 및 실행과 저장소 작업 중 오래된 이벤트를 거부한다; busySessionsRejectDeleteRequestsAndExplainDisabledDeletion | JVM84 / Android40 통과, 실패·오류·skip0. 명시된 물리 확인은 대기 |
| T4 completion (승인 기준/경계/상태/실패, JVM·SQLite·Compose) | Confirming deletion immediately clears in-memory consent, token and prepared continuation before durable deletion is attempted. | 삭제 확인은 첫 저장소 중단점 전에 인증을 무효화한다 | JVM84 / Android40 통과, 실패·오류·skip0. 명시된 물리 확인은 대기 |
| T3 completion (승인 기준/경계/상태/실패, JVM·SQLite·Compose) | A durable empty-state commit clears both latest attempt and latest success together; a fresh store reopen after acknowledged completion finds no local history. | acknowledgedEmptyStateTransactionReopensWithoutAttemptOrSuccess; actualUiCancelPreservesHistoryAndConfirmedLocalDeletionReopensDurablyEmpty | JVM84 / Android40 통과, 실패·오류·skip0. 명시된 물리 확인은 대기 |
| T4, T6 completion (승인 기준/경계/상태/실패, JVM·SQLite·Compose) | If deletion fails, show “이력 삭제에 실패했습니다. 저장된 이력이 남아 있을 수 있습니다.” and provide “삭제 다시 시도”; do not show “삭제 완료” or restore consent/token. | 삭제 실패는 완료로 주장하지 않고 인증 복원 없이 재시도를 허용한다; failedDeletionOffersRetryWithoutClaimingCompletionOrRestoringCredentials | JVM84 / Android40 통과, 실패·오류·skip0. 명시된 물리 확인은 대기 |
| T4, T6 completion (승인 기준/경계/상태/실패, JVM·SQLite·Compose) | After deletion failure, any retained readable record is marked “삭제되지 않은 기존 이력”; unreadable storage remains explicitly unknown rather than appearing empty. | 삭제 실패는 읽을 수 있는 기록만 유지로 표시하고 읽기 불가는 미확정으로 남긴다; failedDeletionLabelsRetainedHistoryWhileUnreadableHistoryRemainsUnknown | JVM84 / Android40 통과, 실패·오류·skip0. 명시된 물리 확인은 대기 |
| T4 completion (승인 기준/경계/상태/실패, JVM·SQLite·Compose) | After deletion failure, new authorization/upload stays disabled for the current session until deletion succeeds or the user explicitly chooses “삭제 취소·기존 이력 유지” and the store is successfully reloaded; neither action restores consent/token. | 삭제 실패는 명시적 유지와 성공한 재읽기 전까지 새 승인을 차단한다 | JVM84 / Android40 통과, 실패·오류·skip0. 명시된 물리 확인은 대기 |
| T3, T7 completion (승인 기준/경계/상태/실패, JVM·SQLite·Compose) | If the process ends during deletion, reopening shows the actual committed state: empty after committed clearing, otherwise the retained prior history or an explicit read error; it never claims an interrupted deletion completed. | interruptedResetBeforeCommitReopensOldStateAndAfterCommitReopensEmpty; 실제 기기 프로세스 종료 경계 대기 | JVM84 / Android40 통과, 실패·오류·skip0. 명시된 물리 확인은 대기 |
| T4, T3 completion (승인 기준/경계/상태/실패, JVM·SQLite·Compose) | An uncommitted deletion intention need not survive process loss; persisted history may reappear after an interrupted/failed deletion, but a durably acknowledged deletion must not be undone by stale callbacks, result writes or recovery. | 복구 후 이전 세대 콜백은 확인된 초기화를 다시 쓰지 못한다; acknowledgedEmptyStateTransactionReopensWithoutAttemptOrSuccess | JVM84 / Android40 통과, 실패·오류·skip0. 명시된 물리 확인은 대기 |
| T3, T4 completion (승인 기준/경계/상태/실패, JVM·SQLite·Compose) | Deletion is idempotent: deleting already-empty history succeeds without remote calls, and any deletion failure remains a storage error rather than a fake success. | 빈 기록의 반복 삭제는 로컬이며 영속화 실패를 성공으로 바꾸지 않는다; repeatedEmptyDeletionIsLocalAndPersistenceFailureNeverBecomesSuccess | JVM84 / Android40 통과, 실패·오류·skip0. 명시된 물리 확인은 대기 |
| T6, T4 completion (승인 기준/경계/상태/실패, JVM·SQLite·Compose) | Local deletion does not delete Drive exports, revoke external grants, clear Health Connect records or promise forensic secure erasure. | deleteConfirmationStatesLocalOnlyScopeAndTriggersNoExternalDeletion | JVM84 / Android40 통과, 실패·오류·skip0. 명시된 물리 확인은 대기 |
| T2 completion (승인 기준/경계/상태/실패, JVM·SQLite·Compose) | A persisted pending authorization without execution start restores as “인증 단계에서 종료됨 · 업로드 미시작”; persisted authorization success awaiting continuation restores as “업로드 시작 전 종료됨 · 다시 인증해 주세요.” | Recovery distinguishes abandoned authorization from awaiting-upload interruption | JVM84 / Android40 통과, 실패·오류·skip0. 명시된 물리 확인은 대기 |
| T2 completion (승인 기준/경계/상태/실패, JVM·SQLite·Compose) | A persisted running attempt without a committed terminal result restores as interrupted/indeterminate, without fabricated completion time or guaranteed uploaded-file count; remote files may already exist. | Running recovery never invents terminal time or confirmed upload count; persistedRunningReopensInterruptedWithoutFinishOrCountAndPreservesPriorSuccess | JVM84 / Android40 통과, 실패·오류·skip0. 명시된 물리 확인은 대기 |
| T2, T3 completion (승인 기준/경계/상태/실패, JVM·SQLite·Compose) | A committed terminal result restores its actual outcome, while failed/empty/interrupted attempts retain the independently committed previous success. | Terminal restoration preserves actual outcome and prior independent success; terminalRestorationPreservesActualOutcomeAndPriorIndependentSuccess | JVM84 / Android40 통과, 실패·오류·skip0. 명시된 물리 확인은 대기 |
| T3 completion (승인 기준/경계/상태/실패, JVM·SQLite·Compose) | Unsupported-format, corrupt and unavailable history are distinguished from an absent history store; no automatic reset or overwrite occurs merely because reading failed. | readingIncompatibleOrCorruptPayloadNeverOverwritesItAndExplicitResetCommitsEmpty; unsupportedSchemaAndCorruptDatabaseRefuseWritesAndResetWithoutReplacement; unavailableDatabaseReadsAndFailedResetLeaveTheStoreUnavailable | JVM84 / Android40 통과, 실패·오류·skip0. 명시된 물리 확인은 대기 |
| T4 completion (승인 기준/경계/상태/실패, JVM·SQLite·Compose) | While history is unreadable or unsupported, new authorization/upload is blocked until an explicit reload succeeds or a user-confirmed local reset durably succeeds; reset failure follows the deletion-failure rules. | 읽기 불가 기록은 명시적 재읽기 또는 초기화 성공 전까지 업로드를 차단한다 | JVM84 / Android40 통과, 실패·오류·skip0. 명시된 물리 확인은 대기 |
| T4, T3 completion (승인 기준/경계/상태/실패, JVM·SQLite·Compose) | Persisting a recovered status uses the same atomic failure rules as ordinary results; if recovery persistence fails, show the derived interruption together with a local-history error and do not claim durable recovery. | 복구 저장 실패는 파생 중단과 로컬 저장 오류를 표시한다; recoverySaveFailureLeavesPersistedRunningAttemptAndDerivableInterruption; recoveryWriteFailureShowsDerivedOutcomeAndUnknownResultWhilePreservingPriorSuccessAndBlockingNewUpload | JVM84 / Android40 통과, 실패·오류·skip0. 명시된 물리 확인은 대기 |
| T5, T7 completion (승인 기준/경계/상태/실패, JVM·SQLite·Compose) | App reopen never restores tokens, upload consent or a prepared continuation, even after failed deletion or abandoned authorization. | 재열기와 지연 승인 결과는 토큰이나 명시적 계속 및 외부 실행을 복원하지 않는다; persistedSuccessSurvivesFreshActivityAndViewModelWhileConsentAndPreparedContinuationReset | JVM84 / Android40 통과, 실패·오류·skip0. 명시된 물리 확인은 대기 |

- 추적성 표 갱신: 39개 모든 기준을 실제 함수명으로 갱신. 기준 문장 원문 일치 및 함수 존재 확인. 원래 계획의 의미를 유지하며 실제 기기 증거 공백은 대기로 둔다.
- skip·비활성화된 테스트: 로컬 JVM84 및 최종 Android40 모두0.
- 신규·변경된 테스트 파일: SyncHistoryTest.kt, HistorySessionTest.kt, HistoryDatabaseInstrumentedTest.kt, SqliteHistoryStoreInstrumentedTest.kt, HealthHomeScreenInstrumentedTest.kt, HealthAppInstrumentedTest.kt.
- 커버리지 증감: 라인% 추적 없음. 실제 저장소 경계·자격 무효화·상태별 렌더링·새 Activity/ViewModel 복원 검증 추가.

## 남은 문제

- 실행하지 못한 검증: 실제 Galaxy 강제 종료·삭제 경계·시스템 테마/글자 설정·TalkBack 관찰. 현재 source 자동 계측40개 및56PNG 중7상태 대표7개 시각 검토 증거와 구분한다.
- 미커버 분기: 승인 범위 밖의 새 분기 없음. 에뮬레이터 Activity 종료·새 화면 모델은 OS 프로세스 종료를 입증하지 않는다.
- 다시 판단할 조건: 참조 전달 누락 재발은 검증 확장 필요. 향후 assembler 회귀 입력에 T1·T4 브리프의 전달 누락을 추가하여 추출과 확인 문제를 구분한다. 실제 기기·TalkBack 또는 PNG 확인에서 계약 위반 발견 시 재검토. PNG 누락 시 호스트 수집 경로를 검증하며 개인 저장소·전체 APK 출력 업로드로 확장하지 않는다.

## 다음 단계

현재 소스의 독립 spec-conformance-check는34PASS/5FAIL(증거 부족)로 기록됐다. 실제 Galaxy/TalkBack 결과는 수행한 경우에만 기록한다. 전체 적합성 PASS 전에는 최종 코드·보안 리뷰/ready-for-review/머지로 진행하지 않으며 스펙 approved·ADR Proposed·계획 in_progress·PR draft를 유지한다.

검증 보조: Gitleaks 소스 및 feature 커밋11개 검사 유출0건. OSV resolved dependency121개 조회 오류0/일치0, Gradle 파일 해시가 최종 source와 동일함을 확인. 이는 전체 보안 리뷰나 미공개 취약점 부재의 보증이 아니다. 최종 lint 오류0/기존 경고7.

독립 적합성의5개 미충족은 실제 성공 후/실행 중/삭제 중 OS 프로세스 종료·재실행, 새 수동 흐름 전 외부 효과 부재, 실제 시스템 설정/TalkBack 확인이다. 기존 새 Activity/ViewModel·SQLite·semantics 실행은 통과했으나 실제 기기 관찰을 대신하지 않는다. 검증자의 계획 격리를 유지한 채 root가39개 매핑 전부를 실제 실행 XML과 추가 대조했다.
