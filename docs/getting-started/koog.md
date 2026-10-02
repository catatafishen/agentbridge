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

## Which models you get

Copilot only offers the full model catalog (current GPT, Claude and Gemini models) to tokens issued to the Copilot
GitHub App that GitHub's own clients, Hermes and OpenCode sign in with. This plugin signs in with that app's client id
(`CopilotAuth.CLIENT_ID`) and, like those tools, exchanges the token for a short-lived Copilot session that it renews as
it expires. A token from any other OAuth app is given a short list of older GPT models only, so there is no setting
for a different client id.

If sign-in fails with a message that GitHub refused the Copilot session, check that the GitHub account has an active
Copilot subscription. If your seat comes from an organization that restricts third-party apps, an organization owner
may need to allow it.

Prefer not to sign in this way? Switch **Provider** to the OpenAI-compatible option and use an API key.

### About using a Copilot subscription this way

This uses the same device sign-in, client id and API that other agent tools use with a Copilot subscription. GitHub has
announced official support for OpenCode specifically; nothing published covers other clients, so this is a gray area,
could change, and GitHub could restrict it. The requests identify themselves as AgentBridge in `User-Agent` and
`Editor-Plugin-Version`, but carry the Copilot integration id Copilot requires. Check that it is acceptable under your
plan and your employer's policy.

## Limits

- Very long conversations are trimmed rather than summarised: old tool results are shortened and then the oldest
  exchanges are dropped, with a warning in the chat.
- No image or audio input.
- Only chat models served on the chat-completions endpoint are offered. Models explicitly marked as not supporting tool
  calls and models served only on other endpoints are hidden for now.

Design notes and findings: [KOOG-HARNESS-INVESTIGATION.md](../KOOG-HARNESS-INVESTIGATION.md).

Back to the [README](../../README.md)
