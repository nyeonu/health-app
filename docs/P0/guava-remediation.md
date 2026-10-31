# P0 Guava security remediation

Candidate source: `1768c19` (based on merged PR #2 / master `1d2e70f`). Reviewed 2026-11-01 KST / 2026-10-31 UTC. This report describes the candidate branch; master is not patched until this change is merged.

## Change and scope

The app Gradle dependency block adds one implementation constraint for `com.google.guava:guava:33.7.2-android`, with the security reason recorded beside it. The constraint upgrades the existing Health Connect transitive dependency without introducing a new runtime integration, globally forcing unrelated modules or suppressing advisory checks. Standard Gradle conflict resolution can select newer compatible versions; future dependency changes still require audit. Application source, OAuth, CSV, signing, manifest and P0 foreground behavior are unchanged.

This is a self-contained dependency configuration correction, executed under be-workflow's small-change route and testing policy. It introduces no V1 architecture or feature scope. The earlier P0 implemented spec and completed plan remain historical records.

## Dependency verification

The merged baseline [audit](dependency-audit.json) resolves Guava `31.1-android` and matches LOW `GHSA-5mg8-w23w-74h3`, MODERATE `GHSA-7g45-4rm6-3mm3` and HIGH `GHSA-xxph-c9ww-hj94`. The prior review's concrete correction was to select a compatible patched Android version; merely upgrading to 32.x would leave the HIGH advisory.

Gradle now resolves **Guava `33.7.2-android` in all three checked configurations**, confirmed from the actual component graph and release `dependencyInsight`. The OSV `querybatch` refresh covers **115 release runtime / 116 debug runtime / 121 debug JVM-test runtime coordinates; 121 distinct queries, zero query errors, zero matches**. [Exact patched coordinates and query timestamp](guava-remediation-audit.json). The baseline's three coordinate matches become zero on the changed graph; this is the regression discriminator, not a new unit test that merely asserts a hardcoded version string.

The graph also updates Guava's transitive failureaccess (1.0.1 → 1.0.3), error-prone annotations (2.11.0 → 2.50.0), J2ObjC annotations (1.3 → 3.1) and JSpecify (1.0.0 → 1.0.1), and removes the old jsr305/checker-qual nodes. Other resolved coordinates are unchanged. All changed coordinates were included in the refresh. Maven Central publishes the Android artifact and its parent POM declares Apache License 2.0. No license exception or proprietary dependency was added.

The check covers all external Gradle component nodes, including BOMs/platform variants, rather than only artifact files. It excludes build plugins, instrumentation-test-only dependencies, Android OS and dynamically supplied Google/HC implementations. A zero-match OSV snapshot does not prove no undisclosed vulnerability or complete exploit unreachability. The prior bytecode reference scan is not used to dismiss the advisories; the affected library is upgraded instead.

## Compatibility and regression evidence

Full JDK 21.0.13, Gradle 8.13 and Android SDK 36 were used, preserving managed proxy/CA trust. No tests were removed, weakened, skipped or added merely to mirror the dependency setting.

| Check | Observed result |
| --- | --- |
| Actual resolved graph + release `dependencyInsight` | All three configurations select Guava `33.7.2-android`; no `31.1-android` remains in them. |
| Core JVM suites | 27 tests, 0 failures/errors/skips; previously verified unchanged core tasks may be up-to-date. |
| App local-TLS JVM suite | 6 tests, 0 failures/errors/skips, rerun on the changed classpath. Covers create/PATCH, explicit retry, 401, redirects, TLS/error redaction, one-shot 503 and cancellation. |
| Debug APK + lint | `BUILD SUCCESSFUL in 1m 9s`, 59 actionable tasks (15 executed, 44 up-to-date), including graph/insight tasks. Lint 0 errors, 14 warnings. |
| Release APK packaging | `:app:assembleRelease` BUILD SUCCESSFUL in 1m 43s, 51 actionable tasks (48 executed, 3 up-to-date). Unsigned release packaging and release vital lint completed; not a production-signed or installed APK. |
| Actual Galaxy / fixed-key account flow | Not run. D01–D09 and owner backup remain pending. |

Commands from `android/`:

```bash
./gradlew :app:dependencyInsight --dependency com.google.guava:guava --configuration releaseRuntimeClasspath
./gradlew --no-daemon :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
./gradlew --no-daemon :app:assembleRelease
```

## Review and remaining gates

**Code/security verdict: APPROVE for this bounded dependency correction.** Critical/Important findings in the correction: none identified. The Gradle constraint is limited to the app implementation dependency graph, keeps the Android artifact, passes existing transport regressions and removes the known matches on the resolved runtime graph. The previous [review](final-security-review.md) remains the historical assessment of unpatched source `6392eb1`; its dependency finding is addressed only by this candidate and its new evidence. This is a same-session review, not a new independent agent review or production sign-off.

Production and V1 entry remain open until the [physical-device acceptance](device-acceptance.md) and owner-controlled key backup are recorded. After merging, use the fixed-certificate master CI APK for a real HC read / Google authorization / private Drive update smoke test and applicable D01–D09 cases; a locally signed APK is not a verified replacement for the working OAuth certificate. No owner signing key or health/account data was used, no APK was distributed, and no device gate is closed by build success. The release packaging check is not a signed production release or a device execution test.

CI checks on the PR are separate evidence. No V1 SAF/WorkManager implementation is included. Continue with P0 device evidence, then V1's approved spec/plan workflow.
