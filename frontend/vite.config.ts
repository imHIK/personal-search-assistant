import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import path from 'node:path'

// The dev server proxies the API to Quarkus on :8080 so the browser only ever talks to one
// origin. That is deliberate: the backend configures no CORS at all, and keeping a single origin
// in dev means the deployed build (served by Quarkus itself) behaves identically.
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
    // Built straight into Quarkus' static-resource root, so the packaged fast-jar serves the
    // console from :8080 alongside the API with no extra wiring. `./gradlew build` runs this via
    // the buildFrontend task; the directory is gitignored.
    outDir: '../src/main/resources/META-INF/resources',
    emptyOutDir: true,
    // A local, single-user tool: one bundle is simpler to reason about than lazy chunks, and
    // ~180 kB gzipped over localhost is not worth code-splitting for.
    chunkSizeWarningLimit: 1000,
  },
})
