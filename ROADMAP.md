# Project Roadmap

## Overview

AgentBridge is an IntelliJ plugin providing ACP/MCP bridge connectivity for AI coding agents,
with IntelliJ-native tools for code intelligence, formatting, and file operations.

---

## ✅ Phase 1: Foundation (COMPLETE)

- ✅ Multi-module Gradle project (plugin-core, mcp-server, integration-tests)
- ✅ Tool Window UI with chat panel
- ✅ Infrastructure prototype (later replaced with direct ACP integration)

## ✅ Phase 2: ACP Integration (COMPLETE)

- ✅ Direct ACP protocol integration
- ✅ JSON-RPC 2.0 over stdin/stdout with Copilot CLI
- ✅ Session lifecycle, model selection, streaming responses
- ✅ Authentication via Copilot CLI

## ✅ Phase 3: MCP Code Intelligence (COMPLETE)

- ✅ MCP server with 92 IntelliJ-native tools
- ✅ PSI bridge HTTP server for tool execution inside IntelliJ process
- ✅ Symbol search, file outline, reference finding
- ✅ Test runner, coverage, run configurations
- ✅ IntelliJ read/write via Document API
- ✅ Code problems, optimize imports, format code

## ✅ Phase 4: IntelliJ-Native File Operations (COMPLETE)

- ✅ Deny built-in edit/create permissions
- ✅ Auto-retry with MCP tool instruction
- ✅ Auto-format (optimize imports + reformat) after every write
- ✅ All writes through IntelliJ Document API (undo support)
- ✅ No "file changed externally" dialog

## ✅ Phase 5: Polish & Usage Tracking (COMPLETE)

- ✅ Reconnect logic (auto-restart dead ACP process)
- ✅ Model persistence, cost multiplier display
- ✅ Real GitHub billing data (premium requests, entitlement)
- ✅ Agent/Plan mode toggle
- ✅ IntelliJ platform UI conventions (JBColor, JBUI, etc.)

## ✅ Phase 6: Multi-Agent Support (COMPLETE)

- ✅ `AgentConfig` strategy interface for agent-agnostic ACP client
- ✅ `AgentSettings` interface for runtime configuration
- ✅ Agent profile system with per-profile settings
- ✅ Built-in profiles: GitHub Copilot, OpenCode, Junie, Kiro
- ✅ Custom profile support with full configuration
- ✅ Agent selector UI in connection panel
- ✅ Project-wide AgentBridge MCP permissions and per-profile instructions
- ✅ Sub-agent name display in chat bubbles

## ✅ Phase 7: UI Polish (COMPLETE)

- ✅ JCEF-based chat panel with streaming markdown
- ✅ Quick-reply buttons with semantic colors
- ✅ Collapsible thinking/tool sections with chip summaries
- ✅ Theme-aware styling (auto-adapts to IDE theme)
- ✅ Context attachments (files, selections)
- ✅ Conversation history with lazy loading

---

## 🎯 Future Work

### Documentation & Developer Experience

- [ ] API documentation for extending with custom agents
- [ ] Video tutorials for common workflows
- [ ] Example custom agent profile templates

### Agent Ecosystem

- [ ] Gemini CLI integration (when ACP support available)
- [ ] Cursor integration investigation
- [ ] Agent-specific instruction templates

### Quality & Testing

- [ ] Cross-platform testing (macOS, Windows)
- [ ] E2E integration tests with mock agent
- [ ] Performance benchmarks for tool execution

### Semantic Memory

- [ ] Grounded memory — codebase-verified claims with evidence linking
  (see [docs/GROUNDED-MEMORY.md](docs/GROUNDED-MEMORY.md))
- [ ] Automatic per-turn mining hook
- [ ] Wake-up context injection on session start
- [ ] Cross-project memory (`~/.agentbridge/memory/`)
- [ ] Retention policies (age-based cleanup, importance pruning)

### Platform Expansion

- [ ] JetBrains Marketplace publication
- [ ] Fleet compatibility investigation
- [ ] Remote development support

---

*Last Updated: 2026-03-22*
