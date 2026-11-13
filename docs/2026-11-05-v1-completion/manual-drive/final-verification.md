# 수동 Drive 개발 검증

2026-11-13. [PR #17](https://github.com/nyeonu/health-app/pull/17)은 검토된 source `b35c11c8b4651cf9b12b004a7fc4ae3d52a4d3ee`에서 모든 소프트웨어 gate를 통과했고 `57da82514e8483f5f32f2ad34ae0111c7f1dc431`로 개발 병합했다. 전체 V1·실기기 수용·출시 승인은 아니다.

[JVM/APK/lint run43431524846](https://github.com/nyeonu/health-app/actions/runs/43431524846): core84+app92=176, 실패/오류/skip0. [API35/36 run43431523876](https://github.com/nyeonu/health-app/actions/runs/43431523876): 각각 Native120, 실패/오류/skip/중복0. critical14+remainder106의 유일 class+method를 합산했다. 각 API foundation host20, SyncState verifier17+cleanup17, complete=true/killed holds PASS0이다. 원본 artifact ID·SHA256·class counts는 [checkpoint](checkpoint-native.json)의 final_manual_drive에 있다. root 및 별도 검증자가 ZIP 해시와 XML/host를 직접 대조했다.

별도 세션 스펙 검증은 계획/다른 리뷰 결론 없이 승인 스펙과 구현을 비교해 수동 slice PASS로 판정했다. 최종 코드·CI 독립 검토 Critical/Important0·APPROVE이며 보안·성능 검토의 credential/late callback/진단 수명 지적은 수정·재검토·실제 회귀 실행으로 처리했다. [최종 검증·병합 적합성 댓글](https://github.com/nyeonu/health-app/pull/17#issuecomment-6854608943)에 과거 실패와 처리를 보존했다.

UI substring 선택자 2건은 exact 제목으로 교정했다. API36의 이전 시스템 Launcher ANR 포커스 실패는 앱 단언/timeout/skip을 바꾸지 않고 이번 동일 source 전체 실행에서 통과했다. CI의 줄별 shell 계약은 단일 Bash helper와 실제 action 형태의 stub 검증으로 보정했다.

새 reconciliation 원격 경로의 실제 지연/전체 admission 비용은 미측정이다. 이전 benchmark를 새 경로의 개선율로 재사용하지 않는다. 실제 Google/Galaxy/HC provider·TalkBack·Doze/배터리·남은 P0 수용 및 개인 서명키 복구 백업 확인은 pending이다. ADR007은 worker 인증 검증을 포함하므로 수동 milestone만 기록하고 자동 단계 전 Proposed를 유지한다.
