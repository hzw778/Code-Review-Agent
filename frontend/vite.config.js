import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// Vite 配置
// server.port: 前端端口（原 5193，Vite 默认 5173，这里保持 5193 一致）
// server.proxy: /api 代理到后端 8080，避免 CORS
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5193,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api/, ''),
      },
    },
  },
})
