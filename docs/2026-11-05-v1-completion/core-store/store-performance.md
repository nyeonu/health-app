# 합성 Android metadata 비용 측정

상태: baseline 측정 완료, T8 보정/동일 fixture 재측정 대기. 실제 Galaxy·Google Drive·배터리·전체 engine latency 측정이 아니다.

[immutable probe run](https://github.com/nyeonu/health-app/actions/runs/41970834390), source `d1589814ed2660bddf1fe2c80331e2ff73b4772b`에서 performance testcase는 양API 각각 PASS/skip0, [숫자 원본72개씩](store-performance-baseline.json)을 root가 ZIP digest와 실제 XML/JSON으로 확인했다. 전체CI는 독립 correctness 재현실패16개와 API35의 별도 GoogleSdkSetup focus실패3개로 실패했다. baseline을 전체승인으로 해석하지 않는다.

6개 independent synthetic DB에30/365/3650개 day+DAILY metadata를 seed했다. FULL_EXPLICIT는5종 scope, PARTIAL_SUMMARY는STEPS만이다. 각 load/scopedLoad/confirmation은 최초reopen1개와 반복reopen3개를 측정한다. 표는 반복3개 elapsed median(ms)이며 p95가 아니다. Process CPU(ms)는 전체프로세스 관측값이고 thread/할당량/배터리를 추정하지 않는다. setup·journal 준비·결과assert는 timed boundary 밖이다. filesystem cold cache를 주장하지 않는다.

| API/profile | N | load ms | scoped load ms | confirmation ms |
|---|---:|---:|---:|---:|
|35/full|30|67.37|69.03|78.01|
|35/full|365|729.14|747.43|722.36|
|35/full|3650|6899.30|6761.19|6853.58|
|36/full|30|92.87|93.13|110.89|
|36/full|365|1023.93|1042.55|1002.82|
|36/full|3650|9983.70|9950.40|9631.68|
|35/partial|30|27.99|65.06|74.08|
|35/partial|365|349.65|660.62|627.88|
|35/partial|3650|3257.19|6422.74|8449.97|
|36/partial|30|48.00|79.75|101.35|
|36/partial|365|469.86|905.90|905.47|
|36/partial|3650|4599.27|8928.78|8502.65|

현재 snapshot의 active row 검증과 profile scan은 day마다 day/dataset pointSELECT를 반복하며, engine의 파일별 current/confirmation도 이 snapshot을 다시 요청한다. 반환 최대9행은 SQL/decode 작업량 제한이 아니다. static N+1 분석과 위 실제 비용이 T8의 근거다. 수정은 streaming bulk validation/집계와 좁은 attempt identity 조회를 사용하되 비선택profile 손상·privatebinding·generation/attempt/date/scope/revision·원격 journal gate를 유지한다. across-call cache나 SQLite durability 하향, 측정fixture/규모/검증완화로 속도를 만들지 않는다. API별 hosted machine 차이가 있어 API35↔36 우열이나 실제기기 수치를 단정하지 않는다.

## Correctness 수정 후 비교 기준선

T8 변경 직전 source `cb89da7783eb8420fca0440cf7b29e61955bca95`의 [양API 전체PASS run](https://github.com/nyeonu/health-app/actions/runs/41977482571)에서도 같은72관측씩을 확보했다. 위 초기진단 기준선은 보존하되, 이후 비교는 손상쓰기/원자적게시 수정을 포함한 이 기준선을 우선 사용한다. 원본 JSON의 correctedSourceBaseline에 144관측을 추가했다. setup/assert/fixture/규모/반복횟수는 동일하며 hosted runner간변동 한계는 남는다. 아래도 반복3 median(ms)이다.

| API/profile | N | load ms | scoped load ms | confirmation ms |
|---|---:|---:|---:|---:|
|35/FULL_EXPLICIT|30|169.30|146.35|177.95|
|35/FULL_EXPLICIT|365|1523.49|1408.77|1412.56|
|35/FULL_EXPLICIT|3650|13983.49|24040.31|13501.77|
|35/PARTIAL_SUMMARY|30|71.79|129.85|153.95|
|35/PARTIAL_SUMMARY|365|696.34|1267.85|1301.01|
|35/PARTIAL_SUMMARY|3650|7208.77|15138.22|12202.18|
|36/FULL_EXPLICIT|30|87.08|79.03|91.91|
|36/FULL_EXPLICIT|365|865.09|842.05|831.49|
|36/FULL_EXPLICIT|3650|8016.62|8056.36|8036.18|
|36/PARTIAL_SUMMARY|30|36.90|68.75|87.18|
|36/PARTIAL_SUMMARY|365|384.69|741.42|743.52|
|36/PARTIAL_SUMMARY|3650|3909.59|7424.24|7331.93|

## Streaming/narrow 구현 후 실제 관측

[8c3b416 Native run](https://github.com/nyeonu/health-app/actions/runs/43138251342)은 양API 각각99/실패0/오류0/skip0(Session9/store32 포함)이며 같은fixture72관측씩을 [after JSON](store-performance-after.json)에 보존했다. 비교기준은 위 correctness 수정후 cb89 baseline이다. 아래는3650행 반복3median(ms)이며 hostedmachine 변동으로개별개선율을실기기/업로드전체 개선율로일반화하지않는다. 특히API36 partial LOAD의6.5%차이는노이즈와구분한효과를증명하지않는다. 각모든N/프로필/operation/원본72표본은JSON에남긴다.

| API/profile | load before→after ms | scoped load before→after ms | confirmation before→after ms |
|---|---:|---:|---:|
|35/FULL_EXPLICIT|13983.49 → 3359.09|24040.31 → 3423.13|13501.77 → 3139.57|
|35/PARTIAL_SUMMARY|7208.77 → 2614.35|15138.22 → 2662.77|12202.18 → 2633.97|
|36/FULL_EXPLICIT|8016.62 → 4368.47|8056.36 → 4229.12|8036.18 → 4279.82|
|36/PARTIAL_SUMMARY|3909.59 → 3656.21|7424.24 → 3820.21|7331.93 → 3881.13|

이 관측은 session 전체의 source/remote IO 대기나 실기기 성능을 증명하지 않는다. DB open의 schema/FK/quick_check와 전체active 무결성 검증은 남는다. 당시 후보e62는 제품source/측정fixture가 같지만 독립 품질 지적Q1의hotjournal복구가미해결이며 after PASS를 PR전체승인·모든강제종료PASS로쓰지않는다.

독립 대조에서36개 반복median 비교 셀 중34개 감소·2개 증가를 확인했다. API36 PARTIAL_SUMMARY LOAD는30행36.90→41.86ms(+13.45%),365행384.69→396.67ms(+3.11%)이며,3650행 첫reopen도3696.63→3693.35ms로거의같다. 따라서 bulk/scoped/confirmation 개선방향을지지하지만작은partial LOAD나전체batch의균일개선을주장하지않는다.

## 최종 core-store source의 실제 재검증
[`764cc69` run43158298634](https://github.com/nyeonu/health-app/actions/runs/43158298634)의 양API는 각각Native106/실패0·오류0·skip0,기존host20·새복구17/정리17·죽인holdPASS0을통과했다. 위8c3중간관측과최초d158/cb89기준선을보존하고최신144관측·36그룹의firstreopen/반복median/CPU/min-max를after JSON의latestVerifiedCandidate에추가했다. setup/assert제외·같은72입력·4표본방법은불변이다. 아래는3650행반복3median(ms)이며분리된guard비용이나실기기upload시간이아니다.
| API/profile | load cb89→764 ms | scoped cb89→764 ms | confirmation cb89→764 ms |
|---|---:|---:|---:|
|35/FULL_EXPLICIT|13983.49 → 3208.76|24040.31 → 3201.31|13501.77 → 3223.22|
|35/PARTIAL_SUMMARY|7208.77 → 2590.03|15138.22 → 2588.42|12202.18 → 2667.32|
|36/FULL_EXPLICIT|8016.62 → 1909.76|8056.36 → 1782.38|8036.18 → 1857.16|
|36/PARTIAL_SUMMARY|3909.59 → 1530.87|7424.24 → 1622.21|7331.93 → 1593.72|

corrected cb89대비이번36median은모두감소했지만중간8c3대비API35의5셀은1.27~23.75%증가했고API36은18셀모두감소했다. 7dc대비API36 partial30 confirmation은40.14→47.01ms(+17.1%)로증가했다. hostedrunner/CPU·warmup·GC/파일캐시변동이남으므로모든구간의균일개선·인과효과를주장하지않는다. firstreopen은coldfilesystem이아니며4표본/반복3median으로p95/p99를만들지않는다.

Q4의loadUnresolvedCreates는engine에서날짜별추가조회하지만이72fixture는load/scoped/confirmation만측정한다. 추가engine조회와current검사의총실행비용·원격IO·폰·배터리·SLA는미측정이다. source상profile별N+1을추가하지않고generation/date범위를2orderedcursor로소비하지만DBopen의schema/FK/quick_check비용까지O(해당날짜profiles)라고단정하지않는다. 비정상hotjournal raw/workcopy·hash/fsync·추가disk비용도별도이며물리ENOSPC/EIO실주입을하지않았다.
