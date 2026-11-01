<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, ApiError, can, dateTime, errorText, post, session } from '../lib/api'
import { copyDocument, documentActions, documentDescriptions, documentKinds, documentLabels, documentLinks, documentVariables, normalizeDocument, validateDocument, type DocumentKind, type DocumentState, type ManagedDocument } from '../lib/documents'
import PageHeader from '../components/PageHeader.vue'
import LoadError from '../components/LoadError.vue'
import DocumentPreview from '../components/DocumentPreview.vue'

const route = useRoute(), router = useRouter()
function readKind(value: unknown): DocumentKind { return documentKinds.includes(value as DocumentKind) ? value as DocumentKind : 'service' }
const kind = ref<DocumentKind>(readKind(route.query.kind)), state = ref<DocumentState | null>(null), draft = ref<ManagedDocument | null>(null)
const loading = ref(false), saving = ref(false), publishing = ref(false), confirming = ref(false), error = ref(''), formError = ref(''), conflict = ref(false)
const mode = ref<'edit' | 'preview'>('edit'), previewSource = ref<'draft' | 'published'>('draft'), selected = ref(-1)
const baseline = ref(''), writable = computed(() => can('ADMIN', 'OPERATOR'))
const dirty = computed(() => !!draft.value && JSON.stringify(draft.value) !== baseline.value)
const busy = computed(() => loading.value || saving.value || publishing.value || confirming.value)
const hasUnpublished = computed(() => !!state.value && state.value.revision !== state.value.publishedRevision)
const selectedSection = computed(() => selected.value >= 0 ? draft.value?.sections[selected.value] : null)
const previewDocument = computed(() => previewSource.value === 'published' ? state.value?.published : draft.value)
const activeCount = computed(() => draft.value?.sections.filter(section => section.enabled).length || 0)
type TextField = 'title' | 'introduction' | 'footerTitle' | 'footer' | 'sectionTitle' | 'sectionBody'
const activeText = ref<{ field: TextField; index: number; element: HTMLInputElement | HTMLTextAreaElement } | null>(null)
let requestId = 0, alive = true

function useState(value: DocumentState, requestedKind: DocumentKind) {
  if (value.kind !== requestedKind || !value.draft || !value.published || !Array.isArray(value.draft.sections) || !Number.isInteger(value.revision) || !value.variables) throw new Error('文档数据不完整，请重新加载')
  state.value = value; draft.value = copyDocument(value.draft); baseline.value = JSON.stringify(draft.value); activeText.value = null
  selected.value = Math.min(selected.value, draft.value.sections.length - 1); formError.value = ''; conflict.value = false
}
async function permitDiscard(message = '当前修改尚未保存，确认放弃并继续？') {
  if (!session.token) return true
  if (busy.value) { ElMessage.warning('操作正在进行，请稍候'); return false }
  if (!dirty.value) return true
  confirming.value = true
  try { await ElMessageBox.confirm(message, '有未保存的修改', { confirmButtonText: '放弃修改', cancelButtonText: '继续编辑', type: 'warning', closeOnClickModal: false }); return true }
  catch { return false } finally { confirming.value = false }
}
async function load() {
  const current = ++requestId, requestedKind = kind.value; loading.value = true; error.value = ''
  try { const value = await api<DocumentState>(`/admin/documents/${requestedKind}`); if (alive && current === requestId && kind.value === requestedKind) useState(value, requestedKind) }
  catch (err) { if (alive && current === requestId) error.value = errorText(err) }
  finally { if (alive && current === requestId) loading.value = false }
}
async function reload() { if (await permitDiscard('重新加载会放弃当前未保存的修改，确认继续？')) await load() }
async function switchKind(value: string | number) {
  const target = readKind(value); if (target === kind.value) return
  await router.push({ path: '/documents', query: { kind: target } })
}
function beforeTabLeave(value: string | number) {
  if (readKind(value) === kind.value) return true
  void switchKind(value)
  // The route guard owns discard confirmation. Only a committed route updates the selected tab.
  return false
}
function selectSection(index: number) { selected.value = index; activeText.value = null; formError.value = '' }
function addSection() {
  if (!draft.value || busy.value || !writable.value || draft.value.sections.length >= 30) return
  let id = 'section-' + Date.now().toString(36)
  while (draft.value.sections.some(section => section.id === id)) id += '-new'
  draft.value.sections.push({ id, title: '', body: '', mediaType: 'TEXT', mediaUrl: '', posterUrl: '', linkUrl: '', actions: [], enabled: true })
  selectSection(draft.value.sections.length - 1); mode.value = 'edit'
}
async function removeSection() {
  if (!draft.value || !selectedSection.value || busy.value || !writable.value) return
  if (draft.value.sections.length <= 1) { ElMessage.warning('文档至少保留一个章节'); return }
  confirming.value = true
  try {
    await ElMessageBox.confirm(`确认从草稿移除“${selectedSection.value.title || '未命名章节'}”？保存并发布后才影响用户阅读。`, '移除章节', { confirmButtonText: '移除章节', cancelButtonText: '保留章节', type: 'warning', closeOnClickModal: false })
    draft.value.sections.splice(selected.value, 1); selectSection(Math.min(selected.value, draft.value.sections.length - 1))
  } catch { /* Keep this section. */ } finally { confirming.value = false }
}
function moveSection(direction: number) {
  if (!draft.value || busy.value || !writable.value) return
  const target = selected.value + direction
  if (selected.value < 0 || target < 0 || target >= draft.value.sections.length) return
  const [section] = draft.value.sections.splice(selected.value, 1); if (section) draft.value.sections.splice(target, 0, section)
  selectSection(target)
}
function focusText(field: TextField, event: FocusEvent) {
  const element = event.target
  if (element instanceof HTMLInputElement || element instanceof HTMLTextAreaElement) activeText.value = { field, index: selected.value, element }
}
function fieldValue(target: NonNullable<typeof activeText.value>): string {
  if (!draft.value) return ''
  if (target.field === 'sectionTitle') return draft.value.sections[target.index]?.title || ''
  if (target.field === 'sectionBody') return draft.value.sections[target.index]?.body || ''
  return draft.value[target.field]
}
async function insertVariable(key: string) {
  const target = activeText.value
  if (!draft.value || !target || busy.value || !writable.value || !documentVariables.some(option => option.key === key)) return
  if ((target.field === 'sectionTitle' || target.field === 'sectionBody') && target.index !== selected.value) { activeText.value = null; return }
  const value = fieldValue(target), start = target.element.selectionStart ?? value.length, end = target.element.selectionEnd ?? start, token = '{{' + key + '}}'
  const next = value.slice(0, start) + token + value.slice(end)
  const limit = target.field === 'introduction' ? 2000 : target.field === 'footer' ? 4000 : target.field === 'sectionBody' ? 8000 : 120
  if (next.length > limit) { ElMessage.warning('插入后超过当前字段字数限制'); return }
  if (target.field === 'sectionTitle') draft.value.sections[target.index]!.title = next
  else if (target.field === 'sectionBody') draft.value.sections[target.index]!.body = next
  else draft.value[target.field] = next
  await nextTick(); target.element.focus(); target.element.setSelectionRange(start + token.length, start + token.length)
}
async function save() {
  if (!draft.value || !state.value || busy.value || !dirty.value || conflict.value || !writable.value) return
  formError.value = ''; const document = normalizeDocument(draft.value), invalid = validateDocument(document)
  if (invalid) { formError.value = invalid.message; selected.value = invalid.section ?? -1; mode.value = 'edit'; activeText.value = null; return }
  saving.value = true; const requestedKind = kind.value, current = ++requestId
  try {
    const value = await post<DocumentState>(`/admin/documents/${requestedKind}`, { revision: state.value.revision, document })
    if (!alive || current !== requestId) return
    useState(value, requestedKind); ElMessage.success('草稿已保存，尚未影响小程序公开内容')
  } catch (err) { if (alive && current === requestId) { formError.value = errorText(err); conflict.value = err instanceof ApiError && err.status === 409 } }
  finally { if (alive && current === requestId) saving.value = false }
}
async function publish() {
  if (!draft.value || !state.value || busy.value || dirty.value || !hasUnpublished.value || conflict.value || !writable.value) return
  if (!activeCount.value) { formError.value = '发布时至少启用一个章节'; mode.value = 'edit'; return }
  publishing.value = true; formError.value = ''; const requestedKind = kind.value, current = ++requestId
  try {
    await ElMessageBox.confirm(`发布已保存的“${documentLabels[requestedKind]}”第 ${state.value.revision} 版？将替换用户当前阅读内容，展示 ${activeCount.value} 个启用章节。其他两篇文档不受影响。`, '发布文档', { confirmButtonText: '确认发布', cancelButtonText: '暂不发布', type: 'warning', closeOnClickModal: false })
    const value = await post<DocumentState>(`/admin/documents/${requestedKind}/publish`, { revision: state.value.revision })
    if (!alive || current !== requestId) return
    useState(value, requestedKind); ElMessage.success('文档已发布')
  } catch (err) { if (err !== 'cancel' && err !== 'close' && alive && current === requestId) { formError.value = errorText(err); conflict.value = err instanceof ApiError && err.status === 409 } }
  finally { if (alive && current === requestId) publishing.value = false }
}
function beforeUnload(event: BeforeUnloadEvent) { if (dirty.value || busy.value) { event.preventDefault(); event.returnValue = '' } }
watch(mode, () => { activeText.value = null })
onBeforeRouteLeave(() => permitDiscard())
onBeforeRouteUpdate(async to => {
  const target = readKind(to.query.kind); if (target === kind.value) return true
  return permitDiscard('切换文档会放弃当前未保存的修改，确认继续？')
})
watch(() => route.query.kind, value => {
  const target = readKind(value); if (target === kind.value) return
  kind.value = target; state.value = null; draft.value = null; baseline.value = ''; selected.value = -1; mode.value = 'edit'; previewSource.value = 'draft'; formError.value = ''; conflict.value = false; activeText.value = null
  void load()
})
onMounted(() => { window.addEventListener('beforeunload', beforeUnload); void load() })
onBeforeUnmount(() => { alive = false; requestId++; window.removeEventListener('beforeunload', beforeUnload) })
</script>

<template>
  <div class="documents-page" data-testid="documents-page">
    <PageHeader title="文档配置" subtitle="服务说明、隐私说明与用户协议分别维护。先保存草稿，确认整篇预览后再发布。">
      <el-button :disabled="busy" :loading="loading" data-testid="document-reload" @click="reload"><AppIcon name="Refresh"/>重新加载</el-button>
      <el-button v-if="writable" type="primary" plain :disabled="!state || !dirty || busy || conflict" :loading="saving" data-testid="document-save" @click="save">保存草稿</el-button>
      <el-button v-if="writable" type="primary" :disabled="!state || dirty || !hasUnpublished || busy || conflict" :loading="publishing" data-testid="document-publish" @click="publish">发布保存版</el-button>
    </PageHeader>
    <div class="panel document-workspace">
      <el-tabs :model-value="kind" class="content-tabs document-tabs" :before-leave="beforeTabLeave"><el-tab-pane v-for="key in documentKinds" :key="key" :name="key" :label="documentLabels[key]" :disabled="busy"/></el-tabs>
      <section class="document-context"><p>{{ documentDescriptions[kind] }}</p><small>正文采用纯文本，支持换行。服务区域、预约及取消时限、客服电话使用配置变量；价格和实际业务规则仍在对应业务配置中维护。</small></section>
      <div class="document-load"><LoadError :error="error" @retry="reload"/></div>
      <div v-if="loading && !state" class="document-loading" role="status">正在加载文档配置…</div>
      <template v-if="state && draft">
        <section class="document-status" data-testid="document-status">
          <div><el-tag :type="dirty || hasUnpublished ? 'warning' : 'success'" effect="plain">{{ dirty ? '有未保存修改' : hasUnpublished ? '有待发布草稿' : '已与发布版同步' }}</el-tag><span>草稿 {{ state.revision }} 版 · 已发布 {{ state.publishedRevision }} 版</span></div>
          <p>草稿保存：{{ dateTime(state.updatedAt) }}<span>最近发布：{{ dateTime(state.publishedAt) }}</span></p>
          <p v-if="dirty" class="document-draft-hint">修改只在当前页面，保存草稿后才可发布；当前小程序仍展示已发布版本。</p>
        </section>
        <div v-if="formError || conflict" class="document-feedback"><el-alert :title="formError || '版本已变化，请重新加载'" type="error" :closable="false" show-icon/><p v-if="conflict">为防止覆盖其他人的修改，当前操作已停止。可先复制需要保留的文字，再重新加载最新版本。<el-button link type="primary" :disabled="busy" @click="reload">重新加载最新版本</el-button></p></div>
        <div class="document-toolbar"><el-radio-group v-model="mode" :disabled="busy" aria-label="文档工作模式"><el-radio-button value="edit">编辑文档</el-radio-button><el-radio-button value="preview">整篇手机预览</el-radio-button></el-radio-group><span>{{ draft.sections.length }} 个章节 · {{ activeCount }} 个启用</span></div>
        <div v-if="mode === 'edit'" v-loading="loading" class="document-editor-layout">
          <aside class="document-outline" aria-label="文档编辑目录">
            <button type="button" :class="{ selected: selected === -1 }" class="outline-document" :disabled="busy" data-testid="document-basics" @click="selectSection(-1)"><AppIcon name="Document"/><span>标题与引言</span></button>
            <div class="outline-heading"><strong>正文章节</strong><el-button link type="primary" :disabled="busy || draft.sections.length >= 30 || !writable" data-testid="document-add-section" @click="addSection"><AppIcon name="Plus"/>新增</el-button></div>
            <ol class="document-section-list"><li v-for="(section, index) in draft.sections" :key="index"><button type="button" :class="{ selected: selected === index }" :disabled="busy" :data-testid="'document-section-' + index" @click="selectSection(index)"><span class="outline-number">{{ index + 1 }}</span><span class="outline-title">{{ section.title || '未命名章节' }}</span><small v-if="!section.enabled">停用</small></button></li></ol>
            <button type="button" :class="{ selected: selected === -2 }" class="outline-document outline-footer" :disabled="busy" data-testid="document-footer" @click="selectSection(-2)"><AppIcon name="Document"/><span>页尾说明</span></button>
            <p class="outline-help">顺序即用户阅读顺序。停用章节保留在草稿中，发布后不向用户展示。</p>
          </aside>
          <div class="document-edit-area">
            <div class="document-variable-bar"><div><strong>{{ selected === -1 ? '标题与引言' : selected === -2 ? '页尾说明' : '第 ' + (selected + 1) + ' 章' }}</strong><small>先点击要插入的文字输入框，再选择变量</small></div><el-dropdown trigger="click" :disabled="!activeText || busy || !writable" @command="insertVariable"><el-button :disabled="!activeText || busy || !writable">插入配置变量<AppIcon name="ArrowDown" :size="12"/></el-button><template #dropdown><el-dropdown-menu><el-dropdown-item v-for="variable in documentVariables" :key="variable.key" :command="variable.key">{{ variable.label }} · <code v-text="'{{' + variable.key + '}}'"/></el-dropdown-item></el-dropdown-menu></template></el-dropdown></div>
            <el-form label-position="top" class="document-form" :disabled="busy || !writable" @submit.prevent="save">
              <template v-if="selected === -1">
                <el-form-item label="文档标题" required><el-input v-model="draft.title" maxlength="120" show-word-limit placeholder="用户阅读时展示的完整标题" data-testid="document-title" @focus="focusText('title', $event)"/></el-form-item>
                <el-form-item label="引言（选填）"><el-input v-model="draft.introduction" type="textarea" :rows="5" maxlength="2000" show-word-limit placeholder="简要说明文档用途、适用人群或阅读提示" data-testid="document-introduction" @focus="focusText('introduction', $event)"/></el-form-item>
                <div class="document-variables"><strong>当前业务配置值</strong><p>插入变量后，后台预览与用户端会读取对应配置值，不必在多篇文档中重复修改。</p><dl><template v-for="variable in documentVariables" :key="variable.key"><dt>{{ variable.label }}</dt><dd>{{ state.variables[variable.key] || '未配置' }}</dd></template></dl><el-button v-if="can('ADMIN')" link type="primary" :disabled="busy" @click="router.push('/settings')">前往平台配置</el-button><span v-else class="field-help">业务配置值由管理员维护。</span></div>
              </template>
              <template v-else-if="selected === -2">
                <p class="document-edit-help">页尾位于全部章节之后，适合补充文档生效、更新和联系说明；无需额外说明时可留空。</p>
                <el-form-item label="页尾标题（选填）"><el-input v-model="draft.footerTitle" maxlength="120" show-word-limit data-testid="document-footer-title" @focus="focusText('footerTitle', $event)"/></el-form-item>
                <el-form-item label="页尾说明（选填）"><el-input v-model="draft.footer" type="textarea" :rows="8" maxlength="4000" show-word-limit data-testid="document-footer-body" @focus="focusText('footer', $event)"/></el-form-item>
              </template>
              <template v-else-if="selectedSection">
                <div class="section-operations"><el-switch v-model="selectedSection!.enabled" active-text="启用章节" data-testid="document-section-enabled"/><div><el-button :disabled="busy || selected === 0" data-testid="document-section-up" @click="moveSection(-1)"><AppIcon name="ArrowUp"/>上移</el-button><el-button :disabled="busy || selected >= draft.sections.length - 1" data-testid="document-section-down" @click="moveSection(1)"><AppIcon name="ArrowDown"/>下移</el-button><el-button type="danger" plain :disabled="busy || draft.sections.length <= 1" data-testid="document-section-remove" @click="removeSection">移除章节</el-button></div></div>
                <el-form-item label="章节标题" required><el-input v-model="selectedSection!.title" maxlength="120" show-word-limit data-testid="document-section-title" @focus="focusText('sectionTitle', $event)"/></el-form-item>
                <el-form-item label="章节正文" :required="selectedSection!.mediaType === 'TEXT'"><el-input v-model="selectedSection!.body" type="textarea" :rows="10" maxlength="8000" show-word-limit placeholder="输入完整正文，使用换行区分段落。HTML 标签会按原文字展示。" data-testid="document-section-body" @focus="focusText('sectionBody', $event)"/><small class="field-help">文字、媒体、相关页面及操作都属于当前章节。媒体显示在章节标题后、正文前。</small></el-form-item>
                <el-form-item label="章节媒体"><el-radio-group v-model="selectedSection!.mediaType" data-testid="document-section-media-type"><el-radio value="TEXT">无媒体</el-radio><el-radio value="IMAGE">图片</el-radio><el-radio value="VIDEO">视频</el-radio></el-radio-group></el-form-item>
                <el-form-item v-if="selectedSection!.mediaType !== 'TEXT'" :label="selectedSection!.mediaType === 'IMAGE' ? '图片链接' : '视频链接'" required><el-input v-model="selectedSection!.mediaUrl" maxlength="1000" placeholder="https://..." data-testid="document-section-media-url"/><small class="field-help">填写公开可访问的 HTTPS 素材链接；尚未接入上传。微信端需配置对应资源域名。</small></el-form-item>
                <el-form-item v-if="selectedSection!.mediaType === 'VIDEO'" label="视频封面（选填）"><el-input v-model="selectedSection!.posterUrl" maxlength="1000" placeholder="HTTPS 图片链接" data-testid="document-section-poster-url"/><small class="field-help">先显示封面，用户点击播放，不自动播放或出声。</small></el-form-item>
                <el-form-item label="相关页面（选填）"><el-select v-model="selectedSection!.linkUrl" clearable placeholder="不配置" data-testid="document-section-link"><el-option v-for="link in documentLinks" :key="link.value" :value="link.value" :label="link.label"/></el-select><small class="field-help">正文后显示“查看相关页面”文字链接，仅支持小程序现有站内页面。</small></el-form-item>
                <el-form-item label="章节操作（选填）"><el-checkbox-group v-model="selectedSection!.actions" class="document-action-options" data-testid="document-section-actions"><el-checkbox v-for="action in documentActions" :key="action.value" :value="action.value"><span>{{ action.label }}</span><small>{{ action.description }}</small></el-checkbox></el-checkbox-group></el-form-item>
                <el-form-item label="章节标识" required><el-input v-model="selectedSection!.id" maxlength="64" data-testid="document-section-id"/><small class="field-help">供页面目录定位。以小写字母开头，可含小写字母、数字及短横线，整篇不可重复；通常保留自动生成值。</small></el-form-item>
              </template>
            </el-form>
          </div>
        </div>
        <div v-else class="document-full-preview">
          <div class="preview-source"><el-radio-group v-model="previewSource" :disabled="busy" aria-label="预览内容版本"><el-radio value="draft">当前编辑内容{{ dirty ? '（未保存）' : '（已保存草稿）' }}</el-radio><el-radio value="published">当前已发布版</el-radio></el-radio-group><p v-if="previewSource === 'published'">这里展示目前用户阅读的版本；草稿修改尚未发布时，不会出现在此处。</p></div>
          <DocumentPreview v-if="previewDocument" :key="kind + previewSource" :kind="kind" :document="previewDocument" :variables="state!.variables" :published-at="previewSource === 'published' ? state!.publishedAt : undefined" :revision="previewSource === 'published' ? state!.publishedRevision : undefined"/>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.document-workspace{min-width:0}.document-context{padding:16px 20px;border-bottom:1px solid #ebeef5}.document-context p{font-size:14px;line-height:24px;margin:0 0 6px;color:#303133}.document-context small{font-size:12px;line-height:21px;display:block;color:#7c8380}.document-load:has(.load-error){padding:16px 20px 0}.document-loading{padding:64px 20px;text-align:center;color:#7c8380}.document-status{padding:16px 20px;background:#fafbfa;border-bottom:1px solid #ebeef5}.document-status>div{display:flex;align-items:center;gap:12px;flex-wrap:wrap}.document-status>div>span:last-child{font-size:13px;color:#606962}.document-status p{margin:8px 0 0;font-size:12px;color:#7c8380;line-height:20px}.document-status p>span{margin-left:24px}.document-status .document-draft-hint{color:#986b2d}.document-feedback{padding:16px 20px 0}.document-feedback>p{font-size:13px;line-height:24px;margin:8px 0 0;color:#7d6652}.document-toolbar{padding:16px 20px;display:flex;justify-content:space-between;align-items:center;gap:12px;border-bottom:1px solid #ebeef5;flex-wrap:wrap}.document-toolbar>span{font-size:12px;color:#7c8380}.document-editor-layout{display:grid;grid-template-columns:240px minmax(0,1fr);min-height:640px}.document-outline{border-right:1px solid #ebeef5;min-width:0;padding:16px 12px}.outline-document,.document-section-list button{width:100%;min-height:44px;border:0;background:none;display:flex;align-items:center;text-align:left;gap:8px;padding:10px 12px;line-height:22px;color:#4d5650;font-size:14px;border-radius:4px}.outline-document.selected,.document-section-list button.selected{background:#edf6f1;color:#16794d;font-weight:500}.outline-document:hover,.document-section-list button:hover{background:#f5f8f6}.outline-document.selected:hover,.document-section-list button.selected:hover{background:#edf6f1}.outline-heading{display:flex;align-items:center;justify-content:space-between;padding:16px 12px 4px}.outline-heading strong{font-size:12px;color:#838a85;font-weight:400}.document-section-list{margin:0;padding:0;list-style:none;max-height:560px;overflow:auto}.outline-title{overflow-wrap:anywhere;flex:1;min-width:0}.outline-number{font-size:12px;color:#8b928c;flex-shrink:0;width:16px}.document-section-list small{font-size:11px;color:#909799;flex-shrink:0;font-weight:400}.outline-footer{margin-top:16px;border-top:1px solid #ebeef5;border-radius:0}.outline-help{font-size:12px;line-height:20px;color:#8b928c;margin:16px 12px 0}.document-edit-area{min-width:0;padding:20px 24px 24px;max-width:1024px;width:100%}.document-variable-bar{display:flex;align-items:flex-start;justify-content:space-between;gap:16px;margin-bottom:24px}.document-variable-bar strong{font-size:16px;font-weight:500;display:block;line-height:24px}.document-variable-bar small{font-size:12px;line-height:20px;color:#8b928c;display:block;margin-top:4px}.document-form{max-width:860px}.document-variables{border-top:1px solid #ebeef5;margin-top:24px;padding-top:20px}.document-variables>strong{font-size:14px;font-weight:500}.document-variables p,.document-edit-help{font-size:13px;line-height:22px;color:#7c8380;margin:8px 0 16px}.document-variables dl{display:grid;grid-template-columns:140px minmax(0,1fr);font-size:13px;line-height:22px;gap:12px 16px;margin:0 0 16px}.document-variables dt{color:#7c8380}.document-variables dd{margin:0;white-space:pre-wrap;overflow-wrap:anywhere;color:#4d5650}.section-operations{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:20px;padding-bottom:16px;border-bottom:1px solid #ebeef5;flex-wrap:wrap}.section-operations>div{display:flex;gap:8px;flex-wrap:wrap}.section-operations .el-button+.el-button{margin-left:0}.document-action-options{display:flex;flex-direction:column;align-items:flex-start;width:100%;gap:12px}.document-action-options :deep(.el-checkbox){margin:0;height:auto;white-space:normal;align-items:flex-start;line-height:22px}.document-action-options :deep(.el-checkbox__input){margin-top:4px}.document-action-options small{display:block;font-size:12px;color:#7c8380;line-height:20px;margin-top:2px;white-space:normal}.document-action-options :deep(.el-checkbox__label){line-height:22px;white-space:normal;overflow-wrap:anywhere}.document-full-preview{padding:24px;background:#f7f8f7}.preview-source{max-width:600px;margin:0 auto 24px}.preview-source .el-radio-group{display:flex;flex-wrap:wrap;gap:8px 24px}.preview-source .el-radio{margin-right:0;white-space:normal;height:auto;min-height:32px}.preview-source :deep(.el-radio__label){white-space:normal;line-height:22px}.preview-source p{font-size:12px;line-height:21px;color:#7c8380;margin:8px 0 0}
@media(max-width:1100px){.document-editor-layout{grid-template-columns:200px minmax(0,1fr)}.document-edit-area{padding:20px}.document-variable-bar{flex-wrap:wrap;gap:12px}}
@media(max-width:650px){.document-context,.document-status,.document-toolbar{padding:16px}.document-status p>span{display:block;margin-left:0;margin-top:4px}.document-editor-layout{display:block}.document-outline{border-right:0;border-bottom:1px solid #ebeef5;padding:12px}.document-section-list{max-height:180px}.outline-help{margin-top:8px}.outline-document{min-height:40px}.outline-heading{padding-top:8px}.outline-footer{margin-top:8px}.document-edit-area{padding:20px 16px}.document-variable-bar{margin-bottom:20px}.document-variable-bar .el-dropdown,.document-variable-bar .el-button{width:100%}.document-variables dl{grid-template-columns:1fr;gap:4px}.document-variables dd{margin-bottom:12px}.document-feedback{padding:16px 16px 0}.document-full-preview{padding:20px 12px}.document-tabs :deep(.el-tabs__item){padding:0 14px;font-size:14px}.document-status>div{gap:8px}.document-toolbar>span{width:100%}.section-operations>div{width:100%}.section-operations .el-button{flex:1;padding:8px}.document-load:has(.load-error){padding:16px 16px 0}}
</style>
