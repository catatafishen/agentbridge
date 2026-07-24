#!/usr/bin/env bash
set -uo pipefail

cd "$(dirname "$0")/.."

failures=0

fail() {
  echo "FAIL: $*" >&2
  failures=$((failures + 1))
}

assert_contains() {
  local file=$1
  local text=$2
  grep -Fq -- "$text" "$file" || fail "$file does not contain: $text"
}

assert_not_contains() {
  local file=$1
  local text=$2
  if grep -Fq -- "$text" "$file"; then
    fail "$file unexpectedly contains: $text"
  fi
}

workflow=.github/workflows/release.yml
core_build=plugin-core/build.gradle.kts
experimental_build=plugin-experimental/build.gradle.kts

assert_not_contains "$workflow" "maven.pkg.github.com/catatafishen/agentbridge"
assert_contains "$workflow" '${GITHUB_REPOSITORY}'
assert_contains "$workflow" "com/github/catatafishen"
assert_contains "$workflow" '"ide-agent-for-copilot"'
assert_contains "$workflow" '"ide-agent-for-copilot-experimental"'
assert_contains "$workflow" '${artifact_id}-${PLUGIN_VERSION}.pom'
assert_contains "$workflow" ":plugin-core:publishPluginZipPublicationToGitHubPackagesRepository"
assert_contains "$workflow" ":plugin-experimental:publishPluginZipPublicationToGitHubPackagesRepository"
assert_contains "$workflow" "bash scripts/assemble-release-notes.sh"

assert_not_contains "$core_build" "catatafishen/agentbridge"
assert_not_contains "$experimental_build" "catatafishen/agentbridge"
assert_contains "$core_build" 'githubPackagesRepository'
assert_contains "$experimental_build" 'githubPackagesRepository'
assert_contains build.gradle.kts 'GITHUB_REPOSITORY'
assert_contains build.gradle.kts 'GITHUB_ACTIONS'

publish_line=$(grep -nF -- "- name: Publish plugin ZIPs to GitHub Packages" "$workflow" | cut -d: -f1)
release_line=$(grep -nF -- "- name: Create tag and GitHub release" "$workflow" | cut -d: -f1)
if [[ -z "$publish_line" || -z "$release_line" || "$publish_line" -ge "$release_line" ]]; then
  fail "package publication must complete before tag/release creation"
fi

mkdir -p .agent-work
test_dir=$(mktemp -d .agent-work/release-workflow-test.XXXXXX)
cleanup() {
  rm -rf -- "$test_dir"
}
trap cleanup EXIT

printf '# Current changes\n' > "$test_dir/this-release.md"
printf '# Marketplace changes\n' > "$test_dir/since-marketplace.md"

if ! bash scripts/assemble-release-notes.sh \
  1.202.1 "$test_dir/this-release.md" "$test_dir/since-marketplace.md" "$test_dir/v1.202.1.md"; then
  fail "release-note assembly failed for v1.202.1"
elif ! grep -Fq -- \
  "Security note: GET /tool-calls/{id} exposes retained complete arguments and results" \
  "$test_dir/v1.202.1.md"; then
  fail "v1.202.1 release notes omit the approved security disclosure"
fi

if ! bash scripts/assemble-release-notes.sh \
  1.202.2 "$test_dir/this-release.md" "$test_dir/since-marketplace.md" "$test_dir/v1.202.2.md"; then
  fail "release-note assembly failed for a future version"
elif grep -Fq -- \
  "Security note: GET /tool-calls/{id} exposes retained complete arguments and results" \
  "$test_dir/v1.202.2.md"; then
  fail "v1.202.1 disclosure leaked into unrelated future release notes"
fi

if ((failures > 0)); then
  exit 1
fi

echo "release workflow assertions passed"
