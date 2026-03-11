export default class WorkingIndicator extends HTMLElement {
    private _interval: ReturnType<typeof setInterval> | null = null;
    private _startTime = 0;
    private _span: HTMLSpanElement | null = null;

    connectedCallback(): void {
        this._span = document.createElement('span');
        this._span.className = 'working-text';
        this.appendChild(this._span);
        this.hidden = true;
        this.setAttribute('role', 'status');
        this.setAttribute('aria-live', 'polite');
        this.setAttribute('aria-label', 'Working');
    }

    show(): void {
        this.hidden = false;
        this._startTime = Date.now();
        this._render();
        this._stopTimer();
        this._interval = setInterval(() => this._render(), 1000);
    }

    hide(): void {
        this.hidden = true;
        this._stopTimer();
    }

    resetTimer(): void {
        if (this.hidden) return;
        this._startTime = Date.now();
        this._render();
    }

    private _render(): void {
        if (!this._span) return;
        const elapsed = Math.floor((Date.now() - this._startTime) / 1000);
        this._span.textContent = elapsed > 0 ? `Working\u2026 ${elapsed}s` : 'Working\u2026';
    }

    private _stopTimer(): void {
        if (this._interval !== null) {
            clearInterval(this._interval);
            this._interval = null;
        }
    }

    disconnectedCallback(): void {
        this._stopTimer();
    }
}
