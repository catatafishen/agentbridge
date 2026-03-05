import {escHtml} from '../helpers';

/**
 * Data-only element that stores tool call params and result.
 * Never displayed inline — ToolPopup reads from it.
 */
export default class ToolSection extends HTMLElement {
    private _init = false;

    connectedCallback(): void {
        if (this._init) return;
        this._init = true;
        this.classList.add('tool-section', 'turn-hidden');
        const title = this.getAttribute('title') || '';
        this.innerHTML = `
            <div class="tool-section-header">${escHtml(title)}</div>
            <div class="tool-section-body">
                <div class="tool-params"></div>
                <div class="tool-result"><span class="tool-running-hint">Running\u2026</span></div>
            </div>`;
        const p = this.getAttribute('params');
        if (p) this.params = p;
    }

    set params(val: string) {
        const el = this.querySelector('.tool-params');
        if (el) el.innerHTML = `<pre class="tool-params-code"><code>${escHtml(val)}</code></pre>`;
    }

    set result(val: string) {
        const el = this.querySelector('.tool-result');
        if (el) el.innerHTML = val;
    }

    updateStatus(_status: string): void { /* status tracked on chip only */
    }
}
