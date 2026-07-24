#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 4 ]]; then
  echo "usage: $0 VERSION THIS_RELEASE SINCE_MARKETPLACE OUTPUT" >&2
  exit 2
fi

version=$1
this_release=$2
since_marketplace=$3
output=$4
version_fragment="docs/release-notes/v${version}.md"

{
  cat "$this_release"
  if [[ -f "$version_fragment" ]]; then
    echo ""
    cat "$version_fragment"
  fi
  echo ""
  if ! diff -q "$this_release" "$since_marketplace" >/dev/null 2>&1; then
    echo "---"
    echo ""
    echo "<details><summary>All changes since last Marketplace publish</summary>"
    echo ""
    cat "$since_marketplace"
    echo ""
    echo "</details>"
  fi
} > "$output"
