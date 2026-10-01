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
    - **GitHub Copilot subscription**: press **Sign in with GitHub…**. A window shows a code in large type (it is also
      copied to the clipboard) and GitHub opens in your browser; enter the code there and click Authorize. The window
      stays open while it waits and closes itself when you are signed in. The token is kept in the IDE's password safe.
      Models are listed from your subscription.
    - **OpenAI-compatible API (API key)**: enter the base URL (empty means OpenAI; a trailing `/v1` is fine), your API
      key
      and the model id. OpenRouter, Ollama and most gateways work.
3. In the AgentBridge tool window choose **Built-in Agent (Koog)** and start a conversation.

Changes apply the next time the agent starts (switch agent or restart it).

## Copilot sign-in needs an OAuth client id

GitHub's device sign-in needs a registered OAuth app, and this plugin does not borrow another product's. If your build
bundles one (`plugin-core/src/main/resources/koog/copilot-oauth-client-id.txt`) you can skip this section. If not, the
settings page shows a **One-time setup needed** box with these steps and an **Open GitHub** button, and pressing **Sign
in
with GitHub…** without an id offers the same. It is free, takes about two minutes, and you do not need a client secret:

1. Open <https://github.com/settings/applications/new> while signed in to GitHub.
2. Fill in the form. GitHub requires all three fields but the sign-in never uses them, so any values work:
   Application name `AgentBridge`, Homepage URL `https://github.com/catatafishen/agentbridge`, Authorization callback
   URL
   `http://localhost`.
3. Tick **Enable Device Flow** (on the form, or on the app's page after you register it) and click **Register
   application**.
4. Copy the **Client ID** shown on the app's page. It is public. Do not paste the client secret; the settings page warns
   if a value looks like one.
5. Paste it into **OAuth client id** on the settings page and press **Sign in with GitHub…**.

If sign-in then says Device Flow is not enabled, open the app under GitHub → Settings → Developer settings → OAuth Apps,
tick **Enable Device Flow** and save. If it says GitHub does not recognise the Client ID, re-copy the Client ID (not the
secret). If your Copilot seat comes from an organization that restricts third-party OAuth apps, an organization owner
may
need to approve the app.

Prefer not to register anything? Switch **Provider** to the OpenAI-compatible option and use an API key.

### About using a Copilot subscription this way

This uses the same device sign-in and API that other agent tools use with a Copilot subscription. GitHub has announced
official support for OpenCode specifically; nothing published covers other clients, so this is a gray area and could
change. Check that it is acceptable under your plan and your employer's policy.

## Limits

- No history compaction yet: very long conversations eventually reach the model's context limit.
- No image or audio input.
- Only chat models served on the chat-completions endpoint are offered. Models explicitly marked as not supporting tool
  calls and models served only on other endpoints are hidden for now.

Design notes and findings: [KOOG-HARNESS-INVESTIGATION.md](../KOOG-HARNESS-INVESTIGATION.md).

Back to the [README](../../README.md)
