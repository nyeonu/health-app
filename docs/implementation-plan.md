# Implementation plan — Health App

Last updated: 2026-10-31 (Asia/Seoul)  
Status: **Not started**

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
- [ ] Can request and inspect permissions on target Galaxy phone.
- [ ] Read at least one real supported Health Connect record, or document precisely which records are unavailable.
- [ ] Generate valid UTF-8 CSV (without PII) from authorized records.
- [ ] Successfully upload and update a private file in Google Drive with user authorization.
- [ ] Verify ChatGPT's actual access to data contents, not merely file visibility (manual validation).
- [ ] Unit tests for DTO mapping, CSV quoting, missing values, date boundaries pass.
- [ ] Record real device and cloud validation instructions/results.

## V1 — Android MVP
- [ ] User-triggered manual synchronization.
- [ ] Once-daily WorkManager scheduling subject to Android restrictions and background-read permissions/feature checks.
- [ ] Daily snapshot exports; re-sync recent 2 days to account for delayed source sync, avoid duplicates.
- [ ] Clear status UI: last attempt, last success, rows, pending, failure reasons.
- [ ] Retry/backoff and idempotent Drive upsert; network and permission denial handling.
- [ ] No false promise of exact-time execution; handle missed days.

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
