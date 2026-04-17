/**
 * Lightweight client-side Markdown-to-HTML renderer used during streaming.
 *
 * Intentionally mirrors the output of the Kotlin `MarkdownRenderer` for visual
 * consistency between the streaming phase and the server-finalized HTML that
 * replaces it once the full response has been received.
 *
 * Server-specific features (file-path link resolution, git-SHA detection) are
 * intentionally omitted here; those are added by the Kotlin finalization step.
 */

function escHtmlMd(s: string): string {
    return s
        .replaceAll('&', '&amp;')
        .replaceAll('<', '&lt;')
        .replaceAll('>', '&gt;')
        .replaceAll('"', '&quot;');
}

/**
 * Format inline Markdown elements: bold, inline-code, links, bare URLs.
 * Matches the Kotlin MarkdownRenderer.formatInline logic.
 */
function formatInline(text: string): string {
    const result: string[] = [];
    // Combined regex (same order/priority as Kotlin implementation):
    //  1. **bold**
    //  2. `inline code`
    //  3. [text](url)
    //  4. bare https?:// URL
    const PATTERN = /\*\*(.+?)\*\*|`([^`]+)`|\[([^\]]+)]\(([^)]+)\)|(https?:\/\/[^\s<>[\]()]+)/g;
    let last = 0;
    let m: RegExpExecArray | null;
    while ((m = PATTERN.exec(text)) !== null) {
        if (m.index > last) result.push(escHtmlMd(text.slice(last, m.index)));
        if (m[1]) {
            // Bold: **content**
            result.push('<b>', formatInline(m[1]), '</b>');
        } else if (m[2]) {
            // Inline code: `content`
            result.push('<code>', escHtmlMd(m[2]), '</code>');
        } else if (m[3]) {
            // Markdown link: [text](url)
            const url = escHtmlMd(m[4].trim());
            const label = escHtmlMd(m[3]);
            result.push(`<a href='${url}'>${label}</a>`);
        } else if (m[5]) {
            // Bare URL
            const url = escHtmlMd(m[5]);
            result.push(`<a href='${url}'>${url}</a>`);
        }
        last = m.index + m[0].length;
    }
    if (last < text.length) result.push(escHtmlMd(text.slice(last)));
    return result.join('');
}

const THINK_TAG_PATTERN = /<(think|thinking)>([\s\S]*?)<\/\1>/gi;
const WRAPPER_TAG_LINE_PATTERN = /^\s*<\/?(task_result|commentary|example|code)>\s*$/gim;

function buildThinkingBlockHtml(content: string): string {
    const normalized = content.trim() || 'No reasoning returned';
    return `<thinking-block><div class="thinking-content">${escHtmlMd(normalized).replaceAll('\r\n', '\n').replaceAll('\r', '\n').replaceAll('\n', '<br>')}</div></thinking-block>`;
}

function preprocessXmlTags(text: string): string {
    return text
        .replace(THINK_TAG_PATTERN, (_match, _tag, content) => buildThinkingBlockHtml(content))
        .replace(WRAPPER_TAG_LINE_PATTERN, '');
}

function preprocessXmlTagsOutsideCodeFences(text: string): string {
    const lines = text.split('\n');
    const processed: string[] = [];
    const segment: string[] = [];
    let inCodeFence = false;

    const flushSegment = (): void => {
        if (segment.length === 0) {
            return;
        }
        processed.push(...preprocessXmlTags(segment.join('\n')).split('\n'));
        segment.length = 0;
    };

    for (const line of lines) {
        if (line.trim().startsWith('```')) {
            flushSegment();
            processed.push(line);
            inCodeFence = !inCodeFence;
            continue;
        }
        if (inCodeFence) {
            processed.push(line);
            continue;
        }
        segment.push(line);
    }

    flushSegment();
    return processed.join('\n');
}

/**
 * Convert a Markdown string to an HTML string.
 *
 * Supported constructs (matching Kotlin MarkdownRenderer):
 *   - Fenced code blocks (``` lang)
 *   - ATX headings (# – ####), mapped to h2–h5 (# → h2)
 *   - Horizontal rules (---, ***, ___)
 *   - Blockquotes (> text)
 *   - GFM tables (| col | col |)
 *   - Unordered lists (- item  /  * item)
 *   - Paragraphs (everything else)
 *   - Inline: **bold**, `code`, [text](url), bare URLs
 *
 * Gracefully handles *incomplete* Markdown that appears mid-stream (open code
 * blocks, unclosed lists, etc.) by closing all open constructs at the end.
 */
export function renderMarkdown(text: string): string {
    const lines = preprocessXmlTagsOutsideCodeFences(text).split('\n');
    const out: string[] = [];
    let inCode = false;
    let inList = false;
    let inTable = false;
    let firstTableRow = true;
    let inBlockquote = false;

    const closeListTable = (): void => {
        if (inList) {
            out.push('</ul>');
            inList = false;
        }
        if (inTable) {
            out.push('</table>');
            inTable = false;
            firstTableRow = true;
        }
    };

    const closeAllInline = (): void => {
        closeListTable();
        if (inBlockquote) {
            out.push('</blockquote>');
            inBlockquote = false;
        }
    };

    for (const line of lines) {
        const t = line.trim();

        if (t.startsWith('<thinking-block')) {
            closeAllInline();
            out.push(t);
            continue;
        }

        // ── Code fence ─────────────────────────────────────────────
        if (t.startsWith('```')) {
            if (inCode) {
                out.push('</code></pre>');
                inCode = false;
            } else {
                closeAllInline();
                const lang = t.slice(3).trim().toLowerCase();
                out.push(lang
                    ? `<pre><code data-lang="${escHtmlMd(lang)}">`
                    : '<pre><code>');
                inCode = true;
            }
            continue;
        }

        if (inCode) {
            out.push(escHtmlMd(line) + '\n');
            continue;
        }

        // ── Blank line → close inline blocks ───────────────────────
        if (t === '') {
            closeAllInline();
            continue;
        }

        // ── Horizontal rule ─────────────────────────────────────────
        if (/^(-{3,}|\*{3,}|_{3,})$/.test(t)) {
            closeAllInline();
            out.push('<hr>');
            continue;
        }

        // ── ATX heading ─────────────────────────────────────────────
        const hm = /^(#{1,4})\s+(.+)/.exec(t);
        if (hm) {
            closeAllInline();
            const level = hm[1].length + 1; // # → h2 (matches Kotlin renderer)
            out.push(`<h${level}>${formatInline(hm[2])}</h${level}>`);
            continue;
        }

        // ── Blockquote ──────────────────────────────────────────────
        if (t.startsWith('> ') || t === '>') {
            closeListTable();
            if (!inBlockquote) {
                out.push('<blockquote>');
                inBlockquote = true;
            }
            const content = t.replace(/^>\s?/, '').trim();
            if (content) out.push(`<p>${formatInline(content)}</p>`);
            continue;
        }

        if (inBlockquote) {
            out.push('</blockquote>');
            inBlockquote = false;
        }

        // ── GFM table ───────────────────────────────────────────────
        if (t.startsWith('|') && t.endsWith('|') && (t.match(/\|/g) ?? []).length >= 3) {
            closeListTable();
            if (!t.replaceAll(/[|\-: ]/g, '').trim()) continue; // separator row
            if (!inTable) {
                out.push('<table>');
                inTable = true;
                firstTableRow = true;
            }
            const cells = t.split('|').slice(1, -1).map(c => c.trim());
            const tag = firstTableRow ? 'th' : 'td';
            out.push('<tr>' + cells.map(c => `<${tag}>${formatInline(c)}</${tag}>`).join('') + '</tr>');
            firstTableRow = false;
            continue;
        } else if (inTable) {
            out.push('</table>');
            inTable = false;
            firstTableRow = true;
        }

        // ── Unordered list ──────────────────────────────────────────
        if (t.startsWith('- ') || t.startsWith('* ')) {
            if (!inList) {
                out.push('<ul>');
                inList = true;
            }
            out.push(`<li>${formatInline(t.slice(2))}</li>`);
            continue;
        } else if (inList) {
            out.push('</ul>');
            inList = false;
        }

        // ── Paragraph ───────────────────────────────────────────────
        out.push(`<p>${formatInline(t)}</p>`);
    }

    // Close any constructs left open by incomplete/streaming content
    if (inCode) out.push('</code></pre>');
    if (inList) out.push('</ul>');
    if (inTable) out.push('</table>');
    if (inBlockquote) out.push('</blockquote>');

    return out.join('');
}
