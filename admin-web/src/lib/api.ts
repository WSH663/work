import { reactive } from 'vue'

export type Row = Record<string, any>
export interface Page<T = Row> { items: T[]; total: number; page: number; size: number }
export interface User { id: number; nickname: string; role: string }
const configuredBase = (import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:18080').trim().replace(/\/+$/, '')
export const API_BASE_URL = configuredBase.endsWith('/api') ? configuredBase : `${configuredBase}/api`
function apiUrl(path: string) {
  return `${API_BASE_URL}/${path.replace(/^\/+/, '').replace(/^api(?:\/|$)/, '')}`
}
const key = 'accessible-travel-admin-session'
function restore(): { token: string; user: User | null } {
  try { const value = JSON.parse(localStorage.getItem(key) || 'null'); return value && value.token && value.user ? value : { token: '', user: null } }
  catch { return { token: '', user: null } }
}
export const session = reactive(restore())
export const staffRoles = ['ADMIN', 'OPERATOR', 'FINANCE']
export class ApiError extends Error {
  constructor(message: string, public readonly status: number) { super(message); this.name = 'ApiError' }
}
export function saveSession(token: string, user: User) {
  session.token = token; session.user = user
  localStorage.setItem(key, JSON.stringify({ token, user }))
}
export function logout() { session.token = ''; session.user = null; localStorage.removeItem(key) }
export function can(...roles: string[]) { return !!session.user && roles.includes(session.user.role) }
export async function api<T = Row>(path: string, options: RequestInit = {}): Promise<T> {
  const requestToken = session.token
  const abort = new AbortController()
  const timeout = window.setTimeout(() => abort.abort(), 20000)
  try {
    const headers = new Headers(options.headers)
    if (options.body) headers.set('Content-Type', 'application/json')
    if (requestToken) headers.set('Authorization', `Bearer ${requestToken}`)
    const response = await fetch(apiUrl(path), { ...options, headers, signal: abort.signal })
    const result = await response.json().catch(() => null)
    if (requestToken && requestToken !== session.token) throw new Error('登录身份已变化，请重新加载当前页面')
    if (response.status === 401 && !path.startsWith('/auth/') && requestToken === session.token) {
      logout()
      window.dispatchEvent(new Event('session-expired'))
      throw new Error('登录已过期，请重新登录')
    }
    if (!response.ok || result?.code !== 0) throw new ApiError(result?.message || (response.status === 403 ? '当前账号没有此操作权限' : `服务请求失败（${response.status}）`), response.status)
    if (!['GET', 'HEAD', 'OPTIONS'].includes((options.method || 'GET').toUpperCase()) && path.startsWith('/admin/')) {
      window.dispatchEvent(new Event('admin-data-changed'))
    }
    return result.data as T
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') throw new Error('请求超时，请检查后端服务后重试')
    if (error instanceof TypeError) throw new Error('无法连接服务，请检查网络或后端是否启动')
    throw error
  } finally { window.clearTimeout(timeout) }
}
export function post<T = Row>(path: string, body?: unknown) { return api<T>(path, { method: 'POST', body: body === undefined ? undefined : JSON.stringify(body) }) }
export function patch<T = Row>(path: string, body: unknown) { return api<T>(path, { method: 'PATCH', body: JSON.stringify(body) }) }
export function query(params: Row) { const value = new URLSearchParams(); Object.entries(params).forEach(([k, v]) => { if (v !== '' && v !== undefined && v !== null) value.set(k, String(v)) }); return value.toString() }
export function errorText(error: unknown) { return error instanceof Error ? error.message : '操作失败，请稍后重试' }
export function money(value: unknown) { return new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY' }).format(Number(value || 0)) }
export function dateTime(value: unknown) { if (!value) return '—'; return String(value).replace('T', ' ').slice(0, 19) }
export const statusNames: Record<string, string> = { PENDING_QUOTE: '待报价', PENDING_CONFIRM: '待用户确认报价', PENDING_PAYMENT: '待支付', PENDING_DISPATCH: '待派单', DISPATCHED: '已派单', IN_SERVICE: '服务中', COMPLETED: '已完成', CANCEL_REQUESTED: '取消待审核', CANCELLED: '已取消', REFUNDING: '待退款', REFUNDED: '已退款', PENDING: '待审核', APPROVED: '已通过', REJECTED: '已驳回', PAID: '已付款', SUCCESS: '成功', FAILED: '失败', ACTIVE: '正常', DISABLED: '已停用', AVAILABLE: '可结算', SETTLED: '已结算', REVERSED: '已回退', FROZEN: '冻结中' }
export const orderStatuses = ['PENDING_QUOTE', 'PENDING_CONFIRM', 'PENDING_PAYMENT', 'PENDING_DISPATCH', 'DISPATCHED', 'IN_SERVICE', 'COMPLETED', 'CANCEL_REQUESTED', 'CANCELLED', 'REFUNDING', 'REFUNDED']
export const roles: Record<string, string> = { ADMIN: '超级管理员', OPERATOR: '运营人员', FINANCE: '财务人员' }
export function statusType(value: string): 'success' | 'warning' | 'danger' | 'info' | 'primary' { if (['COMPLETED', 'PAID', 'SUCCESS', 'ACTIVE', 'SETTLED', 'APPROVED'].includes(value)) return 'success'; if (['FAILED', 'DISABLED', 'REJECTED'].includes(value)) return 'danger'; if (['CANCELLED', 'REFUNDED', 'REVERSED'].includes(value)) return 'info'; if (['PENDING_DISPATCH', 'PENDING_QUOTE', 'CANCEL_REQUESTED', 'PENDING', 'REFUNDING'].includes(value)) return 'warning'; return 'primary' }
