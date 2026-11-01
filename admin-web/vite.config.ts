import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'VITE_')
  const configuredBase = env.VITE_API_BASE_URL?.trim().replace(/\/+$/, '')
  const proxyTarget = configuredBase && configuredBase !== '/api'
    ? configuredBase.replace(/\/api$/, '')
    : 'http://localhost:18080'

  return {
    plugins: [vue()],
    server: { port: 5173, strictPort: true, proxy: { '/api': { target: proxyTarget, changeOrigin: true } } },
    build: { chunkSizeWarningLimit: 1100, rollupOptions: { output: { manualChunks: { vue: ['vue', 'vue-router'], element: ['element-plus'] } } } },
  }
})
