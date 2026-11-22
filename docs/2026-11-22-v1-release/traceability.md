# 스펙 기준과 실제 검증 연결

오케스트레이터가 승인 스펙의11개 기준과 실제28개 named test/단언을 대조했다. source6efa2d2의 현재-head 전체 실행·격리 스펙 PASS 및 PR20 독립 리뷰 APPROVE를 [실행 증거](execution-report.md), [적합성 보고서](spec-conformance.md), [PR 리뷰](pr-review.md)에 고정한다. 선택된 테스트 이름의 누락·skip·오래된 이름·식별자 접두어는 없다. Gate 기준은 CI 원본·검토·APK 메타데이터로 확인하며 단위 테스트로 대체하지 않는다.

## 1. 수동 Drive와 로컬 SAF의 기존 동의·권한·목적지·복구 동작을 유지하고 background 작업은 SAF를 사용하지 않는다.

- `ManualDriveFlowInstrumentedTest.automaticEnabledManualFlowRequiresForegroundConsentAndFreshProofWithoutCreatingRetryLineage` — [ManualDriveFlowInstrumentedTest.kt](../../android/app/src/androidTest/java/com/ny/my/health/ManualDriveFlowInstrumentedTest.kt)
- `ManualDriveFlowInstrumentedTest.sameAccountCategoryAdditionAndRemovalPreserveConfirmedExportAndRequireFreshForegroundProof` — [ManualDriveFlowInstrumentedTest.kt](../../android/app/src/androidTest/java/com/ny/my/health/ManualDriveFlowInstrumentedTest.kt)
- `SafManualFlowInstrumentedTest.safActualActivitySelectionConfiguresWithoutExportingAndExplicitContinueConfirmsOnlySelectedLocalTarget` — [SafManualFlowInstrumentedTest.kt](../../android/app/src/androidTest/java/com/ny/my/health/SafManualFlowInstrumentedTest.kt)
- `SyncWorkSchedulerInstrumentedTest.disabledAndStaleWorkersDoNotAccessHealthOrPrepareDestination` — [SyncWorkSchedulerInstrumentedTest.kt](../../android/app/src/androidTest/java/com/ny/my/health/SyncWorkSchedulerInstrumentedTest.kt)

## 2. 자동 Drive는 명시적 opt-in 전까지 꺼져 있고 background 기능 지원과 background 및 각 category 권한을 요구한다.

- `AutomaticSettingsTest.Explicit opt in schedules and enqueues after verified commit` — [AutomaticSettingsTest.kt](../../android/app/src/test/java/com/ny/my/health/AutomaticSettingsTest.kt)
- `AutomaticHealthReaderTest.Feature and each grant missing block their scopes while HR remains blocked` — [AutomaticHealthReaderTest.kt](../../android/app/src/test/java/com/ny/my/health/AutomaticHealthReaderTest.kt)
- `BackgroundPermissionFlowInstrumentedTest.unavailableOrUnknownFeatureNeverLaunchesAndExplicitResultClearsBusy` — [BackgroundPermissionFlowInstrumentedTest.kt](../../android/app/src/androidTest/java/com/ny/my/health/BackgroundPermissionFlowInstrumentedTest.kt)

## 3. 지원 요약은 Health Connect Aggregate를 사용하고 체중은 최신 실제 기록이며 결측은 결측으로 둔다. 미입증 운동 심박은 BLOCKED로 표시하고 다른 허용 category는 독립 진행한다.

- `AutomaticHealthReaderTest.Null aggregates retain missing values and per metric origins while other categories proceed` — [AutomaticHealthReaderTest.kt](../../android/app/src/test/java/com/ny/my/health/AutomaticHealthReaderTest.kt)
- `AutomaticHealthReaderTest.Bounded weight read returns the latest actual measurement` — [AutomaticHealthReaderTest.kt](../../android/app/src/test/java/com/ny/my/health/AutomaticHealthReaderTest.kt)
- `SyncExecutionSessionInstrumentedTest.automaticUsesPositiveGrantedScopesAndBlocksWhenNoScopesAreAvailable` — [SyncExecutionSessionInstrumentedTest.kt](../../android/app/src/androidTest/java/com/ny/my/health/SyncExecutionSessionInstrumentedTest.kt)
- `CsvExporterTest.Summary only daily exports blank missing values and observed zero` — [CsvExporterTest.kt](../../android/core/src/test/kotlin/com/ny/my/health/core/CsvExporterTest.kt)

## 4. 자동 작업은 connected 및 battery-not-low 제약의 24시간 unique WorkManager 일정과 최근 2일 refresh를 포함한 최대 7일 catch-up을 사용한다.

- `SyncWorkSchedulerInstrumentedTest.uniqueDailyWorkUsesNetworkAndBatteryConstraintsAndCatchUpIsUnique` — [SyncWorkSchedulerInstrumentedTest.kt](../../android/app/src/androidTest/java/com/ny/my/health/SyncWorkSchedulerInstrumentedTest.kt)
- `SyncPlannerTest.Recent two days reserve slots before oldest eligible gaps and retain blocked pending days` — [SyncPlannerTest.kt](../../android/core/src/test/kotlin/com/ny/my/health/core/SyncPlannerTest.kt)

## 5. 영속 retry lineage는 최초 실행 외 최대 3회 재시도하며 새 작업이나 원본 revision은 미완료 budget을 초기화하지 않는다.

- `SyncRetryBudgetStoreInstrumentedTest.sqliteRetryClaimsSurviveReopenAndRacingAttemptsCannotExceedThreeRetriesOrEraseOldMetadata` — [SyncRetryBudgetStoreInstrumentedTest.kt](../../android/app/src/androidTest/java/com/ny/my/health/SyncRetryBudgetStoreInstrumentedTest.kt)
- `SyncExecutionSessionInstrumentedTest.automaticRetryKeepsLineageAndConsumesBudgetWhenSourceRevisionChanges` — [SyncExecutionSessionInstrumentedTest.kt](../../android/app/src/androidTest/java/com/ny/my/health/SyncExecutionSessionInstrumentedTest.kt)
- `SyncRetryBudgetStoreInstrumentedTest.removedCategoryCannotClaimCarriedRetryAndReselectionRetainsBudgetAndDue` — [SyncRetryBudgetStoreInstrumentedTest.kt](../../android/app/src/androidTest/java/com/ny/my/health/SyncRetryBudgetStoreInstrumentedTest.kt)

## 6. 수동 실행과 worker는 동일 canonical gate를 사용하며 worker는 health record 조회 전에 선택 계정의 새 silent 인증과 proof를 수행하고 UI를 띄우지 않는다.

- `SyncExecutionSessionInstrumentedTest.automaticAndManualSessionsShareOneGateAndOnlyOwnerPrepares` — [SyncExecutionSessionInstrumentedTest.kt](../../android/app/src/androidTest/java/com/ny/my/health/SyncExecutionSessionInstrumentedTest.kt)
- `BackgroundDriveAuthorizationTest.Background authorization pins the selected account and proves the same fresh token before any health read without launching resolution` — [BackgroundDriveAuthorizationTest.kt](../../android/app/src/test/java/com/ny/my/health/BackgroundDriveAuthorizationTest.kt)
- `BackgroundDriveAuthorizationTest.Suspending durable checks block stale callbacks and stale proof before reads and propagate cancellation` — [BackgroundDriveAuthorizationTest.kt](../../android/app/src/test/java/com/ny/my/health/BackgroundDriveAuthorizationTest.kt)

## 7. 영속 metadata는 재시작 후에도 확정 결과와 불확실한 create 및 취소 상태를 보존하며 blocked·stale·손상·미확인 상태를 완료로 표시하거나 자동 초기화하지 않는다.

- `SyncRetryBudgetStoreInstrumentedTest.failedSourceReadPreservesOldRevisionAndPriorWithoutRecordingANewObservation` — [SyncRetryBudgetStoreInstrumentedTest.kt](../../android/app/src/androidTest/java/com/ny/my/health/SyncRetryBudgetStoreInstrumentedTest.kt)
- `SyncRetryBudgetStoreInstrumentedTest.driveOptOutCarriesUnknownAndStaleJournalsConfirmedMetadataAndRejectsStaleCallbacks` — [SyncRetryBudgetStoreInstrumentedTest.kt](../../android/app/src/androidTest/java/com/ny/my/health/SyncRetryBudgetStoreInstrumentedTest.kt)
- `SyncExecutionSessionInstrumentedTest.automaticCancellationPreservesUncertainCreateAndConsumedBudgetAcrossReopen` — [SyncExecutionSessionInstrumentedTest.kt](../../android/app/src/androidTest/java/com/ny/my/health/SyncExecutionSessionInstrumentedTest.kt)
- `SyncRetryBudgetStoreInstrumentedTest.authorizationAndExhaustionPreserveRemoteStaleAndPriorConfirmationThroughReopen` — [SyncRetryBudgetStoreInstrumentedTest.kt](../../android/app/src/androidTest/java/com/ny/my/health/SyncRetryBudgetStoreInstrumentedTest.kt)

## 8. 설정은 명시적 opt-in·끄기·reset과 함께 queued·blocked·coverage gap을 표시하고 끄기나 목적지 변경 시 이전 작업을 안전하게 무효화한다.

- `AutomaticSettingsTest.Opt out commits generation invalidation before cancelling old work` — [AutomaticSettingsTest.kt](../../android/app/src/test/java/com/ny/my/health/AutomaticSettingsTest.kt)
- `SyncRetryBudgetStoreInstrumentedTest.sameDriveCategoryChangesPreserveHistoricalTargetsUnknownsPriorAndAccountIsolation` — [SyncRetryBudgetStoreInstrumentedTest.kt](../../android/app/src/androidTest/java/com/ny/my/health/SyncRetryBudgetStoreInstrumentedTest.kt)
- `AutomaticSettingsTest.Work observation failures and unavailable store produce unknown state` — [AutomaticSettingsTest.kt](../../android/app/src/test/java/com/ny/my/health/AutomaticSettingsTest.kt)
- `AutomaticSettingsInstrumentedTest.cancellationBeforeCommitLeavesAutomaticOffAfterSqliteReopenAndDoesNotEnqueue` — [AutomaticSettingsInstrumentedTest.kt](../../android/app/src/androidTest/java/com/ny/my/health/AutomaticSettingsInstrumentedTest.kt)
- `HealthHomeScreenInstrumentedTest.automaticControlsRequireExplicitConsentAndRestoreDoesNotStartActions` — [HealthHomeScreenInstrumentedTest.kt](../../android/app/src/androidTest/java/com/ny/my/health/HealthHomeScreenInstrumentedTest.kt)

## 9. 각 통합 PR 병합과 최종 1.0 전에 현재 head의 API 35·36 전체 Native와 기존 격리 host, JVM, lint 및 APK 빌드가 통과하며 테스트 삭제·skip·완화는 없다.

- [실행 원본·독립 검토·병합 기록](execution-report.md)과 [실제 APK 메타데이터·설치 안내](../V1/1.0-installation.md)로 확인하는 gate다. 단위 테스트로 대체하지 않는다.

## 10. 독립 적합성 검증과 새 세션 PR 리뷰 후 병합하며 수행하지 않은 실기기·계정·P0·접근성 수용은 pending으로 남긴다.

- [실행 원본·독립 검토·병합 기록](execution-report.md)과 [실제 APK 메타데이터·설치 안내](../V1/1.0-installation.md)로 확인하는 gate다. 단위 테스트로 대체하지 않는다.

## 11. APK 후보는 versionName 1.0.0 및 1보다 큰 versionCode를 사용하고 간결한 설치 안내와 미완료 수용 항목을 제공한다.

- [실행 원본·독립 검토·병합 기록](execution-report.md)과 [실제 APK 메타데이터·설치 안내](../V1/1.0-installation.md)로 확인하는 gate다. 단위 테스트로 대체하지 않는다.
