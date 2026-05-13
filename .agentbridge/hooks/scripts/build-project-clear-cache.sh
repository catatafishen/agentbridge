#!/bin/sh
# build-project-clear-cache.sh — Clears the IntelliJ JPS compile-server cache
# on build_project failure.
#
# JPS (Java Project System) caches compiled class state between builds. When the
# cache gets out of sync with actual source/class files — typically after a Git
# operation that changes many files, a dependency update, or a rebuild that was
# interrupted — it reports stale errors for files that are actually correct.
#
# Triggered by: build_project failure hook
# Effect: clears the JPS cache directory so the next incremental build starts
#         from a clean state, then appends a notice to the build output.
. "${0%/*}/_lib.sh"
hook_read_payload

cleared=""

# Clear the project-local idea build cache
IDEA_CACHE="${AGENTBRIDGE_PROJECT_DIR}/.idea/caches/jps_build_used_resources"
if [ -d "$IDEA_CACHE" ]; then
    rm -rf "$IDEA_CACHE" 2>/dev/null && cleared="$cleared idea-caches"
fi

# Clear the per-project JPS compile-server directory under ~/.cache/JetBrains
JPS_COMPILE_DIR=$(find "${HOME}/.cache/JetBrains" -maxdepth 4 -type d -name "compile-server" 2>/dev/null | head -1)
if [ -n "$JPS_COMPILE_DIR" ]; then
    # Each project has a subdirectory named after a hash; find the one matching our project
    project_name=$(basename "$AGENTBRIDGE_PROJECT_DIR")
    project_jps=$(find "$JPS_COMPILE_DIR" -maxdepth 1 -mindepth 1 -type d 2>/dev/null | \
        xargs -I{} sh -c 'ls "{}" 2>/dev/null | grep -q "." && echo "{}"' | head -1)
    if [ -n "$project_jps" ]; then
        rm -rf "${project_jps}"/* 2>/dev/null && cleared="$cleared jps-compile-server"
    fi
fi

if [ -n "$cleared" ]; then
    hook_json_append "\n\n⚠️ JPS cache cleared automatically (${cleared# }). Call build_project again — if the same errors reappear they are real; if they disappear they were stale cache artifacts."
else
    hook_json_append "\n\n⚠️ Could not locate JPS cache to clear. If these errors are in files you did not edit, run: .agent-work/clear-jps-cache.sh, then call build_project again."
fi
