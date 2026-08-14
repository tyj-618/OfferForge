import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 开发代理：/api → 本地 Spring Boot（默认 8081）
export default defineConfig({
  plugins: [vue()],
  build: {
    rollupOptions: {
      output: {
        // echarts 体积大，单独分包避免主 chunk 超阈警告
        manualChunks: { echarts: ['echarts'] }
      }
    }
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8081',
        changeOrigin: true
      }
    }
  }
})
