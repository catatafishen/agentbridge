#!/usr/bin/env bash
#
# Build, deploy, and restart Rider with the updated plugin.
# Then wait for the MCP server to come back up so you can call it to verify.
#
# Usage:
#   ./deploy-to-rider.sh                 # build + deploy + restart Rider
#   ./deploy-to-rider.sh --skip-build    # deploy from existing ZIP + restart Rider
#   ./deploy-to-rider.sh --skip-restart  # build + deploy only (restart Rider manually)
#
set -euo pipefail

RIDER_BINARY="$HOME/.local/share/JetBrains/Toolbox/scripts/rider"
RIDER_PLUGIN_PARENT="$HOME/.local/share/JetBrains/Toolbox/apps/rider/plugins"
RIDER_PROJECT="$PWD/fixtures/dotnet"
DIST_DIR="plugin-core/build/distributions"
MCP_HEALTH="http://127.0.0.1:8642/health"

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
INSTALL_DIR="$RIDER_PLUGIN_PARENT/$PLUGIN_DIR_NAME"

# ── Step 4: Kill Rider ───────────────────────────────────────────────────────
if [[ "$SKIP_RESTART" == "false" ]]; then
    echo "🛑 Stopping Rider..."
    pkill -f "JetBrains/Toolbox/apps/rider" || true
    sleep 2
    pkill -9 -f "JetBrains/Toolbox/apps/rider" 2>/dev/null || true
    sleep 1
fi

# ── Step 5: Deploy ───────────────────────────────────────────────────────────
echo "🗑  Removing old plugin: $INSTALL_DIR"
rm -rf "$INSTALL_DIR"
echo "📂 Extracting to $RIDER_PLUGIN_PARENT..."
unzip -q "$LATEST_ZIP" -d "$RIDER_PLUGIN_PARENT"
if [[ ! -d "$INSTALL_DIR" ]]; then
    echo "❌ Extraction failed — $INSTALL_DIR not found"
    exit 1
fi
echo "✅ Plugin deployed"

# ── Step 6: Restart Rider and wait for MCP ───────────────────────────────────
if [[ "$SKIP_RESTART" == "true" ]]; then
    echo "⚠️  Skipping restart — restart Rider manually to apply the new plugin."
    exit 0
fi

echo "🚀 Starting Rider with $RIDER_PROJECT..."
nohup "$RIDER_BINARY" "$RIDER_PROJECT" > /tmp/rider-deploy-restart.log 2>&1 &

echo "⏳ Waiting for Rider MCP server on port 8642 (up to 3 minutes)..."
for i in $(seq 1 60); do
    HEALTH=$(curl -sf "$MCP_HEALTH" 2>/dev/null || true)
    if [[ -n "$HEALTH" ]]; then
        VERSION=$(echo "$HEALTH" | python3 -c "import sys,json; print(json.load(sys.stdin).get('version','?'))" 2>/dev/null || echo "?")
        echo "✅ Rider MCP server ready — plugin v$VERSION"
        echo ""
        echo "Test with:"
        echo "  curl -sf -X POST http://127.0.0.1:8642/mcp -H 'Content-Type: application/json' \\"
        echo "    -d '{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"get_highlights\",\"arguments\":{\"path\":\"Widget.cs\"}}}'"
        exit 0
    fi
    printf "  [%d/60] waiting (3s)...\r" "$i"
    sleep 3
done

echo ""
echo "❌ Timeout: Rider MCP server did not respond within 3 minutes."
echo "   Check: tail /tmp/rider-deploy-restart.log"
exit 1
