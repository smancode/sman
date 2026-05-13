import { defineConfig, externalizeDepsPlugin } from 'electron-vite';
import path from 'path';

// Enterprise build injection: set env vars before build to bake them into the binary.
// Example: SMAN_FALLBACK_URL=http://10.0.0.1:5882 SMAN_PSK=xxx pnpm electron:build
const enterpriseDefines: Record<string, string> = {};
for (const key of ['SMAN_UPDATE_URL', 'SMAN_HUB_URL', 'SMAN_FALLBACK_URL', 'SMAN_PSK']) {
  const val = process.env[key];
  if (val) {
    enterpriseDefines[`process.env.${key}`] = JSON.stringify(val);
  }
}

export default defineConfig({
  main: {
    plugins: [externalizeDepsPlugin()],
    define: enterpriseDefines,
    build: {
      target: 'node20',
      outDir: 'electron/dist/main',
      emptyOutDir: false,
      lib: {
        entry: 'electron/main.ts',
        formats: ['es'],
        fileName: () => 'main',
      },
      rollupOptions: {
        external: ['electron'],
      },
    },
    resolve: {
      alias: {
        '@': path.resolve(__dirname, './src'),
      },
    },
  },
  preload: {
    plugins: [externalizeDepsPlugin()],
    build: {
      target: 'node20',
      outDir: 'electron/dist/preload',
      emptyOutDir: false,
      lib: {
        entry: 'electron/preload.ts',
        formats: ['cjs'],
        fileName: () => 'preload',
      },
      rollupOptions: {
        external: ['electron'],
      },
    },
  },
});
