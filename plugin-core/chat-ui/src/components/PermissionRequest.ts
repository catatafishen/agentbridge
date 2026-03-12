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

        const argsAttr = this.getAttribute('args');
        if (argsAttr) {
            try {
                const args = JSON.parse(argsAttr) as Record<string, unknown>;
                const entries = Object.entries(args).filter(([, v]) => v !== null && v !== undefined && v !== false && v !== '');
                if (entries.length > 0) {
                    const table = document.createElement('div');
                    table.className = 'perm-args';
                    for (const [key, value] of entries) {
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
                }
            } catch {
                // malformed args — skip
            }
        }

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

        this.appendChild(denyBtn);
        this.appendChild(allowBtn);
        this.appendChild(sessionBtn);
    }

    private _respond(reqId: string, mode: 'deny' | 'once' | 'session', label: string): void {
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
