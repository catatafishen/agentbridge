type TodoItem = {
    id: string;
    title: string;
    description: string | null;
    status: string;
    updatedAt: string | null;
};

export class TodoView extends HTMLElement {
    private _planContent!: HTMLElement;
    private _planEmpty!: HTMLElement;
    private _dbContent!: HTMLElement;
    private _dbEmpty!: HTMLElement;
    private _pollTimer: number | null = null;
    private _lastPlanContent: string | null = null;
    private _lastTodoJson = '';

    connectedCallback(): void {
        this.innerHTML = `
            <div class="tv-container">
                <section class="tv-section">
                    <h2>Plan</h2>
                    <div class="tv-empty tv-plan-empty">No plan file yet. The agent creates plan.md during complex tasks.</div>
                    <div class="tv-content tv-plan-content"></div>
                </section>
                <section class="tv-section">
                    <h2>Todo Database</h2>
                    <div class="tv-empty tv-db-empty">No structured todo database for this session.</div>
                    <div class="tv-db-content"></div>
                </section>
            </div>`;
        this._planContent = this.querySelector<HTMLElement>('.tv-plan-content')!;
        this._planEmpty = this.querySelector<HTMLElement>('.tv-plan-empty')!;
        this._dbContent = this.querySelector<HTMLElement>('.tv-db-content')!;
        this._dbEmpty = this.querySelector<HTMLElement>('.tv-db-empty')!;
        void this.refresh();
        this._pollTimer = globalThis.setInterval(() => void this.refresh(), 2000);
    }

    disconnectedCallback(): void {
        if (this._pollTimer) clearInterval(this._pollTimer);
    }

    async refresh(): Promise<void> {
        await Promise.all([this._refreshPlan(), this._refreshTodos()]);
    }

    private async _refreshPlan(): Promise<void> {
        try {
            const resp = await fetch('/plan');
            if (!resp.ok) {
                console.error(`[AB] Plan fetch failed: HTTP ${resp.status}`);
                return;
            }
            const data = await resp.json() as { content: string | null };
            if (data.content === this._lastPlanContent) return;
            this._lastPlanContent = data.content;

            if (!data.content) {
                this._planEmpty.style.display = '';
                this._planContent.style.display = 'none';
                return;
            }
            this._planEmpty.style.display = 'none';
            this._planContent.style.display = '';
            this._planContent.innerHTML = this._renderMarkdown(data.content);
        } catch (error) {
            console.error('[AB] Plan refresh failed:', error);
        }
    }

    private async _refreshTodos(): Promise<void> {
        try {
            const resp = await fetch('/todos');
            if (!resp.ok) {
                console.error(`[AB] Todo fetch failed: HTTP ${resp.status}`);
                return;
            }
            const data = await resp.json() as { items: TodoItem[] };
            const json = JSON.stringify(data.items);
            if (json === this._lastTodoJson) return;
            this._lastTodoJson = json;
            this._renderTodos(data.items);
        } catch (error) {
            console.error('[AB] Todo refresh failed:', error);
        }
    }

    private _renderTodos(items: TodoItem[]): void {
        if (items.length === 0) {
            this._dbEmpty.style.display = '';
            this._dbContent.style.display = 'none';
            return;
        }
        this._dbEmpty.style.display = 'none';
        this._dbContent.style.display = '';
        const done = items.filter(item => item.status === 'done').length;
        this._dbContent.innerHTML = `
            <div class="tv-db-summary">${done} / ${items.length} completed</div>
            ${items.map(item => this._renderTodoItem(item)).join('')}`;
    }

    private _renderTodoItem(item: TodoItem): string {
        const status = item.status || 'pending';
        const cls = status.replaceAll(/[^a-z0-9_-]/g, '-');
        const description = item.description ? `<div class="tv-db-desc">${this._esc(item.description)}</div>` : '';
        return `<div class="tv-db-item ${cls}">
            <span class="tv-db-status">${this._statusIcon(status)}</span>
            <span class="tv-db-main">
                <span class="tv-db-title">${this._esc(item.title)}</span>
                ${description}
            </span>
            <span class="tv-db-id">${this._esc(item.id)}</span>
        </div>`;
    }

    private _statusIcon(status: string): string {
        switch (status) {
            case 'in_progress':
                return '▶';
            case 'done':
                return '✓';
            case 'blocked':
                return '✖';
            default:
                return '○';
        }
    }

    private _renderMarkdown(md: string): string {
        const out: string[] = [];
        let inCodeBlock = false;

        for (const line of md.split('\n')) {
            if (line.startsWith('```')) {
                out.push(inCodeBlock ? '</code></pre>' : '<pre><code>');
                inCodeBlock = !inCodeBlock;
            } else if (inCodeBlock) {
                out.push(this._esc(line) + '\n');
            } else {
                out.push(this._renderMarkdownLine(line));
            }
        }

        if (inCodeBlock) out.push('</code></pre>');
        return out.join('\n');
    }

    private _renderMarkdownLine(line: string): string {
        const headingMatch = /^(#{1,6})\s+(.*)/.exec(line);
        if (headingMatch) {
            const level = headingMatch[1].length;
            return `<h${level}>${this._inline(headingMatch[2])}</h${level}>`;
        }

        const checkMatch = /^(\s*[-*])\s+\[([ xX])]\s+(.*)/.exec(line);
        if (checkMatch) {
            const checked = checkMatch[2] !== ' ';
            const cls = checked ? 'tv-done' : 'tv-pending';
            return `<div class="tv-check ${cls}">${checked ? '☑' : '☐'} ${this._inline(checkMatch[3])}</div>`;
        }

        const listMatch = /^(\s*[-*])\s+(.*)/.exec(line);
        if (listMatch) {
            return `<div class="tv-li">• ${this._inline(listMatch[2])}</div>`;
        }

        if (line.trim() === '') return '<br>';
        return `<p>${this._inline(line)}</p>`;
    }

    private _inline(text: string): string {
        let s = this._esc(text);
        s = s.replaceAll(/\*\*(.+?)\*\*/g, '<strong>$1</strong>');
        s = s.replaceAll(/\*(.+?)\*/g, '<em>$1</em>');
        s = s.replaceAll(/`(.+?)`/g, '<code>$1</code>');
        return s;
    }

    private _esc(s: string): string {
        return s.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('"', '&quot;');
    }
}
