// Setup: load the web components into the happy-dom environment.
//
// Import the TypeScript source directly through Vitest's module system
// so V8 coverage can attribute lines to individual .ts files.
// (The previous approach — new Function(bundleCode)() — produced 0/0 coverage
//  because V8 can't map anonymous eval'd code back to source files.)

// Provide a minimal _bridge stub before components register
globalThis._bridge = {
    openFile: () => {
    },
    openUrl: () => {
    },
    setCursor: () => {
    },
    loadMore: () => {
    },
    quickReply: () => {
    },
    openScratch: () => {
    },
    showToolPopup: () => {
    },
};

// Import the chat-ui entry point — registers custom elements + exposes ChatController
import '../chat-ui/src/index.ts';
