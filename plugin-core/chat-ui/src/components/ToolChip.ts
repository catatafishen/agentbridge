import {escHtml} from '../helpers';

/** Inline SVG of the AgentBridge icon at chip size (8×8px), color driven by currentColor. */
const AB_ICON_SVG = `<svg class="chip-icon" xmlns="http://www.w3.org/2000/svg" width="8" height="8" viewBox="0 0 13 13" aria-hidden="true"><path d="M 7.925 0 L 3.907 6.5 H 6.98 L 3.907 13 L 9.58 5.318 H 6.389 Z" fill="currentColor"/><circle cx="1.536" cy="1.536" r="1.182" fill="none" stroke="currentColor" stroke-width="0.709"/><circle cx="11.464" cy="1.536" r="1.182" fill="none" stroke="currentColor" stroke-width="0.709"/><circle cx="1.536" cy="11.464" r="1.182" fill="none" stroke="currentColor" stroke-width="0.709"/><circle cx="11.464" cy="11.464" r="1.182" fill="none" stroke="currentColor" stroke-width="0.709"/><polyline points="1.536,2.718 1.536,5.082 2.955,6.5" fill="none" stroke="currentColor" stroke-width="0.709" stroke-linecap="round" stroke-linejoin="round"/><polyline points="11.464,2.718 11.464,5.082 10.045,6.5" fill="none" stroke="currentColor" stroke-width="0.709" stroke-linecap="round" stroke-linejoin="round"/><polyline points="1.536,10.282 1.536,7.918 2.955,6.5" fill="none" stroke="currentColor" stroke-width="0.709" stroke-linecap="round" stroke-linejoin="round"/><polyline points="11.464,10.282 11.464,7.918 10.045,6.5" fill="none" stroke="currentColor" stroke-width="0.709" stroke-linecap="round" stroke-linejoin="round"/></svg>`;

export default class ToolChip extends HTMLElement {
    static get observedAttributes(): string[] {
        return ['label', 'status', 'expanded', 'kind', 'external'];
    }

    private _init = false;

    connectedCallback(): void {
        if (this._init) return;
        this._init = true;
        this.classList.add('turn-chip', 'tool');
        // Check if this tool was handled by MCP (from restored history)
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
        // Remove any previous kind/status class and apply current one
        this.className = this.className.replaceAll(/\bkind-\S+/g, '').replaceAll(/\bstatus-\S+/g, '').trim();
        this.classList.add('turn-chip', 'tool', `kind-${kind}`, `status-${status}`);
        this.classList.toggle('failed', status === 'failed' || status === 'denied');
        this.innerHTML = this._statusIcon(status) + escHtml(truncated);
        if (label.length > 50) {
            this.dataset.tip = label;
            this.setAttribute('title', label);
        }
    }

    private _statusIcon(status: string): string {
        if (status === 'running' || status === 'pending') {
            return '<span class="chip-spinner"></span> ';
        }
        const isSuccess = status !== 'failed' && status !== 'denied' && status !== 'error';
        const isAb = this.classList.contains('is-agentbridge-tool');
        const colorClass = isSuccess ? 'chip-icon-ok' : 'chip-icon-fail';
        if (isAb) {
            // Swap class on the pre-built SVG string
            return AB_ICON_SVG.replace('class="chip-icon"', `class="chip-icon ${colorClass}"`) + ' ';
        }
        const glyph = isSuccess ? '\u2713' : '\u2717';
        return `<span class="chip-icon ${colorClass}">${glyph}</span> `;
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
