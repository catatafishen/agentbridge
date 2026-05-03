import {PollableView} from './PollableView';

type PromptItem = {
    id: string;
    text: string;
    timestamp: string;
};

export class SearchView extends PollableView {
    private _input!: HTMLInputElement;
    private _list!: HTMLElement;
    private _empty!: HTMLElement;
    private _items: PromptItem[] = [];

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
    }

    disconnectedCallback(): void {
        super.disconnectedCallback();
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
        if (this.toggleEmptyState(this._empty, this._list, visible.length === 0)) return;

        this._list.innerHTML = visible.map(item => `
            <button class="pv-item" data-id="${this.escAttr(item.id)}" type="button">
                <span class="pv-time">${this.esc(this._formatTime(item.timestamp))}</span>
                <span class="pv-text">${this.esc(item.text)}</span>
            </button>`).join('');
    }

    private _formatTime(timestamp: string): string {
        if (!timestamp) return '';
        const date = new Date(timestamp);
        if (Number.isNaN(date.getTime())) return timestamp;
        return date.toLocaleString([], {month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit'});
    }
}
