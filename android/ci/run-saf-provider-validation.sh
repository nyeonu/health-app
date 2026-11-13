#!/usr/bin/env bash
set -e

# 계정 없는 테스트에서 사용하지 않는 SDK 설정 패키지가 있으면 한 번 종료한다.
if adb shell pm list packages com.google.android.googlesdksetup | tr -d '\r' | grep -Fxq 'package:com.google.android.googlesdksetup'; then adb shell am force-stop com.google.android.googlesdksetup; fi
# 테스트 전 기존 launcher 종료를 유지하며 ANR 대화상자 해소는 보장하지 않는다.
adb shell am force-stop com.google.android.apps.nexuslauncher
if [ "${GITHUB_EVENT_NAME:-}" = "workflow_dispatch" ] && [ "${TEST_SCOPE:-full}" = "saf-provider-starter" ]; then
  # 기반 기동 진단 전용이며 전체 회귀 통과를 뜻하지 않는다.
  (cd android && chmod +x gradlew && ./gradlew --no-daemon :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.ny.my.health.SyntheticProviderStarterTest)
else
  (cd android && chmod +x gradlew && ./gradlew --no-daemon :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.notClass=com.ny.my.health.HistoryProcessRecoveryInstrumentedTest,com.ny.my.health.SyncStateProcessRecoveryInstrumentedTest)
  bash android/ci/verify-history-process-recovery.sh
  bash android/ci/verify-sync-state-process-recovery.sh
fi
