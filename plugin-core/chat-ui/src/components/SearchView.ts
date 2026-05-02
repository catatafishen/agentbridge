type PromptItem = {
    id: string;
    text: string;
    timestamp: string;
};

export class SearchView extends HTMLElement {
    private _input!: HTMLInputElement;
    private _list!: HTMLElement;
    private _empty!: HTMLElement;
    private _items: PromptItem[] = [];
    private _pollTimer: number | null = null;

    connectedCallback(): void {
        this.innerHTML = `
            <div class="pv-container">
                <input class="pv-search" type="search" placeholder="Search prompts…" aria-label="Search prompts">
                <div class="pv-empty">No prompts yet</div>
                <div class="pv-list"></div>
            </div>`;
        this._input = this.querySelector<HTMLInputElement>('.pv-search')!;
        this._list = this.querySelector<HTMLElement>('.pv-list')!;
        this._empty = this.querySelector<HTMLElement>('.pv-empty')!;
        this._input.addEventListener('input', () => this._render());
        this._list.addEventListener('click', (event) => {
            const row = (event.target as HTMLElement).closest<HTMLElement>('.pv-item');
            const id = row?.dataset.id;
            if (!id) return;
            document.getElementById(id)?.scrollIntoView({block: 'center', behavior: 'smooth'});
        });
        void this.refresh();
        this._pollTimer = globalThis.setInterval(() => void this.refresh(), 3000);
    }

    disconnectedCallback(): void {
        if (this._pollTimer) clearInterval(this._pollTimer);
    }

    async refresh(): Promise<void> {
        try {
            const resp = await fetch('/prompts');
            if (!resp.ok) {
                console.error(`[AB] Prompt fetch failed: HTTP ${resp.status}`);
                return;
            }
            const data = await resp.json() as { items: PromptItem[] };
            this._items = data.items;
            this._render();
        } catch (error) {
            console.error('[AB] Failed to refresh prompts:', error);
        }
    }

    private _render(): void {
        const query = this._input.value.trim().toLowerCase();
        const filtered = query
            ? this._items.filter(item => item.text.toLowerCase().includes(query))
            : this._items;
        const visible = filtered.slice(-100).reverse();
        if (visible.length === 0) {
            this._empty.style.display = '';
            this._list.style.display = 'none';
            return;
        }
        this._empty.style.display = 'none';
        this._list.style.display = '';
        this._list.innerHTML = visible.map(item => `
            <button class="pv-item" data-id="${this._esc(item.id)}" type="button">
                <span class="pv-time">${this._esc(this._formatTime(item.timestamp))}</span>
                <span class="pv-text">${this._esc(item.text)}</span>
            </button>`).join('');
    }

    private _formatTime(timestamp: string): string {
        if (!timestamp) return '';
        const date = new Date(timestamp);
        if (Number.isNaN(date.getTime())) return timestamp;
        return date.toLocaleString([], {month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit'});
    }

    private _esc(s: string): string {
        return s.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('"', '&quot;');
    }
}
