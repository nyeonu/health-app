# PR #20 독립 리뷰

> 검증·리뷰 시점의 원문이다. 이후 [최종 한국어 댓글](https://github.com/nyeonu/health-app/pull/20#issuecomment-7073833100)을 기록하고 PR20을1139553으로 정상 병합했다. 검증 source6efa2d2와 병합본 Android tree는 동일하다.

**판정: APPROVE**

**검토 head:** `6efa2d20bcdf17c7925cdbaf86b8aff3afdd4058`  
**기준:** `75cc443702a3467c87268d49ce52d4d75b2ed8a5`  
**범위:** 전체 관련 diff 60개 파일. `80c5861`의 전체 59개 파일과 이후 `f4eb4cb`, `6efa2d2` 변경을 순차 검토했다. 이 판정은 해당 head의 개발 병합 적합성에 대한 것이며 실제 기기·계정 수용이나 출시 승인이 아니다.

## 현재 발견 사항

남은 Critical 또는 Important 없음. 아래 이전 Important 두 건은 수정과 현재 head 회귀 검증을 확인했다. 선택적 제안 없음.

## 검토 및 검증 근거

- 승인된 `docs/2026-11-22-v1-release/spec.md`, 부모 스펙의 접근·coverage·복구 계약, `AGENTS.md`와 code-review-and-quality 스킬 및 보안·백엔드/프런트 성능·국소 추론 체크리스트를 읽었다. 테스트를 먼저 검토하고 실제 assertion과 SQLite 재개방·rollback·race·불확실한 쓰기, 소유권·재시도·마이그레이션, 수동 Drive/SAF, SDK 권한·계정 proof, WorkManager, 화면과 CI 경로를 확인했다.
- API 35·36 전체 Native 실행 `44978921906`: 각 API에서 source inventory 193, Native 186(critical XML 17 + 나머지 XML 166 + 격리 3), 실패·오류·skip·중복·누락·추가 0. 양쪽 모두 history host recovery 8/settings 4/cleanup 8 및 sync-state recovery 17/cleanup 17을 확인했고 killed hold를 PASS로 센 건 0이다. 공식 ZIP digest를 대조하고 source-derived `/workspace/tooling/verify-native-evidence.py`를 각 원본에 직접 재실행했다. API35 SHA-256 `d6bbd91783f78c7dda57f18036e218d6e5358a3d7a09989b9180494b2befac08`, API36 `eb01c1a5f9238df39b5df622a9d411633db9fd033cd3b463aa3a2747b775102c`.
- JVM 실행 `44978921947`: ZIP SHA-256 `6068e863695d840f1a01a333265f05822af64e328923eddebb472a1d4b26bcbd`; XML 직접 집계 core 94 + app 116 = 210, 실패·오류·skip·중복 0. 독립 적합성 결과는 같은 실행의 lint와 APK 성공을 기록한다. `versionName`은 1.0.0, `versionCode`는 2다.
- 독립 스펙 적합성 검증 `/workspace/v1-final-spec-conformance.md`는 동일 SHA에 PASS를 기록했다. 검증자는 이전 리뷰와 계획을 입력받지 않았으며, 실제 기기 수용 및 과거 각 통합 PR의 gate 이력은 이 판정의 범위 밖이라고 명시했다.
- CI 스크립트 `bash -n` 및 관련 head 간 `git diff --check` 통과. 의존성 감사의 155 Maven 좌표·오류/매치 0 기록과 빌드 입력 해시는 현재 소스와 대조했다. OSV 원본 응답은 이 리뷰에서 재조회하지 않았다. password-storage와 웹 성능 체크리스트의 외부 최신성은 이 Android 기능 판정에 사용하지 않았다.

## 이전 발견 사항과 처리

1. **Important — `fe18ac2` opt-out 후 불확실한 create와 예산 우회.** `SqliteSyncStateStore.kt`가 새 generation에서 이전 미확정 create 및 retry lineage를 조회하지 않아 같은 Drive에서 새 create나 새 retry 예산이 허용될 수 있었다. 수정된 경로는 동일 현재 Drive binding의 날짜·파일 확인·불확실 상태와 미완료 budget을 새 generation으로 원자 이월하고 이전 callback을 무효화한다. SQLite의 rollback, unknown create, stale callback, due·소진 보존 단언이 최종 양 API 전체 실행에 포함되어 통과했다.
2. **Important — `80c5861` 동일 계정의 수동 category 변경 차단.** 기존 기록이 있으면 configure를 거절해 사용자가 항목을 추가·제거할 수 없었다. `f4eb4cb`는 동일 binding에서 등록일·과거 profile·known ID·불확실 상태 및 미완료 budget을 보존하면서 현재 category를 변경하고, 새 claim에는 현재 category subset을 요구하도록 수정했다. 실제 SQLite와 수동 session의 추가·제거·새 proof·stale callback·rollback·budget 보존 테스트가 최종 양 API에서 통과했다. 이력 generation에 없는 계정 binding을 임의로 추정하지 않는다.
3. `f4eb4cb` 전체 실행에서는 API36의 기존 UI 테스트가 더 이상 존재하지 않는 안내 문구를 기대해 실패했고 API35는 Native 전 Maven HTTP 429로 중단됐다. `6efa2d2`는 같은 위치의 기대 문구를 실제 중단·원격 불확실성·명시 복구 안내로 교체했다. 다른 assertion·반복·test method의 삭제, skip 또는 완화는 확인되지 않았고, 이후 현재 head 양 API 전체 실행이 통과했다.

## 한계와 후속 절차

실제 Galaxy S23 Ultra·Google 계정·Health Connect provider·Doze/재부팅·TalkBack·P0 및 복구 가능한 별도 서명키 백업 수용은 미확인이다. 에뮬레이터와 합성 provider는 실제 예약 시각이나 계정 접근의 증거가 아니다. 이전 통합 PR 각각의 gate 이력 전체는 독립 적합성 검증 범위에 포함되지 않았다. 소유자 지침에 따른 한국어 PR 리뷰 댓글과 최종 head 기록은 별도로 수행해야 하며 이 로컬 보고서는 이를 대체하지 않는다.
