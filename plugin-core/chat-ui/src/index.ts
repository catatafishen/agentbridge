/**
 * Chat UI — Web Components
 *
 * Pure custom elements (light DOM) for the chat panel.
 * Bridge wired by Kotlin: window._bridge = { openFile, openUrl, setCursor, loadMore, quickReply }
 */

import './types';

import {b64} from './helpers';
import ChatContainer from './components/ChatContainer';
import ChatMessage from './components/ChatMessage';
import MessageBubble from './components/MessageBubble';
import MessageMeta from './components/MessageMeta';
import ThinkingBlock from './components/ThinkingBlock';
import ToolSection from './components/ToolSection';
import ToolChip from './components/ToolChip';
import ThinkingChip from './components/ThinkingChip';
import SubagentChip from './components/SubagentChip';
import QuickReplies from './components/QuickReplies';
import StatusMessage from './components/StatusMessage';
import SessionDivider from './components/SessionDivider';
import LoadMore from './components/LoadMore';
import TurnDetails from './components/TurnDetails';

import ChatController from './ChatController';

import PermissionRequest from './components/PermissionRequest';

// ── Register custom elements ──────────────────────────

customElements.define('chat-container', ChatContainer);
customElements.define('chat-message', ChatMessage);
customElements.define('message-bubble', MessageBubble);
customElements.define('message-meta', MessageMeta);
customElements.define('thinking-block', ThinkingBlock);
customElements.define('tool-section', ToolSection);
customElements.define('tool-chip', ToolChip);
customElements.define('thinking-chip', ThinkingChip);
customElements.define('subagent-chip', SubagentChip);
customElements.define('quick-replies', QuickReplies);
customElements.define('status-message', StatusMessage);
customElements.define('session-divider', SessionDivider);
customElements.define('load-more', LoadMore);
customElements.define('turn-details', TurnDetails);
customElements.define('permission-request', PermissionRequest);

// ── Expose controller to Kotlin bridge ────────────────

window.ChatController = ChatController;
(window as any).b64 = b64;
(window as any).showPermissionRequest = (turnId: string, agentId: string, reqId: string, toolDisplayName: string, argsJson: string) => {
    ChatController.showPermissionRequest(turnId, agentId, reqId, toolDisplayName, argsJson);
};

// ── Global event handlers ─────────────────────────────

// Link interception
document.addEventListener('click', (e: MouseEvent) => {
    let el = e.target as HTMLElement | null;
    while (el && el.tagName !== 'A') el = el.parentElement;
    if (!el?.getAttribute('href')) return;
    const href = el.getAttribute('href')!;
    if (href.startsWith('openfile://') || href.startsWith('gitshow://')) {
        e.preventDefault();
        globalThis._bridge?.openFile(href);
    } else if (href.startsWith('http://') || href.startsWith('https://')) {
        e.preventDefault();
        globalThis._bridge?.openUrl(href);
    }
});

// Cursor management
let lastCursor = '';
document.addEventListener('mouseover', (e: MouseEvent) => {
    const el = e.target as HTMLElement;
    let c = 'default';
    if (el.closest('a,.turn-chip,.chip-close,.prompt-ctx-chip,.quick-reply-btn,.copy-btn')) c = 'pointer';
    else if (el.closest('p,pre,code,li,td,th,.thinking-content,.streaming')) c = 'text';
    if (c !== lastCursor) {
        lastCursor = c;
        globalThis._bridge?.setCursor(c);
    }
});

// Quick-reply bridge
document.addEventListener('quick-reply', (e: Event) => {
    globalThis._bridge?.quickReply((e as CustomEvent).detail.text);
});
document.addEventListener('load-more', () => {
    globalThis._bridge?.loadMore();
});
