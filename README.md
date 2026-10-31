# Health App

Personal, privacy-conscious Android health-data integration with ChatGPT.

## Goal

Read authorized health records from Android Health Connect (including data synchronized by Samsung Health), export normalized CSV files to a private Google Drive folder, and later support ChatGPT analysis and two-way workout-plan workflows through a separate API/MCP layer.

**Repository:** `nyeonu/health-app`  
**Primary device:** Galaxy S23 Ultra (Android 16)  
**Time zone for daily reporting:** Asia/Seoul  
**Status (2026-11-01 KST):** **P0 merged into `master`** via [PR #1](https://github.com/nyeonu/health-app/pull/1) (merged 2026-11-01 00:45 KST, Draft = false). 33 JVM tests and 3 Android emulator tests passed; APK build and lint passed. The Galaxy reported successful Health Connect/Drive sync. The connected Drive CSV bodies, owner-only permissions, two same-ID revisions and source Samsung Health step-count match were independently verified. Device-only permission revocation, offline/in-flight cancellation and final dependency/security follow-up remain open; merge does **not** mean those checks passed.

## Delivery phases

| Phase | Deliverable |
| --- | --- |
| P0 — Technical PoC | Verify real Health Connect records, permissions, Drive upload, and actual ChatGPT file access |
| V1 — Android MVP | Manual + once-daily snapshot sync, CSV export, private Drive upload, sync status and retry |
| V2 — Incremental sync | Health Connect Changes API, updates/deletes, persisted tokens, recovery |
| V3 — Remote command | Independent TypeScript REST backend and MCP adapter; optional async mobile sync command |
| V4 — Workout plans | ChatGPT-generated plan storage, Android read/display, workout completion feedback |

Trigger (manual, scheduled, remote) and strategy (daily snapshot, incremental) are independent concepts.

## Initial architecture

```text
Galaxy Watch -> Samsung Health -> Health Connect
                                  |
                                  v
                         Android App (Kotlin)
                         - permissions
                         - HealthReader
                         - SyncEngine
                         - CsvExporter
                         - DriveUploader
                                  |
                                  v
                          Private Google Drive
                                  |
                      user-authorized ChatGPT analysis

Future: ChatGPT -> MCP Adapter -> REST API -> queue/FCM -> Android App
```

The future MCP server does **not** directly access Android Health Connect. Mobile device reads require the app and user-granted permissions. MCP access to ChatGPT depends on product/client support and must be verified before V3.

## Source of truth

- [Agent instructions](AGENTS.md)
- [Implementation plan and acceptance criteria](docs/implementation-plan.md)
- [Architecture and trust boundaries](docs/architecture.md)
- [CSV data contract](docs/data-contract.md)
- [V1 storage destination design and pending Galaxy SAF checklist](docs/V1/storage-destinations.md)
- [First Codex assignment](docs/codex-first-task.md)

## Security

**Never commit** real health measurements, CSV exports, Google tokens, OAuth credentials, private keys, or personal identifiers. The GitHub repository is public. Store health records only in explicitly authorized private locations. Minimize collected data and never assume a missing value is zero.

## Implementation

The `android/` project contains a Compose foreground-only manual sync app and a pure JVM `core` module. The app exports yesterday in `Asia/Seoul`, reads only granted categories, and requires private-folder opt-in, Google authorization and an explicit upload action. Tokens and last-success state are memory-only. No backend, scheduling or incremental sync is implemented.

See [P0 build, OAuth setup and device validation](docs/P0/validation.md) before installing or using real data. Build from `android/` with a full JDK 17+ (verified with JDK 21), Android SDK 36 and `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`. SDK-free core tests: `./gradlew -PcoreOnly :core:test`. Do not advance to V1 until the remaining P0 gates are verified.

The application package is `com.ny.my.health` (core: `com.ny.my.health.core`). This identity is a different app, so install it as a new app rather than an in-place update; consider local state before removing the previous app. Request Health Connect permissions and Google authorization again. Register the new package with the actual installed APK certificate SHA-1: the previous OAuth client does not apply automatically. Prior `drive.file` folder/file visibility is not guaranteed. There is no automatic migration or deletion; inspect and clean up previous exports privately and manually. Follow the [application identity migration guide](docs/P0/validation.md#application-identity-migration).

The PR 1 follow-up passed 21 core unit tests and 6 synthetic local TLS transport tests (27 total, no failures/errors/skips), debug build and lint (0 errors/11 warnings). Local TLS tests verify OkHttp create/PATCH behavior, including exactly one send on HTTP 503 with `Retry-After: 0` and a redacted 503 result before a later explicit call; they do not prove Google account access. The user reported generic OAuth failure and zero uploads; its cause and Cloud setup remain unknown. Follow the [beginner Google Cloud and actual APK certificate steps](docs/P0/validation.md#google-project-configuration-beginner-handoff-pending-user-controlled-setup), then share only a fixed OAuth category/numeric status if retry fails.

V1 storage design adds a future choice of SAF local/provider folder or Drive API over the same CSV pipeline, with later both-mode independent status/retry. SAF requires Android system consent but no app Google OAuth/Cloud setup; provider folder/write/persisted-access capabilities remain unsupported until device verification. The [seven-scenario Galaxy checklist](docs/V1/storage-destinations.md#galaxy-s23-ultra-poc--seven-requested-scenarios) is entirely pending. SAF and WorkManager are not implemented and SAF is not a P0 merge gate.

Latest device report shows a completed Health Connect read with all five allowed types, followed by generic sync failure and zero confirmed uploads. The [sync diagnostics correction](docs/PR-1-sync-diagnostics/report.md) now displays allowlisted phase, Drive operation and numeric HTTP status without provider bodies or personal data (31 tests pass). The actual account failure and private upload remain unverified; check Drive API enablement before installing a differently signed diagnostic APK.

The next device diagnostic identified `DRIVE_ROOT_LOOKUP:HTTP_404`. The [Drive root correction](docs/PR-1-drive-root/report.md) removes the root-metadata prerequisite, looks up app folders with the documented root alias, and confirms newly created app folders under My Drive before dataset/CSV writes. The regression failed before correction and passes afterward; 33 JVM tests, APK build and lint pass. `drive.file` and private-target checks remain; actual Google upload and P0 gates are still pending.

Fixed CI signing is configured using two owner-managed Actions Repository secrets. Trusted branch builds distribute APKs only when the fixed key is available; PR validation never uses the key and missing secrets do not produce a random-certificate download. See the [phone/Codespaces key creation and setup guide](docs/P0/fixed-signing.md). Owner Secrets were accepted after correcting a Base64 paste; the hosted fixed-certificate build passed and its distributed APK certificate was independently checked against signing.txt. The user now reports successful fixed-certificate installation/OAuth/sync with two confirmed files. Private backup, remote CSV/private-sharing/no-duplicate-update inspection and permission edge cases remain pending; repeated local synthetic-key builds verified certificate reuse.

[PR #1 post-merge acceptance status](docs/P0/pr-readiness.md) records successful live private Drive CSV content reads, source step-count matching, two-revision idempotent updates and completed Android emulator tests. The owner's merge is recorded; unresolved device-edge and security follow-ups remain tracked rather than silently marked complete.


> Historical diagnostic sections above describe earlier failures during PR development; they are retained for troubleshooting and are superseded by the current P0 post-merge status at the top and in the readiness report.
