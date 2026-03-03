import {b64, escHtml} from './helpers';
import type {TurnContext} from './types';

interface TurnStats {
    model?: string;
    mult?: string;
}

const ChatController = {
    _msgs(): HTMLElement {
        return document.querySelector('#messages')!;
    },

    _container(): HTMLElement & { scrollIfNeeded(): void; forceScroll(): void } | null {
        return document.querySelector('chat-container') as any;
    },

    _thinkingCounter: 0,
    _modelColors: {} as Record<string, number>,
    _nextModelColor: 0,
    _currentModel: '',
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
            // Apply model-based color class for main agent messages
            if (this._currentModel && agentId === 'main') {
                if (!(this._currentModel in this._modelColors)) {
                    this._modelColors[this._currentModel] = this._nextModelColor++ % 6;
                }
                msg.classList.add('model-c' + this._modelColors[this._currentModel]);
            }
            const meta = document.createElement('message-meta');
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

    addUserMessage(text: string, timestamp: string, ctxChipsHtml?: string): void {
        const msg = document.createElement('chat-message');
        msg.setAttribute('type', 'user');
        const meta = document.createElement('message-meta');
        meta.innerHTML = '<span class="ts">' + timestamp + '</span>' + (ctxChipsHtml || '');
        msg.appendChild(meta);
        const bubble = document.createElement('message-bubble');
        bubble.setAttribute('type', 'user');
        bubble.textContent = text;
        msg.appendChild(bubble);
        this._msgs().appendChild(msg);
        this._container()?.forceScroll();
    },

    appendAgentText(turnId: string, agentId: string, text: string): void {
        try {
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
                if (ctx.msg && !ctx.msg.querySelector('message-bubble, tool-section, thinking-block')) {
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

    addToolCall(turnId: string, agentId: string, id: string, title: string, paramsJson?: string, kind?: string): void {
        const ctx = this._ensureMsg(turnId, agentId);
        this._collapseThinkingFor(ctx);
        const section = document.createElement('tool-section');
        section.id = id;
        section.setAttribute('title', title);
        if (paramsJson) section.setAttribute('params', paramsJson);
        ctx.details!.appendChild(section);
        const chip = document.createElement('tool-chip');
        chip.setAttribute('label', title);
        chip.setAttribute('status', 'running');
        if (kind) chip.setAttribute('kind', kind);
        (chip as HTMLElement).dataset.chipFor = id;
        (chip as any).linkSection(section);
        ctx.meta!.appendChild(chip);
        ctx.meta!.classList.add('show');
        this._container()?.scrollIfNeeded();
    },

    updateToolCall(id: string, status: string, resultHtml?: string): void {
        const section = document.getElementById(id);
        if (section) {
            (section as any).result = (typeof resultHtml === 'string') ? resultHtml : 'Completed';
            if (status === 'failed') section.classList.add('failed');
        }
        const chip = document.querySelector('[data-chip-for="' + id + '"]');
        if (chip) chip.setAttribute('status', status === 'failed' ? 'failed' : 'complete');
    },

    addSubAgent(turnId: string, agentId: string, sectionId: string, displayName: string, colorIndex: number, promptText?: string): void {
        const ctx = this._ensureMsg(turnId, agentId);
        this._collapseThinkingFor(ctx);
        ctx.textBubble = null;
        const chip = document.createElement('subagent-chip');
        chip.setAttribute('label', displayName);
        chip.setAttribute('status', 'running');
        chip.setAttribute('color-index', String(colorIndex));
        (chip as HTMLElement).dataset.chipFor = 'sa-' + sectionId;
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
        const now = new Date();
        const ts = String(now.getHours()).padStart(2, '0') + ':' + String(now.getMinutes()).padStart(2, '0');
        const tsSpan = document.createElement('span');
        tsSpan.className = 'ts';
        tsSpan.textContent = ts;
        meta.appendChild(tsSpan);
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

    addSubAgentToolCall(subAgentDomId: string, toolDomId: string, title: string, paramsJson?: string): void {
        const msg = document.getElementById('sa-' + subAgentDomId);
        if (!msg) return;
        const meta = msg.querySelector('message-meta');
        const section = document.createElement('tool-section');
        section.id = toolDomId;
        section.setAttribute('title', title);
        if (paramsJson) section.setAttribute('params', paramsJson);
        const details = msg.querySelector('turn-details');
        if (details) details.appendChild(section);
        else msg.appendChild(section);
        const chip = document.createElement('tool-chip');
        chip.setAttribute('label', title);
        chip.setAttribute('status', 'running');
        chip.dataset.chipFor = toolDomId;
        (chip as any).linkSection(section);
        if (meta) {
            meta.appendChild(chip);
            meta.classList.add('show');
        }
        this._container()?.scrollIfNeeded();
    },

    addError(message: string): void {
        const el = document.createElement('status-message');
        el.setAttribute('type', 'error');
        el.setAttribute('message', message);
        this._msgs().appendChild(el);
        this._container()?.scrollIfNeeded();
    },

    addInfo(message: string): void {
        const el = document.createElement('status-message');
        el.setAttribute('type', 'info');
        el.setAttribute('message', message);
        this._msgs().appendChild(el);
        this._container()?.scrollIfNeeded();
    },

    addSessionSeparator(timestamp: string): void {
        const el = document.createElement('session-divider');
        el.setAttribute('timestamp', timestamp);
        this._msgs().appendChild(el);
    },

    showPlaceholder(text: string): void {
        this.clear();
        this._msgs().innerHTML = '<div class="placeholder">' + escHtml(text) + '</div>';
    },

    clear(): void {
        this._msgs().innerHTML = '';
        this._ctx = {};
        this._thinkingCounter = 0;
        this._modelColors = {};
        this._nextModelColor = 0;
        this._currentModel = '';
    },

    finalizeTurn(turnId: string, statsJson?: string): void {
        const ctx = this._ctx[turnId + '-main'];
        if (ctx?.textBubble && !ctx.textBubble.textContent?.trim()) {
            ctx.textBubble.remove();
        }
        let meta: Element | null = ctx?.meta ?? null;
        if (!meta) {
            const rows = this._msgs().querySelectorAll('chat-message[type="agent"]:not(.subagent-indent)');
            if (rows.length) meta = rows[rows.length - 1].querySelector('message-meta');
        }
        if (statsJson && meta) {
            const stats: TurnStats = typeof statsJson === 'string' ? JSON.parse(statsJson) : statsJson;
            // Model multiplier is shown on the user prompt only, not on agent responses
        }
        if (ctx) {
            ctx.thinkingBlock = null;
            ctx.textBubble = null;
        }
        this._container()?.scrollIfNeeded();
        this._trimMessages();
    },

    showPermissionRequest(turnId: string, agentId: string, reqId: string, toolDisplayName: string, argsJson: string): void {
        this.disableQuickReplies();
        const ctx = this._ensureMsg(turnId, agentId);
        this._collapseThinkingFor(ctx);

        // Question bubble (no chip/tool-section — addToolCall handles those)
        const bubble = document.createElement('message-bubble');
        bubble.innerHTML = `Can I use <strong>${toolDisplayName}</strong>?`;
        ctx.msg!.appendChild(bubble);

        // Allow/Deny actions (below the bubble, still inside chat-message)
        const actions = document.createElement('permission-request');
        actions.setAttribute('req-id', reqId);
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

    setCurrentModel(modelId: string): void {
        this._currentModel = modelId;
    },

    restoreBatch(encodedHtml: string): void {
        const html = b64(encodedHtml);
        const temp = document.createElement('div');
        temp.innerHTML = html;
        const msgs = this._msgs();
        // Insert after the load-more banner (if present), otherwise before existing messages
        const loadMore = msgs.querySelector('load-more');
        const insertBefore = loadMore ? loadMore.nextSibling : msgs.firstChild;
        while (temp.firstChild) {
            msgs.insertBefore(temp.firstChild, insertBefore);
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

    _trimMessages(): void {
        const msgs = this._msgs();
        if (!msgs) return;
        const rows = Array.from(msgs.children).filter(
            c => c.tagName === 'CHAT-MESSAGE' || c.tagName === 'STATUS-MESSAGE'
        );
        if (rows.length > 80) {
            const trimCount = rows.length - 80;
            for (let i = 0; i < trimCount; i++) rows[i].remove();
            const notice = document.createElement('status-message');
            notice.setAttribute('type', 'info');
            notice.setAttribute('message', `${trimCount} older messages trimmed for performance`);
            msgs.insertBefore(notice, msgs.firstChild);
        }
    },

};

export default ChatController;
