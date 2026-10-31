# P0 verification and device handoff

Last verified: 2026-10-31. **Automated verification passed; all physical-device, OAuth, real Drive upload and ChatGPT content-access validation is pending.** No real health files were accessed for this handoff. Do not mark P0 complete from mocked or synthetic tests.

## Recorded automated evidence

| Check | Actual result |
| --- | --- |
| Core JVM tests | 18 tests, 0 failures, 0 errors, 0 skipped; four XML suites under `android/core/build/test-results/test/` |
| Debug APK | `:app:assembleDebug` passed; `android/app/build/outputs/apk/debug/app-debug.apk`, approximately 32 MB |
| Android lint | `:app:lintDebug` passed; 0 errors, 8 warnings |
| Latest combined verification | `python3 /tmp/run-health-gradle.py :core:test :app:assembleDebug :app:lintDebug` → `BUILD SUCCESSFUL in 11s` (incremental; preceding full run 43s) |

The verification environment used full JDK 21.0.13, Gradle 8.13 and Android SDK 36. The `/tmp` helper/logs are local session evidence, not repository prerequisites: the helper supplied process-local Java/proxy/trust configuration. The result is recorded in `/tmp/health-final-verification.log`; durable commands are below. XML tests use synthetic values only. Core tests cover normalization and authorized-category filtering, KST boundaries, CSV quoting/missing values/stable ordering, private Drive target validation and deterministic upsert, redacted failures, partial upload and cancellation. The regression `Consecutive manual attempts choose yesterday across KST midnight` confirms that each manual attempt selects fresh yesterday at invocation, including successive attempts across KST midnight. They do not exercise the Android framework, real Health Connect pagination, OAuth or Google Drive.

Lint report: `android/app/build/reports/lint-results-debug.txt` (HTML sibling available). Six warnings announce newer dependency versions; `DataExtractionRules` requests Android 12+ transfer rules (`allowBackup=false` is already set; P0 does not persist health data/tokens); `MissingApplicationIcon` requests an explicit launcher icon. These remain open production follow-ups, not suppressed findings.

### Dependency audit follow-up

On 2026-10-31, OSV was queried for 113 resolved Maven coordinates from the runtime dependency graph. Transitive `com.google.guava:guava:31.1-android` matched:

| Advisory | OSV database severity | Affected API/path |
| --- | --- | --- |
| [GHSA-5mg8-w23w-74h3](https://osv.dev/vulnerability/GHSA-5mg8-w23w-74h3) | LOW | temporary-directory creation |
| [GHSA-7g45-4rm6-3mm3](https://osv.dev/vulnerability/GHSA-7g45-4rm6-3mm3) | MODERATE | `FileBackedOutputStream` |
| [GHSA-xxph-c9ww-hj94](https://osv.dev/vulnerability/GHSA-xxph-c9ww-hj94) | HIGH | native Java collection deserialization |

No direct app calls to these APIs were found; the app uses HTTP JSON and Health Connect binder/proto paths. This is not proof of transitive unreachability or a zero-vulnerability claim. Independently review reachability and compatible dependency remediation **before production**, and recheck by **2026-11-30**. No dependency versions were changed for this documentation task. Local audit evidence: `/tmp/health-runtime-dependencies.txt`, `/tmp/health-osv-input.json`, `/tmp/health-osv-results.json`, `/tmp/health-osv-vulns.json`; regenerate on the release graph rather than assuming these temporary files persist.

## Build and install

Use a full JDK 17+ (21 verified), Android SDK Platform 36, SDK command-line/platform tools, network access to Google/Maven repositories and accepted SDK licenses. A JRE-only runtime is insufficient. Set `JAVA_HOME` to the full JDK and `ANDROID_HOME` to the SDK root, or configure a local ignored `android/local.properties`. Preserve corporate proxy/CA settings where required; do not disable TLS verification or commit local settings.

From `android/`:

```bash
./gradlew :core:test :app:assembleDebug :app:lintDebug
./gradlew :app:signingReport
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.nyeonu.healthapp/.MainActivity
```

For JVM-only verification without an SDK: `./gradlew -PcoreOnly :core:test` (full JDK still required). In the managed verification environment only, `GRADLE_USER_HOME=/workspace/tooling/gradle-home` and `ANDROID_HOME=/workspace/tooling/android-sdk` were used. Do not publish device serials, signing material or account details from local command output.

## Stack and official setup references

The implemented stack is Kotlin 2.2.20, AGP 8.13.2 / Gradle 8.13, Compose BOM 2025.09.01, Health Connect 1.1.0, Google Play services auth 21.4.0 and coroutines 1.10.2. Dependencies are pinned for this verified P0 build; lint reports several newer versions, so this is not a latest-version claim. Drive REST uses the platform HTTPS transport behind testable interfaces; there is no backend or Google service credential.

- [Android Gradle plugin 8.13 compatibility](https://developer.android.com/build/releases/past-releases/agp-8-13-0-release-notes), [Kotlin releases](https://kotlinlang.org/docs/releases.html), [Compose BOM](https://developer.android.com/develop/ui/compose/bom).
- [Health Connect setup](https://developer.android.com/health-and-fitness/guides/health-connect/develop/get-started), [read data](https://developer.android.com/health-and-fitness/guides/health-connect/develop/read-data), [aggregate data](https://developer.android.com/health-and-fitness/guides/health-connect/develop/aggregate-data), [data types](https://developer.android.com/health-and-fitness/guides/health-connect/data-and-data-types/data-types).
- [Google Android authorization](https://developers.google.com/identity/authorization/android), [Android OAuth client setup](https://developer.android.com/identity/authorization), [Drive API scopes](https://developers.google.com/workspace/drive/api/guides/api-specific-auth), [upload files](https://developers.google.com/workspace/drive/api/guides/manage-uploads).
- [Samsung Health and Health Connect](https://developer.samsung.com/health/blog/en/2022/10/12/accessing-samsung-health-data-through-health-connect).

### Google project configuration (pending user-controlled setup)

1. Create/select a Google Cloud project and enable **Google Drive API**.
2. Configure Google Auth Platform OAuth branding/audience/consent. For an external app in testing, add the intended Google account as a test user; declare only `https://www.googleapis.com/auth/drive.file` for this P0 flow.
3. Create an **Android** OAuth client with package **`dev.nyeonu.healthapp`** and the debug signing certificate **SHA-1** shown by `:app:signingReport`. A different machine/key requires its own matching client registration. Never commit a keystore, credential JSON or token. No web client secret is embedded or required by this authorization flow.
4. Use a physical phone with compatible Google Play services and the registered debug APK. Cancel consent once to verify the app reports no success; then authorize the intended account. Cloud configuration and consent are not proven by compilation.

## Foreground device workflow (all pending)

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

**Documentation distinguishes automated evidence from pending physical account validation** — inspect this file, README, data contract and P0 checkboxes using synthetic test/report evidence only. Confirm the 18-test/build/lint evidence is explicit; physical-device/cloud/content results remain pending; no real fixtures or success claims are introduced. Run `git diff --check`. This documentation review is distinct from executable JVM tests and does not increase their count.

Next action: configure the user's Android OAuth client and test-user consent, install the debug APK, then run the foreground synthetic device/private-Drive checklist before the separately authorized real-record/content checks. V1 remains gated on P0 evidence.
