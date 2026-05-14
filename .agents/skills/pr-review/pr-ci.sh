#!/usr/bin/env bash
# pr-ci.sh <PR_NUMBER> [REPO]
# Shows CI status and extracts failing test names + error messages in one shot.
# Usage: bash .agents/skills/pr-review/pr-ci.sh 628
set -euo pipefail

PR="${1:?Usage: pr-ci.sh <PR_NUMBER> [owner/repo]}"
REPO="${2:-catatafishen/agentbridge}"

echo "=== CI checks for PR #$PR ==="
gh pr checks "$PR" --repo "$REPO" 2>&1

# Extract job IDs from failing check URLs.
# gh pr checks output is tab-separated; URL format: .../runs/<RUN_ID>/job/<JOB_ID>
FAILING_JOBS=$(gh pr checks "$PR" --repo "$REPO" 2>&1 | python3 -c "
import sys, re
for line in sys.stdin:
    if 'fail' in line.lower():
        m = re.search(r'/job/(\d+)', line)
        if m:
            print(m.group(1))
" | sort -u)

if [ -z "$FAILING_JOBS" ]; then
    echo ""
    echo "✓ No failing job URLs found"
    exit 0
fi

for JOB_ID in $FAILING_JOBS; do
    echo ""
    echo "=== Failures in job $JOB_ID ==="
    gh api "repos/$REPO/actions/jobs/$JOB_ID/logs" | python3 -c "
import sys
lines = sys.stdin.readlines()
found = False
for i, line in enumerate(lines):
    # Skip lines where 'FAILED' is part of a passing test name (ends in 'PASSED')
    if 'FAILED' in line and 'PASSED' not in line:
        found = True
        for l in lines[max(0, i-3):min(len(lines), i+15)]:
            print(l, end='')
        print('---')
if not found:
    # Fallback: print last 30 lines for context
    print('(No FAILED markers found — last 30 lines:)')
    for l in lines[-30:]:
        print(l, end='')
"
done
