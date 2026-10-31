# Health App

Personal, privacy-conscious Android health-data integration with ChatGPT.

## Goal

Read authorized health records from Android Health Connect (including data synchronized by Samsung Health), export normalized CSV files to a private Google Drive folder, and later support ChatGPT analysis and two-way workout-plan workflows through a separate API/MCP layer.

**Repository:** `nyeonu/health-app`  
**Primary device:** Galaxy S23 Ultra (Android 16)  
**Time zone for daily reporting:** Asia/Seoul  
**Status:** P0 Android application implemented; JVM tests, debug APK build and lint pass. Install/launch, Health Connect availability and 5/5 grants are user-reported partial evidence; actual records, Google OAuth/private Drive upload and ChatGPT file-content access remain unverified. PR 1 remains draft. P0 exit gates remain open.

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

The PR 1 follow-up passed 21 core unit tests and 5 synthetic local TLS transport tests (26 total, no failures/errors/skips), debug build and lint (0 errors/11 warnings). Local TLS tests verify OkHttp create/PATCH behavior; they do not prove Google account access. The user reported generic OAuth failure and zero uploads; its cause and Cloud setup remain unknown. Follow the [beginner Google Cloud and actual APK certificate steps](docs/P0/validation.md#google-project-configuration-beginner-handoff-pending-user-controlled-setup), then share only a fixed OAuth category/numeric status if retry fails.

V1 storage design adds a future choice of SAF local/provider folder or Drive API over the same CSV pipeline, with later both-mode independent status/retry. SAF requires Android system consent but no app Google OAuth/Cloud setup; provider folder/write/persisted-access capabilities remain unsupported until device verification. The [seven-scenario Galaxy checklist](docs/V1/storage-destinations.md#galaxy-s23-ultra-poc--seven-requested-scenarios) is entirely pending. SAF and WorkManager are not implemented and SAF is not a P0 merge gate.
