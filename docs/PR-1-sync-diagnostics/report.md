# Sync failure diagnostic correction

## Report and bounded scope

The user supplied a device screenshot showing Health Connect available, 5/5 grants, completed read coverage for all five types, a generic sync failure and zero confirmed uploads. The read wrapper updates coverage only after `HealthReader.read` returns. This supports a completed authorized read; it does not prove populated records, normalization/export completion, a particular Drive error or successful upload. No account identifiers, health values or screenshots are stored here.

This is a small failure-reporting bug fix under the existing P0 manual-sync requirements and `testing-policy`, not a new feature plan. The defect confirmed by source inspection is that `SyncViewModel` discarded the coordinator's safe failure phase and displayed one generic message. Actual device failure remains undiagnosed until a new fixed diagnostic is observed.

## Changes and acceptance evidence

- Preserve the coordinator phase (`READ_FAILED`, `NORMALIZATION_FAILED`, `EXPORT_FAILED`, `UPLOAD_FAILED`) and add typed, allowlisted Drive operation/kind/optional numeric HTTP status.
- Identify root lookup, app-folder lookup/create, dataset-folder lookup/create, and file lookup/create/update separately. Distinguish HTTP, network, invalid JSON and rejected metadata. Keep response bodies, private IDs/paths, credentials, measurements, arbitrary exception messages and causes out of diagnostics.
- Render those diagnostics in the real ViewModel failure branch. Preserve confirmed partial-upload counts and failed status; cancellation still propagates. HTTP 403 suggests checking Drive API enablement and access but explicitly does not assert a root cause.
- Preserve existing least-privilege OAuth, private-target checks, transport redaction/limits/TLS/redirect policy, foreground-only reads, explicit continuation and no automatic write retries. No dependency, scope, credential, signing or CI changes.

| Requirement / test source | Executable evidence |
| --- | --- |
| Useful operation and HTTP diagnostic (success criteria/error paths) | `PrivateDriveUploaderTest.HTTP failures identify the exact Drive operation without exposing provider data or retrying`: 403 at every create-path request and 401 on update; confirms request counts and safe details |
| Network, invalid response, unsafe target and cancellation (error partitions/state) | `PrivateDriveUploaderTest.Network invalid response and rejected private target have distinct safe diagnostics` |
| Error survives uploader → coordinator → actual UI formatter (reported bug) | `SyncDiagnosticsTest.HTTP failure survives coordinator and appears in the user message without provider data` |
| Partial uploads, phase allowlist and arbitrary-message rejection (boundaries/state) | `SyncDiagnosticsTest.Partial progress and fixed phase diagnostics preserve failure without leaking arbitrary codes` |
| No changed requests, private upsert/retry/cancellation regressions | Existing core and six real local TLS transport tests, unchanged |

The new tests use the new typed result and formatter, so a runnable pre-fix comparison was not performed. The old ViewModel's generic failure branch was inspected directly. These synthetic tests establish safe failure reporting, not the cause of the user's Google account failure.

## Verification and review

Command: `python /tmp/run-health-gradle.py :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` (equivalent repository Gradle tasks with the managed environment JDK/SDK/proxy setup).

Result: BUILD SUCCESSFUL in 57s; 25 core + 6 app tests (31 total), zero failures/errors/skips. APK assembled; lint passed (0 errors, existing 11 warnings). No device/Google integration claim. `git diff --check` passed.

Self-review follows `code-review-and-quality` and `security-and-hardening` (security and local-reasoning checklists read). No new external calls, unbounded operations, dependencies or credential persistence. Error formatting is pure and uses fixed enums and bounded numeric HTTP status; transport continues discarding HTTP error bodies. This is a self-review, not independent spec verification. Existing dependency-audit evidence remains in PR-1-followup; no new graph audit was run for this dependency-unchanged patch. The password-reference metadata is older than 30 days; no password algorithm/default is used or changed here and no claim of refreshed password guidance is made.

## Remaining manual action

First check Google Drive API is enabled in the same Cloud project as the Android OAuth client (Cloud Console → APIs & Services → Library → Google Drive API → Enable). Retry the installed app without any JSON transfer. If it still fails, a diagnostic APK can show the safe operation/status. HTTP 403 alone does not distinguish disabled API, account policy, insufficient permission or quota.

Official error-status reference checked: https://developers.google.com/workspace/drive/api/guides/handle-errors . Privacy field contract checked: https://developers.google.com/workspace/drive/api/reference/rest/v3/files . No relaxation of omitted or unsafe metadata was made based on speculation.

GitHub Actions currently generates debug keys per runner. Every new artifact's certificate must be measured before sharing OAuth setup instructions. A new debug APK may require the matching Android OAuth package/SHA-1 and reinstall; do not claim stable signing or reuse an earlier artifact fingerprint. Stable CI signing is a separately managed key task, outside this diagnostic correction.

PR 1 remains draft. Real private Drive create/update, authorized records and actual ChatGPT file-content access remain open P0 gates.

## Hosted diagnostic artifact

GitHub Actions run [41177354752](https://github.com/nyeonu/health-app/actions/runs/41177354752), source `9635942f7e091125bc1496846fd9f1c33165294b`, completed successfully. Diagnostic APK artifact [13032759735](https://github.com/nyeonu/health-app/actions/runs/41177354752/artifacts/13032759735) was downloaded through the GitHub connector. Its ZIP SHA-256 matched GitHub metadata (`03cfb258c9fef85e40f92e1ae769b449ab285d14b3d2b503a36b1f42642cccdb`); `apksigner verify --print-certs` and `aapt dump badging` confirmed a valid debug APK and package `com.ny.my.health`. Its certificate differs from the previously shared CI artifact. The actual fingerprint was provided privately in the user handoff, not embedded in production code or used as a default. Registration of the new package/certificate pair and reinstallation/re-consent are required to use that differently signed build; first try Drive API enablement with the existing installation. No signing key was downloaded or shared.
