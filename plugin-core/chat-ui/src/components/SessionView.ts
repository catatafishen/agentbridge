import {PollableView} from './PollableView';

export class SessionView extends PollableView {
    private _content!: HTMLElement;

    constructor() {
        super(2000);
    }

    connectedCallback(): void {
        this.innerHTML = `<div class="sv-container"><div class="sv-content"></div></div>`;
        this._content = this.querySelector<HTMLElement>('.sv-content')!;
    }

    disconnectedCallback(): void {
        super.disconnectedCallback();
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
                <span class="sv-label">${this.esc(statusText)}</span>
            </div>
            ${data.model ? `<div class="sv-row"><span class="sv-key">Model</span><span class="sv-val">${this.esc(data.model)}</span></div>` : ''}
        `;
    }
}
