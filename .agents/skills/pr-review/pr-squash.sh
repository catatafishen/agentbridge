#!/usr/bin/env bash
# pr-squash.sh [BASE_REF]
# Squashes all commits on the current branch since BASE_REF into one commit.
# Prompts before destructive action. Leaves push to you.
# Usage: bash .agents/skills/pr-review/pr-squash.sh
#        bash .agents/skills/pr-review/pr-squash.sh origin/master
set -euo pipefail

BASE="${1:-origin/master}"

COUNT=$(git rev-list "$BASE"..HEAD --count)
if [ "$COUNT" -eq 0 ]; then
    echo "No commits to squash — branch is already at $BASE."
    exit 0
fi

echo "=== Commits to squash ($COUNT since $BASE) ==="
git log --oneline "$BASE"..HEAD
echo ""

FIRST_MSG=$(git log --format="%s" "$BASE"..HEAD | tail -1)
echo "Squash all $COUNT commit(s) into one?"
echo "Suggested message from first commit: \"$FIRST_MSG\""
echo ""
printf "Proceed? [y/N] "
read -r answer
if [ "$answer" != "y" ] && [ "$answer" != "Y" ]; then
    echo "Cancelled."
    exit 0
fi

git reset --soft HEAD~"$COUNT"
echo ""
echo "All changes are staged. Running 'git commit' — edit the message as needed."
echo "(Conventional commit format: feat/fix/chore/refactor: <description>)"
echo ""
git commit
echo ""
echo "Done. Push when ready:"
echo "  git push --force-with-lease"
