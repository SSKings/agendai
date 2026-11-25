import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

// Dev: a API é chamada pelo proxy /api (mesma origem), como em produção atrás do nginx: o cookie de refresh
// (SameSite=Strict, Path=/api/auth) funciona igual nos dois.
export default defineConfig({
  plugins: [react()],
  server: {
    host: true,
    port: 5173,
    strictPort: true,
    proxy: { '/api': { target: process.env.API_URL ?? 'http://localhost:8080', changeOrigin: false } },
  },
  preview: { host: true, port: 4173, strictPort: true },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    css: false,
    coverage: {
      provider: 'v8',
      reporter: ['text', 'html', 'json-summary'],
      include: ['src/**/*.{ts,tsx}'],
      // main.tsx só monta o app no DOM; schema.d.ts é gerado do contrato (só tipos)
      exclude: ['src/main.tsx', 'src/test/**', 'src/**/*.test.{ts,tsx}', 'src/vite-env.d.ts', 'src/api/schema.d.ts'],
      thresholds: { lines: 100, branches: 100, functions: 100, statements: 100 },   // docs/testes.md
    },
  },
})
