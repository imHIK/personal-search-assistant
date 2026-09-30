import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import path from 'node:path'

// One origin in dev as in production: the backend has no CORS config.
export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: { '@': path.resolve(import.meta.dirname, 'src') },
  },
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
      '/q': { target: 'http://localhost:8080', changeOrigin: true },
    },
  },
  build: {
    outDir: '../src/main/resources/META-INF/resources',
    emptyOutDir: true,
    chunkSizeWarningLimit: 1000,
  },
})
