/**
 * BatchRenderer — Creates DOM elements from structured JSON batch data.
 *
 * Replaces the old approach of generating raw HTML server-side. The server now
 * sends structured JSON with pre-rendered markdown, and this module creates
 * the DOM programmatically using custom elements.
 */
import {decodeBase64} from './helpers';

// ── Batch data types (matching Kotlin serialization) ────────

interface UserTurn {
    type: 'user';
    html: string;
    timestamp: string;
}

interface AgentTurn {
    type: 'agent';
    agent: string;
    segments: AgentSegment[];
}

interface AgentSegment {
    timestamp: string;
    entries: SegmentEntry[];
}

interface ThinkingEntry {
    type: 'thinking';
    id: string;
    html: string;
}

interface ToolEntry {
    type: 'tool';
    id: string;
    label: string;
    kind: string;
    status: string;
    params?: string;
    pluginTool?: string;
}

interface TextEntry {
    type: 'text';
    html: string;
}

interface SubAgentEntry {
    type: 'subagent';
    id: string;
    label: string;
    status: string;
    colorIndex: number;
    resultHtml?: string;
}

type SegmentEntry = ThinkingEntry | ToolEntry | TextEntry | SubAgentEntry;

interface SeparatorTurn {
    type: 'separator';
    timestamp: string;
    agent: string;
}

type BatchTurn = UserTurn | AgentTurn | SeparatorTurn;

// ── Public API ──────────────────────────────────────────────

/** Parse a base64-encoded JSON batch and return a DocumentFragment of rendered elements. */
export function renderBatchFragment(encodedJson: string): DocumentFragment {
    const turns: BatchTurn[] = JSON.parse(decodeBase64(encodedJson));
    const fragment = document.createDocumentFragment();

    for (const turn of turns) {
        switch (turn.type) {
            case 'user':
                fragment.appendChild(_renderUserTurn(turn));
                break;
            case 'agent':
                for (const segment of turn.segments) {
                    fragment.appendChild(_renderAgentSegment(turn.agent, segment));
                }
                break;
            case 'separator':
                fragment.appendChild(_renderSeparator(turn));
                break;
        }
    }

    return fragment;
}

// ── Turn renderers ──────────────────────────────────────────

function _renderUserTurn(turn: UserTurn): HTMLElement {
    const msg = document.createElement('chat-message');
    msg.setAttribute('type', 'user');

    const meta = document.createElement('message-meta');
    const ts = document.createElement('span');
    ts.className = 'ts';
    ts.textContent = turn.timestamp;
    meta.appendChild(ts);
    msg.appendChild(meta);

    const bubble = document.createElement('message-bubble');
    bubble.setAttribute('type', 'user');
    bubble.innerHTML = turn.html;
    msg.appendChild(bubble);

    return msg;
}

function _renderAgentSegment(agent: string, segment: AgentSegment): HTMLElement {
    const msg = document.createElement('chat-message');
    msg.setAttribute('type', 'agent');
    if (agent) msg.setAttribute('data-agent', agent);

    const meta = document.createElement('message-meta');
    if (segment.timestamp) {
        const ts = document.createElement('span');
        ts.className = 'ts';
        ts.textContent = segment.timestamp;
        meta.appendChild(ts);
    }

    const details = document.createElement('turn-details');
    let hasChips = false;

    for (const entry of segment.entries) {
        switch (entry.type) {
            case 'thinking':
                hasChips = true;
                _appendThinking(entry, meta, details);
                break;
            case 'tool':
                hasChips = true;
                _appendToolChip(entry, meta);
                break;
            case 'text':
                _appendTextBubble(entry, msg);
                break;
            case 'subagent':
                hasChips = true;
                _appendSubAgent(entry, meta, msg);
                break;
        }
    }

    if (hasChips) meta.classList.add('show');

    // Meta and details go before bubbles/subagent sections
    msg.prepend(details);
    msg.prepend(meta);

    return msg;
}

function _renderSeparator(turn: SeparatorTurn): HTMLElement {
    const el = document.createElement('session-divider');
    el.setAttribute('timestamp', turn.timestamp);
    el.setAttribute('agent', turn.agent);
    return el;
}

// ── Entry renderers ─────────────────────────────────────────

function _appendThinking(entry: ThinkingEntry, meta: HTMLElement, details: HTMLElement): void {
    const chip = document.createElement('thinking-chip');
    chip.setAttribute('label', 'Thought');
    chip.setAttribute('status', 'complete');
    chip.setAttribute('data-chip-for', entry.id);
    meta.appendChild(chip);

    const block = document.createElement('thinking-block');
    block.id = entry.id;
    block.className = 'thinking-section turn-hidden';
    const content = document.createElement('div');
    content.className = 'thinking-content';
    content.innerHTML = entry.html;
    block.appendChild(content);
    details.appendChild(block);
}

function _appendToolChip(entry: ToolEntry, meta: HTMLElement): void {
    const chip = document.createElement('tool-chip');
    chip.setAttribute('label', entry.label);
    chip.setAttribute('status', entry.status);
    chip.setAttribute('kind', entry.kind);
    chip.setAttribute('data-chip-for', entry.id);
    if (entry.params) chip.setAttribute('data-params', entry.params);
    if (entry.pluginTool) chip.setAttribute('data-mcp-handled', 'true');
    meta.appendChild(chip);
}

function _appendTextBubble(entry: TextEntry, msg: HTMLElement): void {
    const bubble = document.createElement('message-bubble');
    bubble.innerHTML = entry.html;
    msg.appendChild(bubble);
}

function _appendSubAgent(entry: SubAgentEntry, meta: HTMLElement, msg: HTMLElement): void {
    const chip = document.createElement('subagent-chip');
    chip.setAttribute('label', entry.label);
    chip.setAttribute('status', entry.status);
    chip.setAttribute('color-index', String(entry.colorIndex));
    chip.setAttribute('data-chip-for', entry.id);
    meta.appendChild(chip);

    const indent = document.createElement('div');
    indent.id = entry.id;
    indent.className = `subagent-indent subagent-c${entry.colorIndex} turn-hidden`;
    const bubble = document.createElement('message-bubble');
    bubble.innerHTML = entry.resultHtml ?? 'Completed';
    indent.appendChild(bubble);
    msg.appendChild(indent);
}
