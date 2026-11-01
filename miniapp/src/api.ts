import { clearAccountSession } from './account-session'
import { apiUrl } from './api-base'
import { ApiNetworkError, networkFailureDiagnostic } from './api-network-error'

export type Data = Record<string, any>
export interface Vehicle { id: number; name: string; code: string; seats: number; description: string; baseFare: number; perKm: number; includedKm: number; charterHourly: number; minimumHours: number; surcharge: number; enabled: boolean }
export interface Place { name: string; address: string; location: string; district?: string }
export interface Order extends Data { id: number; orderNo: string; type: string; status: string; originName: string; destinationName: string; appointmentAt: string; amount: number; paidAmount: number; vehicleName: string; quoteVersion?: number; actions?: Record<string, boolean>; cancelRules?: { allowed: boolean; reason: string; cutoffAt?: string }; appointmentExpired?: boolean; finance?: { totalPaid: number; refunded: number; netPaid: number }; nextStep?: string; payments?: Data[] }
export class ApiError extends Error { constructor(message: string, public status: number) { super(message); this.name = 'ApiError' } }
export const statusLabels: Record<string, string> = { PENDING_QUOTE: '待人工报价', PENDING_CONFIRM: '待确认报价', PENDING_PAYMENT: '待支付', PENDING_DISPATCH: '待派单', DISPATCHED: '已派单', IN_SERVICE: '服务中', COMPLETED: '已完成', CANCEL_REQUESTED: '取消审核中', CANCELLED: '已取消', REFUNDING: '待退款', REFUNDED: '已退款' }
export const money = (value: any) => Number(value || 0).toFixed(2)
export const dateText = (value: any) => value ? String(value).replace('T', ' ').slice(0, 16) : '—'
function miniProgramRuntime(): Record<string, string> | undefined {
  // #ifdef MP-WEIXIN
  try {
    const info = uni.getAccountInfoSync().miniProgram
    return {
      envVersion: ['develop', 'trial', 'release'].includes(info.envVersion) ? info.envVersion : '',
      version: /^[\w.-]{0,40}$/.test(info.version) ? info.version : '',
      appId: /^wx[a-z\d]{16}$/i.test(info.appId) ? info.appId : ''
    }
  } catch { /* Older SDKs may not expose account information. */ }
  // #endif
  return undefined
}
export function api<T = Data>(path: string, method: 'GET' | 'POST' | 'PATCH' | 'DELETE' = 'GET', data?: any): Promise<T> {
  const token = uni.getStorageSync('travel_token')
  const resourcePath = path.split('?')[0]
  const protectedResource = resourcePath === '/me' || resourcePath.startsWith('/me/') || resourcePath === '/orders' || resourcePath.startsWith('/orders/')
  const url = apiUrl(path), requestMethod = method === 'PATCH' ? 'POST' : method
  return new Promise((resolve, reject) => uni.request({
    // 微信 request 不支持 PATCH；用户资料使用服务端 POST /me 兼容入口。
    url, method: requestMethod, data, timeout: 15000,
    header: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    success: (res) => {
      const body = res.data && typeof res.data === 'object' ? res.data as Data : null
      if (res.statusCode >= 200 && res.statusCode < 300 && body?.code === 0) resolve(body.data as T)
      else {
        if (res.statusCode === 401 && protectedResource && token && uni.getStorageSync('travel_token') === token) clearAccountSession()
        reject(new ApiError(body?.message || `请求失败（${res.statusCode}）`, res.statusCode))
      }
    },
    fail: failure => {
      const diagnostic = networkFailureDiagnostic(failure, url, requestMethod)
      console.warn('[API_NETWORK_ERROR]', { ...diagnostic, miniProgram: miniProgramRuntime() })
      reject(new ApiNetworkError(diagnostic.code))
    }
  }))
}
export function errorToast(e: unknown) { uni.showToast({ title: e instanceof Error ? e.message : '操作未完成，请稍后重试', icon: 'none', duration: 3000 }) }
export function requireLogin(): boolean {
  if (uni.getStorageSync('travel_token')) return true
  go('/pages/login/index'); return false
}
let pendingNavigation: { source: string; target: string } | null = null
export function go(url: string) {
  const pages=getCurrentPages(),current=pages[pages.length-1] as any
  const source=String(current?.$page?.fullPath||current?.route||'')
  if (pendingNavigation?.source===source&&pendingNavigation.target===url) return
  const navigation={source,target:url};pendingNavigation=navigation
  const path=url.split('?')[0]
  const complete=()=>{if(pendingNavigation===navigation)pendingNavigation=null}
  const fail=()=>errorToast(new Error('页面暂时无法打开，请返回后重试'))
  if (['/pages/index/index','/pages/orders/index','/pages/profile/index'].includes(path)) {
    const query=url.includes('?')?url.slice(url.indexOf('?')+1):''
    if(query)uni.setStorageSync('travel_tab_query_'+path,query)
    uni.switchTab({url:path,fail,complete})
  } else uni.navigateTo({url,fail,complete})
}
export function copyText(data: string, label = '已复制') { if(!data.trim())return errorToast(new Error('暂无可复制的内容'));uni.setClipboardData({data,success:()=>uni.showToast({title:label,icon:'none'}),fail:()=>errorToast(new Error('复制未完成，请重试'))}) }
export function consumeTabQuery(path:string):Data { const key='travel_tab_query_'+path;const query=uni.getStorageSync(key);uni.removeStorageSync(key);const result:Data={};if(typeof query==='string')for(const part of query.split('&')){const i=part.indexOf('=');if(i<0)continue;try{result[decodeURIComponent(part.slice(0,i))]=decodeURIComponent(part.slice(i+1))}catch{/* 忽略无效参数 */}}return result }
export async function confirm(title: string, content: string): Promise<boolean> { return new Promise(resolve => uni.showModal({ title, content, success: r => resolve(r.confirm), fail: () => resolve(false) })) }
let serviceCallBusy = false
let phoneCallBusy = false
export async function confirmPhoneCall(phone: string, title = '拨打电话'): Promise<void> {
  if (phoneCallBusy) return
  const displayPhone = String(phone || '').trim()
  if (!/^\+?[\d()\s-]{5,25}$/.test(displayPhone)) { errorToast(new Error('联系电话暂不可用，请稍后重试')); return }
  phoneCallBusy = true
  try {
    const accepted = await new Promise<boolean>(resolve => uni.showModal({
      title, content: `联系电话：${displayPhone}\n是否拨打？`, confirmText: '拨打', cancelText: '取消', confirmColor: '#16845b',
      success: result => resolve(result.confirm), fail: () => resolve(false)
    }))
    if (!accepted) return
    await new Promise<void>(resolve => uni.makePhoneCall({
      phoneNumber: displayPhone.replace(/[()\s-]/g, ''),
      fail: failure => { if (!/cancel|取消/i.test(failure.errMsg || '')) errorToast(new Error('暂时无法发起通话，请稍后重试')) },
      complete: () => resolve()
    }))
  } finally { phoneCallBusy = false }
}
export async function callService(): Promise<void> {
  if (serviceCallBusy) return
  serviceCallBusy = true
  try {
    let phone = String(uni.getStorageSync('travel_service_phone') || '').trim()
    if (!phone) {
      const home = await api<Data>('/public/home')
      phone = String(home.servicePhone || '').trim()
      if (phone) uni.setStorageSync('travel_service_phone', phone)
    }
    if (!phone) { uni.showToast({ title: '客服电话暂未配置，请稍后再试', icon: 'none' }); return }
    await confirmPhoneCall(phone, '联系平台客服')
  } catch (error) { errorToast(error) }
  finally { serviceCallBusy = false }
}
export function safeLink(link: string) {
  const allowed = ['/pages/index/index', '/pages/booking/index', '/pages/orders/index', '/pages/profile/index', '/pages/promotion/index', '/pages/messages/index', '/pages/agreement/index']
  if (!link || !allowed.includes(link.split('?')[0])) { uni.showToast({ title: '暂无可跳转的活动页面', icon: 'none' }); return }
  go(link)
}
