import { WechatLoginError } from './wechat-login-error'

export let isWechatMiniProgram = false
// #ifdef MP-WEIXIN
isWechatMiniProgram = true
// #endif

export function supportsWechatPhoneAuthorization(): boolean {
  if (!isWechatMiniProgram) return false
  try { return uni.canIUse('button.open-type.getPhoneNumber') === true }
  catch { return false }
}

export interface WechatPhonePrompt { version: number; token: string; page: unknown; expiresAt: number; code?: string }
export function createWechatPhonePrompt(version: number): WechatPhonePrompt {
  const pages = getCurrentPages()
  return { version, token: String(uni.getStorageSync('travel_token') || ''), page: pages[pages.length - 1], expiresAt: Date.now() + 120000 }
}
export function isWechatPhonePromptCurrent(prompt: WechatPhonePrompt, version: number): boolean {
  const pages = getCurrentPages()
  return prompt.version === version && prompt.token === String(uni.getStorageSync('travel_token') || '') && prompt.page === pages[pages.length - 1] && Date.now() < prompt.expiresAt
}

export function readWechatPhoneCode(event: unknown, usage: 'login' | 'binding' = 'login'): string {
  const alternative = usage === 'login' ? '，或选择“微信登录，稍后绑定”' : '；原手机号保持不变'
  const detail = event && typeof event === 'object' ? (event as { detail?: unknown }).detail : null
  const result = detail && typeof detail === 'object' ? detail as { code?: unknown; errMsg?: unknown } : {}
  const message = typeof result.errMsg === 'string' ? result.errMsg : ''
  if (/user\s*(?:deny|denied|cancel)|cancel|用户拒绝|用户取消|拒绝授权|取消授权/i.test(message)) {
    throw new Error(`您未授权手机号，可重新授权${alternative}。`)
  }
  if (/no\s*permission|permission\s*denied|not\s*(?:authorized|enabled)|无权限|未开通|没有权限/i.test(message)) {
    throw new Error(`小程序暂未获得微信手机号接口权限，请稍后重试${alternative}。`)
  }
  if (/not\s*support|unsupported|不支持|version|版本/i.test(message)) {
    throw new Error(`当前微信版本不支持手机号授权，请更新微信${alternative}。`)
  }
  if (message && !/^getPhoneNumber:ok$/i.test(message.trim())) {
    throw new Error(`微信手机号授权未完成，请重新授权${alternative}。`)
  }
  if (typeof result.code !== 'string' || !result.code.trim()) {
    throw new Error(`未取得手机号授权凭证，请更新微信后重新授权${alternative}。`)
  }
  return result.code.trim()
}

export function requestWechatLoginCode(): Promise<string> {
  return new Promise((resolve, reject) => {
    // #ifdef MP-WEIXIN
    uni.login({
      provider: 'weixin',
      success: result => result.code ? resolve(result.code) : reject(new Error('未取得微信登录凭证，请重新登录。')),
      fail: failure => reject(new WechatLoginError(failure)),
    })
    // #endif
    // #ifndef MP-WEIXIN
    reject(new Error('请在微信小程序内完成微信登录和手机号授权。'))
    // #endif
  })
}
