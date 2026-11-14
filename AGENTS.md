# AgentBridge: Repository Instructions

Rules for anyone (human or agent) changing this repository. Product-facing agent instructions that ship to end users
live in `plugin-core/src/main/resources/default-startup-instructions.md`, not here.

**Not every agent reads this file by itself.** CLI agents load it on their own. The built-in Koog agent builds its
prompt
from bundled resources, so the plugin reads `AGENTS.md` from the project root and adds it to Koog's system prompt
(`ProjectInstructions`), dropping any plugin-generated block first. Other agents may not see it; put anything that must
reach every agent in the bundled instructions instead.

## Instruction Scope

Keep instructions at the narrowest scope where their assumptions hold.

- **Bundled plugin instructions are product-facing.** They run in end-user projects, so they must be repository-,
  machine- and environment-agnostic. Do not assume `gh`, repository scripts, development hooks, bot identities, or this
  repository's workflow.
- **Contributor-only material belongs here** (or in `.agents/skills/` or `docs/`): local scripts, CI/PR procedure,
  repository authentication.
- Before changing a bundled instruction, read it as a user who installed the plugin into an unrelated project on a clean
  machine. If it depends on this repository, keep it local.
- `AGENTS.md` can be overwritten in place: the Codex profile prepends the plugin's generated block into it. Do not
  commit that block.

## Design Principles

See [docs/DESIGN-PRINCIPLES.md](docs/DESIGN-PRINCIPLES.md).

1. **Internally, prefer JetBrains APIs over custom code.** Use `SystemInfo`, `git4idea`, the project model, SDK
   resolution and `ApplicationManager.invokeLater` instead of reimplementing them.
2. **MCP tools are a bridge, not an inventor.** Every tool wraps an IntelliJ action. If JetBrains does not provide the
   feature, decide whether it belongs here at all. If it is unavailable in the user's IDE, disable the tool gracefully.

## Searching This Repository

Use `search_text`, `search_symbols`, `find_references` and `read_file` for implementation traces. Do not use the host's
`search_code_subagent` for inventories or exact symbol lookup: its summaries can omit paths. If you delegate, use an
IntelliJ-specific explore agent and require it to use these tools.

## Workflow

- One logical change per branch, named `feat/<short>`, `fix/<short>`, `docs/<short>`, etc. Never commit to `master`.
- Create the branch from fresh remote state: `git_fetch`, then `git_branch` with `base: "origin/master"`. A bare
  `master` is the local branch and may be behind; `git_branch` never fetches.
- Use [conventional commits](https://www.conventionalcommits.org/): `feat`, `fix`, `refactor`, `docs`, `chore`, `test`.
  Commit messages explain why, for `git blame`. Group related changes in one commit; unrelated changes get their own.
- Rebase onto master; no merge commits.
- **Do not fan out branches.** While an earlier PR from this session is unmerged, add follow-up commits to its branch
  instead of opening a parallel one, unless the human asks otherwise. Never open a second branch on a file another open
  PR of yours changes. When you extend a PR, update its title and description.

### Before you push or open a PR

1. `build_project` succeeds. Do not end a turn on a red build.
2. `run_tests` passes.
3. **No new Sonar issues** (below).

### Sonar

Sonar issues are not allowed. Trust any Sonar report that reaches you (from `write_file`, `edit_text`, `get_highlights`
or `get_problems`) and fix it before committing. The IDE's own hints are not a substitute: they miss rules Sonar
enforces, such as `Thread.sleep` in tests and unused `throws`. Do not silence a finding with `// NOSONAR` or
`@SuppressWarnings`; suppress only when the rule is demonstrably wrong for that code, with a comment saying why.

### CI

CI takes 3-5 minutes. Do not wait on it; If it fails, use `gh run view <run-id> --log-failed`.

### PR review comments

For each thread: fix it or decide not to (with a reason), **reply** saying what you did, then **resolve** it. Never
resolve without a reply and never leave a thread pending.

```
gh api repos/catatafishen/agentbridge/pulls/N/comments/ID/replies -f body="Fixed: ..."
gh api graphql -f query='mutation { resolveReviewThread(input: {threadId: "PRRT_..."}) { thread { isResolved } } }'
```

Get thread ids from
`repository(...) { pullRequest(number: N) { reviewThreads(first: 50) { nodes { id isResolved } } } }`.

## Identity

Hooks apply the agent's non-personal identity to commits (`enforce-commit-author.js`, which overwrites `author`) and the
bot identity to `gh` calls (`enforce-agentbridge-gh-bot-identity.js`). If the bot token is unavailable the call is
blocked rather than falling back to the owner. Do not work around the hooks or amend commits to substitute a human
email. You need not mention identity in commit or PR text.

## Working Principles

- **Git blame before reversing explicit settings.** Before removing a `.gitignore` rule, a disabled flag, a
  commented-out block or a deleted file, find the commit that added it and read its message. Explicit settings are
  rarely accidental.
- **Fix the root cause, not the symptom.** Never add a silent fallback that hides a missing or wrong value; surface it
  with an exception, a log line or `<unknown>`. If you must fix only the symptom, comment what the symptom is, why the
  cause cannot be fixed now, and what the cause is.
- **Clean as you code.** When you edit a file, fix every issue in it (unused imports, redundant casts, dead code), not
  just the ones you caused. If something confused you and you are not touching that file, log it in
  `.agent-work/clarity-backlog.md`.
- **No monolithic files.** One file, one responsibility. If a file needs `// === Section ===` banners to stay
  navigable, split it into files in a package. About 300 lines is the point to ask whether it should be decomposed.

## Unexpected Tool Behaviour

**File an issue the first time a tool surprises you; do not silently work around it.** That covers an error that
contradicts the tool's description, empty or `<unknown>` data where data is expected, a response that does not match
its schema, a retry with different arguments to make it work, or a fallback to a less appropriate tool.

1. File an issue on `catatafishen/agentbridge` through the bot (`run_command "gh issue create ..."`) with the label
   `triage: unconfirmed-tool-problem`. Title: `<tool_name>: <one-line surprise>`. Body: the exact call, what you
   expected (cite the description or schema), what happened, any workaround, and reproduction steps.
2. Read the tool's source under `plugin-core/src/main/java/.../psi/tools/`; if the description, validation or response
   can be fixed in the same PR, fix it.
3. Only then apply a workaround and continue. The workaround does not replace the issue.

Do not skip this because the workaround is easy. Whether it is a real bug is for triage to decide.

## IntelliJ Platform Rules

- Wrap PSI reads in `ReadAction` and writes in `WriteAction`; never nest them; use `invokeLater` for UI. Do heavy work
  (network, file I/O, computation) on background threads, never the UI thread.
- Avoid `getText()` / `getTextRange()` on large PSI; use `textMatches()` or `textLength`. Cache PSI-derived data in
  `UserData` or an index.
- **Never** use deprecated, `@ApiStatus.Experimental` or `@ApiStatus.Internal` APIs; replace a deprecated call when you
  meet it. The Marketplace flags them. Calling them by reflection does not help: the Marketplace verifier catches that
  too (we tried). If an internal API has no public replacement, drop or degrade the feature.
- The replacement for a deprecated API may not exist in the oldest supported IDE (`sinceBuild` in
  `plugin-core/build.gradle.kts`). Check before switching: `ReadAction.computeBlocking` replaces the deprecated
  `ReadAction.compute` but is missing from 2025.3, and release 1.229.0 was rejected for it. Use a `PlatformApiCompat`
  wrapper such as `readActionCompute` instead.
- API calls that differ across supported IDE versions go through `PlatformApiCompat`, with a Javadoc
  `<b>Why extracted:</b>` block. Check it for an existing wrapper before calling a JetBrains API directly, and never
  bypass it from business code.
- Keep defensive null checks even when annotated `@NotNull`.
- Code is Java 21; use Kotlin only where the IntelliJ API needs it.

## Code Quality

- Priority: compilation errors, then warnings, then `[INFORMATION]` hints (apply when they genuinely improve
  readability, not mechanically), then style. Grammar only if asked.
- Fix the cause; do not suppress. The only acceptable suppressions are a framework-required signature, a defensive null
  check the IDE calls "always true", and an overload the IDE wrongly calls redundant. Each needs a comment explaining
  why.
- Common fixes: extract duplicate string literals to constants; reduce cognitive complexity by extracting methods; log
  or document empty `catch` blocks; call `Thread.currentThread().interrupt()` after catching `InterruptedException`;
  remove unused parameters and methods; replace deprecated JetBrains APIs.
- **Do not edit generated output.** `plugin-core/build/generated/resources/chat-ui/...` is built from
  `plugin-core/chat-ui/src/` by `npm run build`, and `plugin-core/out/production/` is IntelliJ build output. Edit the
  source.

## MCP Tool Development

For tools in `psi/tools/`:

- **Descriptions** brief a new team member: what it does, when to use it versus similar tools, what it returns, key
  parameters and defaults, and caveats. "Delete a file" is bad; "Delete a file from the project. This is permanent and
  cannot be undone with the undo tool." is good.
- **Errors** start with `"Error: "` or `"Error (exit N): "` so the protocol handler sets `isError`, and say what to do
  next: `"Error: Nothing staged for commit. Use git_stage first, or pass all: true."`
- **Enrich responses** so the agent needs no follow-up call: git tools append branch context (`getBranchContext()`),
  write tools append git status, preconditions are checked up front, and risky actions (committing to `main`, push
  divergence) are flagged.
- **Remote refs** (`origin/*`, `remotes/*`) auto-fetch via `autoFetchForRemoteRef(ref)` (throttled to once per 60
  seconds), and the response says when a fetch happened.
- **Annotations**: set `isReadOnly()`, `isDestructive()`, `isIdempotent()` (defaults to `isReadOnly()`; override to
  `true` for idempotent writers such as `write_file`) and `openWorldHint` correctly.
- **Token efficiency**: paginate, filter and truncate. Prefer a summary with details behind a parameter.
  `truncateIfNeeded()` in `McpProtocolHandler` is a safety net, not a plan.

## UI / Logic Separation

UI classes (Swing panels, `Configurable` implementations) must not hold testable business logic. A method with no
dependency on Swing or IntelliJ UI APIs belongs in a standalone class that can be unit-tested alone.

- Extract pure logic (serialization, string and date formatting, parsing, error classification, command construction)
  into a class named by responsibility: `XxxFormatter`, `XxxCalculator`, `XxxParser`, `XxxBuilder`. Prefer static
  methods; pass data, not UI components. The UI method becomes a thin wrapper.
- Do not create god utility classes, do not extract trivially, and do not add section banners inside a UI class.
- **Tests follow extractions.** Any time you extract a class, add a `Callbacks`/`Host` interface, move decision logic
  into a companion or `object`, or add a service with testable logic, add unit tests: cover thresholds, empty input and
  precedence, and name tests by behaviour (`"pending ask-user takes precedence over is sending"`). If the extracted code
  has no testable logic, say why in the PR description.
- Good references: `MessageFormatter.kt`, `MarkdownRenderer.kt`, `ConversationSerializer.kt`, `ChatDataModel.kt`.

## Reference

- Agent definition formats and the per-agent support
  table: [docs/AGENT-DEFINITION-FORMATS.md](docs/AGENT-DEFINITION-FORMATS.md)
- Adding an agent: [docs/ADDING-A-NEW-AGENT.md](docs/ADDING-A-NEW-AGENT.md)
- Architecture: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) and [docs/MCP-ARCHITECTURE.md](docs/MCP-ARCHITECTURE.md)
- Contributing: [CONTRIBUTING.md](CONTRIBUTING.md)
