<script setup lang="ts">
import { computed, ref, onMounted, onBeforeUnmount, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { navigation } from '../router'
import { session, logout, api, post, roles, errorText, type Row } from '../lib/api'
import { startAdminTodoUpdates, useAdminTodos } from '../lib/todos'
const route = useRoute(), router = useRouter()
const collapsed = ref(false), mode = ref(''), connected = ref(false), checking = ref(false), exiting = ref(false)
const menus = computed(() => navigation.filter(item => item.roles.includes(session.user?.role || '')))
const groups = computed(() => [...new Set(menus.value.map(item => item.group))])
const todos = useAdminTodos()
const activePath = computed(() => route.path === '/orders' && route.query.worklist === 'refunds' ? '/payments' : route.path)
const menuTodos = computed(() => {
  const result: Record<string, { count: number; to: string; description: string }> = {}
  const operations = todos.data.value?.operations, finance = todos.data.value?.finance
  if (operations) result['/orders'] = { count: operations.total, to: '/orders?worklist=operations', description: `待报价 ${operations.pendingQuotes}，待派单 ${operations.pendingDispatch}，取消审核 ${operations.cancelRequests}` }
  if (finance) {
    result['/payments'] = { count: finance.pendingRefunds, to: '/orders?worklist=refunds', description: `待退款 ${finance.pendingRefunds}` }
    result['/withdrawals'] = { count: finance.pendingWithdrawals + finance.approvedWithdrawals, to: '/withdrawals?worklist=todo', description: `待审核 ${finance.pendingWithdrawals}，待登记付款 ${finance.approvedWithdrawals}` }
  }
  return result
})
const todoUpdateTime = computed(() => todos.updatedAt.value?.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false }) || '')
let stopTodoUpdates: (() => void) | undefined
let alive = true
async function health() {
  if (checking.value) return
  checking.value = true
  try { const data = await api<Row>('/health'); if (alive) { mode.value = String(data.mode || ''); connected.value = true } }
  catch { if (alive) connected.value = false }
  finally { if (alive) checking.value = false }
}
function closeNavigation() { collapsed.value = false }
function keydown(event: KeyboardEvent) { if (event.key === 'Escape') closeNavigation() }
watch(() => route.fullPath, () => { closeNavigation(); if (document.visibilityState !== 'hidden') void todos.refresh(true) })
watch(collapsed, value => { document.body.style.overflow = value ? 'hidden' : '' })
onMounted(() => { void health(); stopTodoUpdates = startAdminTodoUpdates(); document.addEventListener('keydown', keydown); window.addEventListener('resize', closeNavigation) })
onBeforeUnmount(() => { alive = false; stopTodoUpdates?.(); document.body.style.overflow = ''; document.removeEventListener('keydown', keydown); window.removeEventListener('resize', closeNavigation) })
async function exit(command: string) {
  if (command !== 'logout' || exiting.value) return
  exiting.value = true
  try {
    await ElMessageBox.confirm('退出后需重新登录才能继续处理业务。', '退出登录', { confirmButtonText: '退出登录', cancelButtonText: '继续使用', type: 'warning' })
    await post('/auth/logout')
    logout(); await router.replace('/login')
  } catch (error) { if (error !== 'cancel' && error !== 'close') ElMessage.error(errorText(error)) }
  finally { if (alive) exiting.value = false }
}
</script>
<template>
  <div class="app-shell" :class="{ 'sidebar-open': collapsed }">
    <aside id="admin-navigation" class="sidebar" :class="{ 'navigation-visible': collapsed }">
      <div class="sidebar-brand"><router-link class="brand" to="/dashboard"><AppIcon name="Van" :size="24"/><strong>预约出行</strong></router-link><el-button class="sidebar-close" text aria-label="关闭导航" @click="closeNavigation"><AppIcon name="Close" :size="20"/></el-button></div>
      <div class="workspace-label">运营管理后台</div>
      <nav aria-label="后台导航">
        <div v-for="group in groups" :key="group" class="nav-group">
          <div class="nav-group-label">{{ group }}</div>
          <div v-for="item in menus.filter(x => x.group === group)" :key="item.path" class="nav-item" :class="{ 'is-active': activePath === item.path }">
            <router-link :to="item.path" class="nav-link" :aria-current="activePath === item.path ? 'page' : undefined" @click="closeNavigation"><AppIcon :name="item.icon" :size="20"/><span>{{ item.label }}</span></router-link>
            <router-link v-if="menuTodos[item.path]?.count > 0" :to="menuTodos[item.path].to" class="nav-todo-link" :class="{ 'is-stale': todos.error.value }" :aria-label="`${item.label}，${todos.error.value ? '上次统计' : '待处理'} ${menuTodos[item.path].count} 项，查看待办`" :title="`${menuTodos[item.path].description}${todos.error.value ? '；更新失败，当前为上次统计' : '；点击处理'}`" @click="closeNavigation"><span class="nav-todo-count">{{ menuTodos[item.path].count > 99 ? '99+' : menuTodos[item.path].count }}</span></router-link>
          </div>
        </div>
      </nav>
      <div class="sidebar-todo-status" :class="{ 'has-error': todos.error.value }">
        <span role="status" :title="todos.error.value || '可见页面每 30 秒更新，业务处理后自动刷新'">{{ todos.error.value ? (todos.data.value ? '待办更新失败，保留上次统计' : '待办暂不可用，请重试') : todos.loading.value && !todos.data.value ? '正在加载待办…' : todoUpdateTime ? `待办更新于 ${todoUpdateTime}` : '待办尚未加载' }}</span>
        <button :disabled="todos.loading.value" :aria-label="todos.error.value ? '重试加载待办' : '刷新待办'" :title="todos.error.value ? '重试加载待办' : '刷新待办'" @click="todos.refresh(true)"><AppIcon name="Refresh" :size="16"/></button>
      </div>
    </aside>
    <div v-if="collapsed" class="sidebar-mask" @click="closeNavigation"></div>
    <div class="main-shell">
      <header class="topbar"><div class="breadcrumb"><el-button class="mobile-toggle" text :aria-expanded="collapsed" aria-controls="admin-navigation" :aria-label="collapsed ? '关闭导航' : '打开导航'" @click="collapsed = !collapsed"><AppIcon name="Menu" :size="20"/></el-button><router-link class="breadcrumb-home" to="/dashboard">工作台</router-link><AppIcon name="ArrowRight" :size="12"/><strong>{{ route.meta.title }}</strong></div><div class="topbar-right"><button class="connection-state" :disabled="checking" :title="connected ? '点击重新检查服务连接' : '连接异常，点击重试'" @click="health"><i :class="{ online: connected }"></i>{{ checking ? '检查连接中' : connected ? '服务正常' : '重新连接' }}</button><el-tag v-if="mode.toUpperCase().includes('DEV')" type="warning" effect="plain">开发环境</el-tag><el-dropdown trigger="click" @command="exit"><button class="account" :disabled="exiting" aria-label="账号菜单"><AppIcon name="User" :size="20"/><span>{{ session.user?.nickname }}<small>{{ roles[session.user?.role || ''] }}</small></span><AppIcon name="ArrowDown" :size="12"/></button><template #dropdown><el-dropdown-menu><el-dropdown-item command="logout" :disabled="exiting"><AppIcon name="SwitchButton"/>{{ exiting ? '正在退出…' : '退出登录' }}</el-dropdown-item></el-dropdown-menu></template></el-dropdown></div></header>
      <div v-if="mode.toUpperCase().includes('DEV')" class="dev-banner">开发环境的支付与退款为模拟记录，未发生真实资金交易。</div>
      <main class="content-shell"><router-view :key="String(route.name || route.path)"/></main>
    </div>
  </div>
</template>
