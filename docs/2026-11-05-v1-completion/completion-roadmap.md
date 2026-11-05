# V1 completion roadmap

2026-11-05 소유자의 **“병목 개선부터 V1 끝까지 자동승인으로 진행”**을 근거로 기존 V1 개발 범위를 끝까지 수행한다. 범위·성공 기준의 원본은 [이번 에픽 스펙](spec.md)이며, 이 문서는 순서와 완료 경계를 설명한다. 실행 계획/TASK 목록은 다음 세션의 `plan.md`에 작성한다. 기존 implemented 스펙을 수정하거나 계정 로그인/실기기 결과/출시 승인을 대행하지 않는다.

## 현재 출발점

master `20baaa4`에는 P0 manual yesterday/private Drive CSV, V1 foundation SQLite 이력·복구/상태 UI, 안전한 profiling과 상단 layout 교정이 있다. `docs/implementation-plan.md`의 full V1 날짜별 coverage·최근 2일·누락 복구·자동 schedule·SAF는 아직 구현되지 않았다. README의 memory-only/unstarted 등 과거 문구는 현재 code와 최신 V1 증거보다 우선하지 않는다. 과거 foundation history는 계정이 없는 과거 증거이고 새 coverage로 migration하지 않는다.

## 검토 가능한 개발 묶음

| 순서 | 범위와 완료 형태 | 주요 코드 경계 | 선행 조건/독립 증거 |
|---|---|---|---|
| 1 | PR #15 성능 개선: 별도 승인 스펙대로 반복 Drive request를 줄이고 합성 비교 및 한계 보고 | `PrivateDriveUploader`, `SyncCoordinator`, profiling/local TLS tests | 성능 스펙·계획·실제 계측 기준선; 최신 head 독립 code/security/performance/conformance 검토 |
| 2 | per-date/destination core와 private durable store, 어제 enrollment, revision·EMPTY·history floor·단일 admission, recent2+oldest gaps 최대7 계획 | `core/Model`, `Ports`, 새 planner/state; app versioned store; 기존 history 분리 보존 | 실제 store 재열기/중단·동시성·schema 복구; token/CSV staging 없음 |
| 3 | 전경 수동 bounded catch-up와 SAF local 선택·privacy consent·persisted grant·probe·짧은 overwrite/readback | `SyncViewModel`, destination adapter, tree picker/resolver, `HealthHomeScreen` | 합성 provider 테스트와 capability별 기본 disabled; 실제 local/provider 수용 별도 대기 |
| 4 | 명시적 자동 opt-in·AccountPicker/선택 Account 고정·무상호작용 authorization adapter·WorkManager | app configuration, credential adapter, unique periodic/catch-up `CoroutineWorker`; manifest/dependency | 공식 API/설치 SDK 확인, 호환 WorkManager/test dependency·audit; resolution/HC background 실패 BLOCKED |
| 5 | 통합 상태·접근성·설정 해제/초기화·보안/회귀 증거 및 roadmap 실제 상태 반영 | `HealthHomeScreen`, lifecycle/work/store integration, UI/worker tests | APK/lint/JVM/emulator/host 검증, 각 PR 작성자/수정자와 다른 fresh session 검토·업데이트 head 재검토 |

표는 실행 순서 설명이며 구현 task plan이 아니다. 의미 있는 각 묶음은 승인된 spec을 입력으로 별도 계획·구현·리뷰·적합성 검증·한국어 PR 증거를 갖춘다. 자동승인이 있어도 리뷰 발견과 최신 head 재검토를 생략하지 않는다. 전체 에픽 승인과 개별 PR의 검증 완료를 혼동하지 않는다.

## 고정한 수행 정책

- 새 목적지/계정 generation은 구성 시 한국 시간 어제부터 시작한다. 임의 30일/전이력 bootstrap은 없다. 최근 두 날짜도 enrollment 이후만 포함한다.
- 한 trigger는 recent2를 먼저 확보하고 오래된 eligible 누락으로 채운 최대7 고유 날짜를 오래된 순서로 실행한다. 남은 날짜는 pending이고 한 worker의 무제한 loop는 없다.
- 앱 시작은 자동 off이면 감지/표시만 한다. 자동 on이면 공용 admission을 사용하는 unique catch-up을 enqueue한다. manual permission/Google authorization/별도 continuation 계약과 자동 opt-in은 구분한다.
- HC 기본 floor는 최초 grant 이전30일이며 오늘 기준 rolling30일이 아니다. 앱은 그보다 보수적인 매 실행 한국 시간 오늘-30일 read floor로 범위를 좁히고 이전 gap은 HISTORY_ACCESS_REQUIRED로 보존한다. 실제 최초 grant API나 추정 시각을 만들지 않는다. history 권한 확대는 후속 범위다.
- `EMPTY`, 제한 category 확인, last upload, 연속 checked/uploaded-through, stale/gap/blocked를 구분한다. 변화 없는 semantic revision만 확인을 재사용하고 dataset 소멸은 REMOTE_STALE을 남긴다; 자동 remote delete는 없다.
- 자동 schedule은 약24시간·network connected·battery-not-low·최초 외 최대3회 transient retry다. worker interactive UI/token persistence/다른 계정 fallback을 금지한다. 정확한 시각/매일 성공은 보장하지 않는다.
- SAF는 local 실제 capability를 검증하고 cloud provider/미검증 background는 disabled이다. both mode는 기존 설계대로 later로 유지한다. Drive API와 SAF는 사용자가 하나를 명시적으로 구성하는 선택지다.

## 완료 보고의 경계

**개발 완료**는 위 scope의 source·자동 의미 검증·build/lint·최신 head 독립 검토와 수정/재검토가 끝나고 제한을 문서화한 상태다. 기능별 지원 불가/미검증 capability는 비활성 상태와 다음 동작으로 드러나야 한다. 코드가 없는 상태를 기기 미확인이라는 이유로 개발 완료라 부르지 않는다.

**물리 수용 완료**는 실제 Galaxy S23 Ultra Android16, 선택 Google 계정 OAuth/Drive privacy/idempotency, local SAF/provider persist/reboot/짧은 overwrite/오프라인/중단, background HC, Doze·low battery·reboot·force-stop·누락일·늦은 원본 도착·배터리 측정, 실제 TalkBack을 해당 증거로 확인한 상태다. 계측/에뮬레이터는 그 결과를 대신하지 않는다. 수행하지 못하면 개발 완료/physical acceptance pending으로 보고한다.

기존 [V1 기기 수용](../V1/device-acceptance.md), [SAF 7개 시나리오](../V1/storage-destinations.md), [P0 current acceptance](../P0/device-acceptance.md)는 실제 실행 전 미확인을 유지한다. 소유자 자동승인은 시스템 권한/계정 로그인 자동 승인도, 미실행 테스트의 PASS도, signing-key 별도 복구 백업 증거도 아니다. 개발 병합과 release 판단을 분리한다.

## 이번 문서 작업의 확인

AGENTS/README/implementation-plan/architecture/data-contract/V1 문서와 baseline code를 직접 조사했다. spec-writer 공식 template를 적용하고 공식 Android/Google Context7 문서 및 설치 HC1.1.0 source/auth21.4.0·basement18.5.0 AAR API를 확인했다. `javap`는 source/API 가용성 조사이며 실제 계정/기기 실행이 아니다. 문서 두 개만 작성했고 Gradle·제품 코드 변경·계정 로그인·commit은 수행하지 않았다.
