# Quick Start Guide

## Prerequisites

1. **JDK 21** — Install via your package manager or SDKMAN
2. **GitHub Copilot CLI** — Must be installed and authenticated (`copilot auth`)
3. **IntelliJ IDEA 2025.1+** — Ultimate or Community Edition (through 2025.3)

## Setup

### 1. Configure `gradle.properties`

The file contains two machine-specific paths that must match your environment:

```properties
# Path to your IntelliJ IDEA installation (for platform SDK)
intellijPlatform.localPath=/path/to/your/IntelliJ IDEA
# Path to JDK 21 (Gradle build JVM)
org.gradle.java.home=/path/to/jdk-21
```

**Linux example:**

```properties
intellijPlatform.localPath=/opt/idea-IU-253.29346.240
org.gradle.java.home=/usr/lib/jvm/java-21-openjdk
```

**Windows example:**

```properties
intellijPlatform.localPath=C:\\Users\\YOU\\AppData\\Local\\JetBrains\\IntelliJ IDEA 2023.3.3
org.gradle.java.home=C:\\Users\\YOU\\.jdks\\temurin-21.0.6
```

### 2. Build

```bash
./gradlew :plugin-core:buildPlugin
```

Output: `plugin-core/build/distributions/plugin-core-0.1.0-SNAPSHOT.zip`

### 3. Install

**Option A — Deploy to existing IDE:**

```bash
# Linux (Toolbox-managed: no /plugins parent)
PLUGIN_DIR=~/.local/share/JetBrains/IntelliJIdea2025.3
rm -rf "$PLUGIN_DIR/plugin-core"
unzip -q plugin-core/build/distributions/plugin-core-*.zip -d "$PLUGIN_DIR"
```

**Option B — Sandbox IDE (recommended for development):**

```bash
./gradlew :plugin-core:runIde
# Opens a sandboxed IntelliJ with plugin pre-installed (~90s first launch)
```

### 4. Verify

1. Open IntelliJ and a project
2. **View → Tool Windows → IDE Agent for Copilot**
3. Models dropdown should load available Copilot models
4. Type a prompt and click Run

## Development Workflow

```bash
# Make code changes, then:
./gradlew :plugin-core:buildPlugin    # Full rebuild (~30s)
./gradlew :plugin-core:runIde          # Test in sandbox

# Or just run tests:
./gradlew test                         # All tests
./gradlew :mcp-server:test             # MCP server only (fast)
```

**Linux auto-reload:** With sandbox IDE running, `./gradlew :plugin-core:prepareSandbox` hot-reloads the plugin without
restarting.

## What's Included

- **83 MCP tools** — File I/O, PSI analysis, refactoring, git, testing, terminal, documentation
- **ACP protocol** — Direct integration with GitHub Copilot CLI
- **Multi-turn chat** — Session-based conversations with context awareness
- **Permission routing** — File edits routed through IntelliJ Document API with auto-format

See [DEVELOPMENT.md](DEVELOPMENT.md) for full architecture and debugging guide.

---

*Last Updated: 2026-03-05*
