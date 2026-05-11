#!/bin/sh
# Success hook for write_file: appends a stale-naming reminder when 100+ lines are
# written to an existing file. New file creation ("Created: ...") is excluded.
#
# Trigger: SUCCESS
# Input:   JSON payload on stdin with arguments.content, output
# Output:  {"append":"..."} to add reminder, or nothing if not applicable
. "${0%/*}/_lib.sh"
hook_read_payload

# Only trigger for writes to existing files (output starts with "Written:")
output=$(hook_get output)
case "$output" in
    Written:*) ;;
    *) exit 0 ;;
esac

# Count lines in the content argument
content=$(hook_get_arg content)
if [ -z "$content" ]; then
    exit 0
fi

lines=$(printf '%s\n' "$content" | wc -l)
if [ "$lines" -lt 100 ]; then
    exit 0
fi

hook_json_append "\\n\\n⚠️ **Stale naming check**: this file now has $lines lines. Verify that the file name, class names, function names, and comments still accurately reflect the current behavior — large rewrites often introduce stale terminology."
