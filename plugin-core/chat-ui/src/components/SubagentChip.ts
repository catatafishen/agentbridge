import {collapseAllChips} from '../helpers';

export default class SubagentChip extends HTMLElement {
    static get observedAttributes(): string[] {
        return ['label', 'status', 'color-index'];
    }

    private _init = false;
    _linkedSection: HTMLElement | null = null;

    connectedCallback(): void {
        if (this._init) return;
        this._init = true;
        const ci = this.getAttribute('color-index') || '0';
        this.classList.add('turn-chip', 'subagent', 'subagent-c' + ci);
        this.setAttribute('role', 'button');
        this.setAttribute('tabindex', '0');
        this.setAttribute('aria-expanded', 'false');
        this._render();
        this.onclick = (e) => {
            e.stopPropagation();
            this._toggleExpand();
        };
        this.onkeydown = (e) => {
            if (e.key === 'Enter' || e.key === ' ') {
                e.preventDefault();
                this._toggleExpand();
            }
        };
    }

    private _render(): void {
        const label = this.getAttribute('label') || '';
        const status = this.getAttribute('status') || 'running';
        const display = label.length > 50 ? label.substring(0, 47) + '\u2026' : label;
        let html = '';
        if (status === 'running') html = '<span class="chip-spinner"></span> ';
        else if (status === 'failed') this.classList.add('failed');
        html += (label.length > 50 ? '<span>' + display + '</span>' : display);
        this.innerHTML = html;
    }

    private _resolveLink(): void {
        if (!this._linkedSection && this.dataset.chipFor) {
            this._linkedSection = document.getElementById(this.dataset.chipFor);
        }
    }

    private _toggleExpand(): void {
        this._resolveLink();
        const section = this._linkedSection;
        if (!section) return;
        collapseAllChips(this.closest('chat-message'), this);
        if (section.classList.contains('turn-hidden')) {
            section.classList.remove('turn-hidden', 'collapsed');
            section.classList.add('chip-expanded');
            this.classList.add('chip-dimmed');
            this.setAttribute('aria-expanded', 'true');
        } else {
            this.classList.remove('chip-dimmed');
            section.classList.add('collapsing');
            setTimeout(() => {
                section.classList.remove('collapsing', 'chip-expanded');
                section.classList.add('turn-hidden', 'collapsed');
            }, 250);
            this.setAttribute('aria-expanded', 'false');
        }
    }

    linkSection(section: HTMLElement): void {
        this._linkedSection = section;
    }

    attributeChangedCallback(name: string): void {
        if (!this._init) return;
        if (name === 'status' || name === 'label') this._render();
    }
}
