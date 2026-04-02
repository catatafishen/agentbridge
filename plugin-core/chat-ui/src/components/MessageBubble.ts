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
        if (!this._renderPending) {
            this._renderPending = true;
            requestAnimationFrame(() => {
                this._renderPending = false;
                this.innerHTML = renderMarkdown(this._rawText);
            });
        }
    }

    /**
     * Replace the streaming content with the fully server-rendered HTML.
     * Called by ChatController.finalizeAgentText once the Kotlin side has
     * produced the authoritative HTML (with file-path links, git SHA links, etc.).
     */
    finalize(html: string): void {
        this.removeAttribute('streaming');
        this._rawText = '';
        this._renderPending = false;
        this.innerHTML = html;
    }

    get content(): string {
        return this.innerHTML;
    }

    attributeChangedCallback(_name: string): void {
        // No DOM setup needed — streaming state is tracked via _rawText/_renderPending.
    }
}
