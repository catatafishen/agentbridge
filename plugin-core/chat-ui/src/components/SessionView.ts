export class SessionView extends HTMLElement {
    private _content!: HTMLElement;
    private _pollTimer: number | null = null;

    connectedCallback(): void {
        this.innerHTML = `<div class="sv-container"><div class="sv-content"></div></div>`;
        this._content = this.querySelector('.sv-content')!;
    }

    disconnectedCallback(): void {
        this.deactivate();
    }

    activate(): void {
        void this.refresh();
        this._pollTimer ??= globalThis.setInterval(() => void this.refresh(), 2000);
    }

    deactivate(): void {
        if (this._pollTimer != null) {
            clearInterval(this._pollTimer);
            this._pollTimer = null;
        }
    }

    async refresh(): Promise<void> {
        try {
            const resp = await fetch('/session-stats');
            if (!resp.ok) return;
            const data = await resp.json() as { isRunning: boolean; model: string; connected: boolean };
            this._render(data);
        } catch (e) {
            console.warn('[AB] SessionView refresh failed:', e);
            this._content.innerHTML = '<div class="sv-row sv-offline">IDE disconnected</div>';
        }
    }

    private _render(data: { isRunning: boolean; model: string; connected: boolean }): void {
        let statusClass: string;
        let statusText: string;
        let dot: string;
        if (data.isRunning) {
            statusClass = 'sv-running';
            statusText = 'Agent running';
            dot = '🟢';
        } else if (data.connected) {
            statusClass = 'sv-idle';
            statusText = 'Connected';
            dot = '🔵';
        } else {
            statusClass = 'sv-offline';
            statusText = 'Disconnected';
            dot = '🔴';
        }
        this._content.innerHTML = `
            <div class="sv-row ${statusClass}">
                <span class="sv-dot">${dot}</span>
                <span class="sv-label">${this._esc(statusText)}</span>
            </div>
            ${data.model ? `<div class="sv-row"><span class="sv-key">Model</span><span class="sv-val">${this._esc(data.model)}</span></div>` : ''}
        `;
    }

    private _esc(s: string): string {
        return s.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;');
    }
}
