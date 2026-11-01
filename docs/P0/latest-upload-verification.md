# P0 latest re-upload: sleep and overnight interval verified

The user reported re-uploading and requested continued acceptance work. The authenticated Google Drive connector was used read-only to rediscover the app folder, list the latest reporting-day targets, read effective permission metadata, retrieve complete raw CSV bodies and inspect revision histories. No files or sharing settings were changed.

This report contains only contract outcomes. No health values, sample counts, record/file/account identifiers, private URLs/timestamps, CSV content fingerprints or screenshots are stored here. Complete bodies were parsed in memory, outside the repository; only redacted validation results were retained locally.

## Verified observations

- Four populated datasets are now present for the latest reporting day: **daily, heart-rate, workouts and sleep**. The sleep dataset was not present in the earlier recovery inspection and is newly observed. Each has exactly one target-day file; no same-named duplicate was observed in any of the four dataset folders.
- App root, all four dataset folders and all four target-day files are unshared, have no shared-drive location and return only a user/owner permission. This is effective metadata at inspection time, not a future sharing guarantee.
- Complete CSV response sizes match file-size metadata. All four bodies have expected v0.1 headers, consistent column widths, actual rows and schema versions. This was not filename-only or truncated-preview inspection.
- The daily row matches the file's reporting day and Asia/Seoul zone. Steps is present; weight is blank. Unimplemented resting-HR/sleep/exercise summary fields remain blank even though sleep/workout detail files are populated, as the P0 contract requires. No missing field was fabricated as zero.
- Heart-rate samples have record/source provenance, numeric values and offset/UTC timestamps, all inside the reporting day's half-open interval.
- **A real sleep session starts before the reporting-day midnight and ends within the reporting day.** It is included in the target-day sleep CSV, and its duration agrees with the full exported start/end bounds. This establishes an observed earlier-start interval export, beyond the synthetic reader regression tests. Exact agreement with a separately viewed source-system record has not been asserted.
- Workout sessions have provenance, numeric exercise types, valid overlapping intervals and elapsed durations consistent with full exported bounds. No earlier-start/cross-day workout is observed in the current file.
- Current daily, heart-rate and workout file identities each have multiple saved revisions; sleep currently has its initial revision. Together with unique target filenames, this supports repeated updates within the observed target. No comparison to a private-ID cache from a prior tool session, cross-instance race guarantee or server rollback proof is claimed.

## Acceptance and remaining human-only facts

D00 now has real overnight **sleep CSV** evidence plus current schema/private-target/upsert checks. D08 has actual sleep and workout exports, so the earlier absence of a sleep CSV is superseded. Neither proves every upstream record was exposed by Samsung Health/Health Connect or independently matches all original source bounds. Weight's blank value still requires a simple source-availability answer for the exported day; it is not automatically a defect or an actual zero.

D01, D02, D05 and D07 retain their recorded observed-scenario passes. D06 home-departure/retry passed; screen-lock behavior remains unobserved. D03's isolated prepared-read recheck was not reached because continuation disappeared after revocation, and D04 mid-read permission withdrawal still lacks a suitable real-device timing setup. Existing unit evidence must not be relabelled as those physical-device executions.

The remaining user-operated results are requested together: Google authorization withdrawal/recovery (D09), screen-lock interruption/retry, exported-day Health Connect weight-record presence, and private backup of the existing signing key/password (K01). Report only categorical outcomes; never supply values or key contents. A missing/unexecuted answer stays pending. P0 exit and V1 implementation are not marked complete by this report. The separately requested performance work remains deferred.
