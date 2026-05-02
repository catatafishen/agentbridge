export class ReviewView extends HTMLElement {
    private _list!: HTMLElement;
    private _empty!: HTMLElement;
    private _pollTimer: number | null = null;

    connectedCallback(): void {
        this.innerHTML = `
            <div class="rv-container">
                <div class="rv-empty">No agent edits to review</div>
                <div class="rv-list"></div>
            </div>`;
        this._list = this.querySelector<HTMLElement>('.rv-list')!;
        this._empty = this.querySelector<HTMLElement>('.rv-empty')!;
    }

    disconnectedCallback(): void {
        this.deactivate();
    }

    activate(): void {
        void this.refresh();
        this._pollTimer ??= globalThis.setInterval(() => void this.refresh(), 3000);
    }

    deactivate(): void {
        if (this._pollTimer != null) {
            clearInterval(this._pollTimer);
            this._pollTimer = null;
        }
    }

    async refresh(): Promise<void> {
        try {
            const resp = await fetch('/review-items');
            if (!resp.ok) return;
            const data = await resp.json() as {
                items: Array<{
                    path: string;
                    status: string;
                    approved: boolean;
                    linesAdded: number;
                    linesRemoved: number
                }>
            };
            this._render(data.items);
        } catch (error) {
            console.error('[AB] Review item refresh failed:', error);
        }
    }

    private _render(items: Array<{
        path: string;
        status: string;
        approved: boolean;
        linesAdded: number;
        linesRemoved: number
    }>): void {
        if (items.length === 0) {
            this._empty.style.display = '';
            this._list.style.display = 'none';
            return;
        }
        this._empty.style.display = 'none';
        this._list.style.display = '';

        this._list.innerHTML = items.map(item => {
            const statusClass = this._statusClass(item.status);
            const statusIcon = this._statusIcon(item.status);
            const approvedClass = item.approved ? 'rv-approved' : '';
            const fileName = item.path.split('/').pop() || item.path;
            const dir = item.path.substring(0, item.path.length - fileName.length);
            return `<div class="rv-item ${statusClass} ${approvedClass}">
                <span class="rv-status">${statusIcon}</span>
                <span class="rv-path"><span class="rv-dir">${this._esc(dir)}</span>${this._esc(fileName)}</span>
                <span class="rv-diff">
                    ${item.linesAdded > 0 ? `<span class="rv-add">+${item.linesAdded}</span>` : ''}
                    ${item.linesRemoved > 0 ? `<span class="rv-rem">-${item.linesRemoved}</span>` : ''}
                </span>
                ${item.approved ? '<span class="rv-check">✓</span>' : ''}
            </div>`;
        }).join('');
    }

    private _statusClass(status: string): string {
        switch (status) {
            case 'ADDED':
                return 'rv-added';
            case 'DELETED':
                return 'rv-deleted';
            default:
                return 'rv-modified';
        }
    }

    private _statusIcon(status: string): string {
        switch (status) {
            case 'ADDED':
                return '+';
            case 'DELETED':
                return '−';
            default:
                return '~';
        }
    }

    private _esc(s: string): string {
        return s.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;');
    }
}
