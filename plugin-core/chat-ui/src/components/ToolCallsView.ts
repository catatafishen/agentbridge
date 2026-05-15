import {PollableView} from './PollableView';
import type {HookStage, ToolCallData} from '../ToolCallsController';
import ToolCallsController from '../ToolCallsController';
import {highlight} from '../syntaxHighlight';

/**
 * Web component for displaying MCP tool calls with an interactive pipeline visualization.
 *
 * <p>In the IDE (JCEF), data is pushed by Java via {@code ToolCallsController.upsert()}.
 * In the PWA, this component polls {@code /tool-calls} and feeds data through
 * {@code ToolCallsController.setAll()}.
 *
 * <p>When a tool call row is expanded, the detail view shows a visual pipeline:
 * {@code Input → [Permission] → [Pre-hook] → Tool Execution → [Post-hook] → Output}.
 * Each stage is clickable and shows the corresponding data.
 */
export class ToolCallsView extends PollableView {
    private _list!: HTMLElement;
    private _empty!: HTMLElement;
    private _container!: HTMLElement;
    private _expandedId: number | string | null = null;
    private _selectedStage: string | null = null;
    private _unsubscribe: (() => void) | null = null;
    /** True when running inside a JCEF panel (data pushed by Java). */
    private _pushMode = false;
    /** Auto-scroll to bottom when new items arrive. Disabled when user scrolls up. */
    private _autoScroll = true;
    /** Prevents re-entrant auto-load calls. */
    private _loadingMore = false;

    constructor() {
        super(2000);
    }

    connectedCallback(): void {
        this.innerHTML = `
            <div class="tcv-container">
                <div class="tcv-load-more" hidden>↑ Load earlier tool calls</div>
                <div class="tcv-empty">No tool calls yet</div>
                <div class="tcv-list"></div>
            </div>`;
        this._container = this.querySelector<HTMLElement>('.tcv-container')!;
        this._list = this.querySelector<HTMLElement>('.tcv-list')!;
        this._empty = this.querySelector<HTMLElement>('.tcv-empty')!;
        this._list.addEventListener('click', (e) => this._handleClick(e));

        const loadMoreBtn = this.querySelector<HTMLElement>('.tcv-load-more')!;
        loadMoreBtn.addEventListener('click', () => this._loadMore());

        // Scrolling upward disables auto-scroll; reaching the bottom re-enables it.
        this._container.addEventListener('wheel', (e: WheelEvent) => {
            if (e.deltaY < 0 && this._autoScroll) {
                this._autoScroll = false;
            }
        }, {passive: true});
        this._container.addEventListener('scroll', () => {
            if (!this._autoScroll && this._isAtBottom()) {
                this._autoScroll = true;
            }
            // Auto-load more when scrolled to top
            if (this._container.scrollTop < 30) {
                this._loadMore();
            }
        }, {passive: true});

        this._unsubscribe = ToolCallsController.onChange(() => this._render());
    }

    disconnectedCallback(): void {
        super.disconnectedCallback();
        this._unsubscribe?.();
        this._unsubscribe = null;
    }

    /** Enable push mode (JCEF) — disables polling. */
    setPushMode(enabled: boolean): void {
        this._pushMode = enabled;
        if (enabled) this.deactivate();
    }

    async refresh(): Promise<void> {
        if (this._pushMode) return;
        try {
            const resp = await fetch('/tool-calls');
            if (!resp.ok) return;
            const data = await resp.json() as { items: ToolCallData[] };
            ToolCallsController.setAll(data.items);
        } catch {
            // Network error — will retry on next poll
        }
    }

    private _handleClick(e: MouseEvent): void {
        const target = e.target as HTMLElement;

        // View diff button click
        if ((target as HTMLElement).classList.contains('tcv-diff-btn')) {
            const row = target.closest<HTMLElement>('.tcv-item');
            if (row?.dataset.id) {
                const item = ToolCallsController.get(row.dataset.id!);
                if (item?.originalArguments) {
                    const fn = (window as any).openInputDiff;
                    if (typeof fn === 'function') fn(item.originalArguments, item.arguments, item.toolName);
                }
            }
            e.stopPropagation();
            return;
        }

        // Pipeline stage click
        const stageNode = target.closest<HTMLElement>('.tcv-pipe-node');
        if (stageNode?.dataset.stage) {
            this._selectedStage = this._selectedStage === stageNode.dataset.stage
                ? null : stageNode.dataset.stage;
            this._render();
            return;
        }

        // Row expand/collapse
        const row = target.closest<HTMLElement>('.tcv-item');
        if (!row?.dataset.id) return;
        const id = row.dataset.id;
        if (String(this._expandedId) === id) {
            this._expandedId = null;
            this._selectedStage = null;
        } else {
            this._expandedId = id;
            // Auto-select the last (output) pipeline stage so the result is immediately visible.
            const item = ToolCallsController.get(id);
            const activeHooks = this._activeHooks(item?.hookStages ?? []);
            this._selectedStage = activeHooks.length > 0 ? 'output' : null;
        }
        this._render();
    }

    private _render(): void {
        // Controller returns newest-first; reverse for chronological order (newest at bottom).
        const items = ToolCallsController.getAll().reverse();
        const noItems = items.length === 0;
        if (this.toggleEmptyState(this._empty, this._list, noItems)) return;

        // Show/hide "Load earlier" button
        const loadMoreEl = this.querySelector<HTMLElement>('.tcv-load-more');
        if (loadMoreEl) {
            const hasHistoric = items.some(i => i.historic);
            loadMoreEl.hidden = ToolCallsController.isHistoryExhausted() || !hasHistoric;
        }

        // Preserve scroll positions of <pre> blocks inside the expanded item across re-renders.
        const savedPreScrolls: number[] = [];
        if (this._expandedId !== null) {
            this._list.querySelectorAll<HTMLElement>('.tcv-detail pre, .tcv-stage-detail pre')
                .forEach(pre => savedPreScrolls.push(pre.scrollTop));
        }

        const html: string[] = [];
        let lastMinuteKey = '';
        for (const item of items) {
            if (item.timestamp) {
                const d = new Date(item.timestamp);
                const minuteKey = `${d.toDateString()} ${d.getHours()}:${String(d.getMinutes()).padStart(2, '0')}`;
                if (minuteKey !== lastMinuteKey) {
                    html.push(`<div class="tcv-time-sep">${this._formatTs(d)}</div>`);
                    lastMinuteKey = minuteKey;
                }
            }
            html.push(this._renderItem(item));
        }
        this._list.innerHTML = html.join('');

        if (this._expandedId !== null && savedPreScrolls.length > 0) {
            const pres = this._list.querySelectorAll<HTMLElement>('.tcv-detail pre, .tcv-stage-detail pre');
            pres.forEach((pre, i) => {
                if (i < savedPreScrolls.length) pre.scrollTop = savedPreScrolls[i];
            });
        }

        // Only auto-scroll when no item is expanded — don't yank the view away from what the user is reading.
        if (this._autoScroll && this._expandedId === null) {
            this._container.scrollTop = this._container.scrollHeight;
        }
    }

    private _isAtBottom(): boolean {
        return this._container.scrollHeight - this._container.scrollTop - this._container.clientHeight < 50;
    }

    /** Format a Date for display: time only when today, prefixed with "Yesterday" or short date otherwise. */
    private _formatTs(d: Date): string {
        const timeStr = d.toLocaleTimeString([], {hour: '2-digit', minute: '2-digit'});
        const now = new Date();
        if (d.toDateString() === now.toDateString()) return timeStr;
        const yesterday = new Date(now);
        yesterday.setDate(now.getDate() - 1);
        const datePrefix = d.toDateString() === yesterday.toDateString()
            ? 'Yesterday'
            : d.toLocaleDateString([], {month: 'short', day: 'numeric'});
        return `${datePrefix} ${timeStr}`;
    }

    /** Requests an older page of tool calls from the host (push mode only). */
    private _loadMore(): void {
        if (!this._pushMode || this._loadingMore || ToolCallsController.isHistoryExhausted()) return;
        this._loadingMore = true;
        const oldestId = ToolCallsController.oldestHistoricId();
        const fn = (window as any).loadMoreToolCalls;
        if (typeof fn === 'function') {
            fn(oldestId ?? '');
        }
        // Reset guard after a short delay to allow re-triggers if the call failed silently.
        setTimeout(() => {
            this._loadingMore = false;
        }, 2000);
    }

    /**
     * Returns hook stages that actually did something (excludes pass-through / unchanged outcomes).
     */
    private _activeHooks(stages: HookStage[]): HookStage[] {
        return stages.filter(s => s.outcome !== 'pass-through' && s.outcome !== 'unchanged');
    }

    private _renderItem(item: ToolCallData): string {
        const expanded = String(item.id) === String(this._expandedId);
        const kindClass = this._kindCssClass(item.kind);
        const status = item.status || 'running';
        const duration = item.durationMs >= 0 ? this._formatDuration(item.durationMs) : '';

        let detail = '';
        if (expanded) {
            detail = this._renderDetail(item);
        }

        // Mirror chat panel chip classes exactly: turn-chip tool is-agentbridge-tool kind-* status-*
        // so chip-ring CSS (spinning/filled/broken circle) renders identically in both panels.
        return `<div class="tcv-item turn-chip tool is-agentbridge-tool ${kindClass} status-${status}${expanded ? ' tcv-expanded' : ''}" data-id="${item.id}">
            <span class="chip-ring" aria-hidden="true"></span>
            <span class="tcv-title">${this.esc(item.title)}</span>
            ${duration ? `<span class="tcv-duration">${duration}</span>` : ''}
            ${detail}
        </div>`;
    }

    private _renderDetail(item: ToolCallData): string {
        const activeHooks = this._activeHooks(item.hookStages ?? []);
        const resultText = item.result || (item.status === 'running' ? '(still running)' : '');
        const pipeline = activeHooks.length > 0 ? this._renderPipeline(item, activeHooks) : '';
        const stageDetail = this._selectedStage
            ? this._renderStageDetail(item, activeHooks, this._selectedStage)
            : '';

        // ── Metadata row (always shown at top of expanded chip) ──────────────
        // Show the ACP display name only when it's genuinely different from the base tool name
        // (i.e. ACP has augmented it with context like "Run Command — npm build chat-ui").
        // A title that is simply the humanized toolName (e.g. "Run Command" for "run_command")
        // is already visible in the chip header and need not be repeated here.
        const baseTitle = item.toolName.replaceAll('_', ' ').toLowerCase();
        const nameRow = item.title.toLowerCase() === baseTitle
            ? ''
            : `<span class="tcv-meta-item"><strong>${this.esc(item.title)}</strong></span>`;
        const toolRow = `<span class="tcv-meta-item">MCP: ${this.esc(item.toolName)}</span>`;
        const ts = item.timestamp ? this._formatTs(new Date(item.timestamp)) : '';
        const tsRow = ts ? `<span class="tcv-meta-item tcv-meta-ts">${this.esc(ts)}</span>` : '';
        const metaSection = `<div class="tcv-meta-row">${nameRow}${toolRow}${tsRow}</div>`;

        // Default I/O view (shown when no pipeline stage is selected)
        const diffBtn = item.originalArguments && this._pushMode
            ? `<button class="tcv-diff-btn">View diff</button>` : '';
        const ioView = this._selectedStage ? '' : `
            <div class="tcv-io">
                <div class="tcv-io-section">
                    <div class="tcv-label">Input${diffBtn ? ' ' + diffBtn : ''}</div>
                    ${this._renderContent(item.arguments || '')}
                </div>
                <div class="tcv-io-section">
                    <div class="tcv-label">Output</div>
                    ${this._renderContent(resultText)}
                </div>
            </div>`;

        return `<div class="tcv-detail">
            ${metaSection}
            ${pipeline}
            ${stageDetail}
            ${ioView}
        </div>`;
    }

    private _renderPipeline(item: ToolCallData, activeHooks: HookStage[]): string {
        const nodes: string[] = [];

        // Input node
        nodes.push(this._pipeNode('input', 'Input', 'neutral', this._selectedStage === 'input'));

        // Permission hook (only if it did something)
        const permStage = activeHooks.find(s => s.trigger === 'permission');
        if (permStage) {
            nodes.push(
                this._pipeConnector(),
                this._pipeNode('permission', 'Permission',
                    this._outcomeClass(permStage.outcome), this._selectedStage === 'permission'));
        }

        // Pre-hook (only if it did something)
        const preStage = activeHooks.find(s => s.trigger === 'pre');
        if (preStage) {
            nodes.push(
                this._pipeConnector(),
                this._pipeNode('pre', 'Pre-hook',
                    this._outcomeClass(preStage.outcome), this._selectedStage === 'pre'));
        }

        // Tool execution node
        let execClass: string;
        if (item.status === 'running') execClass = 'running';
        else if (item.status === 'error') execClass = 'error';
        else execClass = 'success';
        nodes.push(
            this._pipeConnector(),
            this._pipeNode('execution', item.toolName, execClass, this._selectedStage === 'execution'));

        // Success/failure hook (only if it did something)
        const postStage = activeHooks.find(s => s.trigger === 'success' || s.trigger === 'failure');
        if (postStage) {
            nodes.push(
                this._pipeConnector(),
                this._pipeNode('post', 'Post-hook',
                    this._outcomeClass(postStage.outcome), this._selectedStage === 'post'));
        }

        // Output node
        nodes.push(
            this._pipeConnector(),
            this._pipeNode('output', 'Output', 'neutral', this._selectedStage === 'output'));

        return `<div class="tcv-pipeline">${nodes.join('')}</div>`;
    }

    private _pipeNode(stage: string, label: string, cls: string, selected: boolean): string {
        return `<div class="tcv-pipe-node tcv-pipe-${cls}${selected ? ' tcv-pipe-selected' : ''}"
                     data-stage="${stage}">
            <span class="tcv-pipe-label">${this.esc(label)}</span>
        </div>`;
    }

    private _pipeConnector(): string {
        return '<div class="tcv-pipe-connector">→</div>';
    }

    private _outcomeClass(outcome: string): string {
        switch (outcome) {
            case 'allowed':
                return 'success';
            case 'modified':
            case 'appended':
                return 'warning';
            case 'denied':
            case 'blocked':
            case 'error':
                return 'error';
            default:
                return 'neutral';
        }
    }

    private _renderStageDetail(item: ToolCallData, activeHooks: HookStage[], stage: string): string {
        // Render stage content directly in tcv-detail without an extra wrapper box —
        // the pre/code block already has its own visual container, so double-boxing is redundant.
        if (stage === 'input') {
            const diffBtn = item.originalArguments && this._pushMode
                ? `<button class="tcv-diff-btn">View diff</button>` : '';
            return `<div class="tcv-label">Input Arguments${diffBtn ? ' ' + diffBtn : ''}</div>
                ${this._renderContent(item.arguments || '')}`;
        }
        if (stage === 'output') {
            const resultText = item.result || (item.status === 'running' ? '(still running)' : '');
            return `<div class="tcv-label">Output</div>
                ${this._renderContent(resultText)}`;
        }
        if (stage === 'execution') {
            const meta = item.durationMs > 0
                ? `<div class="tcv-stage-meta"><span>Duration: ${this._formatDuration(item.durationMs)}</span></div>`
                : '';
            return `<div class="tcv-label">Tool: ${this.esc(item.toolName)}</div>
                ${meta}
                <div class="tcv-label">Output</div>
                ${this._renderContent(item.result || '(still running)')}`;
        }

        // Hook stages — keep the tcv-stage-detail wrapper to visually separate them.
        const triggerMap: Record<string, string> = {
            permission: 'permission',
            pre: 'pre',
            post: 'success',
        };
        const trigger = triggerMap[stage];
        if (!trigger) return '';
        const hookStage = activeHooks.find(s => s.trigger === trigger || (stage === 'post' && s.trigger === 'failure'));
        if (!hookStage) return '';

        return `<div class="tcv-stage-detail">
            <div class="tcv-label">${this.esc(hookStage.trigger)} hook: ${this.esc(hookStage.scriptName)}</div>
            <div class="tcv-stage-meta">
                <span>Outcome: <strong>${this.esc(hookStage.outcome)}</strong></span>
                ${hookStage.durationMs > 0 ? `<span>Duration: ${this._formatDuration(hookStage.durationMs)}</span>` : ''}
            </div>
            ${hookStage.detail ? `<div class="tcv-label">Detail</div>${this._renderContent(hookStage.detail)}` : ''}
        </div>`;
    }

    /**
     * Renders text content as a `<pre><code>` block. If the text is valid JSON it is
     * pretty-printed and syntax-highlighted; otherwise it is plain-escaped.
     */
    private _renderContent(text: string): string {
        if (!text) return '<pre><code></code></pre>';
        let inner: string;
        try {
            const parsed = JSON.parse(text);
            const pretty = JSON.stringify(parsed, null, 2);
            inner = highlight(pretty, 'json');
        } catch {
            inner = this.esc(text);
        }
        return `<pre><code>${inner}</code></pre>`;
    }

    private _kindCssClass(kind?: string): string {
        const k = (kind || '').toLowerCase().trim();
        return k ? `kind-${k}` : 'kind-other';
    }

    /**
     * Formats a duration in milliseconds.
     * Shows one decimal place for durations under 10 seconds (e.g. "3.2s") for
     * precision when quick tool calls are being compared.
     */
    private _formatDuration(ms: number): string {
        if (ms < 0) return '';
        if (ms === 0) return '0s';
        if (ms < 10_000) return (ms / 1000).toFixed(1) + 's';
        const totalSec = Math.round(ms / 1000);
        if (totalSec < 60) return totalSec + 's';
        const min = Math.floor(totalSec / 60);
        const sec = totalSec % 60;
        if (min < 60) return sec > 0 ? min + 'm ' + sec + 's' : min + 'm';
        const hr = Math.floor(min / 60);
        const remMin = min % 60;
        return remMin > 0 ? hr + 'h ' + remMin + 'm' : hr + 'h';
    }
}
