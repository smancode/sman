import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    globals: true,
    environment: 'node',
    hookTimeout: 30_000,
    testTimeout: 15_000,
    exclude: [
      '**/node_modules/**',
      '**/dist/**',
      '**/bazaar/**',
      '**/plugins/superpowers/tests/brainstorm-server/**',
    ],
  },
});
