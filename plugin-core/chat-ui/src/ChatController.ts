import {decodeBase64, escHtml, hideRedundantTimestamp} from './helpers';
import {renderBatchFragment} from './BatchRenderer';
import type {TurnContext} from './types';

function _showNotification(title: string, body: string, actions?: { action: string; title: string }[]): void {
    if (!('Notification' in globalThis) || Notification.permission !== 'granted' || !document.hidden) return;
    const sw = navigator.serviceWorker?.controller;
    if (sw) {
        const msg: Record<string, unknown> = {type: 'SHOW_NOTIFICATION', title, body};
        if (actions?.length) msg.actions = actions;
        sw.postMessage(msg);
    } else {
        new Notification(title, {body, silent: true});
    }
}

/** Format milliseconds as human-readable duration: "45s", "1m 23s", "2h 5m" */
function _formatDuration(ms: number): string {
    if (ms <= 0) return '';
    const totalSec = Math.round(ms / 1000);
    if (totalSec < 60) return totalSec + 's';
    const min = Math.floor(totalSec / 60);
    const sec = totalSec % 60;
    if (min < 60) return sec > 0 ? min + 'm ' + sec + 's' : min + 'm';
    const hr = Math.floor(min / 60);
    const remMin = min % 60;
    return remMin > 0 ? hr + 'h ' + remMin + 'm' : hr + 'h';
}

/** Format token count: "1.2k", "245", "12.3k" */
function _formatTokens(n: number): string {
    if (n < 1000) return String(n);
    if (n < 10000) return (n / 1000).toFixed(1) + 'k';
    return Math.round(n / 1000) + 'k';
}

/** Build inline diff stats: +N -N spans, or null if both are zero. */
function _buildDiffSpan(added: number, removed: number): HTMLElement | null {
    if (added === 0 && removed === 0) return null;
    const diffEl = document.createElement('span');
    if (added > 0) {
        const a = document.createElement('span');
        a.className = 'diff-add';
        a.textContent = '+' + added;
        diffEl.appendChild(a);
    }
    if (removed > 0) {
        if (added > 0) diffEl.appendChild(document.createTextNode('\u2009'));
        const d = document.createElement('span');
        d.className = 'diff-del';
        d.textContent = '\u2212' + removed;
        diffEl.appendChild(d);
    }
    return diffEl;
}

/** Build a turn-summary-bar element: a horizontal rule with stats centered. */
function _buildTurnSummaryBar(stats: {
    duration: number; inputTokens: number; outputTokens: number;
    tools: number; added: number; removed: number;
    model: string; multiplier?: string;
}): HTMLElement {
    const bar = document.createElement('div');
    bar.className = 'turn-summary-bar';

    const lineL = document.createElement('span');
    lineL.className = 'turn-summary-line';
    const lineR = document.createElement('span');
    lineR.className = 'turn-summary-line';
    const label = document.createElement('span');
    label.className = 'turn-summary-label';

    const parts: Array<string | HTMLElement> = [];

    if (stats.model) {
        const name = stats.model.includes('/') ? stats.model.split('/').pop()! : stats.model;
        parts.push(name);
    }

    const diffEl = _buildDiffSpan(stats.added, stats.removed);
    if (diffEl) parts.push(diffEl);

    if (stats.inputTokens > 0 || stats.outputTokens > 0) {
        parts.push(_formatTokens(stats.inputTokens) + '/' + _formatTokens(stats.outputTokens) + ' tok');
    }

    if (stats.tools > 0) {
        parts.push(stats.tools + (stats.tools === 1 ? ' tool' : ' tools'));
    }

    if (stats.multiplier) {
        parts.push(stats.multiplier);
    }

    const dur = _formatDuration(stats.duration);
    if (dur) parts.push(dur);

    const sep = ' \u2014 ';
    parts.forEach((part, i) => {
        if (i > 0) label.appendChild(document.createTextNode(sep));
        if (typeof part === 'string') {
            label.appendChild(document.createTextNode(part));
        } else {
            label.appendChild(part);
        }
    });

    bar.appendChild(lineL);
    bar.appendChild(label);
    bar.appendChild(lineR);
    return bar;
}

const ChatController = {
    _domMessageLimit: 80,

    _msgs(): HTMLElement {
        return document.querySelector('#messages')!;
    },

    _container(): HTMLElement & {
        scrollIfNeeded(): void;
        scheduleScrollIfNeeded(): void;
        forceScroll(): void;
        pauseAutoScrollForRestore(): void;
        stopAutoScrollRestore(): void;
        compensateScroll(targetY: number): void;
        resumeAutoScroll(): void;
        autoScroll: boolean;
        workingIndicator: HTMLElement & { show(): void; hide(): void; resetTimer(): void; stop(ms: number): void }
    } | null {
        return document.querySelector<HTMLElement & {
            scrollIfNeeded(): void;
            scheduleScrollIfNeeded(): void;
            forceScroll(): void;
            pauseAutoScrollForRestore(): void;
            stopAutoScrollRestore(): void;
            compensateScroll(targetY: number): void;
            resumeAutoScroll(): void;
            autoScroll: boolean;
            workingIndicator: HTMLElement & { show(): void; hide(): void; resetTimer(): void; stop(ms: number): void }
        }>('chat-container');
    },

    _resetWorkingTimer(): void {
        const wi = this._container()?.workingIndicator;
        if (wi && !wi.hidden) wi.resetTimer();
    },

    _thinkingCounter: 0,
    _turnActive: false,
    _currentProfile: '',
    _currentClientType: '',
    _ctx: {} as Record<string, TurnContext & { thinkingMsg?: HTMLElement | null; thinkingChip?: HTMLElement | null }>,
    /** Caches the first known timestamp for each turn key (turnId-agentId) so late
     *  events (e.g. finalizeAgentText after compaction) can reuse it. */
    _turnTimestamps: {} as Record<string, string>,

    _getCtx(turnId: string, agentId: string): TurnContext & {
        thinkingMsg?: HTMLElement | null;
        thinkingChip?: HTMLElement | null
    } {
        const key = turnId + '-' + agentId;
        if (!this._ctx[key]) {
            this._ctx[key] = {
                msg: null, meta: null, details: null,
                textBubble: null,
                thinkingBlock: null,
            };
        }
        return this._ctx[key];
    },

    _ensureMsg(turnId: string, agentId: string, timestamp?: string): TurnContext & {
        thinkingMsg?: HTMLElement | null;
        thinkingChip?: HTMLElement | null
    } {
        const key = turnId + '-' + agentId;
        if (timestamp) this._turnTimestamps[key] = timestamp;
        const ctx = this._getCtx(turnId, agentId);
        if (!ctx.msg) {
            const msg = document.createElement('chat-message');
            msg.setAttribute('type', 'agent');
            const meta = document.createElement('message-meta');
            meta.className = 'meta';
            const ts = timestamp || this._turnTimestamps[key] || (() => {
                console.warn('[_ensureMsg] No timestamp for turn', turnId, '— showing placeholder');
                return '--:--';
            })();
            const tsSpan = document.createElement('span');
            tsSpan.className = 'ts';
            tsSpan.textContent = ts;
            meta.appendChild(tsSpan);
            msg.appendChild(meta);
            const details = document.createElement('turn-details');
            msg.appendChild(details);
            this._insertMsg(msg);
            this._turnActive = true;
            ctx.msg = msg;
            ctx.meta = meta;
            ctx.details = details;

            if (agentId === 'main' && this._currentClientType) {
                msg.classList.add('client-' + this._currentClientType);
            }
        }
        return ctx;
    },

    _insertMsg(msg: HTMLElement): void {
        const msgs = this._msgs();
        const firstQueued = msgs.querySelector('.message-queued');
        if (firstQueued) {
            firstQueued.before(msg);
        } else {
            msgs.appendChild(msg);
        }
        if (msg.tagName === 'CHAT-MESSAGE') hideRedundantTimestamp(msg);
    },

    _collapseThinkingFor(ctx: TurnContext & {
        thinkingMsg?: HTMLElement | null;
        thinkingChip?: HTMLElement | null
    } | null): void {
        if (!ctx?.thinkingBlock) return;
        (ctx.thinkingBlock as any).finalize();
        ctx.thinkingBlock.removeAttribute('expanded');
        ctx.thinkingBlock.classList.add('turn-hidden');
        if (ctx.thinkingChip) {
            ctx.thinkingChip.setAttribute('status', 'complete');
            ctx.thinkingChip = null;
        }
        ctx.thinkingBlock = null;
        ctx.thinkingMsg = null;
    },

    newSegment(turnId: string, agentId: string): void {
        const ctx = this._getCtx(turnId, agentId);
        if (ctx.textBubble) {
            ctx.textBubble.removeAttribute('streaming');
            const p = ctx.textBubble.querySelector('.pending');
            if (p) p.remove();
        }
        this._collapseThinkingFor(ctx);
        ctx.msg = null;
        ctx.meta = null;
        ctx.details = null;
        ctx.textBubble = null;
    },

    // ── Public API ─────────────────────────────────────────────

    addUserMessage(text: string, timestamp: string, encodedBubbleHtml?: string, entryId?: string): void {
        const msg = document.createElement('chat-message');
        msg.setAttribute('type', 'user');
        if (entryId) msg.id = entryId;
        const meta = document.createElement('message-meta');
        const timestampSpan = document.createElement('span');
        timestampSpan.className = 'ts';
        timestampSpan.textContent = timestamp;
        meta.appendChild(timestampSpan);
        msg.appendChild(meta);
        const bubble = document.createElement('message-bubble');
        bubble.setAttribute('type', 'user');
        if (encodedBubbleHtml) {
            bubble.innerHTML = decodeBase64(encodedBubbleHtml);
        } else {
            bubble.textContent = text;
        }
        msg.appendChild(bubble);
        this._insertMsg(msg);
        this._container()?.forceScroll();
    },

    removeUserMessage(entryId: string): void {
        document.getElementById(entryId)?.remove();
    },

    appendAgentText(turnId: string, agentId: string, text: string, timestamp?: string): void {
        try {
            this._resetWorkingTimer();
            const ctx = this._getCtx(turnId, agentId);
            this._collapseThinkingFor(ctx);
            if (!ctx.textBubble) {
                if (!text.trim()) return;
                const c = this._ensureMsg(turnId, agentId, timestamp);
                const bubble = document.createElement('message-bubble');
                bubble.setAttribute('streaming', '');
                c.msg!.appendChild(bubble);
                c.textBubble = bubble;
            }
            (ctx.textBubble as any).appendStreamingText(text);
            // scrollIfNeeded() removed — text hasn't rendered yet (rAF pending),
            // so scrollHeight is stale. The MutationObserver + ResizeObserver
            // on ChatContainer already handle post-render auto-scroll.
        } catch (e: any) {
            console.error('[appendAgentText ERROR]', e.message, e.stack);
        }
    },

    finalizeAgentText(turnId: string, agentId: string, encodedHtml?: string): void {
        try {
            const ctx = this._getCtx(turnId, agentId);
            if (!ctx.textBubble && !encodedHtml) return;
            if (encodedHtml) {
                if (ctx.textBubble) {
                    (ctx.textBubble as any).finalize(decodeBase64(encodedHtml));
                } else {
                    const c = this._ensureMsg(turnId, agentId);
                    const bubble = document.createElement('message-bubble');
                    c.msg!.appendChild(bubble);
                    (bubble as any).finalize(decodeBase64(encodedHtml));
                }
            } else if (ctx.textBubble) {
                ctx.textBubble.remove();
                if (ctx.msg && !ctx.msg.querySelector('message-bubble, tool-chip, thinking-block')) {
                    ctx.msg.remove();
                    ctx.msg = null;
                    ctx.meta = null;
                }
            }
            ctx.textBubble = null;
            this._container()?.scheduleScrollIfNeeded();
        } catch (e: any) {
            console.error('[finalizeAgentText ERROR]', e.message, e.stack);
        }
    },

    addThinkingText(turnId: string, agentId: string, text: string, timestamp?: string): void {
        this._resetWorkingTimer();
        const ctx = this._ensureMsg(turnId, agentId, timestamp);
        if (!ctx.thinkingBlock) {
            this._thinkingCounter++;
            const el = document.createElement('thinking-block');
            el.id = 'think-' + this._thinkingCounter;
            el.setAttribute('active', '');
            el.setAttribute('expanded', '');
            ctx.details!.appendChild(el);
            ctx.thinkingBlock = el;
            const chip = document.createElement('thinking-chip');
            chip.setAttribute('status', 'thinking');
            chip.dataset.chipFor = el.id;
            (chip as any).linkSection(el);
            ctx.meta!.appendChild(chip);
            ctx.meta!.classList.add('show');
            ctx.thinkingChip = chip;
        }
        (ctx.thinkingBlock as any).appendText(text);
        this._container()?.scheduleScrollIfNeeded();
    },

    collapseThinking(turnId: string, agentId: string, encodedHtml?: string): void {
        const ctx = this._getCtx(turnId, agentId);
        if (encodedHtml && ctx.thinkingBlock) {
            const content = (ctx.thinkingBlock as any).contentEl as Element | null;
            if (content) content.innerHTML = decodeBase64(encodedHtml);
        }
        this._collapseThinkingFor(ctx);
    },

    addToolCall(turnId: string, agentId: string, id: string, title: string, paramsJson?: string, kind?: string, initialStatus?: string): void {
        this.upsertToolChip(turnId, agentId, id, title, paramsJson, {kind, status: initialStatus || 'pending'});
    },

    upsertToolChip(turnId: string, agentId: string, id: string, title: string, paramsJson?: string, options?: {
        kind?: string,
        status?: string,
        timestamp?: string
    }): void {
        this._resetWorkingTimer();
        let chip = document.querySelector('[data-chip-for="' + id + '"]') as HTMLElement | null;
        if (!chip) {
            const ctx = this._ensureMsg(turnId, agentId, options?.timestamp);
            this._collapseThinkingFor(ctx);
            chip = document.createElement('tool-chip');
            chip.dataset.chipFor = id;
            ctx.meta!.appendChild(chip);
            ctx.meta!.classList.add('show');
            // scrollIfNeeded() removed — chip hasn't laid out yet (connectedCallback pending),
            // so scrollHeight is stale. MutationObserver + ResizeObserver on ChatContainer
            // already handle post-render auto-scroll via rAF. Same rationale as appendAgentText().
        }
        chip.setAttribute('label', title);
        chip.setAttribute('status', options?.status || 'pending');
        const kind = options?.kind;
        if (kind) {
            const currentKind = chip.getAttribute('kind');
            if (!currentKind || currentKind === 'other') {
                chip.setAttribute('kind', kind);
            }
        }
        if (paramsJson) chip.dataset.params = paramsJson;
    },

    markMcpHandled(id: string): void {
        const chip = document.querySelector('[data-chip-for="' + id + '"]');
        if (chip) chip.classList.add('is-agentbridge-tool');
        this.setToolChipState(id, 'running');
    },

    removeToolChip(id: string): void {
        const chip = document.querySelector('[data-chip-for="' + id + '"]');
        if (chip) {
            chip.remove();
        }
        const section = document.getElementById(id);
        if (section) {
            section.remove();
        }
    },

    setToolChipState(id: string, state: string): void {
        const chip = document.querySelector('[data-chip-for="' + id + '"]');
        if (!chip) return;
        chip.setAttribute('status', state);
        if (state !== 'pending' && state !== 'running') {
            this._resetWorkingTimer();
        }
    },

    updateToolCall(id: string, status: string, resultHtml?: string): void {
        const jsStatus = status === 'failed' ? 'failed' : 'complete';
        this.setToolChipState(id, jsStatus);
    },

    updateToolCallKind(id: string, kind: string): void {
        const chip = document.querySelector('[data-chip-for="' + id + '"]');
        if (chip) {
            const currentKind = chip.getAttribute('kind');
            if (!currentKind || currentKind === 'other') {
                chip.setAttribute('kind', kind);
            }
        }
    },

    addSubAgent(turnId: string, agentId: string, sectionId: string, displayName: string, colorIndex: number, promptHtml?: string, timestamp?: string): void {
        this._resetWorkingTimer();
        const ctx = this._ensureMsg(turnId, agentId);
        this._collapseThinkingFor(ctx);
        ctx.textBubble = null;
        const chip = document.createElement('subagent-chip');
        chip.setAttribute('label', displayName);
        chip.setAttribute('status', 'running');
        chip.setAttribute('color-index', String(colorIndex));
        chip.dataset.chipFor = 'sa-' + sectionId;
        ctx.meta!.appendChild(chip);
        ctx.meta!.classList.add('show');
        const promptBubble = document.createElement('message-bubble');
        // Safe: colorIndex is a number from the server (no HTML chars possible).
        // displayName is HTML-escaped via escHtml(). promptHtml is pre-rendered HTML
        // produced by MessageFormatter on the Java side and base64-encoded — the Java
        // layer is responsible for sanitising all user-visible content before encoding. — lgtm[js/html-constructed-from-input]
        promptBubble.innerHTML = '<span class="subagent-prefix subagent-c' + colorIndex + '">@' + escHtml(displayName) + '</span> ' + (promptHtml ? decodeBase64(promptHtml) : '');
        ctx.msg!.appendChild(promptBubble);
        const msg = document.createElement('chat-message');
        msg.setAttribute('type', 'agent');
        msg.id = 'sa-' + sectionId;
        msg.classList.add('subagent-indent', 'subagent-c' + colorIndex);
        const meta = document.createElement('message-meta');
        meta.className = 'meta show';
        const ts = timestamp || '--:--';
        const tsSpan = document.createElement('span');
        tsSpan.className = 'ts';
        tsSpan.textContent = ts;
        meta.appendChild(tsSpan);
        const nameSpan = document.createElement('span');
        nameSpan.className = 'agent-name';
        nameSpan.textContent = displayName;
        meta.appendChild(nameSpan);
        msg.appendChild(meta);
        const saDetails = document.createElement('turn-details');
        msg.appendChild(saDetails);
        const resultBubble = document.createElement('message-bubble');
        resultBubble.id = 'result-' + sectionId;
        resultBubble.classList.add('subagent-result');
        msg.appendChild(resultBubble);
        this._insertMsg(msg);
        (chip as any).linkSection(msg);
        this._container()?.scheduleScrollIfNeeded();
    },

    updateSubAgent(sectionId: string, status: string, encodedResultHtml?: string): void {
        const el = document.getElementById('result-' + sectionId);
        if (el) {
            const resultHtml = encodedResultHtml ? decodeBase64(encodedResultHtml) : null;
            el.innerHTML = resultHtml || (status === 'completed' ? 'Completed' : '<span style="color:var(--error)">\u2716 Failed</span>');
        }
        const chip = document.querySelector('[data-chip-for="sa-' + sectionId + '"]');
        if (chip) chip.setAttribute('status', status === 'failed' ? 'failed' : 'complete');
        this._container()?.scheduleScrollIfNeeded();
    },

    addSubAgentToolCall(subAgentDomId: string, toolDomId: string, title: string, paramsJson?: string, kind?: string, isExternal?: boolean): void {
        const msg = document.getElementById('sa-' + subAgentDomId);
        if (!msg) return;
        const meta = msg.querySelector('message-meta');
        const chip = document.createElement('tool-chip');
        chip.setAttribute('label', title);
        chip.setAttribute('status', 'running');
        if (kind) {
            const currentKind = chip.getAttribute('kind');
            if (!currentKind || currentKind === 'other') {
                chip.setAttribute('kind', kind);
            }
        }
        if (isExternal) chip.setAttribute('external', 'true');
        chip.dataset.chipFor = toolDomId;
        if (paramsJson) chip.dataset.params = paramsJson;
        if (meta) {
            meta.appendChild(chip);
            meta.classList.add('show');
        }
        this._container()?.scheduleScrollIfNeeded();
    },

    addSessionSeparator(timestamp: string, agent: string = ''): void {
        const el = document.createElement('session-divider');
        el.setAttribute('timestamp', timestamp);
        if (agent) el.setAttribute('agent', agent);
        this._insertMsg(el);
    },

    showPlaceholder(text: string): void {
        this.clear();
        // Safe: text is HTML-escaped via escHtml() before insertion. — lgtm[js/html-constructed-from-input]
        this._msgs().innerHTML = '<div class="placeholder">' + escHtml(text) + '</div>';
    },

    clear(): void {
        this.hideWorkingIndicator();
        this._msgs().innerHTML = '';
        this._ctx = {};
        this._thinkingCounter = 0;
        this._turnActive = false;
        this._currentProfile = '';
    },

    finalizeTurn(turnId: string, statsJson?: string): void {
        this._turnActive = false;
        // Working indicator is stopped by renderTurnSummary (with the authoritative Kotlin duration)
        // so the displayed duration matches the turn summary bar. Use a rAF as a fallback in case
        // renderTurnSummary is never called (e.g. for empty or cancelled turns).
        const wi = this._container()?.workingIndicator;
        requestAnimationFrame(() => {
            if (wi && !wi.hidden) wi.hide();
        });
        const ctx = this._ctx[turnId + '-main'];
        if (ctx?.textBubble && !ctx.textBubble.textContent?.trim()) {
            ctx.textBubble.remove();
        }
        this._collapseThinkingFor(ctx || null);
        if (ctx) {
            ctx.textBubble = null;
            // If the turn produced thinking but no text output, add a placeholder so the
            // turn is not silently empty (i.e. the message exists but has no bubble).
            if (ctx.msg && !ctx.msg.querySelector('message-bubble')) {
                const placeholder = document.createElement('message-bubble');
                placeholder.classList.add('no-output-placeholder');
                placeholder.textContent = 'Completed without output';
                ctx.msg.appendChild(placeholder);
            }
        }
        // Mark any still-running sub-agent chips as complete (not failed)
        document.querySelectorAll('subagent-chip[status="running"]').forEach(c => c.setAttribute('status', 'complete'));
        // Mark any still-running tool chips as complete (not failed)
        document.querySelectorAll('tool-chip[status="running"]').forEach(c => c.setAttribute('status', 'complete'));
        this._container()?.scheduleScrollIfNeeded();
        this._trimMessages();
        _showNotification('Agent turn complete', 'The agent has finished responding.');
    },

    showPermissionRequest(turnId: string, agentId: string, reqId: string, toolDisplayName: string, contextJson: string): void {
        this.disableQuickReplies();
        const ctx = this._ensureMsg(turnId, agentId);
        this._collapseThinkingFor(ctx);

        // Parse the structured context {question, args} produced by Java.
        // Falls back to generic label if the payload is a plain string (old code paths).
        // Safe: toolDisplayName and parsed.question are both HTML-escaped via escHtml().
        // argsJson is only passed to JSON.stringify — it is rendered via textContent, not innerHTML.
        let questionHtml = `Can I use <strong>${escHtml(toolDisplayName)}</strong>?`;
        let argsJson = '';
        try {
            const parsed = JSON.parse(contextJson) as { question?: string; args?: Record<string, unknown> };
            if (parsed.question) questionHtml = escHtml(parsed.question);
            if (parsed.args && Object.keys(parsed.args).length > 0) argsJson = JSON.stringify(parsed.args);
        } catch {
            // contextJson is a plain string from old code paths — use generic label
        }

        const bubble = document.createElement('message-bubble');
        // Safe: questionHtml is constructed exclusively from escHtml() calls — lgtm[js/html-constructed-from-input]
        bubble.innerHTML = questionHtml;
        ctx.msg!.appendChild(bubble);

        const actions = document.createElement('permission-request');
        actions.setAttribute('req-id', reqId);
        if (argsJson) actions.setAttribute('args', argsJson);
        ctx.msg!.appendChild(actions);

        this._container()?.scheduleScrollIfNeeded();
    },

    showAskUserRequest(turnId: string, agentId: string, reqId: string, question: string, options: string[]): void {
        this.disableQuickReplies();
        const ctx = this._ensureMsg(turnId, agentId);
        this._collapseThinkingFor(ctx);

        const bubble = document.createElement('message-bubble');
        bubble.dataset.reqId = reqId;
        // Safe: question is fully escaped with escHtml() before being set — lgtm[js/html-constructed-from-input]
        bubble.innerHTML = escHtml(question).replaceAll('\n', '<br/>');
        ctx.msg!.appendChild(bubble);

        if (options?.length) {
            const replies = document.createElement('quick-replies');
            replies.dataset.reqId = reqId;
            (replies as any).options = options;
            ctx.msg!.appendChild(replies);
        }

        this._container()?.scheduleScrollIfNeeded();
        const notificationWithActions = Notification as typeof Notification & { readonly maxActions?: number };
        const actions = options?.length
            ? options.slice(0, notificationWithActions.maxActions ?? 2).map(o => ({action: o, title: o}))
            : undefined;
        _showNotification('Agent is asking you something', question, actions?.length ? actions : undefined);
    },

    showQuickReplies(options: string[]): void {
        this.disableQuickReplies();
        if (!options?.length) return;
        const el = document.createElement('quick-replies');
        (el as any).options = options;
        // Insert inside the last agent-row so quick-replies appear above the turn-summary-bar
        const row = this._lastAgentRow();
        if (row) {
            const summaryBar = row.querySelector('.turn-summary-bar');
            if (summaryBar) {
                summaryBar.before(el);
            } else {
                row.appendChild(el);
            }
        } else {
            this._insertMsg(el);
        }
        this._container()?.scheduleScrollIfNeeded();
    },

    disableQuickReplies(): void {
        document.querySelectorAll('quick-replies:not([disabled])').forEach(el => el.setAttribute('disabled', ''));
    },

    cancelAllRunning(): void {
        this.hideWorkingIndicator();
        document.querySelectorAll('tool-chip[status="running"]').forEach(c => c.setAttribute('status', 'failed'));
        document.querySelectorAll('thinking-chip[status="running"], thinking-chip[status="thinking"]').forEach(c => c.setAttribute('status', 'complete'));
        document.querySelectorAll('subagent-chip[status="running"]').forEach(c => c.setAttribute('status', 'failed'));
        document.querySelectorAll('message-bubble[streaming]').forEach(b => b.removeAttribute('streaming'));
    },

    setCodeChangeStats(_added: number, _removed: number): void {
        // Live diff chips in intermediate sections removed — stats appear only in the turn summary.
    },

    /**
     * Renders the final turn summary as a subtle text bar below the last agent message.
     * Replaces any live stats footer with: "Turn complete — model — +N −N — tokens — tools — duration"
     */
    renderTurnSummary(stats: {
        duration: number; inputTokens: number; outputTokens: number;
        tools: number; added: number; removed: number;
        model: string; multiplier?: string;
    }): void {
        // Stop the working indicator with the authoritative Kotlin duration so it shows
        // the same value as the summary bar below (both use Math.round on the same ms value).
        this._container()?.workingIndicator?.stop(stats.duration);

        // Remove ALL live stats footers across every agent row (the footer may be
        // stranded on an earlier row if new segments were added after the last
        // setCodeChangeStats call).
        document.querySelectorAll('message-meta.stats-footer').forEach(el => el.remove());

        const row = this._lastAgentRow();
        if (!row) return;

        // Remove any previous summary bar (guard against double-emit)
        row.querySelectorAll('.turn-summary-bar').forEach(el => el.remove());

        row.appendChild(_buildTurnSummaryBar(stats));
    },

    _lastAgentRow(): Element | null {
        const rows = document.querySelectorAll('.agent-row');
        return rows[rows.length - 1] ?? null;
    },

    setCurrentProfile(profileId: string): void {
        this._currentProfile = profileId;
    },

    setClientType(type: string): void {
        this._currentClientType = type;
    },

    setCurrentModel(modelId: string): void {
        // Kept for stats display compatibility
    },

    restoreBatch(encodedJson: string): void {
        const fragment = renderBatchFragment(encodedJson);
        const msgs = this._msgs();
        const loadMore = msgs.querySelector('load-more');
        const insertBefore = loadMore ? loadMore.nextSibling : msgs.firstChild;

        const container = this._container();
        const prevScrollTop = container?.scrollTop ?? 0;
        const prevHeight = container?.scrollHeight ?? 0;

        if (insertBefore) {
            insertBefore.before(fragment);
        } else {
            msgs.appendChild(fragment);
        }
        this._moveQueuedToBottom();

        // JCEF does not implement CSS scroll anchoring — compensate manually
        if (container && prevScrollTop <= 30) {
            const addedHeight = container.scrollHeight - prevHeight;
            if (addedHeight > 0) {
                const targetScroll = Math.min(50, Math.max(10, prevScrollTop + addedHeight));
                container.compensateScroll(targetScroll);
            }
        }
    },

    restoreBatchFinal(encodedJson: string, smoothAfter: boolean): void {
        const container = this._container();
        if (container) {
            container.style.scrollBehavior = 'auto';
            container.pauseAutoScrollForRestore();
        }

        const fragment = renderBatchFragment(encodedJson);
        const msgs = this._msgs();
        msgs.appendChild(fragment);
        this._moveQueuedToBottom();

        if (!container) return;

        // Two rAFs: first for initial layout, second for code-block setup (_copyObs fires in rAF).
        // After both, instantly jump to bottom, then restore smooth scroll for subsequent use.
        requestAnimationFrame(() => {
            requestAnimationFrame(() => {
                container.stopAutoScrollRestore();
                container.style.scrollBehavior = 'auto';
                container.forceScroll();
                if (smoothAfter) {
                    requestAnimationFrame(() => {
                        container.style.scrollBehavior = 'smooth';
                    });
                }
            });
        });
    },

    showLoadMore(count: number): void {
        let el = document.querySelector('load-more');
        if (!el) {
            el = document.createElement('load-more');
            this._msgs().insertBefore(el, this._msgs().firstChild);
        }
        el.setAttribute('count', String(count));
        el.removeAttribute('loading');
    },

    removeLoadMore(): void {
        document.querySelector('load-more')?.remove();
    },

    prependBatch(encodedJson: string): void {
        const fragment = renderBatchFragment(encodedJson);
        const msgs = this._msgs();
        const loadMore = msgs.querySelector('load-more');
        const insertBefore = loadMore ? loadMore.nextSibling : msgs.firstChild;

        const container = this._container();
        const prevHeight = container?.scrollHeight ?? 0;
        const prevScrollTop = container?.scrollTop ?? 0;
        const wasNearTop = prevScrollTop <= 30;

        if (insertBefore) {
            insertBefore.before(fragment);
        } else {
            msgs.appendChild(fragment);
        }
        this._moveQueuedToBottom();

        // Compensate scroll so user stays at same visual position.
        // Ensure minimum 10px offset so JCEF detects subsequent scroll-up gestures.
        // Cap at 50px to keep user near top for continuous load-more triggering.
        if (container) {
            const addedHeight = container.scrollHeight - prevHeight;
            if (addedHeight > 0) {
                const targetScroll = Math.min(50, Math.max(10, prevScrollTop + addedHeight));
                container.compensateScroll(targetScroll);
            }
        }

        // Continue loading if user was near top before scroll adjustment
        if (wasNearTop) {
            requestAnimationFrame(() => {
                const lm = msgs.querySelector<HTMLElement>('load-more:not([loading])');
                if (lm) lm.click();
            });
        }
    },

    showWorkingIndicator(): void {
        const container = this._container();
        const wi = container?.workingIndicator;
        if (wi) {
            wi.classList.remove('client-copilot', 'client-claude', 'client-opencode', 'client-junie', 'client-kiro', 'client-codex');
            if (this._currentClientType) wi.classList.add('client-' + this._currentClientType);
        }
        wi?.show();
        container?.scheduleScrollIfNeeded();
    },

    hideWorkingIndicator(): void {
        this._container()?.workingIndicator?.hide();
    },

    _trimMessages(): void {
        const msgs = this._msgs();
        if (!msgs) return;
        const rows = Array.from(msgs.children).filter(
            c => c.tagName === 'CHAT-MESSAGE'
        );
        const limit = this._domMessageLimit;
        if (rows.length > limit) {
            const trimCount = rows.length - limit;
            for (let i = 0; i < trimCount; i++) rows[i].remove();
        }
    },

    setDomMessageLimit(limit: number): void {
        this._domMessageLimit = limit;
    },

    showNudgeBubble(id: string, text: string): void {
        const existing = document.getElementById('nudge-' + id);
        if (existing) {
            const bubble = existing.querySelector('message-bubble');
            if (bubble) {
                bubble.textContent = text;
                this._container()?.scheduleScrollIfNeeded();
            }
            return;
        }
        const msg = document.createElement('chat-message');
        msg.id = 'nudge-' + id;
        msg.setAttribute('type', 'user');
        msg.classList.add('nudge-pending');
        const meta = document.createElement('message-meta');
        meta.innerHTML = '<span class="ts nudge-label"><span class="chip-spinner nudge-spinner"></span> Nudge (pending)</span>';
        msg.appendChild(meta);
        const bubble = document.createElement('message-bubble');
        bubble.setAttribute('type', 'user');
        bubble.textContent = text;
        msg.appendChild(bubble);
        const cancelBtn = document.createElement('button');
        cancelBtn.type = 'button';
        cancelBtn.className = 'quick-reply-btn nudge-cancel-btn';
        cancelBtn.textContent = '✕ Cancel nudge';
        cancelBtn.onclick = () => (globalThis as any)._bridge?.cancelNudge(id);
        msg.appendChild(cancelBtn);
        // Insert before the first queued message so nudge appears above queued messages.
        const firstQueued = this._msgs().querySelector('.message-queued');
        if (firstQueued) {
            this._msgs().insertBefore(msg, firstQueued);
        } else {
            this._msgs().appendChild(msg);
        }
        this._container()?.scheduleScrollIfNeeded();
    },

    resolveNudgeBubble(id: string): void {
        const el = document.getElementById('nudge-' + id);
        if (!el) return;
        el.classList.remove('nudge-pending');
        el.classList.add('nudge-sent');
        el.querySelector('.nudge-label')?.remove();
        el.querySelector('.nudge-cancel-btn')?.remove();
    },

    removeNudgeBubble(id: string): void {
        document.getElementById('nudge-' + id)?.remove();
    },

    showQueuedMessage(id: string, text: string): void {
        const msg = document.createElement('chat-message');
        msg.id = 'queued-' + id;
        msg.setAttribute('type', 'user');
        msg.classList.add('message-queued');
        const meta = document.createElement('message-meta');
        meta.innerHTML = '<span class="ts">⏳ Queued for end of turn</span>';
        meta.classList.add('show');
        msg.appendChild(meta);
        const bubble = document.createElement('message-bubble');
        bubble.setAttribute('type', 'user');
        bubble.textContent = text;
        msg.appendChild(bubble);
        const cancelBtn = document.createElement('button');
        cancelBtn.type = 'button';
        cancelBtn.className = 'quick-reply-btn nudge-cancel-btn';
        cancelBtn.textContent = '✕ Cancel message';
        cancelBtn.onclick = () => (globalThis as any)._bridge?.cancelQueuedMessage(id, text);
        msg.appendChild(cancelBtn);
        this._msgs().appendChild(msg);
        this._container()?.scheduleScrollIfNeeded();
    },

    _moveQueuedToBottom(): void {
        const msgs = this._msgs();
        const queued = Array.from(msgs.children).filter(c => c.classList.contains('message-queued'));
        for (const msg of queued) {
            msgs.appendChild(msg);
        }
    },

    removeQueuedMessage(id: string): void {
        document.getElementById('queued-' + id)?.remove();
    },

    removeQueuedMessageByText(text: string): void {
        const msgs = this._msgs();
        const rows = Array.from(msgs.children).filter(c => c.tagName === 'CHAT-MESSAGE' && c.classList.contains('message-queued'));
        for (const row of rows) {
            if (row.querySelector('message-bubble')?.textContent === text) {
                row.remove();
                break;
            }
        }
    },

    getAutoScroll(): boolean {
        return this._container()?.autoScroll ?? true;
    },

    setAutoScroll(enabled: boolean): void {
        const container = this._container();
        if (container) container.autoScroll = enabled;
    },

    /** Re-enables autoscroll without jumping to the bottom. See {@link ChatContainer.resumeAutoScroll}. */
    resumeAutoScroll(): void {
        this._container()?.resumeAutoScroll();
    },

};

export default ChatController;
