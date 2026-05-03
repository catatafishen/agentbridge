import {PollableView} from './PollableView';

type ToolCallItem = {
    id: string;
    title: string;
    kind: string;
    status: string | null;
    timestamp: string;
    arguments: string | null;
    result: string | null;
};

export class ToolCallsView extends PollableView {
    private _list!: HTMLElement;
    private _empty!: HTMLElement;
    private _items: ToolCallItem[] = [];
    private _expandedId: string | null = null;

    constructor() {
        super(2000);
    }

    connectedCallback(): void {
        this.innerHTML = `
            <div class="tcv-container">
                <div class="tcv-empty">No tool calls yet</div>
                <div class="tcv-list"></div>
            </div>`;
        this._list = this.querySelector<HTMLElement>('.tcv-list')!;
        this._empty = this.querySelector<HTMLElement>('.tcv-empty')!;
        this._list.addEventListener('click', (event) => {
            const row = (event.target as HTMLElement).closest<HTMLElement>('.tcv-item');
            if (!row) return;
            this._expandedId = this._expandedId === row.dataset.id ? null : row.dataset.id ?? null;
            this._render();
        });
    }

    disconnectedCallback(): void {
        super.disconnectedCallback();
    }

    async refresh(): Promise<void> {
        try {
            const resp = await fetch('/tool-calls');
            if (!resp.ok) {
                console.error(`[AB] Tool call fetch failed: HTTP ${resp.status}`);
                return;
            }
            const data = await resp.json() as { items: ToolCallItem[] };
            this._items = data.items.slice().reverse();
            this._render();
        } catch (error) {
            console.error('[AB] Failed to refresh tool calls:', error);
        }
    }

    private _render(): void {
        if (this.toggleEmptyState(this._empty, this._list, this._items.length === 0)) return;
        this._list.innerHTML = this._items.map(item => this._renderItem(item)).join('');
    }

    private _renderItem(item: ToolCallItem): string {
        const expanded = item.id === this._expandedId;
        const kind = this._kindClass(item.kind);
        const status = item.status || 'running';
        const statusClass = status.toLowerCase().replaceAll(/[^a-z0-9_-]/g, '-');
        let detail = '';
        if (expanded) {
            const resultText = item.result || (status === 'running' ? '(still running)' : '');
            detail = `
            <div class="tcv-detail">
                <div class="tcv-label">Input</div>
                <pre>${this.esc(item.arguments || '')}</pre>
                <div class="tcv-label">Output</div>
                <pre>${this.esc(resultText)}</pre>
            </div>`;
        }
        return `<div class="tcv-item" data-id="${this.escAttr(item.id)}">
            <div class="tcv-summary">
                <span class="tcv-kind ${kind}">${this.esc(item.kind || 'other')}</span>
                <span class="tcv-title">${this.esc(item.title)}</span>
                <span class="tcv-status ${statusClass}">${this.esc(status)}</span>
            </div>
            <div class="tcv-time">${this.esc(this._formatTime(item.timestamp))}</div>
            ${detail}
        </div>`;
    }

    private _kindClass(kind: string): string {
        const normalized = (kind || '').toLowerCase();
        if (normalized.includes('read')) return 'tcv-read';
        if (normalized.includes('edit') || normalized.includes('write')) return 'tcv-edit';
        if (normalized.includes('execute')) return 'tcv-execute';
        return 'tcv-other';
    }

    private _formatTime(timestamp: string): string {
        if (!timestamp) return '';
        const date = new Date(timestamp);
        if (Number.isNaN(date.getTime())) return timestamp;
        return date.toLocaleTimeString([], {hour: '2-digit', minute: '2-digit', second: '2-digit'});
    }
}
