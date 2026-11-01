<script setup lang="ts">
import { computed, getCurrentInstance, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { Data } from '../api'
import { backendResourceUrl } from '../api-base'
import { adMediaHeight, adMediaKind } from '../ad-content'
import AppIcon from './AppIcon.vue'

const props = defineProps<{ item: Data; placement: 'popup' | 'banner'; active: boolean }>()
const emit = defineEmits<{ resize: [] }>()
const instance = getCurrentInstance(), videoId = `ad-video-${instance?.uid ?? Date.now()}`
const system = uni.getSystemInfoSync()
const width = ref(Math.min(system.windowWidth - (props.placement === 'popup' ? 80 : 32), props.placement === 'popup' ? 336 : 718))
const viewportHeight = ref(system.windowHeight), failed = ref(false), loaded = ref(false), posterFailed = ref(false)
const started = ref(false), buffering = ref(false), mediaVersion = ref(0)
const kind = computed(() => adMediaKind(props.item))
const source = computed(() => backendResourceUrl(String(props.item.mediaUrl || '')))
const poster = computed(() => props.item.posterUrl && !posterFailed.value ? backendResourceUrl(String(props.item.posterUrl)) : '')
const height = computed(() => adMediaHeight(width.value, viewportHeight.value, props.placement, kind.value))
let alive = true, wantsPlayback = false
function measure() {
  nextTick(() => {
    if (!alive) return
    uni.createSelectorQuery().in(instance?.proxy).select('.ad-media').boundingClientRect(rect => {
      if (alive && rect && !Array.isArray(rect) && typeof rect.width === 'number' && rect.width > 0) width.value = rect.width
    }).exec()
  })
}
function pause() {
  if (!started.value) return
  try { uni.createVideoContext(videoId, instance?.proxy).pause() } catch { /* The player may already be unmounted. */ }
}
function stop() { wantsPlayback = false; pause(); started.value = false; buffering.value = false }
function playNative() {
  const version = mediaVersion.value
  nextTick(() => {
    if (!alive || !props.active || !started.value || !wantsPlayback || version !== mediaVersion.value) return
    // The H5 adapter discards HTMLMediaElement.play()'s promise. Handle it here
    // so a rejected request never produces an unhandled browser error.
    // #ifdef H5
    const player = document.getElementById(videoId)?.querySelector('video')
    if (player) void player.play().then(() => {
      if (!alive || !props.active || !started.value || version !== mediaVersion.value || document.getElementById(videoId)?.querySelector('video') !== player) player.pause()
    }).catch(() => {
      if (!alive || !props.active || !started.value || version !== mediaVersion.value || document.getElementById(videoId)?.querySelector('video') !== player) return
      wantsPlayback = false; buffering.value = false
      if (player.error) fail()
      else uni.showToast({ title: '请点击视频播放按钮继续', icon: 'none' })
    })
    return
    // #endif
    // #ifndef H5
    try { uni.createVideoContext(videoId, instance?.proxy).play() } catch { /* Native controls remain available if the player is not ready yet. */ }
    // #endif
  })
}
function play() {
  if (!props.active || kind.value !== 'VIDEO') return
  failed.value = false; started.value = true; buffering.value = false; wantsPlayback = true
  playNative()
}
function playing() { wantsPlayback = false; buffering.value = false }
function paused() { wantsPlayback = false; buffering.value = false }
function fail() { stop(); failed.value = true; emit('resize') }
function retry() {
  failed.value = false; loaded.value = false; posterFailed.value = false; mediaVersion.value++
  if (kind.value === 'VIDEO') play()
}
function previewImage() {
  if (failed.value || !source.value) return
  uni.previewImage({ urls: [source.value], current: source.value, fail: () => uni.showToast({ title: '图片暂时无法打开，请稍后重试', icon: 'none' }) })
}
function resize(event: { size: { windowHeight: number } }) { viewportHeight.value = event.size.windowHeight; measure() }
watch(() => [props.item.id, props.item.mediaUrl, props.item.mediaType, props.item.posterUrl], () => {
  stop(); failed.value = false; loaded.value = false; posterFailed.value = false; mediaVersion.value++; measure()
})
watch(() => props.active, active => { if (!active) stop() })
watch(height, () => nextTick(() => { if (alive) emit('resize') }))
onMounted(() => { measure(); uni.onWindowResize(resize) })
onBeforeUnmount(() => { alive = false; stop(); uni.offWindowResize(resize) })
</script>
<template>
  <view v-if="kind !== 'TEXT'" class="ad-media" :class="{'video-media':kind==='VIDEO'}" :style="{height:height+'px'}">
    <view v-if="failed" class="ad-media-failure">
      <AppIcon name="info" :size="24" color="muted"/>
      <text>{{kind==='VIDEO'?'视频暂时无法播放':'图片暂时无法加载'}}</text>
      <button class="app-button plain media-retry" @click="retry">重新加载</button>
    </view>
    <template v-else-if="kind==='IMAGE'">
      <image :key="mediaVersion" class="ad-picture" :src="source" mode="aspectFit" :aria-label="item.title || '广告图片'" @load="loaded=true" @error="fail" @click="previewImage"/>
      <text v-if="!loaded" class="media-loading" role="status">图片加载中…</text>
      <button v-else class="app-button picture-preview" @click.stop="previewImage">查看大图</button>
    </template>
    <template v-else>
      <video v-if="started && active" :id="videoId" :key="mediaVersion" class="ad-video" :src="source" :poster="poster" :title="item.title" controls :autoplay="false" :loop="false" object-fit="contain" :show-fullscreen-btn="true" :auto-pause-if-navigate="true" :auto-pause-if-open-native="true" @loadedmetadata="playNative" @play="playing" @timeupdate="buffering=false" @waiting="buffering=true" @pause="paused" @ended="paused" @error="fail" @rendererror="fail"/>
      <view v-else class="video-cover">
        <image v-if="poster" class="video-poster" :src="poster" mode="aspectFit" @error="posterFailed=true"/>
        <button class="app-button video-play" :disabled="!active" :aria-label="'播放视频：'+(item.title||'广告')" @click="play"><view class="play-symbol"/><text>播放视频</text></button>
      </view>
      <text v-if="started && buffering" class="video-buffering" role="status">视频加载中…</text>
    </template>
  </view>
</template>
<style scoped>
.ad-media{position:relative;width:100%;background:#f0f2f4;overflow:hidden;flex-shrink:0}.ad-picture,.ad-video,.video-poster,.video-cover{display:block;width:100%;height:100%}.video-media,.video-cover{background:#18201d}.video-cover{position:relative}.video-poster{opacity:.88}.video-play{position:absolute;inset:0;display:flex;flex-direction:column;gap:12px;align-items:center;justify-content:center;width:100%;color:#fff;background:transparent;font-size:14px;text-shadow:0 1px 2px #222;border-radius:0}.video-play[disabled]{color:#fff;background:transparent;opacity:1}.play-symbol{width:0;height:0;border-top:12px solid transparent;border-bottom:12px solid transparent;border-left:19px solid #fff;margin-left:4px;filter:drop-shadow(0 1px 3px rgba(0,0,0,.35))}.picture-preview{position:absolute;right:8px;bottom:8px;min-height:36px;padding:6px 10px;border-radius:4px;background:rgba(255,255,255,.94);color:#42474e;font-size:12px;line-height:24px}.media-loading{position:absolute;inset:0;display:flex;align-items:center;justify-content:center;color:#646a73;font-size:13px;pointer-events:none}.ad-media-failure{height:100%;display:flex;align-items:center;justify-content:center;flex-direction:column;gap:8px;padding:12px;color:#646a73;font-size:13px}.media-retry{min-height:40px;padding:4px 12px;font-size:13px}.video-buffering{position:absolute;top:12px;left:12px;background:rgba(0,0,0,.64);color:#fff;padding:4px 8px;font-size:12px;pointer-events:none;border-radius:4px}.senior .picture-preview,.senior .video-play,.senior .media-retry{font-size:16px;min-height:44px}.senior .ad-media-failure{font-size:16px}
</style>
