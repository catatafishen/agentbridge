#!/usr/bin/env bash
# sonar-issues.sh <PR_NUMBER> [SONAR_PROJECT_KEY]
# Lists the open SonarCloud issues on a PR (the public API needs no token for this project).
#
# Exit codes: 0 = analysed the current PR head and found no issues
#             1 = open issues found (listed)
#             2 = the query itself failed
#             3 = SonarCloud has not analysed the current PR head yet (results would be stale)
# A failed query or a stale analysis is never reported as "no issues".
#
# SonarCloud analyses a PR a few minutes after each push, so right after pushing expect exit 3 and
# retry. Usage: bash .agents/skills/pr-review/sonar-issues.sh 1172
set -euo pipefail

PR="${1:?Usage: sonar-issues.sh <PR_NUMBER> [sonar-project-key]}"
KEY="${2:-catatafishen_agentbridge}"
API="https://sonarcloud.io/api"

HEAD_SHA=$(gh pr view "$PR" --repo catatafishen/agentbridge --json headRefOid -q .headRefOid) || {
    echo "✗ Could not read the head commit of PR #$PR" >&2
    exit 2
}
ANALYSED=$(curl -fsS "$API/project_pull_requests/list?project=$KEY" | PR="$PR" python3 -c "
import os, sys, json
for p in json.load(sys.stdin).get('pullRequests', []):
    if p['key'] == os.environ['PR']:
        print(p.get('commit', {}).get('sha', ''))
") || {
    echo "✗ Could not query SonarCloud for PR #$PR" >&2
    exit 2
}
if [ "$ANALYSED" != "$HEAD_SHA" ]; then
    echo "… SonarCloud has analysed ${ANALYSED:0:9}, but PR #$PR is at ${HEAD_SHA:0:9}: analysis not finished yet, retry shortly." >&2
    exit 3
fi

RESPONSE=$(curl -fsS "$API/issues/search?componentKeys=${KEY}&pullRequest=${PR}&resolved=false&ps=500") || {
    echo "✗ Could not query SonarCloud issues for PR #$PR" >&2
    exit 2
}

echo "$RESPONSE" | PR="$PR" python3 -c "
import os, sys, json
data = json.load(sys.stdin)
if 'issues' not in data:
    print('✗ Unexpected SonarCloud response:', json.dumps(data)[:300], file=sys.stderr)
    sys.exit(2)
issues = data['issues']
print(f\"=== SonarCloud: {len(issues)} open issue(s) on PR #{os.environ['PR']} (current head) ===\")
for i in issues:
    path = i['component'].split(':', 1)[-1]
    print(f\"{i['severity']:<8} {i['rule']:<12} {path}:{i.get('line', '?')}\")
    print(f\"           {i['message']}\")
sys.exit(1 if issues else 0)
"
