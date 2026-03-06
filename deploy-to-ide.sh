#!/usr/bin/env bash
#
# Deploy plugin to the main IDE and attempt dynamic reload.
#
# Usage:
#   ./deploy-to-ide.sh          # build + deploy + reload
#   ./deploy-to-ide.sh --skip-build   # deploy only (assumes ZIP is fresh)
#
set -euo pipefail

DIST_DIR="plugin-core/build/distributions"
BRIDGE_FILE="$HOME/.copilot/psi-bridge.json"

# Detect the top-level directory name inside the ZIP (e.g. "ide-agent-for-copilot")
detect_zip_root_dir() {
    local zip="$1"
    unzip -l "$zip" | awk 'NR>3 && $NF ~ /\/$/ { split($NF,a,"/"); print a[1]; exit }'
}

# Auto-detect IntelliJ install dir for the plugin
detect_install_dir() {
    local plugin_dir_name="$1"
    local base="$HOME/.local/share/JetBrains"

    # 1. Toolbox per-IDE plugin dir: ~/.local/share/JetBrains/IntelliJIdea*/<plugin>
    local ide_dir
    ide_dir=$(ls -dt "$base"/IntelliJIdea* 2>/dev/null | head -1)
    if [[ -n "$ide_dir" && -d "$ide_dir/$plugin_dir_name" ]]; then
        echo "$ide_dir/$plugin_dir_name"
        return
    fi

    # 2. Toolbox app-level: ~/.local/share/JetBrains/Toolbox/apps/.../plugins/<plugin>
    local dir
    dir=$(find "$base/Toolbox/apps" -maxdepth 3 -name "plugins" -type d 2>/dev/null | while read -r d; do
        [[ -d "$d/$plugin_dir_name" ]] && echo "$d/$plugin_dir_name" && break
    done)
    if [[ -n "$dir" ]]; then
        echo "$dir"
        return
    fi

    # 3. Fallback: newest IntelliJIdea dir (create target)
    if [[ -n "$ide_dir" ]]; then
        echo "$ide_dir/$plugin_dir_name"
    fi
}

# Step 1: Build (unless --skip-build)
if [[ "${1:-}" != "--skip-build" ]]; then
    echo "🔨 Building plugin ZIP..."
    ./gradlew :plugin-core:buildPlugin -x buildSearchableOptions --quiet
fi

# Step 2: Find latest ZIP
LATEST_ZIP=$(ls -t "$DIST_DIR"/*.zip 2>/dev/null | head -1)
if [[ -z "$LATEST_ZIP" ]]; then
    echo "❌ No ZIP found in $DIST_DIR"
    exit 1
fi
LATEST_ZIP_ABS=$(realpath "$LATEST_ZIP")
echo "📦 ZIP: $(basename "$LATEST_ZIP")"

# Step 3: Detect plugin directory name from ZIP contents
PLUGIN_DIR_NAME=$(detect_zip_root_dir "$LATEST_ZIP")
if [[ -z "$PLUGIN_DIR_NAME" ]]; then
    echo "❌ Could not detect plugin directory name in ZIP"
    exit 1
fi

# Step 4: Deploy files to plugin install directory
INSTALL_DIR=$(detect_install_dir "$PLUGIN_DIR_NAME")
if [[ -z "$INSTALL_DIR" ]]; then
    echo "❌ Could not find plugin install directory"
    exit 1
fi
echo "📂 Target: $INSTALL_DIR"

echo "🗑  Removing old version..."
rm -rf "$INSTALL_DIR"

echo "📂 Extracting..."
unzip -q "$LATEST_ZIP" -d "$(dirname "$INSTALL_DIR")"

if [[ ! -d "$INSTALL_DIR" ]]; then
    echo "❌ Extraction failed"
    exit 1
fi
echo "✅ Files deployed"

# Step 5: Try dynamic reload via PSI bridge (best-effort)
if [[ -f "$BRIDGE_FILE" ]]; then
    PORT=$(python3 -c "
import json, sys
try:
    reg = json.load(open('$BRIDGE_FILE'))
    for v in reg.values():
        print(v.get('port', '')); break
except: pass
" 2>/dev/null || true)

    if [[ -n "$PORT" ]]; then
        echo "🔄 Requesting dynamic reload on port $PORT..."
        HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
            -X POST "http://127.0.0.1:$PORT/reload-plugin" \
            -H "Content-Type: application/json" \
            -d "{\"zipPath\":\"$LATEST_ZIP_ABS\"}" \
            --connect-timeout 3 --max-time 5 2>/dev/null || echo "000")
        if [[ "$HTTP_CODE" == "200" ]]; then
            echo "🔄 Reload requested — if it fails, restart IDE to apply"
        else
            echo "ℹ️  Dynamic reload unavailable (HTTP $HTTP_CODE) — restart IDE to apply"
        fi
    else
        echo "ℹ️  No PSI bridge found — restart IDE to apply"
    fi
else
    echo "ℹ️  No running IDE detected — restart IDE to apply"
fi
