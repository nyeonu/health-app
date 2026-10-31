# Data contract (draft v0.1)

Status: **Provisional; validate against Health Connect APIs and actual Samsung Health records during P0.**

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

P0 may export only supported populated datasets. No need to generate empty files pretending they contain records.

## Daily summary CSV (one row per local date)
```csv
schema_version,date,timezone,steps,weight_kg,resting_hr_bpm,sleep_min,exercise_min,generated_at
0.1,2026-10-31,Asia/Seoul,,,,,,2026-10-31T09:00:00+09:00
```

The row above illustrates **missing values**, not real health data. Heart rate `resting_hr_bpm` must be computed using a documented validated method or omitted if unavailable; do not confuse single heart-rate readings with resting HR. Daily weight aggregation policy (e.g. last observed measurement) must be documented.

## Workout sessions CSV
```csv
schema_version,record_id,source_package,start_time,end_time,exercise_type,duration_min
```
The exercise_type representation must use a stable, documented Android source mapping. Strength-training sets/reps/loads are not assumed to be provided by Health Connect.

## Heart-rate samples CSV
```csv
schema_version,record_id,source_package,sample_time,bpm
```
One source record may contain multiple timestamped samples; preserve linkage and original timing where available.

## Sleep sessions CSV
```csv
schema_version,record_id,source_package,start_time,end_time,duration_min
```
Sleep stages may be added in a later schema; measure duration using clearly documented semantics.

## Idempotency and update semantics
- Day-scoped file paths are deterministic; re-export replaces the target day's file rather than creating repeated copies.
- Health Connect record IDs are source-local identities; persist enough metadata to reconcile safely.
- Drive file ID should be persisted once uploaded when available; retry must not create duplicates.
- An upload must be considered committed only after remote confirmation.
- Future incremental sync requires deletion handling, token-expiry recovery, and changes to past-day records.
