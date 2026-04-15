#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# generate-changelog.sh — Generate plugin.xml <change-notes> HTML from git
# commits, optionally summarised by an LLM.
#
# Changelog baseline:
#   Uses the 'marketplace-latest' tag (updated by publish-marketplace.yml)
#   to include all commits since the last JetBrains Marketplace publish.
#   Falls back to the latest v* tag if no marketplace tag exists yet.
#
# Usage:
#   scripts/generate-changelog.sh [VERSION]
#
# Environment:
#   PLUGIN_VERSION  — fallback if VERSION positional arg is omitted
#   OPENAI_API_KEY  — if set, uses GPT-4o-mini to summarise commits
#   CHANGELOG_MODEL — override the OpenAI model (default: gpt-4o-mini)
#
# Output: HTML suitable for the <change-notes> element (stdout).
# ---------------------------------------------------------------------------
set -euo pipefail

# ── Version ────────────────────────────────────────────────────────────────
VERSION="${1:-${PLUGIN_VERSION:-}}"
if [[ -z "$VERSION" ]]; then
  echo "Error: version required (pass as arg or set PLUGIN_VERSION)" >&2
  exit 1
fi

# ── Commits since last marketplace publish ─────────────────────────────────
# Use the 'marketplace-latest' tag (set by publish-marketplace.yml after each
# successful upload) so the changelog covers everything since the last version
# that actually reached the JetBrains Marketplace — not just since the last
# GitHub release.  Falls back to the latest v* tag if no marketplace tag exists.
BASELINE_TAG=$(git tag --list 'marketplace-latest' | head -n1)
if [[ -z "$BASELINE_TAG" ]]; then
  BASELINE_TAG=$(git tag --list 'v*' --sort=-v:refname | head -n1)
fi

if [[ -z "$BASELINE_TAG" ]]; then
  COMMITS=$(git log --pretty=format:"%s" HEAD)
else
  COMMITS=$(git log --pretty=format:"%s" "${BASELINE_TAG}..HEAD")
fi

if [[ -z "$COMMITS" ]]; then
  echo "No commits since ${BASELINE_TAG:-initial}" >&2
  COMMITS="General improvements and bug fixes"
fi

# ── Static header (always shown above generated notes) ─────────────────────
STATIC_HEADER='<p><b>AgentBridge</b> connects AI coding agents to your IDE via 100+ MCP tools
for code intelligence, navigation, editing, debugging, and git.</p>
<p>
  <a href="https://github.com/catatafishen/agentbridge">GitHub</a> ·
  <a href="https://github.com/catatafishen/agentbridge/releases">All Releases</a>
</p>
<hr/>'

# ── LLM summarisation (optional) ──────────────────────────────────────────
generate_with_llm() {
  local model="${CHANGELOG_MODEL:-gpt-4o-mini}"
  local prompt
  prompt=$(cat <<'SYSTEM'
You are writing release notes for a JetBrains IDE plugin called AgentBridge.
Given git commit subjects since the last release, output ONLY a JSON object with:
- "title": a catchy 3-5 word release title (no version number)
- "bullets": array of 3-7 concise, user-facing bullet points

Rules:
- Merge related commits into single bullets
- Use plain English, no commit prefixes (feat/fix/refactor)
- Focus on what changed for the user, not implementation details
- Skip chore/ci/docs-only commits unless user-visible
SYSTEM
  )

  local user_msg="Commits:\n${COMMITS}"

  local payload
  payload=$(jq -n \
    --arg model "$model" \
    --arg system "$prompt" \
    --arg user "$user_msg" \
    '{
      model: $model,
      temperature: 0.3,
      response_format: { type: "json_object" },
      messages: [
        { role: "system", content: $system },
        { role: "user",   content: $user }
      ]
    }')

  local response
  response=$(curl -sS --fail-with-body \
    -H "Authorization: Bearer ${OPENAI_API_KEY}" \
    -H "Content-Type: application/json" \
    -d "$payload" \
    "https://api.openai.com/v1/chat/completions" 2>&1) || {
    echo "LLM API call failed: $response" >&2
    return 1
  }

  local content
  content=$(echo "$response" | jq -r '.choices[0].message.content // empty')
  if [[ -z "$content" ]]; then
    echo "LLM returned empty content" >&2
    return 1
  fi

  local title
  title=$(echo "$content" | jq -r '.title // empty')
  local bullets_html
  bullets_html=$(echo "$content" | jq -r '.bullets[]? // empty' | while IFS= read -r line; do
    echo "    <li>${line}</li>"
  done)

  if [[ -z "$title" || -z "$bullets_html" ]]; then
    echo "LLM response missing title or bullets" >&2
    return 1
  fi

  echo "<h3>${VERSION} &mdash; ${title}</h3>"
  echo "<ul>"
  echo "$bullets_html"
  echo "</ul>"
}

# ── Fallback: plain bullet list from commit subjects ───────────────────────
generate_plain() {
  echo "<h3>${VERSION}</h3>"
  echo "<ul>"
  echo "$COMMITS" | while IFS= read -r line; do
    # Strip conventional-commit prefix: "feat(scope): msg" → "msg"
    clean=$(echo "$line" | sed -E 's/^[a-z]+(\([^)]*\))?!?:[[:space:]]*//')
    # Capitalise first letter
    clean="$(echo "${clean:0:1}" | tr '[:lower:]' '[:upper:]')${clean:1}"
    echo "    <li>${clean}</li>"
  done
  echo "</ul>"
}

# ── Assemble output ───────────────────────────────────────────────────────
changelog=""
if [[ -n "${OPENAI_API_KEY:-}" ]] && command -v jq &>/dev/null; then
  changelog=$(generate_with_llm) || true
fi

if [[ -z "$changelog" ]]; then
  changelog=$(generate_plain)
fi

cat <<EOF
${STATIC_HEADER}
${changelog}
EOF
