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
        this._renderAll();
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

    /**
     * Full render: sets innerHTML and classes. Called only from connectedCallback (chip
     * creation). Status/kind updates use _applyClasses() to avoid DOM mutations that
     * would trigger the MutationObserver and cause spurious scroll deferral.
     */
    private _renderAll(): void {
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

    /**
     * CSS-only status/kind update. Does NOT touch innerHTML — avoids childList DOM
     * mutations that trigger ChatContainer's MutationObserver and cause scroll deferral
     * collisions with concurrent streaming renders (the screen-tearing root cause).
     * className changes do not fire the observer (attributes: false in its config).
     */
    private _applyClasses(): void {
        const rawStatus = this.getAttribute('status') || 'running';
        const status = rawStatus.replaceAll(/\s+/g, '-');
        const kind = this.getAttribute('kind') || 'other';
        this.className = this.className.replaceAll(/\bkind-\S+/g, '').replaceAll(/\bstatus-\S+/g, '').trim();
        this.classList.add('turn-chip', 'tool', `kind-${kind}`, `status-${status}`);
        this.classList.toggle('failed', status === 'failed');
    }

    private _showPopup(): void {
        const id = this.dataset.chipFor || '';
        if (id) {
            globalThis._bridge?.showToolPopup?.(id);
        }
    }

    attributeChangedCallback(name: string): void {
        if (!this._init) return;
        if (name === 'status' || name === 'kind') {
            this._applyClasses();
        } else if (name === 'label') {
            // Update text node in-place — characterData mutation, not childList.
            // Also re-reads label for truncation changes.
            const label = this.getAttribute('label') || '';
            const truncated = label.length > 50 ? label.substring(0, 47) + '\u2026' : label;
            const textNode = this.lastChild;
            if (textNode?.nodeType === Node.TEXT_NODE) {
                textNode.textContent = ' ' + truncated;
            }
            if (label.length > 50) {
                this.dataset.tip = label;
                this.setAttribute('title', label);
            } else {
                delete this.dataset.tip;
                this.removeAttribute('title');
            }
        }
    }
}
