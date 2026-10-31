# Codex first assignment — P0 Android proof of concept

Repository: `nyeonu/health-app`

## Objective
Implement **only the smallest verifiable P0 Android slice**. Do not implement V1 scheduling, incremental sync, MCP, FCM, a server, or workout plans yet.

## Required steps
1. Read `AGENTS.md`, `docs/implementation-plan.md`, `docs/architecture.md`, and `docs/data-contract.md` in full.
2. Inspect repository and select currently supported Kotlin/Android Gradle/Compose/Health Connect dependencies. Prefer official documentation. Explain deviations from the planned stack.
3. Scaffold `android/` as a minimal Gradle Android app with `Sync now` screen, current status, Health Connect availability and user consent/permission flow.
4. Implement testable interfaces for `HealthReader`, `Normalizer`, `CsvExporter`, `DriveUploader`, `SyncCoordinator`.
5. Implement a foreground Health Connect read for a small recent window across the P0 record types, handling empty/denied/unavailable states and read pagination. Do not fabricate readings.
6. Implement a deterministic CSV exporter that follows `docs/data-contract.md`, with unit tests for missing fields, CSV escaping, KST date boundaries and stable column order.
7. Prepare user-authorized Google OAuth/Drive upload with smallest possible scope. A functioning real upload depends on user-controlled OAuth client configuration; do not check credentials in.
8. Add instructions for installing a debug build, configuring OAuth, granting permissions, reading device records and verifying private Drive file access.
9. Run the available build/test commands and report exact results. If Android SDK / credentials / device / online dependency fetching are unavailable, clearly state which checks could not run and why.
10. Update roadmap completion checkboxes only for gates genuinely validated, with evidence.

## Acceptance
- App builds in an adequately configured Android environment, or remaining environment blockers are explicit.
- Health Connect is queried only with proper runtime permissions; permission denial does not crash.
- CSV serializer is deterministic and unit-tested; never writes real health data to the repository.
- User initiates sync in the PoC; status and errors are clear.
- Drive integration code is bounded and does not presume credentials are present.
- No unrelated infrastructure, no fake end-to-end success and no expansion beyond P0.

## Response format
Summarize: files changed; architecture choices; build/tests executed and outcomes; required local Android/Google configuration; device/manual tests still pending; known risks; smallest next task.

## Handoff prompt
> Follow `AGENTS.md` and `docs/codex-first-task.md`. Implement P0 only in `nyeonu/health-app`. Make reviewable, testable changes, run available checks, and clearly distinguish actual verification from device/account steps that still need manual testing.
