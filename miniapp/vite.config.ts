import { defineConfig, loadEnv } from 'vite'
import uni from '@dcloudio/vite-plugin-uni'
import { normalizeApiBaseUrl } from './src/backend-url'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '.', 'VITE_')
  return {
    plugins: [uni()],
    server: { host: '0.0.0.0', port: 5174, proxy: { '/api': { target: normalizeApiBaseUrl(env.VITE_API_BASE_URL), changeOrigin: true } } },
  }
})
