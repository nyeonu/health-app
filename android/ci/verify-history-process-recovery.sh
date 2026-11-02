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
stop_job() {
  if [[ -z "$job" ]]; then return; fi
  for ((reap=0; reap<50; reap++)); do
    if ! kill -0 "$job" 2>/dev/null; then break; fi
    sleep 0.1
  done
  if kill -0 "$job" 2>/dev/null; then
    echo 'Instrumentation host job did not exit after force-stop; terminating bounded wrapper' >&2
    kill -TERM "$job" 2>/dev/null || true
  fi
  wait "$job" 2>/dev/null || true
  job=''
}
restore() {
  if [[ -n "$job" ]]; then timeout 5s adb shell am force-stop "$app" >/dev/null 2>&1 || true; stop_job; fi
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
# connectedDebugAndroidTest may uninstall both packages during teardown.
# Reinstall only these already-built artifacts; -r preserves any existing data,
# which the instrumentation fixture must reject before seeding.
for apk in app/build/outputs/apk/debug/app-debug.apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk; do
  if [[ ! -f "$apk" ]]; then
    echo "Required built instrumentation artifact is missing: $apk" >&2
    exit 1
  fi
  if ! timeout 60s adb install -r -t "$apk" >"$scratch/install" 2>&1; then
    cat "$scratch/install"
    echo "Unable to install required built artifact: $apk" >&2
    exit 1
  fi
  if ! grep -Eq '^Success[[:space:]]*$' "$scratch/install"; then
    cat "$scratch/install"
    echo "Package installation did not report success: $apk" >&2
    exit 1
  fi
done
if ! timeout 30s adb shell pm list instrumentation >"$scratch/instrumentation" 2>&1; then
  echo 'Unable to query installed instrumentation' >&2
  exit 1
fi
if ! grep -Fq "instrumentation:$runner (target=$app)" "$scratch/instrumentation"; then
  echo 'Required AndroidJUnitRunner instrumentation is not registered' >&2
  exit 1
fi
invoke() {
  local method=$1 output=$2
  shift 2
  local status
  if timeout 90s adb shell am instrument -w -r -e historyProcessHost true \
    -e class "$class#$method" "$@" "$runner" >"$output" 2>&1; then
    return 0
  else
    status=$?
    cat "$output" >&2
    echo "Instrumentation method $method failed or timed out (exit=$status)" >&2
    return "$status"
  fi
}
passed() {
  local output=$1
  if grep -Eq 'FAILURES|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|shortMsg=|Process crashed' "$output" ||
     ! grep -Eq '^OK \(1 test\)' "$output"; then
    cat "$output"
    echo 'Expected exactly one successful instrumentation test' >&2
    exit 1
  fi
}
ready() {
  local marker=$1 output=$2
  for ((i=0; i<600; i++)); do
    if [[ -r "$output" ]] && grep -Eq "$marker pid=[0-9]+" "$output"; then return; fi
    if ! kill -0 "$job" 2>/dev/null; then cat "$output"; echo 'Instrumentation ended before ready' >&2; exit 1; fi
    sleep 0.1
  done
  if [[ -r "$output" ]]; then cat "$output" >&2; fi
  echo "Timed out waiting for $marker" >&2
  exit 1
}
for fixture in success running authorizing awaiting empty reset-after reset-before reset-empty-before; do
  seed_output="$scratch/seed-$fixture"
  reopen_output="$scratch/reopen-$fixture"
  cleanup_output="$scratch/cleanup-$fixture"
  : >"$seed_output" # Parent clears this invocation's unique file before spawning.
  echo "Starting bounded process fixture: $fixture"
  invoke seedAndHoldUntilHostKillsProcess "$seed_output" -e fixture "$fixture" &
  job=$!
  ready HISTORY_READY "$seed_output"
  dead_pid=$(sed -n 's/.*HISTORY_READY pid=\([0-9]*\).*/\1/p' "$seed_output" | head -1)
  if [[ ! "$dead_pid" =~ ^[0-9]+$ ]]; then
    cat "$seed_output" >&2
    echo "Invalid ready PID for synthetic fixture: $fixture" >&2
    exit 1
  fi
  if [[ " $(adb shell pidof "$app" | tr -d '\r') " != *" $dead_pid "* ]]; then
    cat "$seed_output" >&2
    echo "Ready PID is not the live target for synthetic fixture: $fixture" >&2
    exit 1
  fi
  adb shell am force-stop "$app"
  stop_job # Deliberately killed hold; this invocation is never counted as PASS.
  for ((i=0; i<50; i++)); do
    if ! adb shell "test -d /proc/$dead_pid"; then break; fi
    sleep 0.1
  done
  if adb shell "test -d /proc/$dead_pid"; then echo 'Original PID still exists' >&2; exit 1; fi
  invoke reopenedActivityRestoresOnlyCommittedMetadata "$reopen_output" -e fixture "$fixture" -e deadPid "$dead_pid"
  passed "$reopen_output"
  if ! grep -Eq 'HISTORY_REOPENED pid=[0-9]+' "$reopen_output"; then
    cat "$reopen_output" >&2
    echo "Missing successful reopen marker for synthetic fixture: $fixture" >&2
    exit 1
  fi
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
        settings_output="$scratch/settings-$night-$font"
        : >"$settings_output"
        invoke liveSystemThemeAndFontChangeReachActivityAndControls "$settings_output" -e night "$night" -e font "$font.0" &
        job=$!
        ready HISTORY_SETTINGS_READY "$settings_output"
        adb shell cmd uimode night "$night"
        adb shell settings put system font_scale "$font.0"
        if ! wait "$job"; then
          cat "$settings_output" >&2
          echo "Live settings invocation failed: night=$night font=$font.0" >&2
          job=''
          exit 1
        fi
        job=''
        passed "$settings_output"
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
  invoke cleanupOwnedSyntheticFixture "$cleanup_output"
  passed "$cleanup_output"
  cleanup_count=$((cleanup_count + 1))
  printf 'cleanup scenario=%s successful_tests=1
' "$fixture" >>"$evidence"
done
printf 'complete=true recovery_verifier_tests=%s system_settings_verifier_tests=%s cleanup_tests=%s killed_holds_counted_as_pass=0
' \
  "$recovery_count" "$settings_count" "$cleanup_count" >>"$evidence"
