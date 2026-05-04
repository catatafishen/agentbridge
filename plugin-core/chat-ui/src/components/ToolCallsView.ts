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
    private _expandedId: number | null = null;
    private _selectedStage: string | null = null;
    private _unsubscribe: (() => void) | null = null;
    /** True when running inside a JCEF panel (data pushed by Java). */
    private _pushMode = false;
    /** Auto-scroll to bottom when new items arrive. Disabled when user scrolls up. */
    private _autoScroll = true;

    constructor() {
        super(2000);
    }

    connectedCallback(): void {
        this.innerHTML = `
            <div class="tcv-container">
                <div class="tcv-empty">No tool calls yet</div>
                <div class="tcv-list"></div>
            </div>`;
        this._container = this.querySelector<HTMLElement>('.tcv-container')!;
        this._list = this.querySelector<HTMLElement>('.tcv-list')!;
        this._empty = this.querySelector<HTMLElement>('.tcv-empty')!;
        this._list.addEventListener('click', (e) => this._handleClick(e));

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
        const id = Number(row.dataset.id);
        this._expandedId = this._expandedId === id ? null : id;
        this._selectedStage = null;
        this._render();
    }

    private _render(): void {
        // Controller returns newest-first; reverse for chronological order (newest at bottom).
        const items = ToolCallsController.getAll().reverse();
        if (this.toggleEmptyState(this._empty, this._list, items.length === 0)) return;

        // Preserve scroll positions of <pre> blocks inside the expanded item across re-renders.
        const savedPreScrolls: number[] = [];
        if (this._expandedId !== null) {
            this._list.querySelectorAll<HTMLElement>('.tcv-detail pre, .tcv-stage-detail pre')
                .forEach(pre => savedPreScrolls.push(pre.scrollTop));
        }

        this._list.innerHTML = items.map(item => this._renderItem(item)).join('');

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

    private _renderItem(item: ToolCallData): string {
        const expanded = item.id === this._expandedId;
        const kind = this._kindClass(item.kind);
        const status = item.status || 'running';
        const statusClass = status.toLowerCase().replaceAll(/[^a-z0-9_-]/g, '-');
        const duration = item.durationMs > 0 ? this._formatDuration(item.durationMs) : '';

        let detail = '';
        if (expanded) {
            detail = this._renderDetail(item);
        }

        return `<div class="tcv-item${expanded ? ' tcv-expanded' : ''}" data-id="${item.id}">
            <div class="tcv-summary">
                <span class="tcv-kind ${kind}">${this.esc(item.kind || 'other')}</span>
                <span class="tcv-title">${this.esc(item.title)}</span>
                ${duration ? `<span class="tcv-duration">${duration}</span>` : ''}
                <span class="tcv-status ${statusClass}">${this.esc(status)}</span>
            </div>
            ${detail}
        </div>`;
    }

    private _renderDetail(item: ToolCallData): string {
        const stages = item.hookStages || [];
        const hasHookData = stages.length > 0;
        const resultText = item.result || (item.status === 'running' ? '(still running)' : '');

        // Build pipeline visualization
        const pipeline = hasHookData
            ? this._renderPipeline(item, stages)
            : '';

        // Build stage detail panel (shown when a pipeline node is clicked)
        const stageDetail = this._selectedStage
            ? this._renderStageDetail(item, stages, this._selectedStage)
            : '';

        // Default I/O view (shown when no pipeline stage is selected)
        const ioView = this._selectedStage ? '' : `
            <div class="tcv-io">
                <div class="tcv-io-section">
                    <div class="tcv-label">Input</div>
                    ${this._renderContent(item.arguments || '')}
                </div>
                <div class="tcv-io-section">
                    <div class="tcv-label">Output</div>
                    ${this._renderContent(resultText)}
                </div>
            </div>`;

        return `<div class="tcv-detail">
            ${pipeline}
            ${stageDetail}
            ${ioView}
        </div>`;
    }

    private _renderPipeline(item: ToolCallData, stages: HookStage[]): string {
        const nodes: string[] = [];

        // Input node
        nodes.push(this._pipeNode('input', 'Input', 'neutral', this._selectedStage === 'input'));

        // Permission hook
        const permStage = stages.find(s => s.trigger === 'permission');
        if (permStage) {
            nodes.push(
                this._pipeConnector(),
                this._pipeNode('permission', 'Permission',
                    this._outcomeClass(permStage.outcome), this._selectedStage === 'permission'));
        }

        // Pre-hook
        const preStage = stages.find(s => s.trigger === 'pre');
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

        // Success/failure hook
        const postStage = stages.find(s => s.trigger === 'success' || s.trigger === 'failure');
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
            case 'unchanged':
            case 'pass-through':
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

    private _renderStageDetail(item: ToolCallData, stages: HookStage[], stage: string): string {
        if (stage === 'input') {
            return `<div class="tcv-stage-detail">
                <div class="tcv-label">Input Arguments</div>
                ${this._renderContent(item.arguments || '')}
            </div>`;
        }
        if (stage === 'output') {
            const resultText = item.result || (item.status === 'running' ? '(still running)' : '');
            return `<div class="tcv-stage-detail">
                <div class="tcv-label">Final Output</div>
                ${this._renderContent(resultText)}
            </div>`;
        }
        if (stage === 'execution') {
            return `<div class="tcv-stage-detail">
                <div class="tcv-label">Tool Execution: ${this.esc(item.toolName)}</div>
                ${item.durationMs > 0 ? `<div class="tcv-stage-meta">Duration: ${this._formatDuration(item.durationMs)}</div>` : ''}
                <div class="tcv-label">Raw Output</div>
                ${this._renderContent(item.result || '(still running)')}
            </div>`;
        }

        // Hook stages
        const triggerMap: Record<string, string> = {
            permission: 'permission',
            pre: 'pre',
            post: 'success',
        };
        const trigger = triggerMap[stage];
        if (!trigger) return '';
        const hookStage = stages.find(s => s.trigger === trigger || (stage === 'post' && s.trigger === 'failure'));
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

    private _kindClass(kind?: string): string {
        const normalized = (kind || '').toLowerCase();
        if (normalized.includes('read')) return 'tcv-read';
        if (normalized.includes('edit') || normalized.includes('write')) return 'tcv-edit';
        if (normalized.includes('execute')) return 'tcv-execute';
        return 'tcv-other';
    }

    /**
     * Formats a duration in milliseconds.
     * Shows one decimal place for durations under 10 seconds (e.g. "3.2s") for
     * precision when quick tool calls are being compared.
     */
    private _formatDuration(ms: number): string {
        if (ms <= 0) return '';
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
