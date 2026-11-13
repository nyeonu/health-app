# SAF 전경 수동 내보내기 소프트웨어 검증

PR #18의 검증 소스는 `bbde0da5efbc6a6363bdd5f033128e5e2f5770b5`다. 전경 local 구현과 독립 스펙 검증은 통과했으며, 최신 PR head의 최종 품질·보안 검토와 reviewed merge는 별도 단계다. 전체 V1 완료나 실제 기기 수용 기록은 아니다.

## 현재 실행 증거

| 실행 | 실제 결과 |
|---|---|
| [JVM·lint·APK 43513510015](https://github.com/nyeonu/health-app/actions/runs/43513510015) | core 84 + app 97 = 181 unique. 실패·오류·skip·중복 0. PR의 trusted-branch signing/distribution은 실행하지 않았다. |
| [전체 Native 43513509886](https://github.com/nyeonu/health-app/actions/runs/43513509886) | API 35·36 각각 critical 15 + remainder 117 = 132 unique. 실패·오류·skip·중복 0. 실제 class/method 집합과 원본 ZIP digest를 대조했다. |
| 같은 실행의 기존 host | 각 API에서 history recovery 8 + 화면 설정 4 + cleanup 8 = 20. sync-state recovery 17 + cleanup 17. PID 변경·read-first와 killed hold의 성공 산입 0을 확인했다. |
| 로컬 통합 명령 | 제품·unit 소스가 같은 `73f8705`에서 core/app JVM·lint·debug APK·testAPK 1분18초 통과. 이후 변경은 Native 테스트 한 파일의 Activity 왕복 관측이다. 그 최종 파일도 app97/testAPK 30초 및 root 재확인 13초에 통과했고 현재 CI가 통합 소스 전체를 실행했다. SDK 분리 core 명령도 통과했다. |

| artifact | SHA-256 |
|---|---|
| JVM `14080046202` | `666fb6092b8c14e82c934ffdd0353336f41cdd50b59aa70de4890f7c23587dbd` |
| API 35 `14080787426` | `6c15a6f2dc5fde714c8a0bec5cbcc00c616b9728e6feccba2ecb4725d5234d84` |
| API 36 `14079484601` | `00077446a0fbeb24ed7395e5f27bb42aa8a20da4defbf1f66bde0aaec9241e1c` |

승인 스펙과 구현·원본 실행 증거만 받은 새 Terra 세션의 SAF 소프트웨어 범위 판정은 PASS다. 계획·이전 검토 결론은 제공하지 않았다. 실제 테스트 단언과 원본 XML을 기준으로 권한/probe, 동일 파일/readback, SQLite 재열기·세대 격리, 명시적 전경 실행, Drive 경계, metadata-only 및 큰 글자 semantics를 확인했다. 계획의 실제 테스트 이름 대조는 오케스트레이터가 별도로 수행했다.

## 보존한 실패·수정 이력

- `209fd02` / run `43499702883`: 양 API의 새 configuration 테스트가, 저장소를 직접 바꾼 뒤 원래 Drive 세션을 갱신하지 않은 수명주기 전제로 실패했다. 유효 credential 준비, 실제 이전 세대 실행의 무IO·상태 보존과 refresh 후 ticket 거부를 명시 검증하도록 보완했다.
- `f25551a` / run `43501918822`: 세대 불일치의 기대값을 `BLOCKED`로 잘못 지정해 실패했다. 실제 실행 계약인 정확한 `STALE`로 교정했다. 실패 후 단언과 후속 suite를 통과했다고 기록하지 않았다.
- `527cad5` / run `43503457186`: API 36 전체·host는 통과했으나 API 35 positive SAF flow가 완료 대기 timeout으로 실패했다. 조회용 Activity의 실제 복귀·metadata 완료 및 enabled인 실제 UI 클릭의 작업 완료를 관측하도록 보완했다.
- `73f8705` / run `43507441190`: API 35 전체·host는 통과했으나 API 36 negative flow의 권한 철회 반영 단언이 실패했다. 음성 시나리오에도 겹친 조회 Activity가 관측돼 모든 standalone 조회·철회를 동일한 실제 왕복 완료 경계로 연결했다. 직접 원인은 확정하지 않았으며 이후 현재 소스로 양 API 전체를 다시 통과했다.

기존 단언 삭제, skip, timeout 증가, 임의 sleep, VM 직접 호출로 실제 UI 클릭 대체, 제품 권한 완화는 하지 않았다. 실제 picker·지연 callback·의도적 두 번째 pause, 권한 0·provider mutation 0·건강 읽기 0·Drive fallback 0, 독립 CSV bytes와 SQLite 단언을 유지했다. 검토·수정·재검토 과정은 [PR 댓글](https://github.com/nyeonu/health-app/pull/18)에 남겼다.

## 증거의 경계

실제 Android Activity·권한/별도 UID·ContentResolver와 합성 DocumentsProvider를 사용한 증거다. 실제 Galaxy, 외부 provider, persisted grant의 실제 reboot, Doze·배터리, TalkBack, 실계정·background 및 기존 미확인 P0·별도 개인 서명키 백업은 미확인으로 유지한다. 로컬 폴더가 비공개이거나 백업·다른 앱 접근으로부터 격리된다고 주장하지 않는다.

정상 provider의 실제 UI retry→동일 파일 채택과, 지속 create fault에서 fresh capability probe가 거부되어 unknown을 보존하는 경우를 구분한다. 후자를 journal reconciliation 성공으로 기록하지 않는다. Component 실행은 현재 전체 검증을 대신하지 않으며, 자동 WorkManager·통합 자동 설정/UI·V1 최종 의존성 재감사는 후속 단계다.
