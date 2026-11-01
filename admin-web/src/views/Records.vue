<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, can, dateTime, errorText, money, query, statusNames, type Page, type Row } from '../lib/api'
import PageHeader from '../components/PageHeader.vue'
import LoadError from '../components/LoadError.vue'
import StatusTag from '../components/StatusTag.vue'
const route = useRoute(), router = useRouter()
const resource = computed(() => route.path.slice(1))
const descriptions: Record<string, { title: string; subtitle: string; tip: string }> = {
  payments: { title: '支付与退款', subtitle: '按订单核对支付、全额退款与交易流水。', tip: 'DEV 为开发模拟交易，未发生真实扣款或退款。订单待退款时，请进入关联订单核对并处理。' },
  commissions: { title: '推广与佣金', subtitle: '核对推广归属、30 日待结算佣金与结算结果。', tip: '订单完成 30 日后，系统核验订单仍已完成且未退款，再转入可提现余额。等待期间退款将撤销待结算佣金。' },
  logs: { title: '操作日志', subtitle: '按时间查看后台操作人员、处理对象与业务记录。', tip: '日志由服务器记录，不可在此修改或删除；关联订单可直接进入查询。' },
}
const config = computed(() => descriptions[resource.value] || descriptions.logs!)
const filters = reactive({ keyword: '', page: 1, size: 10 }), keyword = ref(''), queryError = ref('')
const rows = ref<Row[]>([]), total = ref(0), loading = ref(false), error = ref(''), detail = ref<Row | null>(null)
let requestVersion = 0, loadedResource = ''
async function load() {
  const version = ++requestVersion, requestedResource = resource.value, params = { page: filters.page, size: filters.size, ...(requestedResource === 'payments' ? { keyword: filters.keyword } : {}) }
  loading.value = true; error.value = ''; rows.value = []; total.value = 0; detail.value = null
  try {
    const data = await api<Page>('/admin/' + requestedResource + '?' + query(params))
    if (version !== requestVersion || requestedResource !== resource.value) return
    const last = Math.max(1, Math.ceil(data.total / params.size))
    if (params.page > last) { filters.page = last; await load(); return }
    rows.value = data.items; total.value = data.total
  } catch (err) { if (version === requestVersion && requestedResource === resource.value) error.value = errorText(err) }
  finally { if (version === requestVersion) loading.value = false }
}
function search() {
  const term = keyword.value.trim(); queryError.value = ''
  if (term.length > 80) { queryError.value = '查询订单号不能超过 80 个字符'; return }
  filters.keyword = term; keyword.value = term; filters.page = 1
  void router.replace({ path: route.path, query: term ? { keyword: term } : {} }); void load()
}
function reset() { keyword.value = ''; search() }
function changePage(page: number) { if (loading.value || page === filters.page) return; filters.page = page; void load() }
function changeSize(size: number) { if (loading.value) return; filters.size = size; filters.page = 1; void load() }
function orderNumber(row: Row) { return row.orderNo ? String(row.orderNo) : /^订单\s+(WA[0-9A-Z]+)$/.exec(String(row.target || ''))?.[1] || '' }
function openOrder(row: Row) { const number = orderNumber(row); if (!number || !can('ADMIN','OPERATOR','FINANCE')) return; detail.value = null; void router.push({ path: '/orders', query: { keyword: number } }) }
function showDetail(row: Row) { detail.value = { ...row } }
const logNames: Record<string, string> = { ADMIN_LOGIN: '管理员登录', USER_STATUS: '调整用户状态', VEHICLE_CREATE: '新增车型', VEHICLE_UPDATE: '修改车型', CONTENT_CREATE: '新增内容', CONTENT_UPDATE: '修改内容', CONTENT_DELETE: '删除内容', SETTINGS_UPDATE: '修改平台配置', ADMIN_CREATE: '新增管理员', ADMIN_UPDATE: '修改管理员', CREATE: '创建预约订单', QUOTE: '提交包车报价', ACCEPT_QUOTE: '确认包车报价', DISPATCH: '人工派单或改派', START: '开始服务', COMPLETE: '完成服务', DEV_PAYMENT: '开发模拟支付', DEV_REFUND: '开发模拟退款', CANCEL: '取消未支付订单', CANCEL_REQUEST: '申请取消订单', CANCEL_APPROVE: '通过取消申请', CANCEL_REJECT: '驳回取消申请', ADMIN_CANCEL: '客服取消订单', TERMINATE: '中止服务并申请全额退款', WITHDRAWAL_CANCEL: '撤销提现审核并解冻', WITHDRAWAL_REVIEW: '提现审核', WITHDRAWAL_PAID: '提现付款登记', WITHDRAW_REVIEW: '提现审核', WITHDRAW_PAID: '提现付款登记', WITHDRAW_APPROVE: '通过提现申请', WITHDRAW_REJECT: '驳回提现申请' }
const fieldLabels: Record<string, string> = { id: '记录编号', orderId: '订单 ID', orderNo: '订单编号', type: '交易类型', amount: '金额', mode: '交易环境', status: '状态', transactionNo: '支付或退款流水号', reason: '交易备注', createdAt: '记录时间', eligibleAt: '预计结算时间', settledAt: '实际结算时间', userId: '推广用户 ID', referredUserId: '被邀请用户 ID', actorId: '操作人 ID', actorName: '操作人员', action: '操作类型', target: '操作内容' }
const detailFields: Record<string, string[]> = { payments: ['id','orderNo','orderId','type','amount','status','mode','transactionNo','reason','createdAt'], commissions: ['id','userId','referredUserId','orderNo','orderId','amount','status','eligibleAt','settledAt','createdAt'], logs: ['id','actorName','actorId','action','target','createdAt'] }
const settingLabels: Record<string, string> = { platformName: '平台名称', servicePhone: '客服电话', notice: '首页公告', serviceCity: '运营地区', serviceArea: '服务范围', advanceMinutes: '提前预约时长', maxAdvanceDays: '可预约天数', commissionRate: '佣金比例', withdrawalMinimum: '最低提现金额', cancelHours: '取消时限' }
function targetText(row: Row) { return row.action === 'SETTINGS_UPDATE' ? String(row.target || '').split(',').map(key => settingLabels[key] || key).join('、') : row.target || '—' }
function transactionMode(row: Row) { return row.mode === 'DEV' ? '模拟交易 DEV' : row.mode === 'WECHAT' ? '微信支付' : row.mode === 'PRODUCTION' ? '正式交易' : row.mode || '未记录' }
function transactionType(row: Row) { return row.type === 'REFUND' ? '退款' : row.type === 'PAYMENT' ? '支付' : row.type || '—' }
function recordStatus(row: Row) { return resource.value === 'commissions' ? ({ PENDING: '待结算', SETTLED: '已结算', REVERSED: '已撤销' } as Record<string,string>)[row.status] || statusNames[row.status] || row.status || '—' : statusNames[row.status] || row.status || '—' }
function detailText(key: string, row: Row) {
  if (key === 'amount') return money(row[key])
  if (['createdAt','eligibleAt','settledAt'].includes(key)) return dateTime(row[key])
  if (key === 'action') return logNames[row[key]] || row[key] || '—'
  if (key === 'target') return targetText(row)
  if (key === 'status') return recordStatus(row)
  if (key === 'mode') return transactionMode(row)
  if (key === 'type') return transactionType(row)
  return row[key] === '' || row[key] == null ? '—' : row[key]
}
watch(() => [resource.value, route.query.keyword, route.query.worklist], () => {
  if (resource.value === 'payments' && route.query.worklist === 'refunds') {
    const term = String(route.query.keyword || '').trim()
    void router.replace({ path: '/orders', query: { worklist: 'refunds', ...(term ? { keyword: term } : {}) } })
    return
  }
  const term = resource.value === 'payments' ? String(route.query.keyword || '').trim() : ''
  if (loadedResource === resource.value && term === filters.keyword) return
  loadedResource = resource.value; requestVersion++; rows.value = []; total.value = 0; detail.value = null; error.value = ''; queryError.value = ''
  filters.keyword = term; keyword.value = term; filters.page = 1
  if (term.length > 80) { queryError.value = '查询订单号不能超过 80 个字符，请调整后查询'; loading.value = false; return }
  void load()
}, { immediate: true })
onBeforeUnmount(() => { requestVersion++ })
</script>
<template>
  <PageHeader :title="config.title" :subtitle="config.subtitle"><el-button v-if="resource === 'payments' && can('ADMIN', 'FINANCE')" @click="router.push('/orders?worklist=refunds')">处理待退款</el-button><el-button :loading="loading" @click="load"><AppIcon name="Refresh"/>刷新</el-button></PageHeader>
  <LoadError :error="error" @retry="load"/>
  <el-alert class="section-alert" :title="config.tip" type="info" :closable="false" show-icon/>
  <div class="panel">
    <div v-if="resource === 'payments'" class="filter-bar"><el-input v-model="keyword" placeholder="输入订单号查询流水" clearable maxlength="80" class="search-input" :disabled="loading" @keyup.enter="search" @clear="search"/><el-button type="primary" :disabled="loading" @click="search"><AppIcon name="Search"/>查询</el-button><el-button :disabled="loading" @click="reset">重置</el-button><span v-if="filters.keyword" class="applied-query">当前查询：{{ filters.keyword }}</span></div>
    <div v-if="queryError" class="query-error" role="alert">{{ queryError }}</div>
    <div class="panel-heading"><h3>{{ resource === 'payments' ? '交易流水' : resource === 'commissions' ? '佣金结算明细' : '后台操作记录' }}</h3><span class="muted">共 {{ total }} 条</span></div>
    <el-table v-loading="loading" :data="rows" :empty-text="error ? '记录加载失败，请重新加载' : filters.keyword ? '未查询到该订单的交易流水，请核对订单号' : '暂无相关记录'" row-key="id">
      <el-table-column prop="id" label="记录 ID" width="95"/>
      <template v-if="resource === 'payments'">
        <el-table-column label="关联订单" min-width="235"><template #default="{ row }"><button v-if="row.orderNo" class="table-link" @click="openOrder(row)">{{ row.orderNo }}</button><span v-else>—</span></template></el-table-column>
        <el-table-column prop="transactionNo" label="交易流水号" min-width="220" show-overflow-tooltip/>
        <el-table-column label="类型" width="90"><template #default="{ row }"><el-tag :type="row.type === 'REFUND' ? 'warning' : row.type === 'PAYMENT' ? 'primary' : 'info'" effect="plain" disable-transitions>{{ transactionType(row) }}</el-tag></template></el-table-column>
        <el-table-column label="金额" min-width="130" align="right"><template #default="{ row }"><strong class="financial-value">{{ money(row.amount) }}</strong></template></el-table-column>
        <el-table-column label="交易环境" min-width="150"><template #default="{ row }"><el-tag :type="row.mode === 'DEV' ? 'warning' : 'info'" effect="plain" disable-transitions>{{ transactionMode(row) }}</el-tag></template></el-table-column>
        <el-table-column label="状态" width="100"><template #default="{ row }"><StatusTag :value="row.status"/></template></el-table-column>
        <el-table-column prop="reason" label="交易备注" min-width="160" show-overflow-tooltip/>
      </template>
      <template v-else-if="resource === 'commissions'">
        <el-table-column label="推广关系" min-width="160"><template #default="{ row }"><span>推广用户 {{ row.userId }}</span><div class="cell-sub">被邀请用户 {{ row.referredUserId }}</div></template></el-table-column>
        <el-table-column label="关联订单" min-width="235"><template #default="{ row }"><button v-if="row.orderNo" class="table-link" @click="openOrder(row)">{{ row.orderNo }}</button><span v-else>—</span></template></el-table-column>
        <el-table-column label="佣金金额" min-width="130" align="right"><template #default="{ row }"><strong class="financial-value">{{ money(row.amount) }}</strong></template></el-table-column>
        <el-table-column label="结算状态" width="120"><template #default="{ row }"><el-tag :type="row.status === 'PENDING' ? 'warning' : row.status === 'SETTLED' ? 'success' : 'info'" effect="plain" disable-transitions>{{ recordStatus(row) }}</el-tag></template></el-table-column>
        <el-table-column label="预计结算时间" min-width="175"><template #default="{ row }">{{ dateTime(row.eligibleAt) }}</template></el-table-column>
        <el-table-column label="实际结算时间" min-width="175"><template #default="{ row }">{{ dateTime(row.settledAt) }}</template></el-table-column>
      </template>
      <template v-else>
        <el-table-column label="操作人员" min-width="150"><template #default="{ row }">{{ row.actorName || '—' }}<div class="cell-sub">ID {{ row.actorId }}</div></template></el-table-column>
        <el-table-column label="操作类型" min-width="200"><template #default="{ row }">{{ logNames[row.action] || row.action || '—' }}</template></el-table-column>
        <el-table-column label="处理对象 / 内容" min-width="280"><template #default="{ row }"><button v-if="orderNumber(row)" class="table-link" @click="openOrder(row)">{{ targetText(row) }}</button><span v-else class="record-target">{{ targetText(row) }}</span></template></el-table-column>
      </template>
      <el-table-column label="记录时间" min-width="175"><template #default="{ row }">{{ dateTime(row.createdAt) }}</template></el-table-column>
      <el-table-column label="操作" width="80" fixed="right"><template #default="{ row }"><el-button type="primary" link @click="showDetail(row)">详情</el-button></template></el-table-column>
    </el-table>
    <div class="pagination"><span>共 {{ total }} 条记录</span><el-pagination :current-page="filters.page" :page-size="filters.size" :total="total" :disabled="loading" :page-sizes="[10,20,50]" layout="sizes, prev, pager, next" @current-change="changePage" @size-change="changeSize"/></div>
  </div>
  <el-dialog :model-value="!!detail" :title="config.title + ' · 记录详情'" width="620px" @update:model-value="!$event && (detail = null)">
    <template v-if="detail"><p v-if="resource === 'commissions' && detail.status === 'PENDING'" class="record-note">该笔佣金尚不可提现，到期后会核验订单状态再结算。</p><el-descriptions :column="1" border><el-descriptions-item v-for="key in detailFields[resource]" :key="key" :label="fieldLabels[key]"><button v-if="key === 'orderNo' && detail.orderNo" class="table-link" @click="openOrder(detail)">{{ detail.orderNo }}</button><span v-else class="detail-value">{{ detailText(key, detail) }}</span></el-descriptions-item></el-descriptions></template>
    <template #footer><el-button @click="detail = null">关闭</el-button><el-button v-if="detail && orderNumber(detail)" type="primary" @click="openOrder(detail)">查看关联订单</el-button></template>
  </el-dialog>
</template>
<style scoped>
.applied-query{max-width:100%;color:#606266;font-size:13px;overflow-wrap:anywhere}.query-error{padding:12px 20px;color:#b42318;font-size:13px;line-height:22px;border-bottom:1px solid #ebeef5}.financial-value{font-variant-numeric:tabular-nums}.record-target{overflow-wrap:anywhere;white-space:pre-wrap}.detail-value{overflow-wrap:anywhere;white-space:pre-wrap}.record-note{font-size:14px;line-height:22px;color:#606266;margin:0 0 20px}
</style>
