#!/usr/bin/env bash
# ponytail: preflight gate for ToonShader pack runs (AGENTS.md memory-safety rules).
# Exit 0 = safe to launch; nonzero = blocked with reason.
set -u
# ponytail: 10GiB default per AGENTS.md; lower via TOON_MIN_AVAILABLE_KIB when
# the game JVM is explicitly capped (e.g. JAVA_TOOL_OPTIONS=-Xmx3G).
MIN_KB=${TOON_MIN_AVAILABLE_KIB:-$((10 * 1024 * 1024))}

fail=0
for pat in chrome chromium xvfb gradle; do
  hits=$(pgrep -af "$pat" | grep -v 'toon-preflight' || true)
  if [ -n "$hits" ]; then
    echo "BLOCKED: '$pat' process(es) running:"
    echo "$hits" | cut -c1-160
    fail=1
  fi
done
# Java: only flag Minecraft/Gradle JVMs, not unrelated java tools.
jhits=$(ps -eo rss,cmd | awk '/[j]ava/ && (/minecraft/ || /gradle/ || /fabric/) {printf "%.1fGB %s\n", $1/1048576, substr($0, index($0,$2), 120)}')
if [ -n "$jhits" ]; then
  echo "BLOCKED: Minecraft/Gradle java process(es):"
  echo "$jhits"
  fail=1
fi

avail=$(awk '/MemAvailable/{print $2}' /proc/meminfo)
if [ "$avail" -lt "$MIN_KB" ]; then
  printf "BLOCKED: MemAvailable %.1fGiB < %.0fGiB\n" "$(echo "$avail/1048576" | bc -l)" 10
  fail=1
else
  printf "OK: MemAvailable %.1fGiB\n" "$(echo "$avail/1048576" | bc -l)"
fi

[ "$fail" -eq 0 ] && echo "PREFLIGHT PASS" || echo "PREFLIGHT FAIL"
exit $fail
