import { readonly, ref, watch } from 'vue'
import { api, errorText, session } from './api'

export interface AdminTodos {
  operations?: { pendingQuotes: number; pendingDispatch: number; cancelRequests: number; total: number }
  finance?: { pendingRefunds: number; pendingWithdrawals: number; approvedWithdrawals: number; total: number }
  total: number
}

const data = ref<AdminTodos | null>(null), loading = ref(false), error = ref(''), updatedAt = ref<Date | null>(null)
let generation = 0, inFlight: Promise<void> | null = null, refreshAgain = false

function reset() {
  generation++
  inFlight = null; refreshAgain = false
  data.value = null; error.value = ''; loading.value = false; updatedAt.value = null
}

async function refresh(invalidate = false): Promise<void> {
  if (!session.token || !session.user) return
  if (inFlight) { if (invalidate) refreshAgain = true; return inFlight }
  const current = generation, role = session.user.role
  loading.value = true
  const request = (async () => {
    try {
      const result = await api<AdminTodos>('/admin/todos')
      if (current !== generation) return
      const counts = [result?.total]
      if (role === 'ADMIN' || role === 'OPERATOR') counts.push(...['pendingQuotes', 'pendingDispatch', 'cancelRequests', 'total'].map(key => result?.operations?.[key as keyof NonNullable<AdminTodos['operations']>] as number))
      if (role === 'ADMIN' || role === 'FINANCE') counts.push(...['pendingRefunds', 'pendingWithdrawals', 'approvedWithdrawals', 'total'].map(key => result?.finance?.[key as keyof NonNullable<AdminTodos['finance']>] as number))
      if (counts.some(count => !Number.isSafeInteger(count) || count < 0)) throw new Error('待办数据不完整，请刷新重试')
      data.value = result; updatedAt.value = new Date(); error.value = ''
    } catch (err) {
      if (current === generation) error.value = errorText(err)
    } finally {
      if (current === generation) {
        loading.value = false; inFlight = null
        if (refreshAgain) { refreshAgain = false; if (document.visibilityState !== 'hidden') void refresh() }
      }
    }
  })()
  inFlight = request
  return request
}

export function useAdminTodos() {
  return { data: readonly(data), loading: readonly(loading), error: readonly(error), updatedAt: readonly(updatedAt), refresh }
}

// The signed-in layout owns the listeners; leaving it clears data and invalidates pending responses.
export function startAdminTodoUpdates() {
  const stopSessionWatch = watch(() => [session.token, session.user?.id, session.user?.role], () => {
    reset()
    if (document.visibilityState !== 'hidden') void refresh()
  }, { immediate: true, flush: 'sync' })
  const update = () => { if (document.visibilityState !== 'hidden') void refresh(true) }
  const timer = window.setInterval(() => {
    if (document.visibilityState !== 'hidden') void refresh()
  }, 30000)
  document.addEventListener('visibilitychange', update)
  window.addEventListener('focus', update)
  window.addEventListener('admin-data-changed', update)
  return () => {
    stopSessionWatch(); window.clearInterval(timer)
    document.removeEventListener('visibilitychange', update)
    window.removeEventListener('focus', update)
    window.removeEventListener('admin-data-changed', update)
    reset()
  }
}
