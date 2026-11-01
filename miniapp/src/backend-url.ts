export const DEFAULT_API_BASE_URL = 'http://localhost:18080'

/** Shared by the app and Vite, without depending on a browser or Node URL API. */
export function normalizeApiBaseUrl(value?: string): string {
  const base = (value?.trim() || DEFAULT_API_BASE_URL).replace(/\/+$/, '').replace(/(?:\/api)+$/i, '')
  if (!/^https?:\/\/[^\s/?#@]+(?:\/[^\s?#]*)?$/i.test(base)) throw new Error('后端地址须为不含查询参数或账号信息的 HTTP(S) 地址')
  return base
}
