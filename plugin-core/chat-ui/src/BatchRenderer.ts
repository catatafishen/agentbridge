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

interface NudgeSentTurn {
    type: 'nudge_sent';
    html: string;
    timestamp: string;
}

interface StatsTurn {
    type: 'stats';
    duration: number;
    inputTokens: number;
    outputTokens: number;
    tools: number;
    added: number;
    removed: number;
    model: string;
    multiplier?: string;
}

type BatchTurn = UserTurn | AgentTurn | SeparatorTurn | NudgeSentTurn | StatsTurn;

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
            case 'nudge_sent':
                fragment.appendChild(_renderNudgeSentTurn(turn));
                break;
            case 'stats':
                _appendStatsToLastAgent(fragment, turn);
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

/** Renders a sent nudge as a user message with a "Nudge" meta label. */
function _renderNudgeSentTurn(turn: NudgeSentTurn): HTMLElement {
    const msg = document.createElement('chat-message');
    msg.setAttribute('type', 'user');
    msg.classList.add('nudge-sent');

    const meta = document.createElement('message-meta');
    const label = document.createElement('span');
    label.className = 'ts nudge-sent-label';
    label.textContent = turn.timestamp ? `Nudge · ${turn.timestamp}` : 'Nudge';
    meta.appendChild(label);
    msg.appendChild(meta);

    const bubble = document.createElement('message-bubble');
    bubble.setAttribute('type', 'user');
    bubble.innerHTML = turn.html;
    msg.appendChild(bubble);

    return msg;
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

/**
 * Finds the last agent chat-message in the fragment and appends a stats footer.
 * Used to restore turn stats on session resume.
 */
function _appendStatsToLastAgent(fragment: DocumentFragment, stats: StatsTurn): void {
    const agents = fragment.querySelectorAll('chat-message[type="agent"]');
    const lastAgent = agents[agents.length - 1];
    if (!lastAgent) return;

    const meta = document.createElement('message-meta') as any;
    meta.classList.add('stats-footer', 'show');

    // Duration chip
    if (stats.duration > 0) {
        const chip = document.createElement('span');
        chip.className = 'turn-chip stats';
        const totalSec = Math.round(stats.duration / 1000);
        let dur: string;
        if (totalSec < 60) dur = totalSec + 's';
        else {
            const m = Math.floor(totalSec / 60), s = totalSec % 60;
            dur = s > 0 ? m + 'm ' + s + 's' : m + 'm';
        }
        chip.textContent = '⏱ ' + dur;
        meta.appendChild(chip);
    }

    // Token chip
    if (stats.inputTokens > 0 || stats.outputTokens > 0) {
        const fmt = (n: number) => n < 1000 ? String(n) : n < 10000 ? (n / 1000).toFixed(1) + 'k' : Math.round(n / 1000) + 'k';
        const chip = document.createElement('span');
        chip.className = 'turn-chip stats';
        chip.textContent = fmt(stats.inputTokens) + ' in · ' + fmt(stats.outputTokens) + ' out';
        chip.setAttribute('title', stats.model || '');
        meta.appendChild(chip);
    }

    // Tool count chip
    if (stats.tools > 0) {
        const chip = document.createElement('span');
        chip.className = 'turn-chip stats';
        chip.textContent = stats.tools + (stats.tools === 1 ? ' tool' : ' tools');
        meta.appendChild(chip);
    }

    // Multiplier chip
    if (stats.multiplier) {
        const chip = document.createElement('span');
        chip.className = 'turn-chip stats';
        chip.textContent = stats.multiplier;
        chip.setAttribute('title', stats.model || '');
        meta.appendChild(chip);
    }

    // Diff chip
    if (stats.added > 0 || stats.removed > 0) {
        meta.setCodeChangeStats(stats.added, stats.removed);
    }

    lastAgent.appendChild(meta);
}
