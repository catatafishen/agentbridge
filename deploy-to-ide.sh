#!/usr/bin/env bash
#
# Deploy plugin to all local IntelliJ IDEA instances.
#
# Usage:
#   ./deploy-to-ide.sh          # build + deploy
#   ./deploy-to-ide.sh --skip-build   # deploy only (assumes ZIP is fresh)
#
set -euo pipefail

DIST_DIR="plugin-core/build/distributions"

# Detect the top-level directory name inside the ZIP (e.g. "ide-agent-for-copilot")
detect_zip_root_dir() {
    local zip="$1"
    unzip -l "$zip" | awk 'NR>3 && $NF ~ /\/$/ { split($NF,a,"/"); print a[1]; exit }'
}

# Deploy the ZIP to every IntelliJIdea* version dir under ~/.local/share/JetBrains.
# Each versioned dir holds per-version user data (plugins, config, etc.).
deploy_to_all_intellij_dirs() {
    local zip="$1"
    local plugin_dir_name="$2"
    local base="$HOME/.local/share/JetBrains"
    local count=0

    while IFS= read -r ide_dir; do
        [[ -d "$ide_dir" ]] || continue
        local install_dir="$ide_dir/$plugin_dir_name"
        echo "  📂 → $(basename "$ide_dir")"
        rm -rf "$install_dir"
        unzip -q "$zip" -d "$ide_dir"
        if [[ -d "$install_dir" ]]; then
            count=$((count + 1))
        else
            echo "  ❌ Extraction failed for $ide_dir"
        fi
    done < <(ls -dt "$base"/IntelliJIdea* 2>/dev/null)

    if [[ $count -eq 0 ]]; then
        echo "❌ No IntelliJIdea* directories found under $base"
        exit 1
    fi
    echo "✅ Plugin deployed to $count IntelliJ instance(s)"
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
echo "📦 ZIP: $(basename "$LATEST_ZIP")"

# Step 3: Detect plugin directory name from ZIP contents
PLUGIN_DIR_NAME=$(detect_zip_root_dir "$LATEST_ZIP")
if [[ -z "$PLUGIN_DIR_NAME" ]]; then
    echo "❌ Could not detect plugin directory name in ZIP"
    exit 1
fi

# Step 4: Deploy to all IntelliJ version instances
deploy_to_all_intellij_dirs "$LATEST_ZIP" "$PLUGIN_DIR_NAME"
echo "⚠️  Restart IntelliJ to apply the new version."
