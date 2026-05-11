# Success hook for write_file: appends a stale-naming reminder when 100+ lines are
# written to an existing file. New file creation ("Created: ...") is excluded.
#
# Trigger: SUCCESS
# Input:   JSON payload on stdin with arguments.content, output
# Output:  {"append":"..."} to add reminder, or nothing if not applicable
. "$PSScriptRoot\_lib.ps1"
Hook-ReadPayload

# Only trigger for writes to existing files (output starts with "Written:")
$output = Hook-Get 'output'
if (-not $output -or -not $output.StartsWith('Written:')) { exit 0 }

$content = Hook-GetArg 'content'
if (-not $content) { exit 0 }

$lines = ($content -split "`n").Count
if ($lines -lt 100) { exit 0 }

Hook-JsonAppend "`n`n⚠️ **Stale naming check**: this file now has $lines lines. Verify that the file name, class names, function names, and comments still accurately reflect the current behavior — large rewrites often introduce stale terminology."
