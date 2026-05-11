# Success hook for run_in_terminal: appends a soft nudge when the command has
# a better dedicated MCP tool equivalent. Does not block — the command runs
# normally, but the output is annotated to guide the agent toward the better tool.
#
# Trigger: SUCCESS
# Input:   JSON payload on stdin with toolName, arguments.command, output, error
# Output:  {"append":"..."} to add nudge text, or nothing if no nudge needed
. "$PSScriptRoot\_lib.ps1"
Hook-ReadPayload

# Skip error outputs
$isError = Hook-Get 'error'
if ($isError -eq $true -or $isError -eq 'true') { exit 0 }

$cmd = Hook-GetArg 'command'
if (-not $cmd) { exit 0 }
$lcmd = $cmd.ToLower()

# grep/rg/ag → search_text/search_symbols
if ($lcmd -match '^(grep|rg|ag)\s' -or $lcmd -match '\|\s*(grep|rg|ag)\s') {
    Hook-JsonAppend "`n`n⚠️ Prefer search_text or search_symbols over shell grep — they search live editor buffers and support semantic lookup."
    exit 0
}

# cat/head/tail → read_file
if ($lcmd -match '^(cat|head|tail|less|more)\s' -or $lcmd -match '\|\s*cat\s') {
    Hook-JsonAppend "`n`n⚠️ Prefer read_file over shell cat/head/tail — it reads live editor buffers, not stale disk content."
    exit 0
}

# find → list_project_files
if ($lcmd -match '^find\s') {
    Hook-JsonAppend "`n`n⚠️ Prefer list_project_files or list_directory_tree over shell find — they respect project structure and exclusions."
    exit 0
}

# ls/dir/tree → list_project_files
if ($lcmd -match '^(ls|dir|tree)(\s|$)') {
    Hook-JsonAppend "`n`n⚠️ Prefer list_project_files or list_directory_tree over shell ls/tree — they respect project structure and exclusions."
    exit 0
}

# test runners → run_tests
if ($lcmd -match '^(npm test|npm run test|yarn test|pnpm test|pytest|python -m pytest|jest|vitest|mocha|ava|jasmine)' -or
    $lcmd -match '^(\.\/gradlew test|gradle test|\.\/gradlew check|\.\/gradlew build|mvn test|mvn verify|mvn package|go test)') {
    Hook-JsonAppend "`n`n⚠️ Prefer run_tests over shell test commands — it provides structured pass/fail results with IntelliJ test runner integration."
    exit 0
}

# build/compile → build_project
if ($lcmd -match '(\.\/gradlew|gradle)\s+compile|mvn compile') {
    Hook-JsonAppend "`n`n⚠️ Prefer build_project over shell compile/build commands — it uses IntelliJ incremental compiler with structured error reporting."
    exit 0
}
