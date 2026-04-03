#!/usr/bin/env python3
"""
issue-fixer.py — polls GitHub for new issues, PR comments, and failing CI
checks, then sends each event to the IDE agent via the plugin's HTTP endpoint.

Usage:
    python3 scripts/issue-fixer.py [--dry-run] [--once]

Configuration (env vars):
    GITHUB_REPO          owner/repo  (default: catatafishen/agentbridge)
    GITHUB_TOKEN         personal access token with read:issues, contents, pull_requests, checks scopes
    AGENT_GITHUB_LOGIN   GitHub login of the bot account (used to skip bot's own PR comments)
    PLUGIN_URL           base URL of the Chat Web Server  (default: https://localhost:9642)
    STATE_FILE           path to JSON state file  (default: ~/.local/share/issue-fixer/state.json)
    POLL_INTERVAL        seconds between polls  (default: 300)
    BUSY_WAIT_INTERVAL   seconds between agent-busy retries  (default: 60)
    BUSY_WAIT_TIMEOUT    max seconds to wait for agent  (default: 86400)

Event flow:
    New open issue
      └─ existing open PR found? ──yes──> dispatch EXISTING_PR_PROMPT (review it)
      └─ no PR ──────────────────────────> dispatch ISSUE_PROMPT
            ├─ agent decides issue is unclear: posts clarification comment on issue, stops
            └─ agent decides issue is clear:  creates branch, implements fix, opens PR

    Dispatched issue still open + new author comment
      └─ dispatch CLARIFICATION_RECEIVED_PROMPT (re-investigate with new context)

    Issue closed / PR merged
      └─ state updated to "resolved" (no more monitoring)

    Open PR gets new comment
      └─ dispatch PR_COMMENT_PROMPT

    Open PR gets new review (CHANGES_REQUESTED or APPROVED)
      └─ dispatch PR_REVIEW_PROMPT

    Open PR head commit has new CI failure
      └─ dispatch CI_FAILURE_PROMPT

    Open PR has merge conflicts
      └─ dispatch PR_CONFLICT_PROMPT (rebase on remote master, no merge commits)
"""

import argparse
import json
import os
import re
import ssl
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

# ── Configuration ──────────────────────────────────────────────────────────────

GITHUB_REPO = os.environ.get("GITHUB_REPO", "catatafishen/agentbridge")
GITHUB_TOKEN = os.environ.get("GITHUB_TOKEN", "")
AGENT_GITHUB_LOGIN = os.environ.get("AGENT_GITHUB_LOGIN", "")
PLUGIN_URL = os.environ.get("PLUGIN_URL", "https://localhost:9642")
STATE_FILE = Path(os.environ.get("STATE_FILE",
                                 os.path.expanduser("~/.local/share/issue-fixer/state.json")))
POLL_INTERVAL = int(os.environ.get("POLL_INTERVAL", "300"))
BUSY_WAIT_INTERVAL = int(os.environ.get("BUSY_WAIT_INTERVAL", "60"))
BUSY_WAIT_TIMEOUT = int(os.environ.get("BUSY_WAIT_TIMEOUT", "86400"))

# ── Prompt templates ───────────────────────────────────────────────────────────

ISSUE_PROMPT = """\
>

Please investigate GitHub issue #{number}: **{title}**

**Issue description:**
{body}

**Existing PRs referencing this issue:**
{existing_prs}

**Instructions:**

1. Read the issue description carefully. If it is **unclear, incomplete, or lacks enough \
detail** to implement a fix safely, post a GitHub comment asking for clarification:
   ```
   gh issue comment {number} --body "..."
   ```
   After posting, **stop here** — do not create a branch or PR. \
The bot will re-dispatch this issue once the author responds.

2. If an existing PR already addresses this issue (listed above), \
switch to reviewing that PR rather than creating a new one. \
Fetch the branch, verify the implementation, and either approve/merge or push improvements.

3. If the issue is clear and no open PR exists:
   a. Create a branch named `fix/issue-{number}-{slug}` from the latest HEAD of the remote default branch.
   b. Investigate the root cause. Note the issue may have been filed against an older release — \
check commit history and release timestamps.
   c. Implement the fix using test-driven development where feasible. \
Address all IDE warnings and ensure test coverage exists.
   d. Run the full test suite; ensure everything passes.
   e. Commit: `fix: <short description> (closes #{number})`
   f. Open a pull request targeting the default branch with:
      - Title: `fix: <short description>`
      - Body: root cause explanation + fix summary + `Closes #{number}`

Start now.
"""

EXISTING_PR_PROMPT = """\
>

GitHub issue #{number} (**{title}**) already has a pull request:

**PR #{pr_number}**: {pr_title}
**Branch:** `{branch}`
**PR URL:** {pr_url}
**PR state:** {pr_state}

**Issue description:**
{body}

**Instructions:**

Please review PR #{pr_number} for issue #{number}:
1. Check out branch `{branch}` and review the implementation.
2. Verify that the fix fully addresses the issue.
3. Run the test suite on the branch.
4. If the PR is correct and complete: approve and/or merge it.
5. If changes are needed: push additional commits to the branch.
6. If the PR does not address the issue at all: close it with a comment explaining why, \
then create a fresh implementation on a new branch.

Start now.
"""

CLARIFICATION_RECEIVED_PROMPT = """\
>

GitHub issue #{number} (**{title}**) has a new response from the author.

**Full issue description:**
{body}

**New comment(s) from @{author}:**
{new_comments}

**Instructions:**

1. Re-read the issue and the new comment(s). If sufficient detail is now available, \
proceed with the fix:
   a. Create a branch named `fix/issue-{number}-{slug}` from the latest HEAD of \
the remote default branch.
   b. Implement the fix, run tests, commit: `fix: <short description> (closes #{number})`.
   c. Open a pull request targeting the default branch.

2. If the clarification is still insufficient, post another comment asking for the \
specific missing detail.

Start now.
"""

PR_COMMENT_PROMPT = """\
>

PR #{pr_number} "{pr_title}" has a new comment from @{author}.

**Comment:**
{body}

**PR branch:** `{branch}`
**PR URL:** {pr_url}

Please review the comment and take appropriate action — reply, address feedback, \
push a follow-up commit, or explain your decision.
"""

PR_REVIEW_PROMPT = """\
>

PR #{pr_number} "{pr_title}" has a new review from @{author} — verdict: **{state}**.

**Review summary:**
{body}

**PR branch:** `{branch}`
**PR URL:** {pr_url}

Please address the review feedback. If changes are requested, implement them on the branch \
and push. If the review is approved, you can merge.
"""

CI_FAILURE_PROMPT = """\
>

PR #{pr_number} "{pr_title}" has a failing CI check.

**Check:** {check_name}
**Conclusion:** {conclusion}
**Details:** {details_url}

**PR branch:** `{branch}`
**PR URL:** {pr_url}

Please check out the branch, investigate the CI failure, fix the root cause, \
and push a corrected commit.
"""

PR_CONFLICT_PROMPT = """\
>

PR #{pr_number} "{pr_title}" has merge conflicts with `{base_branch}`.

**PR branch:** `{branch}`
**PR URL:** {pr_url}

Please rebase this branch on the latest remote `{base_branch}` and resolve any conflicts. \
Use rebase — do not create a merge commit:

1. `git fetch origin`
2. `git checkout {branch}`
3. `git rebase origin/{base_branch}`
4. Resolve any conflicts, then `git rebase --continue`
5. `git push --force-with-lease origin {branch}`

Start now.
"""

# ── GitHub API ─────────────────────────────────────────────────────────────────

class GitHubRateLimitError(Exception):
    """Raised on HTTP 403 from GitHub — stops the current poll cycle."""


def _gh_request(path: str) -> Any:
    """GET request to the GitHub repos API for GITHUB_REPO."""
    url = f"https://api.github.com/repos/{GITHUB_REPO}/{path}"
    return _gh_raw_request(url)


def _gh_raw_request(url: str, method: str = "GET", data: bytes | None = None) -> Any:
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("User-Agent", "issue-fixer/1.0")
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("X-GitHub-Api-Version", "2022-11-28")
    if GITHUB_TOKEN:
        req.add_header("Authorization", f"Bearer {GITHUB_TOKEN}")
    if data:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            body = resp.read()
            return json.loads(body) if body else {}
    except urllib.error.HTTPError as exc:
        if exc.code == 403:
            raise GitHubRateLimitError(f"GitHub API rate limit (403) for {url}") from exc
        raise


def _gh_search(query: str) -> list[dict]:
    """Search GitHub issues/PRs."""
    encoded = urllib.parse.quote(query)
    url = f"https://api.github.com/search/issues?q={encoded}&per_page=20"
    result = _gh_raw_request(url)
    return result.get("items", [])


def post_github_comment(issue_number: int, body: str, dry_run: bool = False) -> None:
    """Posts a comment directly to a GitHub issue (used for error reporting)."""
    if dry_run:
        print(f"  [dry-run] Would post comment to issue #{issue_number}:\n  {body[:200]}…")
        return
    url = f"https://api.github.com/repos/{GITHUB_REPO}/issues/{issue_number}/comments"
    _gh_raw_request(url, method="POST", data=json.dumps({"body": body}).encode())
    print(f"  ✓ posted comment to issue #{issue_number}")


def fetch_open_issues() -> list[dict]:
    """Returns open issues (not PRs), newest first."""
    items = _gh_request("issues?state=open&per_page=50&sort=created&direction=desc")
    return [i for i in items if "pull_request" not in i]


def fetch_open_prs() -> list[dict]:
    """Returns open pull requests, newest first."""
    return _gh_request("pulls?state=open&per_page=50&sort=created&direction=desc")


def fetch_pr_detail(pr_number: int) -> dict | None:
    """Returns full PR object (includes head branch name, mergeable status)."""
    try:
        return _gh_request(f"pulls/{pr_number}")
    except (urllib.error.URLError, urllib.error.HTTPError):
        return None


def find_prs_for_issue(issue_number: int) -> list[dict]:
    """Finds open PRs that reference this issue via branch naming convention or body search."""
    open_prs = fetch_open_prs()

    # Primary: branch naming convention created by this bot
    by_branch = [p for p in open_prs if p["head"]["ref"].startswith(f"fix/issue-{issue_number}-")]
    if by_branch:
        return by_branch

    # Fallback: search GitHub for PRs that mention the issue number in their body
    try:
        query = f"repo:{GITHUB_REPO} is:pr is:open closes:#{issue_number}"
        results = _gh_search(query)
        pr_numbers = {p["number"] for p in open_prs}
        return [r for r in results if r["number"] in pr_numbers]
    except GitHubRateLimitError:
        raise
    except (urllib.error.URLError, urllib.error.HTTPError) as exc:
        print(f"  [warn] PR search for issue #{issue_number} failed: {exc}")
        return []


def fetch_issue_comments(issue_number: int, since_id: int = 0) -> list[dict]:
    """Returns issue comments with id > since_id, oldest first."""
    comments = _gh_request(
        f"issues/{issue_number}/comments?per_page=100&sort=created&direction=asc"
    )
    return [c for c in comments if c["id"] > since_id]


def fetch_pr_reviews(pr_number: int) -> list[dict]:
    return _gh_request(f"pulls/{pr_number}/reviews?per_page=100")


def fetch_check_runs(sha: str) -> list[dict]:
    result = _gh_request(f"commits/{sha}/check-runs?per_page=100")
    return result.get("check_runs", [])


# ── Plugin HTTP client ─────────────────────────────────────────────────────────

def _plugin_request(path: str, data: bytes | None = None,
                    method: str = "GET") -> tuple[int, dict]:
    # SSL verification is intentionally disabled — connects to the local plugin
    # server which uses a self-signed certificate on localhost.
    ctx = ssl.create_default_context()
    ctx.check_hostname = False  # NOSONAR — localhost self-signed cert
    ctx.verify_mode = ssl.CERT_NONE  # NOSONAR — localhost self-signed cert

    urls = [f"{PLUGIN_URL}{path}"]
    port_match = re.search(r":(\d+)$", PLUGIN_URL)
    if port_match:
        http_port = int(port_match.group(1)) + 1
        http_base = re.sub(r"^https://", "http://",
                           re.sub(r":\d+$", f":{http_port}", PLUGIN_URL))
        urls.append(f"{http_base}{path}")

    last_err: Exception | None = None
    for url in urls:
        req = urllib.request.Request(
            url, data=data, method=method,
            headers={"Content-Type": "application/json"} if data else {},
        )
        try:
            with urllib.request.urlopen(req, context=ctx, timeout=10) as resp:
                body = resp.read()
                return resp.status, json.loads(body) if body else {}
        except urllib.error.HTTPError as exc:
            body = exc.read()
            return exc.code, json.loads(body) if body else {}
        except urllib.error.URLError as exc:
            last_err = exc
            continue

    raise RuntimeError(f"Plugin unreachable at {PLUGIN_URL}: {last_err}")


def is_agent_running() -> bool:
    try:
        status, info = _plugin_request("/info")
        return status == 200 and bool(info.get("running", False))
    except (OSError, RuntimeError, ValueError):
        return False


def wait_for_agent_free(timeout: int = BUSY_WAIT_TIMEOUT) -> bool:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if not is_agent_running():
            return True
        remaining = int(deadline - time.monotonic())
        print(f"  [busy] agent running — retrying in {BUSY_WAIT_INTERVAL}s "
              f"(timeout in {remaining}s)…")
        time.sleep(BUSY_WAIT_INTERVAL)
    return False


def send_prompt(text: str, dry_run: bool = False) -> None:
    if dry_run:
        print(f"[dry-run] Would POST prompt:\n{text[:400]}…\n")
        return

    if not wait_for_agent_free():
        raise RuntimeError(f"Agent still busy after {BUSY_WAIT_TIMEOUT}s — giving up")

    data = json.dumps({"text": text}).encode()
    status, resp = _plugin_request("/prompt", data=data, method="POST")

    if status == 409:
        print("  [busy] /prompt returned 409 — waiting and retrying once…")
        if not wait_for_agent_free():
            raise RuntimeError("Agent busy (409) and still busy after waiting")
        status, resp = _plugin_request("/prompt", data=data, method="POST")

    if status not in (200, 204):
        raise RuntimeError(f"/prompt returned HTTP {status}: {resp}")

    print(f"[prompt] sent → HTTP {status}")


# ── State ──────────────────────────────────────────────────────────────────────

ISSUE_STATUS_DISPATCHED = "dispatched"
ISSUE_STATUS_RESOLVED = "resolved"
NO_DESCRIPTION = "(no description provided)"


def load_state() -> dict:
    if STATE_FILE.exists():
        raw = json.loads(STATE_FILE.read_text())
        # Migrate legacy format: list of ints → dict of dicts
        if "processed_issue_numbers" in raw and "issues" not in raw:
            raw["issues"] = {
                str(n): {
                    "status": ISSUE_STATUS_RESOLVED,
                    "dispatched_at": None,
                    "last_comment_id": 0,
                }
                for n in raw.pop("processed_issue_numbers")
            }
        return raw
    return {
        "issues": {},              # str(number) → {status, dispatched_at, last_comment_id}
        "pr_comment_watermarks": {},  # str(pr_number) → last comment id
        "pr_review_watermarks": {},   # str(pr_number) → last review id
        "pr_known_failures": {},      # sha → [check_run_id, ...]
        "pr_conflict_watermarks": {}, # str(pr_number) → head sha at last conflict dispatch
        "last_check": None,
    }


def save_state(state: dict) -> None:
    STATE_FILE.parent.mkdir(parents=True, exist_ok=True)
    STATE_FILE.write_text(json.dumps(state, indent=2))


# ── Helpers ────────────────────────────────────────────────────────────────────

def slugify(title: str, max_len: int = 40) -> str:
    slug = re.sub(r"[^a-z0-9]+", "-", title.lower()).strip("-")
    return slug[:max_len].rstrip("-")


def truncate(text: str, max_len: int = 10_000) -> str:
    if len(text) <= max_len:
        return text
    return text[:max_len] + f"\n\n… *(truncated — {len(text) - max_len} chars omitted)*"


def format_prs(prs: list[dict]) -> str:
    if not prs:
        return "None."
    return "\n".join(
        f"- PR #{p['number']}: {p['title']} ({p.get('state', 'open')}) — {p['html_url']}"
        for p in prs
    )


def is_bot(login: str) -> bool:
    return login.endswith("[bot]") or (bool(AGENT_GITHUB_LOGIN) and login == AGENT_GITHUB_LOGIN)


# ── Issue processing ───────────────────────────────────────────────────────────

def _mark_resolved_issues(issues_state: dict, open_numbers: set[str]) -> None:
    """Mark dispatched issues that are no longer open as resolved."""
    for key, info in issues_state.items():
        if info.get("status") == ISSUE_STATUS_DISPATCHED and key not in open_numbers:
            info["status"] = ISSUE_STATUS_RESOLVED
            print(f"[poll] issue #{key} is now closed — marking resolved")


def process_issues(state: dict, dry_run: bool) -> None:
    """Dispatch at most ONE issue action per poll cycle, then return.

    Priority order:
    1. Author replied to a previously-dispatched issue → re-dispatch with clarification
    2. New issue (oldest first) → dispatch fix prompt

    This ensures the agent finishes one task before receiving the next.
    """
    issues_state: dict = state.setdefault("issues", {})

    print("[poll] fetching open issues…")
    open_issues = fetch_open_issues()
    open_numbers = {str(i["number"]) for i in open_issues}

    _mark_resolved_issues(issues_state, open_numbers)

    dispatched_issues = [
        i for i in open_issues
        if issues_state.get(str(i["number"]), {}).get("status") == ISSUE_STATUS_DISPATCHED
    ]
    new_issues = [i for i in open_issues if str(i["number"]) not in issues_state]

    if not new_issues and not dispatched_issues:
        print("[poll] no new or active issues")
        return

    if new_issues:
        print(f"[poll] {len(new_issues)} new issue(s) queued: {[i['number'] for i in new_issues]}")
    if dispatched_issues:
        print(f"[poll] {len(dispatched_issues)} dispatched issue(s) to monitor: "
              f"{[i['number'] for i in dispatched_issues]}")

    # Priority 1: check for author replies on dispatched issues (oldest first)
    for issue in reversed(dispatched_issues):
        if _check_author_response(issue, issues_state, dry_run):
            if not dry_run:
                save_state(state)
            return

    # Priority 2: dispatch ONE new issue (oldest first)
    if new_issues:
        issue = new_issues[-1]  # list is newest-first, so last = oldest
        print(f"[poll] dispatching oldest new issue #{issue['number']} "
              f"({len(new_issues) - 1} remaining in queue)")
        if _dispatch_new_issue(issue, issues_state, dry_run):
            if not dry_run:
                save_state(state)


def _dispatch_new_issue(issue: dict, issues_state: dict, dry_run: bool) -> bool:
    number = issue["number"]
    title = issue["title"]
    body = truncate((issue.get("body") or "").strip())
    key = str(number)

    print(f"\n→ new issue #{number}: {title}")

    existing_prs = []
    try:
        existing_prs = find_prs_for_issue(number)
    except Exception as exc:
        print(f"  [warn] failed to search for existing PRs: {exc}")

    open_prs = [p for p in existing_prs if p.get("state") == "open"]

    if open_prs:
        pr = open_prs[0]
        pr_detail = fetch_pr_detail(pr["number"])
        branch = (pr_detail or {}).get("head", {}).get("ref", pr["head"]["ref"]
                  if "head" in pr else "unknown")
        print(f"  → open PR #{pr['number']} found — dispatching review prompt")
        prompt = EXISTING_PR_PROMPT.format(
            number=number,
            title=title,
            body=body or NO_DESCRIPTION,
            pr_number=pr["number"],
            pr_title=pr["title"],
            branch=branch,
            pr_url=pr["html_url"],
            pr_state=pr.get("state", "open"),
        )
    else:
        prompt = ISSUE_PROMPT.format(
            number=number,
            title=title,
            body=body or NO_DESCRIPTION,
            existing_prs=format_prs(existing_prs),
            slug=slugify(title),
        )

    try:
        send_prompt(prompt, dry_run=dry_run)
        issues_state[key] = {
            "status": ISSUE_STATUS_DISPATCHED,
            "dispatched_at": datetime.now(timezone.utc).isoformat(),
            "last_comment_id": 0,
        }
        print(f"  ✓ issue #{number} dispatched")
        return True
    except RuntimeError as exc:
        print(f"  ✗ failed for #{number}: {exc}")
        return False


def _check_author_response(issue: dict, issues_state: dict, dry_run: bool) -> bool:
    """Re-dispatches a previously-dispatched issue if the original author posted new comments."""
    number = issue["number"]
    key = str(number)
    info = issues_state[key]
    last_comment_id: int = info.get("last_comment_id", 0)
    issue_author: str = issue["user"]["login"]

    try:
        new_comments = fetch_issue_comments(number, since_id=last_comment_id)
    except Exception as exc:
        print(f"  [warn] could not fetch comments for issue #{number}: {exc}")
        return False

    # Only re-dispatch for replies from the original issue author (not bots, not the agent)
    author_replies = [
        c for c in new_comments
        if c["user"]["login"] == issue_author and not is_bot(c["user"]["login"])
    ]

    if not author_replies:
        return False

    title = issue["title"]
    body = truncate((issue.get("body") or "").strip())
    new_comments_text = "\n\n".join(
        f"**@{c['user']['login']}** ({c['created_at']}):\n"
        + truncate((c.get("body") or "").strip(), 2_000)
        for c in author_replies
    )

    print(f"\n→ issue #{number} has {len(author_replies)} new author comment(s) — re-dispatching")
    prompt = CLARIFICATION_RECEIVED_PROMPT.format(
        number=number,
        title=title,
        body=body or NO_DESCRIPTION,
        author=issue_author,
        new_comments=new_comments_text,
        slug=slugify(title),
    )

    try:
        send_prompt(prompt, dry_run=dry_run)
        info["last_comment_id"] = max(c["id"] for c in author_replies)
        info["dispatched_at"] = datetime.now(timezone.utc).isoformat()
        print(f"  ✓ re-dispatched issue #{number}")
        return True
    except RuntimeError as exc:
        print(f"  ✗ failed for #{number}: {exc}")
        return False


# ── PR processing ──────────────────────────────────────────────────────────────

def process_pr_comments(pr: dict, state: dict, dry_run: bool) -> None:
    pr_number = pr["number"]
    pr_key = str(pr_number)
    watermarks: dict = state.setdefault("pr_comment_watermarks", {})
    last_id: int = watermarks.get(pr_key, 0)

    new_comments = [
        c for c in fetch_issue_comments(pr_number, since_id=last_id)
        if not is_bot(c["user"]["login"])
    ]
    if not new_comments:
        return

    for comment in new_comments:
        author = comment["user"]["login"]
        body = truncate((comment.get("body") or "").strip())
        print(f"  → new comment on PR #{pr_number} from @{author}")

        prompt = PR_COMMENT_PROMPT.format(
            pr_number=pr_number,
            pr_title=pr["title"],
            author=author,
            body=body,
            branch=pr["head"]["ref"],
            pr_url=pr["html_url"],
        )
        try:
            send_prompt(prompt, dry_run=dry_run)
            watermarks[pr_key] = comment["id"]
            if not dry_run:
                save_state(state)
                print(f"    ✓ comment {comment['id']} dispatched")
        except RuntimeError as exc:
            print(f"    ✗ failed: {exc}")
            break


def process_pr_reviews(pr: dict, state: dict, dry_run: bool) -> None:
    pr_number = pr["number"]
    pr_key = str(pr_number)
    watermarks: dict = state.setdefault("pr_review_watermarks", {})
    last_id: int = watermarks.get(pr_key, 0)

    actionable = [
        r for r in fetch_pr_reviews(pr_number)
        if r["id"] > last_id
        and r["state"] in ("CHANGES_REQUESTED", "APPROVED")
        and not is_bot(r["user"]["login"])
    ]
    if not actionable:
        return

    for review in actionable:
        author = review["user"]["login"]
        state_label = review["state"].replace("_", " ").title()
        body = truncate((review.get("body") or "").strip())
        print(f"  → new review on PR #{pr_number} from @{author}: {state_label}")

        prompt = PR_REVIEW_PROMPT.format(
            pr_number=pr_number,
            pr_title=pr["title"],
            author=author,
            state=state_label,
            body=body or "(no summary — see individual review comments on the PR)",
            branch=pr["head"]["ref"],
            pr_url=pr["html_url"],
        )
        try:
            send_prompt(prompt, dry_run=dry_run)
            watermarks[pr_key] = review["id"]
            if not dry_run:
                save_state(state)
                print(f"    ✓ review {review['id']} dispatched")
        except RuntimeError as exc:
            print(f"    ✗ failed: {exc}")
            break


def process_pr_ci(pr: dict, state: dict, dry_run: bool) -> None:
    pr_number = pr["number"]
    sha = pr["head"]["sha"]
    known_failures: dict = state.setdefault("pr_known_failures", {})
    already_reported: list = known_failures.get(sha, [])

    failed = [
        c for c in fetch_check_runs(sha)
        if c["conclusion"] in ("failure", "action_required", "timed_out", "startup_failure")
        and c["id"] not in already_reported
    ]
    if not failed:
        return

    for check in failed:
        details_url = check.get("details_url") or check.get("html_url") or pr["html_url"]
        conclusion = check["conclusion"].replace("_", " ")
        print(f"  → CI failure on PR #{pr_number}: {check['name']} ({conclusion})")

        prompt = CI_FAILURE_PROMPT.format(
            pr_number=pr_number,
            pr_title=pr["title"],
            check_name=check["name"],
            conclusion=conclusion,
            details_url=details_url,
            branch=pr["head"]["ref"],
            pr_url=pr["html_url"],
        )
        try:
            send_prompt(prompt, dry_run=dry_run)
            already_reported.append(check["id"])
            known_failures[sha] = already_reported
            if not dry_run:
                save_state(state)
                print(f"    ✓ CI failure '{check['name']}' dispatched")
        except RuntimeError as exc:
            print(f"    ✗ failed: {exc}")
            break


def process_pr_conflicts(pr: dict, state: dict, dry_run: bool) -> None:
    """Dispatch a rebase prompt if the PR has merge conflicts.

    Tracks by head SHA — if the branch is force-pushed but still conflicts, dispatches again.
    Skips if GitHub hasn't computed mergeability yet (mergeable=null).
    """
    pr_number = pr["number"]
    pr_key = str(pr_number)
    sha = pr["head"]["sha"]
    conflict_watermarks: dict = state.setdefault("pr_conflict_watermarks", {})

    if conflict_watermarks.get(pr_key) == sha:
        # Already dispatched for this exact commit — no point asking again
        return

    detail = fetch_pr_detail(pr_number)
    if detail is None:
        return

    mergeable = detail.get("mergeable")
    # mergeable=None means GitHub is still computing — skip until next poll
    if mergeable is None:
        return

    mergeable_state = detail.get("mergeable_state", "")
    if mergeable or mergeable_state not in ("dirty", "blocked"):
        return

    branch = detail["head"]["ref"]
    base_branch = detail["base"]["ref"]
    print(f"  → merge conflict on PR #{pr_number} "
          f"(mergeable={mergeable}, state={mergeable_state})")

    prompt = PR_CONFLICT_PROMPT.format(
        pr_number=pr_number,
        pr_title=pr["title"],
        branch=branch,
        base_branch=base_branch,
        pr_url=pr["html_url"],
    )
    try:
        send_prompt(prompt, dry_run=dry_run)
        conflict_watermarks[pr_key] = sha
        if not dry_run:
            save_state(state)
            print(f"    ✓ conflict rebase dispatched for PR #{pr_number}")
    except RuntimeError as exc:
        print(f"    ✗ failed: {exc}")


def process_prs(state: dict, dry_run: bool) -> None:
    print("[poll] fetching open PRs…")
    prs = fetch_open_prs()

    if not prs:
        print("[poll] no open PRs")
        return

    print(f"[poll] {len(prs)} open PR(s): {[p['number'] for p in prs]}")
    for pr in prs:
        print(f"\n  PR #{pr['number']}: {pr['title']}")
        process_pr_conflicts(pr, state, dry_run)
        process_pr_comments(pr, state, dry_run)
        process_pr_reviews(pr, state, dry_run)
        process_pr_ci(pr, state, dry_run)


# ── Main loop ──────────────────────────────────────────────────────────────────

def poll_once(dry_run: bool) -> None:
    state = load_state()

    try:
        process_issues(state, dry_run)
    except GitHubRateLimitError as exc:
        print(f"[error] GitHub rate limit hit — stopping this cycle: {exc}")
        state["last_check"] = datetime.now(timezone.utc).isoformat()
        if not dry_run:
            save_state(state)
        return
    except Exception as exc:
        print(f"[error] issue processing: {exc}")

    try:
        process_prs(state, dry_run)
    except GitHubRateLimitError as exc:
        print(f"[error] GitHub rate limit hit — stopping this cycle: {exc}")
    except Exception as exc:
        print(f"[error] PR processing: {exc}")

    state["last_check"] = datetime.now(timezone.utc).isoformat()
    if not dry_run:
        save_state(state)


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Poll GitHub and dispatch events to the IDE agent"
    )
    parser.add_argument("--dry-run", action="store_true",
                        help="Show prompts without sending or updating state")
    parser.add_argument("--once", action="store_true",
                        help="Run a single poll cycle and exit")
    args = parser.parse_args()

    print(f"issue-fixer starting — repo={GITHUB_REPO} plugin={PLUGIN_URL}")
    if args.dry_run:
        print("  [dry-run mode — no prompts will be sent]\n")

    if args.once:
        poll_once(dry_run=args.dry_run)
        return

    while True:
        try:
            poll_once(dry_run=args.dry_run)
        except Exception as exc:
            print(f"[error] {exc}")
        print(f"\n[sleep] next poll in {POLL_INTERVAL}s…")
        time.sleep(POLL_INTERVAL)


if __name__ == "__main__":
    main()
