import {collapseAllChips} from '../helpers';

export default class ThinkingChip extends HTMLElement {
    static get observedAttributes(): string[] {
        return ['status'];
    }

    private _init = false;
    _linkedSection: HTMLElement | null = null;

    connectedCallback(): void {
        if (this._init) return;
        this._init = true;
        this.classList.add('turn-chip');
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
        const status = this.getAttribute('status') || 'complete';
        this.innerHTML = '<span class="thought-bubble">💭</span> Thought';
        if (status === 'running' || status === 'thinking') {
            this.classList.add('thinking-active');
        } else {
            this.classList.remove('thinking-active');
        }
    }

    attributeChangedCallback(name: string): void {
        if (!this._init) return;
        if (name === 'status') {
            // CSS-only toggle — avoids innerHTML replacement and the childList DOM mutation
            // that would trigger ChatContainer's MutationObserver → spurious scroll deferral.
            const status = this.getAttribute('status') || 'complete';
            this.classList.toggle('thinking-active', status === 'running' || status === 'thinking');
        }
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
            section.classList.remove('turn-hidden');
            section.classList.add('chip-expanded');
            this.classList.add('chip-dimmed');
            this.setAttribute('aria-expanded', 'true');
        } else {
            this.classList.remove('chip-dimmed');
            section.classList.add('collapsing');
            setTimeout(() => {
                section.classList.remove('collapsing', 'chip-expanded');
                section.classList.add('turn-hidden');
            }, 250);
            this.setAttribute('aria-expanded', 'false');
        }
    }

    linkSection(section: HTMLElement): void {
        this._linkedSection = section;
    }
}
