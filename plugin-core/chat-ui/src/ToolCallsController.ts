/**
 * Controller for the tool calls panel.
 *
 * <p>In the IDE (JCEF), Java pushes updates via {@code ToolCallsController.upsert(jsonStr)}.
 * In the PWA, the {@link ToolCallsView} polls {@code /tool-calls} and feeds data through
 * {@code ToolCallsController.setAll(items)}.
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
    id: number;
    title: string;
    toolName: string;
    kind?: string;
    status: string;
    timestamp: string;
    arguments: string;
    result: string;
    durationMs: number;
    hasHooks: boolean;
    hookStages?: HookStage[];
};

type Listener = () => void;

const _items = new Map<number, ToolCallData>();
const _listeners: Listener[] = [];

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
        _items.set(item.id, item);
        _notify();
    },

    /**
     * Replace the entire data set. Called by PWA after polling /tool-calls.
     */
    setAll(items: ToolCallData[]): void {
        _items.clear();
        for (const item of items) {
            _items.set(item.id, item);
        }
        _notify();
    },

    /**
     * Remove a tool call entry by ID.
     */
    remove(id: number): void {
        if (_items.delete(id)) _notify();
    },

    /**
     * Clear all entries.
     */
    clear(): void {
        _items.clear();
        _notify();
    },

    /**
     * Get all entries as an array, newest first.
     */
    getAll(): ToolCallData[] {
        return Array.from(_items.values()).sort((a, b) => b.id - a.id);
    },

    /**
     * Get a single entry by ID.
     */
    get(id: number): ToolCallData | undefined {
        return _items.get(id);
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
