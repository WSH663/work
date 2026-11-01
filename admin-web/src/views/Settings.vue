<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, can, errorText, post, session, type Row } from '../lib/api'
import PageHeader from '../components/PageHeader.vue'
import LoadError from '../components/LoadError.vue'
const loading = ref(false), saving = ref(false), error = ref(''), formError = ref(''), ready = ref(false), snapshot = ref('')
const form = reactive<Row>({ platformName: '', servicePhone: '', notice: '', serviceCity: '', serviceArea: '', advanceMinutes: 60, maxAdvanceDays: 30, commissionRate: 0.05, withdrawalMinimum: 10, cancelHours: 2 })
const keys = Object.keys(form)
const serialize = () => JSON.stringify(Object.fromEntries(keys.map(key => [key, form[key]])))
const dirty = computed(() => ready.value && serialize() !== snapshot.value)
const unavailable = computed(() => !ready.value || loading.value || saving.value || !can('ADMIN'))
const percent = computed(() => Number.isFinite(form.commissionRate) ? String(Number((form.commissionRate * 100).toFixed(2))) + '%' : '—')
async function confirmDiscard() {
  if (!session.token || !dirty.value) return true
  try { await ElMessageBox.confirm('当前修改尚未保存，继续后将丢弃这些修改。', '放弃未保存的修改？', { type: 'warning', confirmButtonText: '放弃修改', cancelButtonText: '继续编辑', closeOnClickModal: false }); return true }
  catch { return false }
}
async function load() {
  if (loading.value || saving.value || !await confirmDiscard()) return
  loading.value = true; ready.value = false; error.value = ''; formError.value = ''
  try { const values = await api('/admin/settings'); keys.forEach(key => { if (values[key] !== undefined) form[key] = values[key] }); snapshot.value = serialize(); ready.value = true }
  catch (err) { error.value = errorText(err) }
  finally { loading.value = false }
}
function precision(value: number, places: number) { return Math.abs(value * 10 ** places - Math.round(value * 10 ** places)) < 0.000001 }
function validate() {
  if (['platformName', 'servicePhone', 'serviceCity', 'serviceArea', 'notice'].some(key => !String(form[key] ?? '').trim())) return '请完整填写平台名称、客服电话、运营地区、服务范围和首页服务提示'
  if (!/^[\d+()\s-]{5,25}$/.test(String(form.servicePhone).trim())) return '客服电话格式不正确，请填写手机号或带区号的联系电话'
  if (['advanceMinutes', 'maxAdvanceDays', 'commissionRate', 'withdrawalMinimum', 'cancelHours'].some(key => typeof form[key] !== 'number' || !Number.isFinite(form[key]))) return '预约与财务规则必须填写有效数字，不能留空'
  if (!Number.isInteger(form.advanceMinutes) || form.advanceMinutes < 0 || form.advanceMinutes > 10080) return '至少提前预约须为 0 至 10080 分钟的整数'
  if (!Number.isInteger(form.maxAdvanceDays) || form.maxAdvanceDays < 1 || form.maxAdvanceDays > 365) return '最多提前预约须为 1 至 365 天的整数'
  if (form.advanceMinutes > form.maxAdvanceDays * 1440) return '至少提前预约不能超过最多提前预约的天数，否则用户将无法预约'
  if (form.cancelHours < 0 || form.cancelHours > 720 || !precision(form.cancelHours, 2)) return '取消时限须为 0 至 720 小时，最多保留两位小数'
  if (form.commissionRate < 0 || form.commissionRate > 0.5 || !precision(form.commissionRate, 4)) return '佣金比例须为 0 至 0.5，最多保留四位小数'
  if (form.withdrawalMinimum < 1 || form.withdrawalMinimum > 999999.99 || !precision(form.withdrawalMinimum, 2)) return '最低提现金额须为 1 至 999999.99 元，最多保留两位小数'
  return ''
}
async function save() {
  if (unavailable.value || !dirty.value) return
  formError.value = validate(); if (formError.value) return
  const payload = Object.fromEntries(keys.map(key => [key, typeof form[key] === 'string' ? form[key].trim() : form[key]]))
  saving.value = true
  try { const values = await post('/admin/settings', payload); keys.forEach(key => { if (values[key] !== undefined) form[key] = values[key] }); snapshot.value = serialize(); ElMessage.success('平台配置已保存') }
  catch (err) { formError.value = errorText(err) }
  finally { saving.value = false }
}
function beforeUnload(event: BeforeUnloadEvent) { if (session.token && (dirty.value || saving.value)) { event.preventDefault(); event.returnValue = '' } }
onBeforeRouteLeave(async () => { if (!session.token) return true; if (saving.value) { ElMessage.warning('配置正在保存，请稍候'); return false } return confirmDiscard() })
onMounted(() => { window.addEventListener('beforeunload', beforeUnload); void load() })
onBeforeUnmount(() => window.removeEventListener('beforeunload', beforeUnload))
</script>
<template>
  <PageHeader title="平台配置" subtitle="维护用户可见的信息、预约规则与推广财务参数。"><el-button :loading="loading" :disabled="saving" @click="load"><AppIcon name="Refresh"/>重新加载</el-button></PageHeader>
  <LoadError :error="error" @retry="load"/>
  <el-alert v-if="formError" class="section-alert" :title="formError" type="error" :closable="false" show-icon/>
  <div v-loading="loading" class="settings-layout">
    <el-form label-position="top" :disabled="unavailable" @submit.prevent="save">
      <section class="panel settings-panel"><div class="panel-heading"><div><h3>平台与服务信息</h3><p>展示在小程序中的平台名称、客服和服务说明</p></div></div><div class="form-content">
        <div class="form-grid"><el-form-item label="平台名称" required><el-input v-model="form.platformName" maxlength="200"/></el-form-item><el-form-item label="客服电话" required><el-input v-model="form.servicePhone" maxlength="25" placeholder="手机号或带区号的电话"/></el-form-item><el-form-item label="运营地区" required><el-input v-model="form.serviceCity" maxlength="200" placeholder="示例服务区域"/></el-form-item><el-form-item label="服务范围说明" required><el-input v-model="form.serviceArea" maxlength="200" placeholder="说明可预约的城市或区域"/></el-form-item></div>
        <el-form-item label="首页服务提示" required><el-input v-model="form.notice" type="textarea" :rows="3" maxlength="1500" show-word-limit/><small class="field-help">显示在首页进入“平台公告”的提示条，并显示于公告页顶部。公告文章在“平台公告”管理；服务说明、隐私说明及用户协议在“文档配置”分别维护。</small></el-form-item>
      </div></section>
      <section class="panel settings-panel"><div class="panel-heading"><div><h3>预约与取消</h3><p>已支付订单临近出发时，由客服协助取消</p></div></div><div class="form-content form-grid">
        <el-form-item label="至少提前预约（分钟）" required><el-input-number v-model="form.advanceMinutes" :min="0" :max="10080" :precision="0"/><small class="field-help">0 表示无需提前，最多设置 7 天。</small></el-form-item>
        <el-form-item label="最多提前预约（天）" required><el-input-number v-model="form.maxAdvanceDays" :min="1" :max="365" :precision="0"/></el-form-item>
        <el-form-item label="自助取消截止时间（距出发小时数）" required><el-input-number v-model="form.cancelHours" :min="0" :max="720" :precision="2" :step="0.5"/><small class="field-help">只限制已支付订单；未支付订单可直接取消。</small></el-form-item>
      </div></section>
      <section class="panel settings-panel"><div class="panel-heading"><div><h3>佣金与提现</h3><p>新订单保存当时的佣金比例，调整不会改变已有订单</p></div></div><div class="form-content">
        <div class="settlement-rule"><strong>订单完成 30 日后结算</strong><p>等待期间佣金不可提现；订单退款后撤销待结算佣金。符合条件的佣金到期后自动转入可提现余额。</p></div>
        <div class="form-grid"><el-form-item label="佣金比例（0 至 0.5）" required><el-input-number v-model="form.commissionRate" :min="0" :max="0.5" :step="0.01" :precision="4"/><small class="field-help">当前为订单实付金额的 {{ percent }}，最多设置 50%。</small></el-form-item><el-form-item label="最低提现金额（元）" required><el-input-number v-model="form.withdrawalMinimum" :min="1" :max="999999.99" :precision="2"/><small class="field-help">达到门槛且有足够可提现余额时，用户可提交申请。</small></el-form-item></div>
      </div></section>
    </el-form>
    <div class="settings-actions"><span :class="{ changed: dirty }">{{ dirty ? '有未保存的修改' : ready ? '配置已加载' : '请先成功加载配置' }}</span><el-button type="primary" :loading="saving" :disabled="unavailable || !dirty" @click="save">保存配置</el-button></div>
  </div>
</template>
<style scoped>
.settlement-rule{margin-bottom:24px;padding-bottom:20px;border-bottom:1px solid #ebeef5}.settlement-rule strong{font-size:14px;font-weight:500;color:#303133}.settlement-rule p{margin:8px 0 0;color:#606266;font-size:13px;line-height:22px}.settings-actions{position:sticky;bottom:0;z-index:5;display:flex;align-items:center;justify-content:space-between;gap:16px;padding:16px 20px;border:1px solid #e4e7ed;background:#fff}.settings-actions>span{font-size:13px;color:#909399}.settings-actions>.changed{color:#9a6700}
</style>
