import { fileURLToPath, URL } from 'node:url'
import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const gatewayUrl = env.VITE_GATEWAY_DEV_URL || 'http://127.0.0.1:8080'
  const outputDirectory = mode === 'gateway'
    ? fileURLToPath(new URL('../flowdesk-gateway/src/main/resources/static', import.meta.url))
    : 'dist'

  return {
    plugins: [vue()],
    resolve: {
      alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) }
    },
    build: {
      outDir: outputDirectory,
      // A clean output prevents stale chunks (including old local-only copy)
      // from remaining reachable after a rebuild.
      emptyOutDir: true,
      // Gateway artifacts are deployable browser assets; do not publish source
      // maps that expose internal implementation and test-only modules.
      sourcemap: mode !== 'production' && mode !== 'gateway'
    },
    server: {
      host: '127.0.0.1',
      port: Number(env.VITE_DEV_PORT || 5173),
      proxy: {
        '/api/v1': {
          target: gatewayUrl,
          changeOrigin: false,
          secure: false
        }
      }
    },
    define: {
      __FLOWDESK_GATEWAY_MODE__: JSON.stringify(mode === 'gateway')
    }
  }
})
