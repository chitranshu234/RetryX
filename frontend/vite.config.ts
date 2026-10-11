import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

/**
 * Vite configuration.
 *
 * The dev proxy forwards /api to the backend so the browser only ever talks to one origin during
 * development. The alternative — pointing the app straight at :8080 — makes every request
 * cross-origin, which means CORS preflights in development that will not happen in production, and
 * a class of bug that only appears in one environment. Proxying keeps dev and prod shaped the same.
 */
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: process.env.RETRYX_API_URL ?? 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: true,
  },
})
