#!/usr/bin/env bash
# create-pr.sh — Creates a GitHub PR as the agentbridge-issue-fixes[bot] App
# and requests review from the repository owner.
#
# Usage:
#   scripts/create-pr.sh --title "feat: ..." --body "..." [--base master]
#
# Requires: GITHUB_APP_ID, GITHUB_APP_PRIVATE_KEY_FILE (from scripts/.env)
#           python3 with PyJWT + cryptography

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ENV_FILE="$SCRIPT_DIR/.env"

# Load .env if present
if [[ -f "$ENV_FILE" ]]; then
    set -a
    # shellcheck source=scripts/.env
    source "$ENV_FILE"
    set +a
fi

: "${GITHUB_REPO:?GITHUB_REPO is required (owner/repo)}"
: "${GITHUB_APP_ID:?GITHUB_APP_ID is required}"
: "${GITHUB_APP_PRIVATE_KEY_FILE:?GITHUB_APP_PRIVATE_KEY_FILE is required}"

# Parse arguments
TITLE=""
BODY=""
BASE="master"
REVIEWER=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --title)  TITLE="$2";    shift 2 ;;
        --body)   BODY="$2";     shift 2 ;;
        --base)   BASE="$2";     shift 2 ;;
        --reviewer) REVIEWER="$2"; shift 2 ;;
        *) echo "Unknown arg: $1" >&2; exit 1 ;;
    esac
done

if [[ -z "$TITLE" ]]; then
    echo "Error: --title is required" >&2
    exit 1
fi

# Generate a fresh GitHub App installation token via Python
TOKEN=$(python3 -c "
import jwt, time, json, urllib.request
from pathlib import Path

app_id = '$GITHUB_APP_ID'
key = Path('$GITHUB_APP_PRIVATE_KEY_FILE').read_bytes()
now = int(time.time())
payload = {'iat': now - 60, 'exp': now + 540, 'iss': app_id}
app_jwt = jwt.encode(payload, key, algorithm='RS256')

def api(url, token, method='GET', data=None):
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header('Authorization', f'Bearer {token}')
    req.add_header('Accept', 'application/vnd.github+json')
    req.add_header('User-Agent', 'create-pr/1.0')
    with urllib.request.urlopen(req) as r:
        return json.loads(r.read())

owner = '$GITHUB_REPO'.split('/')[0]
installs = api('https://api.github.com/app/installations', app_jwt)
inst_id = next(i['id'] for i in installs if i.get('account',{}).get('login') == owner)
result = api(f'https://api.github.com/app/installations/{inst_id}/access_tokens', app_jwt, 'POST')
print(result['token'])
")

if [[ -z "$TOKEN" ]]; then
    echo "Error: failed to obtain GitHub App token" >&2
    exit 1
fi

echo "[auth] obtained GitHub App installation token"

# Detect the owner login for review requests
OWNER="${GITHUB_REPO%%/*}"
if [[ -z "$REVIEWER" ]]; then
    REVIEWER="$OWNER"
fi

# Create the PR using the bot token
PR_URL=$(GH_TOKEN="$TOKEN" gh pr create \
    --repo "$GITHUB_REPO" \
    --title "$TITLE" \
    --body "$BODY" \
    --base "$BASE" 2>&1)

echo "$PR_URL"

# Extract PR number and request review
PR_NUMBER=$(echo "$PR_URL" | sed -n 's|.*/pull/\([0-9]*\)$|\1|p')
if [[ -n "$PR_NUMBER" && -n "$REVIEWER" ]]; then
    GH_TOKEN="$TOKEN" gh pr edit "$PR_NUMBER" \
        --repo "$GITHUB_REPO" \
        --add-reviewer "$REVIEWER" 2>&1 || true
    echo "Requested review from $REVIEWER"
fi
