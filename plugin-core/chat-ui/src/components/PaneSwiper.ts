/**
 * `<pane-swiper>` — N-pane horizontal container with touch swipe,
 * tab bar navigation, dot indicators, and programmatic switching.
 *
 * All panes are always mounted — switching is purely visual via
 * CSS transform on the inner track.
 *
 * Backward-compatible: `leftPane` and `rightPane` getters alias panes[0] and panes[1].
 */

const SWIPE_THRESHOLD = 50;   // px minimum to trigger pane switch
const VELOCITY_THRESHOLD = 0.3; // px/ms — fast flick overrides distance

export class PaneSwiper extends HTMLElement {
    private _track!: HTMLElement;
    private _dots!: HTMLElement;
    private _tabBar!: HTMLElement;
    private readonly _panes: HTMLElement[] = [];
    private _tabLabels: string[] = [];

    /** Currently visible pane index */
    private _activeIndex = 1;

    // Touch tracking
    private _startX = 0;
    private _startY = 0;
    private _startTime = 0;
    private _tracking = false;
    private _horizontal = false;
    private _currentOffset = 0;
    private _scrollerEl: HTMLElement | null = null;

    connectedCallback(): void {
        this.innerHTML = `
            <div class="ps-tab-bar"></div>
            <div class="ps-track"></div>
            <div class="ps-dots"></div>`;
        this._tabBar = this.querySelector('.ps-tab-bar') as HTMLElement;
        this._track = this.querySelector('.ps-track') as HTMLElement;
        this._dots = this.querySelector('.ps-dots') as HTMLElement;

        // Create the two default panes for backward compatibility
        this._addPaneInternal('ps-left');
        this._addPaneInternal('ps-right');

        this._updateTrackWidth();
        this._applyTransform(false);
        this._rebuildDots();
        this._rebuildTabs();

        // Touch events for swipe
        this.addEventListener('touchstart', this._onTouchStart.bind(this), {passive: true});
        this.addEventListener('touchmove', this._onTouchMove.bind(this), {passive: false});
        this.addEventListener('touchend', this._onTouchEnd.bind(this), {passive: true});

        // Dot click
        this._dots.addEventListener('click', (e) => {
            const dot = (e.target as HTMLElement).closest('.ps-dot') as HTMLElement | null;
            if (dot?.dataset.index != null) this.switchTo(Number(dot.dataset.index));
        });

        // Tab click
        this._tabBar.addEventListener('click', (e) => {
            const tab = (e.target as HTMLElement).closest('.ps-tab') as HTMLElement | null;
            if (tab?.dataset.index != null) this.switchTo(Number(tab.dataset.index));
        });
    }

    // ── Public API ──────────────────────────────────────────────────────

    /** Backward-compat alias for panes[0] */
    get leftPane(): HTMLElement {
        return this._panes[0];
    }

    /** Backward-compat alias for panes[1] */
    get rightPane(): HTMLElement {
        return this._panes[1];
    }

    /** All pane elements in order */
    get panes(): readonly HTMLElement[] {
        return this._panes;
    }

    get paneCount(): number {
        return this._panes.length;
    }

    get activeIndex(): number {
        return this._activeIndex;
    }

    /**
     * Add a new pane and return its container div.
     * The pane is appended to the end of the track.
     */
    addPane(label?: string): HTMLElement {
        const pane = this._addPaneInternal();
        if (label) this._tabLabels[this._panes.length - 1] = label;
        this._updateTrackWidth();
        this._rebuildDots();
        this._rebuildTabs();
        return pane;
    }

    /**
     * Set the tab label for a pane at the given index.
     */
    setTabLabel(index: number, label: string): void {
        if (index < 0 || index >= this._panes.length) return;
        this._tabLabels[index] = label;
        this._rebuildTabs();
    }

    /** Switch to pane by index. */
    switchTo(index: number): void {
        if (index < 0 || index >= this._panes.length) return;
        this._activeIndex = index;
        this._applyTransform(true);
        this._updateDots();
        this._updateTabs();
        this.dispatchEvent(new CustomEvent('pane-changed', {detail: {index}}));
    }

    // ── Internal helpers ────────────────────────────────────────────────

    private _addPaneInternal(extraClass?: string): HTMLElement {
        const pane = document.createElement('div');
        pane.className = 'ps-pane' + (extraClass ? ` ${extraClass}` : '');
        this._track.appendChild(pane);
        this._panes.push(pane);
        this._tabLabels.push('');
        return pane;
    }

    private _updateTrackWidth(): void {
        // Track width = N * 100% of the container
        this._track.style.width = `${this._panes.length * 100}%`;
        // Each pane takes 1/N of the track
        this._panes.forEach(p => {
            p.style.width = `${100 / this._panes.length}%`;
        });
    }

    private _applyTransform(animate: boolean): void {
        this._track.style.transition = animate ? 'transform 0.3s cubic-bezier(0.4, 0, 0.2, 1)' : 'none';
        // Each pane is (100/N)% of the track, so offset = index * (100/N)%
        const pct = -this._activeIndex * (100 / this._panes.length);
        this._track.style.transform = `translateX(${pct}%)`;
    }

    private _rebuildDots(): void {
        this._dots.innerHTML = this._panes
            .map((_, i) => `<span class="ps-dot${i === this._activeIndex ? ' active' : ''}" data-index="${i}"></span>`)
            .join('');
    }

    private _updateDots(): void {
        this._dots.querySelectorAll('.ps-dot').forEach((dot, i) => {
            dot.classList.toggle('active', i === this._activeIndex);
        });
    }

    private _rebuildTabs(): void {
        this._tabBar.innerHTML = this._panes
            .map((_, i) => {
                const label = this._tabLabels[i] || `Pane ${i + 1}`;
                const active = i === this._activeIndex ? ' active' : '';
                return `<button class="ps-tab${active}" data-index="${i}">${label}</button>`;
            })
            .join('');
    }

    private _updateTabs(): void {
        this._tabBar.querySelectorAll('.ps-tab').forEach((tab, i) => {
            tab.classList.toggle('active', i === this._activeIndex);
        });
    }

    // ── Touch handling ──────────────────────────────────────────────────

    private _onTouchStart(e: TouchEvent): void {
        if (e.touches.length !== 1) return;
        this._startX = e.touches[0].clientX;
        this._startY = e.touches[0].clientY;
        this._startTime = Date.now();
        this._tracking = true;
        this._horizontal = false;
        this._currentOffset = 0;
        this._scrollerEl = this._findHorizontalScroller(e.target as HTMLElement | null);
    }

    private _onTouchMove(e: TouchEvent): void {
        if (!this._tracking || e.touches.length !== 1) return;
        const dx = e.touches[0].clientX - this._startX;
        const dy = e.touches[0].clientY - this._startY;

        // Lock direction after first significant movement
        if (!this._horizontal && Math.abs(dy) > Math.abs(dx) && Math.abs(dy) > 10) {
            this._tracking = false; // Vertical scroll — let it pass through
            return;
        }
        if (Math.abs(dx) > 10) {
            this._horizontal = true;
        }
        if (!this._horizontal) return;

        // Inside a horizontally scrollable element — defer to native scroll
        // unless already scrolled all the way in the swipe direction
        if (this._scrollerEl) {
            const el = this._scrollerEl;
            const atLeftEdge = el.scrollLeft <= 0;
            const atRightEdge = el.scrollLeft + el.clientWidth >= el.scrollWidth - 1;

            if (dx > 0 && !atLeftEdge) {
                this._tracking = false;
                return;
            }
            if (dx < 0 && !atRightEdge) {
                this._tracking = false;
                return;
            }
            this._scrollerEl = null;
        }

        e.preventDefault();

        // Clamp: don't drag past the first or last pane
        const paneWidth = this.offsetWidth;
        const maxIndex = this._panes.length - 1;
        const baseOffset = -this._activeIndex * paneWidth;
        let targetOffset = baseOffset + dx;
        targetOffset = Math.max(targetOffset, -maxIndex * paneWidth); // don't go past last pane
        targetOffset = Math.min(targetOffset, 0);                     // don't go past first pane

        // Convert pixel offset to percentage of track width
        const trackWidth = paneWidth * this._panes.length;
        const pct = (targetOffset / trackWidth) * 100;
        this._track.style.transition = 'none';
        this._track.style.transform = `translateX(${pct}%)`;
        this._currentOffset = dx;
    }

    private _onTouchEnd(): void {
        if (!this._tracking || !this._horizontal) {
            this._tracking = false;
            return;
        }
        this._tracking = false;

        const elapsed = Date.now() - this._startTime;
        const velocity = Math.abs(this._currentOffset) / Math.max(elapsed, 1);
        const dx = this._currentOffset;
        const maxIndex = this._panes.length - 1;

        if (dx > SWIPE_THRESHOLD || (dx > 20 && velocity > VELOCITY_THRESHOLD)) {
            // Swiped right → go to previous pane
            if (this._activeIndex > 0) this._activeIndex--;
        } else if (dx < -SWIPE_THRESHOLD || (dx < -20 && velocity > VELOCITY_THRESHOLD)) {
            // Swiped left → go to next pane
            if (this._activeIndex < maxIndex) this._activeIndex++;
        }
        this._applyTransform(true);
        this._updateDots();
        this._updateTabs();
        this.dispatchEvent(new CustomEvent('pane-changed', {detail: {index: this._activeIndex}}));
    }

    /** Find the nearest ancestor with horizontal overflow, or null. */
    private _findHorizontalScroller(target: HTMLElement | null): HTMLElement | null {
        let el = target;
        while (el && el !== this) {
            if (el.scrollWidth > el.clientWidth + 1) return el;
            el = el.parentElement;
        }
        return null;
    }
}
