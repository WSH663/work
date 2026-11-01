<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref } from 'vue'
import { onBeforeRouteLeave, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, can, dateTime, errorText, logout, patch, post, roles, session, type Row } from '../lib/api'
import PageHeader from '../components/PageHeader.vue'
import LoadError from '../components/LoadError.vue'
import StatusTag from '../components/StatusTag.vue'
const router = useRouter(), rows = ref<Row[]>([]), loading = ref(false), saving = ref(false), ready = ref(false), error = ref(''), formError = ref(''), dialog = ref(false), relogin = ref(false), closing = ref(false)
const filters = reactive({ keyword: '', role: '', status: '', page: 1, size: 10 }), keyword = ref('')
const form = reactive<Row>({ id: null, username: '', password: '', nickname: '', role: 'OPERATOR', enabled: true }), baseline = ref('')
let requestVersion = 0
const roleDescriptions: Record<string, string> = { ADMIN: '运营、财务与系统配置，可管理后台账号和岗位权限。', OPERATOR: '订单报价、派单、服务流转、用户、车型与内容管理。', FINANCE: '订单查询及退款、交易流水、佣金查询、提现审核、付款登记与未付款撤销。' }
const filtered = computed(() => rows.value.filter(row => (!filters.role || row.role === filters.role) && (!filters.status || row.status === filters.status) && (!filters.keyword || [row.username, row.nickname].some(value => String(value || '').toLowerCase().includes(filters.keyword.toLowerCase())))))
const pageRows = computed(() => filtered.value.slice((filters.page - 1) * filters.size, filters.page * filters.size))
const activeAdmins = computed(() => rows.value.filter(row => row.role === 'ADMIN' && row.status === 'ACTIVE').length)
const fingerprint = () => JSON.stringify(form)
const dirty = computed(() => dialog.value && fingerprint() !== baseline.value)
async function load() {
  if (saving.value) return
  const version = ++requestVersion; loading.value = true; ready.value = false; error.value = ''; rows.value = []
  try { const result = await api<Row[]>('/admin/admins'); if (version === requestVersion) { rows.value = result; ready.value = true; filters.page = Math.min(filters.page, Math.max(1, Math.ceil(filtered.value.length / filters.size))) } }
  catch (err) { if (version === requestVersion) error.value = errorText(err) }
  finally { if (version === requestVersion) loading.value = false }
}
function search() { filters.keyword = keyword.value.trim(); filters.page = 1 }
function reset() { keyword.value = ''; Object.assign(filters, { keyword: '', role: '', status: '', page: 1 }) }
function edit(row?: Row) {
  if (loading.value || saving.value || !ready.value || !can('ADMIN')) return
  Object.assign(form, { id: null, username: '', password: '', nickname: '', role: 'OPERATOR', enabled: true }, row ? { id: row.id, username: row.username, nickname: row.nickname, role: row.role, enabled: row.status === 'ACTIVE' } : {})
  baseline.value = fingerprint(); formError.value = ''; dialog.value = true
}
async function confirmDiscard() {
  if (!session.token || !dirty.value) return true
  try { await ElMessageBox.confirm('输入的管理员信息尚未保存，是否放弃修改？', '关闭编辑', { type: 'warning', confirmButtonText: '放弃修改', cancelButtonText: '继续编辑', closeOnClickModal: false }); return true }
  catch { return false }
}
async function closeDialog(done?: () => void) {
  if (!dialog.value) { done?.(); return }
  if (saving.value || closing.value) return
  closing.value = true
  try { if (!await confirmDiscard()) return; dialog.value = false; formError.value = ''; done?.() }
  finally { closing.value = false }
}
async function save() {
  if (saving.value || !can('ADMIN') || !dirty.value) return
  formError.value = ''
  if (!form.id && (!/^[a-zA-Z0-9_.-]{3,64}$/.test(form.username.trim()) || !form.nickname.trim())) { formError.value = '请填写 3 至 64 位账号（字母、数字或 _ . -）和显示姓名'; return }
  if ((!form.id || form.password) && (form.password.length < 10 || !/[A-Za-z]/.test(form.password) || !/[0-9]/.test(form.password))) { formError.value = '密码至少 10 位，且包含字母和数字'; return }
  if (!Object.hasOwn(roles, form.role)) { formError.value = '请选择有效的岗位角色'; return }
  const current = rows.value.find(row => Number(row.id) === Number(form.id))
  if (current?.role === 'ADMIN' && current.status === 'ACTIVE' && activeAdmins.value <= 1 && (!form.enabled || form.role !== 'ADMIN')) { formError.value = '必须保留至少一位启用的超级管理员'; return }
  const id = form.id, own = Number(id) === Number(session.user?.id)
  const payload = id ? { role: form.role, enabled: form.enabled, ...(form.password ? { password: form.password } : {}) } : { username: form.username.trim(), password: form.password, nickname: form.nickname.trim(), role: form.role }
  saving.value = true
  try {
    if (id) {
      const changes = ['账号：' + form.username, '岗位：' + roles[form.role], '状态：' + (form.enabled ? '正常' : '停用'), form.password ? '将重置该账号密码' : '', own ? '当前账号保存后将退出，需要重新登录。' : '保存后该账号现有登录会话将失效。'].filter(Boolean).join('\n')
      try { await ElMessageBox.confirm(changes, '确认修改管理员权限', { type: 'warning', confirmButtonText: '确认保存', cancelButtonText: '继续编辑', closeOnClickModal: false, customStyle: { whiteSpace: 'pre-line' } }) }
      catch { return }
      await patch('/admin/admins/' + id, payload)
    } else await post('/admin/admins', payload)
    dialog.value = false; form.password = ''
    if (own) { relogin.value = true; logout(); ElMessage.success('账号信息已更新，请重新登录'); await router.replace('/login'); return }
    ElMessage.success(id ? '管理员信息已保存' : '管理员已创建')
  } catch (err) { formError.value = errorText(err) }
  finally { saving.value = false }
  if (!dialog.value) await load()
}
onBeforeRouteLeave(async () => { if (!session.token || relogin.value) return true; if (saving.value) { ElMessage.warning('管理员信息正在保存，请稍候'); return false } return confirmDiscard() })
onBeforeUnmount(() => { requestVersion++ })
void load()
</script>
<template>
  <PageHeader title="管理员与权限" subtitle="按岗位分配后台权限，账号变更后将重新验证登录。"><el-button :loading="loading" :disabled="saving || dialog" @click="load"><AppIcon name="Refresh"/>刷新</el-button><el-button v-if="can('ADMIN')" type="primary" :disabled="!ready || loading || saving" @click="edit()"><AppIcon name="Plus"/>新增管理员</el-button></PageHeader>
  <LoadError :error="error" @retry="load"/>
  <div class="panel">
    <div class="filter-bar"><el-input v-model="keyword" class="search-input" placeholder="账号或显示姓名" clearable :disabled="loading || saving" @keyup.enter="search" @clear="search"/><el-select v-model="filters.role" placeholder="全部岗位" clearable :disabled="loading || saving" @change="filters.page = 1"><el-option v-for="(label, role) in roles" :key="role" :value="role" :label="label"/></el-select><el-select v-model="filters.status" placeholder="全部状态" clearable :disabled="loading || saving" @change="filters.page = 1"><el-option label="正常" value="ACTIVE"/><el-option label="已停用" value="DISABLED"/></el-select><el-button type="primary" :disabled="loading || saving" @click="search"><AppIcon name="Search"/>查询</el-button><el-button :disabled="loading || saving" @click="reset">重置</el-button></div>
    <el-table v-loading="loading" :data="pageRows" :empty-text="error ? '账号加载失败，请重新加载' : filters.keyword || filters.role || filters.status ? '没有符合条件的账号，请调整筛选' : '暂无后台账号'">
      <el-table-column prop="username" label="管理员账号" min-width="180"/><el-table-column prop="nickname" label="显示姓名" min-width="140"/>
      <el-table-column label="岗位" min-width="180"><template #default="{ row }">{{ roles[row.role] || row.role }}<el-tag v-if="Number(row.id) === Number(session.user?.id)" class="self-tag" type="info" size="small">当前账号</el-tag></template></el-table-column>
      <el-table-column label="状态" width="110"><template #default="{ row }"><StatusTag :value="row.status"/></template></el-table-column>
      <el-table-column label="创建时间" min-width="180"><template #default="{ row }">{{ dateTime(row.createdAt) }}</template></el-table-column>
      <el-table-column label="操作" fixed="right" width="110"><template #default="{ row }"><el-button v-if="can('ADMIN')" type="primary" link :disabled="saving || loading" @click="edit(row)">编辑权限</el-button></template></el-table-column>
    </el-table>
    <div class="pagination"><span>共 {{ filtered.length }} 个账号</span><el-pagination v-model:current-page="filters.page" :page-size="filters.size" :total="filtered.length" :page-sizes="[10,20,50]" :disabled="loading || saving" layout="sizes, prev, pager, next" @size-change="filters.size = $event; filters.page = 1"/></div>
  </div>
  <details class="role-description permissions-reference"><summary>岗位权限说明</summary><dl><template v-for="(label, role) in roles" :key="role"><dt>{{ label }}</dt><dd>{{ roleDescriptions[role] }}</dd></template></dl></details>
  <el-dialog :model-value="dialog" :title="form.id ? '编辑管理员' : '新增管理员'" width="540px" :close-on-click-modal="false" :close-on-press-escape="!saving" :show-close="!saving" :before-close="closeDialog" @update:model-value="!$event && closeDialog()">
    <el-alert v-if="formError" :title="formError" type="error" :closable="false" show-icon/>
    <el-form label-position="top" :disabled="saving" @submit.prevent="save">
      <div class="form-grid"><el-form-item label="账号" required><el-input v-model="form.username" :disabled="!!form.id" maxlength="64" autocomplete="off" placeholder="3 至 64 位字母、数字或 _ . -"/></el-form-item><el-form-item label="显示姓名" required><el-input v-model="form.nickname" :disabled="!!form.id" maxlength="60"/></el-form-item></div>
      <el-form-item :label="form.id ? '重置密码（留空保留原密码）' : '登录密码'" :required="!form.id"><el-input v-model="form.password" type="password" show-password maxlength="128" autocomplete="new-password" placeholder="至少 10 位，包含字母和数字"/></el-form-item>
      <el-form-item label="岗位角色" required><el-select v-model="form.role"><el-option v-for="(label, role) in roles" :key="role" :label="label" :value="role"/></el-select><small class="field-help">{{ roleDescriptions[form.role] }}</small></el-form-item>
      <el-form-item v-if="form.id" label="账号状态"><el-switch v-model="form.enabled" active-text="正常" inactive-text="停用"/></el-form-item>
      <el-alert v-if="form.id" :title="Number(form.id) === Number(session.user?.id) ? '正在修改当前登录账号。保存后将退出，请使用变更后的账号信息重新登录。' : '保存后该账号现有登录会话将失效，需要重新登录。'" :type="Number(form.id) === Number(session.user?.id) ? 'warning' : 'info'" :closable="false" show-icon/>
    </el-form>
    <template #footer><el-button :disabled="saving" @click="closeDialog()">返回</el-button><el-button type="primary" :loading="saving" :disabled="!dirty" @click="save">{{ form.id ? '保存管理员' : '创建管理员' }}</el-button></template>
  </el-dialog>
</template>
<style scoped>
.permissions-reference{margin-top:24px;margin-bottom:0}.permissions-reference summary{cursor:pointer;font-size:14px;font-weight:500;color:#606266}.permissions-reference dl{margin-top:20px}
</style>
