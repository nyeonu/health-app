# PR1 follow-up execution evidence

Scope: user PR review plus comments 6537911289 and 6537976514. [Specification](spec.md), [plan](plan.md). Keep PR1 draft; manual Google/HC/content gates stay open.

## Execution result

- T1–T5: implementation/documentation completed; independent conformance and final quality/security review passed on the corrected source (see linked reports).
- T1 replaces URLConnection with OkHttp 5.3.0 while retaining HTTPS allowlist, token validation, no redirects, explicit-only write retry, response/time limits and coroutine cancellation.
- T2 separates actual consent/task cancellation, numeric Google API status and token/scope/missing-result failures through a closed diagnostic vocabulary; no raw error text reaches UI.
- T3 adds V1-only SAF/Drive destination design and all seven pending Galaxy scenarios, with independent destination completion/retries and later both-mode full-success semantics.
- T4 records limited user-reported device observations, beginner Cloud/actual-artifact certificate setup, emulator limitations and new CI app tests/report artifacts; no live Google settings changed.

- T5 applies the user-requested application identity com.ny.my.health / core suffix .core, with source/test directory and documentation alignment only. Separate-install/re-consent/new-OAuth-client and old-file visibility/manual cleanup are documented.

## Verification

Pre-identity-update combined evidence: `python3 /tmp/run-health-gradle.py :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:dependencies --configuration debugRuntimeClasspath` succeeded in 12s (58 tasks, 2 executed, 56 cached). 21 core + 6 app = 27 tests; zero failures/errors/skips. Lint: 0 errors, 11 warnings (9 dependency notices, backup rules, missing icon). The helper preserves managed proxy/CA and selects full JDK 21 / SDK 36; not a repository prerequisite.

Current identity clean build: `:core:clean :app:clean :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` passed in1m24s,59tasks executed,27tests0failure/error/skip,lint0errors11warnings. Root cached verification passed13s. ActualAPK/mergedmanifest identify com.ny.my.health; APK34,698,241bytes. Independent source comparison confirms18Kotlinfiles plus appGradle package mapping only. Test expectations/names remain intact.

Local TLS tests use real OkHttp requests and a local HeldCertificate, not a Google account. Production uses system TLS trust. Old full-JDK URLConnection reproduction produced `ProtocolException: Invalid HTTP method: PATCH` without a network request; do not generalize that result to every Android implementation.

OSV checked 117 resolved runtime Maven coordinates: unchanged Guava 31.1-android matches 3 advisories, no new OkHttp/Okio coordinate match. This is not a zero-vulnerability or full-transitive-unreachability claim. Retain preproduction remediation/reachability review and 2026-11-30 recheck.

## Test comparison

All nine new names below appear in the plan traceability table and were executed; original 18 core tests preserved. Document-only criteria use static review rather than invented executable tests.

| New planned/actual test | File | Result |
|---|---|---|
| Cancelled consent and Google API status produce distinct redacted diagnostics | AuthorizationDiagnosticsTest | PASS |
| Missing token invalid token and missing Drive scope are distinguished | AuthorizationDiagnosticsTest | PASS |
| OAuth diagnostics never expose token scope lists or exception details | AuthorizationDiagnosticsTest | PASS |
| Server retry after zero never replays POST or PATCH without an explicit new attempt | GoogleDriveTransportTest | PASS |
| Local TLS create then PATCH updates the same Drive file without duplicates | GoogleDriveTransportTest | PASS |
| Transport failures and oversized responses expose no sensitive body or cause | GoogleDriveTransportTest | PASS |
| Revoked authorization fails safely and a later explicit retry updates without duplicates | GoogleDriveTransportTest | PASS |
| Cancellation cancels the in flight HTTP call without reporting success | GoogleDriveTransportTest | PASS |
| Redirects and disallowed endpoints never receive authorization | GoogleDriveTransportTest | PASS |

## Recovery

- T1 2/2: first preserved null exception causes through coroutine recovery; final review then reproduced transparent POST replay on503/Retry-After0 despite connection retry being disabled. All POST/PATCH bodies, including empty bodies, are now one-shot. The sixth TLS regression proves one send, preserved/redacted503 and a later explicit attempt. Existing tests/assertions retained.
- T3 1/2: clarify existing CsvFile relativePath/content fields; schema_version is payload and UTF-8 encoding occurs at destination, not new DTO fields.
- T4 2/2: corrected stale artifact size/ad-hoc per-file literal checking, then refreshed evidence for the new27-test single-attempt regression. No executable tests or expected behavior weakened.

- PLAN 1/2: corrected a missing metadata newline joining T4/T5; independent YAML parse confirms all5task IDs/statusdone. No source/test behavior changed.

## Brief evidence

- <!-- brief T1 default · 150줄 · 11134자 · 추정 토큰 3711 · Shared design 참조 2(명시 2, 해소 실패 0) · 변경 파일 3 · 테스트 5 · 작업 블록 24줄 · Shared design 6줄 · Goal 3줄 -->
- <!-- brief T2 default · 147줄 · 10660자 · 추정 토큰 3553 · Shared design 참조 1(명시 1, 해소 실패 0) · 변경 파일 4 · 테스트 3 · 작업 블록 23줄 · Shared design 4줄 · Goal 3줄 -->
- <!-- brief T3 default · 139줄 · 10476자 · 추정 토큰 3492 · Shared design 참조 0(명시 0, 해소 실패 0) · 변경 파일 4 · 테스트 1 · 작업 블록 21줄 · Shared design 0줄 · Goal 3줄 -->
- <!-- brief T4 default · 141줄 · 10405자 · 추정 토큰 3468 · Shared design 참조 0(명시 0, 해소 실패 0) · 변경 파일 4 · 테스트 1 · 작업 블록 23줄 · Shared design 0줄 · Goal 3줄 -->

T1 TransportStack is an intentional fully collected bullet reference, not a missing code fragment. No referenced API contract was missing.

## Remaining validation

- User reports install/launch, HC available, 5/5 grants and opt-in, but generic OAuth failure, zero uploads/no success. These observations are not independently reproduced; denial/revocation and real record reads remain pending.
- No emulator binary, system image or KVM exists in this environment; no live Android UI/provider evidence claimed and no assertion software emulation is impossible.
- Google Cloud setup is user-controlled and not confirmed. Check actual installed APK package/certificate, audience/test user and enabled Drive API privately. No credentials/token/account details are requested or recorded.
- Real private Drive create/update/privacy, Samsung source behavior and ChatGPT actual contents remain unverified. SAF is future design only; all seven provider/device/background outcomes pending, not P0 merge gates.

## Next step

Independent [conformance](conformance-report.md) and [quality/security review](review-report.md) passed on the corrected source. Next use the documented Cloud setup and synthetic device procedure. Do not merge or advance to V1 from these automated results alone.

Final independent namespace conformance and quality/security reviews PASS/APPROVE against source60ccbb5. New-package install/device/account/Drive/ChatGPT and SAF provider checks remain pending. Remote CI results are reported separately when observed; local checks do not substitute for a hosted artifact.
