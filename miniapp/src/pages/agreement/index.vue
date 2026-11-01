<script setup lang="ts">
import { computed, ref } from 'vue'
import { onHide, onLoad, onPullDownRefresh, onShow, onUnload } from '@dcloudio/uni-app'
import AppIcon from '../../components/AppIcon.vue'
import AdMedia from '../../components/AdMedia.vue'
import { api, callService, copyText, errorToast, go, safeLink, type Data } from '../../api'
import { backendResourceUrl } from '../../api-base'
import { documentActionDefinitions, documentActionItems, documentAnchor, documentPublishedAt, parsePublishedDocument, readDocumentCache, saveDocumentCache, type DocumentAction, type DocumentKind, type PublishedDocument } from '../../lib/documents'
import { pageClass } from '../../store'

type PageKind = DocumentKind | 'notice'
const titles: Record<PageKind, string> = { service: '服务说明', terms: '用户服务协议', privacy: '隐私说明', notice: '平台公告' }
const kind = ref<PageKind>('service'), home = ref<Data>({}), homeLoading = ref(false), homeReady = ref(false), homeError = ref('')
const published = ref<PublishedDocument | null>(null), documentLoading = ref(false), documentError = ref(''), usingCache = ref(false)
const expandedNotice = ref(''), mediaErrors = ref<Record<string, boolean>>({}), imageVersions = ref<Record<string, number>>({}), pageVisible = ref(false)
const title = computed(() => kind.value === 'notice' ? titles.notice : published.value?.document.title || titles[kind.value])
const notices = computed<Data[]>(() => Array.isArray(home.value.notices) ? home.value.notices : [])
const document = computed(() => published.value?.document)
const publishedAt = computed(() => published.value ? documentPublishedAt(published.value.publishedAt) : '')
const documentRevision = computed(() => published.value?.revision || 0)
const contents = computed(() => document.value?.sections.map(section => ({ id: documentAnchor(section.id), title: section.title })) || [])
const loading = computed(() => homeLoading.value || kind.value !== 'notice' && documentLoading.value)
let alive = true, homeRequestId = 0, documentRequestId = 0, documentNavigating = false
let homeTask: Promise<void> | null = null, documentTask: Promise<void> | null = null, loadingDocumentKind: DocumentKind | null = null

function updateNavigationTitle() { uni.setNavigationBarTitle({ title: title.value }) }
function errorMessage(error: unknown) { return error instanceof Error ? error.message : '内容加载失败，请稍后重试' }
function loadHome(): Promise<void> {
  if (homeTask) return homeTask
  const version = ++homeRequestId
  homeLoading.value = true; homeError.value = ''
  const task = (async () => {
    try {
      const data = await api('/public/home')
      if (!alive || version !== homeRequestId) return
      home.value = data; homeReady.value = true
      try {
        uni.setStorageSync('travel_home', data)
        // An empty configuration must also clear an obsolete cached phone number.
        uni.setStorageSync('travel_service_phone', String(data.servicePhone || '').trim())
      } catch { /* Successful content remains readable when device storage is unavailable. */ }
      if (kind.value === 'notice') { mediaErrors.value = {}; imageVersions.value = {} }
      if (!notices.value.some(item => String(item.id) === expandedNotice.value)) expandedNotice.value = String(notices.value[0]?.id ?? '')
    } catch (error) {
      if (alive && version === homeRequestId) homeError.value = errorMessage(error)
    } finally {
      if (alive && version === homeRequestId) { homeLoading.value = false; homeTask = null }
    }
  })()
  homeTask = task
  return task
}
function loadDocument(): Promise<void> {
  if (kind.value === 'notice') return Promise.resolve()
  const requestedKind = kind.value
  if (documentTask && loadingDocumentKind === requestedKind) return documentTask
  const version = ++documentRequestId
  documentLoading.value = true; documentError.value = ''; loadingDocumentKind = requestedKind
  const task = (async () => {
    try {
      const data = parsePublishedDocument(await api(`/public/documents/${requestedKind}`))
      if (!alive || version !== documentRequestId || kind.value !== requestedKind) return
      published.value = data; usingCache.value = false
      mediaErrors.value = {}; imageVersions.value = {}
      saveDocumentCache(requestedKind, data)
      updateNavigationTitle()
    } catch (error) {
      if (!alive || version !== documentRequestId || kind.value !== requestedKind) return
      documentError.value = errorMessage(error)
      // A failed request never falls back to source-code wording or a different document.
      const previous = published.value || readDocumentCache(requestedKind)
      if (previous) { published.value = previous; usingCache.value = true; updateNavigationTitle() }
    } finally {
      if (alive && version === documentRequestId) { documentLoading.value = false; documentTask = null; loadingDocumentKind = null }
    }
  })()
  documentTask = task
  return task
}
async function load() { await Promise.allSettled([loadHome(), loadDocument()]) }
function toggleNotice(id: string | number) { expandedNotice.value = expandedNotice.value === String(id) ? '' : String(id) }
function retryImage(id: string | number) { imageVersions.value[id] = (imageVersions.value[id] || 0) + 1; mediaErrors.value[id] = false }
function runAction(action: DocumentAction) {
  if (action === 'contact') { void callService(); return }
  if (action === 'amapPrivacy') { copyText('https://lbs.amap.com/pages/privacy/', '高德隐私政策链接已复制'); return }
  const url = documentActionDefinitions[action].url
  if (url) go(url)
}
function switchDocument(next: 'privacy' | 'terms') {
  if (documentNavigating) return
  documentNavigating = true
  uni.redirectTo({
    url: `/pages/agreement/index?kind=${next}`,
    fail: () => errorToast(new Error('页面暂时无法打开，请稍后重试')),
    complete: () => { documentNavigating = false },
  })
}
function jumpTo(id: string) {
  if (id !== 'document-top' && !contents.value.some(item => item.id === id)) return
  uni.pageScrollTo({ selector: `#${id}`, duration: 180, fail: () => errorToast(new Error('暂时无法跳转，请滑动阅读正文')) })
}
onLoad(options => {
  const requested = String(options?.kind || '')
  kind.value = Object.prototype.hasOwnProperty.call(titles, requested) ? requested as PageKind : 'service'
  published.value = null; usingCache.value = false; documentError.value = ''; documentTask = null; loadingDocumentKind = null
  mediaErrors.value = {}; imageVersions.value = {}; documentRequestId++
  updateNavigationTitle()
  void load()
})
onPullDownRefresh(() => { void load().finally(() => uni.stopPullDownRefresh()) })
onShow(() => { pageVisible.value = true })
onHide(() => { pageVisible.value = false })
onUnload(() => { pageVisible.value = false; alive = false; homeRequestId++; documentRequestId++ })
</script>

<template>
<view id="document-top" class="page agreement-page" :class="[pageClass(), { 'reading-page': kind !== 'notice' }]">
  <view class="document-header">
    <view class="header-main">
      <text class="document-title">{{ title }}</text>
      <text v-if="kind === 'notice'" class="document-subtitle">预约安排、服务调整与平台通知</text>
    </view>
    <button class="app-button text-button refresh-button" :disabled="loading" :aria-label="loading ? '正在刷新' : '刷新内容'" @click="load"><AppIcon name="refresh" :size="18"/><text>{{ loading ? '刷新中' : '刷新' }}</text></button>
  </view>

  <template v-if="kind === 'notice'">
    <view v-if="homeError && homeReady" class="update-warning" role="alert"><text>刷新未完成，以下仍为上次加载的内容。{{ homeError }}</text><button class="app-button text-button" :disabled="homeLoading" @click="loadHome">重试</button></view>
    <view v-if="!homeReady" class="page-state" :aria-busy="homeLoading">
      <AppIcon :name="homeLoading ? 'refresh' : 'info'" :size="28"/>
      <text class="state-title">{{ homeLoading ? '正在加载内容' : '暂时无法加载' }}</text>
      <text class="state-description">{{ homeLoading ? '请稍候…' : homeError }}</text>
      <button v-if="!homeLoading" class="app-button secondary" @click="loadHome">重新加载</button>
    </view>
    <template v-else>
      <view v-if="home.notice" class="service-reminder">
        <AppIcon name="info" :size="20" color="brand"/>
        <view><text class="reminder-title">服务提示</text><text class="body-text">{{ home.notice }}</text></view>
      </view>
      <text class="list-caption">公告内容</text>
      <view v-if="notices.length" class="notice-list">
        <view v-for="item in notices" :key="item.id" class="notice-item">
          <button class="app-button notice-toggle" :aria-expanded="expandedNotice === String(item.id)" @click="toggleNotice(item.id)">
            <text class="notice-title">{{ item.title || '平台公告' }}</text>
            <AppIcon :name="expandedNotice === String(item.id) ? 'chevron-down' : 'chevron-right'" :size="18"/>
          </button>
          <view v-if="expandedNotice === String(item.id)" class="notice-detail">
            <image v-if="item.mediaType === 'IMAGE' && item.mediaUrl && !mediaErrors[item.id]" :key="imageVersions[item.id] || 0" class="content-image" :src="backendResourceUrl(item.mediaUrl)" mode="widthFix" @error="mediaErrors[item.id] = true"/>
            <view v-else-if="item.mediaType === 'VIDEO' && item.mediaUrl" class="content-video"><AdMedia :item="item" placement="popup" :active="pageVisible"/></view>
            <view v-if="item.mediaType === 'IMAGE' && mediaErrors[item.id]" class="media-error"><text>图片暂时无法加载。</text><button class="app-button text-button" @click="retryImage(item.id)">重新加载图片</button></view>
            <text v-if="item.body" class="body-text">{{ item.body }}</text>
            <text v-if="!item.body && !item.mediaUrl" class="body-text secondary-text">该公告暂未补充正文。</text>
            <button v-if="item.linkUrl" class="app-button text-button related-link" @click="safeLink(item.linkUrl)"><text>查看相关页面</text><AppIcon name="chevron-right" :size="16" color="brand"/></button>
          </view>
          <text v-else-if="item.body" class="notice-excerpt">{{ item.body }}</text>
          <button class="app-button text-button expand-button" @click="toggleNotice(item.id)">{{ expandedNotice === String(item.id) ? '收起全文' : '展开全文' }}</button>
        </view>
      </view>
      <view v-else class="page-state compact-state"><AppIcon name="bell" :size="28"/><text class="state-title">暂无平台公告</text><text class="state-description">服务相关通知将在这里展示，可下拉刷新查看。</text></view>
      <view class="notice-help">
        <text class="section-title">需要确认您的出行安排？</text>
        <text class="body-text secondary-text">订单进度请在“我的订单”查看，接送安排或协助需求可联系平台客服。</text>
        <view class="help-actions">
          <button class="app-button secondary" @click="go('/pages/orders/index')"><AppIcon name="orders" :size="18"/><text>我的订单</text></button>
          <button class="app-button secondary" @click="callService"><AppIcon name="phone" :size="18"/><text>联系客服</text></button>
        </view>
      </view>
    </template>
  </template>

  <template v-else>
    <view v-if="!document" class="page-state document-state" :aria-busy="documentLoading">
      <AppIcon :name="documentLoading ? 'refresh' : 'info'" :size="28"/>
      <text class="state-title">{{ documentLoading ? '正在加载文档' : '暂时无法加载文档' }}</text>
      <text class="state-description">{{ documentLoading ? '请稍候…' : documentError || '请重新加载查看已发布内容。' }}</text>
      <button v-if="!documentLoading" class="app-button secondary" @click="loadDocument">重新加载</button>
    </view>
    <template v-else>
      <view v-if="usingCache" class="update-warning" role="alert">
        <text>{{ documentLoading ? '正在重新获取内容，当前显示本机缓存版本。' : '本次未能更新内容，当前显示上次成功加载的缓存版本。' }}{{ documentError }}</text>
        <button class="app-button text-button" :disabled="documentLoading" @click="loadDocument">重试</button>
      </view>
      <text class="document-meta document-published-at">发布时间：{{ publishedAt }}</text>
      <text v-if="document.introduction" class="body-text document-intro">{{ document.introduction }}</text>
      <view class="document-contents">
        <text class="contents-title">快速查阅</text>
        <view class="contents-links"><button v-for="(item, index) in contents" :key="item.id" class="app-button contents-link" @click="jumpTo(item.id)"><text class="contents-number">{{ index + 1 }}.</text><text>{{ item.title }}</text></button></view>
      </view>
      <view v-for="(section, index) in document.sections" :id="documentAnchor(section.id)" :key="section.id" class="reading-section document-reading-section">
        <text class="section-title">{{ index + 1 }}. {{ section.title }}</text>
        <image v-if="section.mediaType === 'IMAGE' && section.mediaUrl && !mediaErrors[section.id]" :key="imageVersions[section.id] || 0" class="content-image" :src="backendResourceUrl(section.mediaUrl)" mode="widthFix" @error="mediaErrors[section.id] = true"/>
        <view v-else-if="section.mediaType === 'VIDEO' && section.mediaUrl" class="content-video"><AdMedia :key="kind + '-' + section.id + '-' + documentRevision" :item="section" placement="popup" :active="pageVisible"/></view>
        <view v-if="section.mediaType === 'IMAGE' && mediaErrors[section.id]" class="media-error"><text>图片暂时无法加载。</text><button class="app-button text-button" @click="retryImage(section.id)">重新加载图片</button></view>
        <text v-if="section.body" class="body-text paragraph">{{ section.body }}</text>
        <button v-if="section.linkUrl" class="app-button text-button related-link" @click="safeLink(section.linkUrl)"><text>查看相关页面</text><AppIcon name="chevron-right" :size="16" color="brand"/></button>
        <view v-if="section.actions.length" class="document-actions">
          <button v-for="action in documentActionItems(section.actions)" :key="action.kind" class="app-button document-action" :class="action.kind === 'contact' ? 'secondary document-contact' : 'action-link'" @click="runAction(action.kind)"><AppIcon :name="action.icon" :size="19"/><text>{{ action.label }}</text><AppIcon v-if="action.url" name="chevron-right" :size="16"/></button>
        </view>
      </view>
      <view v-if="document.footerTitle || document.footer" class="version-note document-footer-note"><text v-if="document.footerTitle" class="note-title">{{ document.footerTitle }}</text><text v-if="document.footer">{{ document.footer }}</text></view>
      <view v-if="homeError" class="update-warning contact-warning" role="alert"><text>联系信息暂未更新。{{ homeError }}</text><button class="app-button text-button" :disabled="homeLoading" @click="loadHome">重试</button></view>
    </template>
    <view class="document-footer">
      <button v-if="kind !== 'terms'" class="app-button text-button" @click="switchDocument('terms')">用户服务协议</button>
      <button v-if="kind !== 'privacy'" class="app-button text-button" @click="switchDocument('privacy')">隐私说明</button>
      <button class="app-button text-button" @click="jumpTo('document-top')">返回顶部</button>
    </view>
  </template>
</view>
</template>

<style scoped>
.agreement-page{padding:24px 20px calc(32px + env(safe-area-inset-bottom));font-size:15px;line-height:1.8;min-height:100vh;box-sizing:border-box;overflow-wrap:anywhere}
.reading-page{background:#fff}
.document-header{display:flex;align-items:flex-start;justify-content:space-between;gap:12px;padding-bottom:20px}
.header-main{flex:1;min-width:0}.document-title{display:block;font-size:22px;font-weight:600;line-height:32px;word-break:break-word;white-space:pre-wrap}.document-subtitle{display:block;font-size:14px;line-height:24px;color:#646a73;margin-top:4px}
.agreement-page .app-button{font-size:15px;line-height:22px;min-height:44px;padding:10px 16px;border-radius:6px;display:flex;align-items:center;justify-content:center;gap:8px;font-weight:500;white-space:normal;word-break:break-word;box-sizing:border-box}
.agreement-page .text-button{background:transparent;color:#116c49;border:0;border-radius:0;padding:10px 0;text-align:left}
.agreement-page .refresh-button{color:#646a73;flex-shrink:0;padding:4px 0 4px 8px;min-height:44px;font-size:14px}
.refresh-button[disabled]{opacity:.6}.body-text{display:block;white-space:pre-wrap;word-break:break-word;font-size:15px;line-height:27px;color:#343a40}.secondary-text{color:#646a73}
.agreement-page .section-title{display:block;font-size:18px;line-height:28px;font-weight:600;white-space:pre-wrap;word-break:break-word}
.update-warning{display:flex;gap:12px;align-items:center;padding:12px 0;margin-bottom:16px;border-top:1px solid #eceeef;border-bottom:1px solid #eceeef;font-size:14px;line-height:24px;color:#775b24}.update-warning>text{flex:1;min-width:0}.update-warning>button{flex-shrink:0}.contact-warning{margin-top:24px}
.page-state{text-align:center;padding:40px 16px;background:#fff}.page-state>.app-icon{margin-bottom:8px}.state-title{display:block;font-size:17px;font-weight:500;line-height:28px;margin-top:8px}.state-description{display:block;font-size:14px;line-height:24px;color:#646a73;margin-top:8px;word-break:break-word}.page-state .app-button{margin:20px auto 0;max-width:200px}.compact-state{padding:32px 16px}
.service-reminder{display:flex;align-items:flex-start;gap:12px;background:#fff;padding:16px;margin-bottom:24px}.service-reminder>.app-icon{margin-top:4px}.service-reminder>view{flex:1;min-width:0}.reminder-title{display:block;font-size:15px;line-height:24px;font-weight:600;margin-bottom:4px}.list-caption{display:block;font-size:14px;line-height:24px;color:#646a73;margin-bottom:8px}
.notice-list{background:#fff;padding:0 16px}.notice-item{padding:8px 0 4px;border-bottom:1px solid #eceeef}.notice-item:last-child{border-bottom:0}.agreement-page .notice-toggle{width:100%;justify-content:space-between;text-align:left;background:transparent;padding:8px 0;gap:16px}.notice-title{flex:1;min-width:0;font-size:17px;line-height:28px;font-weight:600}.notice-detail{padding:4px 0}.notice-excerpt{display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;font-size:15px;line-height:27px;color:#646a73;margin-top:4px;word-break:break-word}.agreement-page .expand-button{font-size:14px;justify-content:flex-start;min-height:44px}.notice-help{margin-top:28px;padding-top:24px;border-top:1px solid #dedfe1}.notice-help .body-text{margin-top:8px}.help-actions{display:flex;gap:12px;margin-top:16px}.help-actions>.app-button{flex:1;min-width:0}
.document-meta{display:block;font-size:13px;line-height:22px;color:#646a73;padding-top:16px;border-top:1px solid #eceeef}.document-intro{margin-top:16px}.document-contents{margin:24px 0 8px;padding:16px 0;border-top:1px solid #eceeef;border-bottom:1px solid #eceeef}.contents-title{display:block;font-size:14px;font-weight:600;line-height:24px;margin-bottom:4px}.contents-links{display:flex;flex-wrap:wrap}.agreement-page .contents-link{width:50%;min-width:0;background:transparent;color:#116c49;justify-content:flex-start;text-align:left;padding:8px 8px 8px 0;font-size:14px;line-height:24px;gap:6px}.contents-link>text:last-child{flex:1;min-width:0}.contents-number{color:#646a73;flex-shrink:0}
.reading-section{padding-top:28px;scroll-margin-top:16px}.paragraph{margin-top:12px}.agreement-page .related-link{justify-content:flex-start;margin-top:8px}.content-image{display:block;width:100%;margin:12px 0}.content-video{display:block;width:100%;margin:12px 0}.media-error{display:block;font-size:14px;line-height:24px;color:#775b24;margin:12px 0}.document-actions{margin-top:16px}.agreement-page .document-action{width:100%;text-align:left;min-height:52px;gap:12px}.document-action>text{flex:1;min-width:0}.document-action>.app-icon{flex-shrink:0}.agreement-page .action-link{justify-content:flex-start;background:transparent;color:#1f2329;border-bottom:1px solid #eceeef;border-radius:0;padding:12px 0}.agreement-page .document-contact{margin-top:12px}.version-note{font-size:13px;line-height:24px;color:#646a73;padding:16px 0;margin-top:28px;border-top:1px solid #eceeef;white-space:pre-wrap;word-break:break-word}.version-note>text{display:block}.note-title{font-weight:600;margin-bottom:4px}.document-footer{display:flex;justify-content:space-between;flex-wrap:wrap;gap:4px 16px;border-top:1px solid #eceeef;padding-top:12px;margin-top:16px}.document-footer .app-button{font-size:14px;max-width:100%}
.senior.agreement-page{font-size:18px}.senior .document-title{font-size:24px;line-height:36px}.senior .document-subtitle,.senior .state-description,.senior .list-caption,.senior .document-meta,.senior .version-note,.senior .media-error,.senior .update-warning{font-size:16px;line-height:28px}.senior .body-text,.senior .notice-excerpt{font-size:18px;line-height:32px}.senior .section-title,.senior .notice-title{font-size:20px;line-height:32px}.senior .reminder-title,.senior .contents-title{font-size:18px;line-height:30px}.senior.agreement-page .app-button{font-size:18px;line-height:28px;min-height:48px}.senior.agreement-page .contents-link{font-size:16px}.senior.agreement-page .refresh-button{font-size:16px}
@media(min-width:600px){.agreement-page{padding-left:32px;padding-right:32px}}
</style>
