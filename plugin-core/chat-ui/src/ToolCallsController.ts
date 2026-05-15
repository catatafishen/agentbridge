/**
 * Controller for the tool calls panel.
 *
 * <p>In the IDE (JCEF), Java pushes updates via {@code ToolCallsController.upsert(jsonStr)}.
 * In the PWA, the {@link ToolCallsView} polls {@code /tool-calls} and feeds data through
 * {@code ToolCallsController.setAll(items)}.
 *
 * <p>Historic tool calls (loaded from the conversation DB on startup) are prepended via
 * {@code ToolCallsController.prependHistoric(jsonStr)}. These have string event-id keys
 * and a {@code historic: true} flag.
 *
 * <p>Listeners (i.e., the ToolCallsView web component) register via {@link onChange} and
 * get notified whenever the data set changes.
 */

export type HookStage = {
    trigger: string;
    scriptName: string;
    outcome: string;
    durationMs: number;
    detail?: string;
};

export type ToolCallData = {
    id: number | string;
    title: string;
    toolName: string;
    kind?: string;
    status: string;
    timestamp: string;
    arguments: string;
    /** Pre-hook arguments JSON; present only when a pre-hook modified the tool input. */
    originalArguments?: string;
    result: string;
    durationMs: number;
    hasHooks: boolean;
    hookStages?: HookStage[];
    /** True for entries loaded from the conversation DB (not from the live session). */
    historic?: boolean;
};

type Listener = () => void;

const _items = new Map<string, ToolCallData>();
const _listeners: Listener[] = [];
let _historyExhausted = false;

function _notify(): void {
    for (const fn of _listeners) fn();
}

const ToolCallsController = {
    /**
     * Insert or update a single tool call entry. Called by Java via executeJavaScript.
     * Accepts a JSON string or an object.
     */
    upsert(data: string | ToolCallData): void {
        const item: ToolCallData = typeof data === 'string' ? JSON.parse(data) : data;
        _items.set(String(item.id), item);
        _notify();
    },

    /**
     * Prepend a historic tool call entry (loaded from the conversation DB).
     * Does not notify listeners — call {@link notifyChange} after a batch of prepends.
     */
    prependHistoric(data: string | ToolCallData): void {
        const item: ToolCallData = typeof data === 'string' ? JSON.parse(data) : data;
        item.historic = true;
        const key = String(item.id);
        if (!_items.has(key)) {
            _items.set(key, item);
        }
    },

    /**
     * Signal that all available history has been loaded (no more pages).
     */
    setHistoryExhausted(exhausted: boolean): void {
        _historyExhausted = exhausted;
        _notify();
    },

    /**
     * Whether all available historic tool calls have been loaded.
     */
    isHistoryExhausted(): boolean {
        return _historyExhausted;
    },

    /**
     * Manually fire change listeners (e.g. after a batch of prependHistoric calls).
     */
    notifyChange(): void {
        _notify();
    },

    /**
     * Replace the entire data set. Called by PWA after polling /tool-calls.
     */
    setAll(items: ToolCallData[]): void {
        _items.clear();
        for (const item of items) {
            _items.set(String(item.id), item);
        }
        _notify();
    },

    /**
     * Remove a tool call entry by ID.
     */
    remove(id: number | string): void {
        if (_items.delete(String(id))) _notify();
    },

    /**
     * Clear live entries only — preserves historic entries and the history-exhaustion flag.
     */
    clear(): void {
        for (const [key, item] of _items) {
            if (!item.historic) _items.delete(key);
        }
        _notify();
    },

    /**
     * Get all entries as an array, sorted by timestamp descending (newest first).
     */
    getAll(): ToolCallData[] {
        return Array.from(_items.values()).sort((a, b) => {
            if (a.timestamp < b.timestamp) return 1;
            if (a.timestamp > b.timestamp) return -1;
            return 0;
        });
    },

    /**
     * Get a single entry by ID.
     */
    get(id: number | string): ToolCallData | undefined {
        return _items.get(String(id));
    },

    /**
     * Returns the event ID of the oldest historic entry, for cursor-based pagination.
     */
    oldestHistoricId(): string | null {
        let oldest: ToolCallData | null = null;
        for (const item of _items.values()) {
            if (item.historic && (!oldest || item.timestamp < oldest.timestamp)) {
                oldest = item;
            }
        }
        return oldest ? String(oldest.id) : null;
    },

    /**
     * Register a change listener. Returns an unsubscribe function.
     */
    onChange(fn: Listener): () => void {
        _listeners.push(fn);
        return () => {
            const idx = _listeners.indexOf(fn);
            if (idx >= 0) _listeners.splice(idx, 1);
        };
    },
};

export default ToolCallsController;
