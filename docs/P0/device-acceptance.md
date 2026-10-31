# P0 residual Galaxy acceptance

Status: **PENDING — no physical device is connected to this review environment.**
Prepared 2026-11-01 KST / 2026-10-31 UTC against `6392eb114f74e7306e3b2931457f05fb02b9dffb`.
This is an execution checklist, not evidence that a scenario passed. Existing live success evidence remains in [PR readiness](pr-readiness.md).

## Preparation and reporting

Use the already working, fixed-certificate P0 APK on Galaxy S23 Ultra. Record its source/build reference privately; if it differs from the reviewed source, record that difference. Do not install this session's locally signed test APK over the working app: its certificate is different and its OAuth registration is not verified. Keep existing private exports; do not delete or share them for testing.

Before each case, note the displayed last-success state and privately inspect the target day's Drive files. Restore connectivity and permissions after each case. Report only scenario ID, PASS/FAIL/NOT OBSERVED, grant count, fixed diagnostic code and whether last success advanced. Keep health values, account details, file IDs, exact private times and raw logs out of Git and chat. Privately compare file identities when required; report only same/different/duplicate.

| ID | Procedure | Expected result / evidence to record | Actual result |
| --- | --- | --- | --- |
| D01 | In Health Connect app permissions for Health App, remove all read grants. Return, refresh permissions, request permissions and decline; enable upload opt-in and try Sync now. | 0/5; `PERMISSION_REQUIRED`; no upload continuation or new success. Existing remote files remain. | PENDING |
| D02 | Grant steps only, leave other four categories denied, return and refresh; authorize Google and continue upload. | 1/5; only authorized populated output is written; denied fields stay blank. If no authorized records exist, EMPTY with no new success. Previously exported files for now-denied categories are not automatically erased. | PENDING |
| D03 | With grants present, complete Google authorization but do not press upload continuation. Revoke HC read grants in system settings, return, then press continuation if still offered. | Fresh grants are checked at actual read: permission-required/empty authorized scope, no unauthorized upload and no new success. Authorization alone must not start a read. | PENDING |
| D04 | Attempt revocation during an active read using an appropriate device test setup. Distinguish read timing from upload timing and Activity departure. | Provider exception/cancellation fails safely, no partial snapshot treated as complete, no new success. Opening Settings normally pauses the app and cancels it; that alone proves D06, not isolated mid-read revocation. If timing cannot be established, record NOT OBSERVED and leave this case open. | PENDING |
| D05 | Authorize Google while online, then disable Wi-Fi and mobile data before pressing upload continuation. Return to the app and continue in the foreground. Reconnect and retry explicitly. | Read may complete locally; actual Drive attempt reports a fixed NETWORK diagnostic (or a separately recorded OAuth failure if authorization was not completed). No new success while offline. Retry confirms existing date files without duplicates. Opening quick settings during execution can trigger lifecycle cancellation; do not count that alone as a Drive network failure. | PENDING |
| D06 | Start a populated sync; leave the app while it is visibly active. Return, reauthorize and retry. Repeat once with screen lock if active execution can be observed. | Interrupted message; no success for the interrupted attempt. Some remote writes may already have committed; inspect privately before retry. Same-date retry resolves existing IDs without duplicates. If the job completed before departure, record NOT OBSERVED. | PENDING |
| D07 | After a confirmed success, force-stop and reopen the app (do not clear app data or uninstall). | Memory-only consent, token and last-success state reset; no automatic read/upload. HC/Google system grants and remote files can remain. Re-enable consent, authorize and retry manually. | PENDING |
| D08 | For the exported local day, privately inspect Samsung Health and Health Connect availability for weight, sleep and exercise separately. | Record only available/missing/not written to HC/permission denied and whether CSV presence is consistent. A five-grant UI count does not establish five populated categories. Session overlap semantics follow the data contract. | PENDING |
| D09 | With an existing Google grant, cancel any consent resolution that is actually offered; separately revoke app access in Google account settings and retry authorization. | Cancellation/failure is explicit, no new sync success; a new successful authorization still requires upload continuation. If Google offers no dialog for the cancellation case, record NOT OBSERVED rather than manufacturing one. | PENDING |
| K01 | Confirm private backup of the existing signing key and its password outside Git/chat, without sharing either. | Owner reports backup confirmed or not confirmed. Never regenerate the working key as part of validation. | PENDING — owner controlled |

## Evidence limitations and exit

Partial writes are not transactional. A cancelled/failed request can leave a confirmed earlier file or an unknown in-flight write. Cancellation means no claimed job success; it cannot guarantee server rollback. Do not claim cross-instance race safety from sequential same-file tests. Do not intentionally share real health folders to test privacy rejection; use synthetic fixtures in a separate approved test setup.

For each result record the installed build reference, scenario ID, outcome and redacted observations. Repeat against a changed APK when relevant. Update this table and the roadmap permission/device gates only from those results. Existing 33 JVM tests and three historical emulator tests do not close these physical-device cases. SAF, WorkManager, Doze/reboot scheduling and battery measurements belong to V1 and its separate seven-scenario storage checklist.

Next smallest user-operated check: D01, then restore only steps permission for D02. A failure stays open and becomes a bounded correction task; do not silently advance to V1.
