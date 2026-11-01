export type WechatLoginFailureKind = 'proxy' | 'network' | 'timeout' | 'cancelled' | 'environment' | 'unknown'

function readErrMsg(failure: unknown): string {
  if (!failure || typeof failure !== 'object') return ''
  // Only the failure description is relevant. Never serialize the SDK object,
  // which may also contain credentials or other account information.
  const descriptor = Object.getOwnPropertyDescriptor(failure, 'errMsg')
  return typeof descriptor?.value === 'string' ? descriptor.value : ''
}

export function classifyWechatLoginFailure(failure: unknown): WechatLoginFailureKind {
  const message = readErrMsg(failure)
  if (/\b(?:cancel|cancelled|canceled|cancellation)\b|用户取消|已取消|取消(?:登录|登陆|授权|操作)/i.test(message)) return 'cancelled'
  if (/timeout|timed?\s*out|ETIMEDOUT|超时/i.test(message)) return 'timeout'
  if (/proxy|tunnel|socks|代理/i.test(message)) return 'proxy'
  if (/app[\s_-]?id|开发者工具|devtools|工具.*(?:登录|登陆)|(?:未|请先|重新)(?:登录|登陆)|not\s+(?:logged?\s*in|login)|please\s+log\s*in|login\s+(?:required|state\s+(?:expired|invalid))|登录(?:态|状态).*(?:过期|失效)/i.test(message)) return 'environment'
  if (/network|网络|连接|connection|\b(?:dns|socket|ENOTFOUND|EHOSTUNREACH)\b|ECONN|ERR_(?:INTERNET|NAME_NOT_RESOLVED|ADDRESS_UNREACHABLE)|failed\s+to\s+fetch/i.test(message)) return 'network'
  return 'unknown'
}

export function safeWechatLoginDiagnostic(failure: unknown): string {
  const message = readErrMsg(failure)
    .replace(/[\u0000-\u001f\u007f-\u009f\u202a-\u202e\u2066-\u2069]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
  if (!message) return ''
  // Fail closed for credential fields, URLs, encoded text or opaque identifiers.
  // Keep only ordinary SDK descriptions, including useful ERR_PROXY_* markers.
  const opaque = message.match(/[A-Za-z0-9+/_=-]{24,}/g) || []
  if (/code|token|secret|session[\s_-]?key|authorization|bearer|password|openid|unionid|:\/\/|%[0-9a-f]{2}/i.test(message)
    || opaque.some(value => !/^ERR_[A-Z0-9_]+$/.test(value))) {
    return '错误原文包含凭据特征、链接或不透明标识，已隐藏。'
  }
  return message.length > 240 ? message.slice(0, 240) + '…' : message
}

const messages: Record<WechatLoginFailureKind, string> = {
  proxy: '微信登录连接失败，请检查网络和代理设置后重试。',
  network: '暂时连接不到微信登录服务，请检查网络后重试。',
  timeout: '微信登录请求超时，请稍后重试。',
  cancelled: '已取消微信登录，您可重新发起。',
  environment: '微信登录环境不可用，请核对小程序 AppID、开发者工具登录状态和项目权限。',
  unknown: '微信登录暂未完成，请稍后重试。',
}

export class WechatLoginError extends Error {
  readonly kind: WechatLoginFailureKind
  readonly diagnostic: string

  constructor(failure: unknown) {
    const kind = classifyWechatLoginFailure(failure)
    super(messages[kind])
    this.name = 'WechatLoginError'
    this.kind = kind
    this.diagnostic = safeWechatLoginDiagnostic(failure)
  }
}
