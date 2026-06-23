#!/usr/bin/env bash
#
# Build, deploy, and restart CLion with the updated plugin.
# Then wait for the MCP server to come back up so you can call it to verify.
#
# Usage:
#   ./deploy-to-clion.sh                 # build + deploy + restart CLion
#   ./deploy-to-clion.sh --skip-build    # deploy from existing ZIP + restart CLion
#   ./deploy-to-clion.sh --skip-restart  # build + deploy only (restart CLion manually)
#
set -euo pipefail

CLION_BINARY="$HOME/.local/share/JetBrains/Toolbox/apps/clion/bin/clion"
CLION_PROJECT="$HOME/CLionProjects/doxygen"
DIST_DIR="plugin-core/build/distributions"
MCP_HEALTH="http://127.0.0.1:8643/health"

SKIP_BUILD=false
SKIP_RESTART=false

for arg in "$@"; do
    case "$arg" in
        --skip-build)   SKIP_BUILD=true ;;
        --skip-restart) SKIP_RESTART=true ;;
    esac
done

# ── Step 1: Build ────────────────────────────────────────────────────────────
if [[ "$SKIP_BUILD" == "false" ]]; then
    echo "🔨 Building plugin ZIP..."
    ./gradlew :plugin-core:buildPlugin -x buildSearchableOptions --quiet
    echo "✅ Build done"
fi

# ── Step 2: Find ZIP ─────────────────────────────────────────────────────────
LATEST_ZIP=$(ls -t "$DIST_DIR"/*.zip 2>/dev/null | head -1)
if [[ -z "$LATEST_ZIP" ]]; then
    echo "❌ No ZIP found in $DIST_DIR — run without --skip-build first"
    exit 1
fi
echo "📦 ZIP: $(basename "$LATEST_ZIP")"

# ── Step 3: Detect plugin directory name from ZIP ────────────────────────────
PLUGIN_DIR_NAME=$(unzip -l "$LATEST_ZIP" | awk 'NR>3 && $NF ~ /\/$/ { split($NF,a,"/"); print a[1]; exit }')
if [[ -z "$PLUGIN_DIR_NAME" ]]; then
    echo "❌ Could not detect plugin directory name in ZIP"
    exit 1
fi

# ── Step 4: Kill CLion ───────────────────────────────────────────────────────
if [[ "$SKIP_RESTART" == "false" ]]; then
    echo "🛑 Stopping CLion..."
    pkill -f "JetBrains/Toolbox/apps/clion" || true
    sleep 2
    # Hard-kill anything still holding the port
    pkill -9 -f "JetBrains/Toolbox/apps/clion" 2>/dev/null || true
    sleep 1
fi

# ── Step 5: Deploy to all CLion* version dirs ────────────────────────────────
BASE="$HOME/.local/share/JetBrains"
count=0
while IFS= read -r clion_dir; do
    [[ -d "$clion_dir" ]] || continue
    install_dir="$clion_dir/$PLUGIN_DIR_NAME"
    echo "  📂 → $(basename "$clion_dir")"
    rm -rf "$install_dir"
    unzip -q "$LATEST_ZIP" -d "$clion_dir"
    if [[ -d "$install_dir" ]]; then
        count=$((count + 1))
    else
        echo "  ❌ Extraction failed for $clion_dir"
    fi
done < <(ls -dt "$BASE"/CLion* 2>/dev/null)

if [[ $count -eq 0 ]]; then
    echo "❌ No CLion* directories found under $BASE"
    exit 1
fi
echo "✅ Plugin deployed to $count CLion instance(s)"

# ── Step 6: Restart CLion and wait for MCP ───────────────────────────────────
if [[ "$SKIP_RESTART" == "true" ]]; then
    echo "⚠️  Skipping restart — restart CLion manually to apply the new plugin."
    exit 0
fi

echo "🚀 Starting CLion with $CLION_PROJECT..."
nohup "$CLION_BINARY" "$CLION_PROJECT" > /tmp/clion-deploy-restart.log 2>&1 &

echo "⏳ Waiting for CLion MCP server on port 8643 (up to 3 minutes)..."
for i in $(seq 1 60); do
    HEALTH=$(curl -sf "$MCP_HEALTH" 2>/dev/null || true)
    if [[ -n "$HEALTH" ]]; then
        VERSION=$(echo "$HEALTH" | python3 -c "import sys,json; print(json.load(sys.stdin).get('version','?'))" 2>/dev/null || echo "?")
        echo "✅ CLion MCP server ready — plugin v$VERSION"
        echo ""
        echo "Test with:"
        echo "  curl -sf -X POST http://127.0.0.1:8643/mcp -H 'Content-Type: application/json' \\"
        echo "    -d '{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"get_file_outline\",\"arguments\":{\"path\":\"src/main.cpp\"}}}'"
        exit 0
    fi
    printf "  [%d/60] waiting (3s)...\r" "$i"
    sleep 3
done

echo ""
echo "❌ Timeout: CLion MCP server did not respond within 3 minutes."
echo "   Check: tail /tmp/clion-deploy-restart.log"
exit 1
