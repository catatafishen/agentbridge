import {escHtml} from '../helpers';

export default class ToolSection extends HTMLElement {
    private _init = false;

    connectedCallback(): void {
        if (this._init) return;
        this._init = true;
        this.classList.add('tool-section', 'turn-hidden');
        const title = this.getAttribute('title') || '';
        this.innerHTML = `
            <div class="tool-section-header"><span class="tool-chevron">▼</span> ${escHtml(title)}</div>
            <div class="tool-section-body">
                <div class="tool-params"></div>
                <div class="tool-result"><span class="tool-running-hint">Running\u2026</span></div>
            </div>`;
        this.querySelector('.tool-section-header')!.addEventListener('click', () => this._collapse());
        const p = this.getAttribute('params');
        if (p) this.params = p;
    }

    private _collapse(): void {
        const chip = document.querySelector(`[data-chip-for="${this.id}"]`) as HTMLElement | null;
        if (chip) {
            chip.style.opacity = '1';
            chip.setAttribute('aria-expanded', 'false');
        }
        this.classList.add('collapsing');
        setTimeout(() => {
            this.classList.remove('collapsing', 'chip-expanded');
            this.classList.add('turn-hidden');
        }, 250);
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
