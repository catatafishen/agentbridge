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
});
