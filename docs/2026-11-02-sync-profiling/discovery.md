# 사전 조사 — 수동 동기화의 요청 구조

기준: master `671d213cd8baf2c386436ae0bec2e0b905ef3da2`. 제품 코드는 변경하지 않았다.

## 실제 확인

새 master worktree에서 다음 기존 테스트를 캐시 없이 실행했다(새 build directory,4 Gradle tasks executed).

```bash
cd android
python /workspace/tooling/run-health-command.py ./gradlew --offline --no-daemon :core:test --tests com.ny.my.health.core.PrivateDriveUploaderTest
```

결과:9 tests,0failures/errors/skips, BUILD SUCCESSFUL in26s. 이는 컴파일을 포함한 작업 시간이고 업로드 시간 측정치가 아니다. 테스트 transport는 합성 대역이며 실제 Google 왕복 시간이 아니다.

| 조건 | 요청 구조 | 확인 수준 |
|---|---|---|
| 기존 앱/데이터 폴더·날짜 파일, 단일 페이지 | GET3 → PATCH1 | 기존 warm-path 테스트가 실제 호출 순서를 단언하고 통과 |
| 앱 폴더부터 없는 첫 파일 | 조회·생성·루트 재확인·폴더 조회/생성·파일 조회/생성, 총7개 transport 호출 | 코드 경로 및 정상 응답 순서의 합성 회귀 통과; 실서비스 지연은 미측정 |
| 기존 트리에 CSV3개, 각 조회 단일 페이지 | 같은 구조를 반복하면 총12개 호출(조회9+갱신3) | 코드상 외삽이며 새 end-to-end 측정 결과가 아님 |

`PrivateDriveUploader.upsert` 전체를 하나의 mutex가 감싸고, `SyncCoordinator`도 파일을 순서대로 호출한다. 따라서 호출자만 병렬화하면 현재 uploader 내부의 직렬 구간은 그대로다. 파일마다 반복되는 폴더 조회와 실제 전송을 나눠 측정할 이유가 있으나, 이것만으로 실사용 지연의 주원인이라고 확정할 수는 없다.

## 아직 없는 증거

- 실제 기기의 Health Connect 읽기·정규화·CSV·Drive metadata/전송·SQLite 커밋별 latency.
- 인증/사용자 대기를 제외한 실행 시간, 반복 비교 조건 및 분포.
- 캐시·배치·병렬화로 얻는 이득과 private-target 재검증·동시 생성·취소·부분 실패에 주는 영향.

실제 최적화는 하지 않았다. [측정 스펙](spec.md)은 2026-11-02 사용자 진행 지시로 승인됐고 다음은 실행 계획 검토·승인이며, 수치 목표는 기준선 뒤에 정한다. 현재 P0/Galaxy/TalkBack 미확인은 완료로 바꾸지 않는다.
