/**
 * Ambient type declarations for globals shared between bundles.
 *
 * chat-components.js (from index.ts) exposes ChatController on globalThis.
 * web-app.ts consumes it. This file lets TypeScript understand the cross-bundle link.
 */

import type ChatControllerType from './ChatController';

/** Extended bridge for the web app — adds methods not needed by the in-IDE panel. */
export interface WebBridge {
    openFile(href: string): void;

    openUrl(href: string): void;

    setCursor(cursor: string): void;

    loadMore(): Promise<Response>;

    quickReply(text: string): Promise<Response>;

    permissionResponse(data: string): void;

    openScratch(): void;

    showToolPopup(): void;

    cancelNudge(id: string): Promise<Response>;

    autoScrollDisabled?(): void;

    autoScrollEnabled?(): void;

    scrollStarted?(): void;

    scrollEnded?(): void;
}

declare global {
    // eslint-disable-next-line no-var
    var ChatController: typeof ChatControllerType;

    // eslint-disable-next-line no-var
    var ICON_SVG: string;

    interface Window {
        ICON_SVG: string;
        _bridge?: WebBridge;
    }

    /** POST helper exposed globally for SSE eval() calls. */
    function webPost(path: string, body: Record<string, unknown>): Promise<Response>;

    /** Called via SSE broadcastTransient when ACP connects. */
    function handleConnected(modelsJsonStr: string, profilesJsonStr: string): void;

    /** Called via SSE broadcastTransient when ACP disconnects. */
    function handleDisconnected(profilesJsonStr: string): void;
}

export {};
