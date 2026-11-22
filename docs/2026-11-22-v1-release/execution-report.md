# 1.0 후보 실행 증거

기능 1.0의 현재-head 전체 Native·격리 host, 독립 적합성 PASS·최종 PR 리뷰 APPROVE, 정상 개발 병합과 고정 서명 APK 확인을 완료했다. 이 파일은 확인한 원본과 남은 gate를 구분하며 실기기·계정·P0·TalkBack·개인 키 백업의 미확인을 바꾸지 않는다.

## 후보와 범위

- 검증 후보: `6efa2d20bcdf17c7925cdbaf86b8aff3afdd4058`, Android tree `5ac60a01b547634329ef625a6e05e950ef67f806`.
- 버전: `com.ny.my.health`, versionName 1.0.0 / versionCode 2 / minSdk 28 / targetSdk 36.
- 수동 Drive·로컬 SAF, 명시 opt-in 자동 Drive, 최대 7일 catch-up/최근 2일 refresh, 최초 외 최대 3회 영속 retry, 안전한 계정 proof와 보수적인 상태 표시를 연결했다. 자동 심박은 미입증 범위로 차단하고 다른 허용 항목은 독립 진행한다.
- 같은 현재 Drive binding의 off/on·항목 변경은 파일 추적과 불확실한 쓰기·소비한 예산을 보존한다. 과거 scope metadata는 현재 읽기 권한을 대신하지 않는다. 다른 계정의 과거 binding을 추정해 합치지 않는다.

## 확인한 실행

| 검증 | 고정 소스 | 실행·결과 |
|---|---|---|
| 현재 JVM/lint/APK | 6efa2d2 | [44978921947](https://github.com/nyeonu/health-app/actions/runs/44978921947) SUCCESS, 원본 JVM210/실패·오류·skip·중복0 |
| SQLite retry store | f4eb4cb | [44978002202](https://github.com/nyeonu/health-app/actions/runs/44978002202), API35·36 각각22/22, 실패·오류·skip·중복·누락·추가0 |
| 수동 Drive/SAF | f4eb4cb | [44978008345](https://github.com/nyeonu/health-app/actions/runs/44978008345), API35·36 각각10/10, 같은 오류 항목0 |
| 자동 설정·권한·화면 | 4839856 | [44974940147](https://github.com/nyeonu/health-app/actions/runs/44974940147), API35·36 각각32/32, 같은 오류 항목0 |
| 현재 전체 Native·격리 host | 6efa2d2 | [44978921906](https://github.com/nyeonu/health-app/actions/runs/44978921906), 양API 각각 Native186/실패·오류·skip·중복·누락·추가0 + history host8/4/8 + state host17/17, killed hold PASS0 |

공식 ZIP SHA256과 실제 XML/source method 집합을 대조했다. 현재 후보의 source inventory는193, 기존 host-only7개를 제외한 전체 Native 실행은186개다. 짧은 고정 진단은 전체 gate를 대신하지 않는다. f4eb4cb 이후 제품 소스는 동일하고, 6efa2d2는 기존 안내 테스트의 문장1개를 현재 계약에 맞게 정합화했다.

현재 JVM artifact14736817996 SHA256: `6068e863695d840f1a01a333265f05822af64e328923eddebb472a1d4b26bcbd`.

## 발견·교정 이력

[PR20 한국어 댓글](https://github.com/nyeonu/health-app/pull/20)의 이전 발견과 교정 이력을 보존한다. 동일 계정 off/on에서 uncertainty·retry가 새 generation에 보이지 않는 문제와 category 변경 거절 회귀를 교정하고 실제 SQLite·수동 세션으로 검증했다. 새 health 읽기 권한이나 자동 복구는 추가하지 않았다. 이월 ledger UUID/key는 PK 구분용이며 동결 범위·관측·count/version·상태/due를 보존한다.

이전 full44977958836은 성공이 아니다. API35는 Native 전 Maven Central UTP artifact HTTP429로 실행0/산출물 없음, API36은 prelude17 중16PASS/1FAIL로 과거 '자동 기능 미지원' 안내 단언에서 중단했다. 6efa2d2에서 실제 수동 중단·명시 복구 안내로 정합화했고 나머지 단언·반복·method를 유지했다. fe18ac2/80c5861의 전체 실행 취소도 통과로 기록하지 않는다. 이전 session44971406816의 fixture 확인시각 실패 역시 후속 실제 성공과 구분한다.

## 보안·의존성 및 한계

[보안 검토](security-review.md)와 [155개 실제 resolved Maven 좌표 감사](dependency-audit.json)를 따른다. 이후 dependency 입력6개 해시는 동일하다. f4eb4cb의 store/ledger 후속 정적 검토는 새로운 구체적 취약점을 확인하지 못했다. debug/release merged manifest에서 backup·cleartext 비활성과 최소 권한을 확인했다. 이는 완전한 취약점 부재나 실제 계정/provider 수용을 보장하지 않는다.

## 남은 실제 수용

별도 개인 키 백업·Galaxy/실계정/Doze/재부팅/저전력/TalkBack 및 기존 P0 미확인은 계속 pending이다. 원본 비교는 소유자가 확인한 범위를 유지하고 다시 미확인으로 바꾸지 않는다. 공개 출시나 Play 배포는 이 개발 후보에 포함하지 않는다.

## 최종 전체 원본 무결성

API35 artifact14738222569 SHA256 `d6bbd91783f78c7dda57f18036e218d6e5358a3d7a09989b9180494b2befac08`, API36 artifact14736864984 SHA256 `eb01c1a5f9238df39b5df622a9d411633db9fd033cd3b463aa3a2747b775102c`를 다운로드 ZIP과 대조했다. 양쪽 각각 XML17+166과 독립 프로세스의3개 protocol test를 실제 source inventory193에서 host-only7개를 제외한186 method와 정확히 대조했다. 격리 raw protocol을 동일 source parser로 재해석하여 업로드 JSON과 비교했고 중복·누락·추가0이다. history8 recovery/4 settings/8 cleanup, sync-state17 recovery/17cleanup, PID 변경·read-first·capture 및 killed holds countedPASS0을 원본에서 확인했다.

각 API artifact의 텍스트 대상 네 가지 고신뢰 key/token 형식 탐지는0, CSV 파일0, 합성 PNG70개다. 이는 포괄적 비밀정보/이미지 검사가 아니며 계정 없는 합성 CI의 제한된 artifact 검토다. 실제 건강값이나 사용자 credential을 공개 산출물로 추가하지 않았다.

## 독립 검증·병합·APK 전달

[격리 스펙 적합성](spec-conformance.md)은 PASS, [fresh-context 전체60파일 PR 리뷰](pr-review.md)는 APPROVE다. [최종 한국어 PR 댓글](https://github.com/nyeonu/health-app/pull/20#issuecomment-7073833100)에 정확한 head·발견·수정·재검토·원본 및 한계를 기록한 뒤 모든 현재-head 체크 SUCCESS/mergeState CLEAN을 확인하여 [PR20](https://github.com/nyeonu/health-app/pull/20)을 `1139553ce9edf0e5640ece469c6c8a246b696b84`로 정상 병합했다. override/admin bypass를 사용하지 않았다. 병합본 Android tree는 검증 후보와 동일하다. 과거 통합 PR의 각 gate 이력은 기존 실행 기록을 보존하며 이 검토가 그 과거 원본을 전부 다시 검증했다고 주장하지 않는다.

trusted master [44982594399](https://github.com/nyeonu/health-app/actions/runs/44982594399)는 고정 서명 APK를 배포했고 원본 JVM210/실패·오류·skip·중복0도 확인했다. 보고서 ZIP SHA256 `6031428d98e5346fbd0e478f8f3069dce659a73a7de392f48a345ea4a2f7e67e`, APK ZIP SHA256 `8dcdeaf88b31512bba681fa02ff91ca03b994344a9177f5f6cb8f13f720f6a30`이다. 실제 APK package/version은 com.ny.my.health/1.0.0/2이고 apksigner 검증·signing.txt·기존 trusted master75cc443의 인증서와 일치했다. backup/cleartext 비활성 및 추가 history/exact alarm/battery exemption 권한 부재를 실제 APK에서도 확인했다. [휴대폰 설치 안내와 파일 해시](../V1/1.0-installation.md)를 따른다.

완료 문서 PR은 Android/CI tree를 변경하지 않으므로 위 검증 tree와 동일성을 대조하며 앱 전체 CI를 새로 수행했다고 표시하지 않는다. 문서 PR 자체의 fresh-context 독립 검토와 한국어 댓글·정상 병합은 별도 수행한다.
