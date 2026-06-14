# 🧪 RUNNING INSIDE A BWRAP SANDBOX

Your agent process has been launched inside a `bubblewrap` (bwrap) sandbox.
This is **not** an unconstrained shell — the filesystem you see is a curated
view, not the host. Read this section before you reach for a shell tool.

## What this means in practice

- **Most of the filesystem is hidden or read-only.** Only a small set of
  bind-mounted directories from the host is visible (typically the project
  directory and a few config paths). Everything else is missing, empty, or
  read-only. Paths that work fine outside the sandbox — `~`, `/etc`, system
  binaries, sibling projects — may not exist here.
- **Native shell tools may fail in surprising ways.** A `bash` / `cat` /
  `find` / `git` call that should "just work" can return empty output, exit
  non-zero, or report files as missing because the path is not bind-mounted
  into the sandbox. The tool isn't broken; it literally cannot see the file.
- **The IDE host can still see everything.** The AgentBridge MCP tools
  run server-side in the IDE process, which is **not** sandboxed. They have
  full read/write access to the project, can spawn unsandboxed commands via
  `run_command`, and can talk to the IDE's PSI / VCS / build
  systems. Prefer them for anything filesystem-related.

## What to do

1. **Use the AgentBridge MCP tools by default** for reads, writes, searches,
   git operations, and shell commands. They bypass the sandbox.
2. **If a native CLI tool fails unexpectedly**, do not retry blindly — the
   path you are touching is probably not bind-mounted. Switch to the MCP
   equivalent immediately.
3. **Do not try to "fix" the sandbox** by chmod-ing paths, creating
   symlinks, or copying files into the project — the sandbox view is
   computed at launch and you cannot escape it from inside.
4. **Report tool surprises early.** If even MCP tools start failing or
   returning empty data on paths that should exist, that is a real bug worth
   filing — see the "Unexpected Tool Behaviour" section in the project's
   `AGENTS.md`.
