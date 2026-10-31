# V1 storage destinations — design and Galaxy validation plan

Status: **Design only; no SAF destination, persisted URI state, storage-port refactor or WorkManager implementation exists in P0. All device/provider results below are PENDING.** SAF is not a P0 merge/exit gate. This addresses [PR1 comment 6537976514](https://github.com/nyeonu/health-app/pull/1#issuecomment-6537976514).

## Coexistence and consent

P0 retains its foreground-only `HealthReader -> Normalizer -> CsvExporter -> DriveUploader` path, app-created private Drive namespace and `drive.file` OAuth scope. Do not replace that working architecture while exploring SAF. V1 proposes a shared export pipeline:

```text
HealthReader -> Normalizer -> CsvExporter -> CsvFile
                                               |
                                  StorageDestination.upsert(CsvFile)
                                               |
                                  +------------+------------+
                                  |                         |
                          DriveApiDestination         SafDestination
                          Google Drive API        selected local/provider tree
```

`StorageDestination`, `DriveApiDestination` and `SafDestination` are proposed interfaces/classes, not implemented components. The existing `CsvFile(relativePath: String, content: String)` DTO remains unchanged: `relativePath` identifies the deterministic dataset/date target, `schema_version` belongs to the CSV payload, and destinations encode the `content` string as UTF-8 at the destination boundary under the existing [CSV contract](../data-contract.md). This design proposes no new `CsvFile` fields or DTO refactor. Both destinations consume the same normalized export; a storage choice must not change measurements, quoting, time windows or missing-value semantics.

V1 selection offers **SAF local/provider folder** or **Drive API**. A later, separately implemented **both** mode selects two destinations explicitly; it does not silently copy to another location. App-private staging, if needed, is distinct from a user-visible SAF export and needs bounded retention/deletion. Do not retain extra health-data copies by default.

SAF itself needs **no app Google OAuth client or Google Cloud project setup**. It requires explicit user consent through Android's system picker. A cloud document provider can independently require its own installed app, signed-in account, network and consent; SAF does not bypass those requirements. Existing Drive API export still needs app OAuth/Cloud configuration. Health Connect permission is separate in every mode.

A user-selected local folder is not automatically private: it can be visible to file managers, backups or other authorized apps. Provider folders can be shared or synced elsewhere. Explain the location and sharing implications before health export, require an explicitly authorized private destination, and do not claim SAF proves a cloud folder's ownership/sharing restrictions. If privacy cannot be established, block export to that target. Never add sharing permissions automatically. ChatGPT access to a SAF/local/Drive export remains a separate, unverified authorization/content-access path.

## SAF selection and durable access

- Use `ACTION_OPEN_DOCUMENT_TREE` for folder access (API 21+), not a single-file chooser as evidence of folder support. SAF document access dates to API 19; these are documented Android APIs, not preview-only Android 16 features. API availability does not establish provider capability. [1][2]
- Request read/write and persistable access; intersect the returned intent flags with `FLAG_GRANT_READ_URI_PERMISSION | FLAG_GRANT_WRITE_URI_PERMISSION` and pass only actually granted flags to `ContentResolver.takePersistableUriPermission`. Require the needed permissions; handle cancelled selection, missing URI, denied persistence and read-only grants explicitly. Do not fabricate grant flags. [1][3]
- Persist the tree URI, destination configuration and file mapping only in app-private state. On process restart/reboot, restore the state, inspect `persistedUriPermissions` and probe actual access. Persisting the string alone does not retain permission. Re-consent is needed after access loss; a persisted grant does not survive every moved/deleted document. [1]
- Treat `content:` URIs as opaque capabilities. Use `ContentResolver`/`DocumentsContract` APIs, never convert them to filesystem paths. Do not place raw URIs, document/account/device identifiers, real CSVs or measurements in logs, screenshots, repository files or issue evidence.
- On Android 11+, tree selection cannot grant internal-storage root, reliable SD-card volume roots or the `Download` directory; `Android/data` and `Android/obb` are restricted too. Test an allowed dedicated subfolder rather than treating these OS restrictions as an app defect. [1]
- Every provider capability starts **UNSUPPORTED (unverified; not enabled)** until that device/provider combination passes the relevant probes: tree selection, child enumeration/create, write/truncate/readback, persisted grants, restart/reboot, offline and background behavior. Google Drive appearing for single-file selection does not establish Drive tree support. No provider, including Drive, has been tested here. Record a tested limitation separately from this conservative default.
- Inspect document flags such as `FLAG_DIR_SUPPORTS_CREATE` and `FLAG_SUPPORTS_WRITE`; they inform capability checks but do not replace real operation/error tests. If Drive folder selection is proven unsupported, retain local SAF plus the existing Drive API as the available coexistence design; do not claim that outcome before testing. [4]

## Deterministic create/update and recovery

`ACTION_CREATE_DOCUMENT` is a save-as flow and **cannot overwrite an existing file**; Android can append a numeric suffix. It is not the upsert algorithm. [1]

1. Key each export by destination, `Asia/Seoul` local date and dataset. Keep the intended target tree and confirmed document URI in app-private state. Resolve the saved document URI or enumerate the selected tree for the deterministic target; verify it belongs to the authorized target and matches the intended file.
2. For a new target, establish that no matching file exists before creating one via the provider's supported document API. For an existing target, update that document URI. Reject duplicate or ambiguous matches; do not pick an arbitrary file, create a numbered replacement, or overwrite an unrelated document. A stale, moved or deleted target blocks that item for explicit recovery/reselection; do not silently recreate it elsewhere.
3. Use `ContentResolver` with a supported truncating write mode (for example `wt` or `rwt` where supported), close resources, and verify the final contents/length by readback with synthetic fixtures during provider validation. Mode `w` alone may or may not truncate depending on the provider. Probe a shorter second payload to detect trailing old bytes. Unsupported truncation/update fails explicitly; do not append or report a successful upsert. [3]
4. A crash, cancellation or provider disconnect during creation/write may leave a partial or indeterminate file. Keep it pending, reconcile the existing document before retry, and do not blindly create again. SAF has no assumed cross-file atomic transaction or guaranteed atomic replacement. A closed stream/readback establishes only the tested provider-level confirmation, not independently verified cloud-server durability or ChatGPT visibility.
5. Persist only confirmed per-file results. Multiple datasets and destinations are not one transaction: retain healthy confirmed results when another file or sink fails. A provider failure must not clear Drive API success, nor vice versa. Re-exporting changed source data creates a new export revision; each intended sink must confirm that revision before it is current.

## Per-destination outcomes and both-mode semantics

V1 durable state is keyed by `(localDate, destination)` with per-dataset/export-revision confirmation. Record the intended destination set for each attempt, coverage dates, `scheduledAt`, `startedAt`, last attempt/success, pending files, fixed failure category and retry state. Store URI mappings privately, never in diagnostic text.

| State | Meaning and retry behavior |
| --- | --- |
| PENDING / BLOCKED | Not yet confirmed; constraints, unavailable grant/provider or indeterminate write cannot advance coverage. Re-consent/configuration may be required. |
| FAILED | Attempt failed; retain prior confirmed coverage and describe the affected date/sink. Retry transient failure with bounded backoff; permanent denial/unsupported capability requires user action. |
| COMPLETE | All intended files for that sink/date/revision are confirmed. An empty authorized source result remains explicit EMPTY, not invented export success or zero measurements. |

Show independent sink status and per-sink covered-through dates. In later both mode, overall full success requires **all intended destinations** to confirm the same date/export revision; Drive COMPLETE + SAF FAILED is partial, not full success. Retry **only the failed or pending sink/files**, keeping the healthy result. If the snapshot itself changes (for example late source records), that is a fresh revision to export to each sink, not a retry of already-confirmed bytes. Removing a destination requires explicit user choice and must not retroactively relabel the earlier partial attempt as fully successful.

Example using synthetic data only: for date D, Drive confirms revision R and SAF loses its grant. Keep Drive's R confirmation, keep SAF D pending with permission-required status, and leave overall D incomplete. After re-consent and target reconciliation, retry SAF R only. No duplicate Drive upload or false global `lastSuccessfulSyncDay` advancement. Global coverage is the intersection of the intended sinks' confirmed coverage, with gaps shown explicitly; successful worker execution alone cannot establish complete data coverage.

## Galaxy S23 Ultra PoC — seven requested scenarios

Execution is pending on the actual Galaxy S23 Ultra (Android 16); there is no implemented SAF path to test yet. This environment has no emulator binary, installed system image or KVM, and supplies no device/provider evidence. Do not interpret that as proof software emulation is impossible. Use synthetic CSV fixtures and private test locations; do not publish identifiers or health/account data.

Result schema for each scenario and each provider/configuration: **actual = PENDING** until run; then **PASS / FAIL / UNSUPPORTED**, with sanitized expected-versus-observed behavior, date, Android/provider version, and local-only evidence reference. PASS requires every listed assertion. FAIL means a claimed supported operation violated the contract. UNSUPPORTED means a required capability is demonstrably unavailable, not that an unrun test passed. Unverified capabilities remain disabled under the default above. Keep failure/unsupported subcases visible rather than averaging a scenario into success.

| # | Scenario and steps | Expected result | Actual result |
| --- | --- | --- | --- |
| 1 | **Local folder selection/create:** launch `ACTION_OPEN_DOCUMENT_TREE`, select an allowed dedicated local folder, consent, export a synthetic CSV; also cancel and try read-only/restricted selections. | Granted folder creates exactly the intended valid CSV; cancellation/insufficient access shows pending/blocked without success. Android 11 restricted roots are respected. Local visibility/privacy caveat is acknowledged. | **PENDING** |
| 2 | **Google Drive provider folder support:** inspect the system tree picker for Drive and attempt to select a folder; distinguish that from Drive appearing in a single-file picker. Probe enumerate/create/write only after consent. | Enable Drive SAF only if actual folder/tree and required operations work. Otherwise record tested UNSUPPORTED with reason, offer local SAF and existing Drive API choice; no inference from provider name or single-file visibility. | **PENDING** |
| 3 | **Persisted grant, app restart and reboot:** save only granted flags with `takePersistableUriPermission`, stop/reopen the app, then reboot; restore private state and read/write the same synthetic target. | Required persisted permissions and real access survive each tested restart. Missing/revoked grants block export and request re-consent without assuming URI persistence equals permission. | **PENDING** |
| 4 | **Same-date export twice:** create a longer fixture, retain/find its document URI, then export a shorter changed fixture for the same date/dataset; inspect identity, count and exact bytes. Also introduce duplicate names. | Update the existing document, one target only, no suffix/duplicate/trailing bytes. `ACTION_CREATE_DOCUMENT` is never treated as overwrite. Duplicate/ambiguous targets block safely. | **PENDING** |
| 5 | **Failure states:** independently move/delete the selected folder/file, revoke permission, disconnect network for each cloud provider, and interrupt/crash/unavailable provider during create/write. | Explicit pending/failed/permission-required or tested unsupported status, no coverage advancement or silent fallback. Reconcile indeterminate writes before retry; recover only after valid target/grant. Local offline behavior is measured separately from cloud provider cache behavior. | **PENDING** |
| 6 | **V1 WorkManager/background and catch-up:** after future implementation, access restored SAF URIs in a worker; test Doze, low battery, no network, reboot, force-stop/reopen, late source arrival and at least one missed local date. | No exact execution-time promise; denied background Health Connect read or URI access blocks visibly. Next permitted worker/app-open/manual trigger detects gaps, backfills bounded dates and refreshes recent two days without duplicates; confirmation advances only the relevant sink coverage. Capture actual battery use, no assumed percentage. | **PENDING** |
| 7 | **Later both-mode independent results/retry:** after future implementation, select Drive API + SAF; make each sink fail in turn, cancel mid-write, retry that sink, then refresh a changed snapshot. | Healthy sink/date/revision stays confirmed; only failed/pending sink/files retry after reconciliation. Full success requires both intended sinks; partial/stale coverage is visible. A changed revision is delivered to both rather than being mislabeled a retry. | **PENDING** |

## V1 background constraints and completion boundary

This is a test/design plan, not a new scheduler. Retain the existing [V1 scheduling constraints](../implementation-plan.md#battery-optimization-and-timing-semantics-approved-for-v1): WorkManager/`CoroutineWorker`, battery-aware/network constraints for automatic work, no polling loop, exact alarm or always-on service. Constraints and provider access must be validated per destination; network eligibility never proves a local or cloud operation completed. Background Health Connect reads need permission/feature checks independently of SAF grants.

On each allowed worker execution, app open and manual request, restore grants/state, find missed `Asia/Seoul` dates, reconcile incomplete writes and run bounded catch-up. Refresh recent two days for late Samsung Health records, tracking export revision per sink. Do not mark stale data fresh because a worker ran. Low battery, Doze, force-stop, reboot and provider/network failures can defer work beyond a calendar day. Only actual Galaxy tests can establish usable provider/background behavior. These checks gate future V1 capability claims, **not P0 merge**.

## Documentation verification

Criteria-based check: **“SAF design covers all seven requested device scenarios with pending results and independent sink status.”** Inspect the seven numbered rows, each expected result and PENDING actual; confirm date/destination/revision state, independent retries, all-intended-sinks completion, unsupported-until-proven capability defaults, privacy/consent, no P0 implementation, and V1-only WorkManager tests. Run `git diff --check`. Passing this document check is not a device test result.

## Official Android sources

Reviewed on 2026-10-31; these API references document Android contracts, not tested provider behavior:

1. [Access documents and other files from shared storage](https://developer.android.com/training/data-storage/shared/documents-files) — tree selection (API 21+), Android 11 restrictions, persistable grants/move-delete caveat, and create-document non-overwrite behavior.
2. [Storage Access Framework and document providers](https://developer.android.com/guide/topics/providers/document-provider) — SAF introduced in API 19 and provider architecture.
3. [ContentResolver](https://developer.android.com/reference/android/content/ContentResolver) — `takePersistableUriPermission`, `persistedUriPermissions`, URI operations and write/truncate modes; provider-dependent mode behavior.
4. [DocumentsContract.Document](https://developer.android.com/reference/android/provider/DocumentsContract.Document) — document capability flags, including write and directory child creation.
