import {escHtml} from '../helpers';
import {toolDisplayName} from '../toolDisplayName';

export default class ToolChip extends HTMLElement {
    static get observedAttributes(): string[] {
        return ['label', 'status', 'expanded', 'kind', 'external'];
    }

    private _init = false;

    connectedCallback(): void {
        if (this._init) return;
        this._init = true;
        this.classList.add('turn-chip', 'tool');
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
        const rawLabel = this.getAttribute('label') || '';
        const status = this.getAttribute('status') || 'running';
        const kind = this.getAttribute('kind') || 'other';
        const isExternal = this.getAttribute('external') === 'true';
        const paramsStr = this.dataset.params || undefined;
        const display = toolDisplayName(rawLabel, paramsStr);
        const truncated = display.length > 50 ? display.substring(0, 47) + '\u2026' : display;
        // Remove any previous kind class and apply current one
        this.className = this.className.replaceAll(/\bkind-\S+/g, '').trim();
        this.classList.add('turn-chip', 'tool', `kind-${kind}`);
        if (isExternal) this.classList.add('external-tool');
        let iconHtml = '';
        if (status === 'running') iconHtml = '<span class="chip-spinner"></span> ';
        else if (status === 'failed') this.classList.add('failed');
        // Add external badge for non-MCP tools
        const externalBadge = isExternal ? '<span class="external-badge" title="Built-in agent tool (not from MCP plugin)">⚠</span> ' : '';
        this.innerHTML = iconHtml + externalBadge + escHtml(truncated);
        if (display.length > 50) this.dataset.tip = display;
        else if (rawLabel !== display) this.dataset.tip = rawLabel;
        if (this.dataset.tip) this.setAttribute('title', this.dataset.tip);
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
