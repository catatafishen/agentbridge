// Setup: load the web components into the happy-dom environment
import {readFileSync} from 'node:fs';
import {resolve} from 'node:path';

const componentsPath = resolve(__dirname, '../src/main/resources/chat/chat-components.js');
const code = readFileSync(componentsPath, 'utf-8');

// Provide a minimal _bridge stub
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
};

// Execute the components code in global scope
const script = document.createElement('script');
script.textContent = code;

// happy-dom doesn't execute script tags, so use Function constructor
new Function(code)();
