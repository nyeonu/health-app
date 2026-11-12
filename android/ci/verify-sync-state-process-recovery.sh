#!/usr/bin/env bash
# 계정 없는 에뮬레이터에서 실제 프로세스를 종료하고 고정 단계의 내구성만 검증한다.
set -euo pipefail
cd "$(dirname "$0")/.."
app=com.ny.my.health
runner=com.ny.my.health.test/androidx.test.runner.AndroidJUnitRunner
class=com.ny.my.health.SyncStateProcessRecoveryInstrumentedTest
scratch=$(mktemp -d)
chmod 700 "$scratch"
job=''
evidence=app/build/outputs/v1-synthetic-sync-state-process/results.txt
mkdir -p "$(dirname "$evidence")"
: >"$evidence"
recovery_count=0
cleanup_count=0
current_fixture=NONE
current_stage=install
current_output=''
fail() {
  local safe_fixture=NONE safe_stage=NONE safe_line='' private_line diagnostic
  # 공개 진단은 fixtureㆍ단계 allowlist와 고정 테스트 파일의 숫자 줄 번호만 허용한다.
  case "$current_fixture" in
    pending-before|pending-after|confirm-before|confirm-after|running|reset-before|reset-after|\
    initial-schema-ready|initial-before-publish|initial-published|reset-owner-before|reset-owner-writing|\
    reset-owner-written|reset-stage-schema-ready|reset-prepared|reset-marker-published|reset-spill-before) safe_fixture=$current_fixture ;;
  esac
  case "$current_stage" in
    install|instrumentation|seed-ready|force-stop|reopen|cleanup|complete) safe_stage=$current_stage ;;
  esac
  if [[ -n "$current_output" && -f "$current_output" ]]; then
    while IFS= read -r private_line; do
      if [[ "$private_line" =~ SyncStateProcessRecoveryInstrumentedTest\.kt:([0-9]+) ]]; then
        safe_line=${BASH_REMATCH[1]}
        break
      fi
    done <"$current_output"
  fi
  diagnostic="failure fixture=$safe_fixture current_stage=$safe_stage test_pass=false"
  if [[ -n "$safe_line" ]]; then diagnostic+=" test_line=$safe_line"; fi
  printf '%s\n' "$diagnostic" >&2
  printf '%s\n' "$diagnostic" >>"$evidence"
  exit 1
}
stop_job() {
  if [[ -z "$job" ]]; then return; fi
  for ((reap=0; reap<50; reap++)); do
    if ! kill -0 "$job" 2>/dev/null; then break; fi
    sleep 0.1
  done
  if kill -0 "$job" 2>/dev/null; then kill -TERM "$job" 2>/dev/null || true; fi
  wait "$job" 2>/dev/null || true
  job=''
}
finish() {
  if [[ -n "$job" ]]; then timeout 5s adb shell am force-stop "$app" >/dev/null 2>&1 || true; stop_job; fi
  # mktemp 전용 비공개 host 출력만 제거한다. 기기의 fixture 삭제는 검증된 테스트만 담당한다.
  rm -rf "$scratch"
}
trap finish EXIT
for apk in app/build/outputs/apk/debug/app-debug.apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk; do
  current_output="$scratch/install"
  [[ -f "$apk" ]] || fail
  timeout 60s adb install -r -t "$apk" >"$scratch/install" 2>&1 || fail
  grep -Eq '^Success[[:space:]]*$' "$scratch/install" || fail
done
current_stage=instrumentation
current_output="$scratch/instrumentation"
timeout 30s adb shell pm list instrumentation >"$scratch/instrumentation" 2>&1 || fail
grep -Fq "instrumentation:$runner (target=$app)" "$scratch/instrumentation" || fail
invoke() {
  local method=$1 output=$2
  shift 2
  timeout 90s adb shell am instrument -w -r -e syncStateProcessHost true \
    -e class "$class#$method" "$@" "$runner" >"$output" 2>&1
}
passed() {
  local output=$1
  if grep -Eq 'FAILURES|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|shortMsg=|Process crashed' "$output" ||
     ! grep -Eq '^OK \(1 test\)' "$output"; then fail; fi
}
ready() {
  local output=$1
  for ((i=0; i<600; i++)); do
    if [[ -r "$output" ]] && grep -Eq 'SYNC_STATE_READY pid=[0-9]+' "$output"; then return; fi
    kill -0 "$job" 2>/dev/null || fail
    sleep 0.1
  done
  fail
}
for fixture in pending-before pending-after confirm-before confirm-after running reset-before reset-after \
  initial-schema-ready initial-before-publish initial-published reset-owner-before reset-owner-writing \
  reset-owner-written reset-stage-schema-ready reset-prepared reset-marker-published reset-spill-before; do
  current_fixture=$fixture
  current_stage=seed-ready
  seed_output="$scratch/seed-$fixture"
  reopen_output="$scratch/reopen-$fixture"
  cleanup_output="$scratch/cleanup-$fixture"
  current_output=$seed_output
  : >"$seed_output"
  invoke seedAndHoldUntilHostKillsProcess "$seed_output" -e fixture "$fixture" &
  job=$!
  ready "$seed_output"
  if [[ "$fixture" == reset-spill-before ]]; then
    grep -Eq '^SYNC_STATE_SPILL main_digest_changed=true journal_magic_verified=true journal_regular=true seeded_rows=20000 pid=[0-9]+' "$seed_output" || fail
    printf 'spill fixture=reset-spill-before main_digest_changed=true journal_magic_verified=true journal_regular=true seeded_rows=20000\n' >>"$evidence"
  fi
  dead_pid=$(sed -n 's/.*SYNC_STATE_READY pid=\([0-9]*\).*/\1/p' "$seed_output" | head -1)
  [[ "$dead_pid" =~ ^[0-9]+$ ]] || fail
  live_pids=$(timeout 5s adb shell pidof "$app" 2>"$scratch/pidof-error" | tr -d '\r') || fail
  [[ " $live_pids " == *" $dead_pid "* ]] || fail
  current_stage=force-stop
  timeout 5s adb shell am force-stop "$app" >"$scratch/stop" 2>&1 || fail
  stop_job
  # 의도적으로 죽인 hold가 정상 반환한 경우도 실패이며 PASS에 포함하지 않는다.
  if grep -Eq '^OK \(' "$seed_output"; then fail; fi
  for ((i=0; i<50; i++)); do
    if ! timeout 5s adb shell "test -d /proc/$dead_pid" >/dev/null 2>&1; then break; fi
    sleep 0.1
  done
  if timeout 5s adb shell "test -d /proc/$dead_pid" >/dev/null 2>&1; then fail; fi
  # 연결 장애를 PID 종료로 오인하지 않도록 독립적인 기기 응답을 확인한다.
  timeout 5s adb shell true >"$scratch/alive" 2>&1 || fail
  current_stage=reopen
  current_output=$reopen_output
  invoke reopeningAroundAtomicCommitsNeverConfirmsAnUnacknowledgedFileOrDate "$reopen_output" \
    -e fixture "$fixture" -e deadPid "$dead_pid" || fail
  passed "$reopen_output"
  new_pid=$(sed -n 's/.*SYNC_STATE_REOPENED pid=\([0-9]*\).*/\1/p' "$reopen_output" | head -1)
  [[ "$new_pid" =~ ^[0-9]+$ && "$new_pid" != "$dead_pid" ]] || fail
  recovery_count=$((recovery_count + 1))
  printf 'recovery fixture=%s successful_verifier_tests=1 pid_change_verified=true read_first_verified=true\n' "$fixture" >>"$evidence"
  current_stage=cleanup
  current_output=$cleanup_output
  invoke cleanupOwnedSyntheticFixture "$cleanup_output" -e fixture "$fixture" || fail
  passed "$cleanup_output"
  cleanup_count=$((cleanup_count + 1))
  printf 'cleanup fixture=%s successful_tests=1\n' "$fixture" >>"$evidence"
done
current_fixture=NONE
current_stage=complete
current_output=''
[[ "$recovery_count" == 17 && "$cleanup_count" == 17 ]] || fail
printf 'complete=true recovery_verifier_tests=%s cleanup_tests=%s killed_holds_counted_as_pass=0\n' \
  "$recovery_count" "$cleanup_count" >>"$evidence"
echo 'complete=true recovery_verifier_tests=17 cleanup_tests=17 killed_holds_counted_as_pass=0'
