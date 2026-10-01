# Built-in Agent (Koog) + AgentBridge (JetBrains IDEs)

AgentBridge's own agent. There is no external CLI to install and no second harness: the agent calls the model provider
directly and runs every tool call through AgentBridge's tool layer, inside the IDE.

> **Experimental.** Requires an IDE built on Kotlin 2.3 or newer (IntelliJ 2026.1 and later). On older IDEs the agent is
> simply not offered.

## What you get

- The model sees exactly one set of instructions (AgentBridge's) and exactly the tools AgentBridge exposes. There are no
  competing vendor instructions and no native tools to disable.
- Tool permissions work as for every other agent: **Settings → Tools → AgentBridge**. A tool you disabled or denied is
  refused here too.
- Edits, Git, builds and tests all go through the IDE, as with the other agents.

## Set it up

1. Open **Settings → Tools → AgentBridge → Agents → Built-in Agent (Koog)**.
2. Pick a provider:
   - **GitHub Copilot subscription**: press **Sign in with GitHub…**. A code is shown and copied to the clipboard and
     GitHub opens in your browser; enter the code there. The token is kept in the IDE's password safe. Models are listed
     from your subscription.
   - **OpenAI-compatible API (API key)**: enter the base URL (empty means OpenAI; a trailing `/v1` is fine), your API key
     and the model id. OpenRouter, Ollama and most gateways work.
3. In the AgentBridge tool window choose **Built-in Agent (Koog)** and start a conversation.

Changes apply the next time the agent starts (switch agent or restart it).

## Copilot sign-in needs an OAuth client id

GitHub's device sign-in needs a registered OAuth app, and this plugin does not borrow another product's. The maintainers
bundle one in `plugin-core/src/main/resources/koog/copilot-oauth-client-id.txt`; if your build has none, enter the
**Client ID** of your own app in the settings page:

1. GitHub → Settings → Developer settings → OAuth Apps → **New OAuth App** (any name and homepage URL).
2. Tick **Enable Device Flow**, create the app and copy its **Client ID** (it is public, not a secret).
3. Paste it into **OAuth client id** on the settings page, then sign in.

### About using a Copilot subscription this way

This uses the same device sign-in and API that other agent tools use with a Copilot subscription. GitHub has announced
official support for OpenCode specifically; nothing published covers other clients, so this is a gray area and could
change. Check that it is acceptable under your plan and your employer's policy.

## Limits

- No history compaction yet: very long conversations eventually reach the model's context limit.
- No image or audio input.
- Only models served on the chat-completions endpoint with tool support are offered. Some Copilot models (those served
  on other endpoints) are hidden for now.

Design notes and findings: [KOOG-HARNESS-INVESTIGATION.md](../KOOG-HARNESS-INVESTIGATION.md).

Back to the [README](../../README.md)
