<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, computed } from 'vue'
import { useRouter } from 'vue-router'
import { api, money, dateTime, errorText, can, type Row } from '../lib/api'
import { useAdminTodos } from '../lib/todos'
import PageHeader from '../components/PageHeader.vue'
import LoadError from '../components/LoadError.vue'
import StatusTag from '../components/StatusTag.vue'
const router = useRouter(), loading = ref(false), error = ref(''), data = ref<Row>({}), updatedAt = ref('')
const { data: todos, loading: todosLoading, error: todosError, refresh: refreshTodos } = useAdminTodos()
let alive = true
const tasks = computed(() => [
  ...(can('ADMIN', 'OPERATOR') ? [
    { label: '待报价', count: todos.value?.operations?.pendingQuotes, note: '预约未过期的包车订单，待客服报价', path: '/orders?worklist=operations&status=PENDING_QUOTE' },
    { label: '待派单', count: todos.value?.operations?.pendingDispatch, note: '已支付订单等待安排车辆', path: '/orders?worklist=operations&status=PENDING_DISPATCH' },
    { label: '取消审核', count: todos.value?.operations?.cancelRequests, note: '用户提交的订单取消申请', path: '/orders?worklist=operations&status=CANCEL_REQUESTED' },
  ] : []),
  ...(can('ADMIN', 'FINANCE') ? [
    { label: '待退款', count: todos.value?.finance?.pendingRefunds, note: '核对退款事项，办理能力以订单详情为准', path: '/orders?worklist=refunds&status=REFUNDING' },
    { label: '提现审核', count: todos.value?.finance?.pendingWithdrawals, note: '推广佣金提现申请', path: '/withdrawals?worklist=todo&status=PENDING' },
    { label: '待登记付款', count: todos.value?.finance?.approvedWithdrawals, note: '已通过审核，实际转账后登记付款', path: '/withdrawals?worklist=todo&status=APPROVED' },
  ] : []),
])
const followups = computed(() => can('ADMIN', 'OPERATOR') ? [
  { label: '待用户确认报价', count: data.value.pendingConfirmations, note: '报价已提交，等待用户确认', path: '/orders?status=PENDING_CONFIRM' },
  { label: '待用户支付', count: data.value.pendingPayments, note: '等待用户付款，付款后方可派单', path: '/orders?status=PENDING_PAYMENT' },
] : [])
const todosErrorText = computed(() => todosError.value ? `${todos.value ? '待办刷新失败，以下保留上次获取的数量。' : '待办暂不可用，请重新加载。'}${todosError.value}` : '')
async function load() { if (loading.value) return; loading.value = true; error.value = ''; try { const result = await api('/admin/dashboard'); if (!alive) return; data.value = result; updatedAt.value = new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit', timeZone: 'Asia/Shanghai' }).format(new Date()) } catch (err) { if (alive) { error.value = errorText(err); data.value = {}; updatedAt.value = '' } } finally { if (alive) loading.value = false } }
async function refreshPage() { await Promise.all([load(), refreshTodos()]) }
function openOrder(row: Row) { router.push({ path: '/orders', query: { keyword: row.orderNo } }) }
function orderPrice(row: Row) { return row.type === 'CHARTER' && Number(row.amount) <= 0 ? (row.status === 'PENDING_QUOTE' ? '待报价' : '未报价') : money(row.amount) }
onMounted(load)
onBeforeUnmount(() => { alive = false })
</script>
<template>
  <PageHeader title="工作台" subtitle="查看待处理事项与最新预约。"><span v-if="updatedAt" class="muted">预约数据更新于 {{ updatedAt }}</span><el-button :loading="loading || todosLoading" @click="refreshPage"><AppIcon name="Refresh"/>刷新</el-button></PageHeader>
  <LoadError :error="error" @retry="load"/>
  <div v-loading="loading">
    <section class="panel dashboard-summary"><dl><div><dt>今日预约</dt><dd>{{ data.todayOrders ?? '—' }}<small>单</small></dd></div><div><dt>累计用户</dt><dd>{{ data.totalUsers ?? '—' }}<small>位</small></dd></div><div><dt>当前服务中</dt><dd>{{ data.inService ?? '—' }}<small>单</small></dd></div><div><dt>累计净实收</dt><dd>{{ data.totalRevenue == null ? '—' : money(data.totalRevenue) }}</dd></div></dl></section>
    <section class="panel dashboard-tasks" aria-labelledby="staff-tasks-title">
      <div class="panel-heading"><div><h2 id="staff-tasks-title">待处理事项</h2><p>需要工作人员处理，按您的操作权限展示。</p></div></div>
      <LoadError :error="todosErrorText" @retry="refreshTodos"/>
      <div class="task-list"><div v-for="task in tasks" :key="task.label" class="task-row"><div><strong>{{ task.label }}</strong><p>{{ task.note }}</p></div><span class="task-count">{{ task.count ?? '—' }}</span><el-button type="primary" link :aria-label="`查看${task.label}`" @click="router.push(task.path)">查看<AppIcon name="ArrowRight" :size="14"/></el-button></div></div>
    </section>
    <section v-if="followups.length" class="panel dashboard-tasks" aria-labelledby="user-followups-title">
      <div class="panel-heading"><div><h2 id="user-followups-title">等待用户操作</h2><p>用于跟进预约进度，不计入待处理提醒。</p></div></div>
      <div class="task-list"><div v-for="task in followups" :key="task.label" class="task-row"><div><strong>{{ task.label }}</strong><p>{{ task.note }}</p></div><span class="task-count">{{ task.count ?? '—' }}</span><el-button type="primary" link :aria-label="`查看${task.label}`" @click="router.push(task.path)">查看<AppIcon name="ArrowRight" :size="14"/></el-button></div></div>
    </section>
    <section class="panel"><div class="panel-heading"><h2>最近预约</h2><el-button type="primary" link @click="router.push('/orders')">全部订单<AppIcon name="ArrowRight" :size="14"/></el-button></div><el-table :data="data.recentOrders || []" empty-text="暂无预约订单"><el-table-column label="订单编号" min-width="230"><template #default="{ row }"><button class="table-link" @click="openOrder(row)">{{ row.orderNo }}</button><div class="cell-sub">{{ row.type === 'CHARTER' ? '预约包车' : '预约用车' }}</div></template></el-table-column><el-table-column label="行程" min-width="240"><template #default="{ row }"><div class="route-cell"><span>{{ row.originName || '—' }}</span><small>至 {{ row.destinationName || '—' }}</small></div></template></el-table-column><el-table-column label="预约时间" min-width="180"><template #default="{ row }">{{ dateTime(row.appointmentAt) }}</template></el-table-column><el-table-column label="订单金额" width="128" align="right"><template #default="{ row }">{{ orderPrice(row) }}</template></el-table-column><el-table-column label="状态" width="128"><template #default="{ row }"><StatusTag :value="row.status"/></template></el-table-column><el-table-column label="操作" width="100" fixed="right"><template #default="{ row }"><el-button link type="primary" @click="openOrder(row)">查看订单</el-button></template></el-table-column></el-table></section>
  </div>
</template>
