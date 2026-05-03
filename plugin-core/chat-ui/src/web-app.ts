/**
 * Web App — main page logic for the PWA / remote web UI.
 *
 * Loaded after chat-components.js (which provides ChatController on globalThis).
 * Connects to the IDE's ChatWebServer via SSE and HTTP.
 */

/// <reference path="./globals.d.ts" />

import {PaneSwiper} from './components/PaneSwiper';
import {FileViewer} from './components/FileViewer';
import {FileNav} from './components/FileNav';
import {ReviewView} from './components/ReviewView';
import {TodoView} from './components/TodoView';
import type {ToolCallsView} from './components/ToolCallsView';
import {SearchView} from './components/SearchView';
import {SessionView} from './components/SessionView';

// Register PWA-only custom elements
customElements.define('pane-swiper', PaneSwiper);
customElements.define('file-viewer', FileViewer);
customElements.define('file-nav', FileNav);
customElements.define('review-view', ReviewView);
customElements.define('todo-view', TodoView);
customElements.define('search-view', SearchView);
customElements.define('session-view', SessionView);

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
    autoScrollDisabled: () => {
        atBottom = false;
        updateScrollFab();
    },
    autoScrollEnabled: () => {
        atBottom = true;
        unreadCount = 0;
        updateScrollFab();
    },
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
const chatAreaEl = document.getElementById('ab-chat')!;
const footerEl = document.getElementById('ab-footer')!;

// ── Pane swiper: file viewer (left) + chat (right) ─────────────────────────

const swiper = document.createElement('pane-swiper') as PaneSwiper;
chatAreaEl.parentElement!.insertBefore(swiper, chatAreaEl);

// Wait for custom element to initialize
requestAnimationFrame(() => {
    // Move chat area into the right pane
    swiper.rightPane.appendChild(chatAreaEl);

    // Set labels on existing panes
    swiper.setTabLabel(0, 'Files');
    swiper.setTabLabel(1, 'Chat');

    // Create file viewer in the left pane
    const fileViewer = document.createElement('file-viewer') as FileViewer;
    swiper.leftPane.appendChild(fileViewer);

    // Create and attach file nav inside the viewer
    const fileNav = document.createElement('file-nav') as FileNav;
    fileViewer.navSlot.appendChild(fileNav);

    // Wire file nav to the server endpoints
    fileNav.configure(
        async (dir: string) => {
            const resp = await fetch(`/list-files?path=${encodeURIComponent(dir)}`);
            if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
            const data = await resp.json() as {
                entries: Array<{ name: string; path: string; isDirectory: boolean; size?: number }>
            };
            return data.entries;
        },
        (path: string) => openFileInViewer(path),
    );

    // Wire the FAB to toggle the file nav dropdown
    fileViewer.wireFab(fileNav);

    // Handle clicks on recent files in the file viewer empty state
    fileViewer.addEventListener('open-file', (e) => {
        const path = (e as CustomEvent).detail?.path;
        if (path) openFileInViewer(path);
    });

    // Override bridge.openFile to fetch and display in the viewer
    globalThis._bridge!.openFile = (href: string) => {
        let path = href;
        let line: number | undefined;
        if (path.startsWith('openfile://')) {
            path = path.substring('openfile://'.length);
        }
        // Extract line number: path:42
        const colonIdx = path.lastIndexOf(':');
        if (colonIdx > 0) {
            const maybeLine = Number.parseInt(path.substring(colonIdx + 1), 10);
            if (!Number.isNaN(maybeLine)) {
                line = maybeLine;
                path = path.substring(0, colonIdx);
            }
        }
        openFileInViewer(path, line);
    };

    async function openFileInViewer(path: string, line?: number): Promise<void> {
        try {
            const resp = await fetch(`/file?path=${encodeURIComponent(path)}`);
            if (!resp.ok) {
                console.error(`[AB] File fetch failed: HTTP ${resp.status} for ${path}`);
                return;
            }
            const data = await resp.json() as { content: string; path: string };
            fileViewer.showFile(data.path, data.content);
            fileNav.showPath(data.path);
            swiper.switchTo(0); // Switch to file viewer pane
            if (line) {
                requestAnimationFrame(() => fileViewer.scrollToLine(line));
            }
        } catch (e) {
            console.error('[AB] File fetch error:', e);
        }
    }

    // Add side panel panes mirroring the IDE side panel tabs.
    const reviewPane = swiper.addPane('Diff');
    const reviewView = document.createElement('review-view') as ReviewView;
    reviewPane.appendChild(reviewView);

    const todoPane = swiper.addPane('Plan');
    const todoView = document.createElement('todo-view') as TodoView;
    todoPane.appendChild(todoView);

    const toolsPane = swiper.addPane('MCP');
    const toolCallsView = document.createElement('tool-calls-view') as ToolCallsView;
    toolsPane.appendChild(toolCallsView);

    const searchPane = swiper.addPane('Prompt DB');
    const searchView = document.createElement('search-view') as SearchView;
    searchPane.appendChild(searchView);

    const sessionPane = swiper.addPane('Stats');
    const sessionView = document.createElement('session-view') as SessionView;
    sessionPane.appendChild(sessionView);

    // Settings pane — MCP status, ACP connect/disconnect, model, theme, reload, version
    const settingsPane = swiper.addPane('Settings');
    settingsPane.id = 'ab-settings-pane';
    settingsPane.innerHTML = `
        <div class="ab-settings-scroll">
            <div class="ab-card" id="ab-mcp-card">
                <div class="ab-card-header">MCP Server</div>
                <div class="ab-card-content">
                    <div class="ab-status-row">
                        <span class="ab-status-label">Status:</span>
                        <span class="ab-status-indicator">
                            <span id="ab-mcp-dot" class="ab-status-dot"></span>
                            <span id="ab-mcp-text">Initializing</span>
                        </span>
                    </div>
                </div>
            </div>
            <div class="ab-card" id="ab-acp-card">
                <div class="ab-card-header">
                    <span>ACP Connection</span>
                    <button id="ab-connect-stop-btn" hidden class="ab-card-stop-btn">⏹</button>
                </div>
                <div class="ab-card-content" id="ab-acp-connect-section">
                    <select id="ab-connect-profile" aria-label="ACP profile"></select>
                    <button id="ab-connect-btn">Connect</button>
                    <div id="ab-connect-status"></div>
                </div>
                <div class="ab-card-content" id="ab-acp-disconnect-section" hidden>
                    <button id="ab-menu-disconnect" class="ab-btn-danger">Disconnect ACP</button>
                </div>
            </div>
            <div class="ab-card">
                <div class="ab-card-header">Model</div>
                <div class="ab-card-content">
                    <select id="ab-menu-model" class="ab-settings-select" aria-label="Model"></select>
                </div>
            </div>
            <div class="ab-card">
                <div class="ab-card-header">Theme</div>
                <div class="ab-card-content">
                    <select id="ab-theme-select" class="ab-settings-select" aria-label="Theme"></select>
                    <div id="ab-theme-status" class="ab-settings-hint"></div>
                </div>
            </div>
            <div class="ab-card">
                <div class="ab-card-header">Diagnostics</div>
                <div class="ab-card-content ab-settings-actions">
                    <button id="ab-menu-reload" class="ab-btn-secondary">🔄 Hard reload</button>
                    <div id="ab-menu-version" class="ab-settings-hint"></div>
                </div>
            </div>
        </div>`;

    const connectProfileSel = settingsPane.querySelector('#ab-connect-profile') as HTMLSelectElement;
    const connectBtn = settingsPane.querySelector('#ab-connect-btn') as HTMLButtonElement;
    const connectStatusEl = settingsPane.querySelector('#ab-connect-status') as HTMLElement;
    const connectStopBtn = settingsPane.querySelector('#ab-connect-stop-btn') as HTMLButtonElement;
    const menuDisconnectBtn = settingsPane.querySelector('#ab-menu-disconnect') as HTMLElement;
    const menuModelSel = settingsPane.querySelector('#ab-menu-model') as HTMLSelectElement;
    const menuReloadBtn = settingsPane.querySelector('#ab-menu-reload') as HTMLElement;
    const themeSelect = settingsPane.querySelector('#ab-theme-select') as HTMLSelectElement;
    const themeStatus = settingsPane.querySelector('#ab-theme-status') as HTMLElement;

    // ── Settings pane: event handlers ────────────────────────────────────────

    menuReloadBtn.addEventListener('click', () => {
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

    menuModelSel.addEventListener('change', () => {
        const id = menuModelSel.value;
        if (id) void webPost('/set-model', {modelId: id});
    });

    menuDisconnectBtn.addEventListener('click', () => void webPost('/disconnect', {}));

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

    connectStopBtn.addEventListener('click', () => void webPost('/stop', {}));

    // ── Settings pane: theme dropdown ────────────────────────────────────────

    fetch('/themes')
        .then(r => r.json() as Promise<Array<{ name: string; dark: boolean; current: boolean }>>)
        .then(themes => {
            themeSelect.innerHTML = themes
                .map(t => `<option value="${t.name}" ${t.current ? 'selected' : ''}>${t.name}${t.dark ? ' 🌙' : ' ☀️'}</option>`)
                .join('');
        })
        .catch(() => {
            themeSelect.hidden = true;
        });

    themeSelect.addEventListener('change', () => {
        const name = themeSelect.value;
        if (!name) return;
        themeStatus.textContent = 'Applying…';
        webPost('/set-theme', {name})
            .then(r => r.json() as Promise<{ ok?: boolean; message?: string }>)
            .then(res => {
                themeStatus.textContent = res.ok ? 'Applied! Reloading…' : (res.message || 'Failed');
                if (res.ok) setTimeout(() => {
                    location.href = '/?v=' + Date.now();
                }, 600);
            })
            .catch(() => {
                themeStatus.textContent = 'Failed to apply theme';
            });
    });

    const sideViews = new Map<number, { activate(): void; deactivate(): void }>([
        [2, reviewView],
        [3, todoView],
        [4, toolCallsView],
        [5, searchView],
        [6, sessionView],
    ]);
    let activeSideView: { activate(): void; deactivate(): void } | null = null;

    // Listen for pane changes to re-focus appropriately.
    swiper.addEventListener('pane-changed', (e) => {
        const index = (e as CustomEvent).detail?.index;
        footerEl.style.display = index === 1 ? '' : 'none';
        if (index === 1) {
            inputEl.focus();
        }

        const nextSideView = sideViews.get(index) ?? null;
        if (nextSideView !== activeSideView) {
            activeSideView?.deactivate();
            activeSideView = nextSideView;
            activeSideView?.activate();
        }
    });
});

// ── Auto-scroll: track whether user is near the bottom ──────────────────────

let atBottom = true;
let unreadCount = 0;

// Scroll-to-bottom FAB (injected into the #ab-chat wrapper)
const scrollFab = document.createElement('button');
scrollFab.id = 'ab-scroll-fab';
scrollFab.setAttribute('aria-label', 'Scroll to bottom');
scrollFab.hidden = true;
chatAreaEl.appendChild(scrollFab);

function updateScrollFab(): void {
    scrollFab.hidden = atBottom;
    scrollFab.textContent = unreadCount > 0 ? `↓ ${unreadCount}` : '↓';
}

scrollFab.addEventListener('click', () => {
    unreadCount = 0;
    // Re-enable autoscroll and scroll to bottom via the ChatContainer setter
    (chatEl as unknown as { autoScroll: boolean }).autoScroll = true;
    atBottom = true;
    updateScrollFab();
});

chatEl.addEventListener('scroll', () => {
    atBottom = chatEl.scrollHeight - chatEl.scrollTop - chatEl.clientHeight < 120;
    if (atBottom) unreadCount = 0;
    updateScrollFab();
}, {passive: true});

function scrollToBottom(): void {
    chatEl.scrollTop = chatEl.scrollHeight;
    atBottom = true;
    updateScrollFab();
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
    if (!atBottom) {
        unreadCount++;
        updateScrollFab();
    }
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
    const mcpDot = document.getElementById('ab-mcp-dot');
    if (mcpDot) mcpDot.className = agentRunning ? 'running' : 'connected';
    const mcpText = document.getElementById('ab-mcp-text');
    if (mcpText) mcpText.textContent = agentRunning ? 'Running' : 'Ready';
    const stopBtn = document.getElementById('ab-connect-stop-btn') as HTMLButtonElement | null;
    if (stopBtn) stopBtn.hidden = !agentRunning;
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

// ── Connection state helpers ────────────────────────────────────────────────

const SETTINGS_PANE_INDEX = 7;

function showChatView(): void {
    swiper.switchTo(1);
    const acpConnect = document.getElementById('ab-acp-connect-section') as HTMLElement | null;
    const acpDisconnect = document.getElementById('ab-acp-disconnect-section') as HTMLElement | null;
    if (acpConnect) acpConnect.hidden = true;
    if (acpDisconnect) acpDisconnect.hidden = false;
}

function showConnectView(profiles?: ProfileInfo[]): void {
    swiper.switchTo(SETTINGS_PANE_INDEX);
    const acpConnect = document.getElementById('ab-acp-connect-section') as HTMLElement | null;
    const acpDisconnect = document.getElementById('ab-acp-disconnect-section') as HTMLElement | null;
    const statusEl = document.getElementById('ab-connect-status') as HTMLElement | null;
    const btn = document.getElementById('ab-connect-btn') as HTMLButtonElement | null;
    const stopBtn = document.getElementById('ab-connect-stop-btn') as HTMLButtonElement | null;
    const profileSel = document.getElementById('ab-connect-profile') as HTMLSelectElement | null;
    if (acpConnect) acpConnect.hidden = false;
    if (acpDisconnect) acpDisconnect.hidden = true;
    if (statusEl) statusEl.textContent = '';
    if (btn) {
        btn.disabled = false;
        btn.textContent = 'Connect';
    }
    if (stopBtn) stopBtn.hidden = !agentRunning;
    if (profiles?.length && profileSel) {
        const prev = profileSel.value;
        profileSel.innerHTML = profiles.map(p => `<option value="${p.id}">${p.name}</option>`).join('');
        if (prev) profileSel.value = prev;
    }
}

// ── Populate model select from info ─────────────────────────────────────────

function populateModels(models?: ModelInfo[], currentModelId?: string): void {
    const sel = document.getElementById('ab-menu-model') as HTMLSelectElement | null;
    if (sel) {
        sel.innerHTML = (models || []).map(m => `<option value="${m.id}">${m.name}</option>`).join('');
    }
    if (currentModelId) syncModelSelect(currentModelId);
}

function syncModelSelect(modelId: string): void {
    if (!modelId) return;
    const sel = document.getElementById('ab-menu-model') as HTMLSelectElement | null;
    if (sel) sel.value = modelId;
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
        const versionEl = document.getElementById('ab-menu-version');
        if (versionEl) versionEl.textContent = 'Plugin v' + (pluginVersion || '?');
        populateModels(info.models, info.model);
        if (info.connected) showChatView();
        else showConnectView(info.profiles);
    })
    .catch(() => {
        showConnectView();
        const statusEl = document.getElementById('ab-connect-status');
        if (statusEl) {
            statusEl.textContent = 'Failed to reach plugin — check that IntelliJ is running';
            statusEl.classList.add('error');
        }
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
    // Scrolling is handled by ChatContainer's MutationObserver (scrollIfNeeded)
    // which respects the _autoScroll flag. No explicit scrollToBottom() here —
    // doing so would bypass _autoScroll and fight user scroll attempts.
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

// Reconnect SSE and catch up on missed events when the page becomes visible.
// Mobile browsers freeze/kill background SSE connections, so on wake:
//   1. Always force-reconnect SSE (the connection may look OPEN but be dead).
//   2. Immediately fetch missed events via REST so content appears before SSE opens.
document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') {
        if (sseRetry) clearTimeout(sseRetry);
        fetchCatchUp();
        connectSSE();
    }
});

function fetchCatchUp(): void {
    fetch('/catch-up?from=' + lastSeq)
        .then(r => r.json() as Promise<{ events?: SseEvent[]; seq?: number }>)
        .then(st => {
            (st.events || []).forEach(ev => {
                if (!ev.seq || ev.seq > lastSeq) processEvent(ev, false);
            });
        })
        .catch(() => { /* SSE reconnect will recover */
        });
}

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
    inputEl.style.height = Math.min(inputEl.scrollHeight, 160) + 'px';
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
