# 반복 가능한 합성 동기화 기준선

## 결과와 측정 경계

2026-11-03 UTC 로컬 JVM 실행에서 실제 `System.nanoTime`과 실제 `GoogleDriveTransport` → OkHttp → local TLS를 사용했다. 아래 시간은 실측이며 가상 시계로 만든 값이 아니다. 각 시나리오 3회 준비 실행을 제외하고 10회 성공을 관찰했다. 재현 가능한 것은 입력·상태·요청 순서·계산법이며 시간의 일치를 보장하지 않는다.

`EXECUTION`은 하네스가 `SyncCoordinator.sync(date)`를 감싼 구간이다. 합성 reader 반환, 실제 normalizer/CSV exporter, 실제 uploader/transport와 TLS 왕복이 포함된다. 실제 manual controller 전체 지연은 측정하지 않았다. 인증·사용자 대기·SQLite 이력 저장·실제 Health Connect 읽기·Google 서버/인터넷·Android 기기/화면/배터리/스케줄링 시간은 모두 미측정이다. `HEALTH_READ`는 고정 객체를 반환하는 합성 reader 시간이다. `AUTHORIZATION_HISTORY`, `AUTHORIZATION_WAIT`, `CONTINUE_WAIT`, `START_HISTORY`, `TERMINAL_HISTORY`는 관찰되지 않았으며 0으로 해석하지 않는다.

단계와 operation과 request는 중첩되므로 서로 합산하지 않는다. operation은 uploader 논리 구간, request는 실제 transport 호출 구간이다. 이 기준선은 단일 페이지 성공 응답을 사용하므로 두 호출 수가 같지만 항상 같다는 주장은 아니다. 여러 파일에서 같은 operation의 `elapsedNanos`는 해당 호출들의 합이다.

## 소스와 환경

최종 기준선의 source revision은 하네스를 포함한 `2ccd1d5bbca1a3a2210a39f2f867c7ba03451a53`이며, production 구현 revision은 `2ee094501571d5b30fd00a0e55fb19da4d4c13cc`이다. 오케스트레이터가 커밋된 소스에서 같은 명령을 독립 재실행했으며 원본 요약의 `dirty=false`를 확인했다. 실행한 하네스 SHA-256은 `baba4dcbde9ebebc000b001ab033c31488e34ba200fafe18410dd2798000f6a9`이다. 아래 표본·분포·원문 예시는 이 최종 실행의 고정 보관본에서 가져왔다. 이 문서는 이후 문서 커밋에 포함되므로 문서 커밋과 측정 소스 revision을 구별한다.

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
| warm1CSV | 1 | 17806154 | 14451 | 73360 | 177915 | 17506628 | 4/4 |
| warm1CSV | 2 | 13176762 | 13681 | 58417 | 79238 | 12838148 | 4/4 |
| warm1CSV | 3 | 12810682 | 12678 | 66990 | 105537 | 12592036 | 4/4 |
| warm1CSV | 4 | 30028564 | 13730 | 59158 | 92268 | 29831982 | 4/4 |
| warm1CSV | 5 | 11160075 | 43644 | 65217 | 118897 | 10887759 | 4/4 |
| warm1CSV | 6 | 13657222 | 11798 | 57185 | 83985 | 13472668 | 4/4 |
| warm1CSV | 7 | 11369926 | 12379 | 57005 | 82643 | 11185963 | 4/4 |
| warm1CSV | 8 | 12209432 | 10185 | 47841 | 68612 | 12057385 | 4/4 |
| warm1CSV | 9 | 9240663 | 10546 | 50555 | 71617 | 9081326 | 4/4 |
| warm1CSV | 10 | 7469320 | 12168 | 59739 | 85337 | 7264495 | 4/4 |
| cold1CSV | 1 | 19880827 | 14552 | 81752 | 97846 | 19654770 | 7/7 |
| cold1CSV | 2 | 12864482 | 12799 | 59619 | 81301 | 12681150 | 7/7 |
| cold1CSV | 3 | 32668145 | 12969 | 52828 | 83114 | 32490571 | 7/7 |
| cold1CSV | 4 | 11427583 | 12739 | 62173 | 108312 | 11216308 | 7/7 |
| cold1CSV | 5 | 11145593 | 14521 | 53730 | 89994 | 10962250 | 7/7 |
| cold1CSV | 6 | 11012485 | 11597 | 68742 | 78827 | 10827399 | 7/7 |
| cold1CSV | 7 | 10642885 | 11858 | 60450 | 101921 | 10432551 | 7/7 |
| cold1CSV | 8 | 7585713 | 11016 | 43826 | 102883 | 7389161 | 7/7 |
| cold1CSV | 9 | 7331124 | 9374 | 38767 | 64616 | 7201270 | 7/7 |
| cold1CSV | 10 | 7068133 | 8593 | 39408 | 57937 | 6945370 | 7/7 |
| warm3CSV | 1 | 21839164 | 16855 | 52929 | 207099 | 21540568 | 12/12 |
| warm3CSV | 2 | 15802301 | 18738 | 57255 | 203253 | 15500472 | 12/12 |
| warm3CSV | 3 | 13715309 | 18647 | 54751 | 124986 | 13483283 | 12/12 |
| warm3CSV | 4 | 13515551 | 15373 | 55863 | 131787 | 13293551 | 12/12 |
| warm3CSV | 5 | 18137142 | 15624 | 300357 | 199457 | 17599612 | 12/12 |
| warm3CSV | 6 | 15775140 | 16604 | 50775 | 142012 | 15543415 | 12/12 |
| warm3CSV | 7 | 11751666 | 15653 | 56734 | 230253 | 11428684 | 12/12 |
| warm3CSV | 8 | 14939481 | 33480 | 51136 | 672031 | 14162324 | 12/12 |
| warm3CSV | 9 | 19846957 | 32088 | 51737 | 141371 | 19601122 | 12/12 |
| warm3CSV | 10 | 10007918 | 16295 | 56063 | 123564 | 9762193 | 12/12 |

## 실측 분포 원문

각 operation/request 행은 한 시도에서 같은 종류 호출의 합에 대한 분포이다. 표본별 전체 operation/request 시간·시작/완료/실패/취소 수는 재실행 시 생성되는 `android/app/build/reports/sync-profiling-baseline/summary.csv`에서 확인한다. 이 파일에는 합성 요약만 있으며 원시 trace·URL·CSV·토큰은 없다.

```csv
scenario,scope,key,n,min_ns,p50_ns,p95_ns,max_ns
warm1CSV,stage,EXECUTION,10,7469320,12209432,30028564,30028564
warm1CSV,stage,HEALTH_READ,10,10185,12379,43644,43644
warm1CSV,stage,NORMALIZE,10,47841,58417,73360,73360
warm1CSV,stage,CSV_EXPORT,10,68612,83985,177915,177915
warm1CSV,stage,DRIVE_TOTAL,10,7264495,12057385,29831982,29831982
warm1CSV,operation,APP_FOLDER_LOOKUP,10,1988070,2801881,3913216,3913216
warm1CSV,operation,DATASET_FOLDER_LOOKUP,10,1787041,2520662,4419440,4419440
warm1CSV,operation,FILE_LOOKUP,10,1756375,2290740,4770452,4770452
warm1CSV,operation,FILE_UPDATE,10,1507054,2548474,21061255,21061255
warm1CSV,request,APP_FOLDER_LOOKUP,10,1879228,2627953,3775290,3775290
warm1CSV,request,DATASET_FOLDER_LOOKUP,10,1616898,2349878,4243097,4243097
warm1CSV,request,FILE_LOOKUP,10,1626331,2151763,4662411,4662411
warm1CSV,request,FILE_UPDATE,10,1434365,2393428,20902148,20902148
cold1CSV,stage,EXECUTION,10,7068133,11012485,32668145,32668145
cold1CSV,stage,HEALTH_READ,10,8593,11858,14552,14552
cold1CSV,stage,NORMALIZE,10,38767,53730,81752,81752
cold1CSV,stage,CSV_EXPORT,10,57937,83114,108312,108312
cold1CSV,stage,DRIVE_TOTAL,10,6945370,10827399,32490571,32490571
cold1CSV,operation,APP_FOLDER_LOOKUP,10,1940749,2846718,6225057,6225057
cold1CSV,operation,APP_FOLDER_CREATE,10,947491,1478911,4565336,4565336
cold1CSV,operation,DATASET_FOLDER_LOOKUP,10,831318,1386875,2398020,2398020
cold1CSV,operation,DATASET_FOLDER_CREATE,10,845729,1576377,20854888,20854888
cold1CSV,operation,FILE_LOOKUP,10,875223,1177292,2011635,2011635
cold1CSV,operation,FILE_CREATE,10,949304,1447755,2172554,2172554
cold1CSV,request,APP_FOLDER_LOOKUP,10,1851957,2716824,6050467,6050467
cold1CSV,request,APP_FOLDER_CREATE,10,875233,1360185,4469303,4469303
cold1CSV,request,DATASET_FOLDER_LOOKUP,10,802104,1339224,2365081,2365081
cold1CSV,request,DATASET_FOLDER_CREATE,10,778008,1478591,20729742,20729742
cold1CSV,request,FILE_LOOKUP,10,775404,1133017,1966108,1966108
cold1CSV,request,FILE_CREATE,10,867391,1291312,1956252,1956252
warm3CSV,stage,EXECUTION,10,10007918,14939481,21839164,21839164
warm3CSV,stage,HEALTH_READ,10,15373,16604,33480,33480
warm3CSV,stage,NORMALIZE,10,50775,54751,300357,300357
warm3CSV,stage,CSV_EXPORT,10,123564,142012,672031,672031
warm3CSV,stage,DRIVE_TOTAL,10,9762193,14162324,21540568,21540568
warm3CSV,operation,APP_FOLDER_LOOKUP,10,2219354,3800468,6604638,6604638
warm3CSV,operation,DATASET_FOLDER_LOOKUP,10,2206234,2627231,9194709,9194709
warm3CSV,operation,FILE_LOOKUP,10,2175569,2673701,9268620,9268620
warm3CSV,operation,FILE_UPDATE,10,2792898,3262924,5949637,5949637
warm3CSV,request,APP_FOLDER_LOOKUP,10,2036041,3137742,6434502,6434502
warm3CSV,request,DATASET_FOLDER_LOOKUP,10,1975691,2477679,8953159,8953159
warm3CSV,request,FILE_LOOKUP,10,2076961,2541043,9135340,9135340
warm3CSV,request,FILE_UPDATE,10,2560653,3124115,5820656,5820656
separate_outcome,execution_ns,requests,started,completed,failed,cancelled,active
http503Failed,102775717,1,1,0,1,0,false
readerCancelled,51227,0,1,0,0,1,false
```

실패는 첫 app lookup의 고정 HTTP 503이며 1 request, 확인 파일 0이다. 실패를 반환한 coordinator 호출은 정상 반환하지만 하네스가 업무 결과로 EXECUTION을 FAILED로 마감하며 started=1/completed=0/failed=1/cancelled=0/active=false를 단언했다. 취소는 합성 reader가 같은 CancellationException을 던지며 0 request, EXECUTION.started=1/completed=0/failed=0/cancelled=1/active=false와 동일한 취소 예외의 전파를 단언했다. TLS 중간 취소의 시간은 이 표본으로 측정하지 않았다. 이 두 결과는 성공 분포에 포함하지 않았다.

## 원본 요약 출력 예시

```text
# 합성 기준선: 시간 단위 ns, 원시 본문·토큰·개인정보 없음
revision=2ccd1d5bbca1a3a2210a39f2f867c7ba03451a53
harness_sha256=baba4dcbde9ebebc000b001ab033c31488e34ba200fafe18410dd2798000f6a9
dirty=false
java=21.0.13+9-LTS;os=Linux/amd64
scenario,sample,scope,key,started,completed,failed,cancelled,elapsed_ns
warm1CSV,1,stage,EXECUTION,1,1,0,0,17806154
warm1CSV,1,stage,HEALTH_READ,1,1,0,0,14451
warm1CSV,1,stage,NORMALIZE,1,1,0,0,73360
warm1CSV,1,stage,CSV_EXPORT,1,1,0,0,177915
```

## 검증과 재실행

저장소 `android` 디렉터리에서:

```bash
python /workspace/tooling/run-health-command.py ./gradlew --offline --no-daemon :app:testDebugUnitTest --tests com.ny.my.health.SyncProfilingBaselineTest --rerun-tasks
```

커밋된 소스의 최종 독립 실행 결과: `BUILD SUCCESSFUL in 42s`, JUnit tests=1, failures=0, errors=0, skipped=0. `summary.csv`는 매번 덮어쓴다. 표본 CSV 외에 Gradle의 표준 테스트 보고서는 기존 경로에 생성된다. OkHttp 초기화가 잡아서 출력한 `android.util.Log.isLoggable not mocked` 진단이 stderr에 있지만 테스트 실패는 없었다. 실제 Android 또는 Google 동작을 이 통과로 주장하지 않는다.

## 관찰과 후속 후보

관찰된 합성 입력에서 DRIVE_TOTAL이 대부분을 차지하며, warm3CSV는 12회, cold1CSV는 7회, warm1CSV는 4회 실제 요청이 발생했다. 먼저 실제 기기에서 인증/대기와 Health Connect/로컬 이력 시간을 따로 수집하고, Drive 요청 종류별 횟수와 단계 분포가 같은 양상인지 확인하는 것이 후속 측정 후보이다. 같은 app folder 조회가 warm3CSV에서 3회인 점은 후속 검토 근거지만 캐시·병렬화·배치·재시도 중 어느 방식을 쓸지 결정하지 않는다. 이 local TLS 지연을 Google 지연으로 외삽하거나 SLA·목표 개선율을 정하지 않는다.
