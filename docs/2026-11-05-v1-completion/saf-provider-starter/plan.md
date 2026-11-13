---
plan_id: saf-provider-starter
title: 다음 SAF 단계의 독립 합성 provider 기반
kind: single
status: in_progress
author: Codex
created: 2026-11-13
spec: ../spec.md
spec_rev: 97c01b1992ad7935e6fa8f1f8b371a3963457daa
branch: feat/v1-saf-provider-starter
tasks:
  - {id: T1, title: 실제 합성 DocumentsProvider와 grant starter, depends_on: [], status: in_progress, tier: standard}
---

# 독립 SAF 합성 provider 기반

## 1. 선택한 방식과 이유

소유자의 V1 자동승인과 병렬지시에 따라 독립검토를통과한 SAF draft T1의 test-only 기반만 별도worktree에서 미리작성한다. PR17제품파일과겹치지않고최종profile API를사용하지않는다. SAF제품/controller활성화나기기capability통과가아니다. PR17병합후SAFchild를실제mergehead로재검토/repin하고3파일만통합한다. actualstarter Native확인후기존SAFT1완료로대응하며중복작성하지않는다. 사용자반복승인없이root가이제한계획을승인했다. 최초계획작성에서존재하지않는protocol명참조를정적조립전에발견해실제SDK계약아래로명확히고정했다. 제품실패/숨은재시도는없다.

## 2. 공통 설계

```text
// SafProviderStarterContracts — SDK36 test-only provider와 실제 target UID grant
SyntheticDocumentsProvider : DocumentsProvider, nested GrantActivity, SyntheticProviderStarterTest, testAPK AndroidManifest.xml만 신규.
queryRoots/queryDocument/queryChildDocuments/openDocument/createDocument/isChildDocument는 SDK36 실제 override를 구현하고 isChildDocument ancestry를 명시한다(기본 false).
고정 test-only authority와 합성 fixture-owned storage만; production APK/manifest에 provider,permission,Activity가 들어가면 실패.
GrantActivity는 test APK UID에서 target package에 tree READ/WRITE/PERSISTABLE/PREFIX grant를 발급하고, target UID ContentResolver가 실제 takePersistableUriPermission/persisted grants/query/read/close를 수행한다.
같은 test UID의 resolver mock을 성공으로 부르지 않는다. read-only grant와 revoke 거부도 실제 OS 경계로 확인한다.
fixture controls는 reset/grant/revoke/duplicate/move/delete/partial/loading/null/no-truncate/wrong-return-identity/create-after-effect/write-close-interruption 등 합성 enum만 사용한다.
provider raw exception/URI/documentID/본문은 공개 assertion메시지/로그/diagnostics에 넣지 않는다. 실제계정/건강값/외부provider/생산grant 사용금지.
SAF production local authority allowlist/background disabled/privacy/probe 계약은 후속 child가 담당한다. 이 test authority는 production capability allowlist에 추가하지 않는다.
```

## 3. 추적성

| 스펙 기준 | 테스트 | 작업 |
|---|---|---|
| SAF local 구성은 별도 privacy/tree 동의·실제 granted flags·persisted permission·합성 probe가 확인된 capability만 활성화하고 미검증 provider/background capability는 disabled 또는 BLOCKED로 유지한다. | Synthetic provider grants target UID tree access and resolver reads the seeded document (실제Android fixture기반 기동) | T1; 전체제품capability는후속SAFpending |

## 4. 작업

### T1: 실제 합성 DocumentsProvider와 grant starter

- **변경 파일**

  | 파일 | 신규/수정 | 변경 요지 |
  |---|---|---|
  | `android/app/src/androidTest/java/com/ny/my/health/SyntheticDocumentsProvider.kt` | 신규 | test APK provider·중첩 GrantActivity·제어된 실패/차단점 |
  | `android/app/src/androidTest/java/com/ny/my/health/SyntheticProviderStarterTest.kt` | 신규 | target ContentResolver로 실제 grant·query·read 한 번 |
  | `android/app/src/androidTest/AndroidManifest.xml` | 신규 | test APK에만 provider와 grant Activity 등록 |

- **인터페이스**: 2절 `SafProviderStarterContracts` 참조.

- **SDK 선언**:

  ```kotlin
  // DocumentsProvider 실제 SDK36 추상 메서드; nullable SDK 인자는 override에서 맞춘다.
  queryRoots(projection: Array<out String>?): Cursor
  queryDocument(documentId: String, projection: Array<out String>?): Cursor
  queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor
  openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor
  createDocument(parentDocumentId: String, mimeType: String, displayName: String): String
  isChildDocument(parentDocumentId: String, documentId: String): Boolean
  // fixture control: reset, grant/revoke, duplicate, move/delete, partial/loading/null,
  // no-truncate, wrong-return-identity, create-after-effect-failure, write/close interruption.
  ```

- **결정 로직**:

  ```
  fixture child ancestry → exact parent chain → tree membership
  failure checkpoint before/after effect → configured synthetic outcome only
  ```

- **호출 순서**:

  ```
  1. test-only GrantActivity가 test APK UID에서 target package에 tree read/write/persistable/prefix grant 발행.
  2. target ContentResolver가 실제 takePersistableUriPermission과 persisted grant 재조회 → 실패 시 starter 실패.
  3. target resolver query/read/close → 실패 시 starter 실패, provider 원문 공개 로그 금지.
  ```

- **작성할 테스트**:
  - "Synthetic provider grants target UID tree access and resolver reads the seeded document"
- **테스트 조건/층**: 실제 binder/OS grant와 exact synthetic bytes, read-only grant 및 revoke 거부도 확인한다(②·③/Android 통합).
- **완료 기준**: provider는 target production manifest/APK에 없고 API35/36에서 starter가 실제 target UID ContentResolver 연결·grant·읽기를 증명한다. test APK의 자체 resolver 접근만으로 완료하지 않는다.
- **검증 방법**:

  ```bash
  cd android
  ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
  ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.ny.my.health.SyntheticProviderStarterTest
  ```

- **주의사항**: §2 확인: SDK DocumentsProvider.isChildDocument 기본값은 false이므로 fixture가 ancestry를 구현한다. provider는 test APK permission MANAGE_DOCUMENTS/grantUriPermissions와 DOCUMENTS_PROVIDER filter를 갖고 grant Activity만 실제 target URI grant를 발급한다. control payload는 enum/합성 값만; 측정값·URI를 assertion 메시지/출력에 담지 않는다. 신규 기반 최초 구현에 한해 manifest/test UID grant 기동 형태만 이 세 파일 안에서 보정할 수 있다. 구현 전 읽기 전용으로 UID/grant 동작을 확인하고 위임 범위 안에서 구현·보고한다. 기동/컴파일 또는 구현 후 검증 실패 뒤에는 blocked로 보고하고 집계된 복구 지시를 기다린다. 테스트 skip·단언 완화 금지.


## 5. 전체 검증

로컬 run-health-command.py wrapper로 app/testAPK컴파일, 최종API35/36에서 starter targetUID grant/읽기실행. 실제Native전완료판정없음. schema/dependency/productionpermission변경없음. branch배포/병합은PR17검증병합뒤SAFchild전체gate를따른다. 이기반단독으로새SAF기능완료를주장하지않는다.

## 복구 기록

없음. 신규기반첫구현은위task명시범위만허용하고실패후숨은교정/재시도금지.
