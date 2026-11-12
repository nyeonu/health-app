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

이 관측은 session 전체의 source/remote IO 대기나 실기기 성능을 증명하지 않는다. DB open의 schema/FK/quick_check와 전체active 무결성 검증은 남는다. 최종 후보e62는 제품source/측정fixture가 같지만 독립 품질 지적Q1의hotjournal복구가미해결이며 after PASS를 PR전체승인·모든강제종료PASS로쓰지않는다.
