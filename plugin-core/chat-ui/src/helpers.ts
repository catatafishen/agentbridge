/** Decode a base64-encoded UTF-8 string. Pair with MessageFormatter.encodeBase64() on the Kotlin side. */
export function decodeBase64(s: string): string {
    const r = atob(s);
    const b = new Uint8Array(r.length);
    for (let i = 0; i < r.length; i++) b[i] = r.codePointAt(i)!;
    return new TextDecoder().decode(b);
}

/** Collapse all expanded chip sections in a container, optionally except one. */
export function collapseAllChips(container: Element | null, except?: Element): void {
    if (!container) return;
    container.querySelectorAll('tool-chip, thinking-chip, subagent-chip').forEach(chip => {
        if (chip === except) return;
        const section = (chip as any)._linkedSection as HTMLElement | undefined;
        if (!section || section.classList.contains('turn-hidden')) return;
        (chip as HTMLElement).style.opacity = '1';
        section.classList.add('turn-hidden');
        section.classList.remove('chip-expanded', 'collapsing', 'collapsed');
    });
}

/** HTML-escape a string. */
export function escHtml(s: string | null | undefined): string {
    return s ? s.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;') : '';
}

/**
 * Collapse a bubble's timestamp when the previous chat-message shares the same minute
 * (timestamps are rendered as "HH:MM"). Reduces visual noise in streams of close messages
 * while still marking the first bubble of each minute.
 *
 * Call *after* the message's .ts span has been populated and the message has been
 * inserted into the DOM. A session-divider between two messages always re-shows the
 * next timestamp, since a new session is a natural break regardless of minute.
 */
export function hideRedundantTimestamp(msg: Element): void {
    const tsEl = msg.querySelector('.ts');
    if (!tsEl?.textContent) return;
    const current = tsEl.textContent.trim();
    if (!current) return;
    let prev = msg.previousElementSibling;
    while (prev) {
        const tag = prev.tagName;
        if (tag === 'SESSION-DIVIDER') return;
        if (tag === 'CHAT-MESSAGE') {
            const prevTs = prev.querySelector('.ts');
            if (prevTs && prevTs.textContent?.trim() === current) {
                tsEl.classList.add('ts-hidden');
            }
            return;
        }
        prev = prev.previousElementSibling;
    }
}

/**
 * Format an ISO timestamp for display.
 * - Today: shows time only ("14:30")
 * - Yesterday: "Yesterday 14:30"
 * - Older: "May 14 14:30"
 * Returns the raw string on parse failure.
 */
export function formatTs(isoOrLegacy: string): string {
    if (!isoOrLegacy) return '';
    const d = new Date(isoOrLegacy);
    if (Number.isNaN(d.getTime())) return isoOrLegacy;
    const timeStr = d.toLocaleTimeString([], {hour: '2-digit', minute: '2-digit'});
    const now = new Date();
    if (d.toDateString() === now.toDateString()) return timeStr;
    const yesterday = new Date(now);
    yesterday.setDate(now.getDate() - 1);
    const datePrefix = d.toDateString() === yesterday.toDateString()
        ? 'Yesterday'
        : d.toLocaleDateString([], {month: 'short', day: 'numeric'});
    return `${datePrefix} ${timeStr}`;
}
