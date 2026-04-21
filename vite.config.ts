import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import path from 'path'
import { readFileSync } from 'fs'

const pkg = JSON.parse(readFileSync(path.resolve(__dirname, 'package.json'), 'utf-8'))

export default defineConfig({
  define: {
    __APP_VERSION__: JSON.stringify(pkg.version),
  },
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  server: {
    port: 5881,
    proxy: {
      '/api': {
        target: 'http://localhost:5880',
        changeOrigin: true,
      },
      '/ws': {
        target: 'ws://localhost:5880',
        ws: true,
      },
    },
  },
  optimizeDeps: {
    exclude: ['electron'],
  },
})
