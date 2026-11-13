#!/usr/bin/env bash
# 전체 에뮬레이터 검증을 한 셸에서 순서대로 실행하고 첫 실패 시 중단한다.
set -e
# 계정 없는 테스트에서 사용하지 않는 SDK 설정 패키지가 있으면 한 번 종료한다.
if adb shell pm list packages com.google.android.googlesdksetup | tr -d '\r' | grep -Fxq 'package:com.google.android.googlesdksetup'; then adb shell am force-stop com.google.android.googlesdksetup; fi
# 테스트 전 기존 launcher 종료를 유지하며 ANR 대화상자 해소는 보장하지 않는다.
adb shell am force-stop com.google.android.apps.nexuslauncher
# 수동 dispatch에서만 고정 진단 범위를 적용하며 나머지 이벤트는 전체 gate를 유지한다.
if [ "${GITHUB_EVENT_NAME:-}" = workflow_dispatch ]; then
  case "${TEST_SCOPE:-full}" in
    full) ;;
    saf-provider-starter)
      if [ ! -f 'android/app/src/androidTest/java/com/ny/my/health/SyntheticProviderStarterTest.kt' ]; then
        echo '고정 진단 테스트 소스가 없습니다: SyntheticProviderStarterTest' >&2
        exit 1
      fi
      (cd android && chmod +x gradlew && ./gradlew --no-daemon :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.ny.my.health.SyntheticProviderStarterTest')
      exit 0
      ;;
    saf-store)
      if [ ! -f 'android/app/src/androidTest/java/com/ny/my/health/ManualDriveStoreInstrumentedTest.kt' ]; then
        echo '고정 진단 테스트 소스가 없습니다: ManualDriveStoreInstrumentedTest' >&2
        exit 1
      fi
      (cd android && chmod +x gradlew && ./gradlew --no-daemon :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.ny.my.health.ManualDriveStoreInstrumentedTest')
      exit 0
      ;;
    saf-capability)
      if [ ! -f 'android/app/src/androidTest/java/com/ny/my/health/SafTreeConfigurationInstrumentedTest.kt' ]; then
        echo '고정 진단 테스트 소스가 없습니다: SafTreeConfigurationInstrumentedTest' >&2
        exit 1
      fi
      (cd android && chmod +x gradlew && ./gradlew --no-daemon :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.ny.my.health.SafTreeConfigurationInstrumentedTest')
      exit 0
      ;;
    saf-destination)
      if [ ! -f 'android/app/src/androidTest/java/com/ny/my/health/SafDestinationInstrumentedTest.kt' ]; then
        echo '고정 진단 테스트 소스가 없습니다: SafDestinationInstrumentedTest' >&2
        exit 1
      fi
      if [ ! -f 'android/app/src/androidTest/java/com/ny/my/health/SafJournalInstrumentedTest.kt' ]; then
        echo '고정 진단 테스트 소스가 없습니다: SafJournalInstrumentedTest' >&2
        exit 1
      fi
      (cd android && chmod +x gradlew && ./gradlew --no-daemon :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.ny.my.health.SafDestinationInstrumentedTest,com.ny.my.health.SafJournalInstrumentedTest')
      exit 0
      ;;
    saf-ui)
      if [ ! -f 'android/app/src/androidTest/java/com/ny/my/health/SafManualUiInstrumentedTest.kt' ]; then
        echo '고정 진단 테스트 소스가 없습니다: SafManualUiInstrumentedTest' >&2
        exit 1
      fi
      (cd android && chmod +x gradlew && ./gradlew --no-daemon :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.ny.my.health.SafManualUiInstrumentedTest')
      exit 0
      ;;
    *)
      echo '알 수 없는 수동 진단 범위입니다.' >&2
      exit 1
      ;;
  esac
fi
# 핵심 화면 4개 클래스를 먼저 실행하고 실패하면 이후 검증을 중단한다.
(cd android && chmod +x gradlew && ./gradlew --no-daemon :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.ny.my.health.HealthAppInstrumentedTest,com.ny.my.health.ManualDriveFlowInstrumentedTest,com.ny.my.health.ManualDriveUiInstrumentedTest,com.ny.my.health.DestinationConfigurationInstrumentedTest)
# 다음 connected 실행이 덮어쓰기 전에 핵심 XML·보고서·추가 출력을 보존한다.
critical_evidence=android/app/build/outputs/critical
mkdir -p "$critical_evidence"
cp -a android/app/build/outputs/androidTest-results/connected "$critical_evidence/androidTest-results"
cp -a android/app/build/reports/androidTests/connected "$critical_evidence/reports"
if [ -d android/app/build/outputs/connected_android_test_additional_output ]; then
  cp -a android/app/build/outputs/connected_android_test_additional_output "$critical_evidence/additional-output"
fi
# 기존 host 전용 2개와 완료한 핵심 4개만 제외하고 나머지 전체를 실행한다.
(cd android && chmod +x gradlew && ./gradlew --no-daemon :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.notClass=com.ny.my.health.HistoryProcessRecoveryInstrumentedTest,com.ny.my.health.SyncStateProcessRecoveryInstrumentedTest,com.ny.my.health.HealthAppInstrumentedTest,com.ny.my.health.ManualDriveFlowInstrumentedTest,com.ny.my.health.ManualDriveUiInstrumentedTest,com.ny.my.health.DestinationConfigurationInstrumentedTest)
bash android/ci/verify-history-process-recovery.sh
bash android/ci/verify-sync-state-process-recovery.sh
