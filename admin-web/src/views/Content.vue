<script setup lang="ts">
import { ref, reactive, onMounted, onBeforeUnmount, computed, watch } from 'vue'
import { useRoute, useRouter, onBeforeRouteLeave } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, post, patch, errorText, can, session, type Row } from '../lib/api'
import { adKinds, informationKinds, contentDefinitions, currentPopup, orderedContent, contentStatus, isAdvertisement, type ContentKind } from '../lib/content-management'
import PageHeader from '../components/PageHeader.vue'
import LoadError from '../components/LoadError.vue'
import ContentPreview from '../components/ContentPreview.vue'
const route = useRoute(), router = useRouter()
const advertising = computed(() => route.path === '/content')
const availableKinds = computed(() => advertising.value ? adKinds : informationKinds)
function routeKind(): ContentKind { const value = String(route.query.kind || '') as ContentKind; return availableKinds.value.includes(value) ? value : availableKinds.value[0]! }
const kind = ref<ContentKind>(routeKind())
const definition = computed(() => contentDefinitions[kind.value])
const linkOptions = [
  { label: '首页', value: '/pages/index/index' }, { label: '预约用车', value: '/pages/booking/index' },
  { label: '预约包车', value: '/pages/booking/index?type=CHARTER' }, { label: '我的订单', value: '/pages/orders/index' },
  { label: '个人中心', value: '/pages/profile/index' }, { label: '推广与佣金', value: '/pages/promotion/index' },
  { label: '消息通知', value: '/pages/messages/index' }, { label: '服务说明', value: '/pages/agreement/index?kind=service' },
  { label: '平台公告', value: '/pages/agreement/index?kind=notice' }, { label: '用户协议', value: '/pages/agreement/index?kind=terms' },
  { label: '隐私政策', value: '/pages/agreement/index?kind=privacy' },
]
const supportedBaseLinks = ['/pages/agreement/index', '/pages/booking/index?type=RIDE']
const rows = ref<Row[]>([]), publicInfo = ref<Row | null>(null), infoError = ref('')
const loading = ref(false), saving = ref(false), pendingId = ref<number | null>(null), error = ref(''), formError = ref(''), dialog = ref(false), preview = ref<Row | null>(null)
const filters = reactive({ keyword: '', status: '', page: 1, size: 10 }), applied = reactive({ keyword: '', status: '' })
const categoryRows = computed(() => orderedContent(rows.value.filter(row => row.kind === kind.value)))
const publishedCount = computed(() => categoryRows.value.filter(row => row.enabled).length)
const popup = computed(() => currentPopup(rows.value))
const filteredRows = computed(() => categoryRows.value.filter(row => (!applied.keyword || [row.title, row.body].some(value => String(value || '').toLowerCase().includes(applied.keyword.toLowerCase()))) && (!applied.status || Boolean(row.enabled) === (applied.status === 'enabled'))))
const visibleRows = computed(() => filteredRows.value.slice((filters.page - 1) * filters.size, filters.page * filters.size))
const defaults = { kind: 'AD', title: '', body: '', mediaUrl: '', mediaType: 'TEXT', posterUrl: '', linkUrl: '', actionText: '', sortOrder: 0, enabled: false, skipSeconds: 0 }
const form = reactive<Row>({ ...defaults }), fieldErrors = reactive<Record<string, string>>({}), closing = ref(false)
const formDefinition = computed(() => contentDefinitions[form.kind as ContentKind])
const formAd = computed(() => isAdvertisement(form.kind))
const busy = computed(() => saving.value || pendingId.value !== null)
const previewFromDraft = ref(false)
let requestVersion = 0, baseline = '', originalEnabled = false
function showPreview(row: Row, fromDraft = false) { if (busy.value) return; previewFromDraft.value = fromDraft; preview.value = { ...row } }
function closePreview(done?: () => void) { if (busy.value) return; preview.value = null; if (done) done() }
function editPreview() { if (!preview.value || busy.value) return; const row = { ...preview.value }; preview.value = null; edit(row) }
function payload() { return Object.fromEntries(Object.keys(defaults).map(key => [key, (key === 'mediaUrl' && form.mediaType === 'TEXT') || (key === 'posterUrl' && form.mediaType !== 'VIDEO') || (key === 'actionText' && (!form.linkUrl || !formAd.value)) ? '' : typeof form[key] === 'string' ? form[key].trim() : form[key]])) }
function clearErrors() { Object.keys(fieldErrors).forEach(key => delete fieldErrors[key]); formError.value = '' }
function clampPage() { filters.page = Math.max(1, Math.min(filters.page, Math.ceil(filteredRows.value.length / filters.size))) }
function search() { Object.assign(applied, { keyword: filters.keyword.trim(), status: filters.status }); filters.page = 1 }
function reset() { filters.keyword = ''; filters.status = ''; search() }
async function load() {
  const version = ++requestVersion; loading.value = true; error.value = ''; infoError.value = ''
  const [contents, context] = await Promise.allSettled([api<Row[]>('/admin/content'), api<Row>('/public/home')])
  if (version !== requestVersion) return
  if (contents.status === 'fulfilled') { rows.value = contents.value; clampPage() } else error.value = errorText(contents.reason)
  if (context.status === 'fulfilled') publicInfo.value = context.value; else infoError.value = errorText(context.reason)
  loading.value = false
}
function edit(row?: Row) {
  if (busy.value || !can('ADMIN', 'OPERATOR')) return
  Object.keys(form).forEach(key => delete form[key]); Object.assign(form, defaults, { kind: kind.value }, row || {})
  originalEnabled = Boolean(form.enabled); baseline = JSON.stringify(payload()); clearErrors(); dialog.value = true
}
function duplicate(row: Row) {
  const copy = { ...row }; delete copy.id
  copy.kind = row.kind === 'AD' ? 'BANNER' : 'AD'; copy.enabled = false; copy.skipSeconds = 0; copy.sortOrder = 0
  edit(copy)
}
async function closeEditor(done?: () => void) {
  if (!dialog.value) { done?.(); return } if (saving.value || closing.value) return
  closing.value = true
  try { if (baseline !== JSON.stringify(payload())) await ElMessageBox.confirm('当前修改尚未保存，确认放弃？', '放弃修改', { confirmButtonText: '放弃修改', cancelButtonText: '继续编辑', type: 'warning' }); dialog.value = false; done?.() }
  catch { /* Retain the draft. */ } finally { closing.value = false }
}
function linkName(link: string) { return linkOptions.find(option => option.value === link)?.label || (link === '/pages/agreement/index' ? '服务说明' : link === '/pages/booking/index?type=RIDE' ? '预约用车' : link ? '已配置站内页面' : '未配置') }
function validMedia(value: string) { try { const url = new URL(value); return url.protocol === 'https:' && !url.username && !url.password && !url.hash } catch { return false } }
function publicationNote(row: Row) {
  if (row.kind === 'AD') return '弹窗按优先级仅展示第一条已上线内容，其余为备用；本操作不会自动下线其他弹窗。'
  return isAdvertisement(row.kind) ? '上线后展示在首页“活动推荐”中。' : '发布后展示在平台公告列表，不会替换首页服务提示。'
}
function publicationLabel(row: Row, enable: boolean) { return isAdvertisement(row.kind) ? enable ? '上线广告' : '下线广告' : enable ? '发布内容' : '撤下内容' }
async function save() {
  if (busy.value || closing.value || !can('ADMIN', 'OPERATOR')) return
  clearErrors()
  for (const field of ['title', 'body', 'mediaUrl', 'posterUrl', 'actionText']) form[field] = String(form[field] || '').trim()
  if (!form.title) fieldErrors.title = '请填写标题'
  if (form.mediaType !== 'TEXT' && !validMedia(form.mediaUrl)) fieldErrors.mediaUrl = '请填写有效的 HTTPS 图片或视频链接'
  if (form.mediaType === 'VIDEO' && form.posterUrl && !validMedia(form.posterUrl)) fieldErrors.posterUrl = '封面须为有效的 HTTPS 图片链接'
  if (form.mediaType === 'VIDEO' && form.posterUrl.length > 1000) fieldErrors.posterUrl = '封面链接不能超过 1000 个字符'
  if (formAd.value && form.linkUrl && form.actionText.length > 12) fieldErrors.actionText = '按钮文案不能超过 12 个字符'
  if (form.mediaType === 'TEXT' && !form.body) fieldErrors.body = '请填写文字正文'
  if (form.linkUrl && !linkOptions.some(option => option.value === form.linkUrl) && !supportedBaseLinks.includes(form.linkUrl)) fieldErrors.linkUrl = '请选择支持的小程序站内页面'
  if (!Number.isInteger(form.sortOrder) || form.sortOrder < 0 || form.sortOrder > 9999) fieldErrors.sortOrder = '排序须为 0 至 9999 的整数'
  if (!Number.isInteger(form.skipSeconds) || form.skipSeconds < 0 || form.skipSeconds > 15) fieldErrors.skipSeconds = '提示秒数须为 0 至 15 的整数'
  if (Object.keys(fieldErrors).length) return
  const id = form.id, body = payload(), statusChanged = Boolean(id) && originalEnabled !== Boolean(body.enabled)
  saving.value = true
  try {
    if (statusChanged || (!id && body.enabled)) await ElMessageBox.confirm(body.enabled ? publicationNote(body) : '保存后用户将无法再看到此条内容。', publicationLabel(body, Boolean(body.enabled)), { confirmButtonText: '确认保存', cancelButtonText: '返回修改', type: 'warning', closeOnClickModal: false })
    if (id) await patch('/admin/content/' + id, body); else await post('/admin/content', body)
    kind.value = body.kind as ContentKind; baseline = JSON.stringify(payload()); dialog.value = false; ElMessage.success(formAd.value ? '广告已保存' : '内容已保存'); await load()
  } catch (err) { if (err !== 'cancel' && err !== 'close') formError.value = errorText(err) } finally { saving.value = false }
}
async function toggle(row: Row) {
  if (busy.value || !can('ADMIN', 'OPERATOR')) return
  const id = Number(row.id), enabled = !row.enabled; pendingId.value = id
  try {
    await ElMessageBox.confirm(`“${row.title}”${enabled ? '：' + publicationNote(row) : '将停止向用户展示。'}`, publicationLabel(row, enabled), { confirmButtonText: enabled ? '确认发布' : '确认撤下', cancelButtonText: '返回', type: 'warning', closeOnClickModal: false })
    const updated = await patch('/admin/content/' + id, { enabled })
    requestVersion++; rows.value = rows.value.map(item => Number(item.id) === id ? updated : item)
    if (Number(preview.value?.id) === id) preview.value = { ...updated }
    await load()
    ElMessage.success(enabled ? '已发布' : '已撤下')
  } catch (err) { if (err !== 'cancel' && err !== 'close') ElMessage.error(errorText(err)) } finally { pendingId.value = null }
}
async function remove(row: Row) {
  if (busy.value || !can('ADMIN', 'OPERATOR')) return
  const id = Number(row.id); pendingId.value = id
  try {
    const successor = row.kind === 'AD' && Number(popup.value?.id) === id ? '若有已上线的备用内容，删除后将按优先级接替。' : ''
    await ElMessageBox.confirm(`确认删除“${row.title}”？删除无法恢复。${successor}`, '删除内容', { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '返回', closeOnClickModal: false })
    await api('/admin/content/' + id, { method: 'DELETE' }); rows.value = rows.value.filter(item => Number(item.id) !== id)
    if (Number(preview.value?.id) === id) preview.value = null
    ElMessage.success('内容已删除'); await load()
  } catch (err) { if (err !== 'cancel' && err !== 'close') ElMessage.error(errorText(err)) } finally { pendingId.value = null }
}
function command(action: string, row: Row) { if (action === 'toggle') void toggle(row); else if (action === 'copy') duplicate(row); else if (action === 'delete') void remove(row) }
watch(() => route.query.kind, () => { kind.value = routeKind() })
watch(kind, value => { reset(); if (route.query.kind !== value) void router.replace({ path: route.path, query: { kind: value } }) })
watch(() => form.mediaType, () => { delete fieldErrors.body; delete fieldErrors.mediaUrl; delete fieldErrors.posterUrl })
watch(() => form.linkUrl, () => { delete fieldErrors.linkUrl; delete fieldErrors.actionText })
onBeforeRouteLeave(async () => { if (!session.token) return true; if (busy.value) { ElMessage.warning('操作正在提交，请稍候'); return false }; await closeEditor(); return !dialog.value })
onMounted(load)
onBeforeUnmount(() => { requestVersion++ })
</script>
<template>
  <PageHeader :title="advertising ? '首页广告' : '平台公告'" :subtitle="advertising ? '管理首页两处推广展示，预览后再上线。' : '维护服务调整、预约安排与平台通知。服务说明、隐私说明和用户协议在“文档配置”分别维护。'"><el-button :loading="loading" :disabled="busy" @click="load"><AppIcon name="Refresh"/>刷新</el-button><el-button v-if="can('ADMIN', 'OPERATOR')" type="primary" :disabled="busy || loading" @click="edit()"><AppIcon name="Plus"/>{{ definition.create }}</el-button></PageHeader>
  <LoadError :error="error" @retry="load"/>
  <div class="panel">
    <el-tabs v-model="kind" class="content-tabs"><el-tab-pane v-for="key in availableKinds" :key="key" :name="key" :label="contentDefinitions[key].label"/></el-tabs>
    <section class="content-context">
      <div class="placement-heading"><strong>{{ definition.location }}</strong><span v-if="!loading && !error" class="content-count">共 {{ categoryRows.length }} 条 · {{ advertising ? '已上线' : '已发布' }} {{ publishedCount }} 条</span></div>
      <p>{{ definition.purpose }}</p><p class="context-secondary">{{ definition.examples }}</p>
      <p v-if="kind === 'AD' && !loading && !error" class="current-popup"><strong>{{ popup ? '当前展示：' : '当前无上线弹窗' }}</strong>{{ popup?.title }}<template v-if="popup">。其余 {{ Math.max(0, publishedCount - 1) }} 条为备用，不会依次弹出。下线当前内容后，下一条按优先级接替。</template></p>
    </section>
    <section v-if="!advertising" class="content-source">
      <div v-if="infoError" class="source-error">前台展示信息加载失败：{{ infoError }}<el-button link type="primary" :disabled="loading || busy" @click="load">重试</el-button></div>
      <template v-else><div><strong>首页服务提示</strong><p>{{ loading && !publicInfo ? '加载中…' : publicInfo?.notice || '当前未配置' }}</p><small>这是首页公告入口的一句常驻提示，与下方公告文章分别维护。</small></div><el-button v-if="can('ADMIN')" @click="router.push('/settings')">编辑首页提示</el-button><span v-else class="context-secondary">由管理员在平台配置中维护</span></template>
    </section>
    <div class="filter-bar"><el-input v-model="filters.keyword" placeholder="搜索标题 / 正文" clearable class="search-input" @keyup.enter="search"/><el-select v-model="filters.status" :placeholder="advertising ? '全部投放状态' : '全部发布状态'" clearable><el-option :label="advertising ? '已上线（含备用）' : '已发布'" value="enabled"/><el-option :label="advertising ? '已下线' : '未发布'" value="disabled"/></el-select><el-button type="primary" @click="search">查询</el-button><el-button @click="reset">重置</el-button></div>
    <el-table v-loading="loading" :data="visibleRows" :empty-text="error ? '加载失败，请重试' : '暂无符合条件的' + definition.label + '，可调整筛选或新增内容'" row-key="id">
      <el-table-column :label="advertising ? '广告内容' : '标题与摘要'" min-width="320"><template #default="{ row }"><div class="media-cell"><div v-if="row.mediaType !== 'TEXT'" :class="['media-thumbnail', 'media-thumbnail-' + row.kind.toLowerCase()]"><el-image v-if="row.mediaType === 'IMAGE' || row.posterUrl" :src="row.mediaType === 'IMAGE' ? row.mediaUrl : row.posterUrl" fit="contain" :preview-src-list="[row.mediaType === 'IMAGE' ? row.mediaUrl : row.posterUrl]" preview-teleported><template #error><span class="media-empty">素材加载失败</span></template></el-image><div v-else class="video-thumbnail-label"><AppIcon name="Picture" :size="20"/><span>视频素材</span></div></div><div><strong>{{ row.title }}</strong><div v-if="row.body" class="media-text-preview">{{ row.body }}</div><div class="media-meta">{{ ({ TEXT:'文字', IMAGE:'图片', VIDEO:'视频' } as Record<string, string>)[row.mediaType] }} · 编号 {{ row.id }}<template v-if="advertising && row.linkUrl"> · {{ row.actionText || '查看详情' }}</template></div></div></div></template></el-table-column>
      <el-table-column :label="advertising ? '按钮去向' : '相关页面'" min-width="120"><template #default="{ row }">{{ row.linkUrl ? linkName(row.linkUrl) : advertising ? '仅查看内容' : '无' }}</template></el-table-column>
      <el-table-column prop="sortOrder" :label="kind === 'AD' ? '优先级' : '显示顺序'" width="100"/>
      <el-table-column :label="advertising ? '实际展示状态' : '发布状态'" width="145"><template #default="{ row }"><el-tag :type="!row.enabled ? 'info' : row.kind === 'AD' && Number(popup?.id) !== Number(row.id) ? 'warning' : 'success'" effect="plain">{{ contentStatus(row, rows) }}</el-tag></template></el-table-column>
      <el-table-column label="操作" fixed="right" width="205"><template #default="{ row }"><div class="table-actions"><el-button link type="primary" :disabled="busy" @click="showPreview(row)">预览</el-button><template v-if="can('ADMIN', 'OPERATOR')"><el-button link type="primary" :disabled="busy" @click="edit(row)">编辑</el-button><el-dropdown trigger="click" :disabled="busy" @command="action => command(action, row)"><el-button link type="primary" :disabled="busy">更多<AppIcon name="ArrowDown" :size="12"/></el-button><template #dropdown><el-dropdown-menu><el-dropdown-item command="toggle">{{ publicationLabel(row, !row.enabled) }}</el-dropdown-item><el-dropdown-item v-if="advertising" command="copy">{{ row.kind === 'AD' ? '复制到首页推荐位' : '复制为首页弹窗' }}</el-dropdown-item><el-dropdown-item command="delete" divided>删除内容</el-dropdown-item></el-dropdown-menu></template></el-dropdown></template></div></template></el-table-column>
    </el-table>
    <div class="pagination"><span>筛选结果 {{ filteredRows.length }} 条</span><el-pagination v-model:current-page="filters.page" v-model:page-size="filters.size" :total="filteredRows.length" :page-sizes="[10, 20, 50]" layout="sizes, prev, pager, next" @size-change="filters.page = 1"/></div>
  </div>
  <el-dialog :model-value="dialog" :title="(form.id ? '编辑' : '新增') + (form.kind === 'SERVICE' ? '说明内容' : formDefinition.label)" width="1120px" class="content-editor-dialog" :close-on-click-modal="false" :close-on-press-escape="!saving" :show-close="!saving" :before-close="closeEditor">
    <el-alert v-if="formError" class="section-alert" :title="formError" type="error" :closable="false" show-icon/>
    <div class="content-editor-layout">
      <el-form class="content-editor-form" label-position="top" :disabled="saving" @submit.prevent="save">
        <h3 class="editor-section">{{ formAd ? '广告素材与文案' : form.kind === 'SERVICE' ? '服务说明页的补充内容' : '文章内容' }}</h3>
        <div class="editor-location"><strong>{{ formDefinition.label }}</strong><span>{{ formDefinition.location }}</span></div>
        <p class="placement-guidance">{{ formDefinition.size }}</p>
        <el-form-item label="内容形式" required><el-radio-group v-model="form.mediaType"><el-radio value="TEXT">文字</el-radio><el-radio value="IMAGE">图片</el-radio><el-radio value="VIDEO">视频</el-radio></el-radio-group></el-form-item>
        <el-form-item label="内容标题" required :error="fieldErrors.title"><el-input v-model="form.title" maxlength="120" show-word-limit :placeholder="formDefinition.titleHint" @input="delete fieldErrors.title"/></el-form-item>
        <el-form-item label="正文说明" :required="form.mediaType === 'TEXT'" :error="fieldErrors.body"><el-input v-model="form.body" type="textarea" :rows="5" maxlength="3000" show-word-limit :placeholder="formDefinition.bodyHint" @input="delete fieldErrors.body"/><small class="field-help">{{ formDefinition.bodyHint }}</small></el-form-item>
        <el-form-item v-if="form.mediaType !== 'TEXT'" :label="form.mediaType === 'VIDEO' ? '视频链接' : '图片链接'" required :error="fieldErrors.mediaUrl"><el-input v-model="form.mediaUrl" maxlength="1000" placeholder="https://example.com/media/..." clearable @input="delete fieldErrors.mediaUrl"/><small class="field-help">填写公开可访问的 HTTPS 素材链接，当前未接入文件上传。微信端需配置对应资源域名。</small></el-form-item>
        <el-form-item v-if="form.mediaType === 'VIDEO'" label="视频封面" :error="fieldErrors.posterUrl"><el-input v-model="form.posterUrl" maxlength="1000" placeholder="可选，HTTPS 图片链接" clearable @input="delete fieldErrors.posterUrl"/><small class="field-help">先展示封面，用户点击播放；不自动播放或出声。</small></el-form-item>
        <h3 class="editor-section">{{ formAd ? '点击与投放' : '关联与发布' }}</h3>
        <el-form-item :label="formAd ? '点击跳转页面' : '相关页面（选填）'" :error="fieldErrors.linkUrl"><el-select v-model="form.linkUrl" clearable placeholder="不配置"><el-option v-for="option in linkOptions" :key="option.value" :label="option.label" :value="option.value"/><el-option v-if="supportedBaseLinks.includes(form.linkUrl)" :label="linkName(form.linkUrl)" :value="form.linkUrl"/></el-select><small class="field-help">{{ formAd ? '配置后显示跳转按钮；不配置时仅阅读内容。首页推荐仍可打开全文，视频点击只播放。' : '配置后在正文下显示“查看相关页面”文字链接；不配置则不显示。只支持小程序站内页面。' }}</small></el-form-item>
        <el-form-item v-if="formAd && form.linkUrl" label="按钮文案" :error="fieldErrors.actionText"><el-input v-model="form.actionText" maxlength="12" show-word-limit placeholder="默认：查看详情" clearable @input="delete fieldErrors.actionText"/></el-form-item>
        <div class="form-grid"><el-form-item :label="form.kind === 'AD' ? '展示优先级' : '显示顺序'" :error="fieldErrors.sortOrder"><el-input-number v-model="form.sortOrder" :min="0" :max="9999" :precision="0" @change="delete fieldErrors.sortOrder"/><small class="field-help">{{ formDefinition.sortHint }}</small></el-form-item><el-form-item v-if="form.kind === 'AD'" label="倒计时提示（秒）" :error="fieldErrors.skipSeconds"><el-input-number v-model="form.skipSeconds" :min="0" :max="15" :precision="0"/><small class="field-help">0 不显示提示；可随时关闭，不自动关闭或跳转。</small></el-form-item></div>
        <el-form-item :label="formAd ? '投放状态' : '发布状态'"><el-radio-group v-model="form.enabled"><el-radio :value="false">{{ formAd ? '下线' : '未发布' }}</el-radio><el-radio :value="true">{{ formAd ? '上线' : '发布' }}</el-radio></el-radio-group><small class="field-help">新增默认{{ formAd ? '下线' : '未发布' }}，检查预览后再{{ formAd ? '上线' : '发布' }}。{{ form.kind === 'AD' ? '上线后按优先级决定当前展示或备用。' : '' }}</small></el-form-item>
      </el-form>
      <aside class="content-editor-preview"><div class="preview-heading"><strong>手机端实时预览</strong><span>{{ formDefinition.label }}</span></div><ContentPreview v-if="dialog" :content="form"/><el-button class="expand-preview" :disabled="!form.title.trim() || saving" @click="showPreview(form, true)">单独预览</el-button></aside>
    </div>
    <template #footer><el-button :disabled="saving || closing" @click="closeEditor()">返回</el-button><el-button type="primary" :loading="saving" :disabled="closing" @click="save">{{ formAd ? '保存广告' : '保存内容' }}</el-button></template>
  </el-dialog>
  <el-dialog :model-value="!!preview" title="手机端内容预览" width="470px" align-center :close-on-click-modal="!busy" :close-on-press-escape="!busy" :show-close="!busy" :before-close="closePreview">
    <template v-if="preview"><p class="muted preview-meta">{{ contentDefinitions[preview.kind as ContentKind].label }} · {{ previewFromDraft ? '未保存预览' : contentStatus(preview, rows) }} · {{ linkName(preview.linkUrl) }}</p><ContentPreview :content="preview"/></template>
    <template #footer><el-button :disabled="busy" @click="closePreview()">{{ previewFromDraft ? '返回编辑' : '关闭' }}</el-button><template v-if="preview && !previewFromDraft && can('ADMIN', 'OPERATOR')"><el-button :disabled="busy" @click="editPreview">编辑内容</el-button><el-button type="primary" :loading="pendingId === Number(preview.id)" :disabled="busy" @click="toggle(preview)">{{ publicationLabel(preview, !preview.enabled) }}</el-button></template></template>
  </el-dialog>
</template>
<style scoped>
.content-context{padding:20px 24px 16px;border-bottom:1px solid #ebeef5}.placement-heading{display:flex;align-items:center;justify-content:space-between;gap:16px}.placement-heading strong{font-size:15px;font-weight:600}.content-count{font-size:13px;color:#69756b;white-space:nowrap}.content-context p{font-size:13px;line-height:22px;margin:8px 0 0;color:#4d5a51}.content-context p.context-secondary,.context-secondary{color:#7d8780;font-size:12px;line-height:20px}.current-popup{padding-top:8px}.current-popup strong{font-weight:500;color:#256142}.content-source{display:flex;align-items:center;justify-content:space-between;gap:20px;padding:16px 24px;background:#fafbfa;border-bottom:1px solid #ebeef5}.content-source>div:first-child{flex:1;min-width:0}.content-source strong{font-size:13px;font-weight:600}.content-source p{font-size:13px;line-height:22px;margin:8px 0;color:#4d5a51;overflow-wrap:anywhere;white-space:pre-wrap}.content-source small{font-size:12px;color:#7d8780;line-height:20px}.source-actions{display:flex;gap:8px;flex-wrap:wrap;flex-shrink:0}.source-actions .el-button+.el-button{margin-left:0}.source-error{font-size:13px;line-height:22px;color:#9a6700}.source-error .el-button{margin-left:12px}.editor-location{display:flex;flex-direction:column;gap:6px;font-size:14px;margin-bottom:16px}.editor-location span{font-size:12px;color:#69756b}.media-cell{min-width:0}.media-cell>div:last-child{min-width:0}.media-cell strong{display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;overflow-wrap:anywhere}.media-text-preview{overflow-wrap:anywhere;max-height:44px}.media-thumbnail-ad,.media-thumbnail-notice,.media-thumbnail-service{width:80px;height:60px}.media-thumbnail-banner{width:120px;height:50px}.video-thumbnail-label{display:flex;align-items:center;justify-content:center;flex-direction:column;gap:4px;font-size:11px;color:#8a938d}.editor-section{font-size:15px;font-weight:600;margin:0 0 18px;padding-top:8px}.editor-section:not(:first-child){margin-top:12px;border-top:1px solid var(--el-border-color-lighter);padding-top:20px}.form-grid :deep(.el-input-number){width:100%}.content-editor-layout{display:grid;grid-template-columns:minmax(0,1fr) 375px;gap:32px;align-items:start}.content-editor-form{min-width:0}.content-editor-preview{position:sticky;top:0;min-width:0}.preview-heading{display:flex;justify-content:space-between;gap:8px;align-items:center;margin-bottom:16px;font-size:14px}.preview-heading span{font-size:12px;color:#7b857e}.placement-guidance{font-size:12px;line-height:20px;color:#69756b;background:#f5f7f5;padding:12px;margin:0 0 20px}.expand-preview{width:100%;margin-top:16px}.preview-meta{line-height:22px;margin-bottom:16px}
.content-list-heading{display:flex;align-items:baseline;gap:12px;flex-wrap:wrap;padding:20px 20px 0}.content-list-heading strong{font-size:14px;font-weight:600}.content-list-heading span{font-size:12px;line-height:20px;color:#69756b}
@media(max-width:1050px){.content-editor-layout{grid-template-columns:minmax(0,1fr) 320px;gap:24px}.content-editor-preview :deep(.phone-viewport){min-height:500px}}
@media(max-width:800px){.content-context{padding:16px}.placement-heading{align-items:flex-start;flex-direction:column;gap:8px}.content-source{padding:16px;align-items:flex-start;flex-direction:column;gap:12px}.content-editor-layout{grid-template-columns:1fr}.content-editor-preview{position:static;border-top:1px solid #ebeef5;padding-top:20px}.content-editor-form .form-grid{grid-template-columns:1fr}.preview-heading{max-width:375px;margin-left:auto;margin-right:auto}.expand-preview{display:block;width:375px;max-width:100%;margin-left:auto;margin-right:auto}}
</style>
