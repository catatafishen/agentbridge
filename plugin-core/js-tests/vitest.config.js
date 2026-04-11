import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    environment: 'happy-dom',
    globals: true,
    setupFiles: ['./setup.js'],
    coverage: {
      provider: 'v8',
      // lcov for Codecov upload; text-summary for CI log
      reporter: ['lcov', 'text-summary'],
      reportsDirectory: './coverage',
      // The tests load a pre-built bundle via new Function(), so coverage is
      // attributed at the bundle level. With BUILD_SOURCEMAPS=1, esbuild embeds
      // inline sourcemaps, and V8 maps coverage back to the TypeScript source.
      // Without sourcemaps the report covers the bundle; still useful for CI gating.
      include: ['../chat-ui/src/**/*.ts'],
      exclude: ['**/*.d.ts'],
    },
  },
});
