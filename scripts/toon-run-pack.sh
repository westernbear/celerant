#!/usr/bin/env bash
# ponytail: sequential single-pack runner; fresh JVM + memory caps per AGENTS.md.
# usage: toon-run-pack.sh <pack-dir-name e.g. 01-complementary-reimagined>
# optional env:
#   LOCAL_MCGLTF=/path/to/MCglTF.jar
#   CELERANT_SHADERPACK_OPTIONS='!MOTION_BLUR AA=0'   # whitespace-separated (!name or name=value)
#   TOON_ATTEMPT_STAMP=20260825T120000Z
set -eu
PACK=$1
BASE=/home/singlerr/celerant/.gstack/toon-evidence/modrinth-top20-2026-08-14
ZIP=$(ls "$BASE/packs/$PACK/"*.zip)
STAGE=/tmp/opencode/stage-zip
rm -rf "$STAGE" && mkdir -p "$STAGE" && cp "$ZIP" "$STAGE/"
# clear leftover Gradle daemons from the previous pack (pattern lives in
# this file, not in our cmdline, so pkill cannot self-match)
pkill -f 'org.gradle.launcher.daemon.bootstrap.GradleDaemon' 2>/dev/null || true
sleep 3
# AGENTS.md: require ≥10 GiB MemAvailable; do not lower the gate.
/home/singlerr/celerant/scripts/toon-preflight.sh
cd /home/singlerr/celerant
# AGENTS.md: MemoryHigh=9G MemoryMax=10G MemorySwapMax=0; --no-daemon --max-workers=1
# (do not combine --scope with --wait)
# Export CELERANT_* so systemd-run inherits them. Do not put OPTIONS in env argv —
# values contain spaces and `!` which break parsing/history.
export CELERANT_SHADERPACK_DIR="$STAGE"
export CELERANT_VISUAL_VRM="${CELERANT_VISUAL_VRM:-/home/singlerr/MCglTF/test_models/transformed_jingburger.vrm}"
GRADLE_EXTRA=()
if [[ -n "${LOCAL_MCGLTF:-}" ]]; then
	GRADLE_EXTRA+=(-PlocalMcgltf="$LOCAL_MCGLTF")
fi
# Defaults follow AGENTS.md; lower them together with TOON_MIN_AVAILABLE_KIB when the
# host cannot spare 10 GiB, since a scope larger than MemAvailable only trades a clean
# limit for host swapping.
MEM_HIGH=${TOON_MEMORY_HIGH:-9G}
MEM_MAX=${TOON_MEMORY_MAX:-10G}
systemd-run --user --scope -p MemoryHigh="$MEM_HIGH" -p MemoryMax="$MEM_MAX" -p MemorySwapMax=0 -- \
	env JAVA_TOOL_OPTIONS="-Xmx3G" \
	xvfb-run -a -s "-screen 0 1280x720x24" \
	./gradlew runClientGameTest --no-daemon --max-workers=1 "${GRADLE_EXTRA[@]}"
STAMP=${TOON_ATTEMPT_STAMP:-$(date -u +%Y%m%dT%H%M%SZ)}
R="$BASE/attempts/${PACK}-${STAMP}"
mkdir -p "$R"
cp build/run/clientGameTest/celerant-shaderpack-matrix.tsv "$R/"
cp build/run/clientGameTest/logs/latest.log "$R/" 2>/dev/null || true
rm -rf "$R/screenshots"
cp -r build/run/clientGameTest/screenshots "$R/screenshots"
echo "PACK_DONE=$PACK"
