# PR #8 review follow-up

## Scope and disposition

The owner requested that the review comments be handled, reflected in the implementation, independently verified in another session, and merged if no issues remain. The [review](https://github.com/nyeonu/health-app/pull/8#issuecomment-6586605837) distinguishes development-merge suitability from actual Galaxy/TalkBack acceptance. The latter and remaining P0 unknowns remain pending; neither development merge nor synthetic tests constitute release approval.

## SQLite recovery correction

Previously, confirmed local reset could repair invalid JSON in a valid table but could never recover a physically corrupt database, unsupported database schema or missing table. Source `67ea617af41f8052335081ea95edf209098ee49d` preserves ordinary read/write failures while adding explicit-reset replacement, described in [ADR-005](../decisions/ADR-005-2026-11-02-V1-explicit-database-recovery.md).

The replacement uses a private persistent recovery marker, synchronized original-family snapshots and validated empty candidate, checked atomic rename and directory synchronization, and complete snapshot/stage cleanup before acknowledgment. The marker blocks ordinary admission after interruption; reading never resets the store. Multiple store instances share a path lock. Normal supported reset remains a SQLite DELETE/FULL transaction.

Unknown WAL schemas are probed read-only. A genuine hot rollback journal that cannot be recovered read-only remains an explicit storage error until confirmed reset; it is never reported as empty. Real-file regressions cover corruption, unsupported/missing schema, WAL preservation, genuine hot journals, cancellation/failure, staged/installed interruption, cleanup failures and cross-instance access. Acknowledged reset removes old-history artifacts and permits fresh normal writes/manual admission.

## OS process and settings verification

The existing normal suite is retained. A separate host script launches fixture instrumentation, verifies live synthetic consent/token/continuation, terminates the actual OS process with `adb am force-stop`, verifies the old process is gone, then runs a fresh-process verifier. It exercises eight committed/interrupted/authentication/deletion scenarios and four actual system night-mode/font-scale transitions. It never launches Google consent or invokes upload continuation.

The deliberately killed holds are not successful tests. The script checks successful verifier output beyond the adb exit code, writes sanitized results only after successful assertions, and resets system settings in its exit trap. It cleans only fixtures with its own ownership marker and rejects preexisting app history. Only fixed synthetic PNGs and the sanitized summary are uploaded, never private DBs or broad app output.

Normal suite command:

```bash
cd android
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.notClass=com.ny.my.health.HistoryProcessRecoveryInstrumentedTest
bash ci/verify-history-process-recovery.sh
```

Host-only hold methods require that external orchestration and are excluded from ordinary discovery; they are not skipped or counted as passing within the normal suite.

## Verification

- Root local core/app JVM, APK, lint and instrumentation compilation: BUILD SUCCESSFUL; existing JVM84 tests, no failures/errors/skips.
- Independent session reran JVM tests and reviewed assertions/source without plans or prior review reports; final latest-head Android evidence is pending.
- Current source Android CI: [41523078006](https://github.com/nyeonu/health-app/actions/runs/41523078006), runtime pending.
- Current source JVM/APK/lint CI: [41523077877](https://github.com/nyeonu/health-app/actions/runs/41523077877).
- Fresh OSV Maven audit:121 coordinates,0 query errors,0 known matches. No dependency or permission change.
- Gitleaks feature history and staged correction diff: no leaks found.

Actual runtime counts, independent verdict and merge disposition must be updated from the downloaded raw evidence before claiming completion. Actual Galaxy/account/TalkBack observations remain pending throughout.
