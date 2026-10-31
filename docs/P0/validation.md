# P0 verification and device handoff

Last verified: 2026-10-31. **Automated verification passed; user-reported install/launch and Health Connect permission observations are partial evidence. Real-record reads, Google OAuth, real Drive upload and ChatGPT content-access gates remain open. PR 1 remains draft.** No real health files were accessed for this handoff. Do not mark P0 complete from mocked or synthetic tests.

## Recorded automated evidence

| Check | Actual result |
| --- | --- |
| Core JVM tests | 21 tests (original 18 preserved + 3 OAuth diagnostics), 0 failures, 0 errors, 0 skipped; five XML suites under `android/core/build/test-results/test/` |
| App JVM transport tests | 6 tests, 0 failures, 0 errors, 0 skipped; one XML suite under `android/app/build/test-results/testDebugUnitTest/`; real OkHttp with synthetic local TLS server |
| Debug APK | `:app:assembleDebug` passed; `android/app/build/outputs/apk/debug/app-debug.apk`, 39,545,449 bytes (approximately 40 MB decimal) |
| Android lint | `:app:lintDebug` passed; 0 errors, 11 warnings |
| Latest combined verification | `python3 /tmp/run-health-gradle.py :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:dependencies --configuration debugRuntimeClasspath` → `BUILD SUCCESSFUL in 12s` (58 actionable tasks: 2 executed, 56 up-to-date; preceding full code verification passed in 37s) |

The verification environment used full JDK 21.0.13, Gradle 8.13 and Android SDK 36. The `/tmp` helper/logs are local session evidence, not repository prerequisites: the helper supplied process-local Java/proxy/trust configuration. The result is recorded in `/tmp/health-followup-validation.log`; durable commands are below. XML tests use synthetic values only. Core tests cover normalization and authorized-category filtering, KST boundaries, CSV quoting/missing values/stable ordering, private Drive target validation and deterministic upsert, redacted failures, partial upload and cancellation. The regression `Consecutive manual attempts choose yesterday across KST midnight` confirms that each manual attempt selects fresh yesterday at invocation, including successive attempts across KST midnight. They do not exercise the Android framework, real Health Connect pagination, OAuth or Google Drive.

Lint report: `android/app/build/reports/lint-results-debug.txt` (HTML sibling available). Nine warnings announce newer dependency versions (including test-only libraries); `DataExtractionRules` requests Android 12+ transfer rules (`allowBackup=false` is already set; P0 does not persist health data/tokens); `MissingApplicationIcon` requests an explicit launcher icon. These remain open production follow-ups, not suppressed findings.

### Dependency audit follow-up

On 2026-10-31, the follow-up OSV query checked 117 resolved runtime Maven coordinates from the runtime dependency graph. Transitive `com.google.guava:guava:31.1-android` matched:

| Advisory | OSV database severity | Affected API/path |
| --- | --- | --- |
| [GHSA-5mg8-w23w-74h3](https://osv.dev/vulnerability/GHSA-5mg8-w23w-74h3) | LOW | temporary-directory creation |
| [GHSA-7g45-4rm6-3mm3](https://osv.dev/vulnerability/GHSA-7g45-4rm6-3mm3) | MODERATE | `FileBackedOutputStream` |
| [GHSA-xxph-c9ww-hj94](https://osv.dev/vulnerability/GHSA-xxph-c9ww-hj94) | HIGH | native Java collection deserialization |

The updated graph resolves OkHttp/okhttp-android 5.3.0 and Okio/okio-jvm 3.16.2; no OSV matches for the new OkHttp/Okio coordinates were returned. Only the same three Guava matches above were returned. This is a database-query snapshot, not a zero-vulnerability claim. No direct app calls to these affected Guava APIs were found; the app uses HTTP JSON and Health Connect binder/proto paths. This is not proof of transitive unreachability or a zero-vulnerability claim. Independently review reachability and compatible dependency remediation **before production**, and recheck by **2026-11-30**. The transport task added OkHttp; this documentation task does not change dependency versions. Current audit evidence: `/tmp/health-followup-validation.log`, `/tmp/health-followup-osv-input.json`, `/tmp/health-followup-osv-results.json`; regenerate on the release graph rather than assuming these temporary files persist.

## Build and install

Use a full JDK 17+ (21 verified), Android SDK Platform 36, SDK command-line/platform tools, network access to Google/Maven repositories and accepted SDK licenses. A JRE-only runtime is insufficient. Set `JAVA_HOME` to the full JDK and `ANDROID_HOME` to the SDK root, or configure a local ignored `android/local.properties`. Preserve corporate proxy/CA settings where required; do not disable TLS verification or commit local settings.

### PR 1 follow-up evidence and limits

The 27 executable tests comprise 21 core unit tests and 6 local TLS transport integration tests. The latter verify create then PATCH of the same synthetic file without duplicate create, revoked authorization and explicit retry, redirects/endpoint allowlisting, bounded/redacted errors and cancellation of an in-flight HTTP call. The added server-retry regression verifies that each POST/PATCH receiving HTTP 503 with `Retry-After: 0` is sent exactly once and returns status 503 with a redacted empty body; only a later explicit call sends another write. Write request bodies are one-shot so server-directed replay cannot silently repeat them. They use real OkHttp and a local test certificate; production retains system TLS trust. They do not verify Google OAuth, real Drive IDs/privacy, Android runtime behavior or provider integration. The 3 added core tests verify fixed OAuth diagnostic categories and token/scope/error redaction; actual SDK dialogs and callbacks still need device checks.

A separate full-JDK reproduction of the old `HttpsURLConnection` path produced `ProtocolException: Invalid HTTP method: PATCH`. This identifies a limitation of that JDK implementation, not proof about every Android device implementation. P0 now uses OkHttp 5.3.0 for PATCH. Earlier P0 reports are historical snapshots; this follow-up supersedes their platform-transport and 18-test-only verification claims.

User [comment 6537911289](https://github.com/nyeonu/health-app/pull/1#issuecomment-6537911289) reports APK install and launch, Health Connect available, 5/5 grants shown and private-folder opt-in. This is user-reported partial device evidence, not independently reproduced or proof of actual records. Google authorization failed with a generic message; zero files uploaded and no successful sync were reported. The root cause and current Google Cloud configuration remain unknown. Denial, partial/zero grants, revocation and all real-record/provider checks below remain pending.

No emulator binary, installed system image or `/dev/kvm` was found in this environment. No live Android UI run was performed; this does not establish that software emulation is impossible. Physical Galaxy validation remains necessary. No Google Cloud account access or health-record access was used.

The CI follow-up adds app JVM tests and their XML artifacts while retaining the existing SDK setup and APK artifacts from user commits `df57843`/`d008d6f`. A configured workflow is not evidence that a future remote run passed.

From `android/`:

```bash
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
./gradlew :app:signingReport
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.nyeonu.healthapp/.MainActivity
```

For JVM-only verification without an SDK: `./gradlew -PcoreOnly :core:test` (full JDK still required). In the managed verification environment only, `GRADLE_USER_HOME=/workspace/tooling/gradle-home` and `ANDROID_HOME=/workspace/tooling/android-sdk` were used. Do not publish device serials, signing material or account details from local command output.

## Stack and official setup references

The implemented stack is Kotlin 2.2.20, AGP 8.13.2 / Gradle 8.13, Compose BOM 2025.09.01, Health Connect 1.1.0, Google Play services auth 21.4.0 and coroutines 1.10.2. Dependencies are pinned for this verified P0 build; lint reports several newer versions, so this is not a latest-version claim. Drive REST uses OkHttp 5.3.0 behind testable interfaces; there is no backend or Google service credential.

- [Android Gradle plugin 8.13 compatibility](https://developer.android.com/build/releases/past-releases/agp-8-13-0-release-notes), [Kotlin releases](https://kotlinlang.org/docs/releases.html), [Compose BOM](https://developer.android.com/develop/ui/compose/bom).
- [Health Connect setup](https://developer.android.com/health-and-fitness/guides/health-connect/develop/get-started), [read data](https://developer.android.com/health-and-fitness/guides/health-connect/develop/read-data), [aggregate data](https://developer.android.com/health-and-fitness/guides/health-connect/develop/aggregate-data), [data types](https://developer.android.com/health-and-fitness/guides/health-connect/data-and-data-types/data-types).
- [Google Android authorization](https://developer.android.com/identity/authorization), [Android OAuth client setup](https://developer.android.com/identity/authorization), [Drive API scopes](https://developers.google.com/workspace/drive/api/guides/api-specific-auth), [upload files](https://developers.google.com/workspace/drive/api/guides/manage-uploads).
- [Samsung Health and Health Connect](https://developer.samsung.com/health/blog/en/2022/10/12/accessing-samsung-health-data-through-health-connect).

### Google project configuration (beginner handoff; pending user-controlled setup)

These steps are checks to perform privately in your own browser, not a diagnosis that missing Cloud setup caused the reported error. Follow the official [Android authorization setup](https://developer.android.com/identity/authorization). Console labels can change.

1. Open [Google Cloud Console](https://console.cloud.google.com/). Sign in with the Google account you intend to use. Open the project selector at the top, choose **New project**, enter a project name and create it (or select an existing intended project). Keep that same project selected throughout.
2. Open **APIs & Services → Library**, search **Google Drive API**, open it and choose **Enable**. If it already says **Manage**, confirm it is enabled in the selected project.
3. Open **Google Auth Platform → Branding** (or the OAuth consent screen setup). Complete the required app name, support email and developer contact fields privately. These contact/account values do not belong in this repository or a public issue.
4. In **Audience**, choose **External** for a personal Google account and keep the app in **Testing**. Under **Test users**, add your own intended Google account privately and save. Use this same account on the phone. Do not send its address here.
5. In **Data Access**, add only `https://www.googleapis.com/auth/drive.file` for this flow and save. This is separate from Health Connect's on-device permissions.
6. Determine the SHA-1 of the **actual APK you will install**, especially if it came from GitHub Actions. With Android SDK build-tools installed, run locally (replace the SDK path/version as needed):

   ```bash
   "$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --print-certs app-debug.apk
   ```

   Read the **Signer #1 certificate SHA-1 digest** privately. For your own local build, `./gradlew :app:signingReport` from `android/` also lists the local variant certificate; it proves a match only if that key signed the installed APK. Do not substitute a SHA-256 digest.
7. Open **Google Auth Platform → Clients → Create client → Android**. Enter package **`dev.nyeonu.healthapp`** and the actual APK signing certificate **SHA-1**, then create/save. Confirm the package/certificate pair matches that artifact. This authorization flow needs no web client secret embedded in the APK, downloaded credential JSON or hardcoded client secret.
8. Install that matching APK on the phone with compatible Google Play services. Open the app, verify Health Connect grants and private-folder opt-in, then choose **Sync now** and authorize with the same test-user account. **Continue upload** is a separate explicit action; use the synthetic checklist below before any real-record validation. Cancellation must report no success. Saving Cloud settings and compiling the APK do not prove authorization or upload.

GitHub Actions may generate a fresh debug keystore on each runner/build, so its certificate may differ from your local build and later artifacts. Register the actual artifact's matching SHA-1 when needed. Updates installed over an existing app require the same signing key; a differently signed APK may require a separately considered reinstall and reset of local app state. Stable signing would need separately managed signing outside this PR. Never commit/upload signing keys, hardcode a fingerprint, automatically overwrite Cloud settings or share credentials. No account details, token, client secret or signing material needs to be sent to the assistant.

### Safe OAuth feedback after retry

With the updated APK, share only its fixed diagnostic category and, if present, numeric Google API status: for example `OAUTH_CONSENT_CANCELED`, `OAUTH_TASK_CANCELED`, `OAUTH_GOOGLE_API_STATUS_10`, `OAUTH_MISSING_TOKEN`, `OAUTH_INVALID_TOKEN` or `OAUTH_MISSING_DRIVE_SCOPE`. Missing intent/result/resolution, unexpected result, resolution-launch failure, foreground departure and unknown failure also have fixed categories. These correspond to the actual Activity/ViewModel handlers and core formatter; no raw SDK exception text is included. Status 10 suggests checking package/certificate/test-user configuration; it does not establish a root cause. Do not send logs/screenshots containing tokens, account addresses, granted-scope lists, credential files, health records or raw exception messages.

## Foreground device workflow (partial user report; remaining checks pending)

On Galaxy S23 Ultra / Android 16, inspect Health Connect settings and provider availability. In Samsung Health's Health Connect/data-permissions settings, enable the relevant **write** permissions; grant this app **read** access separately. Sync watch to phone and Samsung Health to Health Connect before checking. Record source package and original event time privately: delayed writes and unsupported categories must not be interpreted as zeros. UI/settings labels can vary with installed versions.

The app exports **yesterday, Asia/Seoul**, not an arbitrary date or today's live records. The flow is: request Health Connect permissions → opt in to private HealthApp folder creation/use → **Sync now** for Google authorization → **Continue upload** to read/upload in the foreground. OAuth returning does not itself start health reads. No scheduled or continuous sync exists.

Use synthetic records from a separate test provider first. Keep all real measurements, exported CSVs, tokens, IDs and screenshots with personal details outside this public repository and logs. For each scenario below record privately: date/window, category availability/grants, expected outcome, actual redacted outcome and pass/fail. Do not paste raw records into issues.

| Pending scenario | Required observation |
| --- | --- |
| Five type availability | Independently inspect steps, weight, heart rate, sleep and exercise. Record supported/populated, empty or unavailable per type with source/timing; do not imply Samsung publishes all five. |
| Partial, zero and revoked read grants | UI count reflects grants; only authorized types read/export. Zero grants reports permission required; revocation before/during read fails safely, without success or unauthorized data. |
| Samsung timing and provenance | Compare authorized records to the source and exact timestamps after source sync; missing/late data remains blank/empty. |
| KST boundaries | Synthetic instants just before/at midnight and next midnight respect start-inclusive/end-exclusive membership; HR samples outside the day excluded. |
| Pagination and limits | More than 1,000 synthetic records require another page and preserve every record. Test terminal token and bounded overflow (100 pages / 100,000 HR samples) fails without silently truncated output. |
| Multiple providers | Compare steps Aggregate API result and configured source priorities; do not sum raw duplicates. Verify selected latest in-day weight and stable tie behavior. |
| Overlapping sessions | Preserve full original session bounds/duration; adjacent day files can contain the same session. Do not present summed overlapping sessions as daily totals. |
| Empty/missing values | No fabricated zeros; unsupported daily HR/sleep/exercise summaries stay blank. No populated records yields EMPTY, no upload success. |
| Offline and failures | Disconnect before read/upload and between files. Clear failure/confirmed count; no false last-success update. Already uploaded files can remain. |
| Cancel/background/interruption | Cancel either permission dialog; leave app during active sync. Work cancels on pause, no false completion; inspect Drive for partial/in-flight writes before retry. |
| Process restart/local forget | Token and last-success reset with process loss. Local forget does not revoke permissions or delete Drive files; revoke in system/Google account settings separately. |
| Account switch/revocation | Forget local state, revoke app access in Google account settings if required, then authorize the intended other account. Verify destination ownership and that the prior account's files remain untouched. No dedicated in-app account picker/sign-out guarantee. |

## Private Drive and repeat-sync checks (all pending)

1. Inspect the app-created `HealthApp` root and populated `daily`, `workouts`, `heart-rate`, `sleep` date files in the authorized account. `drive.file` limits access to app-authorized files; it does not grant browsing of arbitrary existing private folders. P0 accepts only its marked app-created targets, owned by the user, unshared and outside shared drives.
2. Verify **Manage access** for root, subfolders and files: restricted/private, expected owner, no inherited/public/link sharing. The uploader does not add sharing permissions. Test a shared/non-owned target and ambiguous marked targets; the app must fail before claiming a private successful upload.
3. Record file IDs privately. Repeat the same date sync serially: the same IDs must update, with no duplicate date files. `generated_at` may change. Add a late synthetic record and verify replacement contents.
4. Force failure after one file and retry manually: exports are **not a transaction**, so earlier files remain and last success advances only after all intended files are confirmed. An interrupted create response may be indeterminate; no automatic POST retry is implemented. Cross-instance creation and changes to sharing racing privacy checks are known limitations requiring release review.
5. Verify max 5,000,000-byte CSV rejection and safe errors without raw data/tokens. Keep test exports private and remove them manually if desired; local forget does not remove remote files.

## ChatGPT actual-content gate (separate, pending)

The user's account and available ChatGPT Google Drive connection must authorize this separately. Seeing a filename, search hit or metadata is insufficient. With user permission, use a **synthetic** private exported CSV with a known row/value; ask ChatGPT through the available connector to read its columns and reproduce that value. Compare against the actual file contents, and verify an updated version is read after repeat upload. Record account/client capability and a redacted result, without public account identifiers. No connector or real health content was accessed during implementation.

If CSV contents cannot be read, record the concrete account/client/file-format blocker. As a **manual fallback evaluation only**, import a synthetic CSV into a private Google Sheet and test actual cell-content access through the user's available integration. Sheets is not implemented here and is not an added service/backend; do not call this proof that direct CSV access works. Keep the original ChatGPT/Drive gate open until the agreed path is demonstrated.

## Documentation validation

**Validation evidence separates local TLS tests user reports and pending account provider checks** — criteria-based documentation check: compare this file, README and implementation-plan gates against the actual XML (21 core + 6 app tests, all zero failures/errors/skips), successful combined build and lint report (0 errors/11 warnings). Verify that the user report is limited to install/launch/HC availability/5-of-5 grants/opt-in, with generic OAuth failure, zero upload and unknown root cause. Confirm actual-artifact SHA-1/Cloud/test-user steps, safe diagnostics, emulator limits and remaining real-record/Drive/ChatGPT checks. Preserve the [V1 SAF design and seven pending Galaxy scenarios](../V1/storage-destinations.md): no SAF implementation or P0 merge gate. Keep PR 1 draft. Run `git diff --check`. This static review is separate from executable tests and does not increase their count.

Next action: privately check the actual APK certificate and Google Cloud setup above, retry authorization and report only the fixed category/numeric code if it fails, then run the foreground synthetic device/private-Drive checklist before the separately authorized real-record/content checks. V1 remains gated on P0 evidence.
