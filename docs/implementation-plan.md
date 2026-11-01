# Implementation plan — Health App

Last updated: 2026-11-01 (Asia/Seoul)  
Status: **P0 merged to `master` in PR #1 on 2026-11-01 00:45 KST (Draft = false). 33 JVM + 3 Android 15 emulator tests, APK build and lint passed; private Drive CSV contents, owner-only metadata, same-file updates and source step count verified. Device-only revocation/offline/in-flight interruption and security/dependency follow-ups remain tracked post-merge.**

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

**Automated UI update (2026-11-01 KST):** [Android emulator run](https://github.com/nyeonu/health-app/actions/runs/41212355650) passed 3/3 tests on API 35 (first-launch consent gate, refused upload with zero HC grants, Activity background/resume without fake success). The [regular CI run](https://github.com/nyeonu/health-app/actions/runs/41212355674) passed JVM tests, APK build and lint. These are account-free tests and do not close device-only permission revocation/airplane-mode/in-flight upload tests. [Test matrix](P0/automated-testing.md).

**Exit gates (not assumed passed)**
- [ ] Fully validate permission behavior on target Galaxy phone: installation/foreground sync, D01 zero-grant refusal and D02 steps-only UI behavior are user/UI verified. D02 shows 1/5, STEPS coverage and one uploaded file/success; actual CSV fields are now verified; active revocation and other permission edge cases remain pending. The full gate stays open.
- [x] Read supported live Health Connect records: Samsung Health-origin data appeared in the authorized Drive CSV output, and the user-provided Samsung Health daily-activity screen **independently matched the exported daily step count for the same local date**. Other data types still require source coverage checks.
- [x] Verify exported CSV content and schema through the authenticated Drive connector: daily summary and sample rows parsed; intentionally missing fields remain blank; no direct personal identifiers exported.
- [x] Verify private Google Drive upload/update: owner-only, non-shared folder and CSV metadata; same file identities with later modification timestamps and no duplicate day files in target folders.
- [x] Verify ChatGPT's authenticated Google Drive connector can read actual CSV bodies, not only file names or metadata (2026-10-31).
- [x] Unit tests for DTO mapping, CSV quoting, missing values and date boundaries pass, with uploader/diagnostic/transport regressions: 27 core + 6 local TLS app tests = 33; 0 failures/errors/skips (2026-10-31).
- [x] Write real device and cloud validation instructions ([P0 validation](P0/validation.md)).
- [ ] Complete all device acceptance results: user-reported installation, permissions and sync, plus independently verified private Drive CSV content/update are recorded; permission denial/revocation, offline/background/lifecycle behavior and source completeness cross-check remain pending.

Automated evidence on 2026-10-31: `:core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` passed with 27 core + 6 real-client local TLS app tests = 33 (0 failures/errors/skips), lint 0 errors/11 existing warnings. Hosted source `59bf62e` passed PR checks and the owner-secret push build (run 41183096679); the distributed APK package/certificate matched signing.txt. App-screen success on the fixed-signature APK is now user-reported. This does not prove CSV contents, independent private sharing inspection, same-day remote update or full permission-edge behavior. The runtime OSV audit of 117 coordinates retains three Guava advisory matches and the preproduction review/2026-11-30 recheck; no dependency changes were introduced in subsequent runtime/signing corrections. See [latest readiness evidence and remaining checks](P0/pr-readiness.md).

Downloaded CI APKs now require the fixed owner key; PR runs never receive signing secrets, absent secrets skip publication, invalid signing never falls back to a random downloadable key. Keep the same key to retain the certificate; never commit credentials or signing keys. The independent earlier code review remains documented in PR-1-followup; the repository owner merged PR #1 with the outstanding device and security items still open; acceptance of merge is not evidence that unperformed checks passed.


**Live P0 verification update (2026-10-31):** After Google Drive reconnection, the assistant independently retrieved both CSV file bodies from the connected private Drive, validated schema/date/source structure and blank missing fields, and checked that the root/files remain owner-only and unshared. Both files have stable identities with creation preceding later modification and no duplicate within their target dataset folder. The user additionally supplied a Samsung Health daily activity screenshot for the same local date: **the daily step count matched the uploaded CSV exactly**. The screenshot's activity time, energy and distance are distinct measures not implemented in P0 CSV; their absence is not a transport failure. Do not infer completeness for weight/sleep/exercise records or that permission-denial, offline and lifecycle edge cases passed. No private health values, user/account identifiers, or resource URLs are committed. Details: [PR readiness](P0/pr-readiness.md).

## V1 — Android MVP

**Current P0 acceptance stage:** PR #4's corrected head `fd48be0` received [owner approval](https://github.com/nyeonu/health-app/pull/4#issuecomment-6551010783) after 47 JVM + 3 emulator tests and APK/lint CI passed, then merged as `d33edae`. The prior `192ab46` review hold is superseded by that re-review. Next is fixed-signature Galaxy acceptance, including the [per-page budget's remaining volume/SDK-response limits](P0/reader-sample-budget-fix.md), [D01–D09 and key backup](P0/device-acceptance.md). No device checkbox or V1 entry is closed by the merge.

**Current re-upload validation:** [Four complete current datasets](P0/latest-upload-verification.md), including an earlier-start overnight sleep session, are verified with private sharing, unique filenames and schema/time/duration checks. D00/D08 remain partial for original-source completeness and weight availability. Remaining phone/account/backup answers are bundled; P0 exit/V1 implementation is not claimed and performance work remains deferred.

**Earlier remote recovery verification:** [Authenticated Drive CSV/revision/privacy checks](P0/drive-recovery-verification.md) close D02 and D05 for the observed steps-only/offline-recovery scenarios. D00 remains partial (no real overnight source comparison), D03 is unobserved and D04/D06/D08–D09/key backup remain open, while D07 now passes its observed reset/recovery scenario. No full exit checkbox is closed. D06 has since shown the expected app-departure interruption, 0 confirmed uploads and retained prior success. D06 home-departure/retry now passes by UI plus authenticated same-target/no-duplicate/private-access metadata after the interruption; screen-lock remains separate. D07 force-stop/reopen now passes its reset UI check (opt-in unchecked, Sync now disabled, attempts/success/read coverage cleared, HC grants retained). The user subsequently reports successful explicit manual recovery after this restart; D07 now passes for the observed user/UI scenario. Next is D08 source availability, starting with Health Connect sleep records.

**Earlier user/UI snapshot (D02/D05 pending statements superseded by remote verification above):** following the master APK handoff, the user reports install/launch and shows foreground sync success with 5/5 grants and three confirmed uploaded files. [D00 is partial; D01 is PASS for zero-grant refusal](P0/device-acceptance.md): a subsequent screen shows 0/5, PERMISSION_REQUIRED, no attempt, 0 uploads and no success. This does not verify all five populated data types or close full permission/offline/interruption/backup gates. D02 has since passed its steps-only UI check (1/5, STEPS coverage, one uploaded file/success), with actual CSV contents still unverified. D03 could not reach the isolated read-time check: after HC revocation the user reports refreshed UI with only permission request available and no upload continuation. Process restart is not proven. D05 now shows the expected offline UPLOAD_FAILED:DRIVE_APP_FOLDER_LOOKUP:NETWORK, 0 files and no success. Next is explicit online retry/recovery and remote same-target verification; D04 remains pending.

**Dependency correction merged:** [PR #3](https://github.com/nyeonu/health-app/pull/3), merge `178ee3f`, replaces the affected `31.1-android` selection with `33.7.2-android`; the [updated audit](P0/guava-remediation-audit.json) returns zero matches for 121 distinct resolved coordinates. Both PR workflows passed before merge; [compatibility evidence](P0/guava-remediation.md) retains its original candidate snapshot. The owner's reader findings were subsequently resolved in merged PR #4, with 47 JVM regressions and explicit authorized-history limits. Device acceptance and signing-key backup still require actual results; V1 implementation remains unstarted.

**Baseline entry review (2026-11-01 KST):** the post-merge [security/dependency review](P0/final-security-review.md) and [resolved-coordinate audit](P0/dependency-audit.json) cover historical unpatched source `6392eb1`, where three Guava advisories were identified. Merged PR #3 addresses that coordinate finding in its checked graph; device results remain separate. [Device acceptance](P0/device-acceptance.md): D00 partial, D01 user/UI PASS, D02 PASS (UI plus actual CSV verified), D03 NOT OBSERVED (revocation removed continuation), D05 PASS (offline failure/online recovery and remote same-file checks verified), D06 partial overall (home-departure/retry and remote checks passed; screen-lock unobserved), D07 user/UI PASS for reset and manual recovery, D04/D08–D09 and key backup pending. No full P0 exit gate or V1 implementation is completed by these observations.

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


## P0 merge record — 2026-11-01 (KST)
- [PR #1](https://github.com/nyeonu/health-app/pull/1) has `state=closed`, `merged=true`, `draft=false`, target `master`, merge commit `9a787901d0e8066b311d75e112c82a1cd84f3fa6`.
- Accepted scope: foreground manual Health Connect read, versioned CSV export, authorized private Google Drive upload/update, connected ChatGPT file-body reading, Android emulator regression tests.
- Post-merge follow-up, **not considered passed**: on-device permission denial/revocation during an active read, airplane-mode/real network loss, in-flight upload cancellation and recovery, signing-key private backup, and production dependency/security review.
- V1 WorkManager background scheduling and SAF destination are not implemented and remain separate planned work.

## Deferred performance follow-up

The user requests later investigation of slow sync/upload, including measured bottleneck analysis before considering bounded parallelism or other changes. [Backlog and evidence limits](backlog/sync-performance.md). One UI attempt-to-success interval is about 11 seconds and includes authorization/user wait; it is not an upload-only benchmark. No optimization or instrumentation is started during P0 acceptance.
