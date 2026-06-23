#!/usr/bin/env bash
#
# Build the plugin once, deploy the freshly built ZIP to every versioned
# IDE data directory found on this machine, then restart each IDE.
#
# Scans ~/.local/share/JetBrains/IntelliJIdea* and CLion* for versioned user
# data dirs. Rider is deployed to its fixed Toolbox apps plugins path.
# The main IntelliJ (the host IDE) is restarted last via a detached relauncher
# that survives the IDE being killed.
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

DIST_DIR="plugin-core/build/distributions"
BASE="$HOME/.local/share/JetBrains"

CLION_BINARY="$HOME/.local/share/JetBrains/Toolbox/apps/clion/bin/clion"
CLION_PROJECT="$HOME/CLionProjects/doxygen"

RIDER_BINARY="$HOME/.local/share/JetBrains/Toolbox/scripts/rider"
RIDER_PLUGIN_PARENT="$HOME/.local/share/JetBrains/Toolbox/apps/rider/plugins"
RIDER_PROJECT="$PROJECT_DIR/fixtures/dotnet"

SKIP_BUILD=false
SKIP_RESTART=false
DIAGNOSE_RESTART=false
for arg in "$@"; do
    case "$arg" in
        --skip-build)       SKIP_BUILD=true ;;
        --skip-restart)     SKIP_RESTART=true ;;
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
    MAIN_IDE_PID="$(ps -eo pid,args 2>/dev/null | awk -v bin="$MAIN_IDE_HOME/bin/idea" \
        '$2 == bin { print $1; exit }')"
fi

# ── Functions ─────────────────────────────────────────────────────────────────

# Deploy LATEST_ZIP into a given parent dir (removes old version first).
deploy_to_dir() {
    local parent="$1"
    local install_dir="$parent/$PLUGIN_DIR_NAME"
    rm -rf "$install_dir"
    unzip -q "$LATEST_ZIP" -d "$parent"
    if [[ -d "$install_dir" ]]; then
        return 0
    else
        echo "    ❌ extraction failed for $parent"
        return 1
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

# ── Diagnose mode ────────────────────────────────────────────────────────────
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

# ── Locate ZIP and detect plugin directory name ───────────────────────────────
LATEST_ZIP=$(ls -t "$DIST_DIR"/*.zip 2>/dev/null | head -1)
if [[ -z "$LATEST_ZIP" ]]; then
    echo "❌ No ZIP found in $DIST_DIR"
    exit 1
fi
echo "📦 ZIP: $(basename "$LATEST_ZIP")"

PLUGIN_DIR_NAME=$(unzip -l "$LATEST_ZIP" | awk 'NR>3 && $NF ~ /\/$/ { split($NF,a,"/"); print a[1]; exit }')
if [[ -z "$PLUGIN_DIR_NAME" ]]; then
    echo "❌ Could not detect plugin directory name from ZIP"
    exit 1
fi

# ── Deploy to every versioned IDE data dir ────────────────────────────────────
# Each IntelliJIdea* and CLion* dir under ~/.local/share/JetBrains is a per-version
# user data directory that holds its own plugins/ folder.
echo ""
echo "════════════════════════════════════════════════════════════"
echo "▶ Deploying to all versioned IDE dirs"
echo "════════════════════════════════════════════════════════════"

FAILURES=()
deploy_count=0

while IFS= read -r ide_dir; do
    [[ -d "$ide_dir" ]] || continue
    echo "  → $(basename "$ide_dir")"
    if deploy_to_dir "$ide_dir"; then
        deploy_count=$((deploy_count + 1))
    else
        FAILURES+=("$(basename "$ide_dir")")
    fi
done < <(ls -dt "$BASE"/IntelliJIdea* "$BASE"/CLion* 2>/dev/null)

# Rider stores plugins in the Toolbox apps dir, not a versioned user data dir.
if [[ -d "$RIDER_PLUGIN_PARENT" ]]; then
    echo "  → rider (Toolbox apps)"
    if deploy_to_dir "$RIDER_PLUGIN_PARENT"; then
        deploy_count=$((deploy_count + 1))
    else
        FAILURES+=("rider (Toolbox apps)")
    fi
fi

if [[ $deploy_count -eq 0 ]]; then
    echo "❌ No IDE directories found — nothing deployed"
    exit 1
fi
echo "✅ Deployed to $deploy_count location(s)"

# ── Restart IDEs ─────────────────────────────────────────────────────────────
if [[ "$SKIP_RESTART" == "false" ]]; then

    if [[ -x "$CLION_BINARY" ]]; then
        echo ""
        echo "🛑 Restarting CLion..."
        pkill -f "JetBrains/Toolbox/apps/clion" 2>/dev/null || true
        sleep 2
        pkill -9 -f "JetBrains/Toolbox/apps/clion" 2>/dev/null || true
        sleep 1
        echo "🚀 Starting CLion..."
        nohup "$CLION_BINARY" "$CLION_PROJECT" > /tmp/clion-deploy-restart.log 2>&1 &
        echo "✅ CLion restarting (log: /tmp/clion-deploy-restart.log)"
    fi

    if [[ -x "$RIDER_BINARY" ]]; then
        echo ""
        echo "🛑 Restarting Rider..."
        pkill -f "JetBrains/Toolbox/apps/rider" 2>/dev/null || true
        sleep 2
        pkill -9 -f "JetBrains/Toolbox/apps/rider" 2>/dev/null || true
        sleep 1
        echo "🚀 Starting Rider..."
        nohup "$RIDER_BINARY" "$RIDER_PROJECT" > /tmp/rider-deploy-restart.log 2>&1 &
        echo "✅ Rider restarting (log: /tmp/rider-deploy-restart.log)"
    fi

    restart_main_ide
fi

echo ""
if [[ ${#FAILURES[@]} -eq 0 ]]; then
    echo "🏁 All IDE deployments succeeded."
else
    echo "🏁 Done with failures: ${FAILURES[*]}"
    exit 1
fi
