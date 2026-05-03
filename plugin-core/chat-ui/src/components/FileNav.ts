/**
 * `<file-nav>` — Expandable directory tree navigation at the top of
 * the file viewer pane. Shows a breadcrumb path for the current file
 * and a collapsible dropdown listing the directory contents.
 */

export interface FileEntry {
    name: string;
    path: string;
    isDirectory: boolean;
    size?: number;
}

type FetchDirFn = (path: string) => Promise<FileEntry[]>;
type OpenFileFn = (path: string) => void;

export class FileNav extends HTMLElement {
    private _breadcrumb!: HTMLElement;
    private _dropdown!: HTMLElement;
    private _list!: HTMLElement;
    private _expanded = false;
    private _currentDir = '';
    private _fetchDir!: FetchDirFn;
    private _openFile!: OpenFileFn;
    private readonly _cache = new Map<string, FileEntry[]>();

    connectedCallback(): void {
        this.innerHTML = `
            <div class="fn-bar">
                <div class="fn-breadcrumb"></div>
            </div>
            <div class="fn-dropdown">
                <div class="fn-search-row">
                    <input class="fn-search" type="text" placeholder="Filter…" />
                </div>
                <div class="fn-list"></div>
            </div>`;

        this._breadcrumb = this.querySelector('.fn-breadcrumb') as HTMLElement;
        this._dropdown = this.querySelector('.fn-dropdown') as HTMLElement;
        this._list = this.querySelector('.fn-list') as HTMLElement;

        const search = this.querySelector('.fn-search') as HTMLInputElement;
        search.addEventListener('input', () => this._filterList(search.value));

        // Close dropdown when clicking outside
        document.addEventListener('click', (e) => {
            if (this._expanded && !this.contains(e.target as Node)) {
                this._closeDropdown();
            }
        });
    }

    /** Wire the data sources. Must be called after connectedCallback. */
    configure(fetchDir: FetchDirFn, openFile: OpenFileFn): void {
        this._fetchDir = fetchDir;
        this._openFile = openFile;
    }

    /** Update the breadcrumb to show a file path. Closes any open dropdown. */
    showPath(filePath: string): void {
        this._closeDropdown();
        this._currentDir = filePath.includes('/') ? filePath.substring(0, filePath.lastIndexOf('/')) : '';
        this._renderBreadcrumb(filePath);
    }

    private _renderBreadcrumb(filePath: string): void {
        if (!filePath) {
            this._breadcrumb.textContent = 'No file open';
            return;
        }
        const parts = filePath.split('/');
        this._breadcrumb.innerHTML = '';
        let accumulated = '';
        parts.forEach((part, i) => {
            if (i > 0) {
                accumulated += '/';
                const sep = document.createElement('span');
                sep.className = 'fn-sep';
                sep.textContent = '/';
                this._breadcrumb.appendChild(sep);
            }
            accumulated += part;
            const crumb = document.createElement('span');
            crumb.className = 'fn-crumb';
            crumb.textContent = part;
            if (i < parts.length - 1) {
                const dir = accumulated;
                crumb.classList.add('fn-clickable');
                crumb.addEventListener('click', () => this._navigateDir(dir));
            }
            this._breadcrumb.appendChild(crumb);
        });
    }

    toggleDropdown(): void {
        if (this._expanded) {
            this._closeDropdown();
        } else {
            this._expanded = true;
            this._dropdown.classList.add('fn-open');
            this._loadDir(this._currentDir);
        }
    }

    private _closeDropdown(): void {
        this._expanded = false;
        this._dropdown.classList.remove('fn-open');
    }

    private async _navigateDir(dir: string): Promise<void> {
        this._currentDir = dir;
        if (!this._expanded) {
            this._expanded = true;
            this._dropdown.classList.add('fn-open');
        }
        await this._loadDir(dir);
    }

    private async _loadDir(dir: string): Promise<void> {
        this._list.innerHTML = '<div class="fn-loading">Loading…</div>';

        let entries = this._cache.get(dir);
        if (!entries) {
            try {
                entries = await this._fetchDir(dir);
                this._cache.set(dir, entries);
            } catch {
                this._list.innerHTML = '<div class="fn-error">Failed to load directory</div>';
                return;
            }
        }

        this._renderEntries(entries, dir);
    }

    private _renderEntries(entries: FileEntry[], dir: string): void {
        this._list.innerHTML = '';

        // Parent directory link
        if (dir) {
            const parent = dir.includes('/') ? dir.substring(0, dir.lastIndexOf('/')) : '';
            const up = this._createEntry('..', parent, true);
            this._list.appendChild(up);
        }

        // Sort: directories first, then alphabetical
        const sorted = [...entries].sort((a, b) => {
            if (a.isDirectory !== b.isDirectory) return a.isDirectory ? -1 : 1;
            return a.name.localeCompare(b.name);
        });

        for (const entry of sorted) {
            const el = this._createEntry(entry.name, entry.path, entry.isDirectory);
            this._list.appendChild(el);
        }
    }

    private _createEntry(name: string, path: string, isDir: boolean): HTMLElement {
        const el = document.createElement('div');
        el.className = 'fn-entry' + (isDir ? ' fn-dir' : ' fn-file');
        el.dataset.path = path;
        el.dataset.name = name.toLowerCase();

        const icon = isDir ? '📁' : this._fileIcon(name);
        el.innerHTML = `<span class="fn-icon">${icon}</span><span class="fn-name">${this._escHtml(name)}</span>`;

        el.addEventListener('click', () => {
            if (isDir) {
                this._navigateDir(path);
            } else {
                this._openFile(path);
                this._closeDropdown();
            }
        });

        return el;
    }

    private _filterList(query: string): void {
        const q = query.toLowerCase();
        this._list.querySelectorAll('.fn-entry').forEach(el => {
            const name = (el as HTMLElement).dataset.name ?? '';
            (el as HTMLElement).style.display = (!q || name.includes(q) || name === '..') ? '' : 'none';
        });
    }

    private _fileIcon(name: string): string {
        const ext = name.includes('.') ? name.substring(name.lastIndexOf('.') + 1).toLowerCase() : '';
        const icons: Record<string, string> = {
            java: '☕', kt: '🇰', kts: '🇰', py: '🐍',
            ts: '📘', tsx: '📘', js: '📒', jsx: '📒',
            json: '📋', yaml: '📋', yml: '📋', xml: '📋',
            md: '📝', css: '🎨', html: '🌐', htm: '🌐',
            sh: '⚙️', bash: '⚙️', gradle: '🐘',
            sql: '🗄️', go: '🔵', rs: '🦀', svg: '🖼️',
        };
        return icons[ext] ?? '📄';
    }

    private _escHtml(s: string): string {
        return s.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;');
    }
}
