# Codex instructions — Health App

## Mission
Build a minimal, reliable, privacy-first Android app that reads user-authorized Health Connect records and uploads normalized CSV exports to the user's private Google Drive. Work iteratively through the written phases, beginning with P0.

## Read before editing
1. `README.md`
2. `docs/implementation-plan.md`
3. `docs/architecture.md`
4. `docs/data-contract.md`
5. `docs/codex-first-task.md` (for the initial implementation)

## Engineering rules
- Android app: Kotlin, Jetpack Compose, coroutines, WorkManager when needed.
- Future server: Node.js + TypeScript. **Do not create a backend or MCP service in P0**.
- Keep Health Connect access, data mapping, CSV serialization, Google Drive upload, and orchestration behind separate, testable interfaces. Prefer simple implementations over speculative abstraction.
- Separate sync **trigger** (`MANUAL`, `SCHEDULED`, later `REMOTE`) from sync **strategy** (`DAILY_SNAPSHOT`, later `INCREMENTAL`).
- Treat 24-hour daily windows in `Asia/Seoul`, store original instants with offsets/UTC, and use explicit units. Preserve record identity/provenance where available.
- Never aggregate raw step records by blindly summing duplicate data from multiple recording apps; verify Health Connect aggregation behavior and provenance.
- Do not invent unsupported Health Connect data, Android permissions, API guarantees, or ChatGPT/MCP integration capabilities. For uncertain APIs, check official documentation and log the assumption in the PR.
- Never require an Android hardware device for pure unit tests; isolate Android SDK dependencies via interfaces.
- Add tests for time-window boundaries, missing values, CSV escaping, idempotent file naming/upsert, and failure handling before claiming a feature complete.
- Do not silently replace missing health data with 0 or fabricate successful background synchronization.
- Ask for review before changing phase scope substantially. Do not skip P0 gates.

## Security and privacy
- Repo is PUBLIC. Never commit personal measurements, exported CSVs, OAuth tokens, credential JSON, passwords, secrets, signing keys, user/device identifiers, or debugging dumps.
- Google Drive upload must target a user-authorized private location. Scope least-privilege OAuth access (prefer `drive.file` if feasible).
- Health Connect data must be accessed only through granted permissions. Background reading requires permission/feature support checks; WorkManager timing is inexact.
- Log status and redacted errors only; do not log raw measurements or access tokens.
- Design for consent withdrawal, permission denial, missing records, sync interruption, and recovery.
- Avoid introducing a remotely accessible service until access control, command authentication and replay protection are designed.

## Workflow
- For each task: state scope -> inspect repository -> implement smallest working slice -> run available tests -> report results, limitations, and exact next action.
- Use short, reviewable changes with meaningful commits and a clear summary.
- If the environment lacks Android SDK, do not claim an app build passed. Run all possible static/unit checks, explicitly report the missing capability, and provide exact device validation steps.
- Keep `docs/implementation-plan.md` checkboxes/status synchronized with real evidence, not intentions.
- Use English for code identifiers and documentation conventions; Korean is fine for user-facing text.

## Definition of done
A task is done only when code exists, automated verification was run where supported, and any device-only or account-only manual validation is explicitly identified as pending. Do not confuse a mock test with real Health Connect, Google OAuth, or ChatGPT integration.
