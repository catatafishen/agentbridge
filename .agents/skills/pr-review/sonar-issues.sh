#!/usr/bin/env bash
# sonar-issues.sh <PR_NUMBER> [SONAR_PROJECT_KEY]
# Lists the open SonarCloud issues on a PR (the public API needs no token for this project).
# Exits 1 when there are open issues, 0 when clean, 2 when the query itself failed — so a
# failed query is never mistaken for "no issues".
#
# SonarCloud analyses a PR a couple of minutes after each push. Run this once the
# "SonarCloud Code Analysis" check has finished (gh pr checks <PR> | grep -i sonar).
# Usage: bash .agents/skills/pr-review/sonar-issues.sh 1172
set -euo pipefail

PR="${1:?Usage: sonar-issues.sh <PR_NUMBER> [sonar-project-key]}"
KEY="${2:-catatafishen_agentbridge}"
URL="https://sonarcloud.io/api/issues/search?componentKeys=${KEY}&pullRequest=${PR}&resolved=false&ps=500"

RESPONSE=$(curl -fsS "$URL") || {
    echo "✗ Could not query SonarCloud for PR #$PR ($URL)" >&2
    exit 2
}

echo "$RESPONSE" | python3 -c "
import sys, json
data = json.load(sys.stdin)
if 'issues' not in data:
    print('✗ Unexpected SonarCloud response:', json.dumps(data)[:300], file=sys.stderr)
    sys.exit(2)
issues = data['issues']
print(f'=== SonarCloud: {len(issues)} open issue(s) on PR #$PR ===')
for i in issues:
    path = i['component'].split(':', 1)[-1]
    print(f\"{i['severity']:<8} {i['rule']:<12} {path}:{i.get('line', '?')}\")
    print(f\"           {i['message']}\")
sys.exit(1 if issues else 0)
"
