export default class ChatContainer extends HTMLElement {
    private _init = false;
    private _autoScroll = true;
    private _messages!: HTMLDivElement;
    private _scrollRAF: number | null = null;
    private _observer!: MutationObserver;
    private _copyObs!: MutationObserver;
    private _prevScrollY = 0;
    private _programmaticScroll = false;

    connectedCallback(): void {
        if (this._init) return;
        this._init = true;
        this._autoScroll = true;
        this._messages = document.createElement('div');
        this._messages.id = 'messages';
        this.appendChild(this._messages);

        window.addEventListener('scroll', () => {
            // Ignore scroll events caused by our own scrollTo calls
            if (this._programmaticScroll) {
                this._programmaticScroll = false;
                this._prevScrollY = window.scrollY;
                return;
            }
            const atBottom = window.innerHeight + window.scrollY >= document.body.scrollHeight - 40;
            if (atBottom) {
                this._autoScroll = true;
            } else if (window.scrollY < this._prevScrollY) {
                // User intentionally scrolled up — disable auto-scroll
                this._autoScroll = false;
            }
            this._prevScrollY = window.scrollY;
        });

        // When the panel resizes (tool calls expand/collapse), re-anchor to bottom if we were there
        window.addEventListener('resize', () => {
            if (this._autoScroll) {
                this._programmaticScroll = true;
                window.scrollTo(0, document.body.scrollHeight);
            }
        });

        // Auto-scroll when children change (debounced via rAF)
        this._observer = new MutationObserver(() => {
            if (!this._scrollRAF) {
                this._scrollRAF = requestAnimationFrame(() => {
                    this._scrollRAF = null;
                    this.scrollIfNeeded();
                });
            }
        });
        this._observer.observe(this._messages, {childList: true, subtree: true, characterData: true});

        // Copy-button observer
        this._copyObs = new MutationObserver(() => {
            this._messages.querySelectorAll('pre:not([data-copy-btn]):not(.streaming)').forEach(pre => {
                (pre as HTMLElement).dataset.copyBtn = '1';
                const btn = document.createElement('button');
                btn.className = 'copy-btn';
                btn.innerHTML = '<svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><rect x="5.5" y="5.5" width="9" height="9" rx="1.5"/><path d="M3.5 10.5H3a1.5 1.5 0 0 1-1.5-1.5V3A1.5 1.5 0 0 1 3 1.5h6A1.5 1.5 0 0 1 10.5 3v.5"/></svg>';
                btn.title = 'Copy';
                btn.onclick = () => {
                    const code = pre.querySelector('code');
                    navigator.clipboard.writeText(code ? code.textContent! : pre.textContent!).then(() => {
                        btn.innerHTML = '<svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="3.5 8.5 6.5 11.5 12.5 4.5"/></svg>';
                        btn.title = 'Copied!';
                        setTimeout(() => {
                            btn.innerHTML = '<svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><rect x="5.5" y="5.5" width="9" height="9" rx="1.5"/><path d="M3.5 10.5H3a1.5 1.5 0 0 1-1.5-1.5V3A1.5 1.5 0 0 1 3 1.5h6A1.5 1.5 0 0 1 10.5 3v.5"/></svg>';
                            btn.title = 'Copy';
                        }, 1500);
                    });
                };
                pre.prepend(btn);
            });
        });
        this._copyObs.observe(this._messages, {childList: true, subtree: true});
    }

    get messages(): HTMLDivElement {
        return this._messages;
    }

    scrollIfNeeded(): void {
        if (this._autoScroll) {
            this._programmaticScroll = true;
            window.scrollTo(0, document.body.scrollHeight);
        }
    }

    forceScroll(): void {
        this._autoScroll = true;
        this._programmaticScroll = true;
        window.scrollTo(0, document.body.scrollHeight);
    }

    disconnectedCallback(): void {
        this._observer?.disconnect();
        this._copyObs?.disconnect();
    }
}
