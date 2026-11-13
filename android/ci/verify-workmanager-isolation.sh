#!/usr/bin/env bash
# helper 설치 여부와 실제 instrumentation 결과를 고정 registry로 확인한다.
workmanager_helpers=(com.ny.my.health.WorkManagerStarterInstrumentedTest)
workmanager_probe=com.ny.my.health.WorkManagerIsolationInstrumentedTest
workmanager_isolation_exclusions=$(IFS=,; echo "${workmanager_helpers[*]},$workmanager_probe")

verify_workmanager_isolation() (
  set -euo pipefail
  local evidence=android/app/build/outputs/critical/workmanager-isolation
  local scratch app=com.ny.my.health runner=com.ny.my.health.test/androidx.test.runner.AndroidJUnitRunner
  local job='' stage=setup failure=none
  scratch=$(mktemp -d)
  chmod 700 "$scratch"
  mkdir -p "$evidence"
  rm -f "$evidence"/*.raw.txt "$evidence"/*.json "$evidence"/*.failure.txt "$evidence"/source-inventory.txt
  cleanup() {
    local original=$? cleanup_failed=0 n
    trap - EXIT
    timeout 5s adb shell am force-stop "$app" >"$scratch/cleanup" 2>&1 || cleanup_failed=1
    if [[ -n "$job" ]]; then
      for ((n=0; n<50; n++)); do
        kill -0 "$job" 2>/dev/null || break
        sleep 0.1
      done
      if kill -0 "$job" 2>/dev/null; then kill -TERM "$job" 2>/dev/null || true; fi
      for ((n=0; n<50; n++)); do
        kill -0 "$job" 2>/dev/null || break
        sleep 0.1
      done
      if kill -0 "$job" 2>/dev/null; then kill -KILL "$job" 2>/dev/null || true; fi
      wait "$job" 2>/dev/null || true
    fi
    timeout 5s adb shell true >"$scratch/alive" 2>&1 || cleanup_failed=1
    if timeout 5s adb shell pidof "$app" >"$scratch/remaining" 2>&1; then cleanup_failed=1; fi
    if (( cleanup_failed )); then
      printf 'stage=%s count=0 pass=false failure=cleanup\n' "$stage" >&2
      (( original != 0 )) || original=1
    fi
    rm -rf "$scratch"
    exit "$original"
  }
  trap cleanup EXIT
  fail() {
    failure=$1
    printf 'stage=%s count=0 pass=false failure=%s\n' "$stage" "$failure" >&2
    printf 'stage=%s count=0 pass=false failure=%s\n' "$stage" "$failure" >"$evidence/$stage.failure.txt"
    exit 1
  }
  # 기존 connected 작업의 teardown 이후 빌드된 동일 APK를 다시 설치한다.
  for apk in android/app/build/outputs/apk/debug/app-debug.apk android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk; do
    [[ -f "$apk" ]] || fail apk_missing
    timeout 60s adb install -r -t "$apk" >"$scratch/install" 2>&1 || fail install
    grep -Eq '^Success[[:space:]]*$' "$scratch/install" || fail install
  done
  timeout 30s adb shell pm list instrumentation >"$scratch/runner" 2>&1 || fail runner
  grep -Fxq "instrumentation:$runner (target=$app)" <(tr -d '\r' <"$scratch/runner") || fail runner
  # 주석과 문자열을 제외한 중괄호 범위에서 각 top-level class의 테스트를 찾는다.
  python3 - "$scratch" "${workmanager_helpers[@]}" 2>"$scratch/inventory-error" <<'PYCODE' || fail inventory
import pathlib, re, sys
from collections import Counter
scratch = pathlib.Path(sys.argv[1]); registry = sys.argv[2:]
assert len(registry) == len(set(registry))
root = pathlib.Path('android/app/src/androidTest/java')
installers, all_tests = [], []
def mask(s):
    pattern = r'//[^\n]*|/\*[\s\S]*?\*/|"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\''
    return re.sub(pattern, lambda m: ''.join('\n' if c == '\n' else ' ' for c in m.group()), s)
for p in root.rglob('*.kt'):
    raw = p.read_text(); s = mask(raw)
    package = re.search(r'^package\s+([\w.]+)', s, re.M)
    if not package: continue
    depth = 0; depths = []
    for c in s:
        depths.append(depth)
        if c == '{': depth += 1
        elif c == '}': depth -= 1
        assert depth >= 0
    assert depth == 0
    classes = []
    for m in re.finditer(r'\bclass\s+(\w+)[^{]*\{', s):
        if depths[m.start()] != 0: continue
        opening = m.end()-1
        end = next((i for i in range(opening+1, len(s)) if s[i] == '}' and depths[i] == 1), None)
        assert end is not None
        classes.append((m.start(), end, package.group(1) + '.' + m.group(1)))
    for m in re.finditer(r'@Test(?:\s*\([^)]*\))?\s+fun\s+(\w+)\s*\(', s):
        owners = [c for start, end, c in classes if start < m.start() < end]
        assert len(owners) == 1
        all_tests.append(owners[0] + '#' + m.group(1))
    for m in re.finditer(r'\bWorkManagerTestInitHelper\s*\.\s*initializeTestWorkManager\s*\(', s):
        owners = [c for start, end, c in classes if start < m.start() < end]
        assert len(owners) == 1
        installers.append(owners[0])
assert Counter(installers) == Counter(registry)
assert len(all_tests) == len(set(all_tests))
(scratch / 'inventory').write_text('\n'.join(sorted(all_tests)) + '\n')
for i, cls in enumerate(registry):
    methods = [x for x in all_tests if x.startswith(cls + '#')]
    assert methods
    (scratch / ('helper' + str(i))).write_text('\n'.join(methods) + '\n')
PYCODE
  cp "$scratch/inventory" "$evidence/source-inventory.txt"
  local seen_pids=' ' pid='' selector expected output i status
  run_stage() {
    stage=$1 selector=$2 expected=$3
    output="$scratch/$stage.raw"
    timeout 10s adb shell am force-stop "$app" >"$scratch/stop" 2>&1 || fail force_stop
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
    job=''
    [[ "$status" == 0 ]] || fail invocation
    [[ "$pid" =~ ^[0-9]+$ && "$seen_pids" != *" $pid "* ]] || fail process
    seen_pids+="$pid "
    [[ $(wc -c <"$output") -le 262144 ]] || fail output_size
    # 모든 줄이 고정된 성공 protocol과 예상 identity에 속할 때만 원문을 공개한다.
    python3 - "$output" "$expected" "$evidence/$stage.json" "$stage" 2>"$scratch/protocol-error" <<'PYCODE' || fail protocol
import json, pathlib, re, sys
raw = pathlib.Path(sys.argv[1]).read_bytes()
assert len(raw) <= 262144
text = raw.decode('utf-8').replace('\r\n', '\n')
assert '\r' not in text
expected = pathlib.Path(sys.argv[2]).read_text().splitlines()
assert expected and len(expected) == len(set(expected))
classes = {x.split('#')[0] for x in expected}
methods = {x.split('#')[1] for x in expected}
started, passed, bundle, terminal, ok = [], [], {}, 0, 0
for line in text.splitlines():
    if line == '': continue
    if line.startswith('INSTRUMENTATION_STATUS: '):
        item = line.removeprefix('INSTRUMENTATION_STATUS: ')
        key, sep, value = item.partition('='); assert sep and key not in bundle
        valid = ((key == 'class' and value in classes) or (key == 'test' and value in methods) or
                 (key == 'id' and value == 'AndroidJUnitRunner') or
                 (key == 'current' and value.isdigit() and 1 <= int(value) <= len(expected)) or
                 (key == 'numtests' and value == str(len(expected))) or
                 (key == 'stream' and value == ''))
        assert valid
        bundle[key] = value
    elif line in ('INSTRUMENTATION_STATUS_CODE: 1', 'INSTRUMENTATION_STATUS_CODE: 0'):
        identity = bundle.get('class', '') + '#' + bundle.get('test', '')
        assert identity in expected
        (started if line.endswith('1') else passed).append(identity)
        bundle = {}
    elif line == 'INSTRUMENTATION_RESULT: stream=':
        assert not bundle
    elif re.fullmatch(r'Time: [0-9]+(?:\.[0-9]+)?', line): pass
    elif line == 'OK (' + str(len(expected)) + (' test)' if len(expected) == 1 else ' tests)'):
        ok += 1
    elif line == 'INSTRUMENTATION_CODE: -1': terminal += 1
    else: raise AssertionError('protocol')
assert not bundle and terminal == 1 and ok == 1
assert len(started) == len(expected) and set(started) == set(expected)
assert len(passed) == len(expected) and set(passed) == set(expected)
pathlib.Path(sys.argv[3]).write_text(json.dumps({'stage': sys.argv[4], 'count': len(expected), 'pass': True, 'tests': sorted(passed)}, indent=2) + '\n')
PYCODE
    cp "$output" "$evidence/$stage.raw.txt"
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
