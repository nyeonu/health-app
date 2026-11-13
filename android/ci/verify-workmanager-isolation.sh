#!/usr/bin/env bash
# helper 설치 여부와 실제 instrumentation 결과를 고정 registry로 확인한다.
workmanager_helpers=(com.ny.my.health.WorkManagerStarterInstrumentedTest)
workmanager_probe=com.ny.my.health.WorkManagerIsolationInstrumentedTest
workmanager_isolation_exclusions=$(IFS=,; echo "${workmanager_helpers[*]},$workmanager_probe")

verify_workmanager_isolation() (
  set -euo pipefail
  local evidence=android/app/build/outputs/critical/workmanager-isolation
  local scratch app=com.ny.my.health runner=com.ny.my.health.test/androidx.test.runner.AndroidJUnitRunner
  scratch=$(mktemp -d)
  chmod 700 "$scratch"
  trap 'rm -rf "$scratch"' EXIT
  mkdir -p "$evidence"
  # source의 설치 호출과 registry를 비교하고 각 테스트의 실제 identity를 산출한다.
  python3 - "$scratch" "${workmanager_helpers[@]}" <<'PY'
import pathlib, re, sys
from collections import Counter
scratch = pathlib.Path(sys.argv[1]); registry = sys.argv[2:]
assert len(registry) == len(set(registry)), '중복 helper registry'
root = pathlib.Path('android/app/src/androidTest/java')
installers = []
for p in root.rglob('*.kt'):
    s = p.read_text()
    if re.search(r'\bWorkManagerTestInitHelper\s*\.\s*initializeTestWorkManager\s*\(', s):
        package = re.search(r'^package\s+([\w.]+)', s, re.M).group(1)
        classes = re.findall(r'^class\s+(\w+)', s, re.M)
        assert len(classes) == 1, 'helper source class 확인 실패'
        installers.append(package + '.' + classes[0])
assert Counter(installers) == Counter(registry), 'helper 설치 source와 registry 불일치'
all_tests = []
for p in root.rglob('*.kt'):
    s = p.read_text()
    package = re.search(r'^package\s+([\w.]+)', s, re.M)
    cls = re.search(r'^class\s+(\w+)', s, re.M)
    if not package or not cls: continue
    methods = re.findall(r'@Test(?:\s*\([^)]*\))?\s+fun\s+(\w+)\s*\(', s)
    all_tests.extend(package.group(1) + '.' + cls.group(1) + '#' + m for m in methods)
assert len(all_tests) == len(set(all_tests)), '중복 테스트 identity'
(scratch / 'inventory').write_text('\n'.join(sorted(all_tests)) + '\n')
for i, cls in enumerate(registry):
    methods = [x for x in all_tests if x.startswith(cls + '#')]
    assert methods, 'helper 테스트 없음'
    (scratch / ('helper' + str(i))).write_text('\n'.join(methods) + '\n')
PY
  cp "$scratch/inventory" "$evidence/source-inventory.txt"
  local seen_pids=' ' pid='' stage selector expected output job i status
  run_stage() {
    stage=$1 selector=$2 expected=$3
    output="$scratch/$stage.raw"
    timeout 10s adb shell am force-stop "$app" >"$scratch/stop" 2>&1
    timeout 90s adb shell am instrument -w -r -e class "$selector" "$runner" >"$output" 2>&1 &
    job=$!
    pid=''
    for ((i=0; i<600; i++)); do
      pid=$(timeout 5s adb shell pidof "$app" 2>/dev/null | tr -d '\r' || true)
      [[ "$pid" =~ ^[0-9]+$ ]] && break
      kill -0 "$job" 2>/dev/null || break
      sleep 0.05
    done
    status=0
    wait "$job" || status=$?
    # 원문은 고정 길이로 제한하며 PID가 포함된 출력은 공개하지 않는다.
    [[ $(wc -c <"$output") -le 262144 ]] || { echo "stage=$stage output_too_large=true" >&2; return 1; }
    if grep -Eq '(^|[^0-9])'"$pid"'([^0-9]|$)|[Pp][Ii][Dd][=: ]+[0-9]+' "$output"; then
      echo "stage=$stage private_identifier_in_output=true" >&2; return 1
    fi
    cp "$output" "$evidence/$stage.raw.txt"
    [[ "$status" == 0 ]] || { echo "stage=$stage invocation_failed=true" >&2; return 1; }
    [[ "$pid" =~ ^[0-9]+$ && "$seen_pids" != *" $pid "* ]] || { echo "stage=$stage process_check_failed=true" >&2; return 1; }
    seen_pids+="$pid "
    python3 - "$output" "$expected" "$evidence/$stage.json" "$stage" <<'PY'
import json, pathlib, re, sys
raw = pathlib.Path(sys.argv[1]).read_text(errors='replace').replace('\r', '')
expected = set(pathlib.Path(sys.argv[2]).read_text().splitlines())
assert expected
assert len(re.findall(r'^INSTRUMENTATION_CODE: -1$', raw, re.M)) == 1
assert not re.search(r'FAILURES|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|shortMsg=|Process crashed|^INSTRUMENTATION_STATUS_CODE: (?:-2|-1|2|-3|-4)$', raw, re.M)
started, passed = [], []
# 상태 bundle은 바로 뒤 status code에 귀속된다.
for match in re.finditer(r'((?:^INSTRUMENTATION_STATUS: .*\n)+)^INSTRUMENTATION_STATUS_CODE: (-?\d+)$', raw, re.M):
    bundle, code = match.groups()
    cls = re.search(r'^INSTRUMENTATION_STATUS: class=(.+)$', bundle, re.M)
    method = re.search(r'^INSTRUMENTATION_STATUS: test=(.+)$', bundle, re.M)
    if cls and method:
        identity = cls.group(1) + '#' + method.group(1)
        if code == '1': started.append(identity)
        elif code == '0': passed.append(identity)
        else: raise AssertionError('실패 또는 미완료 status')
assert len(started) == len(expected) and set(started) == expected
assert len(passed) == len(expected) and set(passed) == expected
assert re.search(r'^OK \(' + str(len(expected)) + r' tests?\)$', raw, re.M)
pathlib.Path(sys.argv[3]).write_text(json.dumps({'stage': sys.argv[4], 'count': len(expected), 'pass': True, 'tests': sorted(passed)}, indent=2) + '\n')
PY
    printf 'stage=%s count=%s pass=true\n' "$stage" "$(wc -l <"$expected")"
  }
  printf '%s\n' "$workmanager_probe#normalRuntimeBeforeHelperHasALiveProductionManager" >"$scratch/before"
  printf '%s\n' "$workmanager_probe#normalRuntimeAfterHelperHasALiveProductionManager" >"$scratch/after"
  run_stage normal-before "$(cat "$scratch/before")" "$scratch/before"
  for i in "${!workmanager_helpers[@]}"; do
    run_stage "helper-$i" "${workmanager_helpers[$i]}" "$scratch/helper$i"
  done
  run_stage normal-after "$(cat "$scratch/after")" "$scratch/after"
)
