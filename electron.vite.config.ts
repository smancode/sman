import { defineConfig, externalizeDepsPlugin } from 'electron-vite';
import path from 'path';

export default defineConfig({
  main: {
    plugins: [externalizeDepsPlugin()],
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
