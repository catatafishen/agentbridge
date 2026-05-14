export default class WorkingIndicator extends HTMLElement {
    private _interval: ReturnType<typeof setInterval> | null = null;
    private _startTime = 0;
    private _span: HTMLSpanElement | null = null;

    // Countdown state (active when prompt_user is waiting)
    private _countdownMode = false;
    private _deadlineMs = 0;
    private _countdownReqId = '';
    private _extendBtn: HTMLButtonElement | null = null;

    connectedCallback(): void {
        this._span = document.createElement('span');
        this._span.className = 'working-text';
        this.appendChild(this._span);

        this._extendBtn = document.createElement('button');
        this._extendBtn.type = 'button';
        this._extendBtn.className = 'working-extend';
        this._extendBtn.textContent = 'I need more time';
        this._extendBtn.hidden = true;
        this._extendBtn.onclick = () => {
            if (this._countdownReqId) {
                (globalThis as any)._bridge?.extendAskUser(this._countdownReqId);
            }
        };
        this.appendChild(this._extendBtn);

        this.hidden = true;
        this.setAttribute('role', 'status');
        this.setAttribute('aria-live', 'polite');
        this.setAttribute('aria-label', 'Working');
    }

    show(): void {
        this.hidden = false;
        this._countdownMode = false;
        this._startTime = Date.now();
        this._extendBtn!.hidden = true;
        this.classList.remove('working-countdown', 'working-countdown--warn', 'working-countdown--danger');
        this._render();
        this._stopTimer();
        this._interval = setInterval(() => this._render(), 1000);
    }

    hide(): void {
        this.hidden = true;
        this._countdownMode = false;
        this._extendBtn!.hidden = true;
        this.classList.remove('working-countdown', 'working-countdown--warn', 'working-countdown--danger');
        this._stopTimer();
    }

    resetTimer(): void {
        if (this.hidden) return;
        if (this._countdownMode) return;
        this._startTime = Date.now();
        this._render();
    }

    startCountdown(deadlineEpochMs: number, reqId: string): void {
        this._countdownMode = true;
        this._deadlineMs = deadlineEpochMs;
        this._countdownReqId = reqId;
        this._extendBtn!.hidden = false;
        this.classList.add('working-countdown');
        this.hidden = false;
        this._stopTimer();
        this._render();
        this._interval = setInterval(() => this._render(), 1000);
    }

    updateDeadline(deadlineEpochMs: number): void {
        this._deadlineMs = deadlineEpochMs;
        this.classList.remove('working-countdown--warn', 'working-countdown--danger');
        this._render();
    }

    stopCountdown(): void {
        this._countdownMode = false;
        this._countdownReqId = '';
        this._extendBtn!.hidden = true;
        this.classList.remove('working-countdown', 'working-countdown--warn', 'working-countdown--danger');
        // Revert to elapsed timer mode
        this._startTime = Date.now();
        this._render();
    }

    private _render(): void {
        if (!this._span) return;
        if (this._countdownMode) {
            const remainMs = Math.max(0, this._deadlineMs - Date.now());
            const remainSec = Math.ceil(remainMs / 1000);
            const mm = Math.floor(remainSec / 60);
            const ss = remainSec % 60;
            this._span.textContent = `Waiting\u2026 ${mm}:${ss.toString().padStart(2, '0')}`;
            this.classList.toggle('working-countdown--danger', remainSec <= 10);
            this.classList.toggle('working-countdown--warn', remainSec > 10 && remainSec <= 30);
        } else {
            const elapsed = Math.round((Date.now() - this._startTime) / 1000);
            this._span.textContent = elapsed > 0 ? `Working\u2026 ${elapsed}s` : 'Working\u2026';
        }
    }

    /**
     * Render the authoritative final duration (from Kotlin) and immediately hide.
     * Called by renderTurnSummary so both the indicator and the summary bar
     * show the same value, derived from the same source.
     */
    stop(ms: number): void {
        this._stopTimer();
        this._countdownMode = false;
        this._extendBtn!.hidden = true;
        this.classList.remove('working-countdown', 'working-countdown--warn', 'working-countdown--danger');
        if (!this._span) return;
        const secs = Math.round(ms / 1000);
        this._span.textContent = secs > 0 ? `Working\u2026 ${secs}s` : 'Working\u2026';
        this.hidden = true;
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
