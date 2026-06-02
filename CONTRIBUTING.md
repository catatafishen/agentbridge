# Contributing to AgentBridge

Thank you for your interest in contributing! This document covers everything you need to
know — whether you're fixing a typo, implementing a new agent integration, or becoming a
regular collaborator.

---

## Table of Contents

- [Two ways to contribute](#two-ways-to-contribute)
- [Development setup](#development-setup)
- [Making changes](#making-changes)
- [Pull requests](#pull-requests)
- [PR reviews](#pr-reviews)
- [Becoming a collaborator](#becoming-a-collaborator)
- [Reporting issues](#reporting-issues)
- [Code style](#code-style)
- [License](#license)

---

## Two ways to contribute

### 1 — Fork and PR (standard open-source model)

Anyone can contribute without being added to the repo:

```
Fork → clone → branch → commit → open PR from your fork
```

1. Fork the repository on GitHub
2. Clone your fork: `git clone https://github.com/YOUR_USERNAME/agentbridge`
3. Add upstream: `git remote add upstream https://github.com/catatafishen/agentbridge`
4. Create a feature branch from `master`: `git checkout -b feature/your-feature`
5. Make changes, commit, push to your fork
6. Open a PR from your fork's branch to `catatafishen/agentbridge:master`

Keep your fork in sync with upstream before opening new PRs:

```bash
git fetch upstream
git rebase upstream/master
```

### 2 — Direct collaborator (push branches without forking)

If you're contributing regularly, you can be added as a collaborator.
Collaborators push branches directly to this repository (no fork needed) and open PRs
from those branches.

To request collaborator access, open a Discussion in the
[Collaborators category](https://github.com/catatafishen/agentbridge/discussions) and
briefly describe what you're working on or what you'd like to contribute. This is preferred
over a cold email or issue.

**Collaborator branch naming:** `feat/`, `fix/`, `chore/`, `docs/` prefixes.
Example: `feat/opencode-agent-support`

---

## Development setup

- **JDK 21** is required
- **IntelliJ IDEA 2025.1+** (Community or Ultimate)
- Run `./gradlew build` to verify your setup

> See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for a walkthrough of the project structure.

---

## Making changes

1. Create a branch from the latest `master` (rebase, not merge)
2. Keep commits focused; write clear commit messages
3. Run tests: `./gradlew test`
4. Ensure the plugin builds: `./gradlew :plugin-core:buildPlugin`
5. Push and open a PR — CI will run automatically

---

## Pull requests

- **Keep PRs focused** on a single logical change. Separate refactors from features.
- **Write a clear description** — what changed and *why*, not just what.
- **Reference issues**: `Closes #123` or `Related to #123`.
- **All CI checks must pass** before the PR is eligible for merge:
    - `Build, Test & Verify` — Gradle build + Java/Kotlin tests
    - `MCP Server Tests` — Node.js MCP server tests
- **Linear history required** — rebase onto `master` before merge (no merge commits).
- **Branch is deleted automatically** after merge.

---

## PR reviews

Every PR into `master` requires **at least one approving review from the maintainer**
(`@catatafishen` is auto-requested as reviewer on all PRs).

Reviews serve two purposes: catching bugs and keeping the codebase coherent.

### Automated review (GitHub Copilot)

GitHub Copilot code review is configured for this repo and automatically reviews every PR.
It posts inline comments within a few minutes of the PR being opened or updated. No setup
required from contributors.

### Human review

The maintainer reviews all external PRs. For larger changes that affect architecture or
public-facing behaviour, leave a comment or open a Discussion first — it saves everyone
time to align on the design before implementation.

### Review freshness

Approvals are **dismissed when new commits are pushed** — the maintainer will need to
re-approve after changes. This ensures the review always covers the final state of the
code.

### Resolving conversations

All review threads must be marked resolved before a PR can be merged.

---

## Becoming a collaborator

Collaborators have write access: they can push branches directly (not to `master` — that
is protected), manage labels, and close issues.

**How to request access:**

1. Open a [Discussion](https://github.com/catatafishen/agentbridge/discussions) in the
   **Collaborators** category (or General if that category doesn't exist yet)
2. Describe what you're building or what area you want to help with
3. Link any existing PRs or issues you've contributed to

There's no formal criteria — regular, quality contributions are the signal. One good PR is
worth more than a hundred stars.

**What collaborator access gives you:**

- Push branches directly to this repo (no fork required)
- Triage and label issues
- Close issues and PRs

**What it does not give you:**

- Merging to `master` (still requires CI + review)
- Changing repo settings or branch protection rules

---

## Reporting issues

- Use GitHub Issues for bugs and feature requests
- Include steps to reproduce for bug reports
- Include your IDE version (Help → About) and OS
- For questions and discussions, use [GitHub Discussions](https://github.com/catatafishen/agentbridge/discussions)

---

## Code style

- Follow existing conventions in each file you touch
- Java/Kotlin: formatted by IntelliJ defaults (the CI build catches formatting divergence)
- TypeScript/CSS: `npm run format` in `plugin-core/chat-ui/`
- Add tests for new functionality where practical
- **Do not create large files with comment-based section dividers** — split into focused
  files. See [`.github/copilot-instructions.md`](.github/copilot-instructions.md).

---

## Supporting the project

If you find AgentBridge useful and want to support its development, you can sponsor it at
[github.com/sponsors/catatafishen](https://github.com/sponsors/catatafishen). A large part
of development is done using AI coding agents, and sponsorships directly help cover those
costs.

---

## License

By contributing, you agree that your contributions will be licensed under the
[Apache License, Version 2.0](LICENSE).
