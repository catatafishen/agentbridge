export default class ChatContainer extends HTMLElement {
    private _init = false;
    private _autoScroll = true;
    private _messages!: HTMLDivElement;
    private _scrollRAF: number | null = null;
    private _observer!: MutationObserver;
    private _copyObs!: MutationObserver;
    private _prevScrollY = 0;
    private _programmaticScroll = false;
    private _onScroll: (() => void) | null = null;
    private _onResize: (() => void) | null = null;

    connectedCallback(): void {
        if (this._init) return;
        this._init = true;
        this._autoScroll = true;
        this._messages = document.createElement('div');
        this._messages.id = 'messages';
        this.appendChild(this._messages);

        this._onScroll = () => {
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
                // Trigger load-more when scrolled near the top
                if (window.scrollY <= 50) {
                    const lm = this._messages.querySelector('load-more:not([loading])');
                    if (lm) (lm as HTMLElement).click();
                }
            }
            this._prevScrollY = window.scrollY;
        };
        window.addEventListener('scroll', this._onScroll);

        // When the panel resizes (tool calls expand/collapse), re-anchor to bottom if we were there
        this._onResize = () => {
            if (this._autoScroll) {
                this._programmaticScroll = true;
                window.scrollTo(0, document.body.scrollHeight);
            }
        };
        window.addEventListener('resize', this._onResize);

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

        // Copy & wrap & scratch button observer
        this._copyObs = new MutationObserver(() => {
            this._messages.querySelectorAll('pre:not([data-copy-btn]):not(.streaming)').forEach(pre => {
                (pre as HTMLElement).dataset.copyBtn = '1';

                // Language label from data-lang attribute on <code>
                const codeEl = pre.querySelector('code');
                const lang = codeEl?.getAttribute('data-lang') || '';
                if (lang) {
                    const langLabel = document.createElement('span');
                    langLabel.className = 'code-lang-label';
                    langLabel.textContent = lang;
                    pre.prepend(langLabel);
                }

                // Wrap toggle button
                const wrapBtn = document.createElement('button');
                wrapBtn.className = 'code-action-btn wrap-btn';
                wrapBtn.innerHTML = '<svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M3 4h10M3 8h7a2 2 0 0 1 0 4H8"/><polyline points="9.5 10.5 8 12 9.5 13.5"/></svg>';
                wrapBtn.title = 'Toggle word wrap';
                wrapBtn.onclick = () => {
                    pre.classList.toggle('word-wrap');
                    wrapBtn.classList.toggle('active', pre.classList.contains('word-wrap'));
                };

                // Copy button
                const copyBtn = document.createElement('button');
                copyBtn.className = 'code-action-btn copy-btn';
                copyBtn.innerHTML = '<svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><rect x="5.5" y="5.5" width="9" height="9" rx="1.5"/><path d="M3.5 10.5H3a1.5 1.5 0 0 1-1.5-1.5V3A1.5 1.5 0 0 1 3 1.5h6A1.5 1.5 0 0 1 10.5 3v.5"/></svg>';
                copyBtn.title = 'Copy';
                copyBtn.onclick = () => {
                    const code = pre.querySelector('code');
                    navigator.clipboard.writeText(code ? code.textContent! : pre.textContent!).then(() => {
                        copyBtn.innerHTML = '<svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="3.5 8.5 6.5 11.5 12.5 4.5"/></svg>';
                        copyBtn.title = 'Copied!';
                        setTimeout(() => {
                            copyBtn.innerHTML = '<svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><rect x="5.5" y="5.5" width="9" height="9" rx="1.5"/><path d="M3.5 10.5H3a1.5 1.5 0 0 1-1.5-1.5V3A1.5 1.5 0 0 1 3 1.5h6A1.5 1.5 0 0 1 10.5 3v.5"/></svg>';
                            copyBtn.title = 'Copy';
                        }, 1500);
                    });
                };

                // Open in scratch file button
                const scratchBtn = document.createElement('button');
                scratchBtn.className = 'code-action-btn scratch-btn';
                scratchBtn.innerHTML = '<svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M9 1.5H4a1.5 1.5 0 0 0-1.5 1.5v10A1.5 1.5 0 0 0 4 14.5h8a1.5 1.5 0 0 0 1.5-1.5V6L9 1.5z"/><polyline points="9 1.5 9 6 13.5 6"/></svg>';
                scratchBtn.title = 'Open in scratch file';
                scratchBtn.onclick = () => {
                    const code = pre.querySelector('code');
                    const text = code ? code.textContent! : pre.textContent!;
                    const codeLang = code?.getAttribute('data-lang') || '';
                    globalThis._bridge?.openScratch(codeLang, text);
                };

                // Insert buttons: scratch first (leftmost), then wrap, then copy (rightmost)
                const toolbar = document.createElement('div');
                toolbar.className = 'code-actions';
                toolbar.append(scratchBtn, wrapBtn, copyBtn);
                pre.prepend(toolbar);
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
        if (this._onScroll) window.removeEventListener('scroll', this._onScroll);
        if (this._onResize) window.removeEventListener('resize', this._onResize);
        if (this._scrollRAF) {
            cancelAnimationFrame(this._scrollRAF);
            this._scrollRAF = null;
        }
    }
}
