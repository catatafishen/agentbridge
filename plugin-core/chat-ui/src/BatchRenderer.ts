/**
 * BatchRenderer — Creates DOM elements from structured JSON batch data.
 *
 * Replaces the old approach of generating raw HTML server-side. The server now
 * sends structured JSON with pre-rendered markdown, and this module creates
 * the DOM programmatically using custom elements.
 */
import {decodeBase64, hideRedundantTimestamp} from './helpers';

// ── Batch data types (matching Kotlin serialization) ────────

interface UserTurn {
    type: 'user';
    html: string;
    timestamp: string;
    entryId?: string;
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
            case 'user': {
                const el = _renderUserTurn(turn);
                fragment.appendChild(el);
                hideRedundantTimestamp(el);
                break;
            }
            case 'agent':
                for (const segment of turn.segments) {
                    const el = _renderAgentSegment(turn.agent, segment);
                    fragment.appendChild(el);
                    hideRedundantTimestamp(el);
                }
                break;
            case 'separator':
                fragment.appendChild(_renderSeparator(turn));
                break;
            case 'nudge_sent': {
                const el = _renderNudgeSentTurn(turn);
                fragment.appendChild(el);
                hideRedundantTimestamp(el);
                break;
            }
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
    if (turn.entryId) msg.id = turn.entryId;

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
 * Finds the last agent chat-message in the fragment and appends a turn summary bar.
 * Used to restore turn stats on session resume.
 */
function _appendStatsToLastAgent(fragment: DocumentFragment, stats: StatsTurn): void {
    const agents = fragment.querySelectorAll('chat-message[type="agent"]');
    const lastAgent = agents[agents.length - 1];
    if (!lastAgent) return;
    lastAgent.appendChild(_buildStatBar(stats));
}

function _buildStatBar(stats: StatsTurn): HTMLElement {
    const bar = document.createElement('div');
    bar.className = 'turn-summary-bar';

    const lineL = document.createElement('span');
    lineL.className = 'turn-summary-line';
    const lineR = document.createElement('span');
    lineR.className = 'turn-summary-line';
    const label = document.createElement('span');
    label.className = 'turn-summary-label';

    const totalSec = Math.round(stats.duration / 1000);
    let dur = '';
    if (totalSec > 0) {
        if (totalSec < 60) dur = totalSec + 's';
        else {
            const m = Math.floor(totalSec / 60), s = totalSec % 60;
            dur = s > 0 ? m + 'm ' + s + 's' : m + 'm';
        }
    }
    const fmt = (n: number): string =>
        n < 1000 ? String(n) : n < 10000 ? (n / 1000).toFixed(1) + 'k' : Math.round(n / 1000) + 'k';

    const parts: Array<string | HTMLElement> = [];

    if (stats.model) {
        const name = stats.model.includes('/') ? stats.model.split('/').pop()! : stats.model;
        parts.push(name);
    }

    if (stats.added > 0 || stats.removed > 0) {
        const diffEl = document.createElement('span');
        if (stats.added > 0) {
            const a = document.createElement('span');
            a.className = 'diff-add';
            a.textContent = '+' + stats.added;
            diffEl.appendChild(a);
        }
        if (stats.removed > 0) {
            if (stats.added > 0) diffEl.appendChild(document.createTextNode('\u2009'));
            const d = document.createElement('span');
            d.className = 'diff-del';
            d.textContent = '\u2212' + stats.removed;
            diffEl.appendChild(d);
        }
        parts.push(diffEl);
    }

    if (stats.inputTokens > 0 || stats.outputTokens > 0) {
        parts.push(fmt(stats.inputTokens) + '/' + fmt(stats.outputTokens) + ' tok');
    }

    if (stats.tools > 0) {
        parts.push(stats.tools + (stats.tools === 1 ? ' tool' : ' tools'));
    }

    if (stats.multiplier) {
        parts.push(stats.multiplier);
    }

    if (dur) parts.push(dur);

    const sep = ' \u2014 ';
    parts.forEach((part, i) => {
        if (i > 0) label.appendChild(document.createTextNode(sep));
        if (typeof part === 'string') {
            label.appendChild(document.createTextNode(part));
        } else {
            label.appendChild(part);
        }
    });

    bar.appendChild(lineL);
    bar.appendChild(label);
    bar.appendChild(lineR);
    return bar;
}
