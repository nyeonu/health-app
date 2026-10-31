---
status: implemented
source: ../codex-first-task.md
approved_by: user
approved_at: 2026-10-31
---

# P0 specification index

## 1. Overview
- Implement only the smallest verifiable P0 Android slice specified in ../codex-first-task.md.
- Read authorized Health Connect records, normalize CSV, and upload manually to private Google Drive.
- The user explicitly designated the existing instructions as the specification and requested breakdown through completion on 2026-10-31.

## 2. Scope
The normative requirements remain ../codex-first-task.md, ../implementation-plan.md (P0), ../architecture.md, ../data-contract.md and ../../AGENTS.md. This index introduces no new product requirements.

### 제외
V1 scheduling, incremental sync, MCP, FCM, backend and workout plans.

## 3. Environment
Repository initially contains documentation only. JDK 21 is available; Android SDK, Google OAuth project and physical device are not configured. Install build tooling locally where possible; retain explicit blockers otherwise.

## 4. Contracts
Use ../data-contract.md v0.1. Real device and account verification remain distinct from unit tests.

## 5. Acceptance
- App builds in an adequately configured Android environment, or remaining environment blockers are explicit.
- Health Connect is queried only with proper runtime permissions; permission denial does not crash.
- CSV serializer is deterministic and unit-tested; never writes real health data to the repository.
- User initiates sync in the PoC; status and errors are clear.
- Drive integration code is bounded and does not presume credentials are present.
- No unrelated infrastructure, no fake end-to-end success and no expansion beyond P0.

## 6. Constraints
Keep health data and credentials out of public Git, logs and fixtures. Do not claim physical-device or account integration success without evidence. Code and durable documentation follow the repository's English convention; Korean UI is permitted.
