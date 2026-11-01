# P0 live Drive verification after offline recovery

**Historical inspection:** the [later re-upload verification](latest-upload-verification.md) newly observes sleep data and a real earlier-start sleep interval. Earlier statements below about no sleep/cross-day data describe this initial recovery snapshot, not the latest folder state. D02/D05 recovery evidence remains valid for its observed scenario.

The user reported a successful explicit online retry following the recorded offline `UPLOAD_FAILED:DRIVE_APP_FOLDER_LOOKUP:NETWORK` failure and requested actual Drive-content verification. The authenticated Google Drive connector was used read-only to discover the app folder, list dataset children, fetch metadata, retrieve complete raw CSV bodies and inspect the daily file's previous revision. No Drive file, sharing state or revision was modified.

This report stores only contract checks and redacted outcomes. It contains no health measurements, sample counts, account/file/record identifiers, private URLs, CSV bodies, content fingerprints or private timestamps. No screenshot or live CSV was written into the repository.

## Observed remote evidence

| Area | Verified result |
| --- | --- |
| Target organization | One app folder observed, containing `daily`, `heart-rate` and `workouts` dataset folders. Each has exactly one CSV for the current reporting day. Older-day files remain; no same-named duplicate was observed within these dataset folders. |
| Effective sharing | App root, all three dataset folders and the three current-day CSVs report `shared=false`, no shared-drive location and a single user/owner permission. No public, domain, reader or writer sharing permission was returned. |
| Complete file content | Raw CSV bodies were fetched through authenticated file references and parsed as UTF-8 CSV; received byte counts matched provider file-size metadata. This was not filename-only inspection or a truncated readable-text preview. All three schemas match v0.1, with valid column widths and actual data rows. |
| Daily row / partial grants | Exactly one row for the requested Asia/Seoul reporting day. Steps is present and numeric; weight, resting HR, sleep and exercise summary fields are blank, not fabricated zeros. This complements the user's D02 screenshot showing STEPS-only grants/read coverage and one uploaded file. |
| Heart rate | Parent-record identity and source provenance are populated; sample timestamps carry offsets/UTC and all lie within the reporting day's half-open window. Numeric samples parse correctly. This does not validate a resting-heart-rate estimate or source completeness. |
| Workouts | Session identity/provenance and numeric exercise types are populated; sessions overlap the requested day. Exported durations match the elapsed time between the full CSV start/end bounds. No session crossing the reporting-day boundary is present in this CSV, so the real-device overnight regression remains unobserved. Original source-side bounds were not independently compared. |
| No unauthorized detail refresh observed | Heart-rate/workout file modification metadata precedes both the steps-only attempt and the offline attempt. These existing files were retained from earlier wider-grant exports and were not refreshed by the limited-grant recovery. Old remote files remaining after permission withdrawal is documented behavior, not proof of a new unauthorized read. |
| Same target updated / recovery | The daily file has three saved revisions under the same file identity. Its previous revision precedes the offline failure; the current generated/modified values follow that failure. Previous/current daily values are identical except for the export-generation timestamp. Combined with the user's retry-success report, this confirms recovery updating the existing day file without a duplicate in the observed folder. |

## Acceptance outcome

- **D02 PASS for the observed steps-only scenario:** user/UI scope evidence plus actual daily CSV confirms steps-only content and blank denied/unimplemented fields. Existing detail files remain unchanged from before the partial-grant attempt.
- **D05 PASS for the observed offline/retry scenario:** offline error/zero uploads/no new success was observed in the supplied UI; the user then reported online retry success, and authenticated content/revision/metadata checks confirm a fresh same-target update with private sharing and no duplicate.
- **D00 remains PARTIAL:** current remote formats/privacy/update behavior is verified, but real overnight source-bound comparison and installed-binary identity were not independently observed. No new claim of full P0 acceptance is made.
- **D08 remains pending for source completeness:** workout export is populated, but no sleep folder/CSV is observed. That does not prove an absence of sleep in Samsung Health or Health Connect. Weight is intentionally blank in this restricted-grant export and cannot establish source availability either.
- D03's isolated read-time recheck remains NOT OBSERVED; D04, D06–D09 and private key-backup confirmation remain open.

Revision evidence establishes sequential same-file updates in the observed target, not cross-instance race safety, server rollback on cancellation or completeness across every source/provider/date. Owner-only metadata is the observed permission state at read time, not a guarantee against future sharing changes. No real health values were copied into tests, logs or public reports.

Next guided device case: D06, leave the app while a populated manual sync is visibly active, then return and inspect interruption status before an explicit retry. A job that already completed before departure is NOT OBSERVED for active cancellation. Preserve remote files; interrupted writes are not transactional.
