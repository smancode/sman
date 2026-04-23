// stardom/vitest.config.ts
import { defineConfig } from 'vitest/config';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

export default defineConfig({
  test: {
    globals: true,
  },
  resolve: {
    alias: {
      '../../shared/stardom-types.js': path.resolve(__dirname, '../shared/stardom-types.ts'),
    },
  },
});
