---
plan_id: P0
title: P0 Android proof of concept
kind: single
status: approved
author: Codex
created: 2026-10-31
target: nyeonu/health-app
spec: spec.md
spec_rev: uncommitted-index-of-de491c08e0472cdafba83d1514cd65994fde8ea3
approval_basis: User designated existing instructions as spec and explicitly requested breakdown through completion.
tasks:
  - id: T1
    title: Gradle wrapper bootstrap
    depends_on: []
    status: pending
    tier: standard
  - id: T2
    title: Android and JVM module configuration
    depends_on: [T1]
    status: pending
    tier: standard
  - id: T3
    title: Authorized snapshot domain and normalization
    depends_on: [T2]
    status: pending
    tier: standard
  - id: T4
    title: Manual CSV synchronization
    depends_on: [T3]
    status: pending
    tier: standard
  - id: T5
    title: Foreground Health Connect adapter
    depends_on: [T3]
    status: pending
    tier: standard
  - id: T6
    title: Private Drive idempotent upload
    depends_on: [T4]
    status: pending
    tier: standard
  - id: T7
    title: Health permission entry points
    depends_on: [T2]
    status: pending
    tier: standard
  - id: T8
    title: Compose manual consent and sync screen
    depends_on: [T4, T5, T6, T7]
    status: pending
    tier: standard
  - id: T9
    title: Verification and device handoff
    depends_on: [T8]
    status: pending
    tier: standard
---

# P0 execution plan

## 1. 선택한 방식과 이유

Use the user's existing P0 instructions as the approved source specification. The user explicitly requested end-to-end execution after decomposition, so this authorization covers the concrete plan within P0; no additional product scope is introduced. Pure Kotlin :core keeps time/normalization/CSV/orchestration/Drive contract tests runnable without a device or SDK. Compose and the stable supported AGP8 baseline reduce migration risk versus newly introducing AGP9 built-in Kotlin. GIS AuthorizationClient avoids custom OAuth callbacks and token storage; drive.file scopes uploads to private app-created folders. A dedicated app-created folder with explicit user opt-in avoids broad Drive access/Picker infrastructure in the PoC. These dependency/auth boundaries are ADR-001 Proposed candidates. Real device/account gates cannot be replaced with mocks.

## 2. 공통 설계

### 변경 파일
All paths below are new unless explicitly marked modified in the task. Package: dev.nyeonu.healthapp; core package: dev.nyeonu.healthapp.core.
### 인터페이스
```kotlin
// DataType — granted data categories
enum class DataType { STEPS, WEIGHT, HEART_RATE, SLEEP, EXERCISE }
// WeightMeasurement — UTC instants and source identity
data class WeightMeasurement(val recordId: String, val sourcePackage: String, val time: Instant, val kilograms: Double)
// HeartRateSample — source record linkage
data class HeartRateSample(val recordId: String, val sourcePackage: String, val time: Instant, val bpm: Long)
// SleepSession — original event bounds
data class SleepSession(val recordId: String, val sourcePackage: String, val start: Instant, val end: Instant)
// ExerciseSession — stable Android integer exercise type
data class ExerciseSession(val recordId: String, val sourcePackage: String, val start: Instant, val end: Instant, val exerciseType: Int)
// HealthSnapshot — one KST day, denied categories never read
data class HealthSnapshot(val date: LocalDate, val steps: Long?, val weights: List<WeightMeasurement>, val heartRateSamples: List<HeartRateSample>, val sleepSessions: List<SleepSession>, val exerciseSessions: List<ExerciseSession>, val grantedTypes: Set<DataType>)
// NormalizedDay — daily unvalidated resting HR and duration summaries stay blank
data class NormalizedDay(val date: LocalDate, val generatedAt: Instant, val steps: Long?, val weightKg: Double?, val heartRateSamples: List<HeartRateSample>, val sleepSessions: List<SleepSession>, val exerciseSessions: List<ExerciseSession>)
// HealthReader — foreground-only read
interface HealthReader { suspend fun read(date: LocalDate): HealthSnapshot }
// Normalizer — IO-free mapping
interface Normalizer { fun normalize(snapshot: HealthSnapshot, generatedAt: Instant): NormalizedDay }
// CsvFile — UTF-8 at transport boundary
data class CsvFile(val relativePath: String, val content: String)
// CsvExporter — deterministic dataset order and naming
interface CsvExporter { fun export(day: NormalizedDay): List<CsvFile> }
// DriveUploader — confirmed remote file ID only
interface DriveUploader { suspend fun upsert(file: CsvFile): String }
// SyncResult — no success on empty, denied or partial upload
data class SyncResult(val status: SyncStatus, val uploadedFiles: Int = 0, val errorCode: String? = null)
enum class SyncStatus { SUCCESS, EMPTY, PERMISSION_REQUIRED, FAILED }
// SyncCoordinator — one manual snapshot, cancellation propagates
class SyncCoordinator(reader: HealthReader, normalizer: Normalizer, exporter: CsvExporter, uploader: DriveUploader, clock: Clock) { suspend fun sync(date: LocalDate): SyncResult }
// DayWindow — all dates interpreted in Asia/Seoul
object DayWindow { val zone: ZoneId; fun start(date: LocalDate): Instant; fun end(date: LocalDate): Instant; fun yesterday(clock: Clock): LocalDate }
// DefaultNormalizer — last in-day weight, filter/order samples, retain session identity
class DefaultNormalizer : Normalizer
// VersionedCsvExporter — v0.1 headers from data-contract
class VersionedCsvExporter : CsvExporter
// HealthConnectReader — Android adapter, queried afresh each sync
class HealthConnectReader(context: Context) : HealthReader {
    fun availability(): Int
    suspend fun grantedPermissions(): Set<String>
    companion object { val permissions: Set<String> }
}
// DriveTransport — access token supplied only at Android transport boundary
interface DriveTransport { suspend fun execute(method: String, url: String, contentType: String? = null, body: ByteArray? = null): DriveResponse }
data class DriveResponse(val status: Int, val body: String)
// PrivateDriveUploader — drive.file, app-created folders only
class PrivateDriveUploader(transport: DriveTransport) : DriveUploader
// GoogleDriveTransport — fixed HTTPS endpoints, in-memory access token
class GoogleDriveTransport(accessToken: String) : DriveTransport
```
### 결정 로직 위치
DayWindow and DefaultNormalizer are pure Kotlin in core/Model.kt and core/Normalization.kt. CSV quoting and naming live in core/VersionedCsvExporter.kt; state transitions in core/SyncCoordinator.kt. Drive lookup/upsert decisions live in core/PrivateDriveUploader.kt; no Android dependencies in core.
### 관련된 암묵적 동작
- Health Connect aggregation handles supported cumulative data; do not sum raw steps (confirmed: official get-started documentation).
- GIS AuthorizationClient supplies user consent resolution and access tokens; tokens stay in memory (confirmed: official identity/authorization).
- Compose/activity lifecycle can cancel work; cancellation must never become success (to verify by core cancellation test).
- Build stack: AGP 8.13.2, Gradle 8.13, Kotlin/Compose plugin 2.2.20, compile/target SDK 36, min SDK 28, Java target 17. Health Connect 1.1.0; Compose BOM 2025.09.01; activity-compose 1.11.0; lifecycle-viewmodel-compose 2.9.4; coroutines 1.10.2; kotlinx-serialization-json 1.9.0; play-services-auth 21.4.0; JUnit 4.13.2. Supported stable baseline, not a claim of latest versions; verify artifact existence before introducing it (추정).


## 3. 추적성

| 스펙 기준 | 테스트 이름 (출처/층) | 충족 위치 |
|---|---|---|
| App builds in an adequately configured Android environment, or remaining environment blockers are explicit. | Gradle wrapper runs and reports version 8.13; Core Gradle context starts without Android SDK (①/integration), app assemble/lint (①/build) | T1,T2,T8,T9 |
| Health Connect is queried only with proper runtime permissions; permission denial does not crash. | Denied categories never produce normalized measurements (③/unit); Device procedure covers partial permissions pagination source and timestamps (①/device) | T3,T5,T7,T8,T9 |
| CSV serializer is deterministic and unit-tested; never writes real health data to the repository. | KST day boundaries are half open across UTC midnight; Last authorized in-day weight is selected and missing values stay null; Session identity and original bounds are preserved; CSV headers ordering quoting and missing values follow v0_1; Repeated exports use the same date paths and stable row order (①②④/unit) | T3,T4 |
| User initiates sync in the PoC; status and errors are clear. | Empty and denied reads never upload or report success; Partial upload failure reports only confirmed files; Cancellation propagates without reporting success (③④/unit); Manual device flow distinguishes consent denial empty read upload failure and success (①/device) | T4,T8,T9 |
| Drive integration code is bounded and does not presume credentials are present. | Existing private date file is updated without creating duplicates; Missing private folders and file are created with deterministic metadata; Ambiguous or shared targets are rejected before upload; HTTP failures and malformed responses never report confirmed upload; Invalid export paths are rejected before network access (①③④/unit with fake transport, NOT real Drive) | T6,T8,T9 |
| No unrelated infrastructure, no fake end-to-end success and no expansion beyond P0. | Documentation distinguishes automated evidence from pending physical account validation; Manifest exposes official permission rationale routes and read permissions only (①/manual static) | T7,T9, overall verification |

No prior bugs exist; source ⑤ is inapplicable. Real OAuth/HC/Drive/ChatGPT integration requires the device/account procedure, pending until evidenced. Mapping names may be updated to actual test function names without changing expectations.

## 4. 작업

### T1: Gradle wrapper bootstrap

- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `android/gradlew` | 신규 | Gradle wrapper bootstrap |
  | `android/gradlew.bat` | 신규 | Gradle wrapper bootstrap |
  | `android/gradle/wrapper/gradle-wrapper.jar` | 신규 | Gradle wrapper bootstrap |
  | `android/gradle/wrapper/gradle-wrapper.properties` | 신규 | Gradle wrapper bootstrap |
  | `.gitignore` | 수정 | Gradle wrapper bootstrap |

- **인터페이스**: No shared type changes.
- **결정 로직**:

  Fetch official Gradle 8.13 wrapper files with distribution SHA-256 verification. Add ignores for briefs, local SDK/tooling and temporary files. Bootstrap Gradle under /workspace/tooling, never inside tracked app exports. Install Android SDK command-line tools + platform 36 + build-tools 35.0.0 under /workspace/tooling/android-sdk if available, preserving proxy and CA trust; accept SDK licenses for this build setup. Network shell calls need with_additional_permissions network enabled. Use GRADLE_USER_HOME=/workspace/tooling/gradle-home.

- **작성할 테스트**:
  - "Gradle wrapper runs and reports version 8.13" — new; verify stated contract using synthetic data only.
- **완료 기준**: Listed verification succeeds, or environment-only/device-only blocker is evidenced explicitly; code defects remain incomplete.
- **검증 방법**:
  ```bash
  cd android && ./gradlew --version
  ```
- **주의사항**: Read repository AGENTS.md and required source docs. English code/docs per repository override. Run networked commands with additional network permissions; retain proxy and trust. GRADLE_USER_HOME=/workspace/tooling/gradle-home, ANDROID_HOME=/workspace/tooling/android-sdk. No implementation outside listed files. For initial named infrastructure/API introduction, verify assumptions read-only before writing; may correct syntax/wiring within listed files and named dependencies, never versions/contracts/security. Compile or verification failure => report blocked with evidence; no hidden repair loops. No commits by executors. No fabricated success or real health fixtures.

### T2: Android and JVM module configuration

T1 완료 후 시작.

- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `android/settings.gradle.kts` | 신규 | Android and JVM module configuration |
  | `android/build.gradle.kts` | 신규 | Android and JVM module configuration |
  | `android/gradle.properties` | 신규 | Android and JVM module configuration |
  | `android/core/build.gradle.kts` | 신규 | Android and JVM module configuration |
  | `android/app/build.gradle.kts` | 신규 | Android and JVM module configuration |

- **인터페이스**: No shared type changes.
- **결정 로직**:

  Configure :core as Kotlin JVM and :app Android/Compose; core test task must run without SDK using -PcoreOnly conditional app inclusion in settings. Use stack in shared design, jvmTarget 17 with installed JDK21 (no automatic JDK17 download). App depends on core, Health Connect, GIS, coroutines Android, Compose Material3, lifecycle ViewModel. Introduce only named build plugins; no backend. May verify artifact metadata read-only and report unavailable versions before changing them.

- **작성할 테스트**:
  - "Core Gradle context starts without Android SDK" — new; verify stated contract using synthetic data only.
- **완료 기준**: Listed verification succeeds, or environment-only/device-only blocker is evidenced explicitly; code defects remain incomplete.
- **검증 방법**:
  ```bash
  cd android && ./gradlew -PcoreOnly :core:test
  ```
- **주의사항**: Read repository AGENTS.md and required source docs. English code/docs per repository override. Run networked commands with additional network permissions; retain proxy and trust. GRADLE_USER_HOME=/workspace/tooling/gradle-home, ANDROID_HOME=/workspace/tooling/android-sdk. No implementation outside listed files. For initial named infrastructure/API introduction, verify assumptions read-only before writing; may correct syntax/wiring within listed files and named dependencies, never versions/contracts/security. Compile or verification failure => report blocked with evidence; no hidden repair loops. No commits by executors. No fabricated success or real health fixtures.

### T3: Authorized snapshot domain and normalization

T2 완료 후 시작.

- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `android/core/src/main/kotlin/dev/nyeonu/healthapp/core/Model.kt` | 신규 | Authorized snapshot domain and normalization |
  | `android/core/src/main/kotlin/dev/nyeonu/healthapp/core/Ports.kt` | 신규 | Authorized snapshot domain and normalization |
  | `android/core/src/main/kotlin/dev/nyeonu/healthapp/core/Normalization.kt` | 신규 | Authorized snapshot domain and normalization |
  | `android/core/src/test/kotlin/dev/nyeonu/healthapp/core/NormalizationTest.kt` | 신규 | Authorized snapshot domain and normalization |

- **인터페이스**: 2절 `DataType` 참조; 2절 `WeightMeasurement` 참조; 2절 `HeartRateSample` 참조; 2절 `SleepSession` 참조; 2절 `ExerciseSession` 참조; 2절 `HealthSnapshot` 참조; 2절 `NormalizedDay` 참조; 2절 `HealthReader` 참조; 2절 `Normalizer` 참조; 2절 `CsvFile` 참조; 2절 `CsvExporter` 참조; 2절 `DriveUploader` 참조; 2절 `SyncResult` 참조; 2절 `DayWindow` 참조; 2절 `DefaultNormalizer` 참조
- **결정 로직**:

  Implement shared DTOs and ports. DayWindow is [start,end) KST. DefaultNormalizer selects last in-day authorized weight with deterministic ties; denied fields blank even if input has values. Filter HR samples to window; stable sort details and preserve source and identity. Keep full original bounds for overlapping sleep/exercise sessions; never label raw HR resting HR. P0 daily sleep_min/exercise_min remain blank until validated aggregation semantics. Do not fabricate zeros.

- **작성할 테스트**:
  - "KST day boundaries are half open across UTC midnight" — new; verify stated contract using synthetic data only.
  - "Last authorized in-day weight is selected and missing values stay null" — new; verify stated contract using synthetic data only.
  - "Denied categories never produce normalized measurements" — new; verify stated contract using synthetic data only.
  - "Session identity and original bounds are preserved" — new; verify stated contract using synthetic data only.
- **완료 기준**: Listed verification succeeds, or environment-only/device-only blocker is evidenced explicitly; code defects remain incomplete.
- **검증 방법**:
  ```bash
  cd android && ./gradlew -PcoreOnly :core:test --tests '*NormalizationTest'
  ```
- **주의사항**: Read repository AGENTS.md and required source docs. English code/docs per repository override. Run networked commands with additional network permissions; retain proxy and trust. GRADLE_USER_HOME=/workspace/tooling/gradle-home, ANDROID_HOME=/workspace/tooling/android-sdk. No implementation outside listed files. For initial named infrastructure/API introduction, verify assumptions read-only before writing; may correct syntax/wiring within listed files and named dependencies, never versions/contracts/security. Compile or verification failure => report blocked with evidence; no hidden repair loops. No commits by executors. No fabricated success or real health fixtures.

### T4: Manual CSV synchronization

T3 완료 후 시작.

- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `android/core/src/main/kotlin/dev/nyeonu/healthapp/core/VersionedCsvExporter.kt` | 신규 | Manual CSV synchronization |
  | `android/core/src/main/kotlin/dev/nyeonu/healthapp/core/SyncCoordinator.kt` | 신규 | Manual CSV synchronization |
  | `android/core/src/test/kotlin/dev/nyeonu/healthapp/core/CsvExporterTest.kt` | 신규 | Manual CSV synchronization |
  | `android/core/src/test/kotlin/dev/nyeonu/healthapp/core/SyncCoordinatorTest.kt` | 신규 | Manual CSV synchronization |

- **인터페이스**: 2절 `CsvFile` 참조; 2절 `NormalizedDay` 참조; 2절 `CsvExporter` 참조; 2절 `VersionedCsvExporter` 참조; 2절 `SyncCoordinator` 참조; 2절 `SyncResult` 참조; 2절 `HealthReader` 참조; 2절 `Normalizer` 참조; 2절 `DriveUploader` 참조
- **결정 로직**:

  Implement all v0.1 CSV headers, CRLF RFC4180 escaping, UTC event instants, explicit units, fractional minutes. Emit daily file only if steps/weight populated; details only if records populated. Empty data never becomes success. Sync reads-normalizes-exports-uploads sequentially; count only confirmed uploads, failures expose a stable redacted code and partial count; cancellation propagates. Manual only, no retry loop or schedule. Permissions absent gives PERMISSION_REQUIRED. No clocks via system globals.

- **작성할 테스트**:
  - "CSV headers ordering quoting and missing values follow v0_1" — new; verify stated contract using synthetic data only.
  - "Repeated exports use the same date paths and stable row order" — new; verify stated contract using synthetic data only.
  - "Empty and denied reads never upload or report success" — new; verify stated contract using synthetic data only.
  - "Partial upload failure reports only confirmed files" — new; verify stated contract using synthetic data only.
  - "Cancellation propagates without reporting success" — new; verify stated contract using synthetic data only.
- **완료 기준**: Listed verification succeeds, or environment-only/device-only blocker is evidenced explicitly; code defects remain incomplete.
- **검증 방법**:
  ```bash
  cd android && ./gradlew -PcoreOnly :core:test --tests '*CsvExporterTest' --tests '*SyncCoordinatorTest'
  ```
- **주의사항**: Read repository AGENTS.md and required source docs. English code/docs per repository override. Run networked commands with additional network permissions; retain proxy and trust. GRADLE_USER_HOME=/workspace/tooling/gradle-home, ANDROID_HOME=/workspace/tooling/android-sdk. No implementation outside listed files. For initial named infrastructure/API introduction, verify assumptions read-only before writing; may correct syntax/wiring within listed files and named dependencies, never versions/contracts/security. Compile or verification failure => report blocked with evidence; no hidden repair loops. No commits by executors. No fabricated success or real health fixtures.

### T5: Foreground Health Connect adapter

T3 완료 후 시작.

- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `android/app/src/main/java/dev/nyeonu/healthapp/HealthConnectReader.kt` | 신규 | Foreground Health Connect adapter |

- **인터페이스**: 2절 `HealthConnectReader` 참조; 2절 `DayWindow` 참조; 2절 `HealthSnapshot` 참조; 2절 `DataType` 참조; 2절 `WeightMeasurement` 참조; 2절 `HeartRateSample` 참조; 2절 `SleepSession` 참조; 2절 `ExerciseSession` 참조
- **결정 로직**:

  Use official Health Connect 1.1.0 APIs. Recheck sdk availability and permissions each read. Read permitted types only, all pages for weights/HR/sleep/exercise with page-token loop; choose half-open KST window; aggregate StepsRecord.COUNT_TOTAL rather than raw sum. Map identity and provenance. No permission is not empty health data. HR one recent-day bounded foreground read allowed for P0; no continuous sampling. Bound page loop and explicitly fail on repeated token/oversize if needed; never silently truncate. Do not invent unit conversion APIs; verify sources/SDK.

- **작성할 테스트**:
  - "Device procedure covers partial permissions pagination source and timestamps" — new; verify stated contract using synthetic data only.
- **완료 기준**: Listed verification succeeds, or environment-only/device-only blocker is evidenced explicitly; code defects remain incomplete.
- **검증 방법**:
  ```bash
  cd android && ./gradlew :app:compileDebugKotlin
  ```
- **주의사항**: Read repository AGENTS.md and required source docs. English code/docs per repository override. Run networked commands with additional network permissions; retain proxy and trust. GRADLE_USER_HOME=/workspace/tooling/gradle-home, ANDROID_HOME=/workspace/tooling/android-sdk. No implementation outside listed files. For initial named infrastructure/API introduction, verify assumptions read-only before writing; may correct syntax/wiring within listed files and named dependencies, never versions/contracts/security. Compile or verification failure => report blocked with evidence; no hidden repair loops. No commits by executors. No fabricated success or real health fixtures.

### T6: Private Drive idempotent upload

T4 완료 후 시작.

- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `android/core/src/main/kotlin/dev/nyeonu/healthapp/core/PrivateDriveUploader.kt` | 신규 | Private Drive idempotent upload |
  | `android/core/src/test/kotlin/dev/nyeonu/healthapp/core/PrivateDriveUploaderTest.kt` | 신규 | Private Drive idempotent upload |
  | `android/app/src/main/java/dev/nyeonu/healthapp/GoogleDriveTransport.kt` | 신규 | Private Drive idempotent upload |

- **인터페이스**: 2절 `PrivateDriveUploader` 참조; 2절 `DriveTransport` 참조; 2절 `DriveResponse` 참조; 2절 `DriveUploader` 참조; 2절 `CsvFile` 참조; 2절 `GoogleDriveTransport` 참조
- **결정 로직**:

  Implement shared transport and REST Drive v3. Use kotlinx.serialization Json DOM (no extra plugin). Restrict URL to googleapis HTTPS Drive metadata/upload endpoints; disable redirects, set timeouts, redact HTTP bodies in exceptions. No token persistence. Find app-created private HealthApp root by appProperties marker under user's My Drive; refuse shared/non-owned folders and ambiguous multiple matches instead of arbitrary selection. Create private child dataset folders; deterministic date filename; query parent, name, trashed=false plus marker; update existing file by ID, create only absent. No blind retry of POST, no duplicate success, paginate lookup or fail explicit ambiguity. Check ownership/shared state/parents for found entries. Validate relative paths allowlist; use JSON escaping and URI encoding. Fail HTTP errors and malformed IDs; success only confirmed response. No sharing API calls.

- **작성할 테스트**:
  - "Existing private date file is updated without creating duplicates" — new; verify stated contract using synthetic data only.
  - "Missing private folders and file are created with deterministic metadata" — new; verify stated contract using synthetic data only.
  - "Ambiguous or shared targets are rejected before upload" — new; verify stated contract using synthetic data only.
  - "HTTP failures and malformed responses never report confirmed upload" — new; verify stated contract using synthetic data only.
  - "Invalid export paths are rejected before network access" — new; verify stated contract using synthetic data only.
- **완료 기준**: Listed verification succeeds, or environment-only/device-only blocker is evidenced explicitly; code defects remain incomplete.
- **검증 방법**:
  ```bash
  cd android && ./gradlew -PcoreOnly :core:test --tests '*PrivateDriveUploaderTest'
  ```
- **주의사항**: Read repository AGENTS.md and required source docs. English code/docs per repository override. Run networked commands with additional network permissions; retain proxy and trust. GRADLE_USER_HOME=/workspace/tooling/gradle-home, ANDROID_HOME=/workspace/tooling/android-sdk. No implementation outside listed files. For initial named infrastructure/API introduction, verify assumptions read-only before writing; may correct syntax/wiring within listed files and named dependencies, never versions/contracts/security. Compile or verification failure => report blocked with evidence; no hidden repair loops. No commits by executors. No fabricated success or real health fixtures.

### T7: Health permission entry points

T2 완료 후 시작.

- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `android/app/src/main/AndroidManifest.xml` | 신규 | Health permission entry points |
  | `android/app/src/main/java/dev/nyeonu/healthapp/PrivacyActivity.kt` | 신규 | Health permission entry points |
  | `android/app/src/main/res/values/strings.xml` | 신규 | Health permission entry points |

- **인터페이스**: No shared type changes.
- **결정 로직**:

  Declare INTERNET and exactly five P0 read health permissions; no writes/background/storage/exact alarm. Export launcher MainActivity. Provide official rationale ACTION_SHOW_PERMISSIONS_RATIONALE and Android14+ VIEW_PERMISSION_USAGE alias protected by START_VIEW_PERMISSION_USAGE with HEALTH_PERMISSIONS category. Health Connect package query. Disable backup and cleartext traffic. PrivacyActivity uses simple Compose local policy explaining private Drive flow, consent withdrawal and data retention; don't claim policy published. strings app_name. MainActivity arrives later; resource/manifest validation can precede its implementation.

- **작성할 테스트**:
  - "Manifest exposes official permission rationale routes and read permissions only" — new; verify stated contract using synthetic data only.
- **완료 기준**: Listed verification succeeds, or environment-only/device-only blocker is evidenced explicitly; code defects remain incomplete.
- **검증 방법**:
  ```bash
  cd android && ./gradlew :app:processDebugMainManifest
  ```
- **주의사항**: Read repository AGENTS.md and required source docs. English code/docs per repository override. Run networked commands with additional network permissions; retain proxy and trust. GRADLE_USER_HOME=/workspace/tooling/gradle-home, ANDROID_HOME=/workspace/tooling/android-sdk. No implementation outside listed files. For initial named infrastructure/API introduction, verify assumptions read-only before writing; may correct syntax/wiring within listed files and named dependencies, never versions/contracts/security. Compile or verification failure => report blocked with evidence; no hidden repair loops. No commits by executors. No fabricated success or real health fixtures.

### T8: Compose manual consent and sync screen

T4, T5, T6, T7 완료 후 시작.

- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `android/app/src/main/java/dev/nyeonu/healthapp/MainActivity.kt` | 신규 | Compose manual consent and sync screen |
  | `android/app/src/main/java/dev/nyeonu/healthapp/SyncViewModel.kt` | 신규 | Compose manual consent and sync screen |

- **인터페이스**: 2절 `HealthConnectReader` 참조; 2절 `GoogleDriveTransport` 참조; 2절 `PrivateDriveUploader` 참조; 2절 `SyncCoordinator` 참조; 2절 `DayWindow` 참조; 2절 `DefaultNormalizer` 참조; 2절 `VersionedCsvExporter` 참조; 2절 `SyncResult` 참조
- **결정 로직**:

  Compose Material3 screen shows P0, yesterday KST window, HC availability, granted category count, last attempt, last confirmed success (in-process clearly labelled), upload count, empty/denied/failed/partial states. Separate request Health Connect permissions, private folder upload opt-in, then Sync now obtains GIS AuthorizationClient authorize drive.file only and resolves via StartIntentSenderForResult. OAuth client configured by Android package+SHA1 externally; don't embed credentials. Token in memory only, never saved state/logged. Reauthorization each manual sync supports revoked grants; forget local state/token action, instruct revoke via system. Work runs only with foreground user action and no automatic background execution; guard duplicate clicks, lifecycle cancellation and UI errors. Confirm creation/use of private app folder before upload. Wire actual adapters and core coordinator, not placeholders. Refresh permissions on resume; errors generic/redacted. No requirement for identity/profile scopes.

- **작성할 테스트**:
  - "Manual device flow distinguishes consent denial empty read upload failure and success" — new; verify stated contract using synthetic data only.
- **완료 기준**: Listed verification succeeds, or environment-only/device-only blocker is evidenced explicitly; code defects remain incomplete.
- **검증 방법**:
  ```bash
  cd android && ./gradlew :core:test :app:assembleDebug :app:lintDebug
  ```
- **주의사항**: Read repository AGENTS.md and required source docs. English code/docs per repository override. Run networked commands with additional network permissions; retain proxy and trust. GRADLE_USER_HOME=/workspace/tooling/gradle-home, ANDROID_HOME=/workspace/tooling/android-sdk. No implementation outside listed files. For initial named infrastructure/API introduction, verify assumptions read-only before writing; may correct syntax/wiring within listed files and named dependencies, never versions/contracts/security. Compile or verification failure => report blocked with evidence; no hidden repair loops. No commits by executors. No fabricated success or real health fixtures.

### T9: Verification and device handoff

T8 완료 후 시작.

- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `docs/P0/validation.md` | 신규 | Verification and device handoff |
  | `docs/implementation-plan.md` | 수정 | Verification and device handoff |
  | `docs/data-contract.md` | 수정 | Verification and device handoff |
  | `README.md` | 수정 | Verification and device handoff |

- **인터페이스**: No shared type changes.
- **결정 로직**:

  Document exact build/test results (read logs, don't invent), stack official links, Android OAuth package+debug SHA1 setup, enable Drive API consent test user, install commands, Samsung Health write permissions/source timing, five type availability, repeat sync same file ID, private folder checks, revoked/partial permissions, missing values/offline/cancel/account switch. Verify ChatGPT file CONTENTS manually as a separate gate; do not access real data now. Explain Sheets alternative only as fallback to verify, no implementation. Update P0 roadmap checkboxes only from actual evidence; keep real device/Drive/ChatGPT gates unchecked. Explain partial upload non-transactionality, token memory, app-created drive.file folders and daily fields intentionally blank. Update no-app README status accurately.

- **작성할 테스트**:
  - "Documentation distinguishes automated evidence from pending physical account validation" — new; verify stated contract using synthetic data only.
- **완료 기준**: Listed verification succeeds, or environment-only/device-only blocker is evidenced explicitly; code defects remain incomplete.
- **검증 방법**:
  ```bash
  git diff --check
  ```
- **주의사항**: Read repository AGENTS.md and required source docs. English code/docs per repository override. Run networked commands with additional network permissions; retain proxy and trust. GRADLE_USER_HOME=/workspace/tooling/gradle-home, ANDROID_HOME=/workspace/tooling/android-sdk. No implementation outside listed files. For initial named infrastructure/API introduction, verify assumptions read-only before writing; may correct syntax/wiring within listed files and named dependencies, never versions/contracts/security. Compile or verification failure => report blocked with evidence; no hidden repair loops. No commits by executors. No fabricated success or real health fixtures.

## 5. 전체 검증

1. Run android/gradlew -PcoreOnly :core:test; inspect JUnit XML for zero skipped tests and compare names/expectations to table.
2. With SDK configured run :app:assembleDebug :app:lintDebug and inspect lint report. Without SDK record exact error and execute all available core checks.
3. git diff --check; inspect manifest/permissions, scan tracked files for credentials/raw health data, dependency tree and available dependency security audit (report external-data blockers).
4. Independent spec-conformance-check, then code-review-and-quality and security review. Feed findings to original task's bounded correction; no review-time direct edits.
5. Device/Google/ChatGPT procedure in validation.md remains pending; never mark P0 exited from automated evidence alone.

## 6. 위험과 롤백

Only new P0 app files and scoped documentation are changed on feat/p0-android-poc. Revert changes to remove PoC; no production deployment. Remote day uploads are individually confirmed, not a cross-file transaction. Never delete a user's Drive folder automatically. Denied/revoked permissions and ambiguous/private Drive targets fail explicitly. APK installation and real data upload remain user-operated.

## 복구 기록

| 작업 | 회차/2 | 실패 증거·원인 | 새 근거·수정 가설 | 계획 보정 전→후·유지 기준 | 수정 파일·보존 대상 | 검증 명령·결과 | 실행자·다음 조치 |
|---|---|---|---|---|---|---|---|

## 환경 준비 기록

| 원인·근거 | 준비 조치 | 조치 사용/1 | 확인 명령·결과 | 확인 사용/1 | 다음 조치 |
|---|---|---|---|---|---|
| Default shell could not reach proxy; network-enabled command cloned repo successfully | Request additional network permission on networked commands | 1/1 | git clone succeeded | 1/1 | Preserve proxy and CA trust |

