# 스펙 적합성 검증

> 검증·리뷰 시점의 원문이다. 이후 [최종 한국어 댓글](https://github.com/nyeonu/health-app/pull/20#issuecomment-7073833100)을 기록하고 PR20을1139553으로 정상 병합했다. 검증 source6efa2d2와 병합본 Android tree는 동일하다.

- 대상 스펙: `docs/2026-11-22-v1-release/spec.md`
- 대상 커밋: `6efa2d20bcdf17c7925cdbaf86b8aff3afdd4058`
- 판정: PASS (현재 head의 기능 및 자동화 검증; 병합과 실제 수용은 별도)

## 기준별 결과

| 성공 기준 | 판정 | 실행 증거 |
|---|---|---|
| 수동 Drive와 로컬 SAF의 기존 동의·권한·목적지·복구 동작을 유지하고 background 작업은 SAF를 사용하지 않는다. | PASS | `44978921906` API 35·36 전체 Native 각 186건 통과. 수동 Drive category 추가·제거, 새 foreground proof와 SAF 흐름의 실제 단언을 확인했다. |
| 자동 Drive는 명시적 opt-in 전까지 꺼져 있고 background 기능 지원과 background 및 각 category 권한을 요구한다. | PASS | 현재 head JVM `AutomaticHealthReaderTest`, `AutomaticSettingsTest`와 양 API 설정·권한 Native 통과. 개별 grant 부재 시 차단과 다른 허용 범주의 진행을 단언한다. |
| 지원 요약은 Health Connect Aggregate를 사용하고 체중은 최신 실제 기록이며 결측은 결측으로 둔다. 미입증 운동 심박은 BLOCKED로 표시하고 다른 허용 category는 독립 진행한다. | PASS | 현재 head JVM `AutomaticHealthReaderTest`에서 세 종류 Aggregate 요청, null 보존, 실제 최신 체중, 심박 BLOCKED와 독립 범주 진행을 단언하고 통과했다. |
| 자동 작업은 connected 및 battery-not-low 제약의 24시간 unique WorkManager 일정과 최근 2일 refresh를 포함한 최대 7일 catch-up을 사용한다. | PASS | 양 API `SyncWorkSchedulerInstrumentedTest`는 24시간, unique 및 양 제약을 단언한다. JVM `SyncPlannerTest`는 최근 2일과 최대 5개 gap을 단언한다. |
| 영속 retry lineage는 최초 실행 외 최대 3회 재시도하며 새 작업이나 원본 revision은 미완료 budget을 초기화하지 않는다. | PASS | JVM `SyncRetryBudgetTest`와 양 API SQLite store 및 세션 테스트가 최대 3회, revision 변경, off와 category 변경 후 budget 보존을 단언하고 통과했다. |
| 수동 실행과 worker는 동일 canonical gate를 사용하며 worker는 health record 조회 전에 선택 계정의 새 silent 인증과 proof를 수행하고 UI를 띄우지 않는다. | PASS | 양 API `SyncExecutionSessionInstrumentedTest`의 공용 gate·소유자 단언과 JVM `BackgroundDriveAuthorizationTest`의 계정 고정·proof 선행·UI 미실행 단언 통과. |
| 영속 metadata는 재시작 후에도 확정 결과와 불확실한 create 및 취소 상태를 보존하며 blocked·stale·손상·미확인 상태를 완료로 표시하거나 자동 초기화하지 않는다. | PASS | 양 API 전체 Native와 격리 프로세스 복구 검증 통과. SQLite 재개봉·취소·불확실한 create·이전 확정 결과·stale owner 단언을 확인했다. |
| 설정은 명시적 opt-in·끄기·reset과 함께 queued·blocked·coverage gap을 표시하고 끄기나 목적지 변경 시 이전 작업을 안전하게 무효화한다. | PASS | 양 API UI·SQLite 테스트와 JVM settings 테스트 통과. off 후 미확정 create·budget 보존, 이전 owner 거절, opt-in·reset과 queued/blocked 구분을 단언한다. |
| 각 통합 PR 병합과 최종 1.0 전에 현재 head의 API 35·36 전체 Native와 기존 격리 host, JVM, lint 및 APK 빌드가 통과하며 테스트 삭제·skip·완화는 없다. | PASS | 현재 head `44978921906` 양 API 각 Native 186건, 실패·오류·skip·중복·누락 0; 양 API 격리 host 프로토콜 각각 20+34 검증. `44978921947` JVM 210건 실패·오류·skip 0, lint 및 APK 성공. 수정된 수동 UI 테스트는 기존 단언을 실제 복구 안내 단언으로 교체했고 다른 단언은 유지했다. 과거 각 통합 PR의 게이트 이력 전체는 이 격리 검증의 입력이 아니다. |
| 독립 적합성 검증과 새 세션 PR 리뷰 후 병합하며 수행하지 않은 실기기·계정·P0·접근성 수용은 pending으로 남긴다. | PASS | 본 독립 검증 및 오케스트레이터가 확인한 새 세션 PR 리뷰·PR 댓글 절차. PR은 아직 미병합이며 최종 리뷰·병합 순서는 오케스트레이터가 이 판정 이후 확인한다. README는 실기기·계정·P0·TalkBack 수용을 미완료로 명시한다. |
| APK 후보는 versionName 1.0.0 및 1보다 큰 versionCode를 사용하고 간결한 설치 안내와 미완료 수용 항목을 제공한다. | PASS | `android/app/build.gradle.kts`는 `1.0.0` 및 `2`, 현재 head `44978921947` APK 빌드 성공. README에 설치와 남은 수용 항목이 있다. |

## 계약 준수

HC feature/background/category 권한, Aggregate, Drive 선택 계정의 silent 인증과 proof, DB preflight 후 계획·claim 순서 및 off·due 이전·stale·미해결 복구의 외부 호출 차단을 JVM과 양 API Native의 합성 의존성 단언으로 확인했다. 새 외부 API는 도입되지 않았다. 실제 Google 계정과 실제 Health Connect 제공자에 대한 수용은 별도다.

## 추적성

격리 원칙에 따라 계획 문서를 검증자에게 제공하지 않았다. 오케스트레이터가 11개 기준과 실제 테스트 28개를 대조해 누락·skip·오래된 이름이 없다고 확인했다. 본 검증자가 증거로 쓴 핵심 테스트는 이름뿐 아니라 단언도 읽었다.

## 경계 위반

확인된 위반 없음. 실제 수용을 자동화 결과로 대체하지 않았다.

## 실패 상세

현재 head에서 확인된 실패 없음. 이전 `fe18ac2`의 off→on 상태 유실 경로와 `80c5861`의 동일 계정 category 변경 거부 경로는 수정됐고 현재 head 양 API SQLite와 수동 세션 테스트에서 보존 및 정상 변경 단언이 통과했다. 이전 `f4eb4cb` 전체 실행 `44977958836`은 API 36에서 오래된 UI 문구 단언 한 건이 실패했고 API 35는 Maven HTTP 429로 테스트 시작 전 중단됐다. `6efa2d2`에서 실제 복구 안내 문구 단언으로 갱신한 뒤 양 API 전체 실행이 통과했다. 이전 결함의 실패 재현을 이 검증자가 직접 실행하지는 않았다.

## 남은 불확실성

실제 Galaxy 기기, 실제 Google 계정·Health Connect 제공자, P0, TalkBack 및 복구 가능한 개인 백업 수용은 미확인이다. 합성 provider와 에뮬레이터는 실제 백그라운드 실행 시각이나 계정 접근을 입증하지 않는다. 과거 통합 PR별 gate 이력과 최종 PR 병합 순서는 오케스트레이터의 별도 확인 대상이다.
