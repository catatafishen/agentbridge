#!/usr/bin/env bash
# Success hook for write_file: appends a stale-naming reminder when 100+ lines are
# written to an existing file. New file creation ("Created: ...") is excluded.
#
# Receives JSON payload on stdin: { toolName, arguments: {content, path, ...}, output, error, ... }
# Returns JSON with "append" to add reminder text, or exits silently if not applicable.

set -euo pipefail

result=$(cat | python3 -c "
import sys, json

payload = json.load(sys.stdin)
output = payload.get('output') or ''
if not output.startswith('Written:'):
    sys.exit(0)

content = (payload.get('arguments') or {}).get('content', '')
lines = len(content.splitlines())
if lines < 100:
    sys.exit(0)

msg = (
    '\n\n\u26a0\ufe0f **Stale naming check**: this file now has %d lines. '
    'Verify that the file name, class names, function names, and comments '
    'still accurately reflect the current behavior \u2014 '
    'large rewrites often introduce stale terminology.' % lines
)
print(json.dumps({'append': msg}))
" 2>/dev/null) || exit 0

echo "$result"
