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
