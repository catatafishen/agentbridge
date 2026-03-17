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
    "EXPECTATION": "Junie should display mapped, human-friendly tool names instead of raw MCP IDs like 'intellij-code-tools/search_text'.",
    "NEW INSTRUCTION": "WHEN tool chip contains 'intellij-code-tools/' THEN display mapped friendly tool name without namespace"
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
    "EXPECTATION": "JunieAcpClient should map MCP plugin tool IDs (e.g., 'intellij-code-tools/edit_text') to the same friendly tool names used by other agents so UI renderers resolve correctly and labels look consistent.",
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
    "EXPECTATION": "Only normalize Junie’s actual tool IDs (e.g., intellij-code-tools/intellij_read_file); double-underscore patterns are not used.",
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
    "EXPECTATION": "UI should not prefix tool chips with 'Tool: ' and JunieAcpClient must map 'intellij-code-tools/git_commit' to a friendly name via normalizeToolName.",
    "NEW INSTRUCTION": "WHEN rendering tool chips in UI THEN do not prefix labels with 'Tool: '"
}

[2026-03-16 16:53] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Tool chip prefix + mapping",
    "EXPECTATION": "Tool chips should not show the 'Tool: ' prefix and JunieAcpClient.normalizeToolName must map 'intellij-code-tools/git_commit' to a friendly name.",
    "NEW INSTRUCTION": "WHEN rendering chat tool chips THEN remove any leading 'Tool: ' from labels"
}

[2026-03-16 17:30] - Updated by Junie
{
    "TYPE": "correction",
    "CATEGORY": "Tool title prefix",
    "EXPECTATION": "Titles from Junie like 'Tool: intellij-code-tools/list_project_files' should have the 'Tool: ' prefix stripped before display and normalization.",
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
    "EXPECTATION": "Use the IDE MCP tools with the 'intellij-code-tools-' prefix; non-prefixed calls were denied.",
    "NEW INSTRUCTION": "WHEN invoking any tool in this workspace THEN use names starting with 'intellij-code-tools-'"
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

