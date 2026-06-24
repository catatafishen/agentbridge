#!/usr/bin/env python3
"""Parse JUnit XML reports and upsert a PR comment listing the failed tests.

Invoked by .github/workflows/sonarqube-pr-analyze.yml (privileged half) after the
unprivileged build workflow fails. It reads ONLY the XML data bundled in the build
artifact and never executes fork code. The comment body is sent to the GitHub API
as a JSON document on stdin, never interpolated into a shell, so a crafted test
name cannot inject commands.

Environment:
  GITHUB_REPOSITORY  owner/repo (provided by Actions)
  PR_NUMBER          pull request number to comment on
  RUN_URL            html_url of the failed build run (for the "see log" link)
  GH_TOKEN           token used by the gh CLI
"""
import glob
import json
import os
import subprocess
import xml.etree.ElementTree as ET

MARKER = "<!-- agentbridge:failed-tests-report -->"
MAX_ROWS = 50
MAX_MESSAGE_LEN = 160


def collect_failures():
    """Return a sorted list of {class, name, kind, message} for every failed test."""
    failures = []
    for path in glob.glob("**/build/test-results/**/*.xml", recursive=True):
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError:
            continue
        suites = [root] if root.tag == "testsuite" else root.findall(".//testsuite")
        for suite in suites:
            for case in suite.findall("testcase"):
                bad = case.find("failure")
                if bad is None:
                    bad = case.find("error")
                if bad is None:
                    continue
                failures.append({
                    "class": case.get("classname", ""),
                    "name": case.get("name", ""),
                    "kind": bad.tag,
                    "message": (bad.get("message") or "").strip(),
                })
    failures.sort(key=lambda f: (f["class"], f["name"]))
    return failures


def build_body(failures, run_url):
    if not failures:
        return (
            f"{MARKER}\n"
            "### \u274c Build failed \u2014 no failing tests found in the reports\n\n"
            "The **SonarQube PR Build** workflow failed without a recorded test "
            "failure. This usually means a compilation or setup error rather than a "
            "test assertion. "
            f"See the [build log]({run_url}) for details."
        )
    plural = "s" if len(failures) != 1 else ""
    lines = [
        MARKER,
        f"### \u274c {len(failures)} failing test{plural} in **SonarQube PR Build**",
        "",
        f"From the [build log]({run_url}):",
        "",
        "| Test | Type | Message |",
        "| --- | --- | --- |",
    ]
    for f in failures[:MAX_ROWS]:
        test = f"`{f['class']}.{f['name']}`" if f["class"] else f"`{f['name']}`"
        message = f["message"].replace("|", "\\|").replace("\n", " ").replace("\r", " ")
        if len(message) > MAX_MESSAGE_LEN:
            message = message[:MAX_MESSAGE_LEN - 1] + "\u2026"
        lines.append(f"| {test} | {f['kind']} | {message} |")
    if len(failures) > MAX_ROWS:
        lines.append("")
        lines.append(f"_\u2026and {len(failures) - MAX_ROWS} more. See the build log._")
    return "\n".join(lines)


def gh_api(method, endpoint, payload=None):
    args = ["gh", "api", "--method", method, endpoint]
    if payload is not None:
        args += ["--input", "-"]
    return subprocess.run(
        args,
        input=json.dumps(payload) if payload is not None else None,
        check=True, capture_output=True, text=True,
    ).stdout


def find_existing_comment(repo, pr):
    out = gh_api("GET", f"repos/{repo}/issues/{pr}/comments?per_page=100")
    for comment in json.loads(out):
        if MARKER in (comment.get("body") or ""):
            return comment["id"]
    return None


def main():
    repo = os.environ["GITHUB_REPOSITORY"]
    pr = os.environ.get("PR_NUMBER", "").strip()
    run_url = os.environ.get("RUN_URL", "")
    if not pr:
        print("No PR number provided \u2014 nothing to do")
        return

    body = build_body(collect_failures(), run_url)
    comment_id = find_existing_comment(repo, pr)
    if comment_id:
        gh_api("PATCH", f"repos/{repo}/issues/comments/{comment_id}", {"body": body})
        print(f"Updated existing comment {comment_id}")
    else:
        gh_api("POST", f"repos/{repo}/issues/{pr}/comments", {"body": body})
        print("Created failed-tests comment")


if __name__ == "__main__":
    main()
