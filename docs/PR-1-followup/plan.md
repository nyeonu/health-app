---
plan_id: PR-1-followup
title: PR1 review corrections
kind: single
status: approved
author: Codex
created: 2026-10-31
target: nyeonu/health-app
spec: spec.md
spec_rev: pending-index-commit
approval_basis: User requested handling all PR comments; comments explicitly request client replacement diagnostics and V1 design without SAF implementation.
tasks:
  - id: T1
    title: PATCH-capable transport and local TLS tests
    depends_on: []
    status: pending
    tier: standard
  - id: T2
    title: Safe OAuth diagnostics
    depends_on: []
    status: pending
    tier: standard
  - id: T3
    title: V1 SAF design and device checklist
    depends_on: []
    status: pending
    tier: standard
  - id: T4
    title: Validation handoff and CI evidence
    depends_on: [T1, T2, T3]
    status: pending
    tier: standard
---
# PR1 review corrections
## 1. 선택한 방식과 이유
User-authored review and comments are the approved specification for these corrections. Preserve the existing P0 interfaces/CSV and user-added CI commits. OkHttp is explicitly requested as a supported PATCH alternative; use pinned 5.3.0 verified present on Maven with no OSV coordinate match at planning time (full graph audit still required). JVM local TLS tests exercise the real client including cancellation, without pretending they are Google integration. Core diagnostics remain SDK-free; V1 SAF is documented only. ADR002 Proposed records the network-client replacement. User Cloud setup is unknown; request status asynchronously while completing independent work.
## 2. 공통 설계
### 변경 파일
Task tables below own exact paths. T3 and T4 overlap docs and therefore T4 depends on T3.
### 인터페이스
```kotlin
// GoogleDriveTransport — production API unchanged
class GoogleDriveTransport(accessToken: String) : DriveTransport
// AuthorizationDiagnostics — implementation-specific SDK-free classifier, no throwable messages
object AuthorizationDiagnostics
```
- `TransportStack`: OkHttp 5.3.0 production; JUnit4.13.2, mockwebserver5.3.0, okhttp-tls5.3.0 test-only; existing Kotlin2.2.20/JDK21/SDK36/coroutines1.10.2 unchanged.
### 결정 로직 위치
Endpoint/token validation and response redaction remain inside GoogleDriveTransport; OAuth failure/result classification lives in core/AuthorizationDiagnostics.kt and must be called by actual Activity/ViewModel paths. No IO inside diagnostic formatting.
### 관련된 암묵적 동작
OkHttp.enqueue and cancellation are verified with a local TLS server, no Android hardware. Test client's local routing/trust is internal and must not weaken production endpoint/TLS policy. Android app-unit tests require installed SDK but no device; pure core tests retain -PcoreOnly.
## 3. 추적성
| 스펙 기준 | 테스트 이름 (출처/층) | 충족 위치 |
|---|---|---|
| PATCH is sent by a supported HTTP client and local transport tests prove create then update the same file without duplicates, failure/retry/revoked authorization, bounded/redacted errors, no redirects, allowlisting and cancellation. | Local TLS create then PATCH updates the same Drive file without duplicates; Revoked authorization fails safely and a later explicit retry updates without duplicates; Redirects and disallowed endpoints never receive authorization; Transport failures and oversized responses expose no sensitive body or cause; Cancellation cancels the in flight HTTP call without reporting success (①③④⑤/local TLS integration, not Google account) | T1 |
| OAuth cancellation, Google API numeric status, missing access token and missing drive.file scope produce distinct safe diagnostics without tokens, accounts or exception text. | Cancelled consent and Google API status produce distinct redacted diagnostics; Missing token invalid token and missing Drive scope are distinguished; OAuth diagnostics never expose token scope lists or exception details (①②③/unit), app callback compile/static wiring | T2 |
| Record the user's installed/launched app, HC availability and 5/5 grants as user-reported partial evidence; OAuth/real upload/content gates remain open and actual APK signing/Cloud setup checks are explicit. | Validation evidence separates local TLS tests user reports and pending account provider checks (①/document/static) | T4 |
| V1 design includes shared read/normalize/CSV stages, StorageDestination with Drive API/SAF destinations, independent per-destination outcomes/retries and later both-mode semantics; SAF folder/update/persisted-grant capabilities remain unsupported until device/provider verification. | SAF design covers all seven requested device scenarios with pending results and independent sink status (①/design review) | T3 |
| Provide SAF local folder/create/update/persist/reboot/revocation/offline/provider and V1 background/catch-up test plans, all actual results pending; do not make SAF a P0 merge gate. | Same seven-scenario design checklist (①/design review) | T3,T4 |
## 4. 작업
### T1: PATCH-capable transport and local TLS tests

- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `android/app/build.gradle.kts` | 수정 | PATCH-capable transport and local TLS tests |
  | `android/app/src/main/java/dev/nyeonu/healthapp/GoogleDriveTransport.kt` | 수정 | PATCH-capable transport and local TLS tests |
  | `android/app/src/test/java/dev/nyeonu/healthapp/GoogleDriveTransportTest.kt` | 신규 | PATCH-capable transport and local TLS tests |

- **인터페이스**: 2절 `GoogleDriveTransport` 참조; 2절 `TransportStack` 참조
- **결정 로직**: Replace HttpsURLConnection with OkHttp5.3.0 while keeping production GoogleDriveTransport(accessToken) and core DriveTransport unchanged. Add app test JUnit4.13.2 + mockwebserver5.3.0 + okhttp-tls5.3.0. Pure JVM app-unit tests need SDK but no device. Use actual local TLS server/real OkHttp calls; inject an internal test client/application interceptor to route already-validated Google URLs to local TLS server. Test client trusts its own HeldCertificate only; production uses system TLS and never enables test host/cleartext/trust bypass. Production restrict URI before adding Authorization, redirects includingSSL false, retryOnConnectionFailure false/authenticatorNONE, connect15s/read30s/write30s/wholecall60s, 1MiB successful response bound, no HTTP error body consumption/exposure, always close response. Execute asynchronously via suspendCancellableCoroutine with call.cancel on cancellation; cancellation never converts to generic failure or success. Redact IO/parse/provider messages and causes. Header token validation retained. Verify checked-in old JDK PATCH repro if feasible outside repo; no need to assert undocumented Android behavior. Actual Drive account behavior remains pending.
- **작성할 테스트**:
  - "Local TLS create then PATCH updates the same Drive file without duplicates" — new criteria-based check.
  - "Revoked authorization fails safely and a later explicit retry updates without duplicates" — new criteria-based check.
  - "Redirects and disallowed endpoints never receive authorization" — new criteria-based check.
  - "Transport failures and oversized responses expose no sensitive body or cause" — new criteria-based check.
  - "Cancellation cancels the in flight HTTP call without reporting success" — new criteria-based check.
- **완료 기준**: Tests/checks above pass; device/account limitations explicitly pending.
- **검증 방법**:
  ```bash
  python3 /tmp/run-health-gradle.py :app:testDebugUnitTest --tests '*GoogleDriveTransportTest'
  ```
- **주의사항**: Workdir /workspace/health-app, branch feat/p0-android-poc. English code/docs per AGENTS; Korean user UI. No commits by executors. Only listed paths. Network tool calls need with_additional_permissions network.enabled=true; helper /tmp/run-health-gradle.py preserves proxy/CA, selects JDK21/SDK36 and gradle cache. Read official library APIs before implementing; initial syntax/wiring corrections within named files/dependencies allowed, not versions/security/contracts. Post-verification failure => report blocked with exact evidence; no hidden repair loops. Preserve existing18 core tests and user CI commits. No actual health/account use.

### T2: Safe OAuth diagnostics

- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `android/core/src/main/kotlin/dev/nyeonu/healthapp/core/AuthorizationDiagnostics.kt` | 신규 | Safe OAuth diagnostics |
  | `android/core/src/test/kotlin/dev/nyeonu/healthapp/core/AuthorizationDiagnosticsTest.kt` | 신규 | Safe OAuth diagnostics |
  | `android/app/src/main/java/dev/nyeonu/healthapp/MainActivity.kt` | 수정 | Safe OAuth diagnostics |
  | `android/app/src/main/java/dev/nyeonu/healthapp/SyncViewModel.kt` | 수정 | Safe OAuth diagnostics |

- **인터페이스**: 2절 `AuthorizationDiagnostics` 참조
- **결정 로직**: Introduce SDK-free pure diagnostic classification/messages in core, wire actual GIS callbacks in Activity and ViewModel. Distinguish Activity RESULT_CANCELED and task cancellation, ApiException.statusCode (numeric only, no .message/statusMessage/Throwable text), missing access token, invalid token and missing drive.file scope; handle missing intent/result/resolution and foreground departure as safe fixed categories rather than misleading OAuth cancellation. Health permission launcher failure must not be labelled Google API failure. Preserve same OAuth scope/consent flow, foreground continuation, token memory clearing, busy safeguards, pendingConsent/readyToUpload and no success on any failed authorization. Existing begin/continue date fix retained. No logging of tokens/accounts/CSV; diagnosis must not accept raw exception text. Status10 suggests checking package/certificate/test user config, not assert a proven cause. Pure classifier should be actually used by callback/authorization-result paths, not disconnected test-only helper.
- **작성할 테스트**:
  - "Cancelled consent and Google API status produce distinct redacted diagnostics" — new criteria-based check.
  - "Missing token invalid token and missing Drive scope are distinguished" — new criteria-based check.
  - "OAuth diagnostics never expose token scope lists or exception details" — new criteria-based check.
- **완료 기준**: Tests/checks above pass; device/account limitations explicitly pending.
- **검증 방법**:
  ```bash
  python3 /tmp/run-health-gradle.py -PcoreOnly :core:test --tests '*AuthorizationDiagnosticsTest'
  ```
- **주의사항**: Workdir /workspace/health-app, branch feat/p0-android-poc. English code/docs per AGENTS; Korean user UI. No commits by executors. Only listed paths. Network tool calls need with_additional_permissions network.enabled=true; helper /tmp/run-health-gradle.py preserves proxy/CA, selects JDK21/SDK36 and gradle cache. Read official library APIs before implementing; initial syntax/wiring corrections within named files/dependencies allowed, not versions/security/contracts. Post-verification failure => report blocked with exact evidence; no hidden repair loops. Preserve existing18 core tests and user CI commits. No actual health/account use.

### T3: V1 SAF design and device checklist

- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `docs/V1/storage-destinations.md` | 신규 | V1 SAF design and device checklist |
  | `docs/architecture.md` | 수정 | V1 SAF design and device checklist |
  | `docs/implementation-plan.md` | 수정 | V1 SAF design and device checklist |
  | `README.md` | 수정 | V1 SAF design and device checklist |

- **인터페이스**: No shared API code change.
- **결정 로직**: Implement user comment6537976514 as DESIGN/DOCUMENTATION ONLY. Add V1 StorageDestination.upsert(CsvFile), DriveApiDestination/SafDestination sharing HealthReader->Normalizer->CsvExporter, local/SAF vs DriveAPI choice and later both-mode. No P0 code port/refactor. Per-destination completion/pending/failure/retry/coverage keyed by date and destination; both only fullsuccess when all intended destinations confirmed, retry only failed sink and keep healthy result. SAF ACTION_OPEN_DOCUMENT_TREE, persist granted read/write flags takePersistableUriPermission and restore URI/private state; no filepath conversions/no raw URI in logs; ACTION_CREATE_DOCUMENT does not guarantee overwrite, find/persist documentURI and update via resolver with truncation semantics/provider probes, reject duplicate/ambiguous/moved/deleted targets. SAF does not need app Google OAuth but needs user system consent; all provider capabilities incl Drive tree support unsupported until proven. Local private destination user choice/sharing caveats. Device checklist all7 requested categories with expected results, actualresults PENDING and pass/fail/unsupported schema. V1 WorkManager URI/background/late/missed-day coverage same existing constraints; SAF not P0 merge gate. If Drive SAF folder unsupported use local SAF plus existing DriveAPI, don't claim actualunsupported detection before testing. Explain Android11 treepicker restrictedroots and API stable docs; official sources required.
- **작성할 테스트**:
  - "SAF design covers all seven requested device scenarios with pending results and independent sink status" — new criteria-based check.
- **완료 기준**: Tests/checks above pass; device/account limitations explicitly pending.
- **검증 방법**:
  ```bash
  git diff --check
  ```
- **주의사항**: Workdir /workspace/health-app, branch feat/p0-android-poc. English code/docs per AGENTS; Korean user UI. No commits by executors. Only listed paths. Network tool calls need with_additional_permissions network.enabled=true; helper /tmp/run-health-gradle.py preserves proxy/CA, selects JDK21/SDK36 and gradle cache. Read official library APIs before implementing; initial syntax/wiring corrections within named files/dependencies allowed, not versions/security/contracts. Post-verification failure => report blocked with exact evidence; no hidden repair loops. Preserve existing18 core tests and user CI commits. No actual health/account use.

### T4: Validation handoff and CI evidence

T1, T2, T3 완료 후 시작.

- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `docs/P0/validation.md` | 수정 | Validation handoff and CI evidence |
  | `docs/implementation-plan.md` | 수정 | Validation handoff and CI evidence |
  | `README.md` | 수정 | Validation handoff and CI evidence |
  | `.github/workflows/android-debug-apk.yml` | 수정 | Validation handoff and CI evidence |

- **인터페이스**: No shared API code change.
- **결정 로직**: Record sanitized user comment6537911289 as user-reported install/launch/HC available/5of5/optin; no actualrecords proven. Mark permission gate as userreported with limited observation, actual denial/revocation etc pending. OAuth failed generic, zero upload/no success; rootcause unknown unless user supplies evidence. Cloud package+installed APK certificateSHA1/testaudience/users/DriveAPI checklist; use apksigner verify --print-certs or signingReport, no credential/account values committed. Explain CI auto-generated debug keystore may differ per runner/build; re-register actual artifact SHA1 or use separately managed stable signing outside this PR; never autooverwrite Cloud settings or upload signing key. New safe diagnostics user should share only fixed category/numericcode, never tokens. Local emulator availability: no emulator/systemimage/KVM; no liveUI claim. Preserve Samsung device and Google/account/ChatGPT gates and SAFdesignlink outside P0gate. Update counts from actual XML and note testslevels; current historical P0 reports are snapshots, new followupreport supersedes transport claim. CI retain user's SDK setup/artifact workflow, append :app:testDebugUnitTest to existing Gradle command and app test report artifact path; no emulatorjob or unrelatedworkflowchanges. Actual Google Cloud access unavailable. Final docs must match real results, keep draft.
- **작성할 테스트**:
  - "Validation evidence separates local TLS tests user reports and pending account provider checks" — new criteria-based check.
- **완료 기준**: Tests/checks above pass; device/account limitations explicitly pending.
- **검증 방법**:
  ```bash
  git diff --check
  ```
- **주의사항**: Workdir /workspace/health-app, branch feat/p0-android-poc. English code/docs per AGENTS; Korean user UI. No commits by executors. Only listed paths. Network tool calls need with_additional_permissions network.enabled=true; helper /tmp/run-health-gradle.py preserves proxy/CA, selects JDK21/SDK36 and gradle cache. Read official library APIs before implementing; initial syntax/wiring corrections within named files/dependencies allowed, not versions/security/contracts. Post-verification failure => report blocked with exact evidence; no hidden repair loops. Preserve existing18 core tests and user CI commits. No actual health/account use.

## 5. 전체 검증
Run :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug. Count JUnit XML failures/errors/skips, compare all new names to mapping. Confirm actual wire PATCH/TLS/no automatic retries/cancellation, static production URL/TLS invariants and no secret/value logging. Audit new runtime Maven graph via OSV; retain Guava residual triage if unchanged. Independent fresh-context spec conformance, then code/security review. git diff --check, preserve remote changes, push same PR branch, keep draft and post sanitized response to user comments with actual results/limits. No merge.
## 6. 위험과 롤백
Transport implementation/dependency can be reverted in isolation, but do not silently return to a transport with unverified PATCH support. New TLS tests are synthetic; no Google settings changed. Docs clarify previous P0 review assumptions as historical/superseded. SAF is future design, no new runtime URI grant.
## 복구 기록
| 작업 | 회차/2 | 실패 증거·원인 | 새 근거·수정 가설 | 계획 보정 전→후·유지 기준 | 수정 파일·보존 대상 | 검증 명령·결과 | 실행자·다음 조치 |
|---|---|---|---|---|---|---|---|
## 환경 준비 기록
| 원인·근거 | 준비 조치 | 조치 사용/1 | 확인 명령·결과 | 확인 사용/1 | 다음 조치 |
|---|---|---|---|---|---|
| No /dev/kvm, emulator binary or installed image | No emulator claim; use JVM local TLS tests | 1/1 | inspect tools path and SDK image directories | 1/1 | device/UI/provider tests pending |

