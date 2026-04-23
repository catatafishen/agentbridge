#!/usr/bin/env bash
#
# AgentBridge command shim.
#
# Installed under multiple names (cat, head, grep, egrep, fgrep, rg, git, ...) in a
# directory that the plugin prepends to PATH for every ACP agent subprocess.
# When an agent's shell invokes one of those names, this script runs instead of
# the real binary.
#
# Behaviour:
#   1. POST the argv to the in-IDE shim controller (/shim-exec) on localhost.
#   2. If the controller returns HTTP 200, the body is "EXIT N\n<stdout-bytes>" —
#      print the stdout and exit with N.
#   3. Otherwise (no port set, missing token, network/timeout, controller says
#      passthrough), exec the real binary with PATH stripped of the shim dir so
#      we never recurse.
#
# Robustness rules:
#   - Use only bash builtins for our own parsing — any external tool we use might
#     itself be a shimmed name (e.g. `head`, `grep`).
#   - Take the absolute curl path from REAL_PATH so our curl call never recurses.
#   - Any error in the redirect path silently falls through to the real binary;
#     the agent must always get a working command.

set -u

real_name="${0##*/}"
shim_dir="${0%/*}"

# Compute PATH with the shim dir removed. Used both for finding the real binary
# and for invoking curl safely.
real_path=""
IFS=':' read -ra _path_parts <<< "${PATH:-}"
for _p in "${_path_parts[@]}"; do
    if [ -n "$_p" ] && [ "$_p" != "$shim_dir" ]; then
        if [ -z "$real_path" ]; then
            real_path="$_p"
        else
            real_path="$real_path:$_p"
        fi
    fi
done

exec_real() {
    # Find the real binary via real_path so we don't recurse into ourselves,
    # but exec it with the ORIGINAL PATH (still containing shim_dir) so
    # downstream commands and shebang lookups (e.g. /usr/bin/env node) can
    # still resolve through the shims. If lookup fails, fall back to
    # PATH-stripping to guarantee non-recursion.
    real_bin=""
    IFS=':' read -ra _real_parts <<< "$real_path"
    for _p in "${_real_parts[@]}"; do
        if [ -x "$_p/$real_name" ]; then
            real_bin="$_p/$real_name"
            break
        fi
    done
    if [ -n "$real_bin" ]; then
        exec "$real_bin" "$@"
    else
        PATH="$real_path" exec "$real_name" "$@"
    fi
}

port="${AGENTBRIDGE_SHIM_PORT:-}"
token="${AGENTBRIDGE_SHIM_TOKEN:-}"

if [ -z "$port" ] || [ -z "$token" ]; then
    exec_real "$@"
fi

# Locate curl via the real PATH so we don't accidentally invoke a shimmed curl.
curl_bin=""
IFS=':' read -ra _real_parts <<< "$real_path"
for _p in "${_real_parts[@]}"; do
    if [ -x "$_p/curl" ]; then
        curl_bin="$_p/curl"
        break
    fi
done

if [ -z "$curl_bin" ]; then
    exec_real "$@"
fi

# Build the curl argv. --data-urlencode handles arbitrary bytes safely.
# --max-time 600 covers visible-fallthrough commands (npm install, mvn build,
# docker build…) that the controller may execute server-side and stream back.
# MCP redirects always return in milliseconds.
curl_args=(
    -sS
    --max-time 600
    -o -
    -w '\nHTTP=%{http_code}'
    -H "X-Shim-Token: $token"
    --data-urlencode "argv=$real_name"
    --data-urlencode "cwd=$PWD"
)
for a in "$@"; do
    curl_args+=(--data-urlencode "argv=$a")
done

response=$("$curl_bin" "${curl_args[@]}" "http://127.0.0.1:$port/shim-exec" 2>/dev/null) || exec_real "$@"

# Response is "<body>\nHTTP=NNN" — split on the trailing marker.
http_marker="${response##*$'\n'HTTP=}"
http_code="$http_marker"
body="${response%$'\n'HTTP=*}"

if [ "$http_code" = "200" ]; then
    # Body format: first line "EXIT <code>", rest is stdout (binary-safe).
    first_line="${body%%$'\n'*}"
    rest="${body#*$'\n'}"
    case "$first_line" in
        "EXIT "*)
            code="${first_line#EXIT }"
            # Print everything after the first newline. If body had no newline
            # (only the EXIT line and no stdout), $rest equals $body — handle that.
            if [ "$rest" = "$body" ]; then
                exit "$code"
            fi
            printf '%s' "$rest"
            exit "$code"
            ;;
    esac
fi

# 204, 5xx, malformed body — fall through silently.
exec_real "$@"
