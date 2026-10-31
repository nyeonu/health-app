# P0 post-merge security and dependency review

Reviewed source: `6392eb114f74e7306e3b2931457f05fb02b9dffb` (`master` fetched 2026-10-31 UTC / 2026-11-01 KST).

**Verdict: REQUEST CHANGES for production readiness.** The source review and dependency audit were performed, but dependency disposition and physical-device acceptance remain open. This does not reverse PR #1's completed merge. No new application exploit was reproduced; this is not a zero-vulnerability or production approval statement. This review did not change application behavior, dependencies, signing configuration or phase scope.

## Critical

None confirmed in the reviewed source. Absence of a finding is limited to the checks below.

## Important

- **Dependency disposition remains open — [app Gradle:72](../../android/app/build.gradle.kts#L72).** `androidx.health.connect:connect-client:1.1.0` brings `com.google.guava:guava:31.1-android` into both release and debug runtime graphs, including through `kotlinx-coroutines-guava:1.10.2`. The fresh OSV query still matches three advisories, including HIGH native deserialization memory exhaustion. Malicious Java serialized input reaching the affected Guava collection methods could cause allocation failure/process termination; no such app input path was found or reproduced. Lack of a direct app call is insufficient to establish complete transitive unreachability. Before production, validate a compatible patched Android Guava selection or supply a documented complete reachability/risk disposition. Maven Central lists `33.7.2-android`, and the HIGH advisory records fixed `33.7.2`; this review did not test or apply that candidate. The previous review deadline **2026-11-30** remains a latest recheck date, not permission to ship before disposition. Owner: repository maintainer.

## Suggestion

- Pin third-party CI actions in `.github/workflows/` to reviewed immutable commit SHAs as a separate maintenance task. Current major-version tags are mutable. No compromised action or credential exposure was observed.

## Dependency evidence

The repository already used OSV Maven coordinate queries. This review repeated that method on **all resolved external component nodes**, including BOMs and platform variants: 117 release runtime coordinates, 118 debug runtime coordinates and 123 debug JVM-test runtime coordinates, 123 distinct coordinates overall. This avoids treating only the smaller artifact-file list as the full graph. [Machine-readable audit](dependency-audit.json) records exact coordinates, query time, advisory IDs and database modification times; it contains no private measurements or credentials.

| Advisory | Database severity | Prerequisite / remediation evidence |
| --- | --- | --- |
| [GHSA-5mg8-w23w-74h3](https://osv.dev/vulnerability/GHSA-5mg8-w23w-74h3) | LOW | Guava `Files.createTempDir` plus another principal's temporary-directory access; OSV fixed `32.0.0-android`. No direct app use found. |
| [GHSA-7g45-4rm6-3mm3](https://osv.dev/vulnerability/GHSA-7g45-4rm6-3mm3) | MODERATE | `FileBackedOutputStream` and accessible default temporary directory; OSV fixed `32.0.0-android`; upstream recommends 32.0.1 over 32.0.0 for a Windows regression. No direct app use found. |
| [GHSA-xxph-c9ww-hj94](https://osv.dev/vulnerability/GHSA-xxph-c9ww-hj94) | HIGH | Attacker-controlled native Java collection deserialization; OSV fixed `33.7.2`. Still affects the resolved `31.1-android`. |

No other coordinate in this query returned an OSV match. This excludes build plugins, instrumentation-test-only dependencies, Android OS and Google Play services implementation vulnerabilities, newly undisclosed issues, and a release APK execution test. Database matching is not a call-graph or exploitability proof. Upgrading only to 32.x would not address the current HIGH advisory.

A constant-pool string-reference scan covered **23,453 classes in 76 release runtime dependency artifacts**, including nested JARs in AARs. Besides Guava, ObjectInputStream references were found in Health Connect's shaded protobuf ByteString classes, AndroidX VersionedParcel, Okio ByteString and Kotlin runtime classes. A createTempDir name also appears in Kotlin file/path helpers. These references include serialization hooks and may reject input; they do not themselves establish an attacker-controlled path to vulnerable Guava methods. This broader scan improves on the earlier selected-library scan but is still not complete reachability analysis. Reflection, Android framework/provider behavior and dynamically loaded implementation code remain outside it. No direct app use of ObjectInputStream, Serializable parsing, FileBackedOutputStream or Files.createTempDir was found.

## Reviewed boundaries

| Boundary | Observed controls | Remaining limit |
| --- | --- | --- |
| HC authorization | Five read-only permissions, fresh grants per read, per-type filtering, denied/missing states, explicit bounds and pagination failure | Galaxy mid-read revocation and provider behavior remain pending. |
| OAuth and consent | Google AuthorizationClient; `drive.file` only; separate opt-in and explicit foreground continuation; token only in memory | Real account cancellation/revocation and process-loss cases are pending. No custom OAuth redirect/PKCE flow is introduced. |
| Network | Exact Google HTTPS host/path allowlist, system TLS, redirects refused, 15/30/60-second timeout bounds, 1 MiB response limit, one-shot POST/PATCH, cancellation propagates | Local TLS tests are synthetic; not Google-account or device proof. |
| Drive privacy | App marker, expected parent/MIME, owner-only/unshared/untrashed/non-shared-drive metadata checks; root query and post-create confirmation; no sharing API | Sharing can race metadata checks; same-instance locking does not guarantee cross-instance creation safety. |
| Failure/status | Allowlisted phase/operation/numeric status; provider error bodies and exception causes redacted; no last-success advance on failed/cancelled jobs | Individual remote writes may commit before interruption; no rollback guarantee. |
| Local data and entry points | No intentional local exports/token persistence; backup and cleartext disabled; exported UI activities do not initiate sync from inbound extras | Distributed build is a P0 debug APK, not a hardened production release. |
| Signing/CI | Read-only GitHub job permissions, no signing secrets on PR path, trusted build signing and cleanup, no downloadable fallback key | Private owner backup unconfirmed. Secret-bearing Gradle execution is trusted build infrastructure, not a guarantee against compromised dependencies/actions. |

## Verification

- Reviewed security checklist and the existing P0 specification/acceptance documents; inspected current runtime source, tests, manifest, Gradle configuration, both workflows, signing helper and changes since independent review source `60ccbb5`.
- Tests first: reviewed denial/partial grants, private-target rejection, deterministic upsert, partial failure/cancellation, redirects/TLS, one-shot 503 behavior and diagnostic redaction. Test gaps remain in the actual Android ViewModel/OAuth/provider interaction; the idle emulator lifecycle test cannot prove an active upload cancels on the Galaxy.
- Gitleaks **8.30.1**, `gitleaks git --redact --report-format json --report-path <private-local-report> .`: **61 commits / approximately 436 KB scanned; no leaks found**. This is heuristic detection, not proof that no secret has ever existed. Tracked paths and `.gitignore` were also inspected; no tracked personal export or signing material was identified.
- Automated verification on this source: SDK-free `-PcoreOnly :core:test` passed (27 tests), then `:core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` **BUILD SUCCESSFUL in 2m 40s**, 57 actionable tasks (53 executed, 4 up-to-date). XML: **27 core + 6 local-TLS app tests = 33; 0 failures/errors/skips**. Lint: **0 errors, 14 warnings** (8 GradleDependency, 4 NewerVersionAvailable, DataExtractionRules, MissingApplicationIcon). This fresh result supersedes the older 11-warning count for this session only. Full JDK 21.0.13, Gradle 8.13, SDK 36; local debug signing only, no owner signing secrets used or APK distributed.
- Physical device, account writes, production release execution and emulator instrumentation were not run in this session. Previously recorded live evidence is preserved in [readiness](pr-readiness.md). [Residual device checklist](device-acceptance.md) explicitly remains pending.
- Mandatory security reference freshness check: current OWASP password-cheat-sheet source commit is `87a1faf18a29a5c33d040b000eb3c60330c30b2c`, different from the skill's recorded `a6eb0c6a95f5b056d057d3b6e4e6a3c6b20b8597`. Treat that reference metadata as **needs_review**. No password storage or password parameters were introduced or approved here.

## Reproduction and next action

From `android/`, with full JDK 17+ and SDK 36, preserving configured proxy/CA trust:

```bash
./gradlew --no-daemon :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
./gradlew :app:dependencies --configuration releaseRuntimeClasspath
./gradlew :app:dependencies --configuration debugRuntimeClasspath
./gradlew :app:dependencyInsight --dependency guava --configuration releaseRuntimeClasspath
```

The machine-readable audit lists the resolved coordinates so it can be re-queried via OSV `POST /v1/querybatch` with `{"queries":[{"package":{"ecosystem":"Maven","name":"group:artifact"},"version":"resolved-version"}]}`. Compare the newly resolved graph to that list first; do not assume the graph is locked by this report. Keep provider responses, full scanner reports and build logs local; never mix health exports into audit evidence.

Next: run D01–D09 on the working fixed-certificate APK, confirm key backup privately, and prepare a bounded dependency remediation change with compatibility tests and a refreshed audit. V1's SAF/WorkManager implementation remains gated by actual P0 exit evidence and its own approved spec/plan. The existing P0 plan is `done` and spec `implemented`; they are historical records, not authorization to invent V1 implementation tasks.
