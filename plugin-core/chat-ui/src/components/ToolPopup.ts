/**
 * Singleton floating popup for tool call details.
 * Anchored near the clicked chip — no inline layout shift.
 */
let instance: ToolPopup | null = null;

export function showToolPopup(chip: HTMLElement, section: HTMLElement): void {
    if (!instance) {
        instance = document.createElement('tool-popup') as ToolPopup;
        document.body.appendChild(instance);
    }
    instance.show(chip, section);
}

export function dismissToolPopup(): void {
    instance?.dismiss();
}

export function isToolPopupVisibleFor(chip: HTMLElement): boolean {
    return instance?.isVisibleFor(chip) ?? false;
}

export default class ToolPopup extends HTMLElement {
    private _init = false;
    private _activeChip: HTMLElement | null = null;
    private _onClickOutside = (e: MouseEvent) => this._handleClickOutside(e);
    private _onKeyDown = (e: KeyboardEvent) => this._handleKeyDown(e);
    private _onScroll = () => this._reposition();
    private _onResizeMove: ((e: MouseEvent) => void) | null = null;
    private _onResizeUp: (() => void) | null = null;
    private _onDragMove: ((e: MouseEvent) => void) | null = null;
    private _onDragUp: (() => void) | null = null;
    private _dragged = false;

    connectedCallback(): void {
        if (this._init) return;
        this._init = true;
        this.classList.add('tool-popup', 'tool-popup-hidden');
        this.innerHTML = `
            <div class="tool-popup-header">
                <span class="tool-popup-title"></span>
                <span class="tool-popup-close" role="button" tabindex="0" aria-label="Close">\u00d7</span>
            </div>
            <div class="tool-popup-body">
                <div class="tool-popup-params"></div>
                <div class="tool-popup-result"></div>
            </div>
            <div class="tool-popup-resize"></div>`;
        this.querySelector('.tool-popup-close')!.addEventListener('click', () => this.dismiss());
        this._initDrag();
        this._initResize();
    }

    private _initDrag(): void {
        const header = this.querySelector('.tool-popup-header')! as HTMLElement;
        header.addEventListener('mousedown', (e: Event) => {
            const me = e as MouseEvent;
            // Don't drag when clicking the close button
            if ((me.target as HTMLElement).closest('.tool-popup-close')) return;
            me.preventDefault();
            me.stopPropagation();
            const startX = me.clientX;
            const startY = me.clientY;
            const startLeft = this.offsetLeft;
            const startTop = this.offsetTop;
            this._dragged = true;
            header.style.cursor = 'grabbing';
            globalThis._bridge?.setCursor('grabbing');

            this._onDragMove = (ev: MouseEvent) => {
                const left = startLeft + (ev.clientX - startX);
                const top = startTop + (ev.clientY - startY);
                this.style.left = left + 'px';
                this.style.top = top + 'px';
            };
            this._onDragUp = () => {
                header.style.cursor = '';
                globalThis._bridge?.setCursor('default');
                if (this._onDragMove) document.removeEventListener('mousemove', this._onDragMove);
                if (this._onDragUp) document.removeEventListener('mouseup', this._onDragUp);
                this._onDragMove = null;
                this._onDragUp = null;
            };
            document.addEventListener('mousemove', this._onDragMove);
            document.addEventListener('mouseup', this._onDragUp);
        });
    }

    private _initResize(): void {
        const grip = this.querySelector('.tool-popup-resize')!;
        grip.addEventListener('mousedown', (e: Event) => {
            const me = e as MouseEvent;
            me.preventDefault();
            me.stopPropagation();
            const startX = me.clientX;
            const startY = me.clientY;
            const startW = this.offsetWidth;
            const startH = this.offsetHeight;
            globalThis._bridge?.setCursor('nwse-resize');

            this._onResizeMove = (ev: MouseEvent) => {
                const w = Math.max(220, startW + (ev.clientX - startX));
                const h = Math.max(120, startH + (ev.clientY - startY));
                this.style.width = w + 'px';
                this.style.maxHeight = h + 'px';
                this.style.height = h + 'px';
            };
            this._onResizeUp = () => {
                globalThis._bridge?.setCursor('default');
                if (this._onResizeMove) document.removeEventListener('mousemove', this._onResizeMove);
                if (this._onResizeUp) document.removeEventListener('mouseup', this._onResizeUp);
                this._onResizeMove = null;
                this._onResizeUp = null;
            };
            document.addEventListener('mousemove', this._onResizeMove);
            document.addEventListener('mouseup', this._onResizeUp);
        });
    }

    show(chip: HTMLElement, section: HTMLElement): void {
        // If clicking the same chip, toggle off
        if (this._activeChip === chip && !this.classList.contains('tool-popup-hidden')) {
            this.dismiss();
            return;
        }

        // Deactivate previous chip
        if (this._activeChip) {
            this._activeChip.style.opacity = '1';
            this._activeChip.setAttribute('aria-expanded', 'false');
        }

        // Populate content from the linked section
        const title = section.getAttribute('title') || '';
        this.querySelector('.tool-popup-title')!.textContent = title;

        const paramsEl = section.querySelector('.tool-params');
        const resultEl = section.querySelector('.tool-result');
        const popupParams = this.querySelector('.tool-popup-params')!;
        const popupResult = this.querySelector('.tool-popup-result')!;

        popupParams.innerHTML = paramsEl?.innerHTML || '';
        popupResult.innerHTML = resultEl?.innerHTML || '';

        // Apply kind color class from chip — also reset custom size and drag state
        this.className = 'tool-popup';
        this._dragged = false;
        this.style.width = '';
        this.style.height = '';
        this.style.maxHeight = '';
        const kindClass = Array.from(chip.classList).find(c => c.startsWith('kind-'));
        if (kindClass) this.classList.add(kindClass);
        if (section.classList.contains('failed')) this.classList.add('failed');

        // Activate
        this._activeChip = chip;
        chip.style.opacity = '0.5';
        chip.setAttribute('aria-expanded', 'true');

        this.classList.remove('tool-popup-hidden');
        this._reposition();

        // Listeners
        setTimeout(() => {
            document.addEventListener('click', this._onClickOutside, true);
            document.addEventListener('keydown', this._onKeyDown, true);
            const scroller = document.querySelector('chat-container');
            scroller?.addEventListener('scroll', this._onScroll, {passive: true});
        }, 0);
    }

    dismiss(): void {
        if (this._activeChip) {
            this._activeChip.style.opacity = '1';
            this._activeChip.setAttribute('aria-expanded', 'false');
            this._activeChip = null;
        }
        this.classList.add('tool-popup-hidden');
        document.removeEventListener('click', this._onClickOutside, true);
        document.removeEventListener('keydown', this._onKeyDown, true);
        const scroller = document.querySelector('chat-container');
        scroller?.removeEventListener('scroll', this._onScroll);
    }

    isVisibleFor(chip: HTMLElement): boolean {
        return this._activeChip === chip && !this.classList.contains('tool-popup-hidden');
    }

    private _reposition(): void {
        if (!this._activeChip || this.classList.contains('tool-popup-hidden')) return;
        if (this._dragged) return;
        const chipRect = this._activeChip.getBoundingClientRect();
        const popupHeight = this.offsetHeight;
        const viewportH = window.innerHeight;

        // Horizontal: align left edge with chip, but clamp to viewport
        let left = chipRect.left;
        const maxLeft = window.innerWidth - this.offsetWidth - 8;
        if (left > maxLeft) left = maxLeft;
        if (left < 8) left = 8;

        // Vertical: prefer above the chip, fall back to below
        let top: number;
        if (chipRect.top - popupHeight - 6 > 8) {
            top = chipRect.top - popupHeight - 6;
        } else if (chipRect.bottom + popupHeight + 6 < viewportH) {
            top = chipRect.bottom + 6;
        } else {
            top = Math.max(8, viewportH - popupHeight - 8);
        }

        this.style.left = left + 'px';
        this.style.top = top + 'px';
    }

    private _handleClickOutside(e: MouseEvent): void {
        if (this.contains(e.target as Node)) return;
        if (this._activeChip?.contains(e.target as Node)) return;
        this.dismiss();
    }

    private _handleKeyDown(e: KeyboardEvent): void {
        if (e.key === 'Escape') {
            e.preventDefault();
            this.dismiss();
        }
    }
}
