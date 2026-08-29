#!/usr/bin/env bash
set -euo pipefail

version="${1:?Usage: ./scripts/release.sh <version>}"
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$ ]] || {
	echo "Version must be SemVer, for example 1.0.1 or 26.2-1.3.0" >&2
	exit 2
}

cd "$(git rev-parse --show-toplevel)"
[[ "$(git branch --show-current)" == main ]] || { echo "Release from main" >&2; exit 2; }
[[ -z "$(git status --porcelain)" ]] || { echo "Working tree is not clean" >&2; exit 2; }

git fetch origin main --tags
[[ "$(git rev-parse HEAD)" == "$(git rev-parse origin/main)" ]] || {
	echo "Local main must match origin/main" >&2
	exit 2
}
git rev-parse -q --verify "refs/tags/v$version" >/dev/null && {
	echo "Tag v$version already exists" >&2
	exit 2
}

sed -i "s/^version=.*/version=$version/" gradle.properties
grep -qxF "version=$version" gradle.properties
./gradlew buildAll --no-daemon --stacktrace

if ! git diff --quiet -- gradle.properties; then
	git add gradle.properties
	git commit -m "chore: release v$version"
fi

git tag -a "v$version" -m "Celerant $version"
git push --atomic origin main "v$version"

echo "Release v$version pushed; GitHub Actions will upload per-loader artifacts and publish Fabric, NeoForge, and API JARs."
