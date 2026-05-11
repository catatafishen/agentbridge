# Permission hook for run_in_terminal: blocks commands that cause IDE state desync.
# Only hard-blocks git and sed. Other suboptimal commands get soft warnings via
# run-in-terminal-reprimand.ps1 (SUCCESS hook).
#
# Trigger: PERMISSION
# Input:   JSON payload on stdin with toolName, arguments.command
# Output:  {"decision":"deny","reason":"..."} to block, or nothing to allow
. "$PSScriptRoot\_lib.ps1"
Hook-ReadPayload

$cmd = Hook-GetArg 'command'
if (-not $cmd) { exit 0 }
$lcmd = $cmd.ToLower()

# git commands
if ($lcmd -match '^git(\s|$)' -or $lcmd -match '[&;|]\s*git\s') {
    Hook-JsonDeny "git commands are not allowed via run_in_terminal (causes IntelliJ buffer desync). Use the dedicated git tools instead: git_status, git_diff, git_log, git_commit, etc."
    exit 0
}

# sed
if ($lcmd -match '^sed\s' -or $lcmd -match '\|\s*sed[\s;]') {
    Hook-JsonDeny "sed is not allowed via run_in_terminal (bypasses IntelliJ editor buffers). Use edit_text with old_str/new_str for file editing instead."
    exit 0
}
