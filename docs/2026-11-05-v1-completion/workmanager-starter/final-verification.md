# WorkManager 기반 개발 검증

검증 source: `ea21e9d75c0661b3db3a18543d13d940b8650799`. [실제 양API 전체 실행](https://github.com/nyeonu/health-app/actions/runs/43453138754)은 SUCCESS이며 root가 ZIP hash/원본 XML/class+method 및 host 결과를 직접 대조했다.

| API | artifact | SHA-256 | Native | 기존 host | SyncState host |
|---|---|---|---|---|---|
|35|14055128517|8239cc83a8b32db49bf64de6f62704fbca0c7292f365fa5d1d8e555cfc20e8d6|121/0 fail/error/skip/dup|20|17 verifier +17 cleanup|
|36|14055911186|9993408ab46f1e3ba79303587b38f1c462339e6e03d3a6bffddf6897c726cffe|121/0 fail/error/skip/dup|20|17 verifier +17 cleanup|

critical14+remainder107이며 killed holds를 PASS로 센 수는0이다. 새 합성 CoroutineWorker1회 실제 SUCCESS와 기존120개 회귀가 모두 포함된다. 초기감사는155실제selected공개Maven좌표/네runtime graph/OSV취약점0·오류0과실제AAR/source/javap/debug·release mergedmanifest를대조했다. 기존JVM176/0fail/error/skip, lint/APK/testAPK성공은원래명령·로그기준으로보존하며소스동일성을확인한검사를불필요하게반복하지않았다.

실제느린구간도보존한다. API35 job40분08초/Native+host단계37분27초중hotfixture4method합1208.286초, 나머지117합451.045초다. API36 job38분04초/단계35분34초중hot4합980.904초, 나머지117합462.947초다. 현재45분job한도안에서통과했으나future자동worker회귀가늘면검증시간추가분해/실측최적화가필요할수있다. 이수치는앱업로드latency나배터리측정이아니다. hotfixture실제main/journal spill·magic·readonly·원본preservation단언은완화하지않았다.

이번기반변경은두WorkManager dependency와test-only기반/fixture를준비한것이며production customApplication/자동workenqueue는아직없다. 기본initializer/component와privilegedDUMP diagnostics의의미는감사에기록돼있고후속자동화계획에서명시배선한다. publicTestInitHelper.close는globaldelegate를복원하지않으므로productionApplication도입전에별도instrumentationprocess격리를적용한다.

통합후actualsource/graph대조와최종freshPR검토·한국어댓글·개발merge는아직수행하지않았다. Galaxy/실Google계정/backgroundHC/Doze/reboot/battery/TalkBack수용은미확인이다. ADR008은Proposed를유지한다.
