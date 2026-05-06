import {collapseAllChips} from '../helpers';
import {renderMarkdown} from '../renderMarkdown';

export default class MessageBubble extends HTMLElement {
    static get observedAttributes(): string[] {
        return ['streaming', 'type'];
    }

    private _init = false;
    /** Accumulated raw Markdown text during streaming. */
    private _rawText = '';
    /** True while a requestAnimationFrame re-render is pending. */
    private _renderPending = false;
    /** Handle for the pending rAF, so finalize() can cancel it before overwriting innerHTML. */
    private _rafHandle: number | null = null;

    connectedCallback(): void {
        if (this._init) return;
        this._init = true;
        const parent = this.closest('chat-message');
        const isUser = parent?.getAttribute('type') === 'user';
        this.classList.add(isUser ? 'prompt-bubble' : 'agent-bubble');

        if (isUser) {
            this.onclick = (e: MouseEvent) => {
                if ((e.target as Element).closest('a,.turn-chip')) return;
                collapseAllChips(parent);
            };
        }
    }

    /**
     * Append a streaming text token and schedule a Markdown re-render via
     * requestAnimationFrame.  Batching re-renders to at most one per frame
     * avoids the O(n²) cost of naïve textContent-replacement while still
     * providing a smooth, readable streaming experience.
     */
    appendStreamingText(text: string): void {
        this._rawText += text;
        // Synchronous append keeps textContent up-to-date between frames so
        // callers can always read text content immediately.
        this.appendChild(document.createTextNode(text));
        if (!this._renderPending) {
            this._renderPending = true;
            this._rafHandle = requestAnimationFrame(() => {
                this._rafHandle = null;
                this._renderPending = false;
                this.innerHTML = renderMarkdown(this._rawText);
            });
        }
    }

    /**
     * Replace the streaming content with the fully server-rendered HTML.
     * Called by ChatController.finalizeAgentText once the Kotlin side has
     * produced the authoritative HTML (with file-path links, git SHA links, etc.).
     * Cancels any pending rAF to prevent it from overwriting the final HTML with renderMarkdown('').
     */
    finalize(html: string): void {
        this.removeAttribute('streaming');
        this._rawText = '';
        this._renderPending = false;
        if (this._rafHandle !== null) {
            cancelAnimationFrame(this._rafHandle);
            this._rafHandle = null;
        }
        this.innerHTML = html;
    }

    /**
     * True while a rAF re-render is queued but hasn't fired yet.
     * ChatContainer reads this to avoid writing scrollTop in the same paint frame as
     * the rAF-render — see Fix 11 in SCREEN-TEARING-BUG.md.
     */
    get renderPending(): boolean {
        return this._renderPending;
    }

    get content(): string {
        return this.innerHTML;
    }

    attributeChangedCallback(_name: string): void {
        // No DOM setup needed — streaming state is tracked via _rawText/_renderPending.
    }
}
