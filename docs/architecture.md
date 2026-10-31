# Architecture and integration boundaries

## Current P0 data path
```text
Galaxy Watch / phone sensors
          |
          v
Samsung Health (watch and phone apps; sync semantics vary)
          |
          v
Android Health Connect (local, permission-protected data store)
          |
          | user-authorized read
          v
Health App / Android APK
    HealthReader -> Normalizer -> CsvExporter -> DriveUploader
                         ^
                         |
                 SyncCoordinator
                 Manual (P0)
          |
          | OAuth with least privileges
          v
Private Google Drive folder
          |
          | separately validated ChatGPT Drive connection
          v
ChatGPT (analysis/fitness suggestions)
```

Health Connect is an Android-managed shared data store, not an open remote API or automatic Google Drive backup. Other connected apps may also write records; do not assume all data originates from Samsung Health.

## V1 storage coexistence (design only)

V1 proposes `HealthReader -> Normalizer -> CsvExporter -> StorageDestination.upsert(CsvFile)`, with `DriveApiDestination` and `SafDestination`. Users choose an explicitly authorized private SAF local/provider folder or Drive API; later both mode needs independent date/destination outcomes, coverage and retries. Full success requires every intended sink to confirm; a failed sink must not erase or repeat healthy confirmed results.

SAF uses system folder consent, persisted granted URI permissions and provider-verified create/update behavior; it needs no app Google OAuth/Cloud setup. A provider can have its own account requirements. No provider capability, including Google Drive tree support, is enabled before device verification. Selected local/provider folders are not inherently private. See the [V1 storage design and seven pending Galaxy scenarios](V1/storage-destinations.md) for privacy boundaries, truncation/idempotency and failure recovery. This is future design only: P0 retains `DriveUploader`, manual triggering and existing OAuth/private-Drive protections. SAF is not a P0 merge gate.

## Future command flow (V3+)
```text
ChatGPT -- supported MCP client --> MCP adapter (TypeScript)
  |                                      |
  | Query: get_*                         v
  |                              authenticated REST API
  |                                      |
  |                     +----------------+------------------+
  |                     |                                   |
  |                Drive-backed read                   command job queue
  |                                                         |
  |                                               FCM wake-up (best effort)
  |                                                         |
  |                                                 Android APK
  |                                                         |
  |                                          Health Connect / Drive upload
  |                                                         |
  +---------------- status/result via API ------------------+
```

- MCP server **does not** directly query the phone's Health Connect.
- Remote sync is asynchronous: `request_sync()` yields a job ID; `get_sync_status()` polls.
- An API/MCP tool can read already-synced Drive data even if the phone is offline.
- For workout plans, prefer server-persisted plans fetched by the phone over synchronous inbound phone RPC.
- Android push delivery and background execution are best effort, not immediate guarantees.
- Do not implement remote commands until supported product access and a secure auth model are confirmed.


## Background execution and energy budget (V1 design)
- WorkManager persists the periodic task and delegates its execution to OS background scheduling; `CoroutineWorker` invokes the app's `SyncCoordinator`. The app does not need a constantly running process.
- Never confuse **frequency** (roughly daily) with **wall-clock deadline** (exactly at 06:00). Android may defer jobs by minutes or hours, and extended blocking can prevent a day's execution altogether.
- Persistence/repair: local last-success cursor + day coverage status; every allowed execution and foreground open checks for missed local dates and performs bounded, idempotent catch-up. Recently synced days may be refreshed for late upstream source writes.
- Automatic work: network-connected and battery-not-low; retry transient failures with backoff. Manual sync can be initiated by the user, with clear status when it cannot proceed.
- Keep the device asleep between executions: batch Health Connect reads and Drive uploads, use aggregation for summary values, do not poll sensors or run a foreground service continuously.
- Quality gate: device testing of Doze, reboot, missed days, offline state, low battery, and battery usage before declaring V1 complete.

## Component responsibilities
- `HealthReader`: permissions, availability, pagination, record reads.
- `Normalizer`: clean domain entities, units, timestamps and provenance; no speculative values.
- `CsvExporter`: deterministic file formats, proper escaping and column ordering.
- `DriveUploader`: Google OAuth account access, create/update, retry and metadata.
- `SyncCoordinator`: trigger-independent orchestration, status, idempotency and history.
- V1 `SyncState` (future): durable date/destination coverage, per-file/revision confirmation and pending exports; later change tokens. P0 last-success metadata remains memory-only.
- Future `REST API`: authentication, stored read models, jobs and workout plans.
- Future `MCP adapter`: thin tool handlers delegating to the REST API.

## Privacy / trust boundaries
1. **Health Connect -> App:** foreground/background authorization must be granted; permissions are revocable.
2. **App -> Drive:** distinct Google OAuth consent; use least-privilege scopes and private file access.
3. **App -> SAF (V1 design):** separate system picker consent; private URI/grant state, verified provider operations and explicit destination privacy review. No automatic sharing or assumption that a user-visible folder is private.
4. **Drive -> ChatGPT:** separately user-authorized connector; access must be empirically tested.
5. **ChatGPT -> MCP -> App:** authenticate each tool action; limit data exposure; protect command queue from replay/spoofing.

The repository is public. No real data, tokens, personal identifying values, signing keys, or export archives in Git or CI logs.
