# Architecture and integration boundaries

## Data path now (P0/V1)
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
                 Manual / Scheduled
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

## Component responsibilities
- `HealthReader`: permissions, availability, pagination, record reads.
- `Normalizer`: clean domain entities, units, timestamps and provenance; no speculative values.
- `CsvExporter`: deterministic file formats, proper escaping and column ordering.
- `DriveUploader`: Google OAuth account access, create/update, retry and metadata.
- `SyncCoordinator`: trigger-independent orchestration, status, idempotency and history.
- `SyncState`: persistent last-success timestamp, pending uploads, and future change tokens.
- Future `REST API`: authentication, stored read models, jobs and workout plans.
- Future `MCP adapter`: thin tool handlers delegating to the REST API.

## Privacy / trust boundaries
1. **Health Connect -> App:** foreground/background authorization must be granted; permissions are revocable.
2. **App -> Drive:** distinct Google OAuth consent; use least-privilege scopes and private file access.
3. **Drive -> ChatGPT:** separately user-authorized connector; access must be empirically tested.
4. **ChatGPT -> MCP -> App:** authenticate each tool action; limit data exposure; protect command queue from replay/spoofing.

The repository is public. No real data, tokens, personal identifying values, signing keys, or export archives in Git or CI logs.
