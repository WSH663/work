<script setup lang="ts">
import { ref, reactive, onMounted, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, post, patch, money, errorText, can, type Row } from '../lib/api'
import PageHeader from '../components/PageHeader.vue'
import LoadError from '../components/LoadError.vue'
const rows = ref<Row[]>([]), loading = ref(false), saving = ref(false), error = ref(''), formError = ref(''), dialog = ref(false), closing = ref(false)
const filters = reactive({ keyword: '', status: '', page: 1, size: 10 }), applied = reactive({ keyword: '', status: '' })
const filtered = computed(() => rows.value.filter(row => (!applied.keyword || [row.name, row.code, row.description].some(value => String(value || '').toLowerCase().includes(applied.keyword.toLowerCase()))) && (!applied.status || Boolean(row.enabled) === (applied.status === 'enabled'))))
const paged = computed(() => filtered.value.slice((filters.page - 1) * filters.size, filters.page * filters.size))
const defaults = { name: '', code: '', seats: 4, description: '', baseFare: null, perKm: 0, includedKm: 0, charterHourly: null, minimumHours: 1, surcharge: 0, enabled: false }
const form = reactive<Row>({ ...defaults }), fieldErrors = reactive<Record<string, string>>({})
const fees = [{ key: 'baseFare', label: '起步价', suffix: '元' }, { key: 'includedKm', label: '起步里程', suffix: '公里' }, { key: 'perKm', label: '超出里程单价', suffix: '元 / 公里' }, { key: 'charterHourly', label: '包车参考单价', suffix: '元 / 小时' }, { key: 'minimumHours', label: '包车最少时长', suffix: '小时' }, { key: 'surcharge', label: '固定附加费', suffix: '元' }]
let requestVersion = 0, baseline = '', originalEnabled = false
function payload() { return Object.fromEntries(Object.keys(defaults).map(key => [key, typeof form[key] === 'string' ? form[key].trim() : form[key]])) }
function clearErrors() { Object.keys(fieldErrors).forEach(key => delete fieldErrors[key]); formError.value = '' }
function clampPage() { filters.page = Math.max(1, Math.min(filters.page, Math.ceil(filtered.value.length / filters.size))) }
function search() { Object.assign(applied, { keyword: filters.keyword.trim(), status: filters.status }); filters.page = 1 }
function reset() { filters.keyword = ''; filters.status = ''; search() }
async function load() {
  const version = ++requestVersion; loading.value = true; error.value = ''
  try { const result = await api<Row[]>('/admin/vehicles'); if (version === requestVersion) { rows.value = result; clampPage() } }
  catch (err) { if (version === requestVersion) error.value = errorText(err) }
  finally { if (version === requestVersion) loading.value = false }
}
function edit(row?: Row) {
  if (saving.value || !can('ADMIN', 'OPERATOR')) return
  Object.keys(form).forEach(key => delete form[key]); Object.assign(form, defaults, row || {}); originalEnabled = Boolean(form.enabled); baseline = JSON.stringify(payload()); clearErrors(); dialog.value = true
}
async function closeEditor(done?: () => void) {
  if (!dialog.value) { if (done) done(); return }
  if (saving.value || closing.value) return
  closing.value = true
  try { if (baseline !== JSON.stringify(payload())) await ElMessageBox.confirm('当前车型修改尚未保存，确认放弃修改？', '放弃修改', { confirmButtonText: '放弃修改', cancelButtonText: '继续编辑', type: 'warning' }); dialog.value = false; if (done) done() }
  catch { /* Keep the draft open. */ }
  finally { closing.value = false }
}
function validate() {
  clearErrors()
  for (const [key, label] of [['name', '车型名称'], ['code', '车型代码'], ['description', '车型介绍']]) if (!String(form[key] || '').trim()) fieldErrors[key!] = `请填写${label}`
  if (!Number.isInteger(form.seats) || form.seats < 1 || form.seats > 30) fieldErrors.seats = '座位数须为 1 至 30 的整数'
  for (const fee of fees) {
    const value = form[fee.key], positive = ['baseFare', 'charterHourly', 'minimumHours'].includes(fee.key), max = fee.key === 'minimumHours' ? 240 : 100000
    if (typeof value !== 'number' || !Number.isFinite(value) || value < (positive ? 0.01 : 0) || value > max || Math.abs(value * 100 - Math.round(value * 100)) > 0.00001) fieldErrors[fee.key] = `请输入${positive ? '大于 0' : '不小于 0'}、不超过 ${max} 的数值，最多两位小数`
  }
  return !Object.keys(fieldErrors).length
}
async function save() {
  if (saving.value || closing.value || !can('ADMIN', 'OPERATOR') || !validate()) return
  const id = form.id, body = payload(), statusChanged = id ? originalEnabled !== Boolean(body.enabled) : Boolean(body.enabled)
  saving.value = true
  try {
    if (statusChanged) await ElMessageBox.confirm(body.enabled ? '保存后该车型将开放预约，确认启用？' : '保存后该车型将停止接受新预约，确认停用？', body.enabled ? '启用车型' : '停用车型', { confirmButtonText: '确认保存', cancelButtonText: '返回修改', type: 'warning', closeOnClickModal: false })
    if (id) await patch(`/admin/vehicles/${id}`, body); else await post('/admin/vehicles', body)
    baseline = JSON.stringify(payload()); ElMessage.success('车型资料已保存'); dialog.value = false; await load()
  } catch (err) { if (err !== 'cancel' && err !== 'close') formError.value = errorText(err) }
  finally { saving.value = false }
}
async function toggle(row: Row) {
  if (saving.value || !can('ADMIN', 'OPERATOR')) return
  const id = Number(row.id), enabled = !row.enabled, name = row.name
  saving.value = true
  try {
    await ElMessageBox.confirm(enabled ? `确认启用“${name}”并开放预约？` : `确认停用“${name}”？停用后将不再接受该车型的新预约。`, enabled ? '启用车型' : '停用车型', { confirmButtonText: enabled ? '确认启用' : '确认停用', cancelButtonText: '返回', type: 'warning', closeOnClickModal: false })
    const updated = await patch(`/admin/vehicles/${id}`, { enabled }); requestVersion++; loading.value = false; rows.value = rows.value.map(item => Number(item.id) === id ? updated : item); clampPage(); ElMessage.success(enabled ? '车型已启用' : '车型已停用')
  } catch (err) { if (err !== 'cancel' && err !== 'close') ElMessage.error(errorText(err)) }
  finally { saving.value = false }
}
onMounted(load)
</script>
<template>
  <PageHeader title="车型与价格" subtitle="配置无障碍车型、起步与里程计价，以及包车参考价格。"><el-button :loading="loading" :disabled="saving" @click="load"><AppIcon name="Refresh"/>刷新</el-button><el-button v-if="can('ADMIN', 'OPERATOR')" type="primary" :disabled="saving" @click="edit()"><AppIcon name="Plus"/>新增车型</el-button></PageHeader>
  <LoadError :error="error" @retry="load"/>
  <div class="panel">
    <div class="filter-bar"><el-input v-model="filters.keyword" placeholder="车型名称 / 代码 / 介绍" clearable class="search-input" @keyup.enter="search"/><el-select v-model="filters.status" placeholder="全部状态" clearable><el-option label="已启用" value="enabled"/><el-option label="已停用" value="disabled"/></el-select><el-button type="primary" @click="search">查询</el-button><el-button @click="reset">重置</el-button></div>
    <div class="panel-heading"><div><h3>车型列表</h3><p>包车最终费用由客服报价，服务区域在平台配置中设置</p></div><span class="muted">共 {{ filtered.length }} 个车型</span></div>
    <el-table v-loading="loading" :data="paged" :empty-text="error ? '车型加载失败，请重试' : '暂无符合条件的车型'" row-key="id">
      <el-table-column label="车型" min-width="185"><template #default="{ row }"><strong class="vehicle-name">{{ row.name }}</strong><div class="cell-sub">{{ row.code }} · {{ row.seats }} 座</div></template></el-table-column>
      <el-table-column prop="description" label="车型说明" min-width="230" show-overflow-tooltip/>
      <el-table-column label="起步费用" min-width="145"><template #default="{ row }">{{ money(row.baseFare) }}<div class="cell-sub">包含 {{ row.includedKm }} 公里</div></template></el-table-column>
      <el-table-column label="里程计价" min-width="140"><template #default="{ row }">{{ money(row.perKm) }} / 公里</template></el-table-column>
      <el-table-column label="包车参考" min-width="155"><template #default="{ row }">{{ money(row.charterHourly) }} / 小时<div class="cell-sub">{{ row.minimumHours }} 小时起订</div></template></el-table-column>
      <el-table-column label="附加费" width="115"><template #default="{ row }">{{ money(row.surcharge) }}</template></el-table-column>
      <el-table-column label="状态" width="105"><template #default="{ row }"><el-tag :type="row.enabled ? 'success' : 'info'" effect="plain" disable-transitions>{{ row.enabled ? '已启用' : '已停用' }}</el-tag></template></el-table-column>
      <el-table-column label="操作" width="155" fixed="right"><template #default="{ row }"><div v-if="can('ADMIN', 'OPERATOR')" class="table-actions"><el-button link type="primary" :disabled="saving" @click="edit(row)">编辑</el-button><el-button link :type="row.enabled ? 'danger' : 'primary'" :disabled="saving" @click="toggle(row)">{{ row.enabled ? '停用' : '启用' }}</el-button></div></template></el-table-column>
    </el-table>
    <div class="pagination"><span>共 {{ filtered.length }} 个车型</span><el-pagination v-model:current-page="filters.page" v-model:page-size="filters.size" :total="filtered.length" :page-sizes="[10, 20, 50]" layout="sizes, prev, pager, next" @size-change="filters.page = 1"/></div>
  </div>
  <el-dialog :model-value="dialog" :title="form.id ? '编辑车型' : '新增车型'" width="720px" :close-on-click-modal="false" :close-on-press-escape="!saving" :show-close="!saving" :before-close="closeEditor">
    <el-alert v-if="formError" class="section-alert" :title="formError" type="error" :closable="false" show-icon/>
    <el-alert v-if="!form.id" class="section-alert" title="新车型默认停用。请填写实际定价，保存后核对计价规则，再确认启用。" type="info" :closable="false" show-icon/>
    <el-form label-position="top" :disabled="saving" @submit.prevent="save">
      <h3 class="editor-section">基本资料</h3><div class="vehicle-form-grid">
        <el-form-item label="车型名称" required :error="fieldErrors.name"><el-input v-model="form.name" maxlength="80" placeholder="例：A 型无障碍车" @input="delete fieldErrors.name"/></el-form-item>
        <el-form-item label="车型代码" required :error="fieldErrors.code"><el-input v-model="form.code" maxlength="20" placeholder="例：A" @input="delete fieldErrors.code"/></el-form-item>
        <el-form-item label="乘客座位数" required :error="fieldErrors.seats"><el-input-number v-model="form.seats" :min="1" :max="30" :precision="0" @change="delete fieldErrors.seats"/></el-form-item>
        <el-form-item label="预约状态"><el-switch v-model="form.enabled" active-text="启用" inactive-text="停用"/><small class="field-help">状态在保存成功后生效。</small></el-form-item>
      </div>
      <el-form-item label="车型介绍" required :error="fieldErrors.description"><el-input v-model="form.description" type="textarea" :rows="3" maxlength="1000" show-word-limit placeholder="座位、轮椅固定装置、随车设施及服务说明" @input="delete fieldErrors.description"/></el-form-item>
      <h3 class="editor-section">计费规则</h3><div class="vehicle-form-grid"><el-form-item v-for="fee in fees" :key="fee.key" :label="`${fee.label}（${fee.suffix}）`" required :error="fieldErrors[fee.key]"><el-input-number v-model="form[fee.key]" :min="['baseFare', 'charterHourly', 'minimumHours'].includes(fee.key) ? 0.01 : 0" :max="fee.key === 'minimumHours' ? 240 : 100000" :precision="2" :placeholder="['baseFare', 'charterHourly'].includes(fee.key) ? '请填写实际定价' : '请输入数值'" controls-position="right" @change="delete fieldErrors[fee.key]"/></el-form-item></div>
      <p class="field-help">预约用车按起步价、超出里程及固定附加费计算。包车参考单价用于估价，最终以人工报价为准。</p>
    </el-form>
    <template #footer><el-button :disabled="saving || closing" @click="closeEditor()">返回</el-button><el-button type="primary" :loading="saving" :disabled="closing" @click="save">保存车型</el-button></template>
  </el-dialog>
</template>
<style scoped>
.vehicle-name{display:block;overflow-wrap:anywhere}.vehicle-form-grid{display:grid;grid-template-columns:1fr 1fr;gap:0 24px}.vehicle-form-grid :deep(.el-input-number){width:100%}.editor-section{font-size:15px;font-weight:600;margin:0 0 18px;padding-top:8px}.editor-section:not(:first-child){margin-top:12px;border-top:1px solid var(--el-border-color-lighter);padding-top:20px}@media(max-width:640px){.vehicle-form-grid{grid-template-columns:1fr}}
</style>
