export default class ChatContainer extends HTMLElement {
    private _init = false;
    private _autoScroll = true;
    private _restoring = false; // true while initial history batch is being inserted
    private _messages!: HTMLDivElement;
    private _workingIndicator!: HTMLElement;
    private _scrollRAF: number | null = null;
    private _copyRAF: number | null = null;
    private _observer!: MutationObserver;
    private _copyObs!: MutationObserver;
    private _prevScrollTop = 0;
    private _onScroll: (() => void) | null = null;
    private _onWheel: ((e: WheelEvent) => void) | null = null;
    private _onTouchStart: ((e: TouchEvent) => void) | null = null;
    private _onTouchMove: ((e: TouchEvent) => void) | null = null;
    private _onTouchEnd: (() => void) | null = null;
    private _touchStartY = 0;
    private _wheelRAF: number | null = null;
    private _resizeObs: ResizeObserver | null = null;

    connectedCallback(): void {
        if (this._init) return;
        this._init = true;
        this._autoScroll = true;
        this._messages = document.createElement('div');
        this._messages.id = 'messages';
        this.appendChild(this._messages);

        this._workingIndicator = document.createElement('working-indicator');
        this.appendChild(this._workingIndicator);

        // Wheel events are user-initiated — never fired by programmatic scrollTop changes.
        // Using wheel (not scroll) eliminates the programmatic-vs-manual race condition.
        this._onWheel = (e: WheelEvent) => {
            if (this._autoScroll) {
                // Only disable on upward scroll — the user wants to read earlier content.
                // Scrolling down while already at bottom keeps autoscroll on (no-op).
                if (e.deltaY < 0) {
                    this._autoScroll = false;
                    globalThis._bridge?.autoScrollDisabled?.();
                }
                return;
            }
            // Autoscroll is off: check if user scrolled back to the bottom.
            if (!this._wheelRAF) {
                this._wheelRAF = requestAnimationFrame(() => {
                    this._wheelRAF = null;
                    if (!this._autoScroll && this._isAtBottom()) {
                        this._autoScroll = true;
                        globalThis._bridge?.autoScrollEnabled?.();
                    }
                });
            }
        };
        this.addEventListener('wheel', this._onWheel, {passive: true});

        // Touch events: on mobile/tablet, wheel events don't fire for finger scrolling.
        // Track touch gestures to disable autoscroll when dragging upward.
        this._onTouchStart = (e: TouchEvent) => {
            this._touchStartY = e.touches[0].clientY;
        };
        this.addEventListener('touchstart', this._onTouchStart, {passive: true});

        this._onTouchMove = (e: TouchEvent) => {
            const currentY = e.touches[0].clientY;
            if (this._autoScroll && currentY > this._touchStartY + 10) {
                // Finger dragged downward → user wants to scroll up (see earlier content)
                this._autoScroll = false;
                globalThis._bridge?.autoScrollDisabled?.();
            }
            this._touchStartY = currentY;
        };
        this.addEventListener('touchmove', this._onTouchMove, {passive: true});

        this._onTouchEnd = () => {
            if (!this._autoScroll && this._isAtBottom()) {
                this._autoScroll = true;
                globalThis._bridge?.autoScrollEnabled?.();
            }
        };
        this.addEventListener('touchend', this._onTouchEnd, {passive: true});

        // Scroll handler: only used for load-more trigger at the top.
        this._onScroll = () => {
            if (this.scrollTop < this._prevScrollTop && this.scrollTop <= 30) {
                const lm = this._messages.querySelector<HTMLElement>('load-more:not([loading])');
                if (lm) {
                    lm.click();
                }
            }
            this._prevScrollTop = this.scrollTop;
        };
        this.addEventListener('scroll', this._onScroll);

        // When the container or its content resizes, re-anchor to bottom if auto-scrolling.
        // Debounced via rAF to avoid synchronous forced-reflow tearing in JCEF OSR.
        this._resizeObs = new ResizeObserver(() => {
            if (this._autoScroll && !this._restoring && !this._scrollRAF) {
                this._scrollRAF = requestAnimationFrame(() => {
                    this._scrollRAF = null;
                    this.scrollIfNeeded();
                });
            }
        });
        this._resizeObs.observe(this);
        this._resizeObs.observe(this._messages);

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

        // Copy & wrap & scratch button observer — debounced via rAF
        this._copyObs = new MutationObserver(() => {
            if (!this._copyRAF) {
                this._copyRAF = requestAnimationFrame(() => {
                    this._copyRAF = null;
                    this._setupCodeBlocks();
                });
            }
        });
        this._copyObs.observe(this._messages, {childList: true, subtree: true});
    }

    private _setupCodeBlocks(): void {
        this._messages.querySelectorAll('pre:not([data-copy-btn])').forEach(pre => {
            // Skip pre elements inside streaming bubbles — innerHTML is
            // rebuilt on every token, so injected buttons would be destroyed
            // immediately, creating a MutationObserver → DOM churn loop that
            // causes JCEF OSR tearing. Buttons are added after finalize().
            if (pre.closest('message-bubble[streaming]')) return;
            (pre as HTMLElement).dataset.copyBtn = '1';

            // Language label from data-lang attribute on <code>
            const codeEl = pre.querySelector('code');
            const lang = codeEl?.dataset.lang || '';
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
                navigator.clipboard.writeText(code ? code.textContent ?? '' : pre.textContent ?? '').then(
                    () => this._resetCopyButton(copyBtn)
                );
            };

            // Open in scratch file button
            const scratchBtn = document.createElement('button');
            scratchBtn.className = 'code-action-btn scratch-btn';
            scratchBtn.innerHTML = '<svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M9 1.5H4a1.5 1.5 0 0 0-1.5 1.5v10A1.5 1.5 0 0 0 4 14.5h8a1.5 1.5 0 0 0 1.5-1.5V6L9 1.5z"/><polyline points="9 1.5 9 6 13.5 6"/></svg>';
            scratchBtn.title = 'Open in scratch file';
            scratchBtn.onclick = () => {
                const code = pre.querySelector('code');
                const text = code ? code.textContent ?? '' : pre.textContent ?? '';
                const codeLang = code?.dataset.lang || '';
                globalThis._bridge?.openScratch(codeLang, text);
            };

            // Insert buttons: scratch first (leftmost), then wrap, then copy (rightmost)
            const toolbar = document.createElement('div');
            toolbar.className = 'code-actions';
            toolbar.append(scratchBtn, wrapBtn, copyBtn);
            pre.prepend(toolbar);
        });
    }

    private _resetCopyButton(btn: HTMLButtonElement): void {
        btn.innerHTML = '<svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="3.5 8.5 6.5 11.5 12.5 4.5"/></svg>';
        btn.title = 'Copied!';
        setTimeout(() => {
            btn.innerHTML = '<svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><rect x="5.5" y="5.5" width="9" height="9" rx="1.5"/><path d="M3.5 10.5H3a1.5 1.5 0 0 1-1.5-1.5V3A1.5 1.5 0 0 1 3 1.5h6A1.5 1.5 0 0 1 10.5 3v.5"/></svg>';
            btn.title = 'Copy';
        }, 1500);
    }

    get messages(): HTMLDivElement {
        return this._messages;
    }

    get workingIndicator(): HTMLElement {
        return this._workingIndicator;
    }

    get autoScroll(): boolean {
        return this._autoScroll;
    }

    set autoScroll(enabled: boolean) {
        this._autoScroll = enabled;
        if (enabled) {
            this.scrollTop = this.scrollHeight;
        }
    }

    /**
     * Re-enables the autoscroll flag without forcing a jump to the bottom.
     * Use after programmatic navigation (e.g. scrollToEntry) so that future
     * incoming messages still scroll into view, while leaving the current
     * viewport position intact.
     */
    resumeAutoScroll(): void {
        this._autoScroll = true;
    }

    /**
     * Scroll to an exact position instantly, bypassing CSS scroll-behavior.
     *
     * When scroll-behavior is 'smooth', setting scrollTop starts an animation
     * that can be interrupted by the next MutationObserver/ResizeObserver-driven
     * scrollIfNeeded() call, causing endless stutter as animations keep restarting
     * without reaching the target. Forcing 'auto' here makes every programmatic
     * autoscroll atomic and prevents the feedback loop.
     */
    private _scrollToInstant(top: number): void {
        const prev = this.style.scrollBehavior;
        this.style.scrollBehavior = 'auto';
        this.scrollTop = top;
        this.style.scrollBehavior = prev;
    }

    scrollIfNeeded(): void {
        if (this._autoScroll && !this._restoring) {
            this._scrollToInstant(this.scrollHeight);
        }
    }

    private _isAtBottom(): boolean {
        return this.scrollHeight - this.scrollTop - this.clientHeight < 30;
    }

    /** Snap to the bottom instantly after programmatic inserts or restore completion. */
    forceScroll(): void {
        this._scrollToInstant(this.scrollHeight);
    }

    /** Suppress auto-scroll while initial history batch is being inserted. */
    pauseAutoScrollForRestore(): void {
        this._restoring = true;
    }

    /** Re-enable auto-scroll after initial history batch has been rendered. */
    stopAutoScrollRestore(): void {
        this._restoring = false;
    }

    compensateScroll(targetY: number): void {
        this._scrollToInstant(targetY);
    }

    /**
     * Control CSS scroll-behavior based on streaming state.
     *
     * During streaming: always 'auto' (instant) so the autoScroll setter jumps instantly.
     * After streaming: restore the user preference so explicit user-initiated scrolling
     * (for example via the web app's "scroll to bottom" control) may stay smooth.
     *
     * Note: scrollIfNeeded(), forceScroll(), and compensateScroll() always use instant scrolling
     * via _scrollToInstant() regardless of this CSS property.
     */
    setStreaming(active: boolean, smoothScrollEnabled: boolean): void {
        this.style.scrollBehavior = active ? 'auto' : (smoothScrollEnabled ? 'smooth' : 'auto');
    }

    disconnectedCallback(): void {
        this._observer?.disconnect();
        this._copyObs?.disconnect();
        this._resizeObs?.disconnect();
        if (this._onScroll) this.removeEventListener('scroll', this._onScroll);
        if (this._onWheel) this.removeEventListener('wheel', this._onWheel);
        if (this._onTouchStart) this.removeEventListener('touchstart', this._onTouchStart);
        if (this._onTouchMove) this.removeEventListener('touchmove', this._onTouchMove);
        if (this._onTouchEnd) this.removeEventListener('touchend', this._onTouchEnd);
        if (this._wheelRAF) {
            cancelAnimationFrame(this._wheelRAF);
            this._wheelRAF = null;
        }
        if (this._scrollRAF) {
            cancelAnimationFrame(this._scrollRAF);
            this._scrollRAF = null;
        }
        if (this._copyRAF) {
            cancelAnimationFrame(this._copyRAF);
            this._copyRAF = null;
        }
    }
}
