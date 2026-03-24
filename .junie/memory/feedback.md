[2026-03-16 11:44] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Runtime context mismatch",
    "EXPECTATION": "They run Junie from CLI via a custom plugin and need command-line arguments to exclude built-in tools, not IDE UI instructions.",
    "NEW INSTRUCTION": "WHEN user indicates CLI-based Junie startup THEN propose exact CLI flags to exclude built-in tools"
}

[2026-03-16 12:12] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Parity with Copilot workaround",
    "EXPECTATION": "Handle Junie’s ignored excludedTools the same way Copilot is handled and add documentation about this limitation for tracking future changes.",
    "NEW INSTRUCTION": "WHEN excluding built-in tools for Junie THEN apply Copilot-style fallback and document limitation"
}

[2026-03-16 12:15] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Tool name mapping failure",
    "EXPECTATION": "Junie should display mapped, human-friendly tool names instead of raw MCP IDs like 'agentbridge/search_text'.",
    "NEW INSTRUCTION": "WHEN tool chip contains 'agentbridge/' THEN display mapped friendly tool name without namespace"
}

[2026-03-16 12:30] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Tool name mapping location",
    "EXPECTATION": "Tool name mappings should live in the respective AgentClient classes per agent, not in the UI.",
    "NEW INSTRUCTION": "WHEN rendering tool names anywhere THEN use AgentClient-provided friendly name mapping"
}

[2026-03-16 13:59] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Unmapped tool popup details",
    "EXPECTATION": "The unmapped tool popup should show any input/output instead of only 'complete'.",
    "NEW INSTRUCTION": "WHEN showing unmapped tool popup THEN display tool input and output payloads if available"
}

[2026-03-16 14:01] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Unmapped tool popup missing I/O",
    "EXPECTATION": "Tool calls like search_symbols should display their input parameters and output results, not just a generic 'complete' message.",
    "NEW INSTRUCTION": "WHEN tool status is 'complete' and payload present THEN render input and output sections in popup"
}

[2026-03-16 14:08] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Junie tool name normalization",
    "EXPECTATION": "JunieAcpClient should map MCP plugin tool IDs (e.g., 'agentbridge/edit_text') to the same friendly tool names used by other agents so UI renderers resolve correctly and labels look consistent.",
    "NEW INSTRUCTION": "WHEN normalizing Junie MCP plugin tool IDs THEN map to shared friendly names used by other agents"
}

[2026-03-16 15:00] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "HTTP error display",
    "EXPECTATION": "When 403 occurs, show the server's response message alongside the status to hint at model availability issues.",
    "NEW INSTRUCTION": "WHEN HTTP 4xx/5xx includes response body THEN display status and server error message"
}

[2026-03-16 15:19] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Infinite scroll history",
    "EXPECTATION": "Scrolling to the very top should repeatedly load older messages, not just the first page.",
    "NEW INSTRUCTION": "WHEN chat viewport reaches top AND hasMore=true THEN request next page and prepend messages while preserving scroll"
}

[2026-03-16 15:21] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Infinite scroll still broken",
    "EXPECTATION": "Scrolling upward should continue loading older messages beyond 14:48 until no more history is available.",
    "NEW INSTRUCTION": "WHEN chat scroll reaches top repeatedly AND hasMore=true THEN fetch next page and prepend while preserving scroll"
}

[2026-03-16 15:28] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Client-specific tool mapping",
    "EXPECTATION": "Junie tool names should be normalized in JunieAcpClient and shown as friendly names (without MCP namespace), not via a generic mapping in AcpAgentClient.",
    "NEW INSTRUCTION": "WHEN normalizing Junie MCP tool names THEN implement mapping in JunieAcpClient, not AcpAgentClient"
}

[2026-03-16 16:05] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Junie naming schema",
    "EXPECTATION": "Only normalize Junie’s actual tool IDs (e.g., agentbridge/intellij_read_file); double-underscore patterns are not used.",
    "NEW INSTRUCTION": "WHEN normalizing tool names in JunieAcpClient THEN map only Junie slash-prefixed IDs from tools list"
}

[2026-03-16 16:12] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Client normalization design",
    "EXPECTATION": "Junie should not map GitHub tools, subclasses must own normalization (no super), and UI should not prefix tool chips with 'Tool: '.",
    "NEW INSTRUCTION": "WHEN defining normalizeToolName in AcpClient THEN make it abstract; do not provide fallback"
}

[2026-03-16 16:23] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Abstract class instantiation",
    "EXPECTATION": "After making AcpClient abstract, it should never be directly instantiated; ActiveAgentManager must construct a concrete client or fail explicitly.",
    "NEW INSTRUCTION": "WHEN ActiveAgentManager default case constructs client THEN do not use AcpClient; select concrete or throw"
}

[2026-03-16 16:39] - Updated by Junie
{
    "TYPE": "positive",
    "CATEGORY": "Output correctness confirmation",
    "EXPECTATION": "The startup context and categorized tool counts matched expectations, with friendly tool names applied.",
    "NEW INSTRUCTION": "WHEN listing tools or environment context THEN include category counts and friendly tool names"
}

[2026-03-16 16:42] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Missing startup instructions",
    "EXPECTATION": "Assistant should not claim access to default-startup-instructions.md if it was not injected, and should explicitly report its absence.",
    "NEW INSTRUCTION": "WHEN asked to confirm startup instructions THEN explicitly state if not injected and request injection"
}

[2026-03-16 16:45] - Updated by Junie
{
    "TYPE": "positive",
    "CATEGORY": "Startup instructions confirmed",
    "EXPECTATION": "Assistant correctly recognized that default-startup-instructions.md was injected and reported its presence from initial context.",
    "NEW INSTRUCTION": "WHEN asked to confirm startup instructions THEN report from initial context without reading files"
}

[2026-03-16 16:48] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Tool chip label and mapping",
    "EXPECTATION": "UI should not prefix tool chips with 'Tool: ' and JunieAcpClient must map 'agentbridge/git_commit' to a friendly name via normalizeToolName.",
    "NEW INSTRUCTION": "WHEN rendering tool chips in UI THEN do not prefix labels with 'Tool: '"
}

[2026-03-16 16:53] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Tool chip prefix + mapping",
    "EXPECTATION": "Tool chips should not show the 'Tool: ' prefix and JunieAcpClient.normalizeToolName must map 'agentbridge/git_commit' to a friendly name.",
    "NEW INSTRUCTION": "WHEN rendering chat tool chips THEN remove any leading 'Tool: ' from labels"
}

[2026-03-16 17:30] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Tool title prefix",
    "EXPECTATION": "Titles from Junie like 'Tool: agentbridge/list_project_files' should have the 'Tool: ' prefix stripped before display and normalization.",
    "NEW INSTRUCTION": "WHEN request_permission title starts with 'Tool: ' THEN strip prefix before mapping and display"
}

[2026-03-16 17:35] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Slash-command parsing in messages",
    "EXPECTATION": "Junie should not interpret '/' inside referenced code snippets or quoted input as a command; it should escape or pass the text literally, matching Copilot behavior.",
    "NEW INSTRUCTION": "WHEN message text includes code fences or inline code THEN bypass slash-command parsing and send literal text"
}

[2026-03-16 17:39] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Slash-command parsing",
    "EXPECTATION": "Junie should not treat '/' inside referenced code snippets as a command and should pass the text literally, matching Copilot behavior.",
    "NEW INSTRUCTION": "WHEN message contains code fences or inline code THEN bypass slash-command parsing and send text literally"
}

[2026-03-16 20:30] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Slash-command parsing regression",
    "EXPECTATION": "Slash commands must not be parsed inside code snippets; the text should pass through literally.",
    "NEW INSTRUCTION": "WHEN message contains code fences or inline code THEN disable slash-command parsing and send literal text"
}

[2026-03-16 20:35] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Edit tool output",
    "EXPECTATION": "Edit operations should use the MCP edit tools and return a diff payload so the popup renders the patch instead of 'completed with no output'.",
    "NEW INSTRUCTION": "WHEN invoking code edits via Junie THEN call MCP edit tool and include diff output"
}

[2026-03-16 20:43] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Tool mapping duplication",
    "EXPECTATION": "Eliminate duplicate entries in ChatDataModel and declare friendly names on each tool class, using the map only as a fallback for agent built-ins not provided by the plugin.",
    "NEW INSTRUCTION": "WHEN mapping tools in ChatDataModel THEN remove duplicates, define names on tool classes, keep ChatDataModel map fallback"
}

[2026-03-16 20:46] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Tool mapping duplication",
    "EXPECTATION": "Remove duplicate tool mappings and declare friendly names on each tool class; use the ChatDataModel map only as a fallback for agent built-in tools not provided by the plugin.",
    "NEW INSTRUCTION": "WHEN mapping tools in ChatDataModel THEN remove duplicates, define names on tool classes, keep map fallback"
}

[2026-03-16 21:00] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Junie tool result mapping",
    "EXPECTATION": "Junie tool calls should map to the correct UI renderers and display their input/output instead of falling back to the default renderer with no content.",
    "NEW INSTRUCTION": "WHEN processing Junie tool result update THEN map normalized tool to renderer and render input/output"
}

[2026-03-16 21:05] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Follow agent with Junie",
    "EXPECTATION": "Follow agent should work with Junie by using our MCP tools, not Junie’s built-ins.",
    "NEW INSTRUCTION": "WHEN agent is Junie and follow-agent mode enabled THEN enforce excluding built-ins and warn if Junie ignores the setting"
}

[2026-03-16 21:14] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Junie client mapping/layering",
    "EXPECTATION": "Do not put tool behavior in the UI; fix JunieAcpClient so Junie maps tools like other agents and uses our MCP tools instead of its built-ins.",
    "NEW INSTRUCTION": "WHEN agent is Junie THEN normalize tool IDs and enforce excluding built-ins"
}

[2026-03-16 21:42] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Infinite scroll still broken",
    "EXPECTATION": "Scrolling to the top should load older messages and continue until history is exhausted.",
    "NEW INSTRUCTION": "WHEN chat scroll reaches top AND hasMore=true THEN fetch next page and prepend while preserving scroll"
}

[2026-03-16 21:43] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Infinite scroll not loading",
    "EXPECTATION": "When reaching the top of the chat, older messages should load and continue until history is exhausted, preserving scroll position.",
    "NEW INSTRUCTION": "WHEN chat viewport reaches top AND hasMore=true THEN fetch next page and prepend while preserving scroll"
}

[2026-03-16 21:53] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Infinite scroll pagination",
    "EXPECTATION": "Scrolling to the top should continue loading older messages until history is exhausted, not stop early.",
    "NEW INSTRUCTION": "WHEN chat scroll reaches top repeatedly AND hasMore=true THEN fetch next page and prepend while preserving scroll"
}

[2026-03-16 22:19] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Log tool filtering",
    "EXPECTATION": "The read_ide_log tool should support optional grep-like filters so large logs are manageable.",
    "NEW INSTRUCTION": "WHEN defining read_ide_log tool THEN add optional include/exclude regex and tail/limit params"
}

[2026-03-17 12:36] - Updated by Junie
{
    "TYPE": "positive",
    "CATEGORY": "Startup customization success",
    "EXPECTATION": "Removing built-in tools by defining custom agents at startup worked and matched their goal.",
    "NEW INSTRUCTION": "WHEN discussing disabling built-ins for any agent THEN propose startup-time custom agent configuration approach"
}

[2026-03-17 12:38] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Unsupported CLI flags source",
    "EXPECTATION": "User expects any CLI flags or configuration details to be backed by an injected source or clearly labeled as an assumption, not stated as facts without provenance.",
    "NEW INSTRUCTION": "WHEN providing CLI flags or config details THEN cite injected source or state assumption explicitly"
}

[2026-03-17 12:40] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "CLI flag recommendation provenance",
    "EXPECTATION": "Do not recommend the --exclude-tools flag for Junie without a cited source; existing docs only say it is unsupported/ignored.",
    "NEW INSTRUCTION": "WHEN suggesting Junie CLI flags THEN verify support in injected docs and avoid unsupported recommendations"
}

[2026-03-17 13:35] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Search method selection",
    "EXPECTATION": "When asked to find release information about a JetBrains Junie feature, the agent should use web search rather than local IDE tools.",
    "NEW INSTRUCTION": "WHEN user requests web search for feature availability THEN use web search and cite sources"
}

[2026-03-17 13:37] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Tool prefix requirement",
    "EXPECTATION": "Use the IDE MCP tools with the 'agentbridge-' prefix; non-prefixed calls were denied.",
    "NEW INSTRUCTION": "WHEN invoking any tool in this workspace THEN use names starting with 'agentbridge-'"
}

[2026-03-17 13:42] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Junie tool popup rendering",
    "EXPECTATION": "Both the natural language explanation and the raw input/output should be shown using our custom renderers for all Junie tools (e.g., git_diff), not just git_status.",
    "NEW INSTRUCTION": "WHEN rendering a known Junie tool result with payload THEN show explanation and custom-render raw input/output"
}

[2026-03-17 13:45] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Junie client normalization",
    "EXPECTATION": "Handle explanation/raw split inside JunieAcpClient by normalizing tool results to the standard renderer schema and exposing an optional description field.",
    "NEW INSTRUCTION": "WHEN processing Junie tool result THEN normalize payload for standard renderer and set optional description"
}

[2026-03-17 13:52] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Junie tool parsing",
    "EXPECTATION": "Use current IDE logs to inspect the real Junie tool result format and adjust JunieAcpClient normalization so raw parameters/results render, not just the description.",
    "NEW INSTRUCTION": "WHEN fixing Junie tool popups THEN inspect IDE logs and adapt parser to logged format"
}

[2026-03-17 15:11] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Junie tool rendering",
    "EXPECTATION": "Tool popups should display custom-rendered raw input/output alongside the description, and always fall back to showing the actual parameters and response if the tool is unmapped or payload parsing fails.",
    "NEW INSTRUCTION": "WHEN rendering Junie tool popup THEN show custom payload and description; else show params and response"
}

[2026-03-17 15:14] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "JunieAcpClient parsing rewrite",
    "EXPECTATION": "Rewrite JunieAcpClient.java lines 138-178 to parse the actual tool result format from IDE logs and correctly separate raw result from description for renderer mapping.",
    "NEW INSTRUCTION": "WHEN parsing Junie tool results THEN derive fields from current IDE logs and parse accordingly"
}

[2026-03-17 18:33] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Tool name parsing/rendering",
    "EXPECTATION": "Tool chips should show friendly tool names without stray quotes or file/glob text; current chips display values like 'AcpClient.java\"' or '*.js\"' indicating the parser used arguments/title text instead of the normalized tool ID.",
    "NEW INSTRUCTION": "WHEN deriving tool chip label from Junie updates THEN use normalized tool ID and strip quotes"
}

[2026-03-17 18:37] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Tool chip rendering stage",
    "EXPECTATION": "The issue is not JunieAcpClient normalization; most chips render fine and the problem arises later during UI string template rendering.",
    "NEW INSTRUCTION": "WHEN chip labels show stray quotes or file/glob THEN inspect UI string template rendering"
}

[2026-03-17 18:41] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "UI template rendering",
    "EXPECTATION": "The chip label issue occurs during later UI string template rendering, not in JunieAcpClient normalization; most chips already render correctly.",
    "NEW INSTRUCTION": "WHEN few chips show quoted or globbed labels THEN inspect UI string template rendering stage"
}

[2026-03-17 18:44] - Updated by Junie
{
    "TYPE": "preference",
    "CATEGORY": "Root cause investigation",
    "EXPECTATION": "Beyond adding fallbacks, identify why Junie’s agent and model labels are empty at the source.",
    "NEW INSTRUCTION": "WHEN agent is Junie AND labels are empty THEN trace settings sources and logs"
}

[2026-03-17 19:02] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Newline in tool payload",
    "EXPECTATION": "Junie tool_call_update payloads containing trailing newlines in text/rawInput should not break rendering; the popup should still render input/output normally.",
    "NEW INSTRUCTION": "WHEN parsing Junie tool_call_update text fields THEN safely handle trailing newlines and render"
}

[2026-03-17 19:06] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Tool prefix requirement",
    "EXPECTATION": "Use the IDE MCP tools with the 'agentbridge-' prefix for all actions.",
    "NEW INSTRUCTION": "WHEN invoking any tool in this workspace THEN use names starting with 'agentbridge-'"
}

[2026-03-17 19:08] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "CLI detection regression",
    "EXPECTATION": "Settings should correctly detect Copilot and OpenCode on PATH and not show 'Not found' when installed.",
    "NEW INSTRUCTION": "WHEN detecting Copilot/OpenCode in settings THEN use 'command -v' then '--version'; rely on exit code only"
}

[2026-03-17 19:09] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Tool prefix requirement",
    "EXPECTATION": "All tool calls must use the 'agentbridge-' prefix to avoid being denied.",
    "NEW INSTRUCTION": "WHEN invoking any tool here THEN use names starting with 'agentbridge-'"
}

[2026-03-17 19:12] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Tool chip label newline",
    "EXPECTATION": "Tool chips should show the friendly tool name (from normalized tool ID) without stray quotes or file names, even when Junie sends multiline content or a 'Tool: ' title.",
    "NEW INSTRUCTION": "WHEN Junie tool_call_update contains multiline content/title THEN label from normalized tool ID only"
}

[2026-03-17 19:15] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Comment/format accuracy",
    "EXPECTATION": "JunieAcpClient’s comment about multiple tool result formats is likely inaccurate; only case 1 has been observed in IDE logs.",
    "NEW INSTRUCTION": "WHEN documenting Junie tool result formats THEN verify against IDE logs and remove unobserved cases"
}

[2026-03-17 19:17] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Simplify name parsing",
    "EXPECTATION": "Stop using complex normalization; just extract the tool ID when the title starts with \"Tool: intellij_code_tools/\".",
    "NEW INSTRUCTION": "WHEN tool title begins with 'Tool: ' THEN extract following token as normalized tool ID"
}

[2026-03-17 19:26] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Slash-command parsing + duplicate prompt",
    "EXPECTATION": "When attaching a code snippet, the prompt should be sent literally (no slash-command parsing) and the code should not be duplicated in both resource and text parts.",
    "NEW INSTRUCTION": "WHEN message contains code fences or inline code THEN bypass slash-command parsing and send literal text"
}

[2026-03-17 19:32] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "CLI detection inconsistency",
    "EXPECTATION": "OpenCode and Copilot should be detected on PATH the same way Junie and Claude are, without false 'Not found' messages.",
    "NEW INSTRUCTION": "WHEN detecting OpenCode or Copilot binaries THEN run 'command -v <name>' and trust exit code; try known binary aliases"
}

[2026-03-17 19:40] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "CLI detection still failing",
    "EXPECTATION": "Copilot and OpenCode should be detected on PATH like Junie and Claude, not shown as 'Not found'.",
    "NEW INSTRUCTION": "WHEN detecting Copilot or OpenCode binaries THEN mirror Junie detection using command -v and --version with EnvironmentUtil PATH"
}

[2026-03-17 19:44] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "CLI detection parity",
    "EXPECTATION": "Copilot and OpenCode should be detected on PATH the same way as Junie and not show 'Not found' when installed.",
    "NEW INSTRUCTION": "WHEN detecting Copilot or OpenCode binaries THEN reuse Junie’s detection helper exactly with command -v/--version"
}

[2026-03-17 19:47] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "CLI detection assumption",
    "EXPECTATION": "Copilot and OpenCode should be detected on PATH like Junie/Claude without assuming npm installs.",
    "NEW INSTRUCTION": "WHEN detecting Copilot or OpenCode binaries THEN use command -v/where with IDE PATH; run --version; avoid npm paths"
}

[2026-03-17 20:21] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Junie chip attributes missing",
    "EXPECTATION": "Tool chips for Junie should display key attributes (e.g., file path for Read File, query for Search Text) like other clients do.",
    "NEW INSTRUCTION": "WHEN agent is Junie AND rendering chip for search_text or read_file THEN include query or file path from normalized payload"
}

[2026-03-17 21:09] - Updated by Junie
{
    "TYPE": "positive",
    "CATEGORY": "Bug identification confirmation",
    "EXPECTATION": "The disconnect was caused by a plugin bug (invalid persisted model name causing session/set_model timeout) and needs a fix.",
    "NEW INSTRUCTION": "WHEN restoring a saved model for an agent THEN validate against supported models and fallback without killing session"
}

[2026-03-17 21:27] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Edit permission handling",
    "EXPECTATION": "Kiro should not hang on edits; it must surface permission requests and explicitly report denial or timeout instead of waiting silently.",
    "NEW INSTRUCTION": "WHEN starting an edit and no permission reply arrives THEN timeout and notify user about missing permission"
}

[2026-03-17 21:28] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Tool prefix requirement",
    "EXPECTATION": "Use only IDE MCP tools whose names start with 'agentbridge-' so calls are not denied.",
    "NEW INSTRUCTION": "WHEN invoking any tool in this workspace THEN use names starting with 'agentbridge-'"
}

[2026-03-19 07:37] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Tool chip icon mapping",
    "EXPECTATION": "Tool chips should show the correct per-tool icon instead of always falling back to the default; icons should be selected via the normalized tool ID.",
    "NEW INSTRUCTION": "WHEN rendering chip icon in ChatToolWindowContent THEN select icon by normalized tool ID, else default"
}

[2026-03-19 08:34] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Toolbar icon regression",
    "EXPECTATION": "After removing the dropdown indicator, the Restart Session button should still display the per-agent custom icon instead of the default.",
    "NEW INSTRUCTION": "WHEN modifying RestartSessionGroup toolbar button THEN set presentation.icon via AgentIconProvider for active agent and refresh on agent changes"
}

[2026-03-19 09:30] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Diagnose startup failure",
    "EXPECTATION": "Investigate why OpenCode failed to start by checking IDE logs after the config-write change.",
    "NEW INSTRUCTION": "WHEN OpenCode fails to start THEN use agentbridge-read_ide_log with filters to find errors"
}

[2026-03-19 09:33] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "OpenCode config schema",
    "EXPECTATION": "The written OpenCode config must match OpenCode’s expected schema; keys like \"mcpServers\" are invalid and should be validated before startup.",
    "NEW INSTRUCTION": "WHEN OpenCode stderr says 'Unrecognized key' THEN fix config keys and restart agent"
}

[2026-03-19 09:43] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "OpenCode config schema",
    "EXPECTATION": "OpenCode must start with a valid opencode.json; use correct MCP config keys and structure (not mcpServers, not dotted keys like mcp.agentbridge).",
    "NEW INSTRUCTION": "WHEN generating opencode.json THEN define mcp as object with key 'agentbridge' (no dots)"
}

[2026-03-19 09:45] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Spec verification required",
    "EXPECTATION": "They want the OpenCode config schema verified from authoritative online sources and any recommendations backed by citations, not assumptions.",
    "NEW INSTRUCTION": "WHEN asked to verify config format or specs THEN perform web search and cite sources"
}

[2026-03-19 10:22] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Kiro tool permissions",
    "EXPECTATION": "Tool permission settings should be enforced for Kiro’s native tools, not only MCP tools.",
    "NEW INSTRUCTION": "WHEN agent is Kiro AND dispatching a native tool call THEN enforce configured permission map and block or ask accordingly"
}

[2026-03-19 10:38] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Enforcement mismatch",
    "EXPECTATION": "User expects code changes that enforce Junie to use MCP plugin tools (agentbridge-*) and exclude built-ins; docs-only updates are insufficient. Kiro naming was reverted and works as-is.",
    "NEW INSTRUCTION": "WHEN claiming Junie built-ins are excluded THEN implement code changes and verify runtime uses agentbridge-* tools"
}

[2026-03-19 11:00] - Updated by Junie
{
    "TYPE": "positive",
    "CATEGORY": "Client-specific logic placement",
    "EXPECTATION": "Junie-specific tool filtering should live in JunieAcpClient, with the base AcpClient remaining a no-op so other clients are unaffected.",
    "NEW INSTRUCTION": "WHEN adding Junie-specific session params THEN override addExtraSessionParams in JunieAcpClient; keep base no-op"
}

[2026-03-19 11:23] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Built-in tools not excluded",
    "EXPECTATION": "Only MCP tools (agentbridge- prefixed) should be available and used; native built-in tools must be excluded at startup as intended by commit 2c39da31.",
    "NEW INSTRUCTION": "WHEN tool list includes non agentbridge- tools THEN reinitialize session with built-ins excluded and notify user"
}

[2026-03-19 15:56] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Built-in list misclassification",
    "EXPECTATION": "Do not classify plugin-provided MCP tools (e.g., undo, build_project, get_compilation_errors) as native built-ins in ToolRegistry or exclusion lists.",
    "NEW INSTRUCTION": "WHEN modifying built-in exclusion lists THEN exclude plugin-provided MCP tools from denial"
}

[2026-03-19 16:17] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Provenance verification",
    "EXPECTATION": "Evidence for excludedTools support must come from Junie’s upstream codebase or runtime traces, not our docs.",
    "NEW INSTRUCTION": "WHEN claiming Junie supports excludedTools THEN cite Junie source file/commit or show runtime trace"
}

[2026-03-19 16:27] - Updated by Junie
{
    "TYPE": "positive",
    "CATEGORY": "Evidence-based confirmation",
    "EXPECTATION": "Assistant verified via Junie binary/code that protocol-level tool filtering is not supported and updated code/docs to match reality.",
    "NEW INSTRUCTION": "WHEN asserting Junie feature support THEN cite binary/code inspection or direct runtime test"
}

[2026-03-19 16:39] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Tool prefix requirement",
    "EXPECTATION": "Use only tools whose names start with 'agentbridge-' so calls are not denied.",
    "NEW INSTRUCTION": "WHEN invoking any tool in this workspace THEN use names starting with 'agentbridge-'"
}

[2026-03-19 16:46] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Premature continuation",
    "EXPECTATION": "Wait for the user's confirmation that Junie was restarted with the restricted allowlist before proceeding with verification steps.",
    "NEW INSTRUCTION": "WHEN user mentions pending restart or config change THEN wait for explicit confirmation before continuing"
}

[2026-03-19 16:56] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Tool prefix requirement",
    "EXPECTATION": "Use only tools with the 'agentbridge-' prefix; previous non-prefixed calls were denied.",
    "NEW INSTRUCTION": "WHEN invoking any tool in this workspace THEN use names starting with 'agentbridge-'"
}

[2026-03-19 16:57] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Premature continuation",
    "EXPECTATION": "Wait for explicit confirmation after restart with restricted allowlist before proceeding.",
    "NEW INSTRUCTION": "WHEN user mentions pending restart or config change THEN pause and request explicit confirmation before continuing"
}

[2026-03-19 17:02] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Tool prefix usage",
    "EXPECTATION": "Use only tools with the 'agentbridge-' prefix to avoid denials.",
    "NEW INSTRUCTION": "WHEN invoking any tool here THEN use names starting with 'agentbridge-'"
}

[2026-03-19 17:28] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Tool prefix requirement",
    "EXPECTATION": "Use only tools with the 'agentbridge-' prefix to avoid denials.",
    "NEW INSTRUCTION": "WHEN invoking any tool in this workspace THEN use names starting with 'agentbridge-'"
}

[2026-03-19 17:38] - Updated by Junie
{
    "TYPE": "positive",
    "CATEGORY": "Tool permission fix confirmed",
    "EXPECTATION": "Agentbridge-prefixed MCP tools are allowed again and no longer blanket-denied, while Junie built-ins remain blocked.",
    "NEW INSTRUCTION": "WHEN testing Junie tool permissions THEN verify agentbridge- tools allowed and built-ins denied"
}

[2026-03-19 17:39] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Tool prefix requirement",
    "EXPECTATION": "Use only tools with the 'agentbridge-' prefix; prior non-prefixed calls were denied.",
    "NEW INSTRUCTION": "WHEN invoking any tool in this workspace THEN use names starting with 'agentbridge-'"
}

[2026-03-19 17:47] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Tool chip denial info",
    "EXPECTATION": "Tool chips should clearly indicate when a tool call was denied so the UI makes it obvious.",
    "NEW INSTRUCTION": "WHEN rendering a tool chip for a denied call THEN show a Denied label and reason"
}

[2026-03-20 08:35] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Tool chip denial info",
    "EXPECTATION": "Tool chips should clearly indicate when a call was denied, not just show a generic warning.",
    "NEW INSTRUCTION": "WHEN tool call permission is denied THEN display 'Denied' badge and show denial reason"
}

[2026-03-20 09:16] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Tool prefix requirement",
    "EXPECTATION": "Use only IDE MCP tools with the 'agentbridge-' prefix so calls are not denied.",
    "NEW INSTRUCTION": "WHEN invoking any tool here THEN use names starting with 'agentbridge-'"
}

[2026-03-20 09:18] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "POJO field naming schema",
    "EXPECTATION": "Use a single, project-wide naming schema in ACP POJOs and map different JSON field names to the same model field instead of creating duplicate fields.",
    "NEW INSTRUCTION": "WHEN modeling ACP tool-call JSON THEN map JSON aliases to one canonical field; avoid duplicates"
}

[2026-03-20 09:22] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Typed updates over JSON",
    "EXPECTATION": "Use the existing SessionUpdate typed models and stop passing raw JsonObject payloads in AcpClient.",
    "NEW INSTRUCTION": "WHEN building agent→UI updates in AcpClient THEN emit SessionUpdate instances, not JsonObject"
}

[2026-03-20 09:26] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Use typed models",
    "EXPECTATION": "Stop passing raw JsonObject in AcpClient and use existing SessionUpdate records for tool events.",
    "NEW INSTRUCTION": "WHEN building session updates in AcpClient THEN construct and emit SessionUpdate instances instead of JsonObject"
}

[2026-03-20 09:31] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Typed model over JsonObject",
    "EXPECTATION": "Use the existing SessionUpdate typed records and pass real Java instances through AcpClient instead of raw JsonObjects.",
    "NEW INSTRUCTION": "WHEN emitting session updates in AcpClient THEN construct and pass SessionUpdate instances"
}

[2026-03-20 09:36] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Typed session updates",
    "EXPECTATION": "Use existing SessionUpdate models and avoid passing raw JsonObject payloads in AcpClient.",
    "NEW INSTRUCTION": "WHEN emitting agent → UI updates THEN construct SessionUpdate instances instead of JsonObject"
}

[2026-03-20 09:50] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Typed session models",
    "EXPECTATION": "AcpClient should emit and pass strongly-typed SessionUpdate ToolCall/ToolCallUpdate instances instead of ad-hoc JsonObjects.",
    "NEW INSTRUCTION": "WHEN creating tool_call or tool_call_update events THEN build SessionUpdate records not JsonObjects"
}

[2026-03-20 09:54] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Typed SessionUpdate usage",
    "EXPECTATION": "Use the existing SessionUpdate models and pass real Java instances instead of raw JsonObjects through AcpClient and the UI pipeline.",
    "NEW INSTRUCTION": "WHEN emitting agent→UI updates THEN use SessionUpdate records, not JsonObject"
}

[2026-03-21 22:15] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Tool kind update via MCP",
    "EXPECTATION": "Do not derive the kind for 'other' tool calls in PromptOrchestrator; allow MCP updates to overwrite the chip kind so color changes from gray to the correct one once handled.",
    "NEW INSTRUCTION": "WHEN tool_call.kind is 'other' from Kiro THEN keep kind and allow MCP update to overwrite"
}

[2026-03-21 22:27] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Tool call correlation",
    "EXPECTATION": "Match ACP and MCP tool calls via a stable hash of arguments/parameters, not semaphores; each tool class owns its kind and can overwrite the chip kind after execution using that hash.",
    "NEW INSTRUCTION": "WHEN correlating ACP and MCP tool calls THEN compute canonical args hash and update chip kind"
}

[2026-03-22 00:18] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Tool chip border state",
    "EXPECTATION": "Default chips stay dashed with no special class; only when MCP starts handling a tool call should the chip get a solid border via the 'is-agentbridge-tool' class; on completion simply remove the spinner and do not introduce or rely on a 'status-external' class.",
    "NEW INSTRUCTION": "WHEN a tool call reaches terminal state THEN remove spinner and leave border unchanged"
}

[2026-03-22 13:44] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Generated JS and placement",
    "EXPECTATION": "Do not edit generated .js files; only modify .ts sources. Show line counts in the toolbar using the existing tool-call counter mechanism with metadata, not inside chat, and avoid extra HTML/CSS changes.",
    "NEW INSTRUCTION": "WHEN modifying chat-ui UI THEN change TypeScript only; never edit generated JS"
}

[2026-03-22 16:35] - Updated by Junie
{
    "TYPE": "preference",
    "CATEGORY": "Tool exclusion policy",
    "EXPECTATION": "Exclude or deny all built-in tools except web_fetch and web_search; verify against official online specs, update project documentation, and implement runtime guards.",
    "NEW INSTRUCTION": "WHEN configuring Kiro/Junie tool filtering THEN allow web_fetch/search; deny all other built-ins"
}

[2026-03-22 16:43] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Toolbar stats visibility",
    "EXPECTATION": "After the tool count, the toolbar should display total removed lines in red and added lines in green, either accumulated for the whole session or for the current turn (with real-time updates in current-turn mode).",
    "NEW INSTRUCTION": "WHEN rendering toolbar stats THEN append red removed and green added line counts"
}

[2026-03-22 16:47] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Tool result mapping",
    "EXPECTATION": "The 'Get Compilation Errors' MCP tool call should map to the correct renderer instead of showing as grey with a dotted border.",
    "NEW INSTRUCTION": "WHEN a known MCP tool renders as unmapped THEN compare tool_call id/hash with tool_result and normalize tool ID from logs"
}

[2026-03-22 20:41] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Edit source assets",
    "EXPECTATION": "CSS changes should be made in the source stylesheet, not the generated/built CSS under resources.",
    "NEW INSTRUCTION": "WHEN updating chat UI styles THEN modify source stylesheet and rebuild assets, not built CSS"
}

[2026-03-22 21:01] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "UI placement",
    "EXPECTATION": "The Smart Paste control should appear in the settings view rather than the title bar.",
    "NEW INSTRUCTION": "WHEN adding or moving Smart Paste control THEN place it in the settings view"
}

[2026-03-22 21:10] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Tool kind resolution",
    "EXPECTATION": "Do not infer tool kind via ToolRegistry/title mapping in PromptOrchestrator; rely on MCP updates to set the final kind and attributes.",
    "NEW INSTRUCTION": "WHEN MCP tool_call_update arrives THEN set chip kind and attributes from MCP and ignore title mapping"
}

[2026-03-22 21:39] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Hash correlation mismatch",
    "EXPECTATION": "Agentbridge-handled tools (e.g., Search Text, Git Commit, Git Status) should render with solid borders after restart; current hash correlation fails causing dotted borders.",
    "NEW INSTRUCTION": "WHEN restoring tool chips from history THEN set mcpHandled by matching tool-call hash to MCP handling events"
}

[2026-03-22 21:44] - Updated by Junie
{
    "TYPE": "preference",
    "CATEGORY": "Project files dropdown",
    "EXPECTATION": "In the title bar project files dropdown, do not show empty groups like 'skills (none)', and use the actual IntelliJ icon for Markdown files.",
    "NEW INSTRUCTION": "WHEN rendering project files dropdown THEN hide empty groups; use IntelliJ Markdown icon"
}

[2026-03-23 19:35] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Tool chip border/correlation",
    "EXPECTATION": "Tool chips should get proper solid borders by correctly correlating MCP updates to chips, especially for Junie where arguments/hashes may differ; investigation should use IDE logs.",
    "NEW INSTRUCTION": "WHEN Junie tool chips lack borders THEN inspect IDE logs and align correlation via stable arg hash"
}

[2026-03-23 19:36] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Settings persistence",
    "EXPECTATION": "Tool permission selections should persist across IntelliJ restarts and not revert.",
    "NEW INSTRUCTION": "WHEN plugin starts after IDE restart THEN load and apply saved tool permission settings"
}

[2026-03-24 14:22] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Web UI branding label",
    "EXPECTATION": "The web UI should not display 'IDE agent for Copilot'; it should show only 'AgentBridge'.",
    "NEW INSTRUCTION": "WHEN rendering web UI title or header THEN display 'AgentBridge' only and remove Copilot text"
}

