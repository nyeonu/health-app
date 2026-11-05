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


## Independent PR review (owner instruction, 2026-11-02)

- Write PR titles, descriptions, and agent-authored review/fix/validation comments in Korean (owner instruction, 2026-11-03). Preserve exact code/API identifiers, file paths, commands, hashes and necessary literal output.

- Every PR, including documentation/specification PRs and PR #9, requires review in a separate session from its author and fixer. Use a fresh-context reviewer for each PR; for specification conformance, provide the approved spec and implementation, not the plan or earlier review conclusions.
- Review the current head and full relevant diff. Record the reviewed commit, scope, findings and severity, actual validation evidence and limits, and verdict in the PR's comments. An explicit no-findings result still needs a comment. Chat replies or local reports alone do not satisfy this requirement.
- Correct blocking findings, record the fix commit(s) and checks in PR comments, and obtain independent re-review of the updated head before merge. Preserve previous findings and explain their disposition rather than replacing the review history.
- Record the final reviewed head and merge suitability in a PR comment. Do not treat review approval as owner approval of a draft spec, permission to expand scope, or evidence that unperformed device/account checks passed; retain the existing authorization and acceptance gates.
- Keep reviewers read-only and avoid exposing previous agents' reports through agent-status tools when an isolated review is required. Keep all public review comments free of health data, credentials and private identifiers.

## Battery-aware scheduling (V1+)
- WorkManager is an Android Jetpack **persistent background work scheduler**, not a custom thread manager. Implement work itself in a `CoroutineWorker`; let WorkManager/OS handle scheduling, constraints, and process lifecycle. Do not build a polling loop, custom periodic threads, or always-on service.
- `PeriodicWorkRequest` does **not** guarantee a fixed wall-clock time or even completion within a given calendar day. Doze, app standby, OEM restrictions, absent network, low battery, phone power-off, revoked permission, or force-stop may defer work, sometimes for hours or longer. Do not describe a periodic worker as a reliable 06:00 alarm.
- Persist `lastSuccessfulSyncDay` and per-day outcomes in a durable local store. On worker execution **and** app open/manual sync, identify missing local dates and enqueue idempotent catch-up work. If nothing can run, show an explicit stale/failed state instead of claiming freshness.
- Distinguish `scheduledAt`, `startedAt`, `lastSuccessAt`, and coverage dates. A successful job is not necessarily a complete day's dataset, because Samsung Health can sync later.
- Use WorkManager constraints (network available, battery not low for automatic runs); use appropriate exponential backoff for transient failures. A blocked constraint does not imply that a job has run or will finish today.
- Prefer Health Connect Aggregate API for supported summary metrics and batch outputs into the fewest reasonable Drive requests. Limit detailed heart-rate samples to workout sessions and explicit targeted queries.
- Never assume constraints, battery percentage, exact wake-ups, or Android OS scheduling on a test device. Validate actual behavior on Galaxy S23 Ultra, including Doze, reboot, no network, low battery, and missed-day recovery.
- Avoid exact alarms and battery-optimization exemptions for routine daily exports; they are unnecessary, can cost power, and can be permission restricted. If exact-time functionality is ever proposed, justify it separately before implementation.
## Definition of done
A task is done only when code exists, automated verification was run where supported, and any device-only or account-only manual validation is explicitly identified as pending. Do not confuse a mock test with real Health Connect, Google OAuth, or ChatGPT integration.

## 소유자 자동 승인 위임 (2026-11-05)

- 소유자는 기존 V1 범위의 spec/plan/implementation, 되돌릴 수 있는 수정, 독립 검토와 검토·검증된 PR의 개발 병합을 자동 승인했다. 다음 세션은 이 승인 범위에 반복 승인 질문을 하지 않는다. 범위 확대는 기존 별도 승인 경계를 유지한다.
- 실제 기기·계정 수용 통과, 실제 background toggle/login 실행, secret 노출 승인은 포함하지 않는다. 수행하지 않은 Galaxy/TalkBack/P0/실계정 검증은 pending으로 유지하며 개발 병합을 V1 출시 승인으로 해석하지 않는다.
- 한국어 PR 제목·설명·에이전트 댓글과 fresh-context 별도 세션 독립 검토, 현재 head 검증·차단 지적 수정·재검토·PR 댓글 기록 의무는 그대로 유지한다.
