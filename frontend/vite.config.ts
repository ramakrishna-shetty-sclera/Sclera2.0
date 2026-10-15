import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Dev proxy: every /api call goes to the API gateway (single entry point,
// which relays the BFF session as a Bearer token to the downstream services).
// /auth goes straight to Keycloak — used only by the dev direct-login path
// (password grant + /api/auth/test-exchange); the production login flow is a
// full-page redirect through the gateway's BFF endpoints.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        // 8081, not the gateway's usual 8080: the sibling FDE/copilot app
        // hard-binds 8080, and the two cannot share a machine otherwise.
        target: 'http://localhost:8081',
        changeOrigin: true,
      },
      '/auth': {
        target: 'http://localhost:8180',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/auth/, ''),
      },
    },
  },
})
