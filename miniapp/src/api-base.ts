import { normalizeApiBaseUrl } from './backend-url'
export { DEFAULT_API_BASE_URL, normalizeApiBaseUrl } from './backend-url'

export function backendBaseUrl(): string { return normalizeApiBaseUrl(import.meta.env.VITE_API_BASE_URL) }

export function apiUrl(path: string): string {
  let base = backendBaseUrl()
  // The Vite proxy is available only during H5 development. Production H5 and
  // WeChat always use the configured backend, independent of the page origin.
  // #ifdef H5
  if (import.meta.env.DEV && import.meta.env.VITE_H5_USE_API_PROXY !== 'false') base = ''
  // #endif
  const resource = path.replace(/^\/+/, '').replace(/^(?:api\/)+/i, '').replace(/^api(?=\?|$)/i, '')
  return `${base}/api${resource && !resource.startsWith('?') ? '/' : ''}${resource}`
}

/** Server media may be a backend-relative path or an explicit external URL. */
export function backendResourceUrl(value: unknown): string {
  const resource = typeof value === 'string' ? value.trim() : ''
  if (!resource || /^(?:https?:|data:|blob:|wxfile:)/i.test(resource)) return resource
  if (resource.startsWith('//')) return `https:${resource}`
  return `${backendBaseUrl()}/${resource.replace(/^\/+/, '')}`
}
