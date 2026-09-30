import {describe, expect, it} from 'vitest';
import {renderMarkdown} from '../chat-ui/src/renderMarkdown.ts';

describe('renderMarkdown XML tag preprocessing', () => {
    it('renders think tags as thinking blocks', () => {
        const html = renderMarkdown('Before\n<think>Step 1\nStep 2</think>\nAfter');
        expect(html).toContain('<thinking-block><div class="thinking-content">Step 1<br>Step 2</div></thinking-block>');
        expect(html).toContain('<p>Before</p>');
        expect(html).toContain('<p>After</p>');
    });

    it('renders thinking tags as thinking blocks', () => {
        const html = renderMarkdown('<thinking>Reasoning</thinking>');
        expect(html).toContain('<thinking-block><div class="thinking-content">Reasoning</div></thinking-block>');
    });

    it('strips standalone wrapper tags', () => {
        const html = renderMarkdown('<task_result>\nResult body\n</task_result>\n<example>\nExample body\n</example>');
        expect(html).not.toContain('task_result');
        expect(html).not.toContain('example');
        expect(html).toContain('<p>Result body</p>');
        expect(html).toContain('<p>Example body</p>');
    });

    it('leaves unclosed think tags escaped as plain text', () => {
        const html = renderMarkdown('<think>\nunfinished');
        expect(html).toContain('&lt;think&gt;');
        expect(html).not.toContain('<thinking-block>');
    });

    it('does not preprocess think or wrapper tags inside fenced code blocks', () => {
        const html = renderMarkdown('```md\n<think>Reasoning</think>\n<task_result>\n```');
        expect(html).toContain('&lt;think&gt;Reasoning&lt;/think&gt;');
        expect(html).toContain('&lt;task_result&gt;');
        expect(html).not.toContain('<thinking-block>');
    });

    it('renders empty think tags with fallback text', () => {
        const html = renderMarkdown('<think>\r\n\r\n</think>');
        expect(html).toContain('No reasoning returned');
    });

    it('strips wrapper tags that have surrounding blank lines and indentation', () => {
        const html = renderMarkdown('Before\n\n  <example>  \n\nBody\n\n</example>\n\nAfter');
        expect(html).not.toContain('example');
        expect(html).toContain('<p>Before</p>');
        expect(html).toContain('<p>Body</p>');
        expect(html).toContain('<p>After</p>');
    });

    it('does not strip wrapper tags that share a line with other text', () => {
        const html = renderMarkdown('see <example> here');
        expect(html).toContain('&lt;example&gt;');
    });
});

describe('renderMarkdown inline links', () => {
    it('renders markdown links and bare urls', () => {
        const html = renderMarkdown('[docs](https://example.com/a?b=1) and https://example.org/x');
        expect(html).toContain("<a href='https://example.com/a?b=1'>docs</a>");
        expect(html).toContain("<a href='https://example.org/x'>https://example.org/x</a>");
    });

    it('does not build a link with a truncated href for urls containing parentheses', () => {
        // Parenthesised urls are not supported by the markdown-link rule (previously it produced
        // href='.../Foo_(bar' and dropped the closing paren); the text must at least stay intact.
        const html = renderMarkdown('[t](https://example.com/Foo_(bar))');
        expect(html).not.toContain("href='https://example.com/Foo_(bar'");
        expect(html).toContain('(bar))');
    });

    it('renders a link that follows an unterminated bracket', () => {
        const html = renderMarkdown('[unterminated [ok](https://example.com)');
        expect(html).toContain("<a href='https://example.com'>ok</a>");
    });
});

describe('renderMarkdown pathological input', () => {
    // Guards against super-linear regex backtracking: with the previous patterns each of these
    // took seconds at this size, now they are linear and finish in a few milliseconds.
    const N = 100_000;
    const MAX_MS = 1500;

    function elapsedMs(input) {
        const start = performance.now();
        renderMarkdown(input);
        return performance.now() - start;
    }

    it('handles a long run of unterminated link brackets', () => {
        expect(elapsedMs('['.repeat(N))).toBeLessThan(MAX_MS);
    });

    it('handles repeated unterminated link urls', () => {
        expect(elapsedMs('[a](x'.repeat(N / 5))).toBeLessThan(MAX_MS);
    });

    it('handles a long run of blank lines', () => {
        expect(elapsedMs('\n'.repeat(N) + 'x')).toBeLessThan(MAX_MS);
    });

    it('handles a long run of whitespace-only lines', () => {
        expect(elapsedMs(' \n'.repeat(N / 2) + 'x')).toBeLessThan(MAX_MS);
    });
});
