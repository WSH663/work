export type NetworkErrorCode = 'NET_DOMAIN' | 'NET_TLS' | 'NET_TIMEOUT' | 'NET_DNS' | 'NET_PROXY' | 'NET_OFFLINE' | 'NET_UNKNOWN'

const messages: Record<NetworkErrorCode, string> = {
  NET_DOMAIN: '服务域名校验失败',
  NET_TLS: '安全连接失败',
  NET_TIMEOUT: '连接超时，请稍后重试',
  NET_DNS: '服务地址解析失败',
  NET_PROXY: '网络代理连接失败',
  NET_OFFLINE: '网络不可用，请检查网络',
  NET_UNKNOWN: '暂时连接不到服务'
}

export class ApiNetworkError extends Error {
  constructor(public code: NetworkErrorCode) {
    super(`${messages[code]}（${code}）`)
    this.name = 'ApiNetworkError'
  }
}

function sdkFailure(failure: unknown): { errMsg?: unknown; errCode?: unknown } {
  return failure && typeof failure === 'object' ? failure : {}
}

export function classifyNetworkFailure(failure: unknown): NetworkErrorCode {
  const text = String(sdkFailure(failure).errMsg || '')
  if (/not\s+in\s+domain\s+list|domain\s+(?:not\s+(?:allowed|configured)|is\s+not\s+allowed)|url.*合法域名|不在.*域名.*列表|域名.*未.*配置/i.test(text)) return 'NET_DOMAIN'
  if (/proxy|代理/i.test(text)) return 'NET_PROXY'
  if (/ssl|tls|certificate|cert_|证书/i.test(text)) return 'NET_TLS'
  if (/time[ -]?out|timed?\s*out|超时/i.test(text)) return 'NET_TIMEOUT'
  if (/dns|name_not_resolved|getaddrinfo|host\s+not\s+found|resolve\s+(?:the\s+)?host|域名解析|无法解析/i.test(text)) return 'NET_DNS'
  if (/internet_disconnected|network\s+(?:is\s+)?(?:unreachable|unavailable|disconnected|offline)|not\s+connected|\boffline\b|断网|无网络|网络不可用|网络连接已断开/i.test(text)) return 'NET_OFFLINE'
  return 'NET_UNKNOWN'
}

function safePath(path: string): string {
  return path.replace(/(\/(?:orders|passengers|addresses|messages)\/)[^/]+/gi, '$1:id')
    .replace(/\/\d+(?=\/|$)/g, '/:id')
    .replace(/\/[a-f\d-]{24,}(?=\/|$)/gi, '/:id')
}

/** Keep only the request origin and route; never log URL credentials or query values. */
export function safeRequestLocation(url: string): { origin: string; path: string } {
  const clean = url.split(/[?#]/, 1)[0]
  const match = /^(https?):\/\/([^/]+)(\/.*)?$/i.exec(clean)
  return match
    ? { origin: `${match[1]}://${match[2].replace(/^.*@/, '')}`, path: safePath(match[3] || '/') }
    : { origin: '', path: safePath(clean.startsWith('/') ? clean : '/') }
}

export function safeSdkMessage(value: unknown): string {
  if (typeof value !== 'string') return 'request:fail'
  return value.replace(/[\r\n\t\u0000-\u001f]/g, ' ')
    .replace(/https?:\/\/[^\s"'<>]+/gi, url => { const location = safeRequestLocation(url); return location.origin + location.path })
    .replace(/\?[^\s"'<>]*/g, '?[redacted]')
    .replace(/\b(?:authorization\s*[:=]?\s*)?(?:bearer|basic)\s+[A-Za-z\d._~+/=-]+/gi, 'Authorization [redacted]')
    .replace(/\b(authorization|cookie|set-cookie|access[_-]?token|token|appsecret|secret|password|passwd|session[_-]?(?:key|id)|code|openid|unionid|phone|mobile|latitude|longitude|location|keywords)["']?\s*[:=]\s*(?:"[^"]*"|'[^']*'|[^,\s"';&)}]+)/gi, '$1=[redacted]')
    .replace(/\beyJ[A-Za-z\d_-]+\.[A-Za-z\d_-]+\.[A-Za-z\d_-]+\b/g, '[redacted]')
    .slice(0, 480)
}

export function networkFailureDiagnostic(failure: unknown, url: string, method: string) {
  const sdk = sdkFailure(failure)
  const errCode = typeof sdk.errCode === 'number' && Number.isFinite(sdk.errCode)
    ? sdk.errCode : typeof sdk.errCode === 'string' && /^-?\d{1,12}$/.test(sdk.errCode) ? sdk.errCode : undefined
  return { code: classifyNetworkFailure(failure), ...safeRequestLocation(url), method, errMsg: safeSdkMessage(sdk.errMsg), errCode }
}
