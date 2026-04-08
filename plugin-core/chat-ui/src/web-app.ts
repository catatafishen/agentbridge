/**
 * Web App — main page logic for the PWA / remote web UI.
 *
 * Loaded after chat-components.js (which provides ChatController on globalThis).
 * Connects to the IDE's ChatWebServer via SSE and HTTP.
 */

/// <reference path="./globals.d.ts" />

// ── Bridge: replaces native Kotlin bridge with fetch-based implementations ──

globalThis._bridge = {
    openFile: () => {
    },
    openUrl: (url) => {
        globalThis.open(url, '_blank');
    },
    setCursor: (c) => {
        document.body.style.cursor = c;
    },
    loadMore: () => webPost('/load-more', {}),
    quickReply: (text) => webPost('/reply', {text}),
    permissionResponse: (data) => {
        const parts = data.split(':');
        const resp = parts.pop()!;
        const reqId = parts.join(':');
        void webPost('/permission', {reqId, response: resp});
    },
    openScratch: () => {
    },
    showToolPopup: () => {
    },
    cancelNudge: (id) => webPost('/cancel-nudge', {id}),
};

function webPost(path: string, body: Record<string, unknown>): Promise<Response> {
    return fetch(path, {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify(body),
    });
}

// Expose globally so Java-injected eval() scripts and SSE events can call them
(globalThis as Record<string, unknown>).webPost = webPost;

// ── DOM refs ────────────────────────────────────────────────────────────────

const statusDot = document.getElementById('ab-status')!;
const modelEl = document.getElementById('ab-model')!;
const offlineEl = document.getElementById('ab-offline')!;
const inputEl = document.getElementById('ab-input') as HTMLTextAreaElement;
const sendBtn = document.getElementById('ab-send')!;
const chatEl = document.querySelector('chat-container')!;
const menuBtn = document.getElementById('ab-menu-btn')!;
const menuEl = document.getElementById('ab-menu')!;
const menuVersionEl = document.getElementById('ab-menu-version')!;
const menuReloadBtn = document.getElementById('ab-menu-reload')!;
const menuModelSel = document.getElementById('ab-menu-model') as HTMLSelectElement;
const menuDisconnectBtn = document.getElementById('ab-menu-disconnect')!;
const connectPageEl = document.getElementById('ab-connect-page')!;
const connectProfileSel = document.getElementById('ab-connect-profile') as HTMLSelectElement;
const connectBtn = document.getElementById('ab-connect-btn') as HTMLButtonElement;
const connectStatusEl = document.getElementById('ab-connect-status')!;
const connectStopBtn = document.getElementById('ab-connect-stop-btn') as HTMLButtonElement;
const mcpDot = document.getElementById('ab-mcp-dot')!;
const mcpText = document.getElementById('ab-mcp-text')!;
const chatAreaEl = document.getElementById('ab-chat')!;
const footerEl = document.getElementById('ab-footer')!;
const menuModelSection = document.getElementById('ab-menu-model-section')!;

// ── Auto-scroll: track whether user is near the bottom ──────────────────────

let atBottom = true;
chatEl.addEventListener('scroll', () => {
    atBottom = chatEl.scrollHeight - chatEl.scrollTop - chatEl.clientHeight < 120;
}, {passive: true});

function scrollToBottom(): void {
    chatEl.scrollTop = chatEl.scrollHeight;
}

// ── Track agent state via ChatController overrides ──────────────────────────

let agentRunning = false;

const origShowWorkingIndicator = ChatController.showWorkingIndicator.bind(ChatController);
ChatController.showWorkingIndicator = function () {
    origShowWorkingIndicator();
    agentRunning = true;
    updateButtons();
};

const origFinalizeTurn = ChatController.finalizeTurn.bind(ChatController);
ChatController.finalizeTurn = function (...args: unknown[]) {
    (origFinalizeTurn as (...a: unknown[]) => void)(...args);
    agentRunning = false;
    updateButtons();
};

const origCancelAllRunning = ChatController.cancelAllRunning.bind(ChatController);
ChatController.cancelAllRunning = function () {
    origCancelAllRunning();
    agentRunning = false;
    updateButtons();
};

// ── Track model display ─────────────────────────────────────────────────────

const origSetCurrentModel = ChatController.setCurrentModel.bind(ChatController);
ChatController.setCurrentModel = function (m: string) {
    origSetCurrentModel(m);
    modelEl.textContent = m ? m.substring(m.lastIndexOf('/') + 1) : '';
    syncModelSelect(m);
};

function updateButtons(): void {
    statusDot.className = agentRunning ? 'running' : 'connected';
    mcpDot.className = agentRunning ? 'running' : 'connected';
    mcpText.textContent = agentRunning ? 'Running' : 'Ready';
    connectStopBtn.hidden = !agentRunning;
    sendBtn.innerHTML = globalThis.ICON_SVG + '<span>' + (agentRunning ? 'Nudge' : 'Send') + '</span>';
}

const _origSetClientType = ChatController.setClientType.bind(ChatController);
ChatController.setClientType = (type: string, iconSvg?: string) => {
    _origSetClientType(type);  // preserves _currentClientType used for message bubble styling
    if (iconSvg) {
        globalThis.ICON_SVG = iconSvg.replace(
            '<svg',
            '<svg style="vertical-align:text-bottom;margin-right:4px" fill="currentColor" width="14" height="14"',
        );
    }
    updateButtons();
};

// ── Connection state helpers ────────────────────────────────────────────────

interface ProfileInfo {
    id: string;
    name: string;
}

interface ModelInfo {
    id: string;
    name: string;
}

interface ServerInfo {
    model?: string;
    running?: boolean;
    version?: string;
    connected?: boolean;
    models?: ModelInfo[];
    profiles?: ProfileInfo[];
    vapidKey?: string;
}

function showChatView(): void {
    connectPageEl.hidden = true;
    chatAreaEl.style.display = '';
    footerEl.style.display = '';
    menuDisconnectBtn.style.display = '';
    menuModelSection.style.display = '';
}

function showConnectView(profiles?: ProfileInfo[]): void {
    chatAreaEl.style.display = 'none';
    footerEl.style.display = 'none';
    connectPageEl.hidden = false;
    menuDisconnectBtn.style.display = 'none';
    menuModelSection.style.display = 'none';
    connectStatusEl.textContent = '';
    connectBtn.disabled = false;
    connectBtn.textContent = 'Connect';
    connectStopBtn.hidden = !agentRunning;
    mcpDot.className = agentRunning ? 'running' : 'connected';
    mcpText.textContent = agentRunning ? 'Running' : 'Ready';
    if (profiles?.length) {
        const prev = connectProfileSel.value;
        connectProfileSel.innerHTML = profiles
            .map(p => `<option value="${p.id}">${p.name}</option>`)
            .join('');
        if (prev) connectProfileSel.value = prev;
    }
}

// ── Populate model select from info ─────────────────────────────────────────

function populateModels(models?: ModelInfo[], currentModelId?: string): void {
    menuModelSel.innerHTML = (models || [])
        .map(m => `<option value="${m.id}">${m.name}</option>`)
        .join('');
    if (currentModelId) syncModelSelect(currentModelId);
}

function syncModelSelect(modelId: string): void {
    if (modelId) menuModelSel.value = modelId;
}

// ── Info fetch ──────────────────────────────────────────────────────────────

let pluginVersion = '';

fetch('/info')
    .then(r => r.json() as Promise<ServerInfo>)
    .then(info => {
        if (info.model) {
            modelEl.textContent = info.model.substring(info.model.lastIndexOf('/') + 1);
        }
        agentRunning = info.running || false;
        updateButtons();
        pluginVersion = info.version || '';
        populateModels(info.models, info.model);
        if (info.connected) showChatView();
        else showConnectView(info.profiles);
    })
    .catch(() => {
    });

// ── Hamburger menu ──────────────────────────────────────────────────────────

menuBtn.addEventListener('click', (e: MouseEvent) => {
    e.stopPropagation();
    const isOpen = !menuEl.hidden;
    menuEl.hidden = isOpen;
    if (!isOpen) menuVersionEl.textContent = 'Plugin v' + (pluginVersion || '?');
});

document.addEventListener('click', (e: MouseEvent) => {
    if (!menuEl.hidden && !menuEl.contains(e.target as Node)) menuEl.hidden = true;
});

// Hard reload — navigate to /?v=timestamp to bypass HTTP cache
menuReloadBtn.addEventListener('click', () => {
    menuEl.hidden = true;
    if ('serviceWorker' in navigator) {
        void navigator.serviceWorker.getRegistrations()
            .then(regs => Promise.all(regs.map(r => r.unregister())));
        if ('caches' in globalThis) {
            void caches.keys().then(keys => Promise.all(keys.map(k => caches.delete(k))));
        }
    }
    setTimeout(() => {
        location.href = '/?v=' + Date.now();
    }, 150);
});

// Model select change
menuModelSel.addEventListener('change', () => {
    const id = menuModelSel.value;
    if (id) void webPost('/set-model', {modelId: id});
});

// Disconnect
menuDisconnectBtn.addEventListener('click', () => {
    menuEl.hidden = true;
    void webPost('/disconnect', {});
});

// ── Connect page ────────────────────────────────────────────────────────────

connectBtn.addEventListener('click', () => {
    const profileId = connectProfileSel.value;
    if (!profileId) return;
    connectBtn.disabled = true;
    connectBtn.textContent = 'Connecting\u2026';
    connectStatusEl.textContent = '';
    webPost('/connect', {profileId}).catch(() => {
        connectBtn.disabled = false;
        connectBtn.textContent = 'Connect';
        connectStatusEl.textContent = 'Connection error \u2014 check the IDE plugin.';
    });
});

connectStopBtn.addEventListener('click', () => {
    void webPost('/stop', {});
});

// ── Connected / Disconnected handlers (called from SSE broadcastTransient) ──

function handleConnected(modelsJsonStr: string, _profilesJsonStr: string): void {
    try {
        const models: ModelInfo[] = JSON.parse(modelsJsonStr || '[]');
        populateModels(models, '');
        fetch('/info')
            .then(r => r.json() as Promise<ServerInfo>)
            .then(info => {
                if (info.model) {
                    modelEl.textContent = info.model.substring(info.model.lastIndexOf('/') + 1);
                }
                populateModels(info.models, info.model);
            })
            .catch(() => {
            });
        showChatView();
    } catch {
        showChatView();
    }
}

function handleDisconnected(profilesJsonStr: string): void {
    try {
        const profiles: ProfileInfo[] = JSON.parse(profilesJsonStr || '[]');
        showConnectView(profiles);
    } catch {
        showConnectView([]);
    }
}

// Expose globally for SSE eval
(globalThis as Record<string, unknown>).handleConnected = handleConnected;
(globalThis as Record<string, unknown>).handleDisconnected = handleDisconnected;

// ── State load + SSE connect ────────────────────────────────────────────────

interface SseEvent {
    seq?: number;
    js?: string;
    notification?: boolean;
    title?: string;
    body?: string;
}

let lastSeq = 0;
let sseRetry: ReturnType<typeof setTimeout> | null = null;

fetch('/state')
    .then(r => r.json() as Promise<{ events?: SseEvent[]; seq?: number; domMessageLimit?: number }>)
    .then(st => {
        if (st.domMessageLimit != null) ChatController.setDomMessageLimit(st.domMessageLimit);
        (st.events || []).forEach(ev => processEvent(ev, true));
        lastSeq = st.seq || 0;
        requestAnimationFrame(scrollToBottom);
        connectSSE();
    })
    .catch(() => connectSSE());

// ── Event processing ────────────────────────────────────────────────────────

function processEvent(ev: SseEvent, replaying: boolean): void {
    if (ev.notification) {
        if (!replaying) showNotification(ev.title || 'AgentBridge', ev.body || '');
        return;
    }
    if (ev.js) {
        try {
            // Indirect eval for global scope execution
            const indirectEval = eval;
            indirectEval(ev.js);
        } catch (e) {
            console.warn('event eval:', e, ev.js?.substring(0, 80));
        }
    }
    if (ev.seq && ev.seq > lastSeq) lastSeq = ev.seq;
    if (!replaying && atBottom) requestAnimationFrame(scrollToBottom);
}

// ── SSE ─────────────────────────────────────────────────────────────────────

let currentEs: EventSource | null = null;

function connectSSE(): void {
    if (currentEs) {
        currentEs.close();
        currentEs = null;
    }
    const es = new EventSource('/events?from=' + lastSeq);
    currentEs = es;
    es.onopen = () => {
        statusDot.className = agentRunning ? 'running' : 'connected';
        offlineEl.classList.remove('visible');
    };
    es.onmessage = (e: MessageEvent) => {
        try {
            const ev: SseEvent = JSON.parse(e.data as string);
            if (!ev.seq || ev.seq > lastSeq) {
                processEvent(ev, false);
                if (ev.seq) lastSeq = ev.seq;
            }
        } catch {
            // ignore parse errors
        }
    };
    es.onerror = () => {
        es.close();
        if (currentEs === es) currentEs = null;
        statusDot.className = '';
        offlineEl.classList.add('visible');
        if (sseRetry) clearTimeout(sseRetry);
        sseRetry = setTimeout(connectSSE, 3000);
    };
}

// Reconnect SSE when the page becomes visible again — mobile browsers
// freeze/kill background connections, so we need to re-establish on wake.
document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') {
        if (!currentEs || currentEs.readyState === EventSource.CLOSED) {
            if (sseRetry) clearTimeout(sseRetry);
            connectSSE();
        }
    }
});

// ── Notifications ───────────────────────────────────────────────────────────

function showNotification(title: string, body: string): void {
    // Skip if the page is visible — the user can see the chat directly
    if (document.visibilityState === 'visible') return;
    if (navigator.serviceWorker?.controller) {
        navigator.serviceWorker.controller.postMessage({
            type: 'SHOW_NOTIFICATION', title, body,
        });
    } else if ('Notification' in globalThis && Notification.permission === 'granted') {
        try {
            new Notification(title, {body, icon: '/icon.svg', tag: 'ab'});
        } catch {
            // ignore
        }
    }
}

function subscribePush(vapidKey: string): void {
    if (!('serviceWorker' in navigator && 'PushManager' in globalThis)) {
        console.warn('[AB] Push not supported');
        return;
    }
    void navigator.serviceWorker.ready.then(reg => {
        void reg.pushManager.getSubscription().then(existing => {
            if (existing) {
                console.log('[AB] Push subscription exists');
                webPost('/push-subscribe', existing.toJSON() as Record<string, unknown>)
                    .catch(e => console.error('[AB] Failed to post existing sub:', e));
                return;
            }
            try {
                const appKey = Uint8Array.from(
                    atob(vapidKey.replaceAll('-', '+').replaceAll('_', '/')),
                    c => c.codePointAt(0) ?? 0,
                );
                void reg.pushManager.subscribe({userVisibleOnly: true, applicationServerKey: appKey})
                    .then(sub => {
                        console.log('[AB] Subscribed to push');
                        webPost('/push-subscribe', sub.toJSON() as Record<string, unknown>)
                            .catch(e => console.error('[AB] Failed to post new sub:', e));
                    })
                    .catch(e => console.error('[AB] Subscribe failed:', e));
            } catch (e) {
                console.error('[AB] Push key decode error:', e);
            }
        }).catch(e => console.error('[AB] getSubscription error:', e));
    }).catch(e => console.error('[AB] serviceWorker.ready error:', e));
}

function reqNotifPerm(): void {
    if ('Notification' in globalThis) {
        console.log('[AB] Notification permission:', Notification.permission);
        if (Notification.permission === 'default') {
            void Notification.requestPermission().then(p => {
                console.log('[AB] Permission result:', p);
                if (p === 'granted') {
                    fetch('/info')
                        .then(r => r.json() as Promise<ServerInfo>)
                        .then(info => {
                            console.log('[AB] VAPID key present:', !!info.vapidKey);
                            if (info.vapidKey) subscribePush(info.vapidKey);
                        })
                        .catch(e => console.error('[AB] Failed to fetch info:', e));
                }
            }).catch(e => console.error('[AB] requestPermission error:', e));
        }
    } else {
        console.warn('[AB] Notification API not supported');
    }
}

document.addEventListener('click', reqNotifPerm, {once: true});

// ── Quick-reply bridge (ask_user responses) ─────────────────────────────────

document.addEventListener('quick-reply', (e: Event) => {
    globalThis._bridge?.quickReply((e as CustomEvent).detail.text);
});

// ── Input auto-resize ───────────────────────────────────────────────────────

inputEl.addEventListener('input', () => {
    inputEl.style.height = 'auto';
    inputEl.style.height = Math.min(inputEl.scrollHeight, 120) + 'px';
});

inputEl.addEventListener('keydown', (e: KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        sendAction();
    }
});

// ── Send/nudge actions ──────────────────────────────────────────────────────

sendBtn.onclick = sendAction;

function sendAction(): void {
    const t = inputEl.value.trim();
    if (!t) return;
    inputEl.value = '';
    inputEl.style.height = 'auto';
    void webPost(agentRunning ? '/nudge' : '/prompt', {text: t});
}

// ── Register service worker ─────────────────────────────────────────────────

if ('serviceWorker' in navigator) {
    void navigator.serviceWorker.register('/sw.js')
        .then(() => {
            console.log('[AB] Service worker registered');
            if (Notification.permission === 'granted') {
                console.log('[AB] Notification permission granted, subscribing to push...');
                fetch('/info')
                    .then(r => r.json() as Promise<ServerInfo>)
                    .then(info => {
                        if (info.vapidKey) subscribePush(info.vapidKey);
                    })
                    .catch(e => console.error('[AB] Push subscribe error:', e));
            } else {
                console.log('[AB] Notification permission: ' + Notification.permission);
            }
        })
        .catch(e => console.error('[AB] SW register failed:', e));
} else {
    console.warn('[AB] Service Worker not supported');
}
