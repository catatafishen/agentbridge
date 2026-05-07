import {escHtml} from '../helpers';

export default class ToolChip extends HTMLElement {
    static get observedAttributes(): string[] {
        return ['label', 'status', 'expanded', 'kind', 'external'];
    }

    private _init = false;

    connectedCallback(): void {
        if (this._init) return;
        this._init = true;
        this.classList.add('turn-chip', 'tool');
        if (this.dataset.mcpHandled === 'true') {
            this.classList.add('is-agentbridge-tool');
        }
        this.setAttribute('role', 'button');
        this.setAttribute('tabindex', '0');
        this.setAttribute('aria-expanded', 'false');
        this._render();
        this.onclick = (e) => {
            e.stopPropagation();
            this._showPopup();
        };
        this.onkeydown = (e) => {
            if (e.key === 'Enter' || e.key === ' ') {
                e.preventDefault();
                this._showPopup();
            }
        };
    }

    private _render(): void {
        const label = this.getAttribute('label') || '';
        const rawStatus = this.getAttribute('status') || 'running';
        const status = rawStatus.replaceAll(/\s+/g, '-');
        const kind = this.getAttribute('kind') || 'other';
        const truncated = label.length > 50 ? label.substring(0, 47) + '\u2026' : label;
        this.className = this.className.replaceAll(/\bkind-\S+/g, '').replaceAll(/\bstatus-\S+/g, '').trim();
        this.classList.add('turn-chip', 'tool', `kind-${kind}`, `status-${status}`);
        this.classList.toggle('failed', status === 'failed');
        this.innerHTML = '<span class="chip-ring" aria-hidden="true"></span> ' + escHtml(truncated);
        if (label.length > 50) {
            this.dataset.tip = label;
            this.setAttribute('title', label);
        }
    }

    private _showPopup(): void {
        const id = this.dataset.chipFor || '';
        if (id && globalThis._bridge?.showToolPopup) {
            globalThis._bridge.showToolPopup(id);
        }
    }

    attributeChangedCallback(name: string): void {
        if (!this._init) return;
        if (name === 'status' || name === 'kind') this._render();
    }
}
