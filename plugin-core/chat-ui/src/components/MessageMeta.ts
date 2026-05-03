const CHIP_TAGS = new Set(['TOOL-CHIP', 'THINKING-CHIP', 'SUBAGENT-CHIP']);

// Shared ResizeObserver — one for ALL MessageMeta strips, avoids per-instance overhead
const stripToMeta = new WeakMap<Element, MessageMeta>();
const sharedResizeObserver = new ResizeObserver((entries) => {
    for (const entry of entries) {
        stripToMeta.get(entry.target)?.scheduleNavUpdate();
    }
});

// Shared drag-scroll state — one set of document listeners for all instances
let activeDragMeta: { strip: HTMLElement; startX: number; scrollStart: number } | null = null;
document.addEventListener('mousemove', (e: MouseEvent) => {
    if (!activeDragMeta) return;
    activeDragMeta.strip.scrollLeft = activeDragMeta.scrollStart - (e.clientX - activeDragMeta.startX);
});
document.addEventListener('mouseup', () => {
    if (!activeDragMeta) return;
    activeDragMeta.strip.classList.remove('dragging');
    globalThis._bridge?.setCursor('grab');
    activeDragMeta = null;
});

export default class MessageMeta extends HTMLElement {
    private _init = false;
    private _strip: HTMLElement | null = null;
    private _navLeft: HTMLElement | null = null;
    private _navRight: HTMLElement | null = null;
    private _badge: HTMLElement | null = null;
    private _navRAF: number | null = null;

    connectedCallback(): void {
        if (this._init) return;
        this._init = true;
        this.classList.add('meta');

        // Collect existing chip children (e.g. from restoreBatch)
        const existingChips: HTMLElement[] = [];
        for (const child of Array.from(this.children)) {
            if (child instanceof HTMLElement && CHIP_TAGS.has(child.tagName)) {
                existingChips.push(child);
            }
        }

        this._navLeft = this._createNav('\u2039', -1);
        this._strip = document.createElement('div');
        this._strip.className = 'chip-strip';
        this._navRight = this._createNav('\u203A', 1);
        this._badge = document.createElement('span');
        this._badge.className = 'chip-overflow-count hidden';

        const append = Node.prototype.appendChild.bind(this);
        append(this._navLeft);
        append(this._strip);
        append(this._navRight);
        append(this._badge);

        // Thinking chips go outside the strip (always visible), tool chips go inside
        for (const chip of existingChips) {
            if (chip.tagName === 'THINKING-CHIP') {
                this.insertBefore(chip, this._navLeft);
            } else {
                this._strip.appendChild(chip);
            }
        }

        this._strip.addEventListener('scroll', () => this.scheduleNavUpdate(), {passive: true});
        stripToMeta.set(this._strip, this);
        sharedResizeObserver.observe(this._strip);
        this._initDragScroll(this._strip);
    }

    disconnectedCallback(): void {
        if (this._strip) {
            sharedResizeObserver.unobserve(this._strip);
            stripToMeta.delete(this._strip);
        }
        if (this._navRAF) {
            cancelAnimationFrame(this._navRAF);
            this._navRAF = null;
        }
        if (activeDragMeta?.strip === this._strip) activeDragMeta = null;
    }

    appendChild<T extends Node>(node: T): T {
        if (this._strip && node instanceof HTMLElement && CHIP_TAGS.has(node.tagName)) {
            // Thinking chips go before nav buttons (always visible), tool chips go in strip
            if (node.tagName === 'THINKING-CHIP') {
                this.insertBefore(node, this._navLeft);
            } else {
                this._strip.appendChild(node);
                this._scrollToEnd();
            }
            return node;
        }
        return super.appendChild(node);
    }

    /** Schedule a nav update coalesced via requestAnimationFrame. */
    scheduleNavUpdate(): void {
        if (this._navRAF) return;
        this._navRAF = requestAnimationFrame(() => {
            this._navRAF = null;
            this._updateNav();
        });
    }

    private _createNav(label: string, direction: number): HTMLElement {
        const btn = document.createElement('button');
        btn.className = 'chip-nav hidden';
        btn.textContent = label;
        btn.addEventListener('click', (e) => {
            e.stopPropagation();
            this._scrollBy(direction);
        });
        return btn;
    }

    private _scrollBy(direction: number): void {
        const strip = this._strip;
        if (!strip) return;
        const chips = Array.from(strip.children) as HTMLElement[];
        if (!chips.length) return;

        if (direction > 0) {
            const visibleRight = strip.scrollLeft + strip.clientWidth;
            const target = chips.find(c => c.offsetLeft + c.offsetWidth > visibleRight + 1);
            if (target) strip.scrollTo({left: target.offsetLeft, behavior: 'smooth'});
        } else {
            const target = [...chips].reverse().find(c => c.offsetLeft < strip.scrollLeft - 1);
            if (target) {
                const left = target.offsetLeft + target.offsetWidth - strip.clientWidth;
                strip.scrollTo({left: Math.max(0, left), behavior: 'smooth'});
            }
        }
    }

    private _scrollToEnd(): void {
        const strip = this._strip;
        if (!strip) return;
        // Two rAFs (Fix 9): let the mutation paint first, then write scrollLeft.
        // Behavior is read at execution time from chat-container's scrollBehavior, which
        // reflects both the user's smooth-scroll preference and streaming state ('auto'
        // during streaming, 'smooth' or 'auto' afterward per McpServerSettings).
        requestAnimationFrame(() => {
            requestAnimationFrame(() => {
                const behavior = ((document.querySelector('chat-container') as HTMLElement | null)
                    ?.style.scrollBehavior || 'auto') as ScrollBehavior;
                strip.scrollTo({left: strip.scrollWidth, behavior});
            });
        });
    }

    private _updateNav(): void {
        const strip = this._strip;
        if (!strip || !this._navLeft || !this._navRight || !this._badge) return;

        const canScrollLeft = strip.scrollLeft > 1;
        const canScrollRight = strip.scrollLeft + strip.clientWidth < strip.scrollWidth - 1;

        this._navLeft.classList.toggle('hidden', !canScrollLeft);
        this._navRight.classList.toggle('hidden', !canScrollRight);

        if (canScrollRight) {
            const visibleRight = strip.scrollLeft + strip.clientWidth;
            const chips = Array.from(strip.children) as HTMLElement[];
            const hiddenCount = chips.filter(c => c.offsetLeft >= visibleRight).length;
            if (hiddenCount > 0) {
                this._badge.textContent = '+' + hiddenCount;
                this._badge.classList.remove('hidden');
            } else {
                this._badge.classList.add('hidden');
            }
        } else {
            this._badge.classList.add('hidden');
        }
    }

    private _initDragScroll(strip: HTMLElement): void {
        strip.addEventListener('mousedown', (e: MouseEvent) => {
            if (e.button !== 0) return;
            activeDragMeta = {strip, startX: e.clientX, scrollStart: strip.scrollLeft};
            strip.classList.add('dragging');
            globalThis._bridge?.setCursor('grabbing');
            e.preventDefault();
        });
    }
}
