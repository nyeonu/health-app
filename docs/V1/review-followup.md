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

- Verified implementation/test/workflow source: `cc30964a882f7798e71aca7e5c5f73138a1abf72`.
- Local JVM84(core35+app49), APK/lint/instrumentation compilation pass. Latest JVM/APK/lint CI[41529578661](https://github.com/nyeonu/health-app/actions/runs/41529578661) succeeds.
- Android CI[41529578551](https://github.com/nyeonu/health-app/actions/runs/41529578551): downloaded raw XML48testcases,0failures/errors/skips. Host summary complete=true,8 real process-death recovery+4 real system theme/font+8 fixture-cleanup passes. Killed holds count0passes.
- Downloaded PNG60:56gallery+4actual-system captures; root and independent verifier inspected representative renders. System-setting handshake proves initial opposite Activity values before applying target settings.
- Independent fresh session: **APPROVE**, no Critical/Important/Suggestion. Software automated scope PASS; full39 acceptance38PASS/1unverified actual TalkBack/Galaxy criterion. Plan/prior reports were excluded from the verifier.
- Fresh OSV audit121coordinates,0query errors/known matches; dependency fingerprints unchanged. Secret scans of source/feature history/correction diffs found no leaks.

Independent review also found a malformed schema-v1 case that could falsely acknowledge reset. The correction validates canonical table constraints/PK and rejects history triggers, uses DELETE-all plus empty read-back inside supported reset, and recreates malformed schemas through explicit replacement. Actual CHECK/PK/trigger/ignored-CHECK fixtures now pass and repeated writes remain coherent.

Host setup and readiness errors were corrected without skipping assertions: explicitly reinstall exact built test APKs after Gradle teardown, use portable grep, and use parent-cleared per-case outputs with active PID checks instead of stale READY files. Prior partial runs were not counted as full passes. Detailed correction counts remain in plan.md.

The owner authorizes development merge after issue-free independent review. Actual Galaxy/account/TalkBack and P0 unknowns remain pending; the spec stays approved and ADRs Proposed until full acceptance. See [current conformance](conformance-report.md) and [minimal physical follow-up](device-acceptance.md).
