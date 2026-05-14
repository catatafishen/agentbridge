#!/usr/bin/env bash
# pr-threads.sh <PR_NUMBER> [all|unresolved] [REPO]
# Lists review threads for a PR with the IDs needed for reply and resolve.
# Usage: bash .agents/skills/pr-review/pr-threads.sh 628
#        bash .agents/skills/pr-review/pr-threads.sh 628 all
set -euo pipefail

PR="${1:?Usage: pr-threads.sh <PR_NUMBER> [all|unresolved] [owner/repo]}"
FILTER="${2:-unresolved}"
REPO="${3:-catatafishen/agentbridge}"
OWNER="${REPO%%/*}"
REPO_NAME="${REPO##*/}"

JQ_FILTER='.data.repository.pullRequest.reviewThreads.nodes[]'
if [ "$FILTER" = "unresolved" ]; then
    JQ_FILTER="$JQ_FILTER | select(.isResolved == false)"
fi

echo "=== $FILTER threads for PR #$PR ($REPO) ==="
echo ""

gh api graphql -f query="{
  repository(owner: \"$OWNER\", name: \"$REPO_NAME\") {
    pullRequest(number: $PR) {
      reviewThreads(first: 50) {
        nodes {
          id
          isResolved
          comments(first: 1) {
            nodes {
              databaseId
              body
              url
            }
          }
        }
      }
    }
  }
}" --jq "$JQ_FILTER" | python3 -c "
import sys, json

data = sys.stdin.read().strip()
if not data:
    print('No $FILTER threads found.')
    sys.exit(0)

# Handle both a single object and multiple objects (one per line)
threads = []
for line in data.splitlines():
    line = line.strip()
    if line:
        try:
            threads.append(json.loads(line))
        except json.JSONDecodeError:
            pass

for t in threads:
    comment = t['comments']['nodes'][0] if t['comments']['nodes'] else {}
    print(f'Thread ID (for resolve):   {t[\"id\"]}')
    print(f'Comment DB ID (for reply): {comment.get(\"databaseId\", \"n/a\")}')
    print(f'Resolved: {t[\"isResolved\"]}')
    print(f'URL: {comment.get(\"url\", \"n/a\")}')
    body = comment.get('body', '')
    print(f'Comment: {body[:200]}...' if len(body) > 200 else f'Comment: {body}')
    print()
"
