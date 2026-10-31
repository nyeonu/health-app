# Implementation plan — Health App

Last updated: 2026-10-31 (Asia/Seoul)  
Status: **P0 implemented; 33 automated tests/build/lint passed, foreground Drive CSV bodies, private metadata and same-target update observed via connected Google Drive; remaining device edge-case/source completeness/security checks pending; PR 1 draft**

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
- [x] Read supported live Health Connect records: Samsung Health-origin data appeared in the authorized Drive CSV output. Source-side completeness and unavailable categories require separate phone cross-check.
- [x] Verify exported CSV content and schema through the authenticated Drive connector: daily summary and sample rows parsed; intentionally missing fields remain blank; no direct personal identifiers exported.
- [x] Verify private Google Drive upload/update: owner-only, non-shared folder and CSV metadata; same file identities with later modification timestamps and no duplicate day files in target folders.
- [x] Verify ChatGPT's authenticated Google Drive connector can read actual CSV bodies, not only file names or metadata (2026-10-31).
- [x] Unit tests for DTO mapping, CSV quoting, missing values and date boundaries pass, with uploader/diagnostic/transport regressions: 27 core + 6 local TLS app tests = 33; 0 failures/errors/skips (2026-10-31).
- [x] Write real device and cloud validation instructions ([P0 validation](P0/validation.md)).
- [ ] Complete all device acceptance results: user-reported installation, permissions and sync, plus independently verified private Drive CSV content/update are recorded; permission denial/revocation, offline/background/lifecycle behavior and source completeness cross-check remain pending.

Automated evidence on 2026-10-31: `:core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` passed with 27 core + 6 real-client local TLS app tests = 33 (0 failures/errors/skips), lint 0 errors/11 existing warnings. Hosted source `59bf62e` passed PR checks and the owner-secret push build (run 41183096679); the distributed APK package/certificate matched signing.txt. App-screen success on the fixed-signature APK is now user-reported. This does not prove CSV contents, independent private sharing inspection, same-day remote update or full permission-edge behavior. The runtime OSV audit of 117 coordinates retains three Guava advisory matches and the preproduction review/2026-11-30 recheck; no dependency changes were introduced in subsequent runtime/signing corrections. See [latest readiness evidence and remaining checks](P0/pr-readiness.md).

Downloaded CI APKs now require the fixed owner key; PR runs never receive signing secrets, absent secrets skip publication, invalid signing never falls back to a random downloadable key. Keep the same key to retain the certificate; never commit credentials or signing keys. The independent earlier code review remains documented in PR-1-followup; final merge readiness depends on the remaining checks below, not an app success message alone.


**Live P0 verification update (2026-10-31):** After Google Drive reconnection, the assistant independently retrieved both CSV file bodies from the connected private Drive, validated schema/date/source structure and blank missing fields, and checked that the root/files remain owner-only and unshared. Both files have stable identities with creation preceding later modification and no duplicate within their target dataset folder. Do not infer whole-day Health Connect completeness or that permission-denial, offline and lifecycle edge cases passed. No private health values, user/account identifiers, or resource URLs are committed. Details: [PR readiness](P0/pr-readiness.md).

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

User-reported follow-up: successful foreground sync with two confirmed files after the root correction. Independently verified afterward: authenticated Drive connector read both CSV bodies; both are owner-only/unshared, unique within their target dataset folders, with stable file IDs and later modification times. Source-side completeness, permission-edge flows and final security review remain open. [Fixed CI signing setup](P0/fixed-signing.md) supports owner-managed key reuse and prevents random-certificate APK publication. The hosted fixed-signing run succeeded; owner-controlled key backup is not verified.

Hosted fixed signing now passed using owner-provided Repository Secrets after correcting Base64 input: run 41183096679 / artifact 13036249453. Distributed APK package and certificate were verified against signing.txt. Owner backup and fixed-certificate OAuth/device migration remain pending.
