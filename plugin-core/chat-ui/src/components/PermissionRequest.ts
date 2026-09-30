/**
 * Permission request actions element — rendered below the question bubble.
 * Contains a parameter detail section plus Deny / Allow / Allow for session buttons.
 * Replaced with result text on resolve.
 *
 * Attributes:
 *   req-id   — unique request ID passed back to the bridge on respond
 *   args     — JSON object of tool call parameters (optional)
 */
export default class PermissionRequest extends HTMLElement {
    private _init = false;

    static get observedAttributes(): string[] {
        return ['resolved', 'args'];
    }

    connectedCallback(): void {
        if (this._init) return;
        this._init = true;
        this._render();
    }

    private _render(): void {
        const reqId = this.getAttribute('req-id') || '';
        this.className = 'perm-actions';
        this._buildArgsTable(reqId);
        this._buildButtons(reqId);
    }

    private _buildArgsTable(reqId: string): void {
        const argsAttr = this.getAttribute('args');
        if (!argsAttr) return;
        try {
            const args = JSON.parse(argsAttr) as Record<string, unknown>;
            const entries = Object.entries(args).filter(([, v]) => v !== null && v !== undefined && v !== false && v !== '');
            if (entries.length === 0) return;
            // The diff card's header carries the path and +/- stats, so those args must not
            // also appear as k/v rows — but only when the card actually renders.
            const hasDiffCard = typeof args.diff === 'string' && args.diff !== '';
            const table = document.createElement('div');
            table.className = 'perm-args';
            for (const [key, value] of entries) {
                if (key === 'diff') {
                    // Unified diff of the proposed edit: render as a collapsible card
                    // instead of a truncated k/v row so the change is actually reviewable.
                    this._buildDiffBlock(String(value), args, reqId);
                    continue;
                }
                if (hasDiffCard && (key === 'path' || key === 'diffAdded' || key === 'diffRemoved'
                    || key === 'oldText' || key === 'newText' || key === 'autoOpenDiff')) continue;
                const row = document.createElement('div');
                row.className = 'perm-arg-row';
                const label = document.createElement('span');
                label.className = 'perm-arg-key';
                label.textContent = key;
                const val = document.createElement('span');
                val.className = 'perm-arg-val';
                const strVal = Array.isArray(value) ? value.join(', ') : String(value);
                val.title = strVal;
                val.textContent = strVal.length > 80 ? strVal.slice(0, 77) + '…' : strVal;
                row.appendChild(label);
                row.appendChild(val);
                table.appendChild(row);
            }
            this.appendChild(table);
        } catch {
            // malformed args — skip
        }
    }

    private _buildDiffBlock(diff: string, args: Record<string, unknown>, reqId: string): void {
        // Collapsible card: header with the target path and +/- stats over the scrollable
        // colored diff. Built purely with textContent — no user input reaches innerHTML.
        // Stats come from the sender (computed from the FULL change, not the truncated
        // diff text), keeping a single source of truth between the panels.
        const wrap = document.createElement('div');
        wrap.className = 'perm-diff';
        const card = document.createElement('div');
        card.className = 'perm-diff-card';
        const header = document.createElement('div');
        header.className = 'perm-diff-header';
        const title = document.createElement('span');
        title.className = 'perm-diff-path';
        const p = typeof args.path === 'string' ? args.path : '';
        const fileName = p !== '' ? p.replace(/^.*[\\/]/, '') : '';
        title.textContent = fileName !== '' ? fileName : 'proposed change';
        if (p !== '') title.title = p;
        header.appendChild(title);
        const stats = document.createElement('span');
        stats.className = 'perm-diff-stats';
        const added = typeof args.diffAdded === 'number' ? args.diffAdded : 0;
        const removed = typeof args.diffRemoved === 'number' ? args.diffRemoved : 0;
        const addEl = document.createElement('span');
        addEl.className = 'perm-diff-add-count';
        addEl.textContent = `+${added}`;
        const delEl = document.createElement('span');
        delEl.className = 'perm-diff-del-count';
        delEl.textContent = `\u2212${removed}`;
        stats.appendChild(addEl);
        stats.appendChild(delEl);
        const openInEditor = document.createElement('button');
        openInEditor.type = 'button';
        openInEditor.className = 'perm-diff-toggle';
        openInEditor.textContent = 'Open in editor';
        openInEditor.title = 'Show the full diff in the editor area';
        openInEditor.onclick = () => {
            (globalThis as any)._bridge?.openPermissionDiff?.(
                reqId,
                typeof args.path === 'string' ? args.path : '',
                typeof args.oldText === 'string' ? args.oldText : '',
                typeof args.newText === 'string' ? args.newText : '');
        };
        if (typeof args.newText === 'string' && args.newText !== '') {
            stats.appendChild(openInEditor);
            // Opt-in (sender-side setting): open the editor-area diff as soon as the card
            // appears, not just on click.
            if (args.autoOpenDiff === true) openInEditor.click();
        }
        const toggle = document.createElement('button');
        toggle.type = 'button';
        toggle.className = 'perm-diff-toggle';
        toggle.textContent = 'Hide diff';
        toggle.onclick = () => {
            const visible = wrap.classList.toggle('perm-diff-collapsed');
            toggle.textContent = visible ? 'Show diff' : 'Hide diff';
        };
        stats.appendChild(toggle);
        header.appendChild(stats);
        card.appendChild(header);
        const pre = document.createElement('pre');
        // Drop trailing empty lines: the diff text ends with a newline, which would
        // otherwise render as blank rows at the bottom of the card.
        const lines = diff.split('\n');
        while (lines.length > 0 && lines[lines.length - 1] === '') lines.pop();
        for (const line of lines) {
            if (!line) continue;
            const lineEl = document.createElement('span');
            lineEl.className = line.startsWith('+')
                ? 'perm-diff-add'
                : line.startsWith('-')
                    ? 'perm-diff-del'
                    : 'perm-diff-ctx';
            lineEl.textContent = line;
            pre.appendChild(lineEl);
            pre.appendChild(document.createTextNode('\n'));
        }
        wrap.appendChild(pre);
        card.appendChild(wrap);
        this.appendChild(card);
    }

    private _buildButtons(reqId: string): void {
        const denyBtn = document.createElement('button');
        denyBtn.type = 'button';
        denyBtn.className = 'quick-reply-btn perm-deny';
        denyBtn.textContent = 'Deny';
        denyBtn.onclick = () => this._respond(reqId, 'deny', '\u2717 Denied');

        const allowBtn = document.createElement('button');
        allowBtn.type = 'button';
        allowBtn.className = 'quick-reply-btn perm-allow';
        allowBtn.textContent = 'Allow';
        allowBtn.onclick = () => this._respond(reqId, 'once', '\u2713 Allowed');

        const sessionBtn = document.createElement('button');
        sessionBtn.type = 'button';
        sessionBtn.className = 'quick-reply-btn perm-allow-session';
        sessionBtn.textContent = 'Allow for session';
        sessionBtn.onclick = () => this._respond(reqId, 'session', '\u2713 Allowed for session');

        const alwaysBtn = document.createElement('button');
        alwaysBtn.type = 'button';
        alwaysBtn.className = 'quick-reply-btn perm-allow-always';
        alwaysBtn.textContent = 'Always allow';
        alwaysBtn.onclick = () => this._respond(reqId, 'always', '\u2713 Always allowed');

        this.appendChild(denyBtn);
        this.appendChild(allowBtn);
        this.appendChild(sessionBtn);
        this.appendChild(alwaysBtn);
    }

    private _respond(reqId: string, mode: 'deny' | 'once' | 'session' | 'always', label: string): void {
        this.querySelectorAll('button').forEach(b => {
            b.disabled = true;
        });

        const result = document.createElement('div');
        const allowed = mode !== 'deny';
        result.className = 'perm-result ' + (allowed ? 'perm-allowed' : 'perm-denied');
        result.textContent = label;
        this.replaceChildren(result);

        (globalThis as any)._bridge?.permissionResponse(`${reqId}:${mode}`);
    }
}
