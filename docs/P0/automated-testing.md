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
| Raw reader query bounds, overnight/multi-day sessions, half-open heart-rate samples, terminal/cyclic tokens and limits | Ten `HealthConnectReaderTest` JVM cases exercise the actual reader with real SDK request/record objects and a start-time-selecting SDK double | Real Galaxy provider selection/history enforcement, IPC serialization, quota and permission revocation |
| Error, cancellation, partial upload | `SyncCoordinatorTest` and `GoogleDriveTransportTest` | Real Drive provider failure or physical network-off toggle |
| Repeat uploads, HTTP PATCH, 401/503, no implicit write replay | `GoogleDriveTransportTest` local TLS tests | Actual Google account policy, network, remote Drive API behavior |

## CI and privacy rules

- PR emulator job runs without signing secrets, Google OAuth accounts, health records, or Drive writes.
- Failed emulator tests must fail the job and upload their report artifact when possible.
- Emulator is not a Samsung Galaxy and cannot validate Samsung Health proprietary synchronization or battery usage.
- Real-device-only acceptance still includes permission revocation during a read, airplane mode/no-network behavior, cancellation while a remote upload is in-flight, and source record completeness. Existing live Drive content/private-access verification is recorded in `pr-readiness.md`.
- Do not copy personal measurements, auth tokens, logcat containing account details, or screenshots of health information into public GitHub Actions artifacts.

## Result log

- P0 reader correction candidate, based on merged PR #3 (`178ee3f`): 27 core + 16 app JVM tests = **43**, zero failures/errors/skips; debug APK and lint pass. The new reader suite has ten tests. Before the query/token correction, its initial eight tests failed five cases against the old reader logic with a constructor-only test seam; the corrected logic passes. [Correction and limitations](reader-boundary-fix.md). This does not add a physical-device pass or a new emulator result.

- 2026-10-31: Initial emulator run [41211720307](https://github.com/nyeonu/health-app/actions/runs/41211720307) **failed at AndroidTest Kotlin compilation**, not at runtime: invalid Compose imports/collection helper. Corrected in source commit `fc0d420`.
- 2026-11-01 KST: Re-run [41212355650](https://github.com/nyeonu/health-app/actions/runs/41212355650) **PASSED** on Android 15 (API 35) emulator: 3 instrumented tests executed, 0 skipped, 0 failed, `:app:connectedDebugAndroidTest` and Gradle completed successfully. Artifacts: `android-emulator-test-reports`.
- 2026-11-01 KST: [41212355674](https://github.com/nyeonu/health-app/actions/runs/41212355674) **PASSED** regular JVM tests, Debug APK assembly and lint on the same source. This does not imply actual Samsung Health/Google OAuth/Drive integration was exercised by CI.

