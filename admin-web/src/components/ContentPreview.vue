<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import type { Row } from '../lib/api'

const props = defineProps<{ content: Row }>()
const dismissed = ref(false)
const mediaFailed = ref(false)
const playing = ref(false)
const posterFailed = ref(false)
const videoRef = ref<HTMLVideoElement | null>(null)
const playbackHint = ref('')
let playVersion = 0, alive = true
const isPopup = computed(() => props.content.kind === 'AD')
const isBanner = computed(() => props.content.kind === 'BANNER')
const isNotice = computed(() => props.content.kind === 'NOTICE')
const isDocument = computed(() => !isPopup.value && !isBanner.value)
const hasMedia = computed(() => ['IMAGE', 'VIDEO'].includes(props.content.mediaType))
function resourceUrl(value: unknown) {
  try { const url = new URL(String(value || '').trim()); return url.protocol === 'https:' && !url.username && !url.password && !url.hash ? url.href : '' } catch { return '' }
}
const mediaUrl = computed(() => resourceUrl(props.content.mediaUrl))
const posterUrl = computed(() => resourceUrl(props.content.posterUrl))
const actionText = computed(() => String(props.content.actionText || '').trim() || '查看详情')
function stopVideo() { playVersion++; videoRef.value?.pause(); playing.value = false; playbackHint.value = '' }
function dismissPreview() { stopVideo(); dismissed.value = true }
function videoError(event: Event) {
  if (event.target !== videoRef.value) return
  stopVideo(); mediaFailed.value = true
}
async function playVideo() {
  if (!alive || dismissed.value || playing.value || !mediaUrl.value || mediaFailed.value) return
  const version = ++playVersion
  playbackHint.value = ''; playing.value = true
  await nextTick()
  if (!alive || dismissed.value || version !== playVersion) return
  const video = videoRef.value
  if (!video) return
  try {
    await video.play()
    if (!alive || dismissed.value || version !== playVersion) video.pause()
  } catch (error) {
    if (!alive || dismissed.value || version !== playVersion) return
    const name = error instanceof Error ? error.name : ''
    if (name === 'NotAllowedError') playbackHint.value = '浏览器未允许播放，请点击视频内的播放按钮。'
    else if (name !== 'AbortError') { stopVideo(); mediaFailed.value = true }
  }
}
watch(() => [props.content.kind, props.content.mediaType, props.content.mediaUrl, props.content.posterUrl], () => { stopVideo(); mediaFailed.value = false; posterFailed.value = false; dismissed.value = false })
watch(() => [props.content.title, props.content.body, props.content.linkUrl, props.content.actionText], () => { dismissed.value = false })
onBeforeUnmount(() => { alive = false; stopVideo() })
</script>

<template>
  <div class="content-preview">
    <div v-if="dismissed" class="preview-dismissed"><p>弹窗预览已关闭</p><el-button @click="dismissed = false">重新展示弹窗</el-button></div>
    <div v-else :class="['phone-viewport', { 'phone-popup': isPopup, 'phone-banner': isBanner, 'phone-document': isDocument, 'phone-notice': isNotice }]">
      <template v-if="isPopup || isBanner"><div class="phone-home-title">预约出行</div><div class="phone-map-placeholder" aria-hidden="true"><span/><span/><span/></div><div class="phone-booking-placeholder" aria-hidden="true"><i/><i/><i/></div></template>
      <header v-else class="document-page-header">
        <h2>{{ isNotice ? '平台公告' : '服务说明' }}</h2>
        <p>{{ isNotice ? '预约安排、服务调整与平台通知' : '预约流程、费用与接送须知' }}</p>
      </header>
      <div v-if="isDocument" class="document-context">
        <template v-if="isNotice"><span class="document-list-label">公告内容</span><p>当前条目展开后的阅读效果</p></template>
        <template v-else><p>首页与“我的”的服务说明入口，进入同一个页面。</p><p class="document-location">前面保留服务范围、预约规则、费用与接送等基础章节。</p><h3>平台补充说明</h3><p>以下预览当前编辑的一条补充内容，发布后加入本页。</p></template>
      </div>
      <div v-if="isPopup" class="preview-backdrop"/>
      <article :class="['preview-content', { 'preview-popup-content': isPopup, 'preview-banner-content': isBanner, 'preview-document': isDocument }]">
        <header v-if="isPopup" class="popup-topline"><span>广告</span><button type="button" class="preview-close" aria-label="关闭弹窗预览" @click="dismissPreview"><AppIcon name="Close" :size="18"/></button></header>
        <div class="preview-content-scroll">
          <h3 v-if="isDocument" class="document-item-title">{{ content.title || '在这里预览内容标题' }}</h3>
          <div v-if="hasMedia" :class="['preview-media', 'preview-media-' + String(content.mediaType).toLowerCase()]">
            <template v-if="mediaUrl && !mediaFailed"><img v-if="content.mediaType === 'IMAGE'" :src="mediaUrl" :alt="content.title || (isDocument ? '内容配图' : '广告图片')" @error="mediaFailed = true"/><video v-else-if="playing" :key="mediaUrl + posterUrl" ref="videoRef" :src="mediaUrl" :poster="!posterFailed ? posterUrl || undefined : undefined" controls playsinline preload="none" @error="videoError" @play="playbackHint = ''"/><button v-else type="button" class="preview-video-cover" :aria-label="isDocument ? '播放内容视频预览' : '播放广告视频预览'" @click="playVideo"><img v-if="posterUrl && !posterFailed" :src="posterUrl" alt="视频封面" @error="posterFailed = true"/><span class="preview-play-icon" aria-hidden="true"/><small>点击播放</small></button></template>
            <div v-else class="preview-media-placeholder"><AppIcon name="Picture" :size="24"/><span>{{ mediaFailed ? '素材加载失败' : content.mediaType === 'VIDEO' ? '添加视频链接后预览' : '添加图片链接后预览' }}</span><small v-if="mediaFailed">请检查素材地址及访问权限</small></div>
          </div>
          <p v-if="playbackHint" class="playback-hint" role="status">{{ playbackHint }}</p>
          <div class="preview-copy"><div v-if="isBanner" class="banner-ad-label">广告</div><h3 v-if="!isDocument">{{ content.title || '在这里预览内容标题' }}</h3><p v-if="content.body" class="preview-body">{{ content.body }}</p><p v-else-if="content.mediaType === 'TEXT'" class="preview-body placeholder-copy">{{ isNotice ? '填写公告正文，说明具体事项、适用范围及需要用户留意的安排。' : isDocument ? '填写补充说明正文，说明用户预约或乘车前需要了解的事项。' : '填写正文，说明活动内容、服务亮点与参与方式。' }}</p></div>
        </div>
        <footer v-if="content.linkUrl || isBanner" :class="['preview-action-area', { 'banner-action-area': isBanner, 'document-action-area': isDocument }]"><span class="preview-action">{{ isDocument ? '查看相关页面' : content.linkUrl ? actionText : content.mediaType === 'TEXT' ? '阅读全文' : '查看内容' }}<AppIcon v-if="isBanner || isDocument" name="ArrowRight" :size="14"/></span></footer>
        <p v-if="isPopup && Number(content.skipSeconds) > 0" class="preview-countdown-note">{{ content.skipSeconds }} 秒提示 · 可随时关闭</p>
      </article>
      <div v-if="isDocument && !isNotice" class="document-following"><span>联系与反馈</span><p>补充说明之后保留平台联系方式及反馈说明。</p></div>
    </div>
    <p class="preview-caption">{{ isPopup ? '局部弹窗，首页四周仍可见；右上角可随时关闭。' : isBanner ? '首页横幅：素材与文案分区，标题和按钮不压在图片上。' : isNotice ? '平台公告：标题在前，展开后展示配图或视频、正文及相关页面链接。' : '首页与“我的”共用服务说明页。此处仅预览当前补充条目；基础章节和联系与反馈仍保留，不会被此条内容替换。' }}</p>
    <p v-if="content.linkUrl" class="preview-caption">{{ isDocument ? '相关页面链接仅示意，预览不执行跳转。' : '按钮为展示示意，预览不执行跳转。' }}</p>
    <p v-else-if="isBanner" class="preview-caption">未配置跳转，首页点击后打开该条内容的完整弹窗。</p>
  </div>
</template>

<style scoped>
.content-preview{width:375px;max-width:100%;margin:0 auto}.phone-viewport{position:relative;min-height:540px;background:#f5f6f7;border:1px solid #e1e5e2;border-radius:12px;overflow:hidden;color:#222a26}.phone-home-title{height:52px;padding:16px;font-size:16px;font-weight:600;background:#fff}.phone-map-placeholder{height:150px;position:relative;background:#eef2ef;overflow:hidden}.phone-map-placeholder span{position:absolute;display:block;background:#fff;transform:rotate(-25deg);width:140%;height:16px;left:-20%;top:50px}.phone-map-placeholder span:nth-child(2){transform:rotate(55deg);top:80px}.phone-map-placeholder span:nth-child(3){transform:rotate(-25deg);top:120px}.phone-booking-placeholder{padding:20px 16px;background:#fff;margin-bottom:12px}.phone-booking-placeholder i{display:block;height:12px;width:70%;background:#eef1ef;margin-bottom:16px;border-radius:2px}.phone-booking-placeholder i:last-child{width:100%;height:36px;margin-bottom:0}.preview-backdrop{position:absolute;inset:0;background:#18241d73}.preview-content{background:#fff;overflow:hidden;overflow-wrap:anywhere}.preview-popup-content{position:relative;width:calc(100% - 48px);margin:-264px auto 28px;border-radius:12px;display:flex;flex-direction:column;max-height:490px}.popup-topline{height:40px;min-height:40px;display:flex;align-items:center;justify-content:space-between;padding-left:16px;font-size:11px;color:#6b736e}.preview-close{width:44px;height:40px;display:flex;align-items:center;justify-content:center;padding:0;border:0;background:transparent;color:#5f6862}.preview-content-scroll{overflow-y:auto;min-height:0;overscroll-behavior:contain}.preview-media{position:relative;background:#f3f5f3;display:flex;align-items:center;justify-content:center;overflow:hidden;aspect-ratio:4 / 3}.preview-media img,.preview-media video{width:100%;height:100%;display:block;object-fit:contain}.preview-media-video{aspect-ratio:16 / 9;background:#17221c}.preview-media-placeholder{display:flex;align-items:center;justify-content:center;flex-direction:column;gap:8px;padding:16px;color:#77817a;font-size:12px;text-align:center}.preview-media-video .preview-media-placeholder{color:#d0d8d3}.preview-media-placeholder small{font-size:11px}.preview-copy{padding:20px}.preview-copy h3{margin:0;font-size:20px;line-height:28px;font-weight:600;color:#202b24}.preview-body{white-space:pre-wrap;line-height:24px;font-size:14px;color:#59645d;margin:12px 0 0}.placeholder-copy{color:#89928c}.preview-action-area{padding:0 20px 20px;flex-shrink:0}.preview-action{display:flex;min-height:44px;align-items:center;justify-content:center;gap:8px;background:#16794d;color:#fff;border-radius:6px;font-size:15px;font-weight:500;line-height:22px;padding:10px 12px}.preview-countdown-note{font-size:11px;color:#7f8882;text-align:center;margin:-8px 16px 12px}.preview-banner-content{margin:0 16px 24px;border-radius:8px;border:1px solid #edf0ed}.preview-banner-content .preview-media{aspect-ratio:2.4 / 1}.preview-banner-content .preview-copy{padding:14px 16px 8px}.preview-banner-content .preview-copy h3{font-size:16px;line-height:23px;display:-webkit-box;-webkit-box-orient:vertical;-webkit-line-clamp:2;overflow:hidden}.preview-banner-content .preview-body{font-size:13px;line-height:21px;margin-top:8px;display:-webkit-box;-webkit-box-orient:vertical;-webkit-line-clamp:2;overflow:hidden}.banner-ad-label{font-size:10px;line-height:16px;color:#869087;margin-bottom:4px}.banner-action-area{padding:4px 16px 14px}.banner-action-area .preview-action{justify-content:flex-start;min-height:28px;background:transparent;color:#16794d;font-size:13px;padding:0}.preview-document{margin:16px;border-radius:4px}.preview-document .preview-media{aspect-ratio:auto;min-height:160px}.preview-document .preview-media img,.preview-document .preview-media video{height:auto;max-height:320px}.preview-document .preview-media-video{aspect-ratio:16 / 9}.preview-caption{font-size:12px;line-height:20px;color:#737d76;margin:12px 0 0}.preview-dismissed{display:flex;min-height:540px;flex-direction:column;align-items:center;justify-content:center;border:1px solid #e1e5e2;border-radius:12px;background:#f5f6f7}.preview-dismissed p{font-size:14px;color:#737d76}
.phone-popup{height:600px}.preview-popup-content{position:absolute;left:24px;top:50%;transform:translateY(-50%);margin:0;max-height:calc(100% - 48px)}.preview-banner-content .preview-media-video{aspect-ratio:16 / 9}.preview-video-cover{position:relative;width:100%;height:100%;border:0;background:#17221c;color:#fff;display:flex;align-items:center;justify-content:center;flex-direction:column;gap:8px;padding:0}.preview-video-cover>img{position:absolute;inset:0;width:100%;height:100%;object-fit:contain}.preview-play-icon{position:relative;z-index:1;display:flex;width:48px;height:48px;align-items:center;justify-content:center;border-radius:50%;background:#0007;border:1px solid #ffffffa6}.preview-play-icon:after{content:'';display:block;width:0;height:0;border-top:8px solid transparent;border-bottom:8px solid transparent;border-left:12px solid #fff;margin-left:4px}.preview-video-cover>small{position:relative;z-index:1;font-size:12px;line-height:20px;text-shadow:0 1px 3px #000}.playback-hint{margin:0;padding:10px 16px;font-size:12px;line-height:20px;color:#735b26;background:#fff8e8}
.phone-document{background:#fff;padding-bottom:24px}.phone-notice{background:#f5f6f7}.document-page-header{padding:24px 20px 20px}.document-page-header h2{margin:0;font-size:22px;line-height:32px;font-weight:600}.document-page-header p{margin:4px 0 0;color:#646a73;font-size:14px;line-height:24px}.document-context{margin:0 20px 16px;color:#646a73;font-size:13px;line-height:22px}.document-context p{margin:4px 0 0}.document-context h3{margin:20px 0 4px;color:#222a26;font-size:18px;line-height:28px;font-weight:600}.document-context .document-location{border-top:1px solid #eceeef;border-bottom:1px solid #eceeef;padding:12px 0}.document-list-label{font-size:14px;line-height:24px}.preview-document{margin:0 20px;border-radius:0;padding:0 0 20px;border-bottom:1px solid #eceeef}.phone-notice .preview-document{background:#fff;padding:16px;border:0}.preview-document .document-item-title{margin:0 0 12px;font-size:17px;line-height:28px;font-weight:600}.preview-document .preview-copy{padding:0}.preview-document .preview-body{font-size:15px;line-height:27px;color:#343a40}.preview-document .preview-content-scroll{overflow:visible}.preview-document .preview-media-video .preview-video-cover>img{height:100%}.document-action-area{padding:8px 0 0}.document-action-area .preview-action{display:inline-flex;justify-content:flex-start;background:transparent;color:#116c49;min-height:44px;line-height:24px;font-size:15px;font-weight:500;padding:10px 0;border-radius:0}.document-following{margin:24px 20px 0}.document-following>span{font-size:18px;line-height:28px;font-weight:600}.document-following p{margin:8px 0 0;color:#646a73;font-size:13px;line-height:22px}
@media(max-width:800px){.phone-viewport,.preview-dismissed{min-height:480px}.phone-popup{height:560px}.preview-popup-content{max-height:calc(100% - 48px)}.preview-copy{padding:16px}.preview-copy h3{font-size:18px;line-height:26px}.preview-action-area{padding:0 16px 16px}}
@media(max-width:800px){.document-action-area{padding:8px 0 0}}
</style>
