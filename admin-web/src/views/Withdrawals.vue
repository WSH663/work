<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, can, dateTime, errorText, money, post, query, session, statusNames, type Page, type Row } from '../lib/api'
import PageHeader from '../components/PageHeader.vue'
import LoadError from '../components/LoadError.vue'
import StatusTag from '../components/StatusTag.vue'
const route = useRoute(), router = useRouter(), statuses = ['PENDING', 'APPROVED', 'REJECTED', 'PAID']
const worklist = computed(() => route.query.worklist === 'todo' ? 'todo' : '')
const availableStatuses = computed(() => worklist.value ? ['PENDING', 'APPROVED'] : statuses)
function routeFilters() {
  const status = String(route.query.status || ''), page = Number(route.query.page || 1), size = Number(route.query.size || 10)
  return { status: availableStatuses.value.includes(status) ? status : '', page: Number.isSafeInteger(page) && page > 0 ? page : 1, size: [10, 20, 50].includes(size) ? size : 10 }
}
const filters = reactive(routeFilters()), rows = ref<Row[]>([]), total = ref(0), loading = ref(false), saving = ref(false), error = ref(''), formError = ref(''), action = ref(''), selected = ref<Row>({}), detail = ref<Row | null>(null), closing = ref(false)
const form = reactive({ approved: true, remark: '', paymentReference: '', paymentConfirmed: false, reason: '', cancellationConfirmed: false })
const actionTitles: Record<string, string> = { review: '审核提现申请', paid: '登记已完成的付款', cancel: '撤销提现审核' }
let requestVersion = 0, baseline = ''
const dirty = computed(() => !!action.value && JSON.stringify(form) !== baseline)
const locked = computed(() => loading.value || saving.value || !!action.value)
function statusHint(row: Row) {
  if (row.status === 'PENDING') return '申请金额已冻结，等待财务审核。'
  if (row.status === 'APPROVED') return '审核已通过。实际转账完成后登记付款；尚未转账时可以撤销审核并释放冻结金额。'
  if (row.status === 'PAID') return '财务已登记线下付款。系统不会自动划款，请按真实付款流水核对到账。'
  if (row.status === 'REJECTED') return String(row.remark || '').startsWith('已撤销审核') ? '审核已撤销，冻结金额已退回用户可提现余额。' : '申请已驳回，冻结金额已退回用户可提现余额。'
  return '请刷新列表核对最新申请状态。'
}
async function load() {
  if (saving.value || action.value) return
  const version = ++requestVersion, params = { ...filters, worklist: worklist.value }; loading.value = true; error.value = ''; rows.value = []; total.value = 0; detail.value = null
  try {
    const data = await api<Page>('/admin/withdrawals?' + query(params))
    if (version !== requestVersion) return
    const last = Math.max(1, Math.ceil(data.total / params.size))
    if (params.page > last) { filters.page = last; syncFilters(); return }
    rows.value = data.items; total.value = data.total
  } catch (err) { if (version === requestVersion) error.value = errorText(err) }
  finally { if (version === requestVersion) loading.value = false }
}
function syncFilters() {
  const params: Record<string, string> = {}
  if (worklist.value) params.worklist = worklist.value
  if (filters.status) params.status = filters.status
  if (filters.page > 1) params.page = String(filters.page)
  if (filters.size !== 10) params.size = String(filters.size)
  if (JSON.stringify(routeFilters()) === JSON.stringify(filters)) void load()
  else void router.replace({ path: route.path, query: params })
}
function filterChanged() { filters.page = 1; detail.value = null; syncFilters() }
function reset() { if (locked.value) return; filters.status = ''; filterChanged() }
function changePage(page: number) { if (locked.value || page === filters.page) return; filters.page = page; syncFilters() }
function changeSize(size: number) { if (locked.value) return; filters.size = size; filters.page = 1; syncFilters() }
function openDetail(row: Row) { if (!loading.value && !saving.value) detail.value = { ...row } }
function open(kind: string, row: Row) {
  if (loading.value || saving.value || action.value || !can('ADMIN', 'FINANCE') || !Object.hasOwn(actionTitles, kind) || (kind === 'review' ? row.status !== 'PENDING' : row.status !== 'APPROVED')) return
  selected.value = { ...row }; Object.assign(form, { approved: true, remark: '', paymentReference: '', paymentConfirmed: false, reason: '', cancellationConfirmed: false })
  baseline = JSON.stringify(form); formError.value = ''; action.value = kind
}
async function confirmDiscard() {
  if (!session.token || !dirty.value) return true
  try { await ElMessageBox.confirm('本次处理尚未提交，是否放弃已填写的内容？', '关闭提现处理', { type: 'warning', confirmButtonText: '放弃填写', cancelButtonText: '继续处理', closeOnClickModal: false, closeOnHashChange: false }); return true }
  catch { return false }
}
async function closeAction(done?: () => void) {
  if (!action.value) { done?.(); return }
  if (saving.value || closing.value) return
  closing.value = true
  try { if (!await confirmDiscard()) return; action.value = ''; formError.value = ''; done?.() }
  finally { closing.value = false }
}
async function submit() {
  if (saving.value || !action.value || !can('ADMIN', 'FINANCE')) return
  const kind = action.value, id = Number(selected.value.id); formError.value = ''
  if (!Number.isSafeInteger(id) || id <= 0) { formError.value = '申请编号无效，请关闭后刷新列表'; return }
  if (kind === 'review' && !form.remark.trim()) { formError.value = '请填写审核说明'; return }
  if (kind === 'paid' && (!form.paymentReference.trim() || !form.paymentConfirmed)) { formError.value = '请先完成实际付款，填写付款流水号并确认付款事实'; return }
  if (kind === 'cancel' && (!form.reason.trim() || !form.cancellationConfirmed)) { formError.value = '请填写撤销原因，并确认尚未实际转账；已转账的申请不得撤销'; return }
  const body = kind === 'review' ? { approved: form.approved, remark: form.remark.trim() } : kind === 'cancel' ? { reason: form.reason.trim() } : { paymentReference: form.paymentReference.trim() }
  saving.value = true; let success = false
  try {
    await post('/admin/withdrawals/' + id + '/' + kind, body); success = true; action.value = ''; detail.value = null
    ElMessage.success(kind === 'cancel' ? '审核已撤销，冻结金额已释放' : kind === 'paid' ? '付款登记已保存' : form.approved ? '审核已通过，等待实际付款' : '申请已驳回，冻结金额已退回')
  } catch (err) { formError.value = errorText(err) }
  finally { saving.value = false }
  if (success) await load()
}
watch(() => [route.query.worklist, route.query.status, route.query.page, route.query.size], () => {
  if (saving.value || action.value) return
  Object.assign(filters, routeFilters()); void load()
}, { immediate: true })
onBeforeRouteLeave(async () => { if (!session.token) return true; if (saving.value) { ElMessage.warning('提现正在处理，请稍候'); return false } return confirmDiscard() })
onBeforeRouteUpdate(async (to, from) => { if (['status', 'worklist', 'page', 'size'].every(key => to.query[key] === from.query[key]) || !session.token) return true; if (saving.value) { ElMessage.warning('提现正在处理，请稍候'); return false } if (!await confirmDiscard()) return false; action.value = ''; formError.value = ''; return true })
onBeforeUnmount(() => { requestVersion++ })
</script>
<template>
  <PageHeader :title="worklist ? '提现待办' : '提现审核'" :subtitle="worklist ? '集中处理待审核申请和审核通过后尚未登记付款的申请。' : '按申请状态审核，实际转账后登记付款。'"><el-button v-if="worklist" :disabled="locked" @click="router.push('/withdrawals')">查看全部申请</el-button><el-button :loading="loading" :disabled="saving || !!action" @click="load"><AppIcon name="Refresh"/>刷新</el-button></PageHeader>
  <LoadError :error="error" @retry="load"/>
  <el-alert class="section-alert" title="付款登记只记录已完成的线下转账，不会自动划款。撤销审核仅适用于尚未实际转账的申请。" type="warning" :closable="false" show-icon/>
  <div class="panel">
    <div class="filter-bar"><label class="filter-label">申请状态</label><el-select v-model="filters.status" :placeholder="worklist ? '全部待办状态' : '全部申请状态'" clearable :disabled="locked" @change="filterChanged"><el-option v-for="value in availableStatuses" :key="value" :label="statusNames[value]" :value="value"/></el-select><el-button :disabled="locked || !filters.status" @click="reset">重置</el-button></div>
    <el-table v-loading="loading" :data="rows" :empty-text="error ? '申请加载失败，请重新加载' : filters.status ? '当前状态暂无提现申请' : worklist ? '暂无待审核或待付款的提现申请' : '暂无提现申请'">
      <el-table-column label="申请编号" width="115"><template #default="{ row }"><button class="table-link" @click="openDetail(row)">#{{ row.id }}</button><div class="cell-sub">用户 {{ row.userId }}</div></template></el-table-column>
      <el-table-column label="收款信息" min-width="220"><template #default="{ row }"><strong>{{ row.accountName }}</strong><div class="cell-sub account-number">{{ row.accountNo }}</div></template></el-table-column>
      <el-table-column label="申请金额" min-width="130" align="right"><template #default="{ row }"><strong class="amount">{{ money(row.amount) }}</strong></template></el-table-column>
      <el-table-column label="状态" width="125"><template #default="{ row }"><StatusTag :value="row.status"/><div v-if="row.status === 'REJECTED' && String(row.remark || '').startsWith('已撤销审核')" class="cell-sub">审核已撤销</div></template></el-table-column>
      <el-table-column prop="remark" label="审核说明" min-width="180" show-overflow-tooltip/>
      <el-table-column label="申请时间" min-width="175"><template #default="{ row }">{{ dateTime(row.createdAt) }}</template></el-table-column>
      <el-table-column label="操作" min-width="220" fixed="right"><template #default="{ row }"><div class="table-actions"><el-button type="primary" link :disabled="saving" @click="openDetail(row)">详情</el-button><template v-if="can('ADMIN', 'FINANCE')"><el-button v-if="row.status === 'PENDING'" type="primary" link :disabled="locked" @click="open('review', row)">审核申请</el-button><template v-else-if="row.status === 'APPROVED'"><el-button type="primary" link :disabled="locked" @click="open('paid', row)">付款登记</el-button><el-button type="warning" link :disabled="locked" @click="open('cancel', row)">撤销审核</el-button></template></template></div></template></el-table-column>
    </el-table>
    <div class="pagination"><span>共 {{ total }} 条申请</span><el-pagination :current-page="filters.page" :page-size="filters.size" :total="total" :disabled="locked" :page-sizes="[10,20,50]" layout="sizes, prev, pager, next" @current-change="changePage" @size-change="changeSize"/></div>
  </div>
  <el-drawer :model-value="!!detail" title="提现申请详情" size="560px" @update:model-value="!$event && (detail = null)">
    <template v-if="detail"><div class="detail-amount"><div><span>申请 #{{ detail.id }}</span><strong>{{ money(detail.amount) }}</strong></div><StatusTag :value="detail.status"/></div><p class="detail-hint">{{ statusHint(detail) }}</p>
      <h3 class="form-section-title">收款信息</h3><el-descriptions :column="1" border><el-descriptions-item label="用户 ID">{{ detail.userId }}</el-descriptions-item><el-descriptions-item label="收款人">{{ detail.accountName }}</el-descriptions-item><el-descriptions-item label="收款账户"><span class="account-number">{{ detail.accountNo }}</span></el-descriptions-item></el-descriptions>
      <h3 class="section-title">审核与付款记录</h3><el-descriptions :column="1" border><el-descriptions-item label="审核说明"><span class="multiline">{{ detail.remark || '—' }}</span></el-descriptions-item><el-descriptions-item label="付款流水"><span class="account-number">{{ detail.paymentReference || '—' }}</span></el-descriptions-item><el-descriptions-item label="申请时间">{{ dateTime(detail.createdAt) }}</el-descriptions-item><el-descriptions-item label="更新时间">{{ dateTime(detail.updatedAt) }}</el-descriptions-item></el-descriptions>
    </template>
    <template #footer><div v-if="detail" class="drawer-actions"><el-button @click="detail = null">关闭</el-button><template v-if="can('ADMIN', 'FINANCE')"><el-button v-if="detail.status === 'PENDING'" type="primary" :disabled="locked" @click="open('review', detail)">审核申请</el-button><template v-else-if="detail.status === 'APPROVED'"><el-button type="warning" :disabled="locked" @click="open('cancel', detail)">撤销审核</el-button><el-button type="primary" :disabled="locked" @click="open('paid', detail)">付款登记</el-button></template></template></div></template>
  </el-drawer>
  <el-dialog :model-value="!!action" :title="actionTitles[action]" width="540px" :close-on-click-modal="false" :close-on-press-escape="!saving" :show-close="!saving" :before-close="closeAction" @update:model-value="!$event && closeAction()">
    <el-alert v-if="formError" :title="formError" type="error" :closable="false" show-icon/>
    <div class="withdrawal-summary"><span>申请 #{{ selected.id }} · 用户 {{ selected.userId }}</span><strong>{{ money(selected.amount) }}</strong><p>{{ selected.accountName }}<br><span class="account-number">{{ selected.accountNo }}</span></p></div>
    <el-form label-position="top" :disabled="saving" @submit.prevent="submit">
      <template v-if="action === 'review'"><el-form-item label="审核结果" required><el-radio-group v-model="form.approved"><el-radio :value="true">审核通过</el-radio><el-radio :value="false">驳回申请</el-radio></el-radio-group></el-form-item><el-form-item label="审核说明" required><el-input v-model="form.remark" type="textarea" :rows="3" maxlength="500" show-word-limit :placeholder="form.approved ? '填写核验收款信息及申请资格的结果' : '请说明驳回原因，用户将看到该说明'"/></el-form-item><p class="operation-help">{{ form.approved ? '审核通过后金额继续冻结，实际转账完成后再登记付款。' : '驳回后，申请金额将退回用户可提现余额。' }}</p></template>
      <template v-else-if="action === 'cancel'"><el-alert class="section-alert" title="仅可撤销尚未实际转账的审核。申请将变为已驳回，冻结金额释放；该操作不会追回已转账资金。" type="warning" :closable="false" show-icon/><el-form-item label="撤销原因" required><el-input v-model="form.reason" type="textarea" :rows="3" maxlength="500" show-word-limit placeholder="说明转账未完成或需要撤销审核的原因"/></el-form-item><el-checkbox v-model="form.cancellationConfirmed">已核实尚未实际转账，仅撤销审核并释放冻结金额</el-checkbox></template>
      <template v-else-if="action === 'paid'"><el-form-item label="实际付款流水号" required><el-input v-model="form.paymentReference" maxlength="120" placeholder="银行或支付渠道的真实付款流水号"/></el-form-item><el-checkbox v-model="form.paymentConfirmed">已核对收款人与金额，并确认实际转账已完成</el-checkbox></template>
    </el-form>
    <template #footer><el-button :disabled="saving" @click="closeAction()">返回</el-button><el-button :type="action === 'cancel' || (action === 'review' && !form.approved) ? 'warning' : 'primary'" :loading="saving" @click="submit">{{ action === 'review' ? form.approved ? '确认通过审核' : '确认驳回并解冻' : action === 'cancel' ? '确认撤销审核' : '确认登记' }}</el-button></template>
  </el-dialog>
</template>
<style scoped>
.account-number{overflow-wrap:anywhere;font-variant-numeric:tabular-nums}.detail-amount{display:flex;justify-content:space-between;align-items:center;gap:16px}.detail-amount span{font-size:13px;color:#909399}.detail-amount strong{display:block;margin-top:8px;font-size:24px;font-weight:500;font-variant-numeric:tabular-nums}.detail-hint{font-size:14px;line-height:24px;color:#606266;margin:20px 0 28px}.multiline{white-space:pre-wrap;overflow-wrap:anywhere}.drawer-actions{display:flex;justify-content:flex-end;gap:8px;flex-wrap:wrap}.drawer-actions .el-button+.el-button{margin-left:0}.operation-help{font-size:13px;line-height:22px;color:#606266;margin:0}.el-checkbox{height:auto;align-items:flex-start;line-height:22px}.el-checkbox :deep(.el-checkbox__label){white-space:normal;line-height:22px}.el-checkbox :deep(.el-checkbox__input){padding-top:4px}
</style>
