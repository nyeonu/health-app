# V1 개발 실행 기록

이 문서는 진행 증거이며 V1 수용 완료나 출시 승인이 아니다. 소유자의 자동승인 범위에서 구현·독립 검토·개발 병합을 진행한다. 실제 Galaxy/Google/SAF provider/Doze·재부팅·TalkBack의 미실행 항목은 pending이다.

## 현재 상태 (2026-11-12)

core-store의 소프트웨어 구현·검증은 완료했고 PR #16의 최종 문서 head 대조/개발 병합 단계다. 검증한 코드 source는 `764cc6916c4c6231beb9c9a13b6ab152f33159ac`이며 root 전체 JVM145(core71/app74)/실패0·오류0·skip0, APK/lint/testAPK와 CI43158298603이 통과했다. Native43158298634는 API35·36 각각106/0/0/0, 기존 host20 및 새 복구17/정리17·죽인 hold PASS0, 같은72관측/API다. 별도 스펙 검증은 **core-store 배정 기준 PASS**, 품질 APPROVE, 보안·성능 차단 지적 없음이며 원본 XML/정형 host/metrics를 독립 대조했다. Q1 실제 spill 복구와 Q3/Q4 미확인 create의 동일/교차 scope·SQLite 재열기 회귀가 포함된다.

수정 전후 실패·fixture/환경 문제·추가 복구 회차는 아래 시점별 기록으로 보존한다. `plan.md` frontmatter와 위 현재 상태가 이전 진행 중 문구보다 우선한다. Drive 계정 연결·SAF·WorkManager·통합 UI는 후속이며 전체 V1는 진행 중이다. P0/Galaxy/실계정/provider/Doze/TalkBack·물리 ENOSPC/EIO 주입·생성 거부 환경의 실제 hardlinked admission은 완료 처리하지 않는다. 성능72관측은 새 engine uncertainty query의 독립 비용이나 실기기 전체 업로드 효과를 측정하지 않는다.

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
