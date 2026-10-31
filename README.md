# Health App

Personal, privacy-conscious Android health-data integration with ChatGPT.

## Goal

Read authorized health records from Android Health Connect (including data synchronized by Samsung Health), export normalized CSV files to a private Google Drive folder, and later support ChatGPT analysis and two-way workout-plan workflows through a separate API/MCP layer.

**Repository:** `nyeonu/health-app`  
**Primary device:** Galaxy S23 Ultra (Android 16)  
**Time zone for daily reporting:** Asia/Seoul  
**Status:** Planning and proof-of-concept; no application has been implemented yet.

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
- [First Codex assignment](docs/codex-first-task.md)

## Security

**Never commit** real health measurements, CSV exports, Google tokens, OAuth credentials, private keys, or personal identifiers. The GitHub repository is public. Store health records only in explicitly authorized private locations. Minimize collected data and never assume a missing value is zero.

## Implementation

Start with P0 only. Do not build the MCP server or production background synchronization until the end-to-end data path is demonstrated. See `docs/codex-first-task.md`.
