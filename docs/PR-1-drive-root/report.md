# Drive root metadata 404 correction

## Observed failure and scope

The user reported `UPLOAD_FAILED:DRIVE_ROOT_LOOKUP:HTTP_404` after completed Health Connect read coverage, with zero confirmed uploads. This identifies the failing request as `GET /drive/v3/files/root?fields=id`. The root folder is not an app-created file, and `drive.file` grants per-file access. A 404 can also mean the caller lacks read access; it does not prove the folder is absent or that the user misconfigured Cloud. The confirmed implementation defect is requiring direct root metadata before any app-file lookup. Real account success after correction remains pending.

This bounded bug fix uses `testing-policy` under existing P0 authorization; no new product feature, OAuth scope, dependency, secret, signing policy or service is introduced.

## Corrected behavior and security contract

1. Find the app-marked `HealthApp` folder through the server-side `'root' in parents` query, using the documented root alias. Do not fetch root metadata.
2. If absent, create only `HealthApp` with no `parents` field. Drive v3 specifies that this places a newly created file/folder directly in My Drive. Validate its private ownership, exact name/MIME, marker, not-trashed/no-shared-drive fields, and single canonical parent ID.
3. Before any dataset or CSV write, repeat the root-membership query. Require a unique matching private folder, the same ID and the same canonical parent as the creation response. Missing, changed, ambiguous, shared or malformed confirmation fails closed; there is no automatic retry or fallback write.
4. Existing app-folder membership relies on the HTTPS Drive list query predicate, rather than comparing its canonical parent to a root ID obtained through an inaccessible metadata request. Canonical parent structure remains mandatory. Dataset and CSV parents retain exact equality checks. Private ownership, sharing, marker and trash checks are unchanged at all levels.
5. Preserve fixed safe diagnostics, confirmed upload counts, cancellation, foreground consent, one-shot writes and all transport protections. Remove the unused `ROOT_LOOKUP` enum value; failures now identify the real app-folder operation.

The old root-parent equality test depended on a now-removed root-ID request. Its malformed/missing/multiple-parent cases are covered for the top folder, and the wrong-parent rejection is preserved for an actual child dataset. Create confirmation additionally rejects a changed canonical root parent. This is a documented contract correction, not permission to accept arbitrary existing folders: root membership is enforced by the API query and app ownership/marker checks.

## Official API evidence checked 2026-10-31

- [Folder guide](https://developers.google.com/workspace/drive/api/guides/folder): `root` is an alias usable where a file ID is provided.
- [File resource / parents](https://developers.google.com/workspace/drive/api/reference/rest/v3/files): omitted create parents places an item directly in the user's My Drive; a file has one parent.
- [Scope guidance](https://developers.google.com/workspace/drive/api/guides/api-specific-auth): `drive.file` is per-file access; broad Drive permissions are unnecessary for this correction.
- [Error handling](https://developers.google.com/workspace/drive/api/guides/handle-errors): 404 includes inability to read the requested file. The screenshot identifies the request, not a complete server-side root-cause proof.

## Regression and validation

Before production changes, ran:

`python /tmp/run-health-gradle.py -PcoreOnly :core:test --tests '*Existing app tree syncs when direct My Drive root metadata is inaccessible*'`

Result: expected failure, one test failed with `DriveUploadException` on root HTTP 404 (`BUILD FAILED in 16s`). The synthetic boundary returns 404 for root metadata while allowing marked app-tree operations. This reproduces the blocking dependency without any real account or measurements.

After correction: BUILD SUCCESSFUL in 54s; 27 core + 6 app tests (33 total), zero failures/errors/skips. APK assembled, lint passed (0 errors, 11 existing warnings). `git diff --check` passed. Command:

`python /tmp/run-health-gradle.py :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`

| Criterion / test source | Evidence |
| --- | --- |
| 404 on root metadata must not block authorized app files (reported bug) | `Existing app tree syncs when direct My Drive root metadata is inaccessible`: no root request; four requests and same-ID PATCH confirmation |
| New app folder must be verified under My Drive before health writes (success/error/state) | `New app folder must be confirmed in My Drive before any dataset or health file write`: absent, different-ID, changed-parent, shared and HTTP-error confirmations stop after GET/POST/GET |
| Private folder creation and deterministic upsert | Existing private creation/update tests now assert default-My-Drive metadata, post-create confirmation, exact dataset/file parents, markers and CSV content |
| Privacy/ambiguity/error/cancellation | Existing core cases retained, with top-folder parent structural partitions and exact child-parent rejection |
| Real client request sequence/create-update, explicit retry and no write replay | Six existing local TLS tests retained; expected counts updated only for removed root metadata requests |

Self-review: security checklist read before editing; no private response content or exception causes exposed, no new scope/dependency/credential persistence. Root query membership is now the explicit trust boundary. This is self-review, not an independent conformance claim. No dependency graph change or new graph audit. No password defaults are involved; the skill's dated password-reference metadata was not used as refreshed guidance.

Manual gates remain open: install a correctly registered matching-certificate APK, authorize and upload in foreground, inspect private `HealthApp` and same-day update without duplicates, then validate actual ChatGPT content access. Unit/local TLS success is not Google-account verification. PR 1 remains draft.

## Hosted APK handoff

[GitHub Actions run 41178692575](https://github.com/nyeonu/health-app/actions/runs/41178692575), Android source `d6849a2f25c709fb31b951b34c68dfc34ef45fbd`, completed successfully. [APK artifact 13034336262](https://github.com/nyeonu/health-app/actions/runs/41178692575/artifacts/13034336262) was downloaded through the connector, and its ZIP SHA-256 matched GitHub metadata (`9ee0b1a2010b99a329885843fb4b8e98245fa0e5c6d517aba75b8a1a89daf8ed`). `apksigner verify --print-certs` verified the APK signature; `aapt dump badging` confirmed `com.ny.my.health`. Its certificate differs from the previous diagnostic APK. The actual artifact fingerprint is included in the user handoff, not embedded as a source default. A matching Android OAuth client and reinstall/re-consent are required for this freshly signed build. This verifies the distributed binary, not real Google upload success. No signing key was obtained or shared.
