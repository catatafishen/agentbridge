# CI Security: Fork PR Scanning

This document explains the security design for running SonarQube and ClusterFuzzLite
analysis on pull requests from forked repositories.

## Why Scan Fork PRs At All?

Scanning code from external contributors *before* it is merged is the whole point.
Disabling scanners on fork PRs — the default suggestion from some security tools — is
the wrong trade-off: it removes the check at exactly the moment it is most needed
(before untrusted code enters the main branch). The question is therefore not *whether*
to scan fork PRs, but *how* to do it securely.

---

## SonarQube: The `workflow_run` Two-Workflow Pattern

### The Problem with `pull_request_target`

The `pull_request_target` trigger runs in the context of the *base* repository, meaning
it has write permissions and access to repository secrets (e.g. `SONAR_TOKEN`). If you
also check out the fork's code in that context, a malicious contributor could embed
shell commands in `build.gradle.kts`, a Makefile, or a test script that runs during the
build and exfiltrates `SONAR_TOKEN`.

The critical failure mode is: a maintainer clicks "Approve" on the environment gate to
*get* the CI results, intending to review the code *after* seeing them — but the
malicious build script runs first.

### Solution: Split Into Two Workflows

**`sonarqube-pr-build.yml`** — `pull_request` trigger (unprivileged):

- Triggered automatically on every PR push, including from forks.
- The `pull_request` event has **no access to repository secrets** for fork PRs. This
  is a GitHub platform guarantee — even if the fork's build scripts run, they cannot
  read `SONAR_TOKEN`.
- Checks out the fork's actual head commit, builds, runs tests with JaCoCo coverage,
  then bundles source files + compiled classes + coverage reports into an artifact.
- No approval gate required — the platform enforces the secret isolation.

**`sonarqube-pr-analyze.yml`** — `workflow_run` trigger (privileged):

- Triggered when the build workflow completes successfully.
- Has access to `SONAR_TOKEN`.
- Downloads the artifact produced by the build job.
- Checks out the **base repository** (our trusted code) to get the Gradle wrapper and
  `build.gradle.kts`.
- Overlays the fork's source files and pre-compiled outputs from the artifact on top of
  the base checkout. This gives the sonar scanner the actual PR code to annotate issues
  against, while the Gradle scripts being executed are entirely ours.
- Runs `gradle sonar` skipping test/coverage tasks (already in artifact). Fork code is
  **read** by the sonar scanner; it is never **executed**.

### Why This Is Safe

The sonar scanner performs static analysis: it reads `.java`/`.kt`/`.ts` source files
and compiled `.class` bytecode but does not execute them. Even if a fork contains
malicious Java source code, the scanner cannot use that to exfiltrate a secret —
it would require a vulnerability in the sonar scanner itself, not just malicious user
code.

The Gradle scripts driving the analysis (`build.gradle.kts`, `gradlew`) are always from
the base repository. Any custom annotation processors or code-gen plugins configured in
those scripts are our own and pre-vetted.

---

## ClusterFuzzLite: Accepted Risk with Maximum Mitigation

### Why the `workflow_run` Pattern Does Not Apply

Fuzzing by definition requires **executing** the code under test. ClusterFuzzLite must
compile the fork's fuzz targets and run them to discover bugs. There is no artifact
passing pattern that can separate "run this code" from "run this code in a privileged
context" — the run itself *is* the privileged operation.

The `pull_request_target` + fork code execution is therefore an inherent requirement of
fuzzing external contributions, not a design choice that can be avoided.

### Mitigations in Place

**`environment: ci` gate** — The `PR` job requires maintainer approval before it runs.
No CI secret is accessed until a human explicitly approves.

**`warn-fork-fuzz` job** — A companion job runs automatically (no gate, no secrets) and
posts a security reminder on the PR *before* the gate can be triggered. The comment
names the specific secrets at risk and asks reviewers to verify the code before
approving the gate.

**Why the environment gate is a *stronger* safeguard than a regular PR review:**

A regular PR approval and an environment gate approval are different acts. The
environment gate is a distinct, less common action that specifically prompts the
reviewer to ask: "why does this require separate approval?" The warn-fork-fuzz comment
answers that question with an explicit security checklist. A maintainer who approves the
gate without reading that comment is no less careful than one who merges a PR without
review — in both cases the failure is a process failure, not a workflow design failure.

**Minimized token scope** — `CFLITE_STORAGE_REPO_TOKEN` is a fine-grained PAT scoped
exclusively to `catatafishen/agentbridge-cflite-storage`:

- Read access to metadata
- Read and Write access to code

Even if a fork PR successfully exfiltrates this token, the blast radius is limited to
the fuzz corpus storage repository. The main codebase, secrets, and release pipeline
are unaffected.

### OpenSSF Scorecard Finding

OpenSSF Scorecard's `Dangerous-Workflow` check performs static analysis of the workflow
YAML. It flags the `pull_request_target` + checkout-of-fork-code pattern regardless of
environment protection settings, because the static analyzer cannot verify that the
protection exists or is correctly configured.

This finding accurately describes a real risk that we have mitigated as thoroughly as
the fuzzing use-case allows. It will continue to appear in the scorecard report as an
accepted risk. The rationale documented here and in `cflite_pr.yml` serves as the
reference for future re-evaluation.

The SonarQube finding for `sonarqube.yml` / `sonarqube-pr-analyze.yml` should no longer
appear after the migration to `workflow_run`, since those workflows no longer execute
fork code.
