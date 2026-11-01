<script setup lang="ts">
import { computed, ref, reactive, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, post, query, money, dateTime, errorText, orderStatuses, statusNames, can, session, type Row, type Page } from '../lib/api'
import PageHeader from '../components/PageHeader.vue'
import LoadError from '../components/LoadError.vue'
import StatusTag from '../components/StatusTag.vue'
import OrderActionBar, { type OrderAction } from '../components/OrderActionBar.vue'

const route = useRoute(), router = useRouter()
const worklist = computed(() => ['operations', 'refunds'].includes(String(route.query.worklist || '')) ? String(route.query.worklist) : '')
const availableStatuses = computed(() => worklist.value === 'operations' ? ['PENDING_QUOTE', 'PENDING_DISPATCH', 'CANCEL_REQUESTED'] : worklist.value === 'refunds' ? ['REFUNDING'] : orderStatuses)
const pageTitle = computed(() => worklist.value === 'operations' ? '运营待办' : worklist.value === 'refunds' ? '待退款订单' : '订单与派单')
const pageSubtitle = computed(() => worklist.value === 'operations' ? '处理待报价、待派单和取消申请；待报价仅包含预约时间未过期的订单。' : worklist.value === 'refunds' ? '核对已进入待退款的订单，由财务继续执行退款。' : '查询预约订单，处理报价、支付后的派单、取消审核与退款。')
function positiveInteger(value: unknown, fallback: number) { const number = Number(value); return Number.isSafeInteger(number) && number > 0 ? number : fallback }
function routeFilters() {
  const status = String(route.query.status || ''), type = String(route.query.type || ''), size = Number(route.query.size || 10)
  return { status: availableStatuses.value.includes(status) ? status : '', type: ['RIDE', 'CHARTER'].includes(type) ? type : '', keyword: String(route.query.keyword || ''), page: positiveInteger(route.query.page, 1), size: [10, 20, 50].includes(size) ? size : 10 }
}
const filters = reactive(routeFilters())
const rows = ref<Row[]>([]), total = ref(0), loading = ref(false), error = ref('')
const detail = ref<Row | null>(null), detailTarget = ref<Row | null>(null), drawer = ref(false), detailLoading = ref(false), detailError = ref('')
const actionContext = ref<{ kind: string; id: number; order: Row } | null>(null), submitting = ref(false), actionError = ref('')
const form = reactive({ amount: undefined as number | undefined, remark: '', driverName: '', driverPhone: '', plateNumber: '', approved: true, reason: '' })
const fieldErrors = reactive<Record<string, string>>({}), closingAction = ref(false)
let listVersion = 0, detailVersion = 0, actionBaseline = '', correctingPage = false
const refundNotice = computed(() => {
  if (worklist.value !== 'refunds') return ''
  if (rows.value.some(row => row.actions?.refund === false && row.paymentUnavailableReason)) return '线上退款能力尚未接入，暂不能在线退款。待退款订单会保留在此列表，请财务核对处理安排，不要重复收款。'
  return rows.value.some(row => row.actions?.refund === true) ? '当前可执行的是开发模拟退款，只更新订单和模拟流水，不会向微信发起退款，也不会退回真实资金。' : ''
})
function snapshot(row: Row): Row { return JSON.parse(JSON.stringify(row)) }
function hasAction(row: Row, key: string, fallback: boolean) { return typeof row.actions?.[key] === 'boolean' ? row.actions[key] : fallback }
function actionsFor(row: Row): OrderAction[] {
  const status = row.status, result: OrderAction[] = []
  const add = (kind: string, label: string, allowed: boolean, type: OrderAction['type'] = 'primary') => { if (allowed) result.push({ kind, label, type }) }
  if (can('ADMIN', 'OPERATOR')) {
    add('quote', status === 'PENDING_CONFIRM' ? '修改报价' : '提交报价', hasAction(row, 'quote', row.type === 'CHARTER' && ['PENDING_QUOTE', 'PENDING_CONFIRM'].includes(status)))
    add('dispatch', status === 'DISPATCHED' ? '改派' : '派单', hasAction(row, 'dispatch', ['PENDING_DISPATCH', 'DISPATCHED'].includes(status)))
    add('start', '开始服务', hasAction(row, 'start', status === 'DISPATCHED'))
    add('complete', '完成服务', hasAction(row, 'complete', status === 'IN_SERVICE'))
    add('terminate', '中止服务', hasAction(row, 'terminate', status === 'IN_SERVICE' && Number(row.paidAmount) > 0), 'danger')
    add('cancel-review', '取消审核', hasAction(row, 'cancelReview', status === 'CANCEL_REQUESTED'), 'warning')
    add('cancel', '取消订单', hasAction(row, 'adminCancel', ['PENDING_QUOTE', 'PENDING_CONFIRM', 'PENDING_PAYMENT', 'PENDING_DISPATCH', 'DISPATCHED'].includes(status)), 'danger')
  }
  if (can('ADMIN', 'FINANCE')) add('refund', '全额退款', hasAction(row, 'refund', Number(row.paidAmount) > 0 && ['REFUNDING', 'COMPLETED'].includes(status)), 'danger')
  return result
}
const nextSteps: Record<string, string> = {
  PENDING_QUOTE: '待客服提交包车报价', PENDING_CONFIRM: '待用户确认报价；客服可修改报价', PENDING_PAYMENT: '待用户完成支付，支付后才能派单',
  PENDING_DISPATCH: '待客服安排车辆和司机', DISPATCHED: '待司机接到乘车人，由客服开始服务', IN_SERVICE: '服务进行中，结束后由客服确认完成',
  COMPLETED: '服务已完成', CANCEL_REQUESTED: '待客服审核取消申请；通过后由财务执行退款', CANCELLED: '订单已关闭',
  REFUNDING: '待财务执行全额退款，尚未向微信提交退款', REFUNDED: '退款流程已完成，请核对退款流水及交易模式',
}
function nextStep(row: Row) { return expirationHint(row) || (row.status === 'REFUNDING' && row.actions?.refund === false && row.paymentUnavailableReason ? '线上退款能力尚未接入，暂不能在线退款；待财务核对处理安排。' : '') || nextSteps[row.status] || (typeof row.nextStep === 'string' && row.nextStep ? row.nextStep : '请核对订单状态') }
function expirationHint(row: Row) {
  if (!row.appointmentExpired) return ''
  if (['PENDING_QUOTE', 'PENDING_CONFIRM', 'PENDING_PAYMENT'].includes(row.status)) return '预约时间已过，请取消此单并按新的预约时间重新下单。'
  return ['PENDING_DISPATCH', 'DISPATCHED'].includes(row.status) ? '预约时间已过，请客服核实车辆接送和实际服务进度。' : ''
}
function orderPrice(row: Row) { return row.type === 'CHARTER' && Number(row.amount) <= 0 ? (row.status === 'PENDING_QUOTE' ? '待报价' : '未报价') : money(row.amount) }
function finance(row: Row) {
  const records: Row[] = Array.isArray(row.payments) ? row.payments : []
  const refunds: Row[] = Array.isArray(row.refunds) ? row.refunds : []
  const successful = (items: Row[]) => items.filter(item => item.status === 'SUCCESS').reduce((sum, item) => sum + Number(item.amount || 0), 0)
  const refundRecords = records.length ? records.filter(item => item.type === 'REFUND') : refunds
  const refunded = row.finance?.refunded ?? (refundRecords.length ? successful(refundRecords) : row.status === 'REFUNDED' ? null : 0)
  const netPaid = Number(row.finance?.netPaid ?? row.paidAmount ?? 0)
  const totalPaid = row.finance?.totalPaid ?? (records.length ? successful(records.filter(item => item.type === 'PAYMENT')) : refunded === null ? null : netPaid + Number(refunded))
  return { totalPaid, refunded, netPaid }
}
function amountText(value: unknown) { return value === null || value === undefined ? '—' : money(value) }
function transactionMode(row: Row) { return row.mode === 'DEV' ? '模拟交易 DEV' : row.mode === 'WECHAT' || row.mode === 'PRODUCTION' ? '微信真实交易' : '交易模式待确认' }
async function load() {
  const version = ++listVersion
  loading.value = true; error.value = ''
  try {
    const result = await api<Page>(`/admin/orders?${query({ ...filters, worklist: worklist.value })}`)
    if (version === listVersion) {
      rows.value = result.items; total.value = result.total
      const lastPage = Math.max(1, Math.ceil(result.total / filters.size))
      if (filters.page > lastPage) {
        filters.page = lastPage; correctingPage = true
        try { await syncFilters() } finally { correctingPage = false }
      }
    }
  }
  catch (err) { if (version === listVersion) error.value = errorText(err) }
  finally { if (version === listVersion) loading.value = false }
}
async function syncFilters() {
  const params: Record<string, string> = {}
  if (worklist.value) params.worklist = worklist.value
  if (filters.status) params.status = filters.status
  if (filters.type) params.type = filters.type
  if (filters.keyword) params.keyword = filters.keyword
  if (filters.page > 1) params.page = String(filters.page)
  if (filters.size !== 10) params.size = String(filters.size)
  if (JSON.stringify(routeFilters()) === JSON.stringify(filters)) await load()
  else await router.replace({ path: route.path, query: params })
}
function search() { filters.keyword = filters.keyword.trim(); filters.page = 1; rows.value = []; total.value = 0; syncFilters() }
function changePage(page: number) { if (loading.value || submitting.value || page === filters.page) return; filters.page = page; void syncFilters() }
function resizePage(size: number) { if (loading.value || submitting.value) return; filters.size = size; filters.page = 1; void syncFilters() }
function reset() {
  Object.assign(filters, { status: '', type: '', keyword: '', page: 1 }); rows.value = []; total.value = 0
  syncFilters()
}
function showAllOrders() { void router.push({ path: '/orders', query: { ...(filters.keyword ? { keyword: filters.keyword } : {}), ...(filters.type ? { type: filters.type } : {}) } }) }
async function showDetail(row: Row, allowDuringSubmit = false) {
  if (submitting.value && !allowDuringSubmit) return
  const target = snapshot(row), version = ++detailVersion
  drawer.value = true; detailTarget.value = target; detail.value = null; detailLoading.value = true; detailError.value = ''
  try { const result = await api(`/admin/orders/${target.id}`); if (version === detailVersion && drawer.value) detail.value = result }
  catch (err) { if (version === detailVersion && drawer.value) detailError.value = errorText(err) }
  finally { if (version === detailVersion) detailLoading.value = false }
}
function closeDetail() { detailVersion++; detailLoading.value = false }
function closeDrawer(done: () => void) { if (!submitting.value) done() }
function actionTitle() {
  const kind = actionContext.value?.kind
  if (kind === 'quote') return actionContext.value?.order.status === 'PENDING_CONFIRM' ? '修改报价' : '提交包车报价'
  return ({ dispatch: '安排车辆与司机', 'cancel-review': '审核取消申请', refund: '发起全额模拟退款', cancel: '客服取消订单', terminate: '中止服务并申请全额退款' } as Record<string, string>)[kind || ''] || ''
}
function openAction(kind: string, row: Row) {
  if (submitting.value || closingAction.value || !actionsFor(row).some(item => item.kind === kind)) return
  if (kind === 'start' || kind === 'complete') { void transition(snapshot(row), kind); return }
  const order = snapshot(row)
  actionContext.value = { kind, id: Number(order.id), order }
  Object.assign(form, { amount: Number(order.amount) > 0 ? Number(order.amount) : undefined, remark: order.quoteRemark || '', driverName: order.driverName || '', driverPhone: order.driverPhone || '', plateNumber: order.plateNumber || '', approved: true, reason: '' })
  actionBaseline = JSON.stringify(form); Object.keys(fieldErrors).forEach(key => delete fieldErrors[key]); actionError.value = ''
}
async function refreshAfterAction(id: number) {
  await load()
  if (drawer.value && Number(detailTarget.value?.id) === id) await showDetail({ id }, true)
}
async function closeAction(done?: () => void) {
  if (!actionContext.value) { if (done) done(); return }
  if (submitting.value || closingAction.value) return
  closingAction.value = true
  try { if (actionBaseline !== JSON.stringify(form)) await ElMessageBox.confirm('当前操作尚未提交，确认放弃已填写内容？', '放弃操作', { confirmButtonText: '放弃操作', cancelButtonText: '继续填写', type: 'warning' }); actionContext.value = null; actionError.value = ''; if (done) done() }
  catch { /* Keep the draft open. */ }
  finally { closingAction.value = false }
}
async function submit() {
  if (submitting.value || closingAction.value || !actionContext.value) return
  const { kind, id, order } = actionContext.value
  actionError.value = ''; Object.keys(fieldErrors).forEach(key => delete fieldErrors[key])
  let body: Row = {}
  if (kind === 'quote') {
    if (typeof form.amount !== 'number' || !Number.isFinite(form.amount) || form.amount <= 0 || form.amount > 1000000) fieldErrors.amount = '请输入大于 0、不超过 1000000 元的报价'
    body = { amount: form.amount, remark: form.remark, quoteVersion: order.quoteVersion }
  }
  if (kind === 'dispatch') {
    if (!form.driverName.trim()) fieldErrors.driverName = '请填写司机姓名'
    if (!/^1[3-9]\d{9}$/.test(form.driverPhone.trim())) fieldErrors.driverPhone = '请填写有效的 11 位手机号'
    if (!form.plateNumber.trim()) fieldErrors.plateNumber = '请填写车牌号'
    body = { driverName: form.driverName.trim(), driverPhone: form.driverPhone.trim(), plateNumber: form.plateNumber.trim().toUpperCase() }
  }
  if (kind === 'cancel-review') {
    if (!form.approved && !form.reason.trim()) fieldErrors.reason = '驳回申请时必须填写审核说明'
    body = { approved: form.approved, reason: form.reason.trim() }
  }
  if (['refund', 'cancel', 'terminate'].includes(kind)) {
    if (!form.reason.trim()) fieldErrors.reason = '请填写操作原因'
    body = { reason: form.reason.trim() }
  }
  if (Object.keys(fieldErrors).length) return
  submitting.value = true
  try {
    await post(`/admin/orders/${id}/${kind}`, body)
    ElMessage.success(kind === 'refund' ? '全额模拟退款完成，未发生真实资金退回' : kind === 'quote' ? '报价已提交，等待用户确认' : '操作已完成')
    actionBaseline = JSON.stringify(form); actionContext.value = null
    await refreshAfterAction(id)
  } catch (err) { actionError.value = errorText(err) }
  finally { submitting.value = false }
}
async function transition(row: Row, kind: 'start' | 'complete') {
  if (submitting.value) return
  const id = Number(row.id)
  submitting.value = true
  try {
    await ElMessageBox.confirm(kind === 'start' ? '确认车辆已接到乘车人，开始服务？' : '确认本次行程已经完成？推广佣金将在完成 30 天后按规则结算，期间退款将取消待结算佣金。', kind === 'start' ? '开始服务' : '完成服务', { confirmButtonText: '确认', cancelButtonText: '返回', type: 'warning', closeOnClickModal: false })
    await post(`/admin/orders/${id}/${kind}`)
    ElMessage.success('订单状态已更新')
    await refreshAfterAction(id)
  } catch (err) { if (err !== 'cancel' && err !== 'close') ElMessage.error(errorText(err)) }
  finally { submitting.value = false }
}
async function allowNavigation() {
  if (!session.token) return true
  if (submitting.value) { ElMessage.warning('订单正在处理，请稍候'); return false }
  if (!actionContext.value || actionBaseline === JSON.stringify(form)) return true
  try { await ElMessageBox.confirm('当前操作尚未提交，确认放弃已填写内容？', '放弃操作', { confirmButtonText: '放弃操作', cancelButtonText: '继续填写', type: 'warning', closeOnClickModal: false, closeOnHashChange: false }); return true }
  catch { return false }
}
onBeforeRouteLeave(allowNavigation)
onBeforeRouteUpdate(async (to, from) => {
  if (to.fullPath === from.fullPath) return true
  if (correctingPage) return true
  if (!await allowNavigation()) return false
  actionContext.value = null; actionError.value = ''
  return true
})
watch(() => [route.query.worklist, route.query.status, route.query.type, route.query.keyword, route.query.page, route.query.size], () => {
  if ((worklist.value === 'operations' && !can('ADMIN', 'OPERATOR')) || (worklist.value === 'refunds' && !can('ADMIN', 'FINANCE'))) {
    const params = { ...route.query }; delete params.worklist
    ElMessage.warning('当前账号无权处理此类待办，已切换至全部订单')
    void router.replace({ path: '/orders', query: params })
    return
  }
  if (!submitting.value) {
    detailVersion++; drawer.value = false; detail.value = null; detailTarget.value = null; detailLoading.value = false; detailError.value = ''
    actionContext.value = null; actionError.value = ''
  }
  Object.assign(filters, routeFilters()); rows.value = []; total.value = 0; void load()
}, { immediate: true })
</script>

<template>
  <PageHeader :title="pageTitle" :subtitle="pageSubtitle"><el-button v-if="worklist" :disabled="submitting" @click="showAllOrders">查看全部订单</el-button><el-button :loading="loading" @click="load"><AppIcon name="Refresh"/>刷新</el-button></PageHeader>
  <LoadError :error="error" @retry="load"/>
  <el-alert v-if="refundNotice" class="section-alert" :title="refundNotice" type="warning" :closable="false" show-icon/>
  <div class="panel">
    <div class="filter-bar">
      <el-input v-model="filters.keyword" placeholder="订单号 / 乘车人 / 手机号" clearable class="search-input" @keyup.enter="search"/>
      <el-select v-model="filters.type" placeholder="全部订单类型" clearable><el-option label="预约用车" value="RIDE"/><el-option label="预约包车" value="CHARTER"/></el-select>
      <el-select v-model="filters.status" :placeholder="worklist ? '全部待办状态' : '全部订单状态'" clearable><el-option v-for="s in availableStatuses" :key="s" :label="statusNames[s]" :value="s"/></el-select>
      <el-button type="primary" @click="search"><AppIcon name="Search"/>查询</el-button><el-button @click="reset">重置</el-button>
    </div>
    <el-table v-loading="loading" :data="rows" :empty-text="error ? '订单加载失败，请重试' : worklist === 'operations' ? '暂无符合条件的运营待办' : worklist === 'refunds' ? '暂无待退款订单' : '暂无符合条件的订单'" row-key="id">
      <el-table-column label="订单信息" min-width="230"><template #default="{ row }"><button class="table-link" :disabled="submitting" @click="showDetail(row)">{{ row.orderNo }}</button><div class="cell-sub">{{ row.type === 'CHARTER' ? '预约包车' : '预约用车' }} · {{ row.vehicleName || '—' }}</div></template></el-table-column>
      <el-table-column label="出行线路" min-width="215"><template #default="{ row }"><div class="route-cell"><span>{{ row.originName }}</span><small>至 {{ row.destinationName }}</small></div></template></el-table-column>
      <el-table-column label="乘车人 / 预约" min-width="170"><template #default="{ row }">{{ row.passengerName }} · {{ row.passengerPhone }}<div class="cell-sub">{{ dateTime(row.appointmentAt) }}</div><el-tag v-if="expirationHint(row)" type="warning" size="small" effect="plain" disable-transitions>预约时间已过</el-tag></template></el-table-column>
      <el-table-column label="订单金额 / 收款" min-width="158"><template #default="{ row }"><strong>{{ orderPrice(row) }}</strong><div class="cell-sub">历史支付 {{ amountText(finance(row).totalPaid) }}</div><div v-if="Number(finance(row).refunded) > 0 || row.status === 'REFUNDED'" class="cell-sub">已退 {{ amountText(finance(row).refunded) }} · 实收 {{ money(finance(row).netPaid) }}</div></template></el-table-column>
      <el-table-column label="状态 / 下一步" min-width="225"><template #default="{ row }"><StatusTag :value="row.status"/><div class="cell-sub">{{ nextStep(row) }}</div></template></el-table-column>
      <el-table-column label="操作" fixed="right" width="230"><template #default="{ row }"><div class="order-actions"><el-button link type="primary" :disabled="submitting" @click="showDetail(row)">详情</el-button><OrderActionBar :actions="actionsFor(row)" :busy="submitting" compact @select="openAction($event, row)"/></div></template></el-table-column>
    </el-table>
    <div class="pagination"><span>共 {{ total }} 条订单</span><el-pagination :current-page="filters.page" :page-size="filters.size" :disabled="loading || submitting" :page-sizes="[10, 20, 50]" :total="total" layout="sizes, prev, pager, next" @current-change="changePage" @size-change="resizePage"/></div>
  </div>

  <el-drawer v-model="drawer" title="订单详情" size="680px" :close-on-click-modal="!submitting" :close-on-press-escape="!submitting" :show-close="!submitting" :before-close="closeDrawer" @close="closeDetail">
    <div v-loading="detailLoading" class="detail-body">
      <LoadError :error="detailError" @retry="detailTarget && showDetail(detailTarget)"/>
      <template v-if="detail">
        <div class="order-detail-top"><div><span class="section-label">{{ detail.type === 'CHARTER' ? '预约包车' : '预约用车' }}</span><h3>{{ detail.orderNo }}</h3></div><StatusTag :value="detail.status"/></div>
        <el-alert v-if="!expirationHint(detail)" :title="nextStep(detail)" type="info" :closable="false" show-icon/>
        <el-alert v-if="expirationHint(detail)" class="section-alert" :title="expirationHint(detail)" type="warning" :closable="false" show-icon/>
        <el-alert v-if="detail.paymentUnavailableReason && ['PENDING_PAYMENT', 'REFUNDING'].includes(detail.status)" class="section-alert" :title="detail.paymentUnavailableReason" type="warning" :closable="false" show-icon/>
        <div class="detail-actions"><OrderActionBar :actions="actionsFor(detail)" :busy="submitting" @select="openAction($event, detail!)"/></div>
        <div class="detail-route"><div><span class="route-point-label">起点</span><span>{{ detail.originName }}</span></div><div><span class="route-point-label">终点</span><span>{{ detail.destinationName }}</span></div></div>
        <el-descriptions :column="1" border>
          <el-descriptions-item label="预约时间">{{ dateTime(detail.appointmentAt) }}</el-descriptions-item>
          <el-descriptions-item label="乘车人">{{ detail.passengerName }} / {{ detail.passengerPhone }}</el-descriptions-item>
          <el-descriptions-item label="所选车型">{{ detail.vehicleName }}</el-descriptions-item>
          <el-descriptions-item label="里程 / 时长">{{ detail.distanceKm || 0 }} 公里 / {{ detail.hours || '—' }} 小时</el-descriptions-item>
          <el-descriptions-item label="订单金额"><strong class="amount">{{ orderPrice(detail) }}</strong></el-descriptions-item>
          <el-descriptions-item label="历史支付">{{ amountText(finance(detail).totalPaid) }}</el-descriptions-item>
          <el-descriptions-item label="已退款">{{ amountText(finance(detail).refunded) }}</el-descriptions-item>
          <el-descriptions-item label="当前实收">{{ money(finance(detail).netPaid) }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.quoteRemark" label="报价说明">{{ detail.quoteRemark }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.quoteVersion" label="报价版本">第 {{ detail.quoteVersion }} 版</el-descriptions-item>
          <el-descriptions-item label="行程说明">{{ detail.itinerary || '—' }}</el-descriptions-item>
          <el-descriptions-item label="服务备注">{{ detail.remark || '—' }}</el-descriptions-item>
          <el-descriptions-item label="司机信息">{{ detail.driverName ? `${detail.driverName} / ${detail.driverPhone} / ${detail.plateNumber}` : '尚未派单' }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.cancelRules?.cutoffAt" label="用户取消时限">{{ dateTime(detail.cancelRules.cutoffAt) }} 前可申请；客服可按实际情况协助处理</el-descriptions-item>
          <el-descriptions-item v-if="detail.cancelRules?.reason" label="用户取消规则">{{ detail.cancelRules.reason }}</el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ dateTime(detail.createdAt) }}</el-descriptions-item>
        </el-descriptions>
        <template v-if="detail.logs?.length"><h3 class="section-title">订单动态</h3><el-timeline><el-timeline-item v-for="(log, i) in detail.logs" :key="log.id || i" :timestamp="dateTime(log.createdAt)">{{ log.content || log.remark || log.action }}</el-timeline-item></el-timeline></template>
        <template v-if="detail.payments?.length"><h3 class="section-title">支付与退款流水</h3><div v-for="payment in detail.payments" :key="payment.id" class="record-line"><strong>{{ payment.type === 'REFUND' ? '退款' : payment.type === 'PAYMENT' ? '支付' : '其他交易' }} {{ money(payment.amount) }}</strong> · {{ statusNames[payment.status] || payment.status }}<p>{{ transactionMode(payment) }} · {{ payment.transactionNo || '—' }}</p><p class="muted">{{ payment.reason || '—' }} · {{ dateTime(payment.createdAt) }}</p></div></template>
        <template v-else-if="detail.refunds?.length"><h3 class="section-title">退款记录</h3><div v-for="refund in detail.refunds" :key="refund.id" class="record-line">{{ money(refund.amount) }} · {{ statusNames[refund.status] || refund.status }} · {{ transactionMode(refund) }}<p class="muted">{{ refund.reason }} · {{ dateTime(refund.createdAt) }}</p></div></template>
      </template>
    </div>
  </el-drawer>

  <el-dialog :model-value="!!actionContext" :title="actionTitle()" width="520px" :close-on-click-modal="false" :close-on-press-escape="!submitting" :show-close="!submitting" :before-close="closeAction">
    <template v-if="actionContext">
      <el-alert v-if="actionError" :title="actionError" type="error" :closable="false" show-icon/>
      <p class="muted">订单 {{ actionContext.order.orderNo }}</p>
      <el-form label-position="top" :disabled="submitting" @submit.prevent="submit">
        <template v-if="actionContext.kind === 'quote'">
          <el-form-item label="包车报价（元）" required :error="fieldErrors.amount"><el-input-number v-model="form.amount" :min="0.01" :max="1000000" :precision="2" :step="10" controls-position="right" @change="delete fieldErrors.amount"/></el-form-item>
          <el-form-item label="报价说明"><el-input v-model="form.remark" type="textarea" :maxlength="1000" show-word-limit placeholder="费用包含范围、行程约定等"/></el-form-item>
          <el-alert title="客服提交报价后，由用户确认报价并完成支付，再安排派单。修改报价后仍需用户确认。" type="info" :closable="false"/>
        </template>
        <template v-if="actionContext.kind === 'dispatch'">
          <h3 class="action-section">车辆与司机信息</h3>
          <el-form-item label="司机姓名" required :error="fieldErrors.driverName"><el-input v-model="form.driverName" maxlength="60" @input="delete fieldErrors.driverName"/></el-form-item>
          <el-form-item label="司机手机" required :error="fieldErrors.driverPhone"><el-input v-model="form.driverPhone" maxlength="11" inputmode="tel" @input="delete fieldErrors.driverPhone"/></el-form-item>
          <el-form-item label="车牌号" required :error="fieldErrors.plateNumber"><el-input v-model="form.plateNumber" maxlength="20" placeholder="请输入实际车牌号" @input="delete fieldErrors.plateNumber"/></el-form-item>
        </template>
        <template v-if="actionContext.kind === 'cancel-review'">
          <el-form-item label="审核结果"><el-radio-group v-model="form.approved"><el-radio :value="true">同意取消</el-radio><el-radio :value="false">驳回申请</el-radio></el-radio-group></el-form-item>
          <el-form-item :label="form.approved ? '审核说明（选填）' : '驳回说明'" :required="!form.approved" :error="fieldErrors.reason"><el-input v-model="form.reason" type="textarea" maxlength="500" show-word-limit @input="delete fieldErrors.reason"/></el-form-item>
          <el-alert :title="form.approved ? '通过后进入待退款，由财务另外执行全额退款；此审核不会直接退款。' : '驳回后恢复申请取消前的状态，请说明原因以便用户了解。'" type="warning" :closable="false"/>
        </template>
        <template v-if="actionContext.kind === 'refund'">
          <el-alert title="当前执行全额模拟退款，只生成开发流水并更新状态，不会向微信发起退款，也不会发生真实资金退回。" type="warning" :closable="false" show-icon/>
          <p>本次全额模拟退款：<strong class="amount">{{ money(finance(actionContext.order).netPaid) }}</strong></p>
          <p v-if="actionContext.order.status === 'COMPLETED'" class="muted">完成后 30 天内的待结算佣金会取消；已结算佣金需冲回，若已提现或冻结需先处理财务追偿。</p>
          <el-form-item label="退款原因" required :error="fieldErrors.reason"><el-input v-model="form.reason" type="textarea" maxlength="500" show-word-limit @input="delete fieldErrors.reason"/></el-form-item>
        </template>
        <template v-if="actionContext.kind === 'cancel'">
          <el-alert :title="finance(actionContext.order).netPaid > 0 ? '已支付订单取消后进入待退款，须由财务另行执行退款；不会直接退回资金。' : '未支付订单取消后关闭，无法恢复。'" type="warning" :closable="false"/>
          <el-form-item label="取消原因" required :error="fieldErrors.reason"><el-input v-model="form.reason" type="textarea" maxlength="500" show-word-limit @input="delete fieldErrors.reason"/></el-form-item>
        </template>
        <template v-if="actionContext.kind === 'terminate'">
          <el-alert title="中止后不可恢复，订单将申请全额退款并进入待退款，由财务继续执行；此操作不会直接退款，也不会结算推广佣金。" type="warning" :closable="false" show-icon/>
          <p>待财务处理的全额退款：<strong class="amount">{{ money(finance(actionContext.order).netPaid) }}</strong></p>
          <el-form-item label="中止服务原因" required :error="fieldErrors.reason"><el-input v-model="form.reason" type="textarea" maxlength="500" show-word-limit placeholder="请说明服务中止的实际原因，记录将留存在订单动态中" @input="delete fieldErrors.reason"/></el-form-item>
        </template>
      </el-form>
    </template>
    <template #footer><el-button :disabled="submitting || closingAction" @click="closeAction()">返回</el-button><el-button :type="['refund', 'cancel', 'terminate'].includes(actionContext?.kind || '') ? 'danger' : 'primary'" :loading="submitting" :disabled="closingAction" @click="submit">{{ actionContext?.kind === 'refund' ? '确认全额模拟退款' : actionContext?.kind === 'terminate' ? '确认中止服务' : '确认提交' }}</el-button></template>
  </el-dialog>
</template>

<style scoped>
.order-actions { display: flex; gap: 8px; align-items: flex-start; flex-wrap: wrap; }
.detail-actions { margin: 16px 0; }
.record-line p { overflow-wrap: anywhere; }
.action-section{font-size:15px;font-weight:600;margin:0 0 16px}.detail-body :deep(.el-descriptions__content){overflow-wrap:anywhere;white-space:pre-wrap}.route-cell span,.route-cell small{display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;overflow-wrap:anywhere}.cell-sub{overflow-wrap:anywhere}.order-detail-top h3{overflow-wrap:anywhere}
</style>
