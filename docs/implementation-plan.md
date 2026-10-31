# Implementation plan — Health App

Last updated: 2026-10-31 (Asia/Seoul)  
Status: **P0 implemented; automated verification passed; partial device observations user-reported; real-record/cloud/content gates pending; PR 1 draft**

## Project outcomes
1. Collect user-authorized Health Connect health records that Samsung Health actually writes on the target Galaxy phone.
2. Safely export stable, versioned CSV data to a private Google Drive location without a mandatory backend.
3. Enable ChatGPT to analyze the exported data through a verified, user-authorized read path.
4. Evolve to bidirectional command and workout plan workflows if supported.

## Design decisions
- One repository: Android project now; Node.js/TypeScript API + MCP adapter later.
- Trigger and strategy are orthogonal:
  - Trigger: `MANUAL`, `SCHEDULED`, later `REMOTE`.
  - Strategy: `DAILY_SNAPSHOT`, later `INCREMENTAL`.
- Use `Asia/Seoul` local-day reporting and explicit timestamp offsets.
- No backend in P0/V1. Local state in Android, data export to Google Drive.
- Initial focus: steps, weight, heart rate, sleep sessions and exercise sessions. Validate each type's actual availability.
- Separate daily summaries and detailed sessions/time-series files; preserve source and unit metadata.
- CSV is an export/data-sharing contract, **not** the eventual source of truth for change tracking.
- Missing data is null/blank, not automatically zero. Confidence/freshness must be displayed.

## P0 — Technical proof of concept (FIRST)
**Implementation**
- Scaffold buildable, testable Android project (Kotlin / Compose) using current stable and compatible plugins.
- Inspect Health Connect availability on Android 16; permissions for steps, weight, exercise, sleep, heart rate.
- Implement basic foreground read of a **small recent window**, with permission flow and explicit empty/error states.
- Map to domain DTOs, serialize a minimal versioned CSV with stable field order and explicit units.
- Authenticate the user's Google account and upload to a user-approved private Drive folder, with deterministic naming / update behavior.
- Provide manual `Sync now` action and visible status; no scheduled sync required for PoC.
- Document how to verify Samsung Health -> Health Connect data availability, including source app and timestamp.
- Verify separately that ChatGPT can really access/read one uploaded file via the available Drive integration. If not possible, record the blocker and assess a Google Sheets read alternative.

**Exit gates (not assumed passed)**
- [ ] Fully validate permission behavior on target Galaxy phone: user reports install/launch, HC available, 5/5 grants and opt-in (comment 6537911289); denial/partial/zero/revocation scenarios remain pending. This limited observation does not close the gate.
- [ ] Read at least one real supported Health Connect record, or document precisely which records are unavailable.
- [ ] Generate valid UTF-8 CSV (without PII) from authorized records.
- [ ] Successfully upload and update a private file in Google Drive with user authorization.
- [ ] Verify ChatGPT's actual access to data contents, not merely file visibility (manual validation).
- [x] Unit tests for DTO mapping, CSV quoting, missing values, date boundaries pass (21 core JVM tests: original 18 + 3 OAuth diagnostic tests; 0 failures/errors/skips; 2026-10-31).
- [x] Write real device and cloud validation instructions ([P0 validation](P0/validation.md)).
- [ ] Complete real device and cloud results: partial user-reported observations recorded; generic OAuth failure, zero uploads and no successful sync. Root cause and Cloud setup unknown; actual records, real Drive and ChatGPT content checks remain pending.

Automated evidence on 2026-10-31: `:core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` passed (combined run also resolved `debugRuntimeClasspath`); 21 core + 6 local TLS app tests = 27, with 0 failures/errors/skips; lint has 0 errors and 11 warnings. This proves neither device reads nor OAuth, real uploads or ChatGPT content access. Synthetic CSV tests pass; the authorized real-record CSV gate above remains open. The updated 117-coordinate runtime OSV query returned the same three Guava advisory matches, with no new OkHttp/Okio matches; preproduction review and the 2026-11-30 recheck remain due (see the validation handoff). The follow-up uses OkHttp with one-shot POST/PATCH bodies: the local HTTP 503/`Retry-After: 0` regression proves exactly one send and a redacted 503 result until a later explicit call; historical P0 verification reports are snapshots superseded by this evidence. No emulator/UI or Google Cloud account access was performed. Privately check the actual installed APK signing SHA-1, Android OAuth package/client, test audience/user and enabled Drive API using the [beginner handoff](P0/validation.md#google-project-configuration-beginner-handoff-pending-user-controlled-setup); never commit credentials or signing keys. CI-generated debug keys may differ by build. Report only the updated fixed diagnostic category/numeric status, never raw account/token/error data.

## V1 — Android MVP

Storage choice/coexistence is [documented as V1 design with seven pending Galaxy scenarios](V1/storage-destinations.md). It does not implement SAF or change P0 gates. Capability defaults remain unsupported/unverified until each device/provider is tested; SAF uses system consent without app Google OAuth setup, while Drive API retains its OAuth requirements.

- [ ] Implement the shared `StorageDestination.upsert(CsvFile)` boundary with Drive API and SAF destinations only in V1, after P0 gates.
- [ ] Validate SAF local-folder create/update, Drive tree support, persisted grants/reboot, same-date overwrite, and permission/provider failures on Galaxy; all actual results pending.
- [ ] Persist per-date/per-destination coverage and per-file/revision confirmation; keep healthy results and retry only failed/pending sinks. Later both mode succeeds only when all intended sinks confirm.
- [ ] Verify restored SAF URI access in WorkManager and missed-day recovery as V1 tests, not P0 merge gates.
- [ ] User-triggered manual synchronization.
- [ ] Once-daily WorkManager scheduling subject to Android restrictions and background-read permissions/feature checks.
- [ ] Daily snapshot exports; re-sync recent 2 days to account for delayed source sync, avoid duplicates.
- [ ] Clear status UI: last attempt, last success, rows, pending, failure reasons.
- [ ] Retry/backoff and idempotent Drive upsert; network and permission denial handling.
- [ ] No false promise of exact-time execution; handle missed days.
- [ ] Persist per-local-day sync coverage; detect and queue missing dates on periodic worker run, app launch, and manual sync.
- [ ] Backfill missed dates in bounded batches, keeping failed/incomplete dates pending until every intended destination confirms its files.
- [ ] Repeating sync for an already-exported date updates the same private Drive file without duplicates.
- [ ] Surface overdue/stale data explicitly (last attempted, last successful, covered-through date) rather than displaying a misleading success.

### V1 background scheduling acceptance scenarios
| Scenario | Expected behavior |
| --- | --- |
| Device active and connected | Worker may run near its eligible period; no exact-time assertion. |
| Doze, power saving, or constrained network | Worker may be delayed; data remains visibly stale until verified sync. |
| No network for 2 days; reconnect later | Next allowed run or foreground/manual trigger detects and backfills both missed dates. |
| Phone turned off for a day, then restarted | WorkManager's persisted scheduling resumes when permitted; missed date is backfilled. |
| App force-stopped | No guarantee of background execution until Android permits it again; app reopening initiates coverage check. |
| Duplicate worker execution / retry | Each selected destination upsert is idempotent; only failed/pending sinks retry, and date coverage advances only after all intended destinations confirm. |
| Samsung Health record arrives late | Refresh recent dates and update existing day file as needed. |
| Device battery low | Automatic sync defers per constraints; manual request reports any blocker clearly. |


## Battery optimization and timing semantics (approved for V1)
- WorkManager / `CoroutineWorker` is the official Android scheduling and execution mechanism. **We write the `SyncWorker` business logic; we do not implement a custom scheduler/thread manager.** No infinite polling loop, continuous background service, or manually-held wake lock.
- Automatic sync target: approximately once per day; **not** a guaranteed wall-clock time. A 24-hour `PeriodicWorkRequest` can be deferred significantly due to Doze, system quotas, standby, device power-off, force-stop, absent connectivity, and configured constraints. Work can fail or miss a calendar day.
- Schedule with network-connected and battery-not-low constraints. Use exponential retry for transient failures, and surface permanent failures (permissions revoked or unsupported APIs) to the user. A delayed/blocked job must not appear as a success.
- Daily data coverage is more important than exact execution time. Maintain `lastSuccessfulSyncDay` and completed local-date coverage; on worker execution, app foreground, or manual sync, detect and **backfill missed days** idempotently. If multiple days were missed, process bounded batches and record incomplete coverage.
- Re-read at least recent two days to reconcile delayed Samsung Health to Health Connect writes. Upsert deterministic Drive filenames for the same dates; do not duplicate rows/files. Keep original event instants, report by `Asia/Seoul` local date.
- Prefer Health Connect Aggregate API for supported daily summary metrics; do not read or upload continuous raw heart-rate samples unless required for workout sessions or explicit analysis requests.
- Batch collection, CSV generation, and Drive upload into one scheduled job; skip unchanged files when feasible. The device should sleep between jobs.
- The app UI must distinguish `lastAttemptAt`, `lastSuccessAt`, `dataThroughDate`, queued/blocked status, and explicit sync errors. Do not call a job successful when Health Connect or Drive transfer was incomplete.
- Before calling V1 done, test on Galaxy S23 Ultra with screen off/Doze, limited connectivity, low battery, reboot, late Samsung Health arrival, and recovery after missing at least one calendar day. Capture battery usage metrics. **Do not claim a specific percentage battery cost without measurement.**
- No `AlarmManager.setExact` or exact-alarm special permissions for normal daily synchronization. Those are for genuinely time-critical user-facing alarms, not our batch data-export use case.

## V2 — Incremental sync
- [ ] Implement Health Connect Changes API with per-type token/state as appropriate.
- [ ] Handle insert/update/deletion and token expiry; full rescan fallback.
- [ ] Recover from partial failures without advancing cursor incorrectly.
- [ ] Test reconciliation against full daily snapshot; prevent double counting.
- [ ] Address updated historical sessions affecting past-day summaries.

## V3 — Remote query and sync
- [ ] Confirm actual ChatGPT/MCP availability on user's target client and account.
- [ ] Implement small authenticated Node.js/TypeScript REST API and separate MCP adapter.
- [ ] Initial tools: `get_daily_health`, `get_workout_sessions`, `request_sync`, `get_sync_status`.
- [ ] Push wake-up via FCM/command queue (best effort, not guaranteed instant); APK sends completion state.
- [ ] Server-side authorization, command expiry/replay prevention and privacy-safe logging.

## V4 — Bidirectional workout plans
- [ ] `save_workout_plan` with versioned schema.
- [ ] Android fetch/display of planned exercises, sets, reps, loads and statuses.
- [ ] Separate user-entered strength-training details from what Health Connect actually supports.
- [ ] Capture completion and re-analyze performed vs planned work.

## Key risks / checkpoints
- Samsung Health may not publish every datum or requested granularity into Health Connect.
- Background Health Connect reads depend on platform support, user permission, and runtime state.
- WorkManager is deferrable; scheduled execution is not an exact alarm.
- Google OAuth and Drive APIs need actual on-device consent and Google project configuration.
- A CSV in Drive is not automatically consumable by ChatGPT; verify first.
- ChatGPT custom MCP support varies with product surface; do not design MVP around assumed mobile access.
- Real health data must never enter a public repository, logs, test fixtures or bug reports.

## Phase reporting template
For each phase record: implemented files, tests and results, manual/device verification, remaining blockers, and the next smallest task.

Latest P0 follow-up: [safe sync diagnostic reporting](PR-1-sync-diagnostics/report.md) passes 31 JVM tests, APK build and lint. User-reported read coverage advanced to all five types, with a subsequent generic failure and zero confirmed uploads. Root cause, private Drive create/update and ChatGPT content-access gates remain open.

P0 runtime correction: the user supplied `DRIVE_ROOT_LOOKUP:HTTP_404`. [Root metadata dependency removed](PR-1-drive-root/report.md), with root-query membership and post-create confirmation before health writes; 33 JVM tests, APK build and lint pass. Private account upload remains pending, so no P0 exit checkbox is closed by this correction.

User-reported follow-up: successful foreground sync with two confirmed files after the root correction; independent contents/private-location/same-day update/ChatGPT gates remain pending. [Fixed CI signing setup](P0/fixed-signing.md) supports owner-managed key reuse and stops random-certificate APK publication. Actual key generation/backup/Secrets registration and hosted signing remain user-controlled pending steps.

Hosted fixed signing now passed using owner-provided Repository Secrets after correcting Base64 input: run 41183096679 / artifact 13036249453. Distributed APK package and certificate were verified against signing.txt. Owner backup and fixed-certificate OAuth/device migration remain pending.
