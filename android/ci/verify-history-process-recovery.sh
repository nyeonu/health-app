#!/usr/bin/env bash
# Run after the ordinary connected suite on a fresh account-free emulator.
set -euo pipefail
cd "$(dirname "$0")/.."
app=com.ny.my.health
runner=com.ny.my.health.test/androidx.test.runner.AndroidJUnitRunner
class=com.ny.my.health.HistoryProcessRecoveryInstrumentedTest
scratch=$(mktemp -d)
job=''
evidence=app/build/outputs/v1-synthetic-process/results.txt
mkdir -p app/build/outputs/v1-synthetic-process
: >"$evidence"
recovery_count=0
settings_count=0
cleanup_count=0
old_night=$(adb shell settings get secure ui_night_mode | tr -d '\r')
old_font=$(adb shell settings get system font_scale | tr -d '\r')
restore() {
  if [[ -n "$job" ]]; then adb shell am force-stop "$app" >/dev/null 2>&1 || true; wait "$job" 2>/dev/null || true; fi
  case "$old_night" in
    0) adb shell cmd uimode night auto >/dev/null ;;
    1) adb shell cmd uimode night no >/dev/null ;;
    2) adb shell cmd uimode night yes >/dev/null ;;
    *) adb shell cmd uimode night auto >/dev/null; adb shell settings delete secure ui_night_mode >/dev/null ;;
  esac
  if [[ "$old_font" == null ]]; then adb shell settings delete system font_scale >/dev/null
  else adb shell settings put system font_scale "$old_font" >/dev/null; fi
  rm -rf "$scratch"
}
trap restore EXIT
invoke() {
  local method=$1 output=$2
  shift 2
  timeout 90s adb shell am instrument -w -r -e historyProcessHost true \
    -e class "$class#$method" "$@" "$runner" >"$output" 2>&1
}
passed() {
  local output=$1
  if rg -q 'FAILURES|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|shortMsg=|Process crashed' "$output" ||
     ! rg -q '^OK \(1 test\)' "$output"; then
    cat "$output"
    echo 'Expected exactly one successful instrumentation test' >&2
    exit 1
  fi
}
ready() {
  local marker=$1 output=$2
  for ((i=0; i<600; i++)); do
    if rg -q "$marker pid=[0-9]+" "$output"; then return; fi
    if ! kill -0 "$job" 2>/dev/null; then cat "$output"; echo 'Instrumentation ended before ready' >&2; exit 1; fi
    sleep 0.1
  done
  echo "Timed out waiting for $marker" >&2
  exit 1
}
for fixture in success running authorizing awaiting empty reset-after reset-before reset-empty-before; do
  invoke seedAndHoldUntilHostKillsProcess "$scratch/seed" -e fixture "$fixture" &
  job=$!
  ready HISTORY_READY "$scratch/seed"
  dead_pid=$(sed -n 's/.*HISTORY_READY pid=\([0-9]*\).*/\1/p' "$scratch/seed" | head -1)
  [[ "$dead_pid" =~ ^[0-9]+$ ]]
  [[ " $(adb shell pidof "$app" | tr -d '\r') " == *" $dead_pid "* ]]
  adb shell am force-stop "$app"
  wait "$job" || true # Deliberately killed hold; this invocation is never counted as PASS.
  job=''
  for ((i=0; i<50; i++)); do
    if ! adb shell "test -d /proc/$dead_pid"; then break; fi
    sleep 0.1
  done
  if adb shell "test -d /proc/$dead_pid"; then echo 'Original PID still exists' >&2; exit 1; fi
  invoke reopenedActivityRestoresOnlyCommittedMetadata "$scratch/reopen" -e fixture "$fixture" -e deadPid "$dead_pid"
  passed "$scratch/reopen"
  rg -q 'HISTORY_REOPENED pid=[0-9]+' "$scratch/reopen"
  recovery_count=$((recovery_count + 1))
  printf 'recovery scenario=%s successful_verifier_tests=1 pid_change_verified=true
' "$fixture" >>"$evidence"
  echo "Verified OS process death and committed recovery: $fixture"
  if [[ "$fixture" == success ]]; then
    mkdir -p app/build/outputs/v1-synthetic-process
    for night in no yes; do
      for font in 1 2; do
        if [[ "$night" == yes ]]; then initial=no; else initial=yes; fi
        if [[ "$font" == 1 ]]; then initial_font=2.0; else initial_font=1.0; fi
        adb shell cmd uimode night "$initial"
        adb shell settings put system font_scale "$initial_font"
        invoke liveSystemThemeAndFontChangeReachActivityAndControls "$scratch/settings" -e night "$night" -e font "$font.0" &
        job=$!
        ready HISTORY_SETTINGS_READY "$scratch/settings"
        adb shell cmd uimode night "$night"
        adb shell settings put system font_scale "$font.0"
        wait "$job"
        job=''
        passed "$scratch/settings"
        if [[ "$night" == yes ]]; then theme=dark; else theme=light; fi
        image="system-$theme-$font.png"
        adb pull "/sdcard/Android/data/$app/files/v1-synthetic-process/$image" "app/build/outputs/v1-synthetic-process/$image"
        adb shell rm "/sdcard/Android/data/$app/files/v1-synthetic-process/$image"
        settings_count=$((settings_count + 1))
        printf 'system_settings theme=%s font_scale=%s.0 successful_verifier_tests=1 capture_verified=true
' "$theme" "$font" >>"$evidence"
        echo "Verified live Activity system settings: $theme font=$font.0"
      done
    done
  fi
  invoke cleanupOwnedSyntheticFixture "$scratch/cleanup"
  passed "$scratch/cleanup"
  cleanup_count=$((cleanup_count + 1))
  printf 'cleanup scenario=%s successful_tests=1
' "$fixture" >>"$evidence"
done
printf 'complete=true recovery_verifier_tests=%s system_settings_verifier_tests=%s cleanup_tests=%s killed_holds_counted_as_pass=0
' \
  "$recovery_count" "$settings_count" "$cleanup_count" >>"$evidence"
