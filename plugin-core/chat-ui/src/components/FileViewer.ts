/**
 * `<file-viewer>` — Read-only file content viewer with line numbers,
 * syntax highlighting, and Markdown rendering.
 *
 * PWA-only component: in JCEF, file links open directly in the IDE editor.
 */

import {detectLanguage, highlight} from '../syntaxHighlight';
import {renderMarkdown} from '../renderMarkdown';

export class FileViewer extends HTMLElement {
    private _nav!: HTMLElement;
    private _content!: HTMLElement;
    private _emptyState!: HTMLElement;
    private _pathDisplay!: HTMLElement;
    private _currentPath = '';

    /** Recently opened file paths (newest first). */
    private _recent: string[] = [];
    private _recentList!: HTMLElement;

    connectedCallback(): void {
        this.innerHTML = `
            <div class="fv-container">
                <div class="fv-nav-slot"></div>
                <div class="fv-toolbar">
                    <span class="fv-path"></span>
                    <button class="fv-copy-btn" title="Copy path" hidden>📋</button>
                </div>
                <div class="fv-content-area">
                    <div class="fv-empty">
                        <div class="fv-empty-icon">📂</div>
                        <div class="fv-empty-title">File Viewer</div>
                        <div class="fv-empty-hint">Click a file link in the chat or browse files above</div>
                        <div class="fv-recent-list"></div>
                    </div>
                    <div class="fv-content" hidden></div>
                </div>
                <button class="fv-fab" title="Browse files" aria-label="Browse files">
                    <svg viewBox="0 0 24 24" fill="currentColor" width="24" height="24">
                        <path d="M10 4H2v16h20V6H12l-2-2z"/>
                    </svg>
                </button>
            </div>`;

        this._nav = this.querySelector('.fv-nav-slot') as HTMLElement;
        this._content = this.querySelector('.fv-content') as HTMLElement;
        this._emptyState = this.querySelector('.fv-empty') as HTMLElement;
        this._pathDisplay = this.querySelector('.fv-path') as HTMLElement;
        this._recentList = this.querySelector('.fv-recent-list') as HTMLElement;

        const copyBtn = this.querySelector('.fv-copy-btn') as HTMLButtonElement;
        copyBtn.addEventListener('click', () => {
            navigator.clipboard.writeText(this._currentPath);
            copyBtn.textContent = '✓';
            setTimeout(() => {
                copyBtn.textContent = '📋';
            }, 1500);
        });
    }

    /** The slot where the `<file-nav>` should be inserted. */
    get navSlot(): HTMLElement {
        return this._nav;
    }

    /** The FAB button — call this after connectedCallback to wire the FileNav toggle. */
    wireFab(fileNav: { toggleDropdown(): void }): void {
        const fab = this.querySelector('.fv-fab') as HTMLButtonElement;
        fab.addEventListener('click', (e) => {
            e.stopPropagation();
            fileNav.toggleDropdown();
        });
    }

    get currentPath(): string {
        return this._currentPath;
    }

    /** Display file content with syntax highlighting or Markdown rendering. */
    showFile(path: string, content: string): void {
        this._currentPath = path;
        this._pathDisplay.textContent = path;
        const copyBtn = this.querySelector('.fv-copy-btn') as HTMLElement;
        copyBtn.hidden = false;

        this._addToRecent(path);

        const lang = detectLanguage(path);

        if (lang === 'markdown') {
            this._content.innerHTML = `<div class="fv-markdown">${renderMarkdown(content)}</div>`;
        } else {
            const lines = content.split('\n');
            const highlighted = lang ? highlight(content, lang) : this._escHtml(content);
            const highlightedLines = highlighted.split('\n');

            const gutterHtml = lines.map((_, i) =>
                `<span class="fv-ln">${i + 1}</span>`
            ).join('\n');

            const codeHtml = highlightedLines.join('\n');

            this._content.innerHTML = `
                <div class="fv-code-wrap">
                    <pre class="fv-gutter">${gutterHtml}</pre>
                    <pre class="fv-code"><code>${codeHtml}</code></pre>
                </div>`;
        }

        this._emptyState.hidden = true;
        this._content.hidden = false;
        this._content.scrollTop = 0;
    }

    /** Scroll to a specific line number. */
    scrollToLine(line: number): void {
        const ln = this._content.querySelector(`.fv-ln:nth-child(${line})`);
        if (ln) {
            ln.scrollIntoView({block: 'center', behavior: 'smooth'});
            // Briefly highlight the target line
            const idx = line - 1;
            const codeLines = this._content.querySelectorAll('.fv-code code');
            if (codeLines.length > 0) {
                const codePre = this._content.querySelector('.fv-code') as HTMLElement;
                if (codePre) {
                    const lineHeight = Number.parseFloat(getComputedStyle(codePre).lineHeight) || 18;
                    const highlight = document.createElement('div');
                    highlight.className = 'fv-line-highlight';
                    highlight.style.top = `${idx * lineHeight}px`;
                    highlight.style.height = `${lineHeight}px`;
                    const wrap = this._content.querySelector('.fv-code-wrap');
                    wrap?.appendChild(highlight);
                    setTimeout(() => highlight.remove(), 2000);
                }
            }
        }
    }

    /** Reset to empty state. */
    clear(): void {
        this._currentPath = '';
        this._pathDisplay.textContent = '';
        this._content.hidden = true;
        this._emptyState.hidden = false;
        (this.querySelector('.fv-copy-btn') as HTMLElement).hidden = true;
    }

    private _addToRecent(path: string): void {
        this._recent = [path, ...this._recent.filter(p => p !== path)].slice(0, 10);
        this._renderRecent();
    }

    private _renderRecent(): void {
        if (this._recent.length === 0) {
            this._recentList.innerHTML = '';
            return;
        }
        this._recentList.innerHTML = `
            <div class="fv-recent-title">Recent files</div>
            ${this._recent.map(p => {
            const name = p.includes('/') ? p.substring(p.lastIndexOf('/') + 1) : p;
            return `<div class="fv-recent-item" data-path="${this._escAttr(p)}">
                    <span class="fv-recent-name">${this._escHtml(name)}</span>
                    <span class="fv-recent-path">${this._escHtml(p)}</span>
                </div>`;
        }).join('')}`;

        this._recentList.querySelectorAll('.fv-recent-item').forEach(el => {
            el.addEventListener('click', () => {
                const path = (el as HTMLElement).dataset.path;
                if (path) this.dispatchEvent(new CustomEvent('open-file', {detail: {path}}));
            });
        });
    }

    private _escHtml(s: string): string {
        return s.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;');
    }

    private _escAttr(s: string): string {
        return s.replaceAll('&', '&amp;').replaceAll('"', '&quot;').replaceAll('<', '&lt;');
    }
}
