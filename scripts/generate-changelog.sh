#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# generate-changelog.sh — Generate release notes from git commits,
# summarised by an LLM for end-user consumption.
#
# Usage:
#   scripts/generate-changelog.sh [VERSION]
#
# Environment:
#   PLUGIN_VERSION      — fallback if VERSION positional arg is omitted
#   GH_MODELS_TOKEN     — GitHub Models PAT (models:read scope, free tier)
#   OPENAI_API_KEY      — fallback: OpenAI API key
#   CHANGELOG_MODEL     — override model name (default: gpt-4o-mini)
#   CHANGELOG_BASELINE  — override baseline tag (default: auto-detect)
#   CHANGELOG_FORMAT    — output format: "html" (default) or "md"
#
# LLM provider priority:
#   1. GH_MODELS_TOKEN  → GitHub Models (free with Copilot subscription)
#   2. OPENAI_API_KEY   → OpenAI API (paid)
#   3. Neither set      → plain commit messages (no summarisation)
#
# Baseline detection (when CHANGELOG_BASELINE is not set):
#   1. 'marketplace-latest' tag — includes all commits since last marketplace
#      publish (used for plugin.xml change-notes that accumulate changes)
#   2. Falls back to latest v* tag if no marketplace tag exists
#
# Output: HTML (for plugin.xml <change-notes>) or Markdown (for GitHub
#         release notes), written to stdout.
# ---------------------------------------------------------------------------
set -euo pipefail

# ── Configuration ──────────────────────────────────────────────────────────
VERSION="${1:-${PLUGIN_VERSION:-}}"
if [[ -z "$VERSION" ]]; then
  echo "Error: version required (pass as arg or set PLUGIN_VERSION)" >&2
  exit 1
fi

FORMAT="${CHANGELOG_FORMAT:-html}"

# ── Determine baseline tag ─────────────────────────────────────────────────
if [[ -n "${CHANGELOG_BASELINE:-}" ]]; then
  BASELINE_TAG="$CHANGELOG_BASELINE"
else
  BASELINE_TAG=$(git tag --list 'marketplace-latest' | head -n1)
  if [[ -z "$BASELINE_TAG" ]]; then
    BASELINE_TAG=$(git tag --list 'v*' --sort=-v:refname | head -n1)
  fi
fi

# ── Collect commit subjects ────────────────────────────────────────────────
if [[ -z "$BASELINE_TAG" ]]; then
  COMMITS=$(git log --pretty=format:"%s" HEAD)
else
  COMMITS=$(git log --pretty=format:"%s" "${BASELINE_TAG}..HEAD")
fi

if [[ -z "$COMMITS" ]]; then
  echo "No commits since ${BASELINE_TAG:-initial}" >&2
  COMMITS="General improvements and bug fixes"
fi

# Also collect PR descriptions for richer context
PR_DESCRIPTIONS=""
if [[ -z "$BASELINE_TAG" ]]; then
  PR_MERGE_COMMITS=$(git log --merges --pretty=format:"%H" HEAD 2>/dev/null || true)
else
  PR_MERGE_COMMITS=$(git log --merges --pretty=format:"%H" "${BASELINE_TAG}..HEAD" 2>/dev/null || true)
fi
if [[ -n "$PR_MERGE_COMMITS" ]]; then
  while IFS= read -r sha; do
    body=$(git log -1 --pretty=format:"%b" "$sha" 2>/dev/null || true)
    if [[ -n "$body" ]]; then
      PR_DESCRIPTIONS+="$body"$'\n\n'
    fi
  done <<< "$PR_MERGE_COMMITS"
fi

# ── Determine LLM endpoint ─────────────────────────────────────────────────
LLM_TOKEN=""
LLM_ENDPOINT=""

if [[ -n "${GH_MODELS_TOKEN:-}" ]]; then
  LLM_TOKEN="$GH_MODELS_TOKEN"
  LLM_ENDPOINT="https://models.inference.ai.azure.com/chat/completions"
elif [[ -n "${OPENAI_API_KEY:-}" ]]; then
  LLM_TOKEN="$OPENAI_API_KEY"
  LLM_ENDPOINT="https://api.openai.com/v1/chat/completions"
fi

# ── Static header (HTML only, for plugin.xml) ──────────────────────────────
STATIC_HEADER='<p><b>AgentBridge</b> connects AI coding agents to your IDE via 100+ MCP tools
for code intelligence, navigation, editing, debugging, and git.</p>
<p>
  <a href="https://github.com/catatafishen/agentbridge">GitHub</a> &middot;
  <a href="https://github.com/catatafishen/agentbridge/releases">All Releases</a>
</p>
<hr/>'

# ── LLM summarisation ─────────────────────────────────────────────────────
# Returns JSON: { "title": "...", "summary": "...", "bullets": ["...", ...] }
call_llm() {
  local model="${CHANGELOG_MODEL:-gpt-4o-mini}"
  local prompt
  prompt=$(cat <<'SYSTEM'
You are writing release notes for a JetBrains IDE plugin called AgentBridge.
AgentBridge connects AI coding agents (GitHub Copilot, Claude, etc.) to the
IDE via MCP tools — giving agents access to code navigation, refactoring,
debugging, git, and other IDE features.

Your audience is plugin users (developers who use AI coding assistants in
IntelliJ-based IDEs). They want to know what changed that they will notice
when using the plugin.

Given the commit messages and PR descriptions since the last release, produce
a JSON object with:
- "title": A short, descriptive release title (3-6 words). No version number.
  Describe the theme of the release in plain language.
- "summary": 1-2 sentences of naturally flowing text describing the most
  important change(s) in this release. Write as if telling a colleague what
  shipped.
- "bullets": An array of 3-7 concise bullet points. Each bullet should
  describe one user-visible improvement or fix.

Rules:
- Write from the user's perspective. "You can now..." or "Fixed an issue
  where..." rather than "Added class X" or "Refactored module Y".
- Merge related commits into a single bullet. Many commits are implementation
  steps of one feature — find the feature, not the steps.
- Do NOT use emojis anywhere.
- Do NOT group by commit type (feat/fix/refactor). Group by user impact.
- Skip purely internal changes that users cannot observe (CI tweaks,
  refactoring with no behavior change, dependency bumps with no user impact).
- If all commits are internal/invisible, say so honestly: "Internal
  improvements to code quality and reliability."
- Keep bullets concrete: "Faster file search in large projects" is better
  than "Performance improvements".
- No marketing fluff. Be factual and specific.
SYSTEM
  )

  local user_msg="Commit messages:\n${COMMITS}"
  if [[ -n "$PR_DESCRIPTIONS" ]]; then
    user_msg+="\n\nPR descriptions (more context):\n${PR_DESCRIPTIONS}"
  fi

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
    -H "Authorization: Bearer ${LLM_TOKEN}" \
    -H "Content-Type: application/json" \
    -d "$payload" \
    "$LLM_ENDPOINT" 2>&1) || {
    echo "LLM API call failed: $response" >&2
    return 1
  }

  local content
  content=$(echo "$response" | jq -r '.choices[0].message.content // empty')
  if [[ -z "$content" ]]; then
    echo "LLM returned empty content" >&2
    return 1
  fi

  local title summary
  title=$(echo "$content" | jq -r '.title // empty')
  summary=$(echo "$content" | jq -r '.summary // empty')
  local bullets
  bullets=$(echo "$content" | jq -r '.bullets[]? // empty')

  if [[ -z "$title" || -z "$bullets" ]]; then
    echo "LLM response missing title or bullets" >&2
    return 1
  fi

  # Output format: line 1 = title, line 2 = summary, rest = bullets
  echo "$title"
  echo "$summary"
  echo "$bullets"
}

# ── Fallback: clean commit subjects ───────────────────────────────────────
# Strip conventional-commit prefixes for cleaner plain output.
strip_prefix() {
  sed -E 's/^[a-z]+(\([^)]*\))?!?:[[:space:]]*//'
}

capitalise() {
  while IFS= read -r line; do
    echo "$(echo "${line:0:1}" | tr '[:lower:]' '[:upper:]')${line:1}"
  done
}

# ── Format: HTML (for plugin.xml <change-notes>) ──────────────────────────
format_html() {
  local title="$1"
  local summary="$2"
  shift 2
  local bullets=("$@")

  if [[ -n "$title" ]]; then
    echo "<h3>${VERSION} &mdash; ${title}</h3>"
  else
    echo "<h3>${VERSION}</h3>"
  fi
  if [[ -n "$summary" ]]; then
    echo "<p>${summary}</p>"
  fi
  echo "<ul>"
  for bullet in "${bullets[@]}"; do
    echo "    <li>${bullet}</li>"
  done
  echo "</ul>"
  if [[ -n "$LLM_TOKEN" && ${#LLM_BULLETS[@]} -gt 0 ]]; then
    echo "<p><em>Release notes generated with AI assistance.</em></p>"
  fi
}

# ── Format: Markdown (for GitHub release notes) ───────────────────────────
format_md() {
  local title="$1"
  local summary="$2"
  shift 2
  local bullets=("$@")

  if [[ -n "$title" ]]; then
    echo "## ${VERSION} — ${title}"
  else
    echo "## ${VERSION}"
  fi
  echo ""
  if [[ -n "$summary" ]]; then
    echo "$summary"
    echo ""
  fi
  for bullet in "${bullets[@]}"; do
    echo "- ${bullet}"
  done
  if [[ -n "$LLM_TOKEN" && ${#LLM_BULLETS[@]} -gt 0 ]]; then
    echo ""
    echo "---"
    echo "*These release notes were generated automatically with AI assistance.*"
  fi
}

# ── Generate content ──────────────────────────────────────────────────────
LLM_TITLE=""
LLM_SUMMARY=""
LLM_BULLETS=()

if [[ -n "$LLM_TOKEN" ]] && command -v jq &>/dev/null; then
  llm_output=$(call_llm) || true
  if [[ -n "$llm_output" ]]; then
    LLM_TITLE=$(echo "$llm_output" | head -1)
    LLM_SUMMARY=$(echo "$llm_output" | sed -n '2p')
    mapfile -t LLM_BULLETS < <(echo "$llm_output" | tail -n +3)
  fi
fi

# Fall back to cleaned commit subjects if LLM didn't produce output
if [[ ${#LLM_BULLETS[@]} -eq 0 ]]; then
  mapfile -t LLM_BULLETS < <(echo "$COMMITS" | strip_prefix | capitalise)
fi

# ── Output ─────────────────────────────────────────────────────────────────
if [[ "$FORMAT" == "md" ]]; then
  format_md "$LLM_TITLE" "$LLM_SUMMARY" "${LLM_BULLETS[@]}"
else
  echo "$STATIC_HEADER"
  echo ""
  format_html "$LLM_TITLE" "$LLM_SUMMARY" "${LLM_BULLETS[@]}"
fi
