/**
 * Base class for side-panel views that poll an endpoint at a regular interval.
 * Subclasses implement {@link refresh} and call {@link toggleEmptyState} for
 * the common empty/list visibility toggle.
 *
 * Extracts the activate/deactivate timer lifecycle and HTML escaping so each
 * concrete view only contains its own rendering logic.
 */
export abstract class PollableView extends HTMLElement {
    private _pollTimer: number | null = null;
    private readonly _intervalMs: number;

    protected constructor(intervalMs = 3000) {
        super();
        this._intervalMs = intervalMs;
    }

    disconnectedCallback(): void {
        this.deactivate();
    }

    activate(): void {
        void this.refresh();
        this._pollTimer ??= globalThis.setInterval(() => void this.refresh(), this._intervalMs);
    }

    deactivate(): void {
        if (this._pollTimer != null) {
            clearInterval(this._pollTimer);
            this._pollTimer = null;
        }
    }

    abstract refresh(): Promise<void>;

    /**
     * Shows {@code emptyEl} and hides {@code listEl} when {@code isEmpty} is
     * true, and vice-versa. Returns {@code isEmpty} for convenient chaining.
     */
    protected toggleEmptyState(emptyEl: HTMLElement, listEl: HTMLElement, isEmpty: boolean): boolean {
        emptyEl.style.display = isEmpty ? '' : 'none';
        listEl.style.display = isEmpty ? 'none' : '';
        return isEmpty;
    }

    protected esc(s: string): string {
        return s.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;');
    }

    protected escAttr(s: string): string {
        return this.esc(s).replaceAll('"', '&quot;');
    }
}
