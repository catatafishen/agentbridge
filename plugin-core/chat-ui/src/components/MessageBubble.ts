import {collapseAllChips} from '../helpers';

export default class MessageBubble extends HTMLElement {
    static get observedAttributes(): string[] {
        return ['streaming', 'type'];
    }

    private _init = false;
    private _pre: HTMLPreElement | null = null;

    connectedCallback(): void {
        if (this._init) return;
        this._init = true;
        const parent = this.closest('chat-message');
        const isUser = parent?.getAttribute('type') === 'user';
        this.classList.add(isUser ? 'prompt-bubble' : 'agent-bubble');

        if (isUser) {
            this.setAttribute('tabindex', '0');
            this.setAttribute('role', 'button');
            this.setAttribute('aria-label', 'Toggle message details');
        }
        this.onclick = (e: MouseEvent) => {
            if ((e.target as Element).closest('a,.turn-chip')) return;
            collapseAllChips(parent);
            const meta = parent?.querySelector('message-meta');
            if (meta) meta.classList.toggle('show');
        };

        if (this.hasAttribute('streaming')) this._setupStreaming();
    }

    private _setupStreaming(): void {
        if (!this._pre) {
            this._pre = document.createElement('pre');
            this._pre.className = 'streaming';
            this.innerHTML = '';
            this.appendChild(this._pre);
        }
    }

    appendStreamingText(text: string): void {
        if (!this._pre) this._setupStreaming();
        // Append a new text node instead of `textContent +=` to avoid O(n²) behaviour:
        // textContent= reads all existing content then replaces it, making each token
        // append O(n) in total accumulated length. With long responses this saturates
        // the renderer thread and causes the whole JCEF panel to appear frozen.
        this._pre!.appendChild(document.createTextNode(text));
    }

    finalize(html: string): void {
        this.removeAttribute('streaming');
        this._pre = null;
        this.innerHTML = html;
    }

    get content(): string {
        return this.innerHTML;
    }

    attributeChangedCallback(name: string): void {
        if (name === 'streaming' && this._init) {
            if (this.hasAttribute('streaming')) {
                this._setupStreaming();
            }
        }
    }
}
