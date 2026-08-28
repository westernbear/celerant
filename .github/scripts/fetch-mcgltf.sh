#!/usr/bin/env bash
# Fetch MCglTF multiloader JARs for CI. Usage: fetch-mcgltf.sh [fabric|neoforge]
set -euo pipefail

LOADER="${1:-}"
REPO="westernbear/MCglTF"
MCGLTF_DIR="${HOME}/.celerant/mcgltf"
TAG="v$(sed -n 's/^mcgltf_version=//p' gradle.properties | head -n1)"

mkdir -p "$MCGLTF_DIR"

patterns=( 'mcgltf-api-*.jar' 'mcgltf-common-*.jar' )
case "$LOADER" in
	fabric) patterns+=( 'MCglTF-Fabric-*.jar' ) ;;
	neoforge) patterns+=( 'MCglTF-NeoForge-*.jar' ) ;;
	"") patterns+=( 'MCglTF-Fabric-*.jar' 'MCglTF-NeoForge-*.jar' ) ;;
	*) echo "Unknown loader: $LOADER" >&2; exit 2 ;;
esac

download_args=( gh release download "$TAG" --repo "$REPO" --dir "$MCGLTF_DIR" --clobber )
for pattern in "${patterns[@]}"; do
	download_args+=( --pattern "$pattern" )
done
"${download_args[@]}" 2>/dev/null || true

jar_present() {
	local pattern="$1"
	find "$MCGLTF_DIR" -maxdepth 1 -type f -name "$pattern" ! -name '*-sources*' ! -name '*-javadoc*' | grep -q .
}

needs_build=false
for pattern in "${patterns[@]}"; do
	if ! jar_present "$pattern"; then
		needs_build=true
	fi
done

if [[ "$needs_build" == true ]]; then
	rm -rf /tmp/mcgltf-src
	git clone --depth 1 --branch feat/multiloader-fabric-neoforge-api \
		"https://github.com/${REPO}" /tmp/mcgltf-src
	tasks=( :api:jar :common:jar )
	case "$LOADER" in
		fabric) tasks+=( :fabric:build ) ;;
		neoforge) tasks+=( :neoforge:build ) ;;
		"") tasks+=( :fabric:build :neoforge:build ) ;;
	esac
	( cd /tmp/mcgltf-src && ./gradlew "${tasks[@]}" --no-daemon --max-workers=1 )
	for module in api common fabric neoforge; do
		if [[ -d "/tmp/mcgltf-src/${module}/build/libs" ]]; then
			find "/tmp/mcgltf-src/${module}/build/libs" -maxdepth 1 -type f -name '*.jar' \
				! -name '*-sources*' ! -name '*-javadoc*' ! -name '*-dev*' \
				-exec cp -t "$MCGLTF_DIR" {} +
		fi
	done
fi

write_env() {
	local var="$1"
	local pattern="$2"
	local path
	path="$(find "$MCGLTF_DIR" -maxdepth 1 -type f -name "$pattern" ! -name '*-sources*' ! -name '*-javadoc*' | head -n1 || true)"
	if [[ -n "$path" ]]; then
		echo "${var}=${path}" >> "${GITHUB_ENV:?GITHUB_ENV is required}"
	fi
}

write_env LOCAL_MCGLTF_API 'mcgltf-api-*.jar'
write_env LOCAL_MCGLTF_COMMON 'mcgltf-common-*.jar'
write_env LOCAL_MCGLTF_FABRIC 'MCglTF-Fabric-*.jar'
write_env LOCAL_MCGLTF_NEOFORGE 'MCglTF-NeoForge-*.jar'

# common-test requires api + common
if [[ -z "${LOADER:-}" ]]; then
	jar_present 'mcgltf-api-*.jar' || { echo "mcgltf-api JAR missing after fetch/build" >&2; exit 1; }
	jar_present 'mcgltf-common-*.jar' || { echo "mcgltf-common JAR missing after fetch/build" >&2; exit 1; }
fi
if [[ "$LOADER" == fabric ]]; then
	jar_present 'MCglTF-Fabric-*.jar' || { echo "MCglTF-Fabric JAR missing after fetch/build" >&2; exit 1; }
fi
if [[ "$LOADER" == neoforge ]]; then
	jar_present 'MCglTF-NeoForge-*.jar' || { echo "MCglTF-NeoForge JAR missing after fetch/build" >&2; exit 1; }
fi
