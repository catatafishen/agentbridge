/** Decode a base64-encoded UTF-8 string. */
export function b64(s: string): string {
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
