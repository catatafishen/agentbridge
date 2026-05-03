#!/usr/bin/env bash
# Hook for git_push tool: appends a PR creation tip when pushing a feature branch.
#
# Receives a JSON payload on stdin with fields:
#   toolName, arguments, argumentsJson, output, error, projectName, timestamp
#
# Returns JSON with "append" to add text to the tool output, or exits silently
# (empty stdout) to leave the output unchanged.

set -euo pipefail

# Read the hook payload from stdin
payload=$(cat)

# Don't modify error responses
is_error=$(echo "$payload" | python3 -c "import sys,json; print(json.load(sys.stdin).get('error', False))" 2>/dev/null || echo "false")
if [ "$is_error" = "True" ] || [ "$is_error" = "true" ]; then
    exit 0
fi

# Extract the branch from the tool output's "Context" section
output=$(echo "$payload" | python3 -c "import sys,json; print(json.load(sys.stdin).get('output',''))" 2>/dev/null || echo "")
branch=$(echo "$output" | grep -oP 'Pushed \K[^ ]+' | head -1 || true)

if [ -z "$branch" ]; then
    exit 0
fi

# Only append tip for feature branches (not main/master)
if [ "$branch" = "main" ] || [ "$branch" = "master" ]; then
    exit 0
fi

# Append the PR creation tip
echo '{"append": "\nTip: create a PR with: gh pr create"}'
