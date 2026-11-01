# P0 readiness — merged implementation and residual acceptance

Status (2026-11-01 KST): **PR #1, #3 and #4 merged into `master`.** PR #4 merge commit `d33edae688421ea41d00b22353e960c006f021a5` contains the owner-approved reader correction at `fd48be0`; PR #3 contains the Guava advisory correction. Earlier core live data-path evidence is verified; current fixed-signature Galaxy acceptance, source completeness/exception cases and owner key-backup confirmation remain open. Code merge/review is not production or P0 exit approval.

The user reports the fixed-signature APK installs, authorizes and completes foreground synchronization. In the authorized ChatGPT Google Drive connection on 2026-10-31, the assistant additionally **retrieved both private CSV bodies and their permission metadata**. The files include a valid daily-summary row and Samsung Health-origin heart-rate samples; unpopulated fields remain blank, not zero. This is observed remote content evidence, not merely the app's success message. Five allowed categories do not imply five populated datasets. No screenshot, health measurements, personal account information, file IDs, private timestamps, key material or credentials are committed here.

## Current evidence

**New device report after the master APK handoff:** the user reports installation/launch and supplied an app screen showing successful foreground manual sync, five granted categories and three confirmed uploaded files. This is partial D00 user/UI evidence, not independent verification of the installed binary, new CSV bodies, overnight source bounds or repeated same-file updates. Do not infer five populated types from five grants or identify the three datasets from the file count. A subsequent guided D01 zero-grant test is user/UI PASS: 0/5, PERMISSION_REQUIRED, no attempt, 0 uploads and no success. D02 steps-only UI behavior has since passed, with actual CSV contents still unverified; D03 is NOT OBSERVED for the isolated read-time check: user reports refresh and permission-request-only UI after revocation, with no upload continuation. No process-kill cause is inferred. D04–D09 and key backup remain pending. No screenshot/private timestamps are committed.

**PR #4 accepted and merged:** the owner [re-reviewed `fd48be0` and accepted the correction](https://github.com/nyeonu/health-app/pull/4#issuecomment-6551010783), superseding REQUEST CHANGES on `192ab46`. The [per-page correction](reader-sample-budget-fix.md) has 47 JVM + 3 Android 15 instrumented tests, zero failures/errors/skips in downloaded current-head XML; [JVM/APK/lint](https://github.com/nyeonu/health-app/actions/runs/41252675747) and [emulator](https://github.com/nyeonu/health-app/actions/runs/41252675744) CI succeeded. The earlier same-session approval remains superseded as history; the later owner re-review is the merge basis. Device gates remain open.

**Historical baseline:** [PR #3](https://github.com/nyeonu/health-app/pull/3) merged as `178ee3f`; the [initial interval/pagination evidence](reader-boundary-fix.md) had 43 JVM tests and was subsequently extended to the 47-test corrected head above. Earlier counts/holds in historical reports do not describe current master. No new physical-device result is inferred from the merge.

| Area | Evidence / status |
| --- | --- |
| Core, reader and transport verification | Current reviewed head: 27 core + 20 app JVM tests (14 reader / 6 local TLS), APK/lint pass; 3 Android 15 emulator tests passed, no failures/errors/skips. See linked PR #4 runs and [automated test matrix](automated-testing.md). |
| Hosted source and fixed signing | Master `d33edae`, [trusted push run 41256103556](https://github.com/nyeonu/health-app/actions/runs/41256103556) succeeded; [APK artifact 13063750974](https://github.com/nyeonu/health-app/actions/runs/41256103556/artifacts/13063750974) digest, package and actual APK certificate verified against signing.txt and the earlier known-working fixed-certificate artifact 13036249453. [Install handoff](device-acceptance.md). Installation/launch and UI sync success are now user-reported after that handoff; installed-binary/account/provider edge cases remain independently unverified. |
| New package/OAuth/foreground read/upload | User-reported success on fixed-signature APK, two confirmed files; first migration is no longer merely pending |
| Review comments | PATCH transport, identity migration, Guava, interval queries, empty terminal tokens and per-page sample budget addressed. Owner accepted PR #4 at `fd48be0`; current priority is the [Galaxy checklist](device-acceptance.md), not V1 implementation. |
| Actual CSV/date/source contents | **Verified** by authorized Drive content read: expected v0.1 headers, local report day, valid rows, Samsung Health source metadata, timestamps and intentionally blank missing fields. **The user also provided a Samsung Health screen for the same local date and its daily step count exactly matched the exported Drive CSV.** No real values stored in Git |
| Private Drive sharing and same-day updates | **Observed** via Drive metadata: app folder and both CSV files are non-shared/owner-only, each is unique within its dataset folder; each file retains the same ID with **two confirmed Drive revisions**. Comparison confirms the daily-summary values are unchanged apart from the export-generation timestamp and the heart-rate CSV body remains unchanged. This confirms repeated same-target updates, not concurrent-write race safety |
| Permissions and failure UI | D01 zero-grant refusal passed on Galaxy by user/UI evidence: 0/5, PERMISSION_REQUIRED, 0 uploads/no success. Core and emulator tests remain separate evidence. D02 partial-grant UI passed with CSV verification pending; mid-read revocation, real offline Drive attempts and in-flight interruption remain pending. |
| Steps-only partial grant (D02) | User/UI shows 1/5, STEPS-only read and success coverage, sync success and one confirmed uploaded file. UI behavior passed; D02 remains partial until actual new CSV fields/blank denied values are checked. Existing files from earlier wider grants may remain. D03 is NOT OBSERVED after continuation loss; D04–D09 and backup are pending. |
| ChatGPT actual file-content access | **Verified** after connector reconnection: both raw CSV bodies fetched and parsed via the authenticated Google Drive connector. Content verified, not only names/metadata. No data disclosed in public PR |
| Key backup | User-controlled; not confirmed |

The previous independent source review covers source `60ccbb5` and earlier. Subsequent root, diagnostics and signing changes have their own reports. The repository owner merged PR #1 after reviewing the evidence. This document preserves unresolved verification as post-merge risk, not a claim of a new independent security review or completion of every device scenario.

## Post-merge review follow-up (2026-11-01 KST)

**Subsequent dependency candidate:** [Guava remediation](guava-remediation.md) upgrades the existing transitive library to `33.7.2-android` and its [fresh 121-coordinate audit](guava-remediation-audit.json) returns zero OSV matches. This is candidate-branch evidence until merged; it does not change the historical audit below or close any physical-device gate. Use the report for exact compatibility results and limits.

A fresh [security/dependency review](final-security-review.md) now covers master source `6392eb1`, including the subsequent runtime, signing and CI changes. The audit queried 117 release runtime / 118 debug runtime / 123 JVM-test runtime coordinates (123 distinct); the same three Guava advisories remain, including HIGH native-deserialization memory exhaustion. No direct app deserialization path was found, but full transitive unreachability is not proven and no patch was applied. The review therefore does not approve production readiness. [Exact coordinate/advisory evidence](dependency-audit.json).

The [residual Galaxy checklist](device-acceptance.md) separates denial/partial grants, pre-read and mid-read revocation, actual offline Drive attempts, in-flight departure, process restart, source coverage and Google authorization withdrawal. Every new device result and owner-controlled key backup remains pending. Settings/quick-settings departure can cancel a foreground job; it must not be misreported as isolated revocation/network-failure proof. V1 implementation remains subject to P0 exit gates.


## Verified remote integration evidence (2026-10-31)
- Authenticated connected Google Drive listed one app root and two dataset folders with one CSV each; no same-named duplicates were observed in those target folders.
- Read file bodies (not just search result metadata), checked schema headers, row structure, timestamp format, source metadata, and missing-cell semantics.
- Metadata shows owner-only permissions, `shared=false`, no shared-drive location, and two saved revisions for each existing file ID. Previous/current content comparison shows only the daily-generation timestamp changed; the detailed sample CSV is unchanged.
- No live health values, personal account names, IDs, or private folder URLs are stored in this public repository.
- Product scope remains P0 foreground manual sync; automatic scheduled sync, SAF storage and MCP are later phases.

## Smallest next device check

1. The Samsung Health daily step count was **cross-checked and matched exactly** against the uploaded CSV for the same date. This validates the step count for that date, not all possible upstream records. Verify the intended absence/availability of weight, sleep and exercise records separately.
2. Run missing device edge cases: permission denial, partial/zero grants, revocation, offline behavior, cancel/background and process restart. Do not expose real measurements in reports.
3. Previous/current Drive revisions have been compared; race conditions and partial-failure recovery remain separate tests using synthetic data.
4. Review still-open dependency advisories and refresh the code/security review as post-merge hardening work. Keep P0 scope; SAF and WorkManager remain V1.

Historical review initially requested Draft/no merge pending real device/Drive/ChatGPT tests: https://github.com/nyeonu/health-app/pull/1#pullrequestreview-5720488649 . Private Drive content, privacy, same-file revisions and source steps were subsequently verified, and the repository owner has merged the PR. Remaining device exception and security checks remain recorded as post-merge work. Roadmap: [implementation plan](../implementation-plan.md).


## Merge event
- [PR #1](https://github.com/nyeonu/health-app/pull/1) merged 2026-11-01 00:45:43 KST; GitHub reports `draft=false`.
- The previous Draft blocker is no longer an open workflow status. No further attempt to mark the merged PR ready for review is required or appropriate.
- Acceptance restrictions remaining above are follow-up test gaps, not blockers preventing an already-completed merge.
