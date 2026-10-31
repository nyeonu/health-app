# P0 reader interval and pagination correction

Scope: the two existing reader defects identified in the owner's [PR #3 review comment](https://github.com/nyeonu/health-app/pull/3#issuecomment-6550358477). PR #3 was merged with expected head `f3d8401` after both PR workflows succeeded; merge commit `178ee3f32505115b5e7aefb5431254cdfd2515da`. This correction is a new candidate based on that master, not part of the Guava change and not a V1/V2 implementation.

Verified implementation/test source commit: `b19fb036cdfb789d945053d9080d8389a5d1436e`.

**Owner review correction:** [REQUEST CHANGES at `192ab46`](https://github.com/nyeonu/health-app/pull/4#issuecomment-6550528982) identified that the sample cap was checked only after all raw heart-rate pages had accumulated. The initial same-session approval below is superseded. The [page-by-page sample-budget correction and new evidence](reader-sample-budget-fix.md) address that finding in the same PR. Merge remains pending re-review; the original 43-test result alone is insufficient.

## Required behavior and correction

The existing [data contract](../data-contract.md) requires overlapping sleep/exercise sessions with their full original bounds, and heart-rate samples only in the half-open reporting day. The reader previously applied the day's lower bound to raw interval-record queries, so a start-time-selecting provider could omit a record before the normalizer ever saw it. Extending only the normalizer, or assuming every session starts within a one-day lookback, cannot correct that omission.

The actual `ReadRecordsRequest` for sleep, exercise and heart rate now uses `TimeRangeFilter.before(dayEnd)`. The provider's authorized history boundary remains effective; no history or background permission is added. Sleep/exercise records are filtered by `record.startTime < dayEnd && record.endTime > dayStart`, with original bounds/IDs/provenance preserved. Heart-rate samples remain filtered by `dayStart <= sample.time < dayEnd`. Weight and aggregate steps retain their original day-scoped query; no raw step sum is introduced.

Pagination now terminates on `pageToken.isNullOrEmpty()`. Nonempty token repetition, maximum 100 pages, page size 1,000 and maximum 100,000 inspected heart-rate samples still fail explicitly. The 100th page can complete successfully when its token is null/empty; an unfinished 100th page fails. No incomplete snapshot is returned after provider/permission failure or a limit violation.

The only production file changed is `HealthConnectReader.kt`. An internal constructor accepts the actual SDK client/status providers for JVM verification; the public Context constructor still uses the application context, checks SDK availability and gets fresh grants for each read. Test injection does not replace or disable production permission checks. No dependency, manifest, signing, OAuth, CSV schema, storage or orchestration change is included. This bounded defect correction uses be-workflow's testing-policy bug route; it is not a new feature or architecture plan.

## Official API evidence

- [Android read-data pagination and restrictions](https://developer.android.com/health-and-fitness/guides/health-connect/develop/read-data): terminal tokens can be null or empty; ordinary read authorization is limited by the first permission grant's history boundary. That boundary is not a rolling 30-day lookback from the requested date, and the app does not request history expansion.
- [AndroidX TimeRangeFilter](https://developer.android.com/reference/androidx/health/connect/client/time/TimeRangeFilter): `before(Instant)` supports an open lower endpoint. The pinned AndroidX `connect-client:1.1.0` source JAR was inspected to verify this exact API.
- [AOSP RecordHelper at inspected commit](https://android.googlesource.com/platform/packages/modules/HealthFitness/+/45168a88ae2a7e1abafe1cc81001d97ff00194e2/service/java/com/android/server/healthconnect/storage/datatypehelpers/RecordHelper.java): raw-record request time predicates use the start-time column and add the read-access-start-date predicates separately. Aggregation's interval predicates are a separate path; they must not be assumed for raw reads. This source evidence is not a claim that every Samsung/legacy provider version was executed or inspected.

## Regression evidence and traceability

`HealthConnectReaderTest` executes the production reader with real AndroidX request/response/record objects. Its test-only SDK interface double filters records by the request's start-time bounds, records the actual requests and fails on unexpected operations. It deliberately does not supply already-overlapping records regardless of the query, which would hide the original defect. No Android device, new mock library or account is required. Reflection is confined to the private test double.

Before the query/token correction, with only the constructor test seam in place, **8 reader tests executed and 5 failed**: overnight/multi-day sessions, earlier heart-rate parents, null/empty terminal tokens, multiple pages and last-permitted-page termination. The first test-writing attempt exposed inaccessible SDK-internal types; the tests were corrected to use public record types and AggregateRequest equality before collecting this failing evidence. After the correction, those eight passed. Two further guard tests bring the final reader suite to ten.

| Existing requirement / failure condition | Final reader regression |
| --- | --- |
| Earlier starts, midnight overlap, original session bounds | `Sleep and exercise queries retain overnight and multi day sessions with original bounds`: previous-day and three/five-day starts; exact boundary exclusions; actual query asserted. |
| Day samples from an earlier parent | `Heart rate query includes earlier parent records but exports only half open day samples`: four-day-old parent, samples immediately before/at day start and immediately before/at day end. |
| Preserve non-interval day semantics | `Weight and aggregated steps keep the original daily query bounds`: actual weight and aggregate requests compared to day-scoped requests. |
| Terminal tokens | `Null and empty last page tokens terminate without an extra request`: both tokens, request count exactly one. |
| Continuation integrity | `Multiple pages preserve interval query bounds and continuation tokens`: stable window/page size, token progression, earlier record on first page. |
| Cycle protection | `Repeated nonempty tokens still fail instead of returning partial data`. |
| Page-limit boundary | `Page limit fails and a terminal token on the last permitted page succeeds`: exactly 100 requests in each case. |
| Permission gate / later provider revocation | `Fresh denied grants prevent reads and later revocation fails without a partial snapshot`: no requests on zero grants, only the later granted type queried, page-two SecurityException propagates. |
| Size bounds | `Oversized pages and excessive samples fail without returning a truncated snapshot`: 1,001 records / 100,001 samples. |
| SDK gate | `Unavailable SDK fails before acquiring a client`. |

Combined verification: `:core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` **BUILD SUCCESSFUL in 47s**, 57 actionable tasks (13 executed, 44 up-to-date). XML shows **27 core + 16 app tests = 43, zero failures/errors/skips**, including all six pre-existing transport tests and ten new reader tests. Full JDK 21.0.13, Gradle 8.13 and SDK 36. A subsequent test-source-only typing cleanup removed two new compiler warnings; final `:app:testDebugUnitTest :app:lintDebug` **BUILD SUCCESSFUL in 34s**, 36 actionable tasks (5 executed, 31 up-to-date), with all 16 app tests still passing and no new test compiler warnings. Lint remains **0 errors, 14 warnings**. No product logic was changed by that test cleanup. Gitleaks 8.30.1 found no leaks in the app source/test tree or P0 documents; diff whitespace checks passed.

## Review, limitations and next check

**Initial same-session approval: superseded by the owner's REQUEST CHANGES.** It missed the deferred sample-count check and raw-page accumulation described in the follow-up above. The type permission checks remain fresh, no extra permissions or logging are introduced, and aggregate-step semantics are unchanged. The corrected page-budget implementation must be assessed with its new regression evidence, not this historical approval. No independent agent review, physical-provider proof or P0 completion is claimed here.

The broader raw lookup may read more authorized history than the exported day. **Historical volume can now hit the unchanged page/sample bounds even when the desired day is small.** All inspected heart-rate samples count toward the safety cap, including earlier samples later excluded from export. This is an explicit failure tradeoff, not a battery/performance improvement claim. No arbitrary lookback or early-stop rule based on session duration was invented. Provider quota/history rejection fails rather than fabricating rows or success. Records whose starts are outside the platform's authorized history cannot be recovered by this change; full source completeness must not be inferred.

Actual Galaxy behavior, provider page-token serialization, permission withdrawal, quota/latency, fixed-certificate Google authorization and Drive updates remain pending. No health records/account writes were accessed here; APKs built locally use a test certificate and were not distributed. The existing [D01–D09 and key-backup checklist](device-acceptance.md) remains open. After merge, use the owner-fixed master CI APK: verify a known overnight sleep/exercise interval and its original bounds privately, verify same-date Drive updates without duplicates, then complete the exception cases. Report only matched/missing, success/failure and safe error codes, never measurements, IDs, tokens or keys. V1 SAF/WorkManager follows the P0 acceptance decision, not this unit-test count.
