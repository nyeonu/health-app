---
status: implemented
approved_by: user
approved_at: 2026-10-31
source: PR1 user review PRR_kwDOCMRxVs8AAAABVPe6yQ and comments 6537911289,6537976514
---
# PR 1 follow-up specification
## 1. Overview
- Handle the user's three PR comments: PATCH-capable transport/tests, sanitized OAuth diagnostics/device evidence, and a V1 SAF coexistence design/test checklist.
- Keep PR1 draft. The user explicitly requested processing these comments; that authorizes the bounded corrections and documentation.
## 2. Scope
P0 networking and diagnostics fixes, user-reported validation evidence, V1 design only.
### 제외
No merge, no SAF implementation or storage-port refactor in P0, no WorkManager/backend/incremental feature. No automatic Google Cloud account configuration or real health-data access.
## 3. Environment
Full JDK21 and SDK36 exist. No configured Google Cloud identity/account. Galaxy test results are user-reported, not independently reproduced. No emulator binary, system image or /dev/kvm was found; document current local emulator unavailability without claiming software emulation is impossible.
## 4. Contracts
Preserve DriveTransport.execute, drive.file only, HTTPS host/path allowlist, Authorization protections, redirects disabled, bounded response, timeout, cancellation propagation, no blind POST retry and error redaction. CSV contract unchanged.
## 5. Acceptance
- PATCH is sent by a supported HTTP client and local transport tests prove create then update the same file without duplicates, failure/retry/revoked authorization, bounded/redacted errors, no redirects, allowlisting and cancellation.
- OAuth cancellation, Google API numeric status, missing access token and missing drive.file scope produce distinct safe diagnostics without tokens, accounts or exception text.
- Record the user's installed/launched app, HC availability and 5/5 grants as user-reported partial evidence; OAuth/real upload/content gates remain open and actual APK signing/Cloud setup checks are explicit.
- V1 design includes shared read/normalize/CSV stages, StorageDestination with Drive API/SAF destinations, independent per-destination outcomes/retries and later both-mode semantics; SAF folder/update/persisted-grant capabilities remain unsupported until device/provider verification.
- Provide SAF local folder/create/update/persist/reboot/revocation/offline/provider and V1 background/catch-up test plans, all actual results pending; do not make SAF a P0 merge gate.
## 6. Constraints
Never print token/account/health payloads, weaken TLS/security or claim local fake-server tests validate Google accounts. Preserve remote CI commits df57843/d008d6f. Tests and build evidence must describe their level and limitations.
