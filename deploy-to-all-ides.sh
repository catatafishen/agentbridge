#!/usr/bin/env bash
#
# Build the plugin once, then deploy the freshly built ZIP to every local IDE
# by delegating to the per-IDE scripts (deploy-to-ide.sh / -clion.sh / -rider.sh).
#
# The build runs a single time here; each per-IDE script is invoked with
# --skip-build so they reuse the same ZIP. CLion and Rider are separate
# processes that restart themselves. The main IntelliJ (the IDE this script is
# usually launched from) is deployed last and then restarted via a detached
# relauncher that survives the IDE being killed.
#
# Usage:
#   ./deploy-to-all-ides.sh                # build once + deploy + restart all IDEs
#   ./deploy-to-all-ides.sh --skip-build   # deploy existing ZIP to all IDEs
#   ./deploy-to-all-ides.sh --skip-restart # build + deploy, restart nothing
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"
PROJECT_DIR="$SCRIPT_DIR"

SKIP_BUILD=false
SKIP_RESTART=false
DIAGNOSE_RESTART=false
EXTRA_ARGS=()
for arg in "$@"; do
    case "$arg" in
        --skip-build)       SKIP_BUILD=true ;;
        --skip-restart)     SKIP_RESTART=true; EXTRA_ARGS+=("--skip-restart") ;;
        --diagnose-restart) DIAGNOSE_RESTART=true ;;
    esac
done

# Capture the running IntelliJ's home AND launcher PID BEFORE anything is killed.
# Modern IntelliJ uses a native launcher (bin/idea) that hosts the JVM in-process,
# so there is no separate `java ... com.intellij.idea.Main` process to match — the
# launcher process itself IS the IDE. idea.home.path is read from the headless JPS
# build child; the GUI process is the one whose argv[0] is $HOME/bin/idea.
MAIN_IDE_HOME="$(ps -eo args 2>/dev/null | awk '
    match($0, /-Didea\.home\.path=[^ ]+/) {
        s = substr($0, RSTART + 17, RLENGTH - 17)
        if (s !~ /sandbox/) { print s; exit }
    }')"
MAIN_IDE_PID=""
if [[ -n "$MAIN_IDE_HOME" ]]; then
    MAIN_IDE_PID="$(ps -eo pid,args 2>/dev/null | awk -v bin="$MAIN_IDE_HOME/bin/idea" '
        $2 == bin { print $1; exit }')"
fi

# Non-destructive probe: prove home/PID detection and detached-survival work in
# the caller's shell context WITHOUT killing IntelliJ. Run from the IDE terminal:
#   ./deploy-to-all-ides.sh --diagnose-restart
if [[ "$DIAGNOSE_RESTART" == "true" ]]; then
    log="/tmp/intellij-deploy-restart.log"
    {
        echo "===== DIAGNOSE $(date '+%F %T') ====="
        echo "MAIN_IDE_HOME='$MAIN_IDE_HOME'"
        echo "MAIN_IDE_PID='$MAIN_IDE_PID'"
        echo "launcher exists: $([[ -x "$MAIN_IDE_HOME/bin/idea" ]] && echo yes || echo NO)"
        echo "PID alive: $([[ -n "$MAIN_IDE_PID" ]] && kill -0 "$MAIN_IDE_PID" 2>/dev/null && echo yes || echo NO)"
        echo "PROJECT_DIR='$PROJECT_DIR'"
        echo "parent shell pid: $$  ppid: $PPID"
    } >"$log" 2>&1
    setsid bash -c "
        sleep 3
        echo \"[detached \$(date '+%T')] SURVIVED parent exit; my pid=\$\$ ppid=\$PPID\" >>'$log'
    " </dev/null >/dev/null 2>&1 &
    disown || true
    echo "🔎 Diagnose scheduled. Wait ~5s, then ask the agent to read $log"
    exit 0
fi

# ── Build once ───────────────────────────────────────────────────────────────
if [[ "$SKIP_BUILD" == "false" ]]; then
    echo "🔨 Building plugin ZIP (once)..."
    ./gradlew :plugin-core:buildPlugin -x buildSearchableOptions --quiet
    echo "✅ Build done"
fi

# ── Deploy to each IDE, reusing the ZIP ──────────────────────────────────────
FAILURES=()

run_deploy() {
    local name="$1"; shift
    local script="$1"; shift
    if [[ ! -x "$script" ]]; then
        echo "⚠️  Skipping $name — $script not found or not executable"
        return
    fi
    echo ""
    echo "════════════════════════════════════════════════════════════"
    echo "▶ Deploying to $name"
    echo "════════════════════════════════════════════════════════════"
    if "$script" --skip-build "${EXTRA_ARGS[@]}"; then
        echo "✅ $name done"
    else
        echo "❌ $name failed (continuing with the others)"
        FAILURES+=("$name")
    fi
}

# Restart the host IntelliJ from a detached process so it survives the IDE
# (this script's parent) being killed. Kills the running instance, waits for it
# to exit, then reopens this project with the same install. Every step is logged
# to /tmp/intellij-deploy-restart.log for diagnosis.
restart_main_ide() {
    local log="/tmp/intellij-deploy-restart.log"
    {
        echo "===== restart_main_ide $(date '+%F %T') ====="
        echo "MAIN_IDE_HOME='$MAIN_IDE_HOME'"
        echo "MAIN_IDE_PID='$MAIN_IDE_PID'"
        echo "PROJECT_DIR='$PROJECT_DIR'"
    } >"$log" 2>&1

    if [[ -z "$MAIN_IDE_HOME" || ! -x "$MAIN_IDE_HOME/bin/idea" ]]; then
        echo "⚠️  Could not locate the running IntelliJ launcher — restart it manually."
        echo "launcher missing or not executable: '$MAIN_IDE_HOME/bin/idea'" >>"$log"
        return
    fi
    if [[ -z "$MAIN_IDE_PID" ]] || ! kill -0 "$MAIN_IDE_PID" 2>/dev/null; then
        echo "⚠️  Could not find the running IntelliJ PID — restart it manually."
        echo "no live MAIN_IDE_PID ('$MAIN_IDE_PID')" >>"$log"
        return
    fi
    local launcher="$MAIN_IDE_HOME/bin/idea"
    echo "🔄 Scheduling IntelliJ restart (detached) — it will close and reopen this project..."
    echo "scheduling detached relaunch; will kill pid $MAIN_IDE_PID via $launcher" >>"$log"
    setsid bash -c "
        log='$log'; pid='$MAIN_IDE_PID'
        echo \"[detached \$(date '+%T')] start; target pid=\$pid\" >>\"\$log\"
        sleep 2
        echo \"[detached \$(date '+%T')] SIGTERM \$pid\" >>\"\$log\"
        kill -TERM \"\$pid\" 2>>\"\$log\" || true
        for _ in \$(seq 1 30); do
            kill -0 \"\$pid\" 2>/dev/null || break
            sleep 1
        done
        if kill -0 \"\$pid\" 2>/dev/null; then
            echo \"[detached \$(date '+%T')] still alive after 30s; SIGKILL \$pid\" >>\"\$log\"
            kill -9 \"\$pid\" 2>>\"\$log\" || true
            sleep 2
        fi
        echo \"[detached \$(date '+%T')] relaunching $launcher $PROJECT_DIR\" >>\"\$log\"
        '$launcher' '$PROJECT_DIR' >>\"\$log\" 2>&1 &
        echo \"[detached \$(date '+%T')] relaunch issued (pid \$!)\" >>\"\$log\"
    " </dev/null >/dev/null 2>&1 &
    disown || true
}

# Restart CLion and Rider first (separate processes) so their deploys finish
# before we kill the host IntelliJ.
run_deploy "CLion" "./deploy-to-clion.sh"
run_deploy "Rider" "./deploy-to-rider.sh"

# Main IntelliJ last: deploy without restart, then self-restart detached.
run_deploy "Main IntelliJ" "./deploy-to-ide.sh"

echo ""
if [[ ${#FAILURES[@]} -eq 0 ]]; then
    echo "🏁 All IDE deployments succeeded."
else
    echo "🏁 Done with failures: ${FAILURES[*]}"
fi

if [[ "$SKIP_RESTART" == "false" ]]; then
    restart_main_ide
fi

if [[ ${#FAILURES[@]} -ne 0 ]]; then
    exit 1
fi
