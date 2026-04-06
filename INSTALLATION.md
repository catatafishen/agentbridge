# Manual Plugin Installation Guide

## Prerequisites

- IntelliJ IDEA 2025.1 or later (any JetBrains IDE)
- Java 21 installed
- An ACP-compatible agent CLI (e.g., GitHub Copilot CLI, OpenCode, Junie, Kiro)

## Installation Steps

### 1. Locate the Plugin ZIP

The plugin has been built and is located at:

```
plugin-core/build/distributions/agentbridge-<version>.zip
```

**Size:** ~3 MB  
**Contents:** Plugin JAR, metadata, icon, mcp-server JAR

---

### 2. Install Plugin in IntelliJ IDEA

#### Option A: Via Settings Dialog

1. Open IntelliJ IDEA
2. Go to **File → Settings** (or **IntelliJ IDEA → Settings** on macOS)
3. Navigate to **Plugins**
4. Click the **⚙️ (gear icon)** → **Install Plugin from Disk...**
5. Browse to `plugin-core/build/distributions/`
6. Select the ZIP file
7. Click **OK**
8. Click **Restart IDE** when prompted

#### Option B: Via Drag & Drop

1. Open IntelliJ IDEA
2. Drag the ZIP file from File Explorer directly onto the IDE window
3. Confirm installation
4. Restart when prompted

---

### 3. Verify Installation

After restart:

1. Check if plugin is loaded:
    - **Settings → Plugins → Installed**
    - Look for **"AgentBridge"** in the list
    - Status should be **✓ Enabled**

2. Check for tool window:
    - Look for **"AgentBridge"** in the right sidebar
    - Or go to **View → Tool Windows → AgentBridge**

3. Check IDE logs for errors:
    - **Help → Show Log in Explorer**
    - Open `idea.log`
    - Search for "AgentBridge" or "AcpClient"
    - Look for any ERROR or WARN messages

---

### 4. Test the Plugin

#### 4.1 Open Tool Window

1. Click **AgentBridge** in the right sidebar
2. Tool window should open with:
    - **Chat console** (conversation area)
    - **Toolbar** (agent selector, model selector, settings)
    - **Prompt input** with file attachment support

#### 4.2 Select an Agent

1. Choose an agent profile from the dropdown (e.g., GitHub Copilot, OpenCode)
2. If using Copilot, ensure CLI is authenticated (`copilot auth`)
3. The status indicator shows connection state

#### 4.3 Send a Test Prompt

1. Type a simple prompt like "What files are in this project?"
2. Click Run or press Enter
3. The agent should respond using IntelliJ MCP tools

---

## Troubleshooting

### Plugin Not Appearing in Tool Windows

**Symptom:** No "AgentBridge" tool window visible

**Solutions:**

1. Check if plugin is enabled: **Settings → Plugins → AgentBridge** (should have checkmark)
2. Restart IDE: **File → Invalidate Caches and Restart → Just Restart**
3. Check logs for errors: `Help → Show Log in Explorer`

### Plugin Install Fails

**Symptom:** Error during installation: "Plugin is invalid"

**Solutions:**

1. **Rebuild the plugin:**
   ```bash
   ./gradlew :plugin-core:clean :plugin-core:buildPlugin
   ```

2. **Check ZIP is not corrupted:**
    - File size should be ~3 MB
    - Can extract with unzip/7-Zip to verify contents

3. **Check IDE version:**
    - Plugin requires IntelliJ 2025.1 or later
    - Check: **Help → About** → Build number should be 251.x or higher

### Agent Connection Fails

**Symptom:** "Error loading models" or connection timeout

**Solutions:**

1. Verify agent CLI is installed and authenticated
2. Check agent-specific setup (e.g., `copilot auth` for Copilot)
3. Review IDE logs for detailed error messages

---

## Uninstallation

To remove the plugin:

1. **Settings → Plugins**
2. Find **AgentBridge**
3. Click **⚙️ → Uninstall**
4. Restart IDE

---

## Development Mode

If you're actively developing and want to test changes:

### Quick Rebuild & Reinstall

```bash
# 1. Rebuild plugin
./gradlew :plugin-core:buildPlugin -x buildSearchableOptions

# 2. Remove old version (Linux)
rm -rf ~/.local/share/JetBrains/IntelliJIdea2025.3/plugin-core

# 3. Install new version
unzip -q plugin-core/build/distributions/agentbridge-*.zip \
    -d ~/.local/share/JetBrains/IntelliJIdea2025.3/

# 4. Restart IDE
```

### Sandbox Development

For faster iteration without affecting your main IDE:

```bash
./gradlew :plugin-core:runIde
```

This launches a sandboxed IntelliJ with the plugin pre-installed.

---

## Next Steps

Once installation is successful:

- [Quick Start Guide](QUICK-START.md) — Development workflow
- [Features](FEATURES.md) — All 92 MCP tools documented
- [Development Guide](DEVELOPMENT.md) — Architecture and debugging
