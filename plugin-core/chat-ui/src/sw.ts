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

// SECURITY: The push payload contains only {seq, title} — never the notification
// body. Push payloads travel through third-party push services (Google FCM, Apple
// APNs, Mozilla autopush) and may contain sensitive information (code snippets,
// file paths, error messages). We fetch the body from the local server via /state,
// keeping sensitive content on the local network only.

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
            // ignore parse/fetch errors — show notification with defaults
        }
        // showNotification MUST always be called (Chrome enforces userVisibleOnly: true).
        // Wrap in try-catch so the waitUntil promise always resolves — if it rejects,
        // Chrome logs a violation and may eventually revoke the push subscription.
        try {
            await self.registration.showNotification(title, {
                body,
                icon: '/icon-192.png',
                badge: '/badge-96.png',
                tag: 'agentbridge',
                // Required: alerts the user even when replacing an existing notification
                // with the same tag (otherwise the replacement is completely silent).
                renotify: true,
                requireInteraction: false,
            });
        } catch {
            // Permission may have been revoked between subscription and push delivery.
            // Nothing we can do — Chrome will show its generic fallback notification.
        }
    })());
});

// ── Local notifications (forwarded from the page) ───────────────────────────

interface NotificationMessage {
    type: 'SHOW_NOTIFICATION';
    title?: string;
    body?: string;
    actions?: NotificationAction[];
    /** Permission request id; present when actions are permission responses. */
    permissionReqId?: string;
}

self.addEventListener('message', (e) => {
    // Only accept messages from clients on the same origin as the SW.
    // The SW is registered with the page's origin, so any cross-origin sender
    // would be a misconfiguration or attack — drop those messages defensively.
    if (e.origin && e.origin !== self.location.origin) return;
    const data = e.data as NotificationMessage | undefined;
    if (data?.type === 'SHOW_NOTIFICATION') {
        const opts: NotificationOptions = {
            body: data.body || '',
            icon: '/icon-192.png',
            badge: '/badge-96.png',
            tag: 'agentbridge',
            renotify: true,
            requireInteraction: false,
        };
        if (data.actions?.length) {
            opts.actions = data.actions;
            (opts as NotificationOptions & {data?: unknown}).data = {permissionReqId: data.permissionReqId};
        }
        e.waitUntil(
            self.registration.showNotification(data.title || 'AgentBridge', opts),
        );
    }
});

// ── Notification click → focus/open the app ─────────────────────────────────

self.addEventListener('notificationclick', (e) => {
    const permissionReqId = (e.notification.data as {permissionReqId?: string} | undefined)?.permissionReqId;
    if (permissionReqId && e.action) {
        e.notification.close();
        e.waitUntil(
            fetch('/permission', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({reqId: permissionReqId, response: e.action}),
            }).catch(() => {
                self.clients.matchAll({type: 'window', includeUncontrolled: true}).then(list => {
                    const c = list.find(w => w.url.startsWith(self.location.origin));
                    if (c) return c.focus();
                    return self.clients.openWindow('/');
                });
            }),
        );
        return;
    }
    e.notification.close();
    e.waitUntil(
        self.clients.matchAll({type: 'window', includeUncontrolled: true}).then(list => {
            const c = list.find(w => w.url.startsWith(self.location.origin));
            if (c) return c.focus();
            return self.clients.openWindow('/');
        }),
    );
});
