import {beforeEach, describe, expect, it, vi} from 'vitest';

// Helper to wait for custom element upgrades
function flush() {
    return new Promise(r => setTimeout(r, 0));
}

describe('Web Components Registration', () => {
    it('all custom elements are defined', () => {
        const tags = [
            'chat-container', 'chat-message', 'message-bubble', 'message-meta',
            'thinking-block', 'tool-chip', 'thinking-chip',
            'subagent-chip', 'quick-replies',
            'session-divider', 'load-more', 'turn-details', 'permission-request',
        ];
        for (const tag of tags) {
            expect(customElements.get(tag), `${tag} should be registered`).toBeDefined();
        }
    });

    it('ChatController is globally available', () => {
        expect(window.ChatController).toBeDefined();
        expect(typeof window.ChatController.addUserMessage).toBe('function');
        expect(typeof window.ChatController.appendAgentText).toBe('function');
        expect(typeof window.ChatController.addToolCall).toBe('function');
        expect(typeof window.ChatController.addSubAgent).toBe('function');
    });
});

describe('chat-container', () => {
    let container;

    beforeEach(() => {
        document.body.innerHTML = '';
        container = document.createElement('chat-container');
        document.body.appendChild(container);
    });

    it('creates #messages div on connect', () => {
        const msgs = container.querySelector('#messages');
        expect(msgs).not.toBeNull();
    });

    it('exposes messages getter', () => {
        expect(container.messages).toBe(container.querySelector('#messages'));
    });

    it('scrollIfNeeded does not throw', () => {
        expect(() => container.scrollIfNeeded()).not.toThrow();
    });

    it('forceScroll does not throw', () => {
        expect(() => container.forceScroll()).not.toThrow();
    });
});

describe('chat-message', () => {
    beforeEach(() => {
        document.body.innerHTML = '';
    });

    it('renders with user type (prompt-row class)', () => {
        const msg = document.createElement('chat-message');
        msg.setAttribute('type', 'user');
        document.body.appendChild(msg);
        expect(msg.className).toContain('prompt-row');
    });

    it('renders with agent type (agent-row class)', () => {
        const msg = document.createElement('chat-message');
        msg.setAttribute('type', 'agent');
        document.body.appendChild(msg);
        expect(msg.className).toContain('agent-row');
    });
});

describe('message-bubble', () => {
    let bubble;

    beforeEach(() => {
        document.body.innerHTML = '';
        bubble = document.createElement('message-bubble');
        document.body.appendChild(bubble);
    });

    it('appendStreamingText renders Markdown after RAF', async () => {
        vi.useFakeTimers();
        bubble.appendStreamingText('**Hello**');
        vi.advanceTimersByTime(20);
        vi.useRealTimers();
        expect(bubble.innerHTML).toContain('<b>Hello</b>');
    });

    it('appendStreamingText accumulates multiple tokens', async () => {
        vi.useFakeTimers();
        bubble.appendStreamingText('Hello ');
        bubble.appendStreamingText('World');
        vi.advanceTimersByTime(20);
        vi.useRealTimers();
        expect(bubble.textContent).toContain('Hello World');
    });

    it('appendStreamingText renders inline code', async () => {
        vi.useFakeTimers();
        bubble.appendStreamingText('Use `npm install` to set up.');
        vi.advanceTimersByTime(20);
        vi.useRealTimers();
        expect(bubble.innerHTML).toContain('<code>npm install</code>');
    });

    it('finalize replaces content with HTML', () => {
        bubble.appendStreamingText('raw text');
        bubble.finalize('<p>Formatted</p>');
        expect(bubble.innerHTML).toContain('<p>Formatted</p>');
        expect(bubble.hasAttribute('streaming')).toBe(false);
    });
});

describe('message-meta', () => {
    it('renders with show class', () => {
        const meta = document.createElement('message-meta');
        meta.classList.add('show');
        document.body.appendChild(meta);
        expect(meta.classList.contains('show')).toBe(true);
    });
});

describe('thinking-block', () => {
    let block;

    beforeEach(() => {
        document.body.innerHTML = '';
        block = document.createElement('thinking-block');
        document.body.appendChild(block);
    });

    it('creates thinking content on connect', () => {
        expect(block.querySelector('.thinking-content')).not.toBeNull();
    });

    it('appendText adds to content', () => {
        block.appendText('Thought 1');
        const content = block.querySelector('.thinking-content');
        expect(content.textContent).toContain('Thought 1');
    });

    it('finalize removes active attribute', () => {
        block.setAttribute('active', '');
        block.appendText('some thinking');
        block.finalize();
        expect(block.hasAttribute('active')).toBe(false);
    });
});

describe('tool-chip', () => {
    let chip;

    beforeEach(() => {
        document.body.innerHTML = '';
        chip = document.createElement('tool-chip');
        chip.setAttribute('label', 'Read File');
        chip.setAttribute('status', 'running');
        document.body.appendChild(chip);
    });

    it('renders label text', () => {
        expect(chip.textContent).toContain('Read File');
    });

    it('shows spinner when running', () => {
        expect(chip.querySelector('.chip-spinner')).not.toBeNull();
    });

    it('removes spinner and shows no spinner on complete', () => {
        chip.setAttribute('status', 'complete');
        expect(chip.querySelector('.chip-spinner')).toBeNull();
        // No spinner means completed
        expect(chip.textContent).toContain('Read File');
    });

    it('adds failed class on failed', () => {
        chip.setAttribute('status', 'failed');
        expect(chip.querySelector('.chip-spinner')).toBeNull();
        expect(chip.classList.contains('failed')).toBe(true);
    });

});

describe('thinking-chip', () => {
    let chip;

    beforeEach(() => {
        document.body.innerHTML = '';
        chip = document.createElement('thinking-chip');
        document.body.appendChild(chip);
    });

    it('renders thought label', () => {
        expect(chip.textContent).toContain('💭');
    });

    it('linkSection toggles thinking block visibility', () => {
        const block = document.createElement('thinking-block');
        block.classList.add('turn-hidden');
        document.body.appendChild(block);
        chip.linkSection(block);
        chip.click();
        expect(block.classList.contains('turn-hidden')).toBe(false);
    });
});

describe('subagent-chip', () => {
    let chip;

    beforeEach(() => {
        document.body.innerHTML = '';
        chip = document.createElement('subagent-chip');
        chip.setAttribute('label', 'Explore Agent');
        chip.setAttribute('status', 'running');
        chip.setAttribute('color-index', '0');
        document.body.appendChild(chip);
    });

    it('renders agent label', () => {
        expect(chip.textContent).toContain('Explore Agent');
    });

    it('shows spinner when running', () => {
        expect(chip.querySelector('.chip-spinner')).not.toBeNull();
    });

    it('completes when status changes', () => {
        chip.setAttribute('status', 'complete');
        expect(chip.querySelector('.chip-spinner')).toBeNull();
        expect(chip.textContent).toContain('Explore Agent');
    });
});

describe('quick-replies', () => {
    let qr;

    beforeEach(() => {
        document.body.innerHTML = '';
        qr = document.createElement('quick-replies');
        document.body.appendChild(qr);
    });

    it('renders buttons from options property', () => {
        qr.options = ['Yes', 'No', 'Maybe'];
        const buttons = qr.querySelectorAll('.quick-reply-btn');
        expect(buttons.length).toBe(3);
        expect(buttons[0].textContent).toBe('Yes');
        expect(buttons[1].textContent).toBe('No');
        expect(buttons[2].textContent).toBe('Maybe');
    });

    it('dispatches quick-reply event on button click', () => {
        let received = null;
        document.addEventListener('quick-reply', e => {
            received = e.detail.text;
        });
        qr.options = ['Click me'];
        qr.querySelector('.quick-reply-btn').click();
        expect(received).toBe('Click me');
    });

    it('disabled attribute prevents clicks from dispatching', () => {
        let received = false;
        document.addEventListener('quick-reply', () => {
            received = true;
        });
        qr.options = ['Test'];
        qr.setAttribute('disabled', '');
        qr.querySelector('.quick-reply-btn').click();
        expect(received).toBe(false);
    });

    it('applies semantic color class from :color suffix', () => {
        qr.options = ['Keep', 'Delete:danger', 'Details:primary'];
        const buttons = qr.querySelectorAll('.quick-reply-btn');
        expect(buttons[0].className).toBe('quick-reply-btn');
        expect(buttons[0].textContent).toBe('Keep');
        expect(buttons[1].className).toBe('quick-reply-btn qr-danger');
        expect(buttons[1].textContent).toBe('Delete');
        expect(buttons[2].className).toBe('quick-reply-btn qr-primary');
        expect(buttons[2].textContent).toBe('Details');
    });

    it('dispatches clean label without color suffix', () => {
        let received = null;
        document.addEventListener('quick-reply', e => {
            received = e.detail.text;
        }, {once: true});
        qr.options = ['Force push:danger'];
        qr.querySelector('.quick-reply-btn').click();
        expect(received).toBe('Force push');
    });

    it('preserves colons that are not semantic colors', () => {
        qr.options = ['Time: 3pm', 'Note:unknown'];
        const buttons = qr.querySelectorAll('.quick-reply-btn');
        expect(buttons[0].textContent).toBe('Time: 3pm');
        expect(buttons[0].className).toBe('quick-reply-btn');
        expect(buttons[1].textContent).toBe('Note:unknown');
        expect(buttons[1].className).toBe('quick-reply-btn');
    });

    it('dismiss suffix disables buttons without dispatching event', () => {
        let received = false;
        document.addEventListener('quick-reply', () => {
            received = true;
        }, {once: true});
        qr.options = ['Ok:dismiss'];
        const btn = qr.querySelector('.quick-reply-btn');
        expect(btn.textContent).toBe('Ok');
        expect(btn.className).toBe('quick-reply-btn qr-dismiss');
        btn.click();
        expect(received).toBe(false);
        expect(qr.hasAttribute('disabled')).toBe(true);
    });
});

describe('session-divider', () => {
    it('renders timestamp', () => {
        const sd = document.createElement('session-divider');
        sd.setAttribute('timestamp', 'Feb 27, 2026 8:45 AM');
        document.body.appendChild(sd);
        expect(sd.textContent).toContain('Feb 27, 2026 8:45 AM');
    });
});

describe('load-more', () => {
    it('renders count', () => {
        const lm = document.createElement('load-more');
        lm.setAttribute('count', '42');
        document.body.appendChild(lm);
        expect(lm.textContent).toContain('42');
    });

    it('dispatches load-more event on click', () => {
        let fired = false;
        document.addEventListener('load-more', () => {
            fired = true;
        });
        const lm = document.createElement('load-more');
        lm.setAttribute('count', '10');
        document.body.appendChild(lm);
        lm.querySelector('button, [role="button"], .load-more-btn')?.click?.();
        // If no clickable child, click the element itself
        if (!fired) lm.click();
        expect(fired).toBe(true);
    });
});
