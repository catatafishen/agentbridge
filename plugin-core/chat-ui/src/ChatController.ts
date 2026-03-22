import {b64, escHtml} from './helpers';
import type {TurnContext} from './types';

const ChatController = {
    _msgs(): HTMLElement {
        return document.querySelector('#messages')!;
    },

    _container(): HTMLElement & {
        scrollIfNeeded(): void;
        forceScroll(): void;
        compensateScroll(targetY: number): void;
        workingIndicator: HTMLElement & { show(): void; hide(): void; resetTimer(): void }
    } | null {
        return document.querySelector<HTMLElement & {
            scrollIfNeeded(): void;
            forceScroll(): void;
            compensateScroll(targetY: number): void;
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

    _ensureMsg(turnId: string, agentId: string): TurnContext & {
        thinkingMsg?: HTMLElement | null;
        thinkingChip?: HTMLElement | null
    } {
        const ctx = this._getCtx(turnId, agentId);
        if (!ctx.msg) {
            const msg = document.createElement('chat-message');
            msg.setAttribute('type', 'agent');
            const meta = document.createElement('message-meta');
            meta.className = 'meta';
            const now = new Date();
            const ts = String(now.getHours()).padStart(2, '0') + ':' + String(now.getMinutes()).padStart(2, '0');
            const tsSpan = document.createElement('span');
            tsSpan.className = 'ts';
            tsSpan.textContent = ts;
            meta.appendChild(tsSpan);
            msg.appendChild(meta);
            const details = document.createElement('turn-details');
            msg.appendChild(details);
            this._msgs().appendChild(msg);
            ctx.msg = msg;
            ctx.meta = meta;
            ctx.details = details;

            if (agentId === 'main' && this._currentClientType) {
                msg.classList.add('client-' + this._currentClientType);
            }
        }
        return ctx;
    },

    _collapseThinkingFor(ctx: TurnContext & {
        thinkingMsg?: HTMLElement | null;
        thinkingChip?: HTMLElement | null
    } | null): void {
        if (!ctx?.thinkingBlock) return;
        ctx.thinkingBlock.removeAttribute('active');
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

    addUserMessage(text: string, timestamp: string, encodedBubbleHtml?: string): void {
        const msg = document.createElement('chat-message');
        msg.setAttribute('type', 'user');
        const meta = document.createElement('message-meta');
        meta.innerHTML = '<span class="ts">' + timestamp + '</span>';
        msg.appendChild(meta);
        const bubble = document.createElement('message-bubble');
        bubble.setAttribute('type', 'user');
        if (encodedBubbleHtml) {
            bubble.innerHTML = b64(encodedBubbleHtml);
        } else {
            bubble.textContent = text;
        }
        msg.appendChild(bubble);
        this._msgs().appendChild(msg);
        this._container()?.forceScroll();
    },

    appendAgentText(turnId: string, agentId: string, text: string): void {
        try {
            this._resetWorkingTimer();
            const ctx = this._getCtx(turnId, agentId);
            this._collapseThinkingFor(ctx);
            if (!ctx.textBubble) {
                if (!text.trim()) return;
                const c = this._ensureMsg(turnId, agentId);
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
                    (ctx.textBubble as any).finalize(b64(encodedHtml));
                } else {
                    const c = this._ensureMsg(turnId, agentId);
                    const bubble = document.createElement('message-bubble');
                    c.msg!.appendChild(bubble);
                    (bubble as any).finalize(b64(encodedHtml));
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

    addThinkingText(turnId: string, agentId: string, text: string): void {
        this._resetWorkingTimer();
        const ctx = this._ensureMsg(turnId, agentId);
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

    collapseThinking(turnId: string, agentId: string): void {
        const ctx = this._getCtx(turnId, agentId);
        this._collapseThinkingFor(ctx);
    },

    addToolCall(turnId: string, agentId: string, id: string, title: string, paramsJson?: string, kind?: string, isExternal?: boolean, initialStatus?: string): void {
        this.upsertToolChip(turnId, agentId, id, title, paramsJson, kind, initialStatus || 'pending');
    },

    upsertToolChip(turnId: string, agentId: string, id: string, title: string, paramsJson?: string, kind?: string, initialStatus?: string): void {
        this._resetWorkingTimer();
        let chip = document.querySelector('[data-chip-for="' + id + '"]') as HTMLElement | null;
        if (!chip) {
            const ctx = this._ensureMsg(turnId, agentId);
            this._collapseThinkingFor(ctx);
            chip = document.createElement('tool-chip');
            chip.dataset.chipFor = id;
            ctx.meta!.appendChild(chip);
            ctx.meta!.classList.add('show');
            this._container()?.scrollIfNeeded();
        }
        chip.setAttribute('label', title);
        chip.setAttribute('status', initialStatus || 'pending');
        if (kind) chip.setAttribute('kind', kind);
        if (paramsJson) chip.dataset.params = paramsJson;
    },

    markMcpHandled(id: string): void {
        const chip = document.querySelector('[data-chip-for="' + id + '"]');
        if (chip) chip.classList.add('is-agentbridge-tool');
        this.setToolChipState(id, 'running');
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
            chip.setAttribute('kind', kind);
        }
    },

    addOrphanMcpCall(_turnId: string, _agentId: string, _toolName: string): void {
        // No-op: orphan handling removed; replaced by ToolChipRegistry correlation
    },

    addSubAgent(turnId: string, agentId: string, sectionId: string, displayName: string, colorIndex: number, promptText?: string): void {
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
        promptBubble.innerHTML = '<span class="subagent-prefix subagent-c' + colorIndex + '">@' + escHtml(displayName) + '</span> ' + escHtml(promptText || '');
        ctx.msg!.appendChild(promptBubble);
        const msg = document.createElement('chat-message');
        msg.setAttribute('type', 'agent');
        msg.id = 'sa-' + sectionId;
        msg.classList.add('subagent-indent', 'subagent-c' + colorIndex);
        const meta = document.createElement('message-meta');
        meta.className = 'meta show';
        const now = new Date();
        const ts = String(now.getHours()).padStart(2, '0') + ':' + String(now.getMinutes()).padStart(2, '0');
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
        this._msgs().appendChild(msg);
        (chip as any).linkSection(msg);
        this._container()?.scrollIfNeeded();
    },

    updateSubAgent(sectionId: string, status: string, resultHtml?: string): void {
        const el = document.getElementById('result-' + sectionId);
        if (el) {
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
        if (kind) chip.setAttribute('kind', kind);
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
        this._msgs().appendChild(el);
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
        if (ctx) {
            ctx.thinkingBlock = null;
            ctx.textBubble = null;
        }
        // Mark any still-running sub-agent chips as complete (not failed)
        document.querySelectorAll('subagent-chip[status="running"]').forEach(c => c.setAttribute('status', 'complete'));
        // Mark any still-running tool chips as complete (not failed)
        document.querySelectorAll('tool-chip[status="running"]').forEach(c => c.setAttribute('status', 'complete'));
        this._container()?.scrollIfNeeded();
        this._trimMessages();
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

    showQuickReplies(options: string[]): void {
        this.disableQuickReplies();
        if (!options?.length) return;
        const el = document.createElement('quick-replies');
        (el as any).options = options;
        this._msgs().appendChild(el);
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
        const rows = document.querySelectorAll('.prompt-row');
        const row = rows[rows.length - 1];
        if (!row) return;
        let meta = row.querySelector('message-meta');
        if (!meta) {
            meta = document.createElement('message-meta');
            row.insertBefore(meta, row.firstChild);
        }
        meta.classList.add('show');
        const chip = document.createElement('span');
        chip.className = 'turn-chip stats';
        chip.textContent = multiplier;
        chip.dataset.tip = model;
        chip.setAttribute('title', model);
        meta.appendChild(chip);
    },

    setCodeChangeStats(added: number, removed: number): void {
        const rows = document.querySelectorAll('.prompt-row');
        const row = rows[rows.length - 1];
        if (!row) return;
        let meta = row.querySelector('message-meta');
        if (!meta) {
            meta = document.createElement('message-meta');
            row.insertBefore(meta, row.firstChild);
        }
        meta.classList.add('show');
        (meta as any).setCodeChangeStats(added, removed);
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

    restoreBatch(encodedHtml: string): void {
        const html = b64(encodedHtml);
        const temp = document.createElement('div');
        temp.innerHTML = html;
        const msgs = this._msgs();
        // Insert after the load-more banner (if present), otherwise before existing messages
        const loadMore = msgs.querySelector('load-more');
        const insertBefore = loadMore ? loadMore.nextSibling : msgs.firstChild;

        // Measure before insertion so we can restore the visual scroll position.
        // JCEF does not implement CSS scroll anchoring, so inserting content above the
        // viewport leaves scrollY at 0, pinning the user to the top and preventing a
        // second scroll-up trigger.
        const prevScrollY = window.scrollY;
        const prevHeight = document.body.scrollHeight;

        while (temp.firstChild) {
            msgs.insertBefore(temp.firstChild, insertBefore);
        }

        // If the user was near the top when load-more fired, compensate for the added
        // height so they are no longer pinned at scrollY=0 and can scroll up again.
        // We ensure a minimum scroll offset of 10px so JCEF always detects subsequent
        // scroll-up gestures, preventing the user from getting stuck at the absolute top.
        // Cap at 50px to keep user near top so subsequent scroll-up can trigger more loads.
        if (prevScrollY <= 30) {
            const addedHeight = document.body.scrollHeight - prevHeight;
            if (addedHeight > 0) {
                const targetScroll = Math.min(50, Math.max(10, prevScrollY + addedHeight));
                window.scrollTo(0, targetScroll);
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

    prependBatch(encodedHtml: string): void {
        const html = b64(encodedHtml);
        const temp = document.createElement('div');
        temp.innerHTML = html;
        const msgs = this._msgs();
        const loadMore = msgs.querySelector('load-more');
        const insertBefore = loadMore ? loadMore.nextSibling : msgs.firstChild;

        const prevHeight = document.body.scrollHeight;
        const prevScrollY = window.scrollY;
        const wasNearTop = prevScrollY <= 30;

        while (temp.firstChild) {
            msgs.insertBefore(temp.firstChild, insertBefore);
        }

        // Compensate scroll so user stays at same visual position.
        // We ensure a minimum scroll offset of 10px so JCEF always detects subsequent
        // scroll-up gestures, preventing the user from getting stuck at the absolute top.
        // Also cap at 50px to keep user near top so subsequent scroll-up can trigger more loads.
        const addedHeight = document.body.scrollHeight - prevHeight;
        if (addedHeight > 0) {
            const targetScroll = Math.min(50, Math.max(10, prevScrollY + addedHeight));
            const container = this._container();
            if (container) {
                container.compensateScroll(targetScroll);
            } else {
                window.scrollTo(0, targetScroll);
            }
        }

        // Continue loading if user was near top before scroll adjustment.
        // This allows continuous loading as user scrolls up through history.
        if (wasNearTop) {
            requestAnimationFrame(() => {
                const lm = msgs.querySelector<HTMLElement>('load-more:not([loading])');
                if (lm) lm.click();
            });
        }
    },

    showWorkingIndicator(): void {
        this._container()?.workingIndicator?.show();
        this._container()?.scrollIfNeeded();
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
        if (rows.length > 80) {
            const trimCount = rows.length - 80;
            for (let i = 0; i < trimCount; i++) rows[i].remove();
        }
    },

};

export default ChatController;
