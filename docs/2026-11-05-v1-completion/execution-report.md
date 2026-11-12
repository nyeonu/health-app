# V1 개발 실행 기록

이 문서는 진행 증거이며 V1 수용 완료나 출시 승인이 아니다. 소유자의 자동승인 범위에서 구현·독립 검토·개발 병합을 진행한다. 실제 Galaxy/Google/SAF provider/Doze·재부팅·TalkBack의 미실행 항목은 pending이다.

## 선행 작업

- PR #14의 앱 최초 진입/맨 위 제목과 날짜 가림: 소유자 답변 “정상으로 보여”. 사용자 관찰 결과이며 새 V1 화면의 접근성 전반 통과로 확대하지 않는다.
- 성능 PR #15: master `bf071e19e4e52c285e075b3dfe3db09e1bda8135`로 병합. 현재 worktree에는 `1cbf860c6c431edd437a5d039fe1d36eafade2f6`로 반영했다. 성능 source·별도 검토·합성 local TLS·API35/36 증거는 해당 PR 댓글/문서가 원본이다.
- 고정 서명 APK: [trusted master build](https://github.com/nyeonu/health-app/actions/runs/41959514365), [artifact](https://github.com/nyeonu/health-app/actions/runs/41959514365/artifacts/13385245747). 다운로드한 ZIP digest/실제 APK 서명/기존 certificate 동일/package를 확인했다. APK SHA256 `e6f1f55adc442498f900a9e678446ae0630ac41258aa0db6731742165ef50f31`. 새 바이너리의 실기기 업로드 성능 측정은 아직 없다.

## core-store 진행

| 단계 | 실제 검증 | 상태·한계 |
|---|---|---|
| T1 모델/planner | canonical scope literal vector, 등록일·KST·최대7·범위별 checked/uploaded 구분; core45/실패0·오류0·skip0 | done; 기본 복구2회 뒤 소유자 자동승인에 따른 추가 정합성 교정1회는 child plan에 별도 누적 |
| T2 revision/codec/reducer | schema0.1 포함 literal EMPTY digest/4dataset 이름순; core49/0/0/0 | done; recovery1 protocol 보정 |
| PR15 병합 후 통합 | core63+app74=137/0/0/0, APK/lint/testAPK 2분21초 | 그 시점 source에 대한 검증; 이후 변경의 통합 검증을 대신하지 않음 |
| T2 recovery2 | unscoped unfinished/finished attempt·구성 categories·정확한 journal/CAS/start 거부 fixture; core64/0/0/0 | 실행자9초 PASS; root 명령7초 입력동일 UP-TO-DATE와 실제 XML 대조. source 수정은 `947b0c7` 포함 |
| T3 reconciliation recovery1 | 모든 intended 파일 재조회, 과거 확인은 prior로 보존, unsafe/moved/shared/absent/조회실패로 과거 성공 재사용 금지, healthy 무쓰기; target 실행자/root 각20초 PASS | recovery2의 typed failure 보정 진행 중. 최신 전체 head 통과를 아직 주장하지 않음 |
| T4~T6 | 실제 SQLite와 OS force-stop 검증 필요 | pending; 로컬 환경 emulator/KVM 없음. API35/36 CI의 실제 XML·host evidence 필요 |

T3 오류 분류의 durable contract는 상위 `980a38b`에 기술 보정했고 child parent revision을 재검토·고정했다. generic PENDING은 자동 재시도 권한이 아니다. 실제 provider 오류 매핑·worker retry budget 소비는 각각 후속 작업에서 검증한다.

## 미완료 검증

core-store의 현재 head JVM/APK/lint, SQLite instrumentation, 별도 process-kill harness, 독립 스펙 적합성·코드·보안 검토와 한국어 PR 댓글은 완료 후 실제 증거로 갱신한다. [PR #16](https://github.com/nyeonu/health-app/pull/16)을 draft로 열었으며 개발 병합/전체 V1 완료를 주장하지 않는다. 기존 P0 미확인 항목은 [readiness](../P0/pr-readiness.md)의 상태를 유지한다.

core-store 후속: T3 typed failure 수정의 root 통합 JVM138(core64/app74)/실패0·오류0·skip0 + APK/lint/testAPK는1분31초 PASS. 그 뒤 독립 사전 계약 검토가 발견한 finished scope 전환(T2 추가교정3)과 자정 지연floor(T3 추가교정3)를 보정했다. 마지막 root core64/0/0/0은9초 PASS이며 read 직전 floor와 종료시점 coverage today를 재확인한다. 각 기본2회 한도 사용 뒤 추가 교정은 소유자의 자동승인 위임에 따라 누적 표시했고 회차를 초기화하지 않았다. T3 done/T4 in_progress. Android 재통합·실제SQLite·최종PR 독립 검토는 여전히 후속이다.

T3 trigger policy 추가 정합성: 같은 reader/engine에서 MANUAL→SCHEDULED→MANUAL이 다른 요청 scope를 선택하도록 access(trigger) 인자를 필수화했다. 자동 HR은 WORKOUT_SCOPED이며 background unavailable 자동 시도는 source/remote IO 없이 STALE, 이후 수동은 가능함을 합성 검증했다. target33/0/0/0(22초), root 전체core64/0/0/0(10초). 실제 자동 HC reader/권한 admission은 후속이다. T4 최초 전체 build는 nullable core public property의 app-side smartcast 오류로25초 FAIL했고, 같은 행동을 유지한 local binding 교정 뒤 재검증 중이다. SQLite/전체 V1 완료를 아직 주장하지 않는다.

T4 source checkpoint `c637b195035b317bc4897c300e9fbefff3c6bb5c`: 마지막 source 전체를 root 명령으로1분37초 검증했다. core64+app74=138/0/0/0, APK/lint/testAPK PASS. 별도 의미 있는 SQLite instrumentation12개가 compile됐으나 실제 실행은 아직 미완료다. CAS 부수파일 무생성, blocker/stale/prior 보존, scalar 타입, generation cap, 자동off reset, exact empty stage, atomic owned-marker 퇴역과 unknown symlink 보존 fixture를 포함한다. 기본2회 뒤 owner-auto 교정3~8은 childplan에 원인/파일/검증을 누적 기록했으며 초기화하지 않았다. 실제SQLite CI 및 T5/T6/독립최종리뷰가 남아 있으므로 T4는 in_progress다.

PR16 첫 NativeCI [41967950562](https://github.com/nyeonu/health-app/actions/runs/41967950562), head c2b49a2: 양API35/36 XML 각69tests/1failure/0errors/0skipped. SQLite12개중11PASS이며 row-malformed fixture raw locale open의 android_metadata 부수생성으로 기대CORRUPT보다 schemaUNSUPPORTED가 먼저 발생했다. 공식AOSP15 SQLiteConnection locale 동작을 대조했고 raw4곳에 production의NO_LOCALIZED_COLLATORS를 적용한다. schema/기대/skip은 완화하지 않았다. 기존host는Gradle실패로 미실행이다. ZIP SHA256: API35 e669775964d3bb879e959b770df2b9bc92759735522ecfefa465bdfbe6f868cb, API36 f71a8d850b1d16ac2f1f9544d06cbab60b732a78de1b270c28781662a7cffd25.

별도 fresh-context 검토는 초기구성·손상상태 일반쓰기·reset초기중단 지적을 남겼으며 [PR댓글](https://github.com/nyeonu/health-app/pull/16#issuecomment-6649790858)에 각각 보존했다. 보안scope 차단취약점 없음/secret scan184commits 탐지0과 metadata N+1 P2측정요구는 전체승인이 아니다. source 7625f9728331f29be9356fcc784057b5bde646a9의 회귀 probe와 Android metadata 측정1개를 root 전체명령1분49초에 compile/JVM138/0/0/0·APK/lint/testAPK로 확인했다. Native회귀 실패→fix후 통과/성능관측72개와최종head재검토는 아직대기다.

독립 지적 재현 probe [41970834390](https://github.com/nyeonu/health-app/actions/runs/41970834390), head d158981: 양API89개/skip0, SQLite31개중같은16개새회귀가실패했다. 기존12개는PASS여서 rawlocale fixture교정도 확인했다. API35는GoogleSdkSetup ANR에 따른UI3개 추가실패(전체19fail), API36은16fail이며 실제PNG/stack으로분리했다. ZIP digest API35 f426e09e23141daf9d17fb80ed1ac8bdbc36fd3944b0555061f32dd84c212014, API36 997be89adc846549f5649207c0cbae5c0fb8ecbf9695324f1324c34d00b30b92. 이전source의실제실패를확인한뒤 fix4d691f4를공개하며 수정후Native를재검증한다. sourceassert31개는그대로이며fixture정리prefix만새비활성파일에맞춰추가했다. root최종full14초 JVM138/0/0/0·APK/lint/testAPK PASS는NativePASS가아니다.

T7 actual측정은양API각1 testcasePASS·72관측으로 [baseline보고](core-store/store-performance.md)에보존했다. FULL_EXPLICIT365행confirmation 반복3median은API35/36 약722/1003ms,3650행6854/9632ms로metadata반복조회비용이확인됐다. 기존안전검증을유지한T8 streaming/narrow읽기보정을계획했으며T4실제통과후T5와파일이겹치지않게실행한다. Session통합테스트는별도class로분리하고상위plan을재검토·pin했다. CIbootstrap4a65f1a는계정없는테스트에서미사용GoogleSDKsetup의존재를확인해한번종료하며global ANR/permission/이미지/timeout/assertion/skip을바꾸지않는다. actualfullCI 확인전승인이아니다.

79ffd actualCI41975679263은양API 모두emulator-runner가script각줄을개별sh -c로실행하여새multiline if 첫줄에서EOF/fi오류로Native전에중단했다. 실제test0/산출물0이며수정store통과/실패증거로쓰지않는다. 원인은source4d와무관한검증scriptcompatibility다. 한줄완결guard7a6c954로수정했고root의line별sh -n·mockexact/other/absent stop1/0/0을확인했다. 이미지/permission/timeout/assertion/skip/host는그대로이며새양API결과를확인한다.

2026-11-12 재개: head cb89da7783eb8420fca0440cf7b29e61955bca95, [NativeCI41977482571](https://github.com/nyeonu/health-app/actions/runs/41977482571) 양API SUCCESS, 각89/0/0/0·store31/31·performance1/1+72관측·기존host20/죽인holdPASS0을root가원본XML/결과로확인했다. APK/JVM CI41977482722도SUCCESS. T4/T9 done으로전환하고T5/T8을시작한다. 초기독립지적의실패→수정후실제통과는확인했으나 PR16전체리뷰/T6/後측정은미완료다. T5 상세gate계약이조립브리프에누락된것을같은문구2절이동으로복구2/2기록, T6 기존7사례+initial3/reset6=16의정확한read-first/retry기대값을복구1/2로보완했다.
