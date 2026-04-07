import {decodeBase64, escHtml} from './helpers';
import {renderBatchFragment} from './BatchRenderer';
import type {TurnContext} from './types';

function _showNotification(title: string, body: string, actions?: { action: string; title: string }[]): void {
    if (!('Notification' in window) || Notification.permission !== 'granted' || !document.hidden) return;
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

const ChatController = {
    _domMessageLimit: 80,

    _msgs(): HTMLElement {
        return document.querySelector('#messages')!;
    },

    _container(): HTMLElement & {
        scrollIfNeeded(): void;
        forceScroll(): void;
        compensateScroll(targetY: number): void;
        autoScroll: boolean;
        workingIndicator: HTMLElement & { show(): void; hide(): void; resetTimer(): void }
    } | null {
        return document.querySelector<HTMLElement & {
            scrollIfNeeded(): void;
            forceScroll(): void;
            compensateScroll(targetY: number): void;
            autoScroll: boolean;
            workingIndicator: HTMLElement & { show(): void; hide(): void; resetTimer(): void }
        }>('chat-container');
    },

    _resetWorkingTimer(): void {
        const wi = this._container()?.workingIndicator;
        if (wi && !wi.hidden) wi.resetTimer();
    },

    _thinkingCounter: 0,
    _currentProfile: '',
    _currentClientType: '',
    _ctx: {} as Record<string, TurnContext & { thinkingMsg?: HTMLElement | null; thinkingChip?: HTMLElement | null }>,

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
        const ctx = this._getCtx(turnId, agentId);
        if (!ctx.msg) {
            const msg = document.createElement('chat-message');
            msg.setAttribute('type', 'agent');
            const meta = document.createElement('message-meta');
            meta.className = 'meta';
            const ts = timestamp || (() => {
                console.warn('[_ensureMsg] No timestamp provided for turn', turnId, '— showing placeholder');
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
            msgs.insertBefore(msg, firstQueued);
        } else {
            msgs.appendChild(msg);
        }
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
        meta.innerHTML = '<span class="ts">' + timestamp + '</span>';
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
            this._container()?.scrollIfNeeded();
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
            this._container()?.scrollIfNeeded();
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
        this._container()?.scrollIfNeeded();
    },

    collapseThinking(turnId: string, agentId: string, encodedHtml?: string): void {
        const ctx = this._getCtx(turnId, agentId);
        if (encodedHtml && ctx.thinkingBlock) {
            const content = (ctx.thinkingBlock as any).contentEl as Element | null;
            if (content) content.innerHTML = decodeBase64(encodedHtml);
        }
        this._collapseThinkingFor(ctx);
    },

    addToolCall(turnId: string, agentId: string, id: string, title: string, paramsJson?: string, kind?: string, isExternal?: boolean, initialStatus?: string): void {
        this.upsertToolChip(turnId, agentId, id, title, paramsJson, kind, initialStatus || 'pending');
    },

    upsertToolChip(turnId: string, agentId: string, id: string, title: string, paramsJson?: string, kind?: string, initialStatus?: string, timestamp?: string): void {
        this._resetWorkingTimer();
        let chip = document.querySelector('[data-chip-for="' + id + '"]') as HTMLElement | null;
        if (!chip) {
            const ctx = this._ensureMsg(turnId, agentId, timestamp);
            this._collapseThinkingFor(ctx);
            chip = document.createElement('tool-chip');
            chip.dataset.chipFor = id;
            ctx.meta!.appendChild(chip);
            ctx.meta!.classList.add('show');
            this._container()?.scrollIfNeeded();
        }
        chip.setAttribute('label', title);
        chip.setAttribute('status', initialStatus || 'pending');
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
        this._container()?.scrollIfNeeded();
    },

    updateSubAgent(sectionId: string, status: string, encodedResultHtml?: string): void {
        const el = document.getElementById('result-' + sectionId);
        if (el) {
            const resultHtml = encodedResultHtml ? decodeBase64(encodedResultHtml) : null;
            el.innerHTML = resultHtml || (status === 'completed' ? 'Completed' : '<span style="color:var(--error)">\u2716 Failed</span>');
        }
        const chip = document.querySelector('[data-chip-for="sa-' + sectionId + '"]');
        if (chip) chip.setAttribute('status', status === 'failed' ? 'failed' : 'complete');
        this._container()?.scrollIfNeeded();
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
        this._container()?.scrollIfNeeded();
    },

    addSessionSeparator(timestamp: string, agent: string = ''): void {
        const el = document.createElement('session-divider');
        el.setAttribute('timestamp', timestamp);
        if (agent) el.setAttribute('agent', agent);
        this._insertMsg(el);
    },

    showPlaceholder(text: string): void {
        this.clear();
        this._msgs().innerHTML = '<div class="placeholder">' + escHtml(text) + '</div>';
    },

    clear(): void {
        this.hideWorkingIndicator();
        this._msgs().innerHTML = '';
        this._ctx = {};
        this._thinkingCounter = 0;
        this._currentProfile = '';
    },

    finalizeTurn(turnId: string, statsJson?: string): void {
        this.hideWorkingIndicator();
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
        this._container()?.scrollIfNeeded();
        this._trimMessages();
        _showNotification('Agent turn complete', 'The agent has finished responding.');
    },

    showPermissionRequest(turnId: string, agentId: string, reqId: string, toolDisplayName: string, contextJson: string): void {
        this.disableQuickReplies();
        const ctx = this._ensureMsg(turnId, agentId);
        this._collapseThinkingFor(ctx);

        // Parse the structured context {question, args} produced by Java.
        // Falls back to generic label if the payload is a plain string (old code paths).
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
        bubble.innerHTML = questionHtml;
        ctx.msg!.appendChild(bubble);

        const actions = document.createElement('permission-request');
        actions.setAttribute('req-id', reqId);
        if (argsJson) actions.setAttribute('args', argsJson);
        ctx.msg!.appendChild(actions);

        this._container()?.scrollIfNeeded();
    },

    showAskUserRequest(turnId: string, agentId: string, reqId: string, question: string, options: string[]): void {
        this.disableQuickReplies();
        const ctx = this._ensureMsg(turnId, agentId);
        this._collapseThinkingFor(ctx);

        const bubble = document.createElement('message-bubble');
        bubble.dataset.reqId = reqId;
        bubble.innerHTML = escHtml(question).replace(/\n/g, '<br/>');
        ctx.msg!.appendChild(bubble);

        if (options?.length) {
            const replies = document.createElement('quick-replies');
            replies.dataset.reqId = reqId;
            (replies as any).options = options;
            ctx.msg!.appendChild(replies);
        }

        this._container()?.scrollIfNeeded();
        const actions = options?.length
            ? options.slice(0, Notification.maxActions || 2).map(o => ({action: o, title: o}))
            : undefined;
        _showNotification('Agent is asking you something', question, actions?.length ? actions : undefined);
    },

    showQuickReplies(options: string[]): void {
        this.disableQuickReplies();
        if (!options?.length) return;
        const el = document.createElement('quick-replies');
        (el as any).options = options;
        this._insertMsg(el);
        this._container()?.scrollIfNeeded();
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

    setPromptStats(model: string, multiplier: string): void {
        const row = this._lastAgentRow();
        if (!row) return;
        const meta = this._ensureStatsFooter(row);
        const chip = document.createElement('span');
        chip.className = 'turn-chip stats';
        chip.textContent = multiplier;
        chip.dataset.tip = model;
        chip.setAttribute('title', model);
        meta.appendChild(chip);
    },

    setCodeChangeStats(added: number, removed: number): void {
        const row = this._lastAgentRow();
        if (!row) return;
        const meta = this._ensureStatsFooter(row);
        (meta as any).setCodeChangeStats(added, removed);
    },

    /**
     * Renders the final turn summary, replacing any live stats footer with
     * a comprehensive chip strip showing duration, tokens, tools, and diff.
     */
    renderTurnSummary(stats: {
        duration: number; inputTokens: number; outputTokens: number;
        tools: number; added: number; removed: number;
        model: string; multiplier?: string;
    }): void {
        const row = this._lastAgentRow();
        if (!row) return;

        // Remove ALL existing stats footers so only the final summary remains
        document.querySelectorAll('message-meta.stats-footer').forEach(el => el.remove());

        const meta = document.createElement('message-meta') as any;
        meta.classList.add('stats-footer', 'show');
        row.appendChild(meta);

        // Duration chip: "45s" or "1m 23s"
        const dur = _formatDuration(stats.duration);
        if (dur) {
            const chip = document.createElement('span');
            chip.className = 'turn-chip stats';
            chip.textContent = '⏱ ' + dur;
            meta.appendChild(chip);
        }

        // Token chip: "1.2k in · 3.4k out"
        if (stats.inputTokens > 0 || stats.outputTokens > 0) {
            const chip = document.createElement('span');
            chip.className = 'turn-chip stats';
            chip.textContent = _formatTokens(stats.inputTokens) + ' in · ' + _formatTokens(stats.outputTokens) + ' out';
            chip.setAttribute('title', stats.model || '');
            meta.appendChild(chip);
        }

        // Tool count chip
        if (stats.tools > 0) {
            const chip = document.createElement('span');
            chip.className = 'turn-chip stats';
            chip.textContent = stats.tools + (stats.tools === 1 ? ' tool' : ' tools');
            meta.appendChild(chip);
        }

        // Multiplier chip (e.g. "5x")
        if (stats.multiplier) {
            const chip = document.createElement('span');
            chip.className = 'turn-chip stats';
            chip.textContent = stats.multiplier;
            chip.setAttribute('title', stats.model || '');
            meta.appendChild(chip);
        }

        // Diff chip: +42 −7
        if (stats.added > 0 || stats.removed > 0) {
            meta.setCodeChangeStats(stats.added, stats.removed);
        }
    },

    _lastAgentRow(): Element | null {
        const rows = document.querySelectorAll('.agent-row');
        return rows[rows.length - 1] ?? null;
    },

    /**
     * Ensures a stats footer exists on the given agent row.
     * Removes any previously placed stats footer from OTHER agent rows so
     * only the latest row displays live stats (fixes "stats on multiple messages").
     */
    _ensureStatsFooter(agentRow: Element): HTMLElement {
        // Remove footers from all OTHER rows
        document.querySelectorAll('message-meta.stats-footer').forEach(el => {
            if (el.parentElement !== agentRow) el.remove();
        });
        let meta = agentRow.querySelector('message-meta.stats-footer') as HTMLElement | null;
        if (!meta) {
            meta = document.createElement('message-meta');
            meta.classList.add('stats-footer', 'show');
            agentRow.appendChild(meta);
        }
        return meta;
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

        msgs.insertBefore(fragment, insertBefore);
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

        msgs.insertBefore(fragment, insertBefore);
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
        container?.scrollIfNeeded();
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
                bubble.textContent = (bubble.textContent || '') + '\n\n' + text;
                this._container()?.scrollIfNeeded();
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
        this._container()?.scrollIfNeeded();
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
        this._container()?.scrollIfNeeded();
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

};

export default ChatController;
