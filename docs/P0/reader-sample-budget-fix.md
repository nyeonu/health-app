# PR #4 review correction: per-page heart-rate budget

Addresses the owner's [Important finding](https://github.com/nyeonu/health-app/pull/4#issuecomment-6550528982) at source `192ab46`. That head is not approved for merge. This is a correction within PR #4, not a new phase. The initial same-session approval in [the interval/pagination report](reader-boundary-fix.md) is explicitly superseded.

Verified correction implementation/test commit: `e69a17e80bc9d28302fe9a4cf6e4d81feb2dd60a`.

## Failure and bounded correction

The initial reader called `readAll()` for heart-rate records, retaining every raw page before checking the 100,000-sample limit. A 1,000-record/page bound does not bound samples within those records. Expanding raw interval lookup to authorized earlier starts increased exposure to this pre-existing accumulation pattern.

A shared private `readPages()` helper now validates page size, processes the received page synchronously, and only then follows its continuation token. The heart-rate callback sums that page's sample counts into a Long running total and **throws immediately on more than 100,000 samples**, before examining sample values or requesting a subsequent page. Each permitted page is filtered directly into day-specific domain samples; no all-pages raw heart-rate accumulator or temporary filtered sample list remains. Weight/sleep/exercise retain `readAll()` as an adapter over the same pagination helper, preserving their previous behavior.

The budget still counts **all inspected samples**, including historical samples excluded from export. Exactly 100,000 is allowed; 100,001 is rejected. It is not a per-day-only budget and is not reset between pages. Null/empty terminal tokens, repeated nonempty token rejection, maximum 100 pages and maximum page size 1,000 remain. Provider/permission/limit errors propagate; the coordinator returns READ_FAILED and performs no upload even when earlier pages had populated the prospective day snapshot. No new permission, dependency, log, persistence, CSV schema or external service is added.

## Reproduction and regression evidence

The same synthetic SDK/client conditions were run before and after the correction. No real health records or account operations were used.

| Check | Before (`192ab46` + new tests only) | Corrected behavior |
| --- | --- | --- |
| Historical pages with 60,000 samples each and nonempty tokens | Requested page **3** despite 120,000 received on pages 1–2; assertion expecting two requests failed. | Raises `Health Connect sample limit exceeded` after request **2**; no third request. |
| Page one has three tracked past samples; page two asserts page one already processed | Page-two request observed **0** inspected past samples instead of 3. | All **3** are inspected/discarded before page-two request; only two day-specific samples appear in output. |
| 60,000 past samples + 40,000 current-day samples | Already accepted (boundary coverage retained). | Accepts exactly **100,000 inspected**, outputs only **40,000** current-day samples, two requests. |
| 60,000 + 40,001 | Already rejected at completion, insufficient alone to detect delayed checking. | Rejects **100,001** on receipt of second page, two requests. |
| Coordinator with a populated first page and over-budget second page | No upload, but erroneously reached request **3**. | READ_FAILED, zero confirmed files, **zero uploader calls**, exactly **two requests**. |

Four new `HealthConnectReaderTest` cases cover these observations:

- `Heart rate sample cap stops after the second oversized page without requesting a third`
- `Heart rate sample budget accepts exactly 100000 and rejects 100001 across pages`
- `Past heart rate samples are processed and discarded before requesting the next page`
- `Read limit failure after a populated page never uploads a partial day`

Before correction, **14 reader tests ran, three failed**; the existing ten and the new exact-boundary case passed. Failure messages recorded request counts 3 instead of 2 and first-page inspections 0 instead of 3. After correction, **all 14 reader tests pass**, including the existing null/empty tokens, cyclic tokens, page-size/page-limit boundaries, fresh grants/permission revocation, unavailable SDK, interval queries and date filtering. All six unchanged local-TLS transport tests and 27 core tests also pass.

Local command: `cd android && ./gradlew --no-daemon :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`. Result: **BUILD SUCCESSFUL in 51s**, 57 actionable tasks (15 executed, 42 up-to-date). XML: **27 core + 20 app = 47 tests, zero failures/errors/skips**. Debug APK passes; lint **0 errors / 14 existing warnings**. Full JDK 21.0.13, Gradle 8.13, SDK 36 with configured proxy/CA trust. Hosted JVM/build/lint and emulator results must be checked against the updated PR head; earlier green runs do not validate this correction.

## Review and limitations

Code inspection confirms the heart-rate path calls the page consumer before continuing and never calls the accumulating `readAll()` adapter. The sample limit check precedes date filtering, so filtering older samples cannot bypass the inspection budget. Tests assert provider call counts and page-consumption order; they do not infer release from nondeterministic garbage collection. Null/empty token semantics, token-cycle/page bounds, permission error propagation and atomic read-before-upload behavior are preserved through the common helper and the coordinator tests. Testing and performance checklists were applied to the reproduced call-count and retention-order defect; no unrelated optimization was made.

This is not an absolute heap-byte bound. A **single SDK response is allocated before the app can inspect it**, and may itself exceed the sample budget. The reader no longer retains all raw heart-rate pages, but retained output-day samples and the current response still consume memory. No heap profile, OOM reproduction, device latency, p95/p99, battery measurement or field performance improvement is claimed. Historical volume can still legitimately cause a fail-closed limit error for a small export day.

**Subsequent review/merge event:** the owner [approved corrected head `fd48be0`](https://github.com/nyeonu/health-app/pull/4#issuecomment-6551010783), explicitly resolving the Important finding. Its [JVM/APK/lint CI](https://github.com/nyeonu/health-app/actions/runs/41252675747) and [Android 15 emulator CI](https://github.com/nyeonu/health-app/actions/runs/41252675744) succeeded; downloaded XML shows 47 JVM + 3 instrumented tests, zero failures/errors/skips. PR #4 merged as `d33edae688421ea41d00b22353e960c006f021a5`. Galaxy/provider IPC, fixed-key OAuth/Drive smoke testing, D01–D09 and key-backup confirmation remain pending; this acceptance of the code change does not close P0/V1 gates.
