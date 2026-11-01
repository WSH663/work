import { createRouter, createWebHashHistory } from 'vue-router'
import { session, staffRoles, logout } from './lib/api'

export const navigation = [
  { path: '/dashboard', label: '工作台', icon: 'Monitor', group: '工作台', roles: staffRoles, component: () => import('./views/Dashboard.vue') },
  { path: '/orders', label: '订单与派单', icon: 'Tickets', group: '运营管理', roles: staffRoles, component: () => import('./views/Orders.vue') },
  { path: '/users', label: '用户管理', icon: 'User', group: '运营管理', roles: ['ADMIN', 'OPERATOR'], component: () => import('./views/Users.vue') },
  { path: '/vehicles', label: '车型与价格', icon: 'Van', group: '运营管理', roles: ['ADMIN', 'OPERATOR'], component: () => import('./views/Vehicles.vue') },
  { path: '/content', label: '首页广告', icon: 'Picture', group: '运营管理', roles: ['ADMIN', 'OPERATOR'], component: () => import('./views/Content.vue') },
  { path: '/information', label: '平台公告', icon: 'Document', group: '运营管理', roles: ['ADMIN', 'OPERATOR'], component: () => import('./views/Content.vue') },
  { path: '/documents', label: '文档配置', icon: 'DocumentCopy', group: '运营管理', roles: ['ADMIN', 'OPERATOR'], component: () => import('./views/Documents.vue') },
  { path: '/payments', label: '支付与退款', icon: 'Money', group: '财务管理', roles: ['ADMIN', 'FINANCE'], component: () => import('./views/Records.vue') },
  { path: '/commissions', label: '推广与佣金', icon: 'Share', group: '财务管理', roles: ['ADMIN', 'FINANCE'], component: () => import('./views/Records.vue') },
  { path: '/withdrawals', label: '提现审核', icon: 'Wallet', group: '财务管理', roles: ['ADMIN', 'FINANCE'], component: () => import('./views/Withdrawals.vue') },
  { path: '/settings', label: '平台配置', icon: 'Setting', group: '系统管理', roles: ['ADMIN'], component: () => import('./views/Settings.vue') },
  { path: '/admins', label: '管理员与权限', icon: 'UserFilled', group: '系统管理', roles: ['ADMIN'], component: () => import('./views/Admins.vue') },
  { path: '/logs', label: '操作日志', icon: 'Document', group: '系统管理', roles: ['ADMIN'], component: () => import('./views/Records.vue') },
]
const router = createRouter({ history: createWebHashHistory(), routes: [
  { path: '/login', component: () => import('./views/Login.vue') },
  { path: '/', component: () => import('./views/Layout.vue'), children: [
    { path: '', redirect: '/dashboard' },
    ...navigation.map(item => ({ path: item.path.slice(1), component: item.component, meta: { title: item.label, roles: item.roles } })),
  ] },
  { path: '/:pathMatch(.*)*', redirect: '/dashboard' },
] })
export function loginDestination(value: unknown): string {
  if (typeof value !== 'string' || !value.startsWith('/') || value.startsWith('//')) return '/dashboard'
  const path = value.split(/[?#]/)[0]
  const target = navigation.find(item => item.path === path && item.roles.includes(session.user?.role || ''))
  return target ? value : '/dashboard'
}
router.beforeEach(to => {
  document.title = `${to.meta.title || '登录'} · 预约出行运营中心`
  const authorized = session.token && session.user && staffRoles.includes(session.user.role)
  if (!authorized && to.path !== '/login') { logout(); return { path: '/login', query: { redirect: to.fullPath } } }
  if (authorized && to.path === '/login') return loginDestination(to.query.redirect)
  const allowed = to.meta.roles as string[] | undefined
  if (allowed && !allowed.includes(session.user?.role || '')) return '/dashboard'
  if (authorized && ['/content', '/information'].includes(to.path) && to.query.kind === 'SERVICE') return { path: '/documents', query: { kind: 'service' } }
})
window.addEventListener('session-expired', () => {
  const current = router.currentRoute.value
  if (current.path !== '/login') void router.replace({ path: '/login', query: { redirect: current.fullPath } })
})
export default router
