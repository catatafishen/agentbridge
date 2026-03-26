/**
 * Service Worker for the AgentBridge PWA.
 *
 * Handles:
 * - Offline fallback page
 * - Web Push notifications (from server VAPID push)
 * - Local notifications (forwarded from the page via postMessage)
 * - Notification click → focus/open the app
 */

/// <reference lib="webworker" />

declare const self: ServiceWorkerGlobalScope;

self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', (e) => e.waitUntil(self.clients.claim()));

// ── Offline fallback ────────────────────────────────────────────────────────

const OFFLINE_HTML = `<!DOCTYPE html>
<html><body style="font-family:sans-serif;padding:2em;text-align:center;background:#1a1a1a;color:#e0e0e0">
<h2>Connection failed</h2>
<p>Could not reach the server. Make sure:</p>
<ul style="text-align:left;display:inline-block">
<li>The IDE plugin is running</li>
<li>Your phone is on the same network as your computer</li>
<li>The CA certificate is installed (System &rarr; Encryption &rarr; CA certs)</li>
</ul>
<p><a href="/" style="color:#7ab8ff">Retry</a></p>
</body></html>`;

self.addEventListener('fetch', (e) => {
    if (new URL(e.request.url).pathname === '/events') return;
    e.respondWith(
        fetch(e.request).catch(
            () => new Response(OFFLINE_HTML, {
                status: 503,
                headers: {'Content-Type': 'text/html'},
            }),
        ),
    );
});

// ── Web Push ────────────────────────────────────────────────────────────────

interface PushData {
    title?: string;
    seq?: number;
}

interface StateResponse {
    events?: Array<{ notification?: boolean; seq?: number; body?: string }>;
}

self.addEventListener('push', (e) => {
    e.waitUntil((async () => {
        let title = 'AgentBridge';
        let body = '';
        try {
            const data: PushData = e.data ? JSON.parse(e.data.text()) : {};
            title = data.title || 'AgentBridge';
            if (data.seq) {
                const r = await fetch('/state');
                const st: StateResponse = await r.json();
                const ev = (st.events || []).slice().reverse()
                    .find(ev => ev.notification && ev.seq != null && ev.seq >= data.seq!);
                if (ev?.body) body = ev.body;
            }
        } catch {
            // ignore parse/fetch errors
        }
        await self.registration.showNotification(title, {
            body,
            icon: '/icon-192.png',
            badge: '/badge-96.png',
            tag: 'agentbridge',
            requireInteraction: false,
        });
    })());
});

// ── Local notifications (forwarded from the page) ───────────────────────────

interface NotificationMessage {
    type: 'SHOW_NOTIFICATION';
    title?: string;
    body?: string;
    actions?: NotificationAction[];
}

self.addEventListener('message', (e) => {
    const data = e.data as NotificationMessage | undefined;
    if (data?.type === 'SHOW_NOTIFICATION') {
        const opts: NotificationOptions = {
            body: data.body || '',
            icon: '/icon-192.png',
            badge: '/badge-96.png',
            tag: 'agentbridge',
            requireInteraction: false,
        };
        if (data.actions?.length) opts.actions = data.actions;
        e.waitUntil(
            self.registration.showNotification(data.title || 'AgentBridge', opts),
        );
    }
});

// ── Notification click → focus/open the app ─────────────────────────────────

self.addEventListener('notificationclick', (e) => {
    e.notification.close();
    e.waitUntil(
        self.clients.matchAll({type: 'window', includeUncontrolled: true}).then(list => {
            const c = list.find(w => w.url.startsWith(self.location.origin));
            if (c) return c.focus();
            return self.clients.openWindow('/');
        }),
    );
});
