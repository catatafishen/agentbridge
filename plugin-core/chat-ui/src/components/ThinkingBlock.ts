export default class ThinkingBlock extends HTMLElement {
    private _init = false;

    connectedCallback(): void {
        if (this._init) return;
        this._init = true;
        this.classList.add('thinking-section');
        if (!this.querySelector('.thinking-content')) {
            this.innerHTML = `<div class="thinking-content"></div>`;
        }
    }

    get contentEl(): Element | null {
        return this.querySelector('.thinking-content');
    }

    appendText(text: string): void {
        const el = this.contentEl;
        if (el) el.textContent += text;
    }

    finalize(): void {
        const el = this.contentEl;
        if (el && !el.textContent?.trim()) {
            el.textContent = 'No reasoning returned';
        }
        this.removeAttribute('active');
    }
}
