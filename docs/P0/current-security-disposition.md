# P0 current security disposition

Reviewed source: `74781133fb804286ef4c02000fe2ef855cd8b438` (merged PR #5).

**Result:** the historical Guava coordinate finding is addressed on current master. No additional Critical or Important issue was identified in the reviewed code delta. This is a same-session incremental review, not independent production approval or completion of P0 device acceptance.

## Scope and evidence

The [original security review](final-security-review.md) covers `6392eb1`. Since that source, the only Android/CI changes are the Guava app constraint, `HealthConnectReader` and its regression suite. OAuth, consent, transport, Drive privacy checks, signing, manifest and workflows have no intervening changes. Since validated APK source `d33edae`, Android and workflow files are unchanged; PR #5 changed documentation only.

- Resolved all external component nodes, including BOM/platform nodes, from current Gradle configurations: 115 release runtime, 116 debug runtime and 121 debug JVM-test runtime coordinates. The graph is identical to the [Guava remediation graph](guava-remediation-audit.json); all three select `33.7.2-android`.
- Fresh OSV Maven `querybatch`: **121 distinct coordinates, zero query errors, zero advisory matches**. [Current machine-readable audit](current-dependency-audit.json). This excludes build plugins, instrumentation-test-only dependencies, Android/Play/HC implementations and undisclosed vulnerabilities; it is not reachability proof.
- Gitleaks 8.30.1 scanned **81 commits / approximately 632 KB**, with no leaks found. Full scanner output remains local. Heuristic secret detection does not prove absence of all private data.
- Reviewed changed reader and tests: fresh grants are obtained at each read; denied categories are excluded; earlier-start interval lookup remains subject to provider authorization. Exported HR samples are filtered to the half-open requested day, and sessions must overlap it while retaining original bounds. Empty terminal tokens terminate; repeated tokens, oversized pages, excessive pages and sample budgets fail instead of returning truncated success. HR sample budget is checked before requesting the next page.
- The reader's synthetic revocation test raises `SecurityException` after a populated first page and observes no returned snapshot. The unchanged coordinator performs no upload before a complete reader result, maps ordinary read exceptions to `READ_FAILED`, and propagates coroutine cancellation. This is source/test evidence, not an actual Galaxy revocation execution.
- Existing verified corrected-head CI remains **47 JVM + 3 emulator tests**, APK and lint passed; [run links](pr-readiness.md). Tests/build were not rerun for this documentation-only change. Only dependency resolution, OSV and secret scanning were freshly executed here. Initial restricted Gradle startup failed on wildcard-IP discovery; the same offline resolution task succeeded with permitted network/socket access.

OWASP skill reference freshness was checked again: source commit `87a1faf18a29a5c33d040b000eb3c60330c30b2c` differs from the skill's recorded version, so its metadata remains **needs_review**. No password storage or new password parameters are introduced or approved.

## Remaining risks and exact next action

- A single SDK response is allocated before inspection; limits bound inspected history, not absolute heap bytes. Authorized-history scans may fail for a small reporting day. No performance or battery improvement is claimed.
- Owner-only Drive metadata is a point-in-time observation. Remote writes are not transactional; cancellation cannot promise rollback or cross-instance creation safety.
- P0 distributes a debug APK. Production release hardening is not established. Immutable third-party action pinning remains the original nonblocking maintenance suggestion.
- [Device acceptance](device-acceptance.md) is the authoritative current status: D00/D08 partial; D01/D02/D05/D07 observed-scenario PASS; D06 home-departure and screen-lock interruption/retry PASS (lock variant by explicit user report); D09 withdrawal/reauthorization/manual recovery PASS by user report, cancellation NOT OBSERVED; D03 NOT OBSERVED; D04 pending; K01 partial (Secrets configured, separate recoverable backup unconfirmed). Device changes derive from the owner report, not this source review.

The owner also confirms no reporting-day HC weight record; the blank weight export is consistent. Next: confirm recoverable signing-key/password backup outside GitHub Secrets, and compare original source-session bounds privately. D03/D04 need a reproducible timing setup or an explicit owner decision on residual risk and follow-up verification; neither is silently waived. V1 implementation and performance optimization remain deferred.
