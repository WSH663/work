<script setup lang="ts">
import { reactive, ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, patch, query, errorText, money, dateTime, can, type Row, type Page } from '../lib/api'
import PageHeader from '../components/PageHeader.vue'
import LoadError from '../components/LoadError.vue'
import StatusTag from '../components/StatusTag.vue'
const filters = reactive({ keyword: '', page: 1, size: 10 }), rows = ref<Row[]>([]), total = ref(0), loading = ref(false), error = ref(''), saving = ref(false), selected = ref<Row | null>(null)
let requestVersion = 0
async function load() {
  const version = ++requestVersion; loading.value = true; error.value = ''
  try { const result = await api<Page>(`/admin/users?${query({ ...filters })}`); if (version !== requestVersion) return; rows.value = result.items; total.value = result.total }
  catch (err) { if (version === requestVersion) error.value = errorText(err) }
  finally { if (version === requestVersion) loading.value = false }
}
function search() { filters.keyword = filters.keyword.trim(); filters.page = 1; rows.value = []; total.value = 0; void load() }
function reset() { filters.keyword = ''; search() }
function resizePage() { filters.page = 1; void load() }
function closeDetail(done?: () => void) { if (saving.value) return; selected.value = null; if (done) done() }
async function toggle(row: Row) {
  if (saving.value || !can('ADMIN', 'OPERATOR')) return
  const id = Number(row.id), disabled = row.status !== 'DISABLED', nickname = row.nickname || `用户 ${id}`
  saving.value = true
  try {
    await ElMessageBox.confirm(disabled ? `确认停用“${nickname}”？该用户的现有登录将失效，停用期间不能使用平台。` : `确认恢复“${nickname}”的账号使用权限？`, disabled ? '停用账号' : '恢复账号', { confirmButtonText: disabled ? '确认停用' : '确认恢复', cancelButtonText: '返回', type: 'warning', closeOnClickModal: false })
    const updated = await patch(`/admin/users/${id}`, { status: disabled ? 'DISABLED' : 'ACTIVE' })
    if (Number(selected.value?.id) === id) selected.value = { ...selected.value, ...updated }
    rows.value = rows.value.map(item => Number(item.id) === id ? { ...item, ...updated } : item)
    ElMessage.success('账号状态已更新'); await load()
  } catch (err) { if (err !== 'cancel' && err !== 'close') ElMessage.error(errorText(err)) }
  finally { saving.value = false }
}
onMounted(load)
</script>
<template>
  <PageHeader title="用户管理" subtitle="查看用户资料、账号状态与推广关系。"><el-button :loading="loading" :disabled="saving" @click="load"><AppIcon name="Refresh"/>刷新</el-button></PageHeader>
  <LoadError :error="error" @retry="load"/>
  <div class="panel">
    <div class="filter-bar"><el-input v-model="filters.keyword" placeholder="昵称 / 手机号 / 推广码" clearable class="search-input" @keyup.enter="search"/><el-button type="primary" @click="search"><AppIcon name="Search"/>查询</el-button><el-button @click="reset">重置</el-button></div>
    <el-table v-loading="loading" :data="rows" :empty-text="error ? '用户加载失败，请重试' : '暂无符合条件的用户'" row-key="id">
      <el-table-column prop="id" label="用户 ID" width="90"/>
      <el-table-column label="用户资料" min-width="200"><template #default="{ row }"><button class="table-link user-name" :disabled="saving" @click="selected = { ...row }">{{ row.nickname || '未设置昵称' }}</button><div class="cell-sub">{{ row.phone || '未绑定手机号' }}</div></template></el-table-column>
      <el-table-column prop="inviteCode" label="专属推广码" min-width="135" show-overflow-tooltip/>
      <el-table-column label="邀请人" min-width="130" show-overflow-tooltip><template #default="{ row }">{{ row.inviterName || row.inviterNickname || (row.referrerId ? `用户 ${row.referrerId}` : '无邀请人') }}</template></el-table-column>
      <el-table-column label="可用佣金" min-width="125" align="right"><template #default="{ row }">{{ money(row.availableAmount ?? row.balance) }}</template></el-table-column>
      <el-table-column label="账号状态" width="115"><template #default="{ row }"><StatusTag :value="row.status"/></template></el-table-column>
      <el-table-column label="注册时间" min-width="175"><template #default="{ row }">{{ dateTime(row.createdAt) }}</template></el-table-column>
      <el-table-column label="操作" width="160" fixed="right"><template #default="{ row }"><div class="table-actions"><el-button type="primary" link :disabled="saving" @click="selected = { ...row }">详情</el-button><el-button v-if="can('ADMIN', 'OPERATOR')" :type="row.status === 'DISABLED' ? 'primary' : 'danger'" link :disabled="saving" @click="toggle(row)">{{ row.status === 'DISABLED' ? '恢复账号' : '停用账号' }}</el-button></div></template></el-table-column>
    </el-table>
    <div class="pagination"><span>共 {{ total }} 位用户</span><el-pagination v-model:current-page="filters.page" v-model:page-size="filters.size" :total="total" :page-sizes="[10, 20, 50]" layout="sizes, prev, pager, next" @current-change="load" @size-change="resizePage"/></div>
  </div>
  <el-dialog :model-value="!!selected" title="用户资料与推广关系" width="580px" :close-on-click-modal="!saving" :close-on-press-escape="!saving" :show-close="!saving" :before-close="closeDetail" @update:model-value="!$event && closeDetail()">
    <template v-if="selected">
      <h3 class="detail-section">基本资料</h3>
      <el-descriptions :column="1" border><el-descriptions-item label="用户 ID">{{ selected.id }}</el-descriptions-item><el-descriptions-item label="昵称"><span class="detail-value">{{ selected.nickname || '未设置昵称' }}</span></el-descriptions-item><el-descriptions-item label="手机号码">{{ selected.phone || '未绑定手机号' }}</el-descriptions-item><el-descriptions-item label="状态"><StatusTag :value="selected.status"/></el-descriptions-item><el-descriptions-item label="注册时间">{{ dateTime(selected.createdAt) }}</el-descriptions-item></el-descriptions>
      <h3 class="detail-section">推广关系与佣金</h3>
      <el-descriptions :column="1" border><el-descriptions-item label="推广码">{{ selected.inviteCode || '—' }}</el-descriptions-item><el-descriptions-item label="邀请人 ID">{{ selected.referrerId || '无邀请人' }}</el-descriptions-item><el-descriptions-item label="可用佣金">{{ money(selected.availableAmount ?? selected.balance) }}</el-descriptions-item><el-descriptions-item label="冻结佣金">{{ money(selected.frozenAmount) }}</el-descriptions-item></el-descriptions>
    </template>
    <template #footer><el-button :disabled="saving" @click="closeDetail()">关闭</el-button><el-button v-if="selected && can('ADMIN', 'OPERATOR')" :type="selected.status === 'DISABLED' ? 'primary' : 'danger'" :loading="saving" @click="toggle(selected)">{{ selected.status === 'DISABLED' ? '恢复账号' : '停用账号' }}</el-button></template>
  </el-dialog>
</template>
<style scoped>
.user-name{font-weight:600;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;overflow-wrap:anywhere}.detail-section{font-size:15px;font-weight:600;margin:0 0 14px}.detail-section:not(:first-child){margin-top:24px}.detail-value{overflow-wrap:anywhere;white-space:pre-wrap}
</style>
