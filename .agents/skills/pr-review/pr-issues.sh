#!/usr/bin/env bash
# pr-issues.sh — manage GitHub issues for catatafishen/agentbridge
# Usage:
#   pr-issues.sh list [open|closed|all]           — list issues (default: open)
#   pr-issues.sh view <NUMBER>                     — show issue details + comments
#   pr-issues.sh comment <NUMBER> <body>           — add a comment
#   pr-issues.sh close <NUMBER> [reason]           — close with optional comment
#   pr-issues.sh open <NUMBER>                     — reopen a closed issue
#   pr-issues.sh label <NUMBER> <label>            — add a label
#   pr-issues.sh new <title> <body>               — create a new issue
#   pr-issues.sh link-pr <ISSUE> <PR>              — add cross-reference comment
set -euo pipefail

REPO="${REPO:-catatafishen/agentbridge}"
CMD="${1:-list}"

case "$CMD" in
  list)
    STATE="${2:-open}"
    echo "=== $STATE issues ($REPO) ==="
    gh issue list --repo "$REPO" --state "$STATE" --limit 30 \
      --json number,title,labels,assignees,createdAt \
      --jq '.[] | "#\(.number) [\(.labels | map(.name) | join(", "))] \(.title)"'
    ;;

  view)
    NUM="${2:?Usage: pr-issues.sh view <NUMBER>}"
    echo "=== Issue #$NUM ($REPO) ==="
    gh issue view "$NUM" --repo "$REPO"
    echo ""
    echo "=== Comments ==="
    gh issue view "$NUM" --repo "$REPO" --json comments \
      --jq '.comments[] | "--- @\(.author.login) ---\n\(.body)\n"'
    ;;

  comment)
    NUM="${2:?Usage: pr-issues.sh comment <NUMBER> <body>}"
    BODY="${3:?Usage: pr-issues.sh comment <NUMBER> <body>}"
    gh issue comment "$NUM" --repo "$REPO" --body "$BODY"
    echo "Comment added to #$NUM"
    ;;

  close)
    NUM="${2:?Usage: pr-issues.sh close <NUMBER> [reason]}"
    REASON="${3:-}"
    if [ -n "$REASON" ]; then
      gh issue comment "$NUM" --repo "$REPO" --body "$REASON"
    fi
    gh issue close "$NUM" --repo "$REPO"
    echo "Issue #$NUM closed"
    ;;

  open)
    NUM="${2:?Usage: pr-issues.sh open <NUMBER>}"
    gh issue reopen "$NUM" --repo "$REPO"
    echo "Issue #$NUM reopened"
    ;;

  label)
    NUM="${2:?Usage: pr-issues.sh label <NUMBER> <label>}"
    LABEL="${3:?Usage: pr-issues.sh label <NUMBER> <label>}"
    gh issue edit "$NUM" --repo "$REPO" --add-label "$LABEL"
    echo "Label '$LABEL' added to #$NUM"
    ;;

  new)
    TITLE="${2:?Usage: pr-issues.sh new <title> <body>}"
    BODY="${3:?Usage: pr-issues.sh new <title> <body>}"
    gh issue create --repo "$REPO" --title "$TITLE" --body "$BODY"
    ;;

  link-pr)
    ISSUE="${2:?Usage: pr-issues.sh link-pr <ISSUE> <PR>}"
    PR="${3:?Usage: pr-issues.sh link-pr <ISSUE> <PR>}"
    gh issue comment "$ISSUE" --repo "$REPO" \
      --body "Addressed in PR #$PR — https://github.com/$REPO/pull/$PR"
    echo "Cross-reference added to issue #$ISSUE -> PR #$PR"
    ;;

  *)
    echo "Unknown command: $CMD"
    echo "Commands: list, view, comment, close, open, label, new, link-pr"
    exit 1
    ;;
esac
