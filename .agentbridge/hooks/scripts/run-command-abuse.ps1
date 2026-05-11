# Permission hook for run_command: blocks shell commands that should use dedicated MCP tools.
# Prevents IntelliJ buffer desync and guides agents toward IDE-integrated equivalents.
#
# Trigger: PERMISSION
# Input:   JSON payload on stdin with toolName, arguments.command, projectName, timestamp
# Output:  {"decision":"deny","reason":"..."} to block, or nothing to allow
. "$PSScriptRoot\_lib.ps1"
Hook-ReadPayload

$cmd = Hook-GetArg 'command'
if (-not $cmd) { exit 0 }
$lcmd = $cmd.ToLower()

# git commands (must use dedicated git tools)
if ($lcmd -match '^git(\s|$)' -or $lcmd -match '[&;|]\s*git\s') {
    Hook-JsonDeny "git commands are not allowed via run_command (causes IntelliJ buffer desync). Use the dedicated git tools instead: git_status, git_diff, git_log, git_commit, git_stage, git_unstage, git_branch, git_stash, git_show, git_blame, git_push, git_remote, git_fetch, git_pull, git_merge, git_rebase, git_cherry_pick, git_tag, git_reset."
    exit 0
}

# cat/head/tail/less/more (must use read_file)
if ($lcmd -match '^(cat|head|tail|less|more)\s' -or $lcmd -match '\|\s*(cat|head|tail|less|more)\s') {
    Hook-JsonDeny "cat/head/tail/less/more are not allowed via run_command (reads stale disk files). Use read_file to read live editor buffers instead."
    exit 0
}

# sed (must use edit_text)
if ($lcmd -match '^sed\s' -or $lcmd -match '[|&;]\s*sed[\s;]') {
    Hook-JsonDeny "sed is not allowed via run_command (bypasses IntelliJ editor buffers). Use edit_text with old_str/new_str for file editing instead."
    exit 0
}

# find (must use list_project_files)
if ($lcmd -match '^find\s') {
    Hook-JsonDeny "find commands are not allowed via run_command. Use list_project_files or list_directory_tree to find files instead."
    exit 0
}

# Gradle compile-only tasks (must use build_project)
if ($lcmd -match 'gradlew.*(compilejava|compilekotlin|\bclasses\b|testclasses)') {
    if ($lcmd -notmatch 'test|check|\bbuild\b|assemble') {
        Hook-JsonDeny "Gradle compile tasks are not allowed via run_command. Use build_project to compile via IntelliJ incremental compiler instead."
        exit 0
    }
}
