export default class QuickReplies extends HTMLElement {
    static get observedAttributes(): string[] {
        return ['disabled'];
    }

    private _init = false;

    connectedCallback(): void {
        if (this._init) return;
        this._init = true;
        this.classList.add('quick-replies');
    }

    /** Valid semantic color suffixes for quick-reply buttons. */
    private static readonly COLORS = new Set(['danger', 'primary', 'success', 'warning']);

    set options(arr: string[]) {
        this.innerHTML = '';
        (arr || []).forEach(raw => {
            const {label, color} = QuickReplies.parseOption(raw);
            const btn = document.createElement('button');
            btn.type = 'button';
            btn.className = 'quick-reply-btn' + (color ? ` qr-${color}` : '');
            btn.textContent = label;
            btn.onclick = () => {
                if (this.hasAttribute('disabled')) return;
                this.setAttribute('disabled', '');
                this.dispatchEvent(new CustomEvent('quick-reply', {detail: {text: label}, bubbles: true}));
            };
            this.appendChild(btn);
        });
    }

    /**
     * Parse "Label:color" suffix. Only recognized semantic colors are stripped;
     * colons in the label text itself are preserved.
     */
    private static parseOption(raw: string): { label: string; color: string | null } {
        const idx = raw.lastIndexOf(':');
        if (idx > 0) {
            const candidate = raw.substring(idx + 1).trim().toLowerCase();
            if (QuickReplies.COLORS.has(candidate)) {
                return {label: raw.substring(0, idx).trim(), color: candidate};
            }
        }
        return {label: raw, color: null};
    }

    attributeChangedCallback(name: string): void {
        if (name === 'disabled') this.classList.toggle('disabled', this.hasAttribute('disabled'));
    }
}
