# 반복 가능한 합성 동기화 기준선

## 결과와 측정 경계

2026-11-03 UTC 로컬 JVM 실행에서 실제 `System.nanoTime`과 실제 `GoogleDriveTransport` → OkHttp → local TLS를 사용했다. 아래 시간은 실측이며 가상 시계로 만든 값이 아니다. 각 시나리오 3회 준비 실행을 제외하고 10회 성공을 관찰했다. 재현 가능한 것은 입력·상태·요청 순서·계산법이며 시간의 일치를 보장하지 않는다.

`EXECUTION`은 하네스가 `SyncCoordinator.sync(date)`를 감싼 구간이다. 합성 reader 반환, 실제 normalizer/CSV exporter, 실제 uploader/transport와 TLS 왕복이 포함된다. 실제 manual controller 전체 지연은 측정하지 않았다. 인증·사용자 대기·SQLite 이력 저장·실제 Health Connect 읽기·Google 서버/인터넷·Android 기기/화면/배터리/스케줄링 시간은 모두 미측정이다. `HEALTH_READ`는 고정 객체를 반환하는 합성 reader 시간이다. `AUTHORIZATION_HISTORY`, `AUTHORIZATION_WAIT`, `CONTINUE_WAIT`, `START_HISTORY`, `TERMINAL_HISTORY`는 관찰되지 않았으며 0으로 해석하지 않는다.

단계와 operation과 request는 중첩되므로 서로 합산하지 않는다. operation은 uploader 논리 구간, request는 실제 transport 호출 구간이다. 이 기준선은 단일 페이지 성공 응답을 사용하므로 두 호출 수가 같지만 항상 같다는 주장은 아니다. 여러 파일에서 같은 operation의 `elapsedNanos`는 해당 호출들의 합이다.

## 소스와 환경

이 최초 실행의 기반 HEAD는 `594b7dfa42e5e52583ff06ac85f32c8bcb9d93d8`, production 구현 revision은 `2ee094501571d5b30fd00a0e55fb19da4d4c13cc`이다. HEAD에는 신규 하네스가 아직 포함되지 않은 `dirty=true` 실행이다. 실행한 하네스 SHA-256은 `baba4dcbde9ebebc000b001ab033c31488e34ba200fafe18410dd2798000f6a9`이다. 커밋 후 같은 명령의 결과로 아래 증거를 확정할 예정이다.

- JDK: wrapper가 선택한 Eclipse Temurin `21.0.13+9-LTS`, JVM target 17. 셸 기본 JDK와 구별한다.
- OS: Linux `6.18.57`, x86_64/amd64, 공유 클라우드 실행 환경. CPU 부하·JIT·GC·OS 스케줄링은 통제하지 않았다.
- Gradle 8.13, AGP 8.13.2, Kotlin 2.2.20, Android compile/target SDK 36 (platform revision 2), build tools 35.0.0/36.0.0 설치. JVM 단위 테스트이므로 Android OS는 실행하지 않는다.
- OkHttp/MockWebServer/okhttp-tls 5.3.0, JUnit 4.13.2, coroutines 1.10.2, kotlinx-serialization-json 1.9.0. 기존 의존성만 사용했다.
- 오프라인 Gradle 캐시 사용, 단일 테스트, 로컬 소켓을 허용한 실행. 새 서비스·실제 계정·실제 credential을 사용하지 않았다.

## 고정 입력과 상태 재설정

날짜 `2026-10-30`, generatedAt `2026-10-31T00:00:00Z`, 권한 `{STEPS, HEART_RATE, SLEEP}`. steps 42, weight/exercise 없음. 3 CSV 조건에는 `2026-10-30T00:00:00Z` 합성 심박 70 한 건과 같은 시각부터 60초인 합성 수면 한 건을 추가한다. 모든 ID/source/metadata/token은 고정 합성 값이다. CSV는 실제 exporter가 생성하며 원시 본문은 보고서에 기록하지 않는다.

| 조건 | 매회 원격 응답 상태 | 파일 수 | 요청/operation 수 | 매회 검증한 HTTP 순서 |
| --- | --- | ---: | ---: | --- |
| warm1CSV | app/daily/file 존재 | 1 | 4/4 | GET GET GET PATCH |
| cold1CSV | app/daily/file 없음; 생성 응답과 app root 재확인 | 1 | 7/7 | GET POST GET GET POST GET POST |
| warm3CSV | app 및 daily/heart-rate/sleep/file 존재 | 3 | 12/12 | GET GET GET PATCH를 3회 |

매회 MockWebServer 응답 큐를 고정 스크립트로 채운다. 상태 저장 서버가 아니므로 이전 POST가 다음 표본의 원격 상태를 바꾸지 않는다. `cold`는 원격 metadata 부재를 뜻한다. 매회 collector/coordinator/uploader/transport를 새로 만들고, 시나리오 내에서는 같은 TLS 서버·인증서·OkHttp client·connection pool을 준비 실행과 관찰에 재사용한다. 준비 3회에 초기 handshake/JIT 비용이 들어갈 수 있고 그 시간은 분포에서 제외한다. 연결 재사용은 허용되지만 실제 재연결 횟수는 측정하지 않았다. 시나리오 간에는 서버/client를 새로 만들고 종료 시 pool을 비운다. TLS 인증서 생성·서버 시작·응답 큐 구성·검증·보고서 파일 쓰기는 `EXECUTION` 밖이다. production URL 검증 후 테스트 interceptor가 localhost로 전달하며 모든 요청에 TLS handshake가 있음을 단언한다.

요청 수 차이와 함께 client/JIT/실행 순서 차이도 있으므로 시나리오 차이를 인과적 개선율로 사용하지 않는다. POST multipart boundary는 기존 구현이 임의 생성하며 입력 데이터와 응답 metadata만 고정한다.

## 산출법과 표본

시간 단위는 아래 모두 ns이다. 정렬한 N개 값에서 `ceil(N × p)`번째 값을 선택한다. 알려진 순열 1…10에서 p50=5, p95=10을 테스트한다. N=10에서는 p50=5번째, p95=최댓값이다. 이 작은 합성 표본은 실제 사용자 tail 또는 SLA 근거가 아니다.

각 행은 성공 한 번이며 모든 stage 시작/완료 수는 1, 실패/취소 수는 0이다. 작업/요청 수는 위 표와 같고 매회 단언했다.

| 조건 | 표본 | EXECUTION | HEALTH_READ | NORMALIZE | CSV_EXPORT | DRIVE_TOTAL | operation/요청 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| warm1CSV | 1 | 13098811 | 12298 | 60540 | 73950 | 12921987 | 4/4 |
| warm1CSV | 2 | 15866998 | 13240 | 53880 | 73730 | 15693559 | 4/4 |
| warm1CSV | 3 | 16811749 | 13641 | 74972 | 108592 | 16578161 | 4/4 |
| warm1CSV | 4 | 14181813 | 18407 | 109313 | 155070 | 13853254 | 4/4 |
| warm1CSV | 5 | 9986357 | 14992 | 63404 | 88091 | 9739640 | 4/4 |
| warm1CSV | 6 | 18207586 | 12739 | 54320 | 80389 | 18023131 | 4/4 |
| warm1CSV | 7 | 17321537 | 15023 | 54441 | 116373 | 17076222 | 4/4 |
| warm1CSV | 8 | 16665661 | 10796 | 52858 | 63825 | 16508718 | 4/4 |
| warm1CSV | 9 | 13172100 | 12629 | 51527 | 93359 | 12985572 | 4/4 |
| warm1CSV | 10 | 8124175 | 10746 | 44035 | 186287 | 7846572 | 4/4 |
| cold1CSV | 1 | 19605012 | 10546 | 55563 | 72838 | 19442731 | 7/7 |
| cold1CSV | 2 | 13774627 | 10236 | 56124 | 83084 | 13599596 | 7/7 |
| cold1CSV | 3 | 11319382 | 10956 | 53911 | 67871 | 11166754 | 7/7 |
| cold1CSV | 4 | 8690579 | 10246 | 48462 | 70295 | 8540715 | 7/7 |
| cold1CSV | 5 | 9640432 | 20831 | 40510 | 60801 | 9500273 | 7/7 |
| cold1CSV | 6 | 16464356 | 9884 | 134710 | 61872 | 16235295 | 7/7 |
| cold1CSV | 7 | 8443811 | 11517 | 115942 | 82002 | 8204264 | 7/7 |
| cold1CSV | 8 | 37177263 | 8032 | 42353 | 84776 | 37017796 | 7/7 |
| cold1CSV | 9 | 12251880 | 11597 | 56604 | 65508 | 12095517 | 7/7 |
| cold1CSV | 10 | 11557737 | 10506 | 50896 | 81472 | 11391829 | 7/7 |
| warm3CSV | 1 | 17347766 | 15903 | 52488 | 130063 | 17122571 | 12/12 |
| warm3CSV | 2 | 30720635 | 22193 | 61111 | 175071 | 30431975 | 12/12 |
| warm3CSV | 3 | 14962916 | 21041 | 64596 | 183473 | 14650722 | 12/12 |
| warm3CSV | 4 | 17332014 | 18367 | 70195 | 222251 | 16993159 | 12/12 |
| warm3CSV | 5 | 16466284 | 20250 | 58707 | 205045 | 16156754 | 12/12 |
| warm3CSV | 6 | 17636586 | 17936 | 58337 | 160669 | 17371191 | 12/12 |
| warm3CSV | 7 | 37210688 | 18597 | 57205 | 205927 | 36901348 | 12/12 |
| warm3CSV | 8 | 17304852 | 46229 | 63434 | 176453 | 16988611 | 12/12 |
| warm3CSV | 9 | 16122222 | 18237 | 59789 | 114691 | 15904738 | 12/12 |
| warm3CSV | 10 | 21413960 | 17176 | 54942 | 175412 | 21140283 | 12/12 |

## 실측 분포 원문

각 operation/request 행은 한 시도에서 같은 종류 호출의 합에 대한 분포이다. 표본별 전체 operation/request 시간·시작/완료/실패/취소 수는 재실행 시 생성되는 `android/app/build/reports/sync-profiling-baseline/summary.csv`에서 확인한다. 이 파일에는 합성 요약만 있으며 원시 trace·URL·CSV·토큰은 없다.

```csv
scenario,scope,key,n,min_ns,p50_ns,p95_ns,max_ns
warm1CSV,stage,EXECUTION,10,8124175,14181813,18207586,18207586
warm1CSV,stage,HEALTH_READ,10,10746,12739,18407,18407
warm1CSV,stage,NORMALIZE,10,44035,54320,109313,109313
warm1CSV,stage,CSV_EXPORT,10,63825,88091,186287,186287
warm1CSV,stage,DRIVE_TOTAL,10,7846572,13853254,18023131,18023131
warm1CSV,operation,APP_FOLDER_LOOKUP,10,1910705,2556476,3785260,3785260
warm1CSV,operation,DATASET_FOLDER_LOOKUP,10,1767521,2555574,5531826,5531826
warm1CSV,operation,FILE_LOOKUP,10,1761273,2972015,10349288,10349288
warm1CSV,operation,FILE_UPDATE,10,1977454,3059705,6364746,6364746
warm1CSV,request,APP_FOLDER_LOOKUP,10,1810125,2359773,3620786,3620786
warm1CSV,request,DATASET_FOLDER_LOOKUP,10,1676927,2449917,5301212,5301212
warm1CSV,request,FILE_LOOKUP,10,1631538,2873458,10188168,10188168
warm1CSV,request,FILE_UPDATE,10,1803846,2877103,6172409,6172409
cold1CSV,stage,EXECUTION,10,8443811,11557737,37177263,37177263
cold1CSV,stage,HEALTH_READ,10,8032,10506,20831,20831
cold1CSV,stage,NORMALIZE,10,40510,53911,134710,134710
cold1CSV,stage,CSV_EXPORT,10,60801,70295,84776,84776
cold1CSV,stage,DRIVE_TOTAL,10,8204264,11391829,37017796,37017796
cold1CSV,operation,APP_FOLDER_LOOKUP,10,2218763,2984063,6417650,6417650
cold1CSV,operation,APP_FOLDER_CREATE,10,974340,1200517,24978427,24978427
cold1CSV,operation,DATASET_FOLDER_LOOKUP,10,1001752,1489378,1630667,1630667
cold1CSV,operation,DATASET_FOLDER_CREATE,10,972588,1186347,3840507,3840507
cold1CSV,operation,FILE_LOOKUP,10,965737,1749945,3000747,3000747
cold1CSV,operation,FILE_CREATE,10,1198294,1328708,3627821,3627821
cold1CSV,request,APP_FOLDER_LOOKUP,10,2133606,2854229,6254076,6254076
cold1CSV,request,APP_FOLDER_CREATE,10,894442,1121990,24892519,24892519
cold1CSV,request,DATASET_FOLDER_LOOKUP,10,963334,1299545,1582707,1582707
cold1CSV,request,DATASET_FOLDER_CREATE,10,890165,1111374,3743323,3743323
cold1CSV,request,FILE_LOOKUP,10,934762,1716996,2938195,2938195
cold1CSV,request,FILE_CREATE,10,1089943,1230262,3527501,3527501
warm3CSV,stage,EXECUTION,10,14962916,17332014,37210688,37210688
warm3CSV,stage,HEALTH_READ,10,15903,18367,46229,46229
warm3CSV,stage,NORMALIZE,10,52488,58707,70195,70195
warm3CSV,stage,CSV_EXPORT,10,114691,175412,222251,222251
warm3CSV,stage,DRIVE_TOTAL,10,14650722,16993159,36901348,36901348
warm3CSV,operation,APP_FOLDER_LOOKUP,10,3071652,4300251,6473742,6473742
warm3CSV,operation,DATASET_FOLDER_LOOKUP,10,3022019,3460954,5801697,5801697
warm3CSV,operation,FILE_LOOKUP,10,3440082,4596914,19833998,19833998
warm3CSV,operation,FILE_UPDATE,10,3035138,4279410,16830697,16830697
warm3CSV,request,APP_FOLDER_LOOKUP,10,2909862,4136359,6286455,6286455
warm3CSV,request,DATASET_FOLDER_LOOKUP,10,2827099,3287284,5613557,5613557
warm3CSV,request,FILE_LOOKUP,10,3060867,4428714,19602504,19602504
warm3CSV,request,FILE_UPDATE,10,2885045,4110029,16681474,16681474
separate_outcome,execution_ns,requests,started,completed,failed,cancelled,active
http503Failed,101751952,1,1,0,1,0,false
readerCancelled,36985,0,1,0,0,1,false
```

실패는 첫 app lookup의 고정 HTTP 503이며 1 request, 확인 파일 0이다. 실패를 반환한 coordinator 호출은 정상 반환하지만 하네스가 업무 결과로 EXECUTION을 FAILED로 마감하며 started=1/completed=0/failed=1/cancelled=0/active=false를 단언했다. 취소는 합성 reader가 같은 CancellationException을 던지며 0 request, EXECUTION.started=1/completed=0/failed=0/cancelled=1/active=false와 동일한 취소 예외의 전파를 단언했다. TLS 중간 취소의 시간은 이 표본으로 측정하지 않았다. 이 두 결과는 성공 분포에 포함하지 않았다.

## 원본 요약 출력 예시

```text
# 합성 기준선: 시간 단위 ns, 원시 본문·토큰·개인정보 없음
revision=594b7dfa42e5e52583ff06ac85f32c8bcb9d93d8
harness_sha256=baba4dcbde9ebebc000b001ab033c31488e34ba200fafe18410dd2798000f6a9
dirty=true
java=21.0.13+9-LTS;os=Linux/amd64
scenario,sample,scope,key,started,completed,failed,cancelled,elapsed_ns
warm1CSV,1,stage,EXECUTION,1,1,0,0,13098811
warm1CSV,1,stage,HEALTH_READ,1,1,0,0,12298
warm1CSV,1,stage,NORMALIZE,1,1,0,0,60540
warm1CSV,1,stage,CSV_EXPORT,1,1,0,0,73950
```

## 검증과 재실행

저장소 `android` 디렉터리에서:

```bash
python /workspace/tooling/run-health-command.py ./gradlew --offline --no-daemon :app:testDebugUnitTest --tests com.ny.my.health.SyncProfilingBaselineTest --rerun-tasks
```

보정 후 결과: `BUILD SUCCESSFUL in 41s`, JUnit tests=1, failures=0, errors=0, skipped=0. `summary.csv`는 매번 덮어쓴다. 표본 CSV 외에 Gradle의 표준 테스트 보고서는 기존 경로에 생성된다. OkHttp 초기화가 잡아서 출력한 `android.util.Log.isLoggable not mocked` 진단이 stderr에 있지만 테스트 실패는 없었다. 실제 Android 또는 Google 동작을 이 통과로 주장하지 않는다.

## 관찰과 후속 후보

관찰된 합성 입력에서 DRIVE_TOTAL이 대부분을 차지하며, warm3CSV는 12회, cold1CSV는 7회, warm1CSV는 4회 실제 요청이 발생했다. 먼저 실제 기기에서 인증/대기와 Health Connect/로컬 이력 시간을 따로 수집하고, Drive 요청 종류별 횟수와 단계 분포가 같은 양상인지 확인하는 것이 후속 측정 후보이다. 같은 app folder 조회가 warm3CSV에서 3회인 점은 후속 검토 근거지만 캐시·병렬화·배치·재시도 중 어느 방식을 쓸지 결정하지 않는다. 이 local TLS 지연을 Google 지연으로 외삽하거나 SLA·목표 개선율을 정하지 않는다.
