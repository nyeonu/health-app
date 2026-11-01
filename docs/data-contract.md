# Data contract (draft v0.1)

Status: **P0 v0.1 implemented and synthetic JVM-tested; actual Samsung Health records and account integration remain pending.**

## General rules
- UTF-8 CSV, RFC 4180-style quoting, header row required, comma delimiter.
- Stable file prefixes and a schema version included in payload or adjacent manifest.
- Report date = `Asia/Seoul` local date (`YYYY-MM-DD`). Derive from unambiguous instants.
- Event times = ISO-8601/RFC-3339 with offset or UTC `Z`. Never store timezone-naive timestamps.
- Units in field names: `_kg`, `_bpm`, `_min`, `_m`. Durations are in minutes unless specified.
- Missing/not measured = empty cell (nullable). `0` is valid only when an actual zero was observed.
- Do not include names, emails, user IDs, device IDs, account IDs, location traces or authorization tokens.
- Persist source app/provenance metadata when exposed. Distinguish measurements from estimates/derived summaries.
- Avoid creating a false total by adding duplicate records from different providers; use suitable aggregation/provenance policy.

## Initial Google Drive layout
```text
HealthApp/
  daily/YYYY-MM-DD.csv
  workouts/YYYY-MM-DD.csv
  heart-rate/YYYY-MM-DD.csv
  sleep/YYYY-MM-DD.csv
```

P0 exports only populated datasets from granted categories for **yesterday in Asia/Seoul**, using the half-open window `[local midnight, next local midnight)`. A day is 24 hours in this zone. The daily file is present only when steps or weight exists; detail-only records do not invent a daily summary. No populated records means no files and an explicit EMPTY outcome, not upload success.

Only app-created, app-marked (`healthApp=p0-v1`) private folders/files are accepted under `drive.file`. A user opt-in authorizes creation/use of this layout; P0 does not select an arbitrary existing folder. Same-named unmarked folders are outside this app namespace. Shared, non-owned, trashed, shared-drive or ambiguous app-marked targets are rejected. No sharing permissions are added.

## Daily summary CSV (one row per local date)
```csv
schema_version,date,timezone,steps,weight_kg,resting_hr_bpm,sleep_min,exercise_min,generated_at
0.1,2026-10-31,Asia/Seoul,,,,,,2026-10-31T09:00:00+09:00
```

The row above illustrates **missing values**, not real health data. Heart rate `resting_hr_bpm` must be computed using a documented validated method or omitted if unavailable; do not confuse single heart-rate readings with resting HR. P0 intentionally leaves `resting_hr_bpm`, `sleep_min` and `exercise_min` blank: no daily aggregation for these fields has been validated.

`steps` comes only from Health Connect `StepsRecord.COUNT_TOTAL` aggregation, never a blind sum of raw multi-provider records. Provider priority/deduplication behavior must still be checked on the target device. `weight_kg` is the latest authorized in-day measurement; equal instants use ascending source package, record ID and kilograms, selecting the maximum tuple for a stable tie-break. Missing or ungranted values remain blank; actual aggregate zero remains zero.

## Workout sessions CSV
```csv
schema_version,record_id,source_package,start_time,end_time,exercise_type,duration_min
```
`exercise_type` is the decimal Health Connect `ExerciseSessionRecord.exerciseType` integer (the AndroidX constants), preserved without an invented label mapping. Strength-training sets/reps/loads are not assumed to be provided by Health Connect.

## Heart-rate samples CSV
```csv
schema_version,record_id,source_package,sample_time,bpm
```
One source record may contain multiple timestamped samples; preserve linkage and original timing where available.

## Sleep sessions CSV
```csv
schema_version,record_id,source_package,start_time,end_time,duration_min
```
Sleep stages are not exported in P0. Sleep and exercise `duration_min` are elapsed time between the **full original** start/end instants, expressed in minutes; sessions overlapping the day are included without clipping. A session spanning midnight can therefore occur in adjacent date exports. Do not sum these files as daily totals or deduplicate by date alone; retain `(source_package, record_id)` and original bounds.

Heart-rate samples are filtered to the selected day and preserve their parent record identity. P0 exports this bounded recent day on explicit foreground request; it does not implement continuous raw heart-rate collection. To find overlapping intervals that started earlier, raw sleep/exercise/heart-rate lookup scans authorized record starts before day-end under the existing page/sample bounds, then filters the output to this contract. It does not assume a maximum session length or request additional history permission. Large authorized history can cause an explicit limit failure even when the exported day would be small; see the [reader correction and validation limits](P0/reader-boundary-fix.md). Details sort deterministically by event time/bounds, source, record ID and measurement/type as needed. All timestamps serialize as UTC `Z`; the summary identifies the reporting zone.

## Idempotency and update semantics
- Day-scoped file paths are deterministic; re-export replaces the target day's file rather than creating repeated copies.
- Health Connect record IDs are source-local identities; persist enough metadata to reconcile safely.
- P0 resolves the deterministic date file in the marked private folder on every attempt and updates its existing Drive ID; it does not persist a file-ID cache. V1 durable state remains future work.
- An upload must be considered committed only after remote confirmation.
- Future incremental sync requires deletion handling, token-expiry recovery, and changes to past-day records.

## P0 limits and partial outcomes
- Reads are bounded to 100 pages of up to 1,000 records per paginated type and 100,000 inspected heart-rate samples. The heart-rate running total is checked when each page arrives, before requesting another page; only target-day domain samples are retained across pages. A single SDK response is already allocated before this check, so this is not an absolute heap-byte bound. Limit exhaustion fails explicitly; data is not silently truncated. See [per-page budget evidence](P0/reader-sample-budget-fix.md).
- Each encoded CSV must be at most 5,000,000 bytes for the simple upload path. Oversized exports fail.
- Multi-file upload is not transactional: failure/cancellation may leave earlier confirmed files or an indeterminate in-flight remote write. A failed job does not advance last-success state. No automatic POST retry is performed; manually retry and verify remote state.
- Same-instance operations are serialized. Cross-instance concurrent creation and a sharing change racing privacy checks remain limitations; device/account checks must verify actual IDs and privacy.
- OAuth tokens, consent and last-success metadata are process-memory-only; no local health export or credential persistence is implemented. Local forget clears local state, does not revoke system/Google permissions and does not delete remote exports.
