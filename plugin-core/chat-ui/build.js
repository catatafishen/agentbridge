import * as esbuild from 'esbuild';
import * as fs from 'fs';

const banner = `/*
 * ⚠️  AUTO-GENERATED FILE - DO NOT EDIT DIRECTLY!
 *
 * This file is built from TypeScript sources in plugin-core/chat-ui/src/
 *
 * To make changes:
 *   1. Edit the TypeScript files in plugin-core/chat-ui/src/
 *   2. Run: cd plugin-core/chat-ui && npm run build
 *   3. The changes will be compiled into this file
 *
 * See plugin-core/chat-ui/README.md for more information.
 */

`;

const sourcemaps = process.env.BUILD_SOURCEMAPS === '1';

async function build() {
    // 1. Chat components — custom elements + ChatController (loaded first)
    await esbuild.build({
        entryPoints: ['src/index.ts'],
        bundle: true,
        format: 'iife',
        globalName: '__chatUI',
        outfile: 'dist/chat-components.js',
        target: 'es2022',
        sourcemap: sourcemaps ? 'inline' : false,
    });

    // 2. Web app — PWA page logic (loaded after chat-components)
    await esbuild.build({
        entryPoints: ['src/web-app.ts'],
        bundle: true,
        format: 'iife',
        globalName: '__webApp',
        outfile: 'dist/web-app.js',
        target: 'es2022',
        sourcemap: sourcemaps ? 'inline' : false,
    });

    // 3. Service worker — runs in SW context, no DOM
    await esbuild.build({
        entryPoints: ['src/sw.ts'],
        bundle: true,
        format: 'iife',
        outfile: 'dist/sw.js',
        target: 'es2022',
        sourcemap: sourcemaps ? 'inline' : false,
    });

    // Prepend banner to JS output files
    for (const file of ['dist/chat-components.js', 'dist/web-app.js', 'dist/sw.js']) {
        const content = fs.readFileSync(file, 'utf8');
        fs.writeFileSync(file, banner + content);
    }

    console.log('✓ Built: chat-components.js, web-app.js, sw.js');
}

build().catch(() => process.exit(1));
