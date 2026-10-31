# P0 automated test matrix

This file separates repeatable automated evidence from tests requiring a real Galaxy device or user-authorized Google account.

## Executable suites

- **Core JVM**: `cd android && ./gradlew :core:test` — fake authorized records and Drive port; no real health data.
- **HTTP transport JVM**: `cd android && ./gradlew :app:testDebugUnitTest` — OkHttp local TLS server, PATCH/idempotency, 401/503, redirects, cancellation, redacted errors.
- **Android emulator instrumentation**: `cd android && ./gradlew :app:connectedDebugAndroidTest` — runs on Android 15 Google APIs emulator, tests actual Compose Activity and lifecycle with no personal account. CI workflow: `.github/workflows/android-emulator-tests.yml`; artifact: `android-emulator-test-reports`.

## Scenario-to-evidence mapping

| Behavior | Automated proof | What it DOES NOT prove |
| --- | --- | --- |
| App startup and explicitly disabled Drive consent | `HealthAppInstrumentedTest.firstLaunchNeverStartsGoogleAuthorizationWithoutExplicitOptIn` | Real Google OAuth authorization |
| No Health Connect grants and user opts into Drive | `HealthAppInstrumentedTest.grantingDriveOptInWithoutHealthPermissionsDoesNotStartUpload` | Provider-specific consent UI or post-grant reads |
| Activity background → resume | `HealthAppInstrumentedTest.backgroundAndReturnDoNotFabricateSuccessfulSynchronization` | In-flight network/upload cancellation in an Activity with real OAuth |
| Zero/partial grants | `SyncCoordinatorTest` fake HealthReader permission-partition tests | Revoking individual Health Connect permissions mid-read on Samsung device |
| Error, cancellation, partial upload | `SyncCoordinatorTest` and `GoogleDriveTransportTest` | Real Drive provider failure or physical network-off toggle |
| Repeat uploads, HTTP PATCH, 401/503, no implicit write replay | `GoogleDriveTransportTest` local TLS tests | Actual Google account policy, network, remote Drive API behavior |

## CI and privacy rules

- PR emulator job runs without signing secrets, Google OAuth accounts, health records, or Drive writes.
- Failed emulator tests must fail the job and upload their report artifact when possible.
- Emulator is not a Samsung Galaxy and cannot validate Samsung Health proprietary synchronization or battery usage.
- Real-device-only acceptance still includes permission revocation during a read, airplane mode/no-network behavior, cancellation while a remote upload is in-flight, and source record completeness. Existing live Drive content/private-access verification is recorded in `pr-readiness.md`.
- Do not copy personal measurements, auth tokens, logcat containing account details, or screenshots of health information into public GitHub Actions artifacts.

## Result log

- 2026-10-31: Instrumentation test source and CI job added. **Execution result pending** until the new workflow finishes. Distinguish compile failure, emulator setup failure and test assertion failure.
