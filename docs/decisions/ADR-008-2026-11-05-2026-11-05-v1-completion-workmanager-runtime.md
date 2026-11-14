# ADR-008: WorkManager 2.11.0과 공용 admission으로 자동 실행

## 상태
Proposed

## 날짜
2026-11-05

## 티켓
2026-11-05-v1-completion

## 배경
현재 WorkManager/worker test 기반은 없다. 승인된 V1은 약24시간·connected/battery-not-low 제약과 최대7 날짜 batch를 요구하며 exact time을 보장하지 않는다. 공식 release notes와 Maven POM/AAR에서 2.11.0 stable, minSdk23/minCompileSdk35/minAGP8.6.0/Kotlin2.1.20/coroutines1.9.0을 확인했고 현재 앱 하한과 호환된다.

소유자의 “병목 개선부터 V1 끝까지 자동승인” 위임을 근거로 approved 에픽 계획의 결정 시점에 기록한다. 아직 구현/적합성 검증을 마치지 않았으므로 Accepted가 아니다.

## 결정
androidx.work:work-runtime:2.11.0과 androidTest work-testing:2.11.0을 도입하여 CoroutineWorker와 unique periodic/one-time catch-up을 사용한다. Application 소유 runtime의 동일 canonical-path admission gate/control/owner를 foreground와 worker가 공유한다. 수동 세션은 기존 manual reader를 유지하고 worker마다 fresh automatic reader와 새 SyncExecutionSession/DestinationSync를 같은 reader로 구성한다. generation/scope/frozen cohort의 durable retry lineage를 private journal에 보관하며 revision 관찰로 budget을 초기화하지 않는다(상세 Proposed ADR-010).

## 검토한 대안
### 자체 polling/상시 service
- 장점: 주기 흐름을 앱 코드로 제어한다
- 단점: OS lifecycle/Doze/배터리 책임과 지속 프로세스 비용이 커진다
- 기각 이유: AGENTS와 스펙의 deferrable 작업 원칙에 따라 기각

### unique work만으로 중복 방지
- 장점: 추가 gate 구현이 적다
- 단점: manual과 다른 unique 이름 사이 중복 실행을 막지 못한다
- 기각 이유: 모든 trigger를 포괄하는 공용 gate가 필요해 기각

### work-runtime-ktx와 multiprocess 추가
- 장점: 기존 문서 예제 형태를 따른다
- 단점: CoroutineWorker는 runtime에 있고 multiprocess는 지원 범위 밖이다
- 기각 이유: 불필요한 artifact와 프로세스 복잡성을 피하기 위해 기각

## 결과와 비용
초기 도입 T에서 실제 resolved graph/merged manifest audit와 starter worker 기동을 통과해야 기능 구현을 시작한다. Configuration.Provider/WorkerFactory와 WorkManagerInitializer 제거가 필요하고 기본 diagnostics receiver는 공개 식별자 노출을 줄이기 위해 비활성화한다. retry cap은 한 미종결 frozen cohort lineage의 transient 재시도(최초 외 최대3회)이며 periodic lifetime을 영구 중단시키지 않는다. HC background feature/grant·account/provider capability 부재는 BLOCKED이고 UI를 시작하지 않는다. 자동 읽기는 기존 whole-day raw HR reader를 재사용하지 않고 지원 Aggregate와 운동구간 bounded query만 사용한다. provider interval-start 의미를 안전하게 확인할 수 없으면 상세HR은 BLOCKED이고 다른 허용 category만 진행한다. 범위는 WORKOUT_SCOPED로 저장하여 manual EXPLICIT_DAY와 혼합하지 않는다. broad raw history 우회는 금지한다. Doze/reboot/force-stop/배터리·실계정 수용은 별도 대기이다.

## 참고 자료
- [승인 스펙](../2026-11-05-v1-completion/spec.md)
- [에픽 계획](../2026-11-05-v1-completion/plan.md)
- [WorkManager release notes](https://developer.android.com/jetpack/androidx/releases/work)
- [공식 runtime 2.11.0 POM](https://dl.google.com/dl/android/maven2/androidx/work/work-runtime/2.11.0/work-runtime-2.11.0.pom)
- [unique work](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/manage-work)
