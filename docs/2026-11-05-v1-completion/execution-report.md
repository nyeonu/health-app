# V1 개발 실행 기록

이 문서는 진행 증거이며 V1 수용 완료나 출시 승인이 아니다. 소유자의 자동승인 범위에서 구현·독립 검토·개발 병합을 진행한다. 실제 Galaxy/Google/SAF provider/Doze·재부팅·TalkBack의 미실행 항목은 pending이다.

## 현재 상태 (2026-11-13)

core-store PR16과 수동 Drive PR17의 소프트웨어 구현·검증·독립 검토·개발 병합을 완료했다. PR17 merge는 `57da82514e8483f5f32f2ad34ae0111c7f1dc431`, 검증 source는 `b35c11c8b4651cf9b12b004a7fc4ae3d52a4d3ee`다. JVM176/실패·오류·skip0 및 APK/lint, API35/36 각각 Native120/실패·오류·skip·중복0, 각 history host20과 SyncState verifier17+cleanup17/killed holds PASS0을 원본으로 대조했다. 별도 수동 slice 스펙 PASS·품질 APPROVE·보안/성능 추가 차단0과 수정/재검토는 [최종 검증](manual-drive/final-verification.md) 및 PR17 한글 댓글에 있다.

SAF는 별도 test APK UID/target resolver 기반 provider·실제 capability·순수 화면의 API35/36 검증을 완료했다(source086b5b3). 같은 대상 wt/readback·journal 복구 adapter는 초기 독립 지적을 수정해 lint/APK/testAPK 및 소스 재검토를 통과했고(cb274caf), controller도 별도4 JVM 테스트/오류·실패·skip0 및 APK·소스 독립 검토를 통과했다(d51fde4). 실제 Activity 연결·최종 전체 gate·SAF PR 검토/merge는 미완료다.

새 SAF adapter/component 양API 실행 두 건(43459510333/43459516911)은 GitHub Actions 결제/지출한도 제한으로 네 runner가 모두 시작되지 않았다(steps/tests/artifact0). 당시 코드 테스트 실패가 아니었으며, 소유자 Public 전환을 root가 직접 확인한 뒤 latesta1598b2의 새 실행43478573782/43478579503 네 runner가 실제 시작됐다. 현재 T10은 in_progress이고 의존 T6는 실제 결과 대조 전까지 pending이다. 소유자에게 Actions 사용 가능 상태 확인을 요청했고 billing 변경/credential 요청/무한 재실행은 하지 않는다. 검증 범위를 줄이거나 실제 실행 없는 PASS로 우회하지 않는다. [현재 계획](saf-local/plan.md)과 [원본 checkpoint](saf-local/checkpoint-native.json)에 재개 지점을 보존했다.

병렬 WorkManager 기반은 초기 네 graph/155공개Maven좌표 OSV0취약점·오류 및 metadata/manifest/API/build 감사 뒤, sourceea21e9d/run43453138754의 양API 전체 Native121/오류·실패·skip·중복0과 기존host20/SyncState17+cleanup17/killed holds PASS0까지 root가 ZIP hash/원본XML/host결과로 대조했다. 이 branch는 아직 SAF 통합이나 PR merge를 마치지 않았고 customApplication/자동실행을 구현했다는 뜻이 아니다. 자동화 초안에는 helper process 격리를 production 초기화보다 먼저 수행하고 retry/store lane과 summary/HC lane을 독립 병렬화하는 순서를 준비했다. 최종 자동화·보안 재감사·실기기 수용은 별도 gate다.

전체 V1 SAF·자동화·최종 UI는 진행 중이다. 실제 Galaxy/Google/provider/Doze·재부팅·TalkBack, 남은 P0 및 복구 가능한 개인 서명키 백업 확인은 pending이다. 성능72관측과 이전 Drive benchmark를 새 reconciliation·전체 admission 비용이나 실기기 개선율의 증거로 사용하지 않는다. 아래 기록은 시점별 이력을 보존하며 이 현재 상태와 계획 frontmatter가 우선한다.

## 선행 작업

- PR #14의 앱 최초 진입/맨 위 제목과 날짜 가림: 소유자 답변 “정상으로 보여”. 사용자 관찰 결과이며 새 V1 화면의 접근성 전반 통과로 확대하지 않는다.
- 성능 PR #15: master `bf071e19e4e52c285e075b3dfe3db09e1bda8135`로 병합. 현재 worktree에는 `1cbf860c6c431edd437a5d039fe1d36eafade2f6`로 반영했다. 성능 source·별도 검토·합성 local TLS·API35/36 증거는 해당 PR 댓글/문서가 원본이다.
- 고정 서명 APK: [trusted master build](https://github.com/nyeonu/health-app/actions/runs/41959514365), [artifact](https://github.com/nyeonu/health-app/actions/runs/41959514365/artifacts/13385245747). 다운로드한 ZIP digest/실제 APK 서명/기존 certificate 동일/package를 확인했다. APK SHA256 `e6f1f55adc442498f900a9e678446ae0630ac41258aa0db6731742165ef50f31`. 새 바이너리의 실기기 업로드 성능 측정은 아직 없다.

## core-store 초기 진행 이력

| 단계 | 실제 검증 | 상태·한계 |
|---|---|---|
| T1 모델/planner | canonical scope literal vector, 등록일·KST·최대7·범위별 checked/uploaded 구분; core45/실패0·오류0·skip0 | done; 기본 복구2회 뒤 소유자 자동승인에 따른 추가 정합성 교정1회는 child plan에 별도 누적 |
| T2 revision/codec/reducer | schema0.1 포함 literal EMPTY digest/4dataset 이름순; core49/0/0/0 | done; recovery1 protocol 보정 |
| PR15 병합 후 통합 | core63+app74=137/0/0/0, APK/lint/testAPK 2분21초 | 그 시점 source에 대한 검증; 이후 변경의 통합 검증을 대신하지 않음 |
| T2 recovery2 | unscoped unfinished/finished attempt·구성 categories·정확한 journal/CAS/start 거부 fixture; core64/0/0/0 | 실행자9초 PASS; root 명령7초 입력동일 UP-TO-DATE와 실제 XML 대조. source 수정은 `947b0c7` 포함 |
| T3 reconciliation recovery1 | 모든 intended 파일 재조회, 과거 확인은 prior로 보존, unsafe/moved/shared/absent/조회실패로 과거 성공 재사용 금지, healthy 무쓰기; target 실행자/root 각20초 PASS | recovery2의 typed failure 보정 진행 중. 최신 전체 head 통과를 아직 주장하지 않음 |
| T4/T9 | cb89da7 API35/36 Native89/0/0/0·SQLite31PASS·기존host20PASS | done; 새session과V1 force-stop/T8후측정은아직미완료 |
| T5/T8/T6 | 공용gate·저장소반복조회개선·새force-stop16경계 | T5/T8 구현중, T6선행gate대기. 로컬KVM없음으로실제Native는CI에서확인 |

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

T8 비교기준 보강: 최초 d158 진단측정144관측을 유지하고 수정후전체NativePASS인 cb89의같은fixture144관측을 baseline JSON의correctedSourceBaseline에추가했다. 이후전후비교는동일correctness코드를포함한기준선을우선한다. API35 runner편차가커3650 full LOAD약14초/scoped약24초/confirmation약13.5초, API36약8초이며실기기수치나SLA가아니다. 동일반복3median만제시하고p95를추정하지않는다.

T8 recovery1 로컬full1분56초PASS, XML core65/app74/실패·오류·skip0 및APK/lint/testAPK. 첫실패는새fake의malformedUUID를정상identity교체로표현한기대불일치였고validfixture·실제generation불일치null계약·selected최대9보존으로한정교정했다. T5 focused최초core4PASS후crossmodulepublicnullable smartcast compile오류는기본계획복구2회와별도owner-auto추가교정3으로기록했다. root가T8patch를T5focused검증중통합했으므로그중간결과를동결source전체증거로쓰지않으며, 모든source동결후rootfull명령으로다시확정한다.

T5/T8 통합 source동결후 root full명령이1분51초PASS했다. 실제XML core65/app74=139/실패·오류·skip0, APK/lint/testAPK 통과. T5 productionnullable/Nativeinlinefixture compiler교정은추가회차3/4로누적기록했다. sourcecheckpoint는actualSQLite32·Session9·성능72/API PASS가아니며T5/T8는in_progress다. T6 initial/reset을포함한16host작성에진입하며완료판정은최종head전체실행과별도review후에만한다.

T6 구현3파일과안전진단보정source동결후rootfull43초PASS: JVM139/0/0/0(입력동일대부분캐시), APK/lint/testAPK. root진단mock6입력·YAML/discovery·runner각줄shell문법PASS. 신규harness는16실제force-stop verifier와16cleanup을실행하며hold성공집계0을강제하고raw출력을업로드하지않는다. 실제실행전이므로완료가아니다. 8c3b416 별도fresh4세션검토는확정source차단지적없음이나최종head/harness/후측정은pending으로각PR댓글에기록했다.

실제T5/T8 Native확인: 8c3b416/CI43138251342 양API각99/0/0/0(Session9/store32/performance1), 기존historyhost20/holdPASS0. root가원본ZIP digest/XML/72관측JSON을대조했고 after원본과전체비교를core-store/store-performance-after.json에저장했다. API35/36 3650full confirmation 반복3median은13.50→3.14초/8.04→4.28초이며host변동·합성조건/실기기업로드아님을유지한다. Important Q1 readonly-hotjournal은별도미해결로병합보류하며T10 test-only 실제cache-spill 재현추가중.

Q1 Android 수정전실패확인: f12/CI43145722146 양API원본ZIP/XML/hostsafe결과를root가확인했다. Native99/0/0/0·기존16+16PASS와별개로4096일×4096byte유효handle의main실제변경/regularjournalmagic을입증했고새PID first READY assert181이실패했다. [안전한재현요약](core-store/hot-journal-probe.json)에raw/계정/PID없이저장했다. 이전20kseedtimeout은이proof가아니다.
Q1 로컬수정후보 bd6e26f는raw/canary/fullvalidation/원본identity gate,orphan무생성거부,최초CE보존,기존UNAVAILABLE분류,canonicalparentalias를반영했다. rootfull1분59초PASS·실제XML139/0/0/0·APK/lint/testAPK, 별도4세션source검토차단지적없음. recovery4path명령실패/5compiler+inline교정/6alias/7orphan+CE/8path분류는모두누적기록했다. 새 Native36/Session9/기존57/performance1=103 및17OS복구/17cleanup·후측정과최종publishedhead재검토는이후CI로확정한다. 물리ENOSPC/EIO실주입은미실행이며fault분할/취소검증과구분한다.

수정후보 b7bdf789/Native43151273714 실제결과: 양API각103/실패1/오류0/skip0, unsafeRollbackSidecarsAndChangedSourcePairsNeverFollowExternalPathsOrDeleteUnknownFiles의Files.createLink(line900)에서AccessDeniedException이다. API35의해당test logcat은SELinux untrusted_app→app_data_file의link거부(permissive=0)를직접기록했다. API36도같은생성오류이나동일AVC증거를확인했다고주장하지않는다. 하드링크를만들기전실패하여그거부단언과그룹뒤분기는미실행이고host17도도달하지않았다. XML/ZIP SHA256 API35 6f9769bc22a3c57245de1b11f00927961f052fa216102a81a925601b9a4b456b/API36 a9e0d456d19f20584a393f8227cee8be8e467fb8a737eac7b51ec8a5b80c8652를root가검증했다. JVM/APK/lint43151273722는PASS. 정상DB성능72/API는실행됐으나전체통과나Q1해소로해석하지않는다. 별도작성자/품질/보안세션이보안설정을유지한fixture보완을진단중이며T11/PR병합은계속차단한다.

R9 공개수정 7dc1f43d071416e9744e36f900bffdf48f1594c5: helper regular 가시성만internal, 실제검사본문/호출불변. 두createLink시도와성공시실제sameinode/nlink2·기존Store거부를유지하고정확AccessDenied는무생성/원본보존+동일validator nlink1/2 경계검증으로분리했다. 실제hardlinked OSadmission은미검증으로남기며skip/Assume/보안설정변경없다. API36link AVC도다음testlogcat에서root가직접확인했다. 실행자와root의독립full명령각1분57초PASS/JVM139/0/0/0·APK/lint/testAPK. 공개head별도4세션source재검토신규차단지적없음은PR댓글6814429306/6814430977/6814432683/6814441960에보존했다. Native43154054835/APK43154054868및host17·성능관측·최종증거재검토는아직진행중으로완료가아니다.

Important Q3는별도품질댓글6814539633에REQUESTCHANGES로기록했다. 공개7dc동결Android gate와별개로T3복구5를격리worktree에서진행한다. 최초test-only 실제FAIL(expectedINDETERMINATE/actualPENDING,3tests/1fail/0error/0skip)을root가XML대조했으며제품은미수정이다. planner의명시blocker제외조건과혼동하지않도록기존omitAck 기반blocker없는입력으로red회귀를정밀화한다.

7dc/Native43154054835 API35 실제103/1/0/0: 새unsafe그룹포함store36·Session9·performance1은PASS다. 실패는기존HealthAppInstrumentedTest.nativeScreenKeepsContentBoundaryWhileScrollingAndReturningToTop의captureToImage→PixelCopy wait assertion(line314/232)이다. 실제화면경계단언실패라고추정하지않고UIcapture실패로보존하며host17은미실행이다. ZIP SHA256 6d37a490216f5820aa01d165c621077e0defd69760ef2ac51d0eec0ff3c59008을root원본대조했다. 테스트/환경보안/timeout을완화하지않으며Q3최종source전체CI에서다시확인한다. API36은아직진행중이다.

7dc API36 실제PASS: Native103/0/0/0(store36/session9/performance1),기존historyhost20및새host17verifier/17cleanup·죽인holdPASS0. root가ZIP SHA256 03dc5e69fe46848bfe5d5d5038be0fbab1b2b01d609228edc3eda9ec4b25c807과원본XML/정형host결과를검증했다. 실제main변경/regularjournalmagic4096×4096입증→force-stop/새PID첫일반read READY·이전전체state복원/후속mutation이통과하여API36의Q1수정전후proof가성립했다. API35는기존PixelCopycapture실패로host미실행이며양API최종gate는아직미완료다. Q3교정뒤최신source전체CI와독립최종검토를유지한다.

be20 rootfull2분7초/JVM142/0/0/0·APK/lint/testAPK PASS. 별도spec/security는같은scopeQ3보정과별개로scope전환Q4누락을발견하여FAIL/REQUESTCHANGES,댓글6814802584에보존했다. 지금generation/date의모든scope physicaldataset불명상태를조회/투영하는T3복구6을계획보정했다. 현재CI결과·기존회차·같은scope의실패후통과증거를유지하고최신head전체검증/재검토전병합하지않는다.

범위전환Q4는A EXPLICIT_DAY unknown/새B SUMMARY에서flagfalse라는실제red6/1/0/0을확인했다. T3복구6은date별모든scope의nulltarget unresolved Dataset집합을조회/검증하고기존journal/PlannedFile에합친다. SQL은기존PK의generation/date범위2orderedcursor·canonicaldecode·orphan거부이며형식/index/cache추가없다. 복구7은Nativefixture의모듈공개API한줄만교정했다. 실행자focused8/0/0/0+fullJVM145/0/0/0·APK/lint/testAPK PASS후5파일후보aba7fc6을받았고최종root/CI/독립재검토는아직대기다.

be20/Native43156361988 양API원본대조: 각각104/0/0/0, store36/Session10/성능1·기존host20·새17복구/17정리·killedholdPASS0. 실제spill main변경/regularmagic/4096×4096→새PID firstread READY 및이전state복원/후속write는양API통과했다. API35의이전7dc PixelCopywait실패와달리같은UI source의재검증도통과했다. ZIP SHA256 API35 c0c5d20ebc09c36d631062ad53dfc96d63f4693e4b93cdb01647374a69bcf4d3/API36 79f4adc087e837ad4a535ecc5461facf5bf3f727c7b529501886ec62cb94391c를root검증했고hot-journal-after.json에안전한관측을보존했다. 이는Q1/same-scopeQ3의actual증거이며Q4최신764cc/106개및portquery의통과를승계하지않는다.


2026-11-12 계획 전환: root가 확인한 PR #16 개발 병합 head는 `4b98aee1119ded1f1c6553e92198ac004d30fa87`이다. core-store 완료 뒤 manual-drive 브랜치에서 실제 현재 source와 승인 spec(97c01b1)을 직접 대조하여 parent ManualDriveContracts/file map/traceability와 9T child draft를 작성했다. 1T 제품/테스트 파일은 2~5개이며 auth+transport, uploader+reconcile, known-ID+명시 blocker reopen을 각 기능 경로로 묶었다. parent 먼저 commit 후 child parent_rev repin·root 승인 검토를 거쳐 별도 executor에 넘긴다. 아직 implementation/task done/새 테스트 PASS를 주장하지 않는다.

계획 확인 범위: AGENTS·task-breakdown/work-plan-template/structure-rules·testing-policy/common·security mandatory checklist, current HealthConnectReader/Ports/DestinationSync/PrivateDriveUploader/SqliteSyncStateStore/Session/Activity/VM/transport 및 기존 테스트/CI를 읽었다. 문서 근거의 setAccount/about/checksum 확인값과 실제 OAuth/Galaxy pending을 분리했다. source·test·Gradle·실계정·원격 writes·PR 동작은 수행하지 않았다. 계획 중 발견한 intended PlannedFile equality guard와 BLOCKED+null blocker validation을 공통 계약에 명시하여 runtime reservation과 PENDING day reopen을 고정했다. ADR007은 Proposed 유지다. 이전 PR16 증거/실패 이력과 P0/실기기 미확인은 보존한다.

### manual-drive 실행 승인 (2026-11-12)

PR16 병합 master `4b98aee1119ded1f1c6553e92198ac004d30fa87`에서 9T 계획을 root 승인했다. 별도 fresh-context 계획 검토에서 차단 지적은 없었다. parent 계약 선행 커밋과 child revision 고정을 마쳤으며 구현·실행 증거는 이후 누적한다. 승인만으로 기능·실기기 수용 완료를 뜻하지 않는다.

최초 브리프는 T1 217줄/6671추정토큰, T2 219줄/6726추정토큰, T4 217줄/6666추정토큰이며 모두 상세 Shared design47줄·해소 실패0이었다. PLAN 복구1은 조립기 표기 보정이다. T2 복구1은 새 의존성 없이 기존 core JSON parser를 이용하는 fifth-file predicate 경계 보정이다. T1/T2/T4 분리 authoring을 시작했으며 Gradle은 아직 실행하지 않았다.

manual-drive T1은 root verify25/0/0/0으로 완료했다. T4는 testAPK 컴파일을 author/root가 각각 확인했으며 실제 SQLite/Native 동작은 CI pending이다. 검증 전후 production/test source가 바뀌지 않도록 build slot과 edit freeze를 운영한다. T1 마지막 page100×1000 fixture 보완 후 root가 다시 실행했다.

manual-drive 중간 통합 head34147fd(이후 문서만 변경) root 전체 검증: `/workspace/tooling/pr17-checkpoint-t124.log` BUILD SUCCESSFUL3분8초. actual XML core72+app80=152/0/0/0, APK/lint/testAPK 통과. Android SQLite2개는 compile만 확인한 상태이며 CI43169661306 실행 결과 대기다. 독립 quality 중간 검토에서 차단0·Suggestion1(orphan fixture 분리성)으로 test-only 보정을 배정했다.

manual-drive T12346 중간 전체 검증 source `c10a5445758dcb48404757b910bb8bb5a206bbb6`: root `/workspace/tooling/pr17-checkpoint-t12346.log` BUILD SUCCESSFUL2분7초, core80+app80=160/0/0/0, APK/lint/testAPK 통과. 이후 문서만 변경했다. 신규 store2/Session3 actual Native는 pending이다. 독립 quality r2가 c10의 취소 테스트 Important와 orphan Suggestion 정적 해결을 확인했고 새 차단 지적 없이 현재 slice APPROVE; 최종 구현 head와 actual Native 확인은 별개다.

manual-drive T123456 source `8b2f04bb7833887cba7e7c9685a63a0c3eeac5e1` root 전체 검증163/0/0/0(80core+83app), APK/lint/testAPK 성공1분51초. GET503 교정의 actual TLS14건과 exact1전송 회귀를 포함하며 독립 quality r3에서 새 차단 지적이 없었다. T7 구현 중이고 actual Native 및 최종 PR head 독립 검토는 pending이다.

manual-drive T7 recovery3 red2건→수정후3/0/0/0 및 quality r5재검토 해결. source3b5b84c root전체166/0/0/0+APK/lint/testAPK 2분19초 성공. source7ca Native 양API각111/0/0/0과 기존host20·SyncState17/17 원본/digest 대조를 완료했으며 T4/T6 source4파일 동일성을 확인했다. T8 UI 실제 Native와 T9 Activity는 아직pending이다.
