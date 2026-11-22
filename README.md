# Health App

Personal, privacy-conscious Android health-data integration with ChatGPT.

## Goal

Read authorized health records from Android Health Connect (including data synchronized by Samsung Health), export normalized CSV files to a private Google Drive folder, and later support ChatGPT analysis and two-way workout-plan workflows through a separate API/MCP layer.

**Repository:** `nyeonu/health-app`  
**Primary device:** Galaxy S23 Ultra (Android 16)  
**Time zone for daily reporting:** Asia/Seoul  
**현재 상태 (2026-11-22):** 기능 1.0.0/2의 소프트웨어 구현·전체 자동 검증·독립 검토를 완료한 설치 후보입니다. 수동 비공개 Drive·로컬 SAF와 명시 opt-in 자동 Drive를 지원합니다. API35·36 전체 Native186개씩·기존 격리 host 및 JVM210/lint/APK를 통과했습니다. [설치 안내](docs/V1/1.0-installation.md)와 [실행 증거](docs/2026-11-22-v1-release/execution-report.md)를 확인하세요. 실제 Galaxy·계정/provider·Doze/재부팅/저전력·TalkBack 및 기존 P0 미확인은 남아 있습니다. Actions Secrets 설정만으로 별도 개인 키 백업이 확인되지는 않습니다.

## Delivery phases

| Phase | Deliverable |
| --- | --- |
| P0 — Technical PoC | Verify real Health Connect records, permissions, Drive upload, and actual ChatGPT file access |
| V1 — Android MVP | Manual + once-daily snapshot sync, CSV export, private Drive upload, sync status and retry |
| V2 — Incremental sync | Health Connect Changes API, updates/deletes, persisted tokens, recovery |
| V3 — Remote command | Independent TypeScript REST backend and MCP adapter; optional async mobile sync command |
| V4 — Workout plans | ChatGPT-generated plan storage, Android read/display, workout completion feedback |

Trigger (manual, scheduled, remote) and strategy (daily snapshot, incremental) are independent concepts.

## Initial architecture

```text
Galaxy Watch -> Samsung Health -> Health Connect
                                  |
                                  v
                         Android App (Kotlin)
                         - permissions
                         - HealthReader
                         - SyncEngine
                         - CsvExporter
                         - DriveUploader
                                  |
                                  v
                          Private Google Drive
                                  |
                      user-authorized ChatGPT analysis

Future: ChatGPT -> MCP Adapter -> REST API -> queue/FCM -> Android App
```

The future MCP server does **not** directly access Android Health Connect. Mobile device reads require the app and user-granted permissions. MCP access to ChatGPT depends on product/client support and must be verified before V3.

## Source of truth

- [Agent instructions](AGENTS.md)
- [Implementation plan and acceptance criteria](docs/implementation-plan.md)
- [Architecture and trust boundaries](docs/architecture.md)
- [CSV data contract](docs/data-contract.md)
- [V1 storage destination design and pending Galaxy SAF checklist](docs/V1/storage-destinations.md)
- [First Codex assignment](docs/codex-first-task.md)

## Security

**Never commit** real health measurements, CSV exports, Google tokens, OAuth credentials, private keys, or personal identifiers. The GitHub repository is public. Store health records only in explicitly authorized private locations. Minimize collected data and never assume a missing value is zero.

## Implementation

`android/`는 Compose 앱과 SDK-free JVM `core`를 포함합니다. Health Connect에서 허용된 항목만 읽고 `Asia/Seoul` 날짜 기준 CSV를 내보냅니다. 수동 동기화는 비공개 Google Drive 또는 검증된 로컬 SAF 폴더 중 하나를 사용합니다. 로컬 폴더의 파일은 다른 앱이나 백업에 노출될 수 있습니다. cloud SAF, 백그라운드 SAF, 동시 목적지, backend 및 incremental sync는 지원하지 않습니다.

설치와 사용 전에 [P0 빌드·OAuth 설정 및 기기 검증](docs/P0/validation.md)을 확인하세요. `android/`에서 JDK 17 이상과 Android SDK 36으로 `./gradlew :app:assembleDebug`를 실행해 빌드하고 APK를 설치합니다. Drive 사용 시 설치된 APK의 서명 인증서에 맞는 Google OAuth 설정이 필요합니다. 앱에서 Health Connect 항목별 읽기 권한을 허용하고 목적지를 선택하세요. Drive는 비공개 폴더 사용 동의와 Google 인가 후 **업로드 계속**을 눌러야 합니다. 로컬 SAF는 별도 위치·노출 동의와 폴더 권한 및 쓰기 검증 후 명시적으로 계속합니다. 선택이나 권한 화면 복귀만으로 수동 업로드하지 않습니다.

자동 동기화는 기본적으로 꺼져 있으며 비공개 Drive에서만 사용할 수 있습니다. 기기의 백그라운드 읽기 지원과 별도 권한을 확인·요청한 뒤 **자동 동기화 켜기**의 동의 화면에서 확인해야 합니다. 허용된 걸음·수면·운동 요약과 최신 체중을 처리하며 자동 심박 범위는 차단됩니다. WorkManager는 약 24시간 간격으로 예약하지만 네트워크 연결, 배터리 부족 방지 조건, Doze와 기기 제한으로 지연될 수 있어 정확한 시각이나 당일 완료는 보장하지 않습니다. 누락 날짜는 한 번에 최대 7일을 따라잡고 최근 2일을 다시 확인합니다. 일시적 실패에는 영속적인 최대 3회 재시도 예산이 적용되며, 소진·차단·원격 상태 불명 등은 화면에서 확인하고 수동 복구해야 합니다. 성공 시각만으로 현재 날짜의 완전성이나 최신성을 판단하지 마세요.

같은 Google 계정의 재인증과 내보낼 항목 변경은 기존 등록일·파일 추적 정보·불확실한 업로드·소비한 재시도 예산을 보존합니다. 과거 항목의 기록을 보관하는 것은 현재 읽기 권한을 뜻하지 않으며, 새 실행은 현재 선택과 허용 범위만 사용합니다. 자동 끄기·켜기로 실패 예산이 초기화되지 않습니다.

이 설정과 날짜별 확인 상태는 앱 전용 SQLite에 보관합니다. 자동 동기화를 끄거나 로컬 상태를 초기화해도 이미 전송된 파일이나 시스템 권한은 삭제되지 않습니다. 미확인 P0 항목과 실제 Galaxy·TalkBack·Google 계정 수용은 그대로 남아 있습니다. 전체 Native 실행·원본 무결성과 독립 검토는 [현재 실행 증거](docs/2026-11-22-v1-release/execution-report.md)에 고정했습니다.

초기 app identity 교체 이력: 현재 package는 `com.ny.my.health` (core: `com.ny.my.health.core`)입니다. 이전 package에서 옮기는 경우 다른 앱이므로, so install it as a new app rather than an in-place update; consider local state before removing the previous app. 해당 package 이전에서는 Health Connect 권한과 Drive 사용 시 Google 인가를 다시 확인합니다. Register the new package with the actual installed APK certificate SHA-1: the previous OAuth client does not apply automatically. Prior `drive.file` folder/file visibility is not guaranteed. There is no automatic migration or deletion; inspect and clean up previous exports privately and manually. Follow the [application identity migration guide](docs/P0/validation.md#application-identity-migration).

### 과거 진단 및 검증 이력

The PR 1 follow-up passed 21 core unit tests and 6 synthetic local TLS transport tests (27 total, no failures/errors/skips), debug build and lint (0 errors/11 warnings). Local TLS tests verify OkHttp create/PATCH behavior, including exactly one send on HTTP 503 with `Retry-After: 0` and a redacted 503 result before a later explicit call; they do not prove Google account access. 초기 PR 1 후속 검증의 OAuth 실패 보고는 당시 이력입니다. 실제 기기·계정 수용의 현재 상태는 [P0 readiness](docs/P0/pr-readiness.md)를 참고하세요. Follow the [beginner Google Cloud and actual APK certificate steps](docs/P0/validation.md#google-project-configuration-beginner-handoff-pending-user-controlled-setup), then share only a fixed OAuth category/numeric status if retry fails.

V1 전경 수동 내보내기는 같은 CSV/journal 경로에서 Drive API 또는 검증된 SAF local tree를 선택합니다. SAF는 별도 Android 폴더 동의와 실제 권한·쓰기·짧은 덮어쓰기/readback probe를 요구하며, 선택·복원만으로 건강 자료를 내보내지 않습니다. 로컬 폴더는 다른 앱이나 백업에 노출될 수 있어 별도 동의가 필요합니다. 미검증/cloud provider와 SAF background는 차단하며 Android 28의 local capability는 지원하지 않습니다. [소프트웨어 검증 증거](docs/V1/saf-local-verification.md)와 [미확인 Galaxy/provider 체크리스트](docs/V1/storage-destinations.md#galaxy-s23-ultra-poc--seven-requested-scenarios)를 구분합니다. 동시 both-mode는 지원하지 않으며 SAF는 P0 merge gate가 아닙니다.

Latest device report shows a completed Health Connect read with all five allowed types, followed by generic sync failure and zero confirmed uploads. The [sync diagnostics correction](docs/PR-1-sync-diagnostics/report.md) now displays allowlisted phase, Drive operation and numeric HTTP status without provider bodies or personal data (31 tests pass). The actual account failure and private upload remain unverified; check Drive API enablement before installing a differently signed diagnostic APK.

The next device diagnostic identified `DRIVE_ROOT_LOOKUP:HTTP_404`. The [Drive root correction](docs/PR-1-drive-root/report.md) removes the root-metadata prerequisite, looks up app folders with the documented root alias, and confirms newly created app folders under My Drive before dataset/CSV writes. The regression failed before correction and passes afterward; 33 JVM tests, APK build and lint pass. `drive.file` and private-target checks remain; actual Google upload and P0 gates are still pending.

Fixed CI signing is configured using two owner-managed Actions Repository secrets. Trusted branch builds distribute APKs only when the fixed key is available; PR validation never uses the key and missing secrets do not produce a random-certificate download. See the [phone/Codespaces key creation and setup guide](docs/P0/fixed-signing.md). Owner Secrets were accepted after correcting a Base64 paste; the hosted fixed-certificate build passed and its distributed APK certificate was independently checked against signing.txt. The user now reports successful fixed-certificate installation/OAuth/sync with two confirmed files. Private backup, remote CSV/private-sharing/no-duplicate-update inspection and permission edge cases remain pending; repeated local synthetic-key builds verified certificate reuse.

[PR #1 post-merge acceptance status](docs/P0/pr-readiness.md) records successful live private Drive CSV content reads, source step-count matching, two-revision idempotent updates and completed Android emulator tests. The owner's merge is recorded; unresolved device-edge and security follow-ups remain tracked rather than silently marked complete.


> Historical diagnostic sections above describe earlier failures during PR development; they are retained for troubleshooting and are superseded by the current status at the top and in the readiness report.
