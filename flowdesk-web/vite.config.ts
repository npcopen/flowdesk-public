import { fileURLToPath, URL } from 'node:url'
import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const gatewayUrl = env.VITE_GATEWAY_DEV_URL || 'http://127.0.0.1:8080'

  return {
    plugins: [vue()],
    resolve: {
      alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) }
    },
    build: {
      // 前端独立交付，由 Nginx 提供 dist，不再写入后端源码目录。
      outDir: 'dist',
      // 构建前清理输出，避免旧分块或本地演示内容在重新部署后仍可访问。
      emptyOutDir: true,
      sourcemap: false
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
    }
  }
})
